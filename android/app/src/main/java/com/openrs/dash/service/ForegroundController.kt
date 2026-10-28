package com.openrs.dash.service

/**
 * Foreground-service bookkeeping for [CanDataService].
 *
 * The activity only binds the service (BIND_AUTO_CREATE): with the UI open it runs as a plain bound
 * service — not started, not foreground. Real work (an adapter connection) [promote]s it: the service
 * starts itself, so it outlives the activity's binding, and immediately calls startForeground. When the
 * work ends, [demote] leaves the foreground and drops the started state, so with no UI bound the service
 * stops instead of idling in the background (and never shows a notification with nothing to do).
 *
 * Why: MainActivity called startForegroundService() on every onCreate while the service only called
 * startForeground() when a connection started — recreating the activity while disconnected or in demo
 * broke that contract and Android raised an ANR ("did not then call Service.startForeground()").
 */
internal class ForegroundController(private val host: Host) {

    interface Host {
        /** startForegroundService(self) — makes the service a started service. [enterForeground] follows at once. */
        fun startSelf()
        /** startForeground(notification). Also refreshes the notification when already foreground. */
        fun enterForeground()
        /** stopForeground(REMOVE). */
        fun exitForeground()
        /** stopSelf() — the service then lives only while a client is bound. */
        fun stopSelf()
    }

    var isForeground = false
        private set
    private var started = false

    /**
     * Start (if needed) and go foreground. Throws what the host throws — e.g. a background start that
     * Android 12+ refuses — after undoing a self-start so no started-but-not-foreground service is left.
     */
    fun promote() {
        if (!started) {
            host.startSelf()
            started = true
        }
        try {
            host.enterForeground()
        } catch (e: Exception) {
            host.stopSelf()
            started = false
            throw e
        }
        isForeground = true
    }

    /** Leave the foreground and the started state. No-op when neither applies. */
    fun demote() {
        if (isForeground) {
            host.exitForeground()
            isForeground = false
        }
        if (started) {
            host.stopSelf()
            started = false
        }
    }
}
