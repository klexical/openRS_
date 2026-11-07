package com.openrs.dash.can

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.LogText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result of a drive mode command attempt.
 */
sealed class DriveCommandResult {
    object Success : DriveCommandResult()
    data class Failed(val message: String) : DriveCommandResult()
    object Busy : DriveCommandResult()
    object NoConfirmation : DriveCommandResult()
    /** The car reported another mode after the request (#252). Nothing further was sent: only the driver's tap on [offer] can send again. */
    data class LandedElsewhere(val offer: DriveModeOffer) : DriveCommandResult()
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
 *
 * One call sends one command (#252): a timeout or a landing in another mode never sends another by itself, so a mode the
 * driver selected by hand during the wait stands. A landing elsewhere comes back as [DriveCommandResult.LandedElsewhere],
 * and only the driver's tap on its offer starts a new request ([askDriver]).
 *
 * The adapter's OK is only acceptance. Confirmation is the car reporting [targetMode] in a resolved 0x1B0 frame
 * stamped AFTER the request ([STAMP_TAG]) on the SAME connection ([connectionId]) — a cached / held matching value never
 * confirms, a transient mode on the way (Track via Sport) isn't judged until the window ends, and a reconnect while
 * waiting is [DriveCommandResult.ConnectionLost]. The report must be corroborated after the request
 * (#252, [reportedAfter]): two agreeing 0x1B0 reports, and for Sport / Track two agreeing 0x420 details before them — a
 * detail cached before the request, a 0x420 flip after the last 0x1B0, an undocumented 0x420 or any single frame never
 * confirms, and never makes an offer. A matching report says what the car is in after the request, not that
 * the request caused it. Timing is unchanged.
 *
 * Returns a [DriveCommandResult] — callers map it to UI feedback.
 */
suspend fun executeDriveModeChange(
    sender: FirmwareCommandSender,
    targetMode: DriveMode,
    currentMode: DriveMode,
    state: () -> VehicleState = { OpenRSDashApp.instance.vehicleState.value },
    connectionId: () -> Int = { com.openrs.dash.service.CanDataService.link.connectionEpoch },
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

    // The car is in another mode, by this request or by the driver's hand: the app can't tell which, so it only asks.
    if (landedFresh && landed != targetMode && landed != DriveMode.UNKNOWN && landed != currentMode) {
        DiagnosticLogger.event("DM_CMD",
            "LANDED ELSEWHERE: car reports ${landed.label}, requested ${targetMode.label} — nothing further sent, driver asked")
        return DriveCommandResult.LandedElsewhere(DriveModeOffer(
            targetMode, landed, link, live.fieldLastUpdateMs[CanDecoder.modeRunTag(landed)] ?: 0L))
    }

    DiagnosticLogger.event("DM_CMD",
        "NOT CONFIRMED — adapter accepted, no CAN report of ${targetMode.label} after the request in " +
        "${clock() - requestedAt} ms (window nominally 17 s) " +
        "(current=$landed${if (landedFresh) "" else ", not reported since the request"}, target=$targetMode, " +
        "modeDetail420=0x${CanDecoder.modeDetail420Hex})")
    return DriveCommandResult.NoConfirmation
}

/**
 * What the driver is asked after a landing elsewhere (#252). It holds only while the car is still on [observed], in the same run of
 * reports, on the same connection ([connection], the link's connection epoch), so a tap can't send after a reconnect or a further change.
 */
data class DriveModeOffer(val requested: DriveMode, val observed: DriveMode, val connection: Int, val observedRunStart: Long) {
    fun stands(vs: VehicleState, connectionNow: Int): Boolean =
        vs.isConnected && connectionNow == connection && vs.driveMode == observed &&
            vs.fieldLastUpdateMs[CanDecoder.modeRunTag(observed)] == observedRunStart
}

/** How long an unanswered offer stays up. A default the owner can change. */
const val DRIVE_MODE_OFFER_TIMEOUT_MS = 30_000L
private const val OFFER_POLL_MS = 100L

/**
 * Puts [offer] to the driver through [ask] (true = tapped), and takes it away on the timeout or as soon as it stops holding. True only for a
 * tap on an offer that still holds at that moment: the caller then makes a normal new request. Otherwise nothing is sent. Logs the answer.
 */
suspend fun askDriver(
    offer: DriveModeOffer,
    ask: suspend () -> Boolean,
    state: () -> VehicleState = { OpenRSDashApp.instance.vehicleState.value },
    connectionId: () -> Int = { com.openrs.dash.service.CanDataService.link.connectionEpoch },
): Boolean = coroutineScope {
    val asking = async { withTimeoutOrNull(DRIVE_MODE_OFFER_TIMEOUT_MS) { ask() } }
    val watch = launch {
        while (offer.stands(state(), connectionId())) delay(OFFER_POLL_MS)
        asking.cancel()
    }
    val tapped = try { asking.await() } catch (e: CancellationException) { ensureActive(); null }
    watch.cancel()
    val send = tapped == true && offer.stands(state(), connectionId())
    DiagnosticLogger.event("DM_CMD", when {
        send -> "Driver asked for ${offer.requested.label} with the car on ${offer.observed.label}"
        !offer.stands(state(), connectionId()) -> "Offer withdrawn, nothing sent: connection or mode changed"
        tapped == null -> "Offer timed out, nothing sent"
        else -> "Offer dismissed, nothing sent"
    })
    send
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
 * The same rule gates an offer, so a single wrong-mode frame never asks the driver anything.
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
    DiagnosticLogger.event("DM_CMD", "CONNECTION CHANGED while waiting for ${target.label} — not confirmed, nothing further sent")
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
