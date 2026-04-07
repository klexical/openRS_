package com.openrs.dash.can

import android.content.Context
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveMode
import com.openrs.dash.diagnostics.DiagnosticLogger
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
}

/**
 * Executes the full drive mode change flow:
 *   1. Send command via [FirmwareCommandSender] (WiFi REST or BLE AT+FRS)
 *   2. 2-second settling delay
 *   3. 15-second CAN polling for confirmation
 *   4. Auto-correction on overshoot
 *
 * Returns a [DriveCommandResult] — callers map it to UI feedback.
 */
suspend fun executeDriveModeChange(
    sender: FirmwareCommandSender,
    targetMode: DriveMode,
    currentMode: DriveMode
): DriveCommandResult {
    DiagnosticLogger.event("DM_CMD",
        "Pre-flight: current=$currentMode, modeDetail=0x${CanDecoder.modeDetail420Hex}, " +
        "fw=${OpenRSDashApp.instance.firmwareVersionLabel.value}")
    DiagnosticLogger.event("DM_CMD",
        "Sending driveMode=${targetMode.toFirmwareInt()} (${targetMode.label})")

    val result = sender.setDriveMode(targetMode.toFirmwareInt())
    if (result.isFailure) {
        val ex = result.exceptionOrNull()
        DiagnosticLogger.event("DM_CMD", "FAILED: ${ex?.message}")
        return if (ex is BusyException) DriveCommandResult.Busy
               else DriveCommandResult.Failed(ex?.message ?: "Drive mode command failed")
    }

    DiagnosticLogger.event("DM_CMD", "OK")

    // Settling delay: firmware needs time to press the mode button and
    // ECU needs time to broadcast the new mode on 0x420 (~600 ms interval).
    // SLCAN data showed up to 4s firmware delay on cold start.
    delay(2_000)

    // Watch CAN for confirmation (up to 15s after settling).
    var confirmed = false
    for (i in 0 until 150) {
        delay(100)
        val live = OpenRSDashApp.instance.vehicleState.value
        if (live.driveMode == targetMode) {
            confirmed = true
            break
        }
    }

    if (confirmed) return DriveCommandResult.Success

    val live = OpenRSDashApp.instance.vehicleState.value
    val landed = live.driveMode

    // Mode changed, but to the wrong one — auto-correct.
    if (landed != targetMode && landed != DriveMode.UNKNOWN && landed != currentMode) {
        DiagnosticLogger.event("DM_CMD",
            "Overshoot: landed=${landed.label}, expected=${targetMode.label} — auto-correcting")

        val retry = sender.setDriveMode(targetMode.toFirmwareInt())
        if (retry.isSuccess) {
            delay(2_000)
            var corrected = false
            for (j in 0 until 150) {
                delay(100)
                val rl = OpenRSDashApp.instance.vehicleState.value
                if (rl.driveMode == targetMode) { corrected = true; break }
            }
            if (corrected) {
                DiagnosticLogger.event("DM_CMD", "Auto-correction succeeded")
                return DriveCommandResult.Success
            } else {
                val rl = OpenRSDashApp.instance.vehicleState.value
                DiagnosticLogger.event("DM_CMD",
                    "Auto-correction failed (current=${rl.driveMode}, target=$targetMode)")
                return DriveCommandResult.CorrectionFailed
            }
        }
    }

    DiagnosticLogger.event("DM_CMD",
        "No CAN confirmation after 17s (current=$landed, target=$targetMode, " +
        "modeDetail420=0x${CanDecoder.modeDetail420Hex})")
    return DriveCommandResult.NoConfirmation
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
