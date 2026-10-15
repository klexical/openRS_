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
 * Not thread-safe — owned by the recorder's single sampling coroutine.
 */
class FreshnessGate(private val notBeforeMs: Long) {
    private val consumed = HashMap<String, Long>()

    fun takeIfFresh(tag: String, stamps: Map<String, Long>): Boolean {
        val ts = stamps[tag] ?: return false
        if (ts <= notBeforeMs) return false
        val last = consumed[tag]
        if (last != null && ts <= last) return false
        consumed[tag] = ts
        return true
    }
}
