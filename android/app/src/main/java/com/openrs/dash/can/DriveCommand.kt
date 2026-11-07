package com.openrs.dash.can

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.LogText
import kotlinx.coroutines.delay

/**
 * Result of a drive mode command attempt.
 */
sealed class DriveCommandResult {
    object Success : DriveCommandResult()
    data class Failed(val message: String) : DriveCommandResult()
    object Busy : DriveCommandResult()
    object CorrectionFailed : DriveCommandResult()
    object NoConfirmation : DriveCommandResult()
    /** The link dropped or was replaced while waiting for the car — nothing is confirmed, no correction is sent. */
    object ConnectionLost : DriveCommandResult()
}

/** Shared in-flight command state. Header reads this to gate MODE-pill pulse. */
val driveModePending = mutableStateOf<DriveMode?>(null)

/**
 * Shared mode-change confirmation state. Set by the dock on a successful change,
 * cleared ~1.2s later. Header border + bottom nav indicator read this to render
 * a short ambient tint in the newly-selected mode's color.
 */
val modeChangeBreath = mutableStateOf<DriveMode?>(null)

/** Mode-specific success haptic. Reinforces that the car actually switched. */
fun successHapticFor(mode: DriveMode): HapticFeedbackType = when (mode) {
    DriveMode.NORMAL -> HapticFeedbackType.VirtualKey
    DriveMode.SPORT  -> HapticFeedbackType.Confirm
    DriveMode.TRACK  -> HapticFeedbackType.Reject
    DriveMode.DRIFT  -> HapticFeedbackType.GestureEnd
    else             -> HapticFeedbackType.Confirm
}

/**
 * Executes the full drive mode change flow:
 *   1. Send command via [FirmwareCommandSender] (WiFi REST or BLE AT+FRS)
 *   2. 2-second settling delay
 *   3. 15-second CAN polling for confirmation
 *   4. Auto-correction on overshoot
 *
 * The adapter's OK is only acceptance. Confirmation is the car reporting [targetMode] in a resolved 0x1B0 frame
 * stamped AFTER the request ([STAMP_TAG]) on the SAME connection ([connectionId]) — a cached / held matching value never
 * confirms, a transient mode on the way (Track via Sport) isn't judged until the window ends, and a reconnect while
 * waiting is [DriveCommandResult.ConnectionLost] with no correction. The report must be corroborated after the request
 * (#252, [reportedAfter]): two agreeing 0x1B0 reports, and for Sport / Track two agreeing 0x420 details before them — a
 * detail cached before the request, a 0x420 flip after the last 0x1B0, an undocumented 0x420 or any single frame never
 * confirms, and never triggers the correction. A matching report says what the car is in after the request, not that
 * the request caused it. Timing and the single correction are unchanged.
 *
 * Returns a [DriveCommandResult] — callers map it to UI feedback.
 */
suspend fun executeDriveModeChange(
    sender: FirmwareCommandSender,
    targetMode: DriveMode,
    currentMode: DriveMode,
    state: () -> VehicleState = { OpenRSDashApp.instance.vehicleState.value },
    connectionId: () -> Int = { com.openrs.dash.service.CanDataService.link.generation },
    clock: () -> Long = System::currentTimeMillis,
    firmwareLabel: () -> String = { OpenRSDashApp.instance.firmwareVersionLabel.value },
): DriveCommandResult {
    val link = connectionId()
    DiagnosticLogger.event("DM_CMD",
        "Pre-flight: current=$currentMode, modeDetail=0x${CanDecoder.modeDetail420Hex}, fw=${firmwareLabel()}")
    DiagnosticLogger.event("DM_CMD",
        "Sending driveMode=${targetMode.toFirmwareInt()} (${targetMode.label})")

    val requestedAt = clock()
    val result = sender.setDriveMode(targetMode.toFirmwareInt())
    if (result.isFailure) {
        val ex = result.exceptionOrNull()
        DiagnosticLogger.event("DM_CMD", "FAILED: ${LogText.sanitize(ex?.message ?: "no message")}")
        return if (ex is BusyException) DriveCommandResult.Busy
               else DriveCommandResult.Failed(ex?.message ?: "Drive mode command failed")
    }

    DiagnosticLogger.event("DM_CMD", "ACCEPTED by the adapter (${targetMode.label}) — not yet confirmed by the car")

    // Settling delay: firmware needs time to press the mode button and
    // ECU needs time to broadcast the new mode on 0x420 (~600 ms interval).
    // SLCAN data showed up to 4s firmware delay on cold start.
    delay(2_000)

    // Watch CAN for confirmation (up to 15s after settling).
    when (awaitMode(targetMode, requestedAt, link, state, connectionId)) {
        Wait.CONFIRMED -> {
            DiagnosticLogger.event("DM_CMD", "CONFIRMED by CAN: ${targetMode.label} reported " +
                "${clock() - requestedAt} ms after the request (the car's state after it — not proof the request caused it)")
            return DriveCommandResult.Success
        }
        Wait.LINK_CHANGED -> return connectionLost(targetMode)
        Wait.TIMED_OUT -> Unit
    }

    val live = state()
    val landed = live.driveMode
    // Only a mode the car reported after the request can be an overshoot — never a held value or unresolved evidence
    val landedFresh = reportedAfter(live, landed, requestedAt)

    // Mode changed, but to the wrong one — auto-correct.
    if (landedFresh && landed != targetMode && landed != DriveMode.UNKNOWN && landed != currentMode) {
        DiagnosticLogger.event("DM_CMD",
            "Overshoot: landed=${landed.label}, expected=${targetMode.label} — auto-correcting")

        val correctedAt = clock()
        val retry = sender.setDriveMode(targetMode.toFirmwareInt())
        if (retry.isSuccess) {
            DiagnosticLogger.event("DM_CMD", "Correction ACCEPTED by the adapter — not yet confirmed by the car")
            delay(2_000)
            when (awaitMode(targetMode, correctedAt, link, state, connectionId)) {
                Wait.CONFIRMED -> {
                    DiagnosticLogger.event("DM_CMD", "Auto-correction succeeded — CONFIRMED by CAN " +
                        "${clock() - correctedAt} ms after the correction")
                    return DriveCommandResult.Success
                }
                Wait.LINK_CHANGED -> return connectionLost(targetMode)
                Wait.TIMED_OUT -> {
                    val rl = state()
                    DiagnosticLogger.event("DM_CMD",
                        "Auto-correction failed (current=${rl.driveMode}, target=$targetMode)")
                    return DriveCommandResult.CorrectionFailed
                }
            }
        }
    }

    DiagnosticLogger.event("DM_CMD",
        "NOT CONFIRMED — adapter accepted, no CAN report of ${targetMode.label} after the request within 17s " +
        "(current=$landed${if (landedFresh) "" else ", not reported since the request"}, target=$targetMode, " +
        "modeDetail420=0x${CanDecoder.modeDetail420Hex})")
    return DriveCommandResult.NoConfirmation
}

