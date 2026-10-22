package com.openrs.dash.data

/**
 * Pull detection for the knock logger (rc.6, #174) — "% of pulls with KR > 0".
 *
 * A pull is throttle ≥ [MIN_THROTTLE_PCT] at ≥ [MIN_RPM], sustained for [MIN_DURATION_MS].
 * Short dips (a gear change) shorter than [MAX_GAP_MS] don't end it. A pull is "knocked"
 * if a knock event lands while it is active (or within the gap after its last qualifying
 * sample — KR is polled ~1.2 s, so the reading can trail the pull slightly).
 *
 * Streaming and pure: fed at whatever rate the caller has (1 Hz recorder, OBD merges for
 * the session log). Not thread-safe — each owner keeps its own instance on one thread.
 */
class PullTracker {

    var pullCount = 0
        private set
    var knockPullCount = 0
        private set

    private var activeStartMs = -1L
    private var lastQualifyingMs = -1L
    private var counted = false
    private var knocked = false

    fun update(nowMs: Long, rpm: Double, throttlePct: Double) {
        val qualifies = throttlePct >= MIN_THROTTLE_PCT && rpm >= MIN_RPM
        if (activeStartMs >= 0 && nowMs - lastQualifyingMs > MAX_GAP_MS) endPull()
        if (!qualifies) return
        if (activeStartMs < 0) {
            activeStartMs = nowMs
            counted = false
            knocked = false
        }
        lastQualifyingMs = nowMs
        if (!counted && nowMs - activeStartMs >= MIN_DURATION_MS) {
            counted = true
            pullCount++
            if (knocked) knockPullCount++
        }
    }

    /** Record a knock event at [nowMs]; attributed to the active pull, if any. */
    fun markKnock(nowMs: Long) {
        if (activeStartMs < 0 || nowMs - lastQualifyingMs > MAX_GAP_MS || knocked) return
        knocked = true
        if (counted) knockPullCount++
    }

    private fun endPull() {
        activeStartMs = -1L
        counted = false
        knocked = false
    }

    companion object {
        const val MIN_THROTTLE_PCT = 80.0
        const val MIN_RPM = 2500.0
        const val MIN_DURATION_MS = 2_000L
        const val MAX_GAP_MS = 2_000L
    }
}
