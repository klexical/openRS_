package com.openrs.dash.service

import com.openrs.dash.can.LinkPhase

/**
 * Who owns the adapter connection (#212). Every transition — an auto-start's eligibility check and start, an explicit
 * start, a stop / cancel, a phase published by a connection job, a delayed result delivered from one — runs under one
 * lock, and work from a job is applied only while its generation is still the current one. So a network callback that
 * passed its check can't start a connection after a cancel, and an old job's late state, OBD merge or scan result can't
 * land on a newer session.
 *
 * Pure (no Android types) so the interleavings are unit-tested; [CanDataService] keeps one process-wide instance, like
 * [manualConnectRequired] it must survive the idle service being recreated with the activity.
 */
internal class LinkOwnership {
    private val lock = Any()

    /** Bumped by every begin / end; a job holds the value it began with. */
    @Volatile var generation = 0
        private set

    /** The phase last published (UI flow mirrors it). Read under [transition] for decisions such as cancel. */
    @Volatile var phase: LinkPhase = LinkPhase.OFF
        private set

    /**
     * Set after a user cancel or leaving demo mode: Wi-Fi / Bluetooth / service-start callbacks must not reconnect on
     * their own until an explicit connect clears it. Process-wide only — it resets with the process.
     */
    @Volatile var manualConnectRequired = false
        private set

    /** Run a transition (start, stop, reconnect, demo) under the ownership lock. Reentrant. */
    fun <T> transition(block: () -> T): T = synchronized(lock, block)

    /**
     * Callback start: the eligibility check ([blocked], plus [manualConnectRequired]) and [start] run as one step, so a
     * cancel can't fall between them. Returns whether [start] ran.
     */
    fun autoStart(blocked: () -> Boolean, start: () -> Unit): Boolean = synchronized(lock) {
        if (manualConnectRequired || blocked()) false else { start(); true }
    }

    fun requireManualConnect() = synchronized(lock) { manualConnectRequired = true }
    fun clearManualConnect() = synchronized(lock) { manualConnectRequired = false }

    /** A new connection job takes ownership; returns its generation. */
    fun begin(): Int = synchronized(lock) { phase = LinkPhase.CONNECTING; ++generation }

    /** Ownership ends (stop / cancel / exhausted / before a reconnect): every job begun so far is now stale. */
    fun end(finalPhase: LinkPhase) = synchronized(lock) { generation++; phase = finalPhase }

    fun owns(gen: Int): Boolean = gen == generation

    /**
     * User cancel (pill, DRIVE, notification action): allowed only while [LinkPresentation.offersCancel] holds for the
     * phase and recording state at the moment it is handled — so a stale Cancel delivered after the link connected or a
     * recording began does nothing. Allowed: blocks auto-connect and runs [end]. Returns whether it cancelled.
     */
    fun cancel(recording: () -> Boolean, end: () -> Unit): Boolean = synchronized(lock) {
        if (!LinkPresentation.offersCancel(phase, recording())) false
        else { manualConnectRequired = true; end(); true }
    }

    /** A job publishes a phase: applied (with [apply]) only while [gen] is current — atomically with the check. */
    fun publish(gen: Int, newPhase: LinkPhase, apply: (LinkPhase) -> Unit): Boolean = synchronized(lock) {
        if (gen != generation) false else { phase = newPhase; apply(newPhase); true }
    }

    /** A delayed result from job [gen] (OBD merge, status reply): [apply] runs only while that job still owns the link. */
    fun deliver(gen: Int, apply: () -> Unit): Boolean = synchronized(lock) {
        if (gen != generation) false else { apply(); true }
    }
}