/** Freshness tag for drive mode — stamped by resolved 0x1B0 frames (CanDecoder: a changed mode at once, unchanged ≤ 1 s). */
const val STAMP_TAG = "driveMode"

private enum class Wait { CONFIRMED, LINK_CHANGED, TIMED_OUT }

/**
 * [mode] rests on corroborated reports received after [requestedAt] (#252) — no single frame is enough:
 *  - the current run of resolved 0x1B0 reports is [mode], and two of its reports are after the request
 *    ([CanDecoder.MODE_RUN_PREV_TAG] and the "driveMode" stamp);
 *  - for Sport / Track, which 0x1B0 alone can't tell apart, the latest report resolved with a 0x420 detail corroborated by
 *    the agreeing detail before it, both after the request ([CanDecoder.MODE_RESOLVED_PAIR_TAG]).
 * The same rule gates the overshoot correction, so a single wrong-mode frame never sends a corrective command.
 */
private fun reportedAfter(vs: VehicleState, mode: DriveMode, requestedAt: Long): Boolean {
    val s = vs.fieldLastUpdateMs
    if (!s.containsKey(CanDecoder.modeRunTag(mode))) return false
    if ((s[STAMP_TAG] ?: Long.MIN_VALUE) <= requestedAt) return false
    if ((s[CanDecoder.MODE_RUN_PREV_TAG] ?: Long.MIN_VALUE) <= requestedAt) return false
    if (mode != DriveMode.SPORT && mode != DriveMode.TRACK) return true
    return (s[CanDecoder.MODE_RESOLVED_PAIR_TAG] ?: Long.MIN_VALUE) > requestedAt
}

/** 150 × 100 ms: the requested mode in a frame after [requestedAt] on connection [link]. */
private suspend fun awaitMode(
    target: DriveMode, requestedAt: Long, link: Int, state: () -> VehicleState, connectionId: () -> Int,
): Wait {
    for (i in 0 until 150) {
        delay(100)
        val live = state()
        if (!live.isConnected || connectionId() != link) return Wait.LINK_CHANGED
        if (live.driveMode == target && reportedAfter(live, target, requestedAt)) return Wait.CONFIRMED
    }
    return Wait.TIMED_OUT
}

private fun connectionLost(target: DriveMode): DriveCommandResult {
    DiagnosticLogger.event("DM_CMD", "CONNECTION CHANGED while waiting for ${target.label} — not confirmed, no correction sent")
    return DriveCommandResult.ConnectionLost
}

// ── Legacy convenience overload ──────────────────────────────────────────────
// Used during transition — callers that still pass ctx+host create a WiFiFirmwareApi inline.

@Deprecated("Use FirmwareCommandSender overload", ReplaceWith("executeDriveModeChange(WiFiFirmwareApi(ctx, host), targetMode, currentMode)"))
suspend fun executeDriveModeChange(
    ctx: Context,
    host: String,
    targetMode: DriveMode,
    currentMode: DriveMode
): DriveCommandResult = executeDriveModeChange(WiFiFirmwareApi(ctx, host), targetMode, currentMode)
