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
        /**
         * startForeground(notification) with connectedDevice, plus location when [location] (#261). Also refreshes the
         * notification, and changes the types, when already foreground.
         */
        fun enterForeground(location: Boolean)
        /** stopForeground(REMOVE). */
        fun exitForeground()
        /** stopSelf() — the service then lives only while a client is bound. */
        fun stopSelf()
    }

    var isForeground = false
        private set
    private var started = false
    /** The foreground currently carries the location type (#261). Cleared by [demote]. */
    var hasLocation = false
        private set
    /** Location is wanted but was not added (app not visible, or refused); reported once until the wish or the type changes. */
    var locationDeferred = false
        private set
    private var addRefused = false   // a refused add is reported once per wish, apart from a deferral
    /** Set by [release]: the service is destroyed, and nothing may start it or change its foreground again (#294). */
    private var released = false

    /**
     * Start (if needed) and go foreground. Throws what the host throws — e.g. a background start that
     * Android 12+ refuses — after undoing a self-start so no started-but-not-foreground service is left.
     */
    fun promote() {
        // A start after the destroy would create a new service instance and post this dead one's notification (#294)
        check(!released) { "service destroyed" }
        if (!started) {
            host.startSelf()
            started = true
        }
        try {
            host.enterForeground(hasLocation)                // a refresh keeps the types it has
        } catch (e: Exception) {
            // A refresh with location refused (e.g. from the background): stay foreground with connectedDevice only
            val fallback = hasLocation && runCatching { host.enterForeground(false) }.isSuccess
            if (!fallback) {
                host.stopSelf()
                started = false
                hasLocation = false
                throw e
            }
            hasLocation = false                              // the caller's applyTypes reports it (deferred / refused)
        }
        isForeground = true
    }

    /** What [applyTypes] did. */
    sealed class TypeChange {
        object None : TypeChange()
        object Added : TypeChange()
        object Dropped : TypeChange()
        /** Location wanted, app not visible: connectedDevice only (reported once). */
        object Deferred : TypeChange()
        /** Location wanted and the system refused it: connectedDevice only. */
        class Refused(val error: Exception) : TypeChange()
        /** Dropping location was refused: the foreground keeps it. */
        class DropRefused(val error: Exception) : TypeChange()
    }

    /**
     * Bring the foreground's types in line with [wantLocation] (#261, [ForegroundTypes]). Only while foreground — never
     * promotes or demotes, so a link end's demote order (N2) is unchanged. Location is added only while [appVisible] (the
     * Android 14+ while-in-use rule); a failed add leaves the foreground as it was (connectedDevice).
     */
    fun applyTypes(wantLocation: Boolean, appVisible: Boolean): TypeChange {
        if (!isForeground) return TypeChange.None
        if (!wantLocation) { locationDeferred = false; addRefused = false }
        return when (ForegroundTypes.next(wantLocation, hasLocation, appVisible)) {
            ForegroundTypes.Next.KEEP -> TypeChange.None
            ForegroundTypes.Next.ADD_LOCATION -> try {
                host.enterForeground(true); hasLocation = true; locationDeferred = false; addRefused = false; TypeChange.Added
            } catch (e: Exception) {
                locationDeferred = true
                if (addRefused) TypeChange.None else { addRefused = true; TypeChange.Refused(e) }
            }
            ForegroundTypes.Next.DEFER_LOCATION ->
                if (locationDeferred) TypeChange.None else { locationDeferred = true; TypeChange.Deferred }
            ForegroundTypes.Next.DROP_LOCATION -> try {
                host.enterForeground(false); hasLocation = false; TypeChange.Dropped
            } catch (e: Exception) { TypeChange.DropRefused(e) }
        }
    }

    /**
     * The service is being destroyed (#294): leave the foreground at once and end the bookkeeping as not foreground and not
     * started, so a demote still deferred for a drive's points (#262), which the destroy cancels, is not needed, and a
     * collector body that runs after the destroy finds nothing to re-type or refresh. No stopSelf: the service is already
     * stopping. Every later [promote] throws, and [applyTypes] and [demote] do nothing.
     */
    fun release() {
        clearLocationState()
        if (isForeground) {
            host.exitForeground()
            isForeground = false
        }
        started = false
        released = true
    }

    private fun clearLocationState() {
        hasLocation = false
        locationDeferred = false
        addRefused = false
    }

    /** Leave the foreground and the started state. No-op when neither applies. */
    fun demote() {
        clearLocationState()
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

/**
 * #261: the foreground service carries the location type only while a drive records (not paused) with fine location
 * granted, and adds it only while the app is visible. Without it, Android stops delivering while-in-use location to the
 * service once the app leaves the screen (emulator, Android 16: 0.80 points/s in front, ~0 behind).
 */
internal object ForegroundTypes {
    fun wantsLocation(recording: Boolean, paused: Boolean, fineGranted: Boolean): Boolean = recording && !paused && fineGranted

    enum class Next { KEEP, ADD_LOCATION, DEFER_LOCATION, DROP_LOCATION }

    fun next(want: Boolean, has: Boolean, visible: Boolean): Next = when {
        want && has -> Next.KEEP
        want && visible -> Next.ADD_LOCATION
        want -> Next.DEFER_LOCATION
        has -> Next.DROP_LOCATION
        else -> Next.KEEP
    }
}

/** Whether an openRS_ activity is started (visible), set by MainActivity.onStart / onStop. Process-wide; memory only. */
object AppVisibility {
    val visible = kotlinx.coroutines.flow.MutableStateFlow(false)
}
