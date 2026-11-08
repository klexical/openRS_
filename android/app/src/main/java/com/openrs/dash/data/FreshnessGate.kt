package com.openrs.dash.data

/**
 * Per-drive freshness gate for polled (Mode 22) fields (rc.5, #184 / #187).
 *
 * The drive recorder samples at 1 Hz, but PCM DIDs refresh slower and VehicleState
 * keeps the last decoded value forever. Recording that held value every second
 * repeats one observation many times and, at drive start, can replay a reading
 * from before the drive existed.
 *
 * [takeIfFresh] returns true exactly once per new decode of a tag, and never for a
 * decode stamped before [notBeforeMs] (the drive start). Stamps come from
 * [VehicleState.fieldLastUpdateMs] (written by markFresh/putGeneric).
 *
 * The drive recorder also (N1, issue to be filed) raises the floor with [skip] on every fix it skips (paused, disconnected): a
 * decode made before the gap is never stored at the first fix after it. And it reads through [takeIfCurrent], which refuses a
 * decode older than twice the field's poll budget ([PolledFreshness], the rule the UI uses for STALE) — a long gap with the
 * link up can't store a held reading either.
 *
 * Not thread-safe — owned by the recorder's single sampling coroutine.
 */
class FreshnessGate(notBeforeMs: Long) {
    private val consumed = HashMap<String, Long>()
    /** Decodes stamped at or before this are never taken: the drive start, raised by [skip]. */
    private var floorMs = notBeforeMs

    /**
     * True exactly once per new decode of [tag] made after the floor; with [nowMs] and [maxAgeMs], also only while the decode is
     * at most [maxAgeMs] old (an older one is refused and left unconsumed).
     */
    fun takeIfFresh(tag: String, stamps: Map<String, Long>, nowMs: Long? = null, maxAgeMs: Long? = null): Boolean {
        val ts = stamps[tag] ?: return false
        if (ts <= floorMs) return false
        if (nowMs != null && maxAgeMs != null && nowMs - ts > maxAgeMs) return false
        val last = consumed[tag]
        if (last != null && ts <= last) return false
        consumed[tag] = ts
        return true
    }

    /** [takeIfFresh] with the poll-schedule age limit for [tag]: twice its refresh period at the current engine state. */
    fun takeIfCurrent(tag: String, vs: VehicleState, nowMs: Long): Boolean =
        takeIfFresh(tag, vs.fieldLastUpdateMs, nowMs,
            2 * PolledFreshness.refreshMs(tag, vs.rpm > com.openrs.dash.can.ObdConstants.FAST_POLL_MIN_RPM))

    /** A fix was skipped at [nowMs] (paused, disconnected): nothing decoded up to then may be taken afterwards. */
    fun skip(nowMs: Long) { if (nowMs > floorMs) floorMs = nowMs }
}
