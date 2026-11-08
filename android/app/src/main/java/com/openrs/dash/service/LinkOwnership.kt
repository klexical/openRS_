package com.openrs.dash.service

import com.openrs.dash.can.LinkPhase

/**
 * Who owns the adapter connection (#212). Every transition — an auto-start's eligibility check and start, an explicit
 * start, a stop / cancel, a phase published by a connection job, a delayed result delivered from one — runs under one
 * lock, and work from a job is applied only while its generation is still the current one. So a network callback that
 * passed its check can't start a connection after a cancel, and an old job's late state, OBD merge or scan result can't
 * land on a newer session.
 *
 * Pure (no Android types) so the interleavings are unit-tested; [CanDataService] keeps one process-wide instance (it
 * must survive the idle service being recreated with the activity) and attaches a persistent [CancelStore], so an
 * explicit Cancel also survives process death.
 */
internal class LinkOwnership {
    private val lock = Any()

    /** Bumped by every begin / end; a job holds the value it began with. */
    @Volatile var generation = 0
        private set

    /**
     * One connection's identity, for a wait that must end on the connection it began on (#252, #125). Bumped by [begin], [end] and
     * every phase change a job publishes: SlcanConnection retries inside one job, so [generation] doesn't move when the link drops
     * and comes back, and neither `isConnected` nor a poll sees a gap that closes between two looks.
     */
    @Volatile var connectionEpoch = 0
        private set

    /** The phase last published (UI flow mirrors it). Read under [transition] for decisions such as cancel. */
    @Volatile var phase: LinkPhase = LinkPhase.OFF
        private set

    /** Persists the user's explicit Cancel (#212) — survives service recreation and process death. */
    interface CancelStore {
        fun load(): Boolean
        fun save(cancelled: Boolean)
    }

    private var store: CancelStore? = null

    /** An explicit user Cancel. Persisted through [CancelStore] until an explicit Connect / Retry clears it. */
    @Volatile var userCancelled = false
        private set

    /** Leaving / entering demo mode: memory only (debug builds), cleared by an explicit connect. */
    @Volatile private var demoHold = false

    /**
     * Wi-Fi / Bluetooth / service-start callbacks must not connect on their own while this is set — after a user
     * Cancel (persisted) or demo mode (this process only). An explicit connect clears both. It is separate from the
     * saved auto-connect preference, which it never changes.
     */
    val manualConnectRequired: Boolean get() = userCancelled || demoHold || taskRemovedHold

    /** A task removal ended an attempt (#212): callbacks wait for the next activity. Memory only, so the next launch still auto-connects. */
    @Volatile private var taskRemovedHold = false

    /**
     * Attach the persistent store and load the saved Cancel. [CanDataService] calls this in `onCreate` before it
     * registers any network callback or tries an automatic start, so a Cancel from an earlier process is honoured.
     */
    fun attachStore(s: CancelStore) = synchronized(lock) {
        store = s
        userCancelled = s.load()
    }

    /** Run a transition (start, stop, reconnect, demo) under the ownership lock. Reentrant. */
    fun <T> transition(block: () -> T): T = synchronized(lock, block)

    /**
     * Callback start: the eligibility check ([blocked], plus [manualConnectRequired]) and [start] run as one step, so a
     * cancel can't fall between them. Returns whether [start] ran; when it didn't, [onHeld] gets the holds that applied
     * at that moment ([holdsNow]), read under the same lock.
     */
    fun autoStart(blocked: () -> Boolean, onHeld: (String) -> Unit = {}, start: () -> Unit): Boolean = synchronized(lock) {
        val demo = blocked()
        if (manualConnectRequired || demo) { onHeld(holdsNow(demo)); false } else { start(); true }
    }

    /** The holds on automatic connects right now, for the refusal line: each one that applies, in a fixed order. */
    private fun holdsNow(demoActive: Boolean): String = listOfNotNull(
        "a saved Cancel".takeIf { userCancelled },
        "demo mode".takeIf { demoHold || demoActive },
        "a task removal, until the app is opened".takeIf { taskRemovedHold },
    ).joinToString(" + ")

    /** Demo mode entered / left: hold automatic connects for the rest of this process. */
    fun requireManualConnect() = synchronized(lock) { demoHold = true }

    /**
     * A task removal (#212): ends only what Cancel would, at the moment it is handled, and never while a drive is being finalized, whose write the foreground
     * service is keeping alive. Unlike [cancel] it saves nothing: a swipe isn't a Cancel. Returns whether it ended the attempt.
     */
    fun cancelOnTaskRemoved(recording: () -> Boolean, finalizing: () -> Boolean, end: () -> Unit): Boolean = synchronized(lock) {
        if (!LinkPresentation.offersCancel(phase, recording()) || finalizing()) false
        else { taskRemovedHold = true; end(); true }
    }

    /** An activity is back: true if a task removal was holding automatic connects, which it no longer does. */
    fun releaseTaskRemovedHold(): Boolean = synchronized(lock) { taskRemovedHold.also { taskRemovedHold = false } }

    /** An explicit Connect / Retry: clears the persisted Cancel, the demo hold and a task removal's hold. */
    fun clearManualConnect() = synchronized(lock) {
        demoHold = false
        taskRemovedHold = false
        if (userCancelled) { userCancelled = false; store?.save(false) }
    }

    /** A new connection job takes ownership; returns its generation. */
    fun begin(): Int = synchronized(lock) { phase = LinkPhase.CONNECTING; connectionEpoch++; ++generation }

    /** Ownership ends (stop / cancel / exhausted / before a reconnect): every job begun so far is now stale. */
    fun end(finalPhase: LinkPhase) = synchronized(lock) { generation++; connectionEpoch++; phase = finalPhase }

    fun owns(gen: Int): Boolean = gen == generation

    /**
     * User cancel (pill, DRIVE, notification action): allowed only while [LinkPresentation.offersCancel] holds for the
     * phase and recording state at the moment it is handled — so a stale Cancel delivered after the link connected or a
     * recording began does nothing. Allowed: blocks auto-connect and runs [end]. Returns whether it cancelled.
     */
    fun cancel(recording: () -> Boolean, end: () -> Unit): Boolean = synchronized(lock) {
        if (!LinkPresentation.offersCancel(phase, recording())) false
        else { userCancelled = true; store?.save(true); end(); true }
    }

    /** A job publishes a phase: applied (with [apply]) only while [gen] is current — atomically with the check. */
    fun publish(gen: Int, newPhase: LinkPhase, apply: (LinkPhase) -> Unit): Boolean = synchronized(lock) {
        if (gen != generation) false else {
            if (newPhase != phase) connectionEpoch++
            phase = newPhase; apply(newPhase); true
        }
    }

    /** A delayed result from job [gen] (OBD merge, status reply): [apply] runs only while that job still owns the link. */
    fun deliver(gen: Int, apply: () -> Unit): Boolean = synchronized(lock) {
        if (gen != generation) false else { apply(); true }
    }
}
