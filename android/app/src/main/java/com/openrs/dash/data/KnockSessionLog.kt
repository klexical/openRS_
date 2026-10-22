package com.openrs.dash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-level knock capture (rc.6, #174) — knock events whenever the adapter is
 * connected, independent of drive recording. In memory only (driveId = 0); exported in
 * the diagnostic ZIP as `drive_knock_session_*.csv` and shown under the SESSION scope.
 *
 * Uses the same fresh-only rule as the recorder ([KnockCapture] + its own
 * [FreshnessGate]), so a KR reading is logged once per decode and never from before the
 * session started. Fed from `CanDataService.mergeObdState` OUTSIDE the state `update{}`.
 */
object KnockSessionLog {

    const val MAX_EVENTS = 2_000

    data class Pulls(val total: Int = 0, val knocked: Int = 0)

    private var gate = FreshnessGate(0L)
    private var tracker = PullTracker()

    private val _events = MutableStateFlow<List<KnockEventEntity>>(emptyList())
    val events: StateFlow<List<KnockEventEntity>> = _events.asStateFlow()

    private val _pulls = MutableStateFlow(Pulls())
    val pulls: StateFlow<Pulls> = _pulls.asStateFlow()

    /** Start a new session at [nowMs] — drops events and rejects stamps older than it. */
    @Synchronized
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        gate = FreshnessGate(nowMs)
        tracker = PullTracker()
        _events.value = emptyList()
        _pulls.value = Pulls()
    }

    /** Feed the merged live state after an OBD merge. */
    @Synchronized
    fun onState(vs: VehicleState, nowMs: Long, lat: Double? = null, lng: Double? = null) {
        tracker.update(nowMs, vs.rpm, vs.throttleOrPedalPct)
        val captured = KnockCapture.eventsFrom(vs, gate, driveId = 0L, now = nowMs, lat = lat, lng = lng)
        if (captured.isNotEmpty()) {
            tracker.markKnock(nowMs)
            val merged = _events.value + captured
            _events.value = if (merged.size > MAX_EVENTS) merged.subList(merged.size - MAX_EVENTS, merged.size).toList() else merged
        }
        val p = Pulls(tracker.pullCount, tracker.knockPullCount)
        if (p != _pulls.value) _pulls.value = p
    }
}
