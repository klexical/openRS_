package com.openrs.dash.can

import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Request capture (#209 Step B, the owner's decisions of 2026-11-14): a switch for one connection. While it is on, the PCM
 * poller sends its next request only after the previous one is answered or [WAIT_MS] has passed, and a PCM DID probe
 * shares that one-at-a-time gate, so each answer's delay can be read from the session's raw log. NRC suppression does not
 * skip a DID while it is on. Every link up and every link down turns it off (automatic reconnects included), and a
 * capture that was on never ends silently: the session log says it ended and why, and the export says when.
 */
class RequestCapture {
    private val _on = MutableStateFlow(false)
    /** Whether the capture is on now (the DIAG switch reads it). */
    val on: StateFlow<Boolean> = _on.asStateFlow()
    private var linkUp = false

    /** The user's switch. Refused (false) with no link up: a capture belongs to a connection. */
    fun set(enabled: Boolean): Boolean {
        val changed = synchronized(this) {
            if (enabled && !linkUp) return false
            (_on.value != enabled).also { _on.value = enabled }
        }
        if (changed) DiagnosticLogger.requestCaptureChanged(enabled, if (enabled) REASON_ON else REASON_SWITCHED_OFF)
        return true
    }

    /** A link came up ([up]) or went down: the switch goes off, and a capture that was on is recorded as ended. */
    fun linkChanged(up: Boolean) {
        val wasOn = synchronized(this) {
            linkUp = up
            _on.value.also { _on.value = false }
        }
        if (wasOn) DiagnosticLogger.requestCaptureChanged(false, if (up) REASON_RECONNECTED else REASON_LINK_ENDED)
    }

    companion object {
        /** The app's one switch: the connection reads it, DIAG shows and sets it. */
        val shared = RequestCapture()
        /** How long the poller waits for each PCM answer while the capture is on (owner, 2026-11-14). */
        const val WAIT_MS = 2_000L
        const val REASON_ON = "switched on"
        const val REASON_SWITCHED_OFF = "switched off"
        const val REASON_LINK_ENDED = "the connection ended"
        const val REASON_RECONNECTED = "a new connection started"
    }
}
