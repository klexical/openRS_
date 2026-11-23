package com.openrs.dash.can

import com.openrs.dash.data.VehicleState

/**
 * Which TPMS sensor-ID reads (BCM DIDs 0x280F–0x2812) the BCM poller sends on each cycle of one connection (#518).
 *
 * A 0x280B report is matched to a tyre only by the sensor ID that position's read returned, so a lost reply used to
 * leave that tyre's temperature unknown for the whole connection: the four reads went out once, on connect. Now the
 * first cycle sends all four, as before (a new connection re-reads every position), and each later cycle sends only the
 * positions whose ID is still unknown in the state, until each has been sent [maxSends] times. Read-only Mode 22
 * requests, nothing else; one instance per connection, so a reconnect starts a new budget.
 */
internal class TpmsIdReads(private val maxSends: Int = ObdConstants.TPMS_ID_MAX_SENDS) {
    private val queries = ObdConstants.BCM_TPMS_ID_QUERIES
    private val sends = IntArray(queries.size)
    private var firstCycle = true

    /** The reads to send this cycle, in [ObdConstants.BCM_TPMS_ID_QUERIES] order; each one returned counts as sent. */
    fun due(state: VehicleState): List<String> {
        val all = firstCycle
        firstCycle = false
        return queries.indices
            .filter { i -> sends[i] < maxSends && (all || idAt(state, i) < 0) }
            .map { i -> sends[i]++; queries[i] }
    }

    /** The sensor ID stored for [ObdConstants.BCM_TPMS_ID_QUERIES]`[i]` (LF, RF, RR, LR); -1 = not yet read. */
    private fun idAt(s: VehicleState, i: Int): Long = when (i) {
        0 -> s.tpmsSensorIdLF
        1 -> s.tpmsSensorIdRF
        2 -> s.tpmsSensorIdRR
        else -> s.tpmsSensorIdLR
    }
}
