package com.openrs.dash.service

import android.annotation.SuppressLint
import android.app.ApplicationExitInfo
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.diagnostics.LogText

/** Why a connection ended (#212). Fixed labels: R8 may rename constants, and the export has to read the same in every build. */
enum class LinkStopReason(val label: String) {
    USER_DISCONNECT("USER_DISCONNECT"),
    USER_CANCEL("USER_CANCEL"),
    ATTEMPTS_EXHAUSTED("ATTEMPTS_EXHAUSTED"),
    WIFI_LOST("WIFI_LOST"),
    BLUETOOTH_TURNING_OFF("BLUETOOTH_TURNING_OFF"),
    BLUETOOTH_OFF("BLUETOOTH_OFF"),
    SERVICE_DESTROYED("SERVICE_DESTROYED"),
    DEMO_ENTRY("DEMO_ENTRY"),
    USER_RETRY("USER_RETRY"),
    RETRY_NOT_STARTED("RETRY_NOT_STARTED"),
}

/**
 * Text of the session-log lines that say why a connection stopped (#212). Lines only: nothing here changes a generation, a phase,
 * a retry, the foreground service, a Cancel or a recording. A line names a task removal, not a Recents swipe: the system also
 * removes a task for Clear all, Recents trimming or a disabled package.
 */
internal object LinkStopLog {
    fun rec(recording: Boolean, paused: Boolean) = when {
        recording && paused -> "paused"
        recording -> "recording"
        else -> "off"
    }

    private fun phase(p: LinkPhase) = when (p) {
        LinkPhase.OFF -> "OFF"
        LinkPhase.CONNECTING -> "CONNECTING"
        LinkPhase.CONNECTED -> "CONNECTED"
        LinkPhase.RECONNECTING -> "RECONNECTING"
        LinkPhase.FAILED -> "FAILED"
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    fun stopLine(reason: LinkStopReason, phase: LinkPhase, gen: Int, rec: String, demo: Boolean) =
        "reason=${reason.label} phase=${phase(phase)} gen=$gen rec=$rec demo=${yesNo(demo)}"

    fun taskRemovedLine(phase: LinkPhase, jobActive: Boolean, rec: String, demo: Boolean) =
        "phase=${phase(phase)} job=${yesNo(jobActive)} rec=$rec demo=${yesNo(demo)}"

    /** The system's record of how this app's last process ended. Fields one by one, never `toString()`: it holds process details. */
    fun exitLine(reason: Int, status: Int, importance: Int, ageMs: Long, description: String?) =
        "reason=${exitReasonName(reason)} status=$status importance=$importance age=${ageMs / 1000}s " +
            "description=${LogText.sanitize(description ?: "", 60)}"

    @SuppressLint("InlinedApi")   // compile-time constants; only read on API 30+
    fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> reason.toString()
    }
}
