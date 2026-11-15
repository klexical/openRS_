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
    /** The current link's token ([linkUp]); null with no link. */
    private var current: Any? = null
    /** Counts switch-ons: a connection tells a new capture from the one it is in (its first turn waits for older requests). */
    @Volatile var switchOns = 0
        private set

    /**
     * The user's switch. Refused (false) with no link up: a capture belongs to a connection. Every change is recorded
     * inside this lock, so a switch-on racing a link drop can't record "on" after "off" (QA's F1); the logger never calls
     * back into this object, so the lock order (this, then the logger's) is fixed.
     */
    fun set(enabled: Boolean): Boolean = synchronized(this) {
        if (enabled && !linkUp) return false
        if (_on.value != enabled) {
            _on.value = enabled
            if (enabled) switchOns++
            DiagnosticLogger.requestCaptureChanged(enabled, if (enabled) REASON_ON else REASON_SWITCHED_OFF)
        }
        true
    }

    /** A link came up: the switch starts off (a capture still on is recorded as ended). Returns the link's token. */
    fun linkUp(): Any {
        val token = Any()
        synchronized(this) {
            current = token; linkUp = true
            if (_on.value) { _on.value = false; DiagnosticLogger.requestCaptureChanged(false, REASON_RECONNECTED) }
        }
        return token
    }

    /**
     * The link of [token] ended, or (null) the user stopped or replaced the connection: the switch goes off, and a capture
     * that was on is recorded as ended for [reason].
     */
    fun linkDown(token: Any?, reason: String) {
        synchronized(this) {
            // An old link's late end (a manual reconnect cancels the old connection, whose end can come after the new
            // link is up) leaves the new link alone (the spec review's C6)
            if (token != null && token !== current) return
            current = null; linkUp = false
            if (_on.value) { _on.value = false; DiagnosticLogger.requestCaptureChanged(false, reason) }
        }
    }

    companion object {
        /** The app's one switch: the connection reads it, DIAG shows and sets it. */
        val shared = RequestCapture()
        /** How long the poller waits for each PCM answer while the capture is on (owner, 2026-11-14). */
        const val WAIT_MS = 2_000L
        /**
         * After a PCM request got no answer within [WAIT_MS], nothing more goes to the PCM until this long after that
         * request was written, so a late answer lands before the next request and is that request's (owner, 2026-11-14,
         * the spec review's C1). A chosen value: the usual default for a response-pending wait in diagnostic libraries
         * (P2*); no standard was read. A first answer has no such bound to borrow.
         */
        const val LATE_WINDOW_MS = 5_000L
        const val REASON_ON = "switched on"
        const val REASON_SWITCHED_OFF = "switched off"
        const val REASON_LINK_ENDED = "the connection ended"
        const val REASON_RECONNECTED = "a new connection started"
        const val REASON_STOPPED = "the connection was stopped"
    }
}
