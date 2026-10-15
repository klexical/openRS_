package com.openrs.dash.can

/**
 * PCM Mode 22 poll schedule (rc.5, #184).
 *
 * Engine running: every cycle sends the whole fast group (TIP actual/desired, WGDC, KR1–4)
 * followed by ONE slow DID, rotating through the slow list. At 150 ms gaps that is
 * ~1.2 s per cycle — fast DIDs refresh at ~0.8 Hz, each slow DID every ~19 s.
 *
 * Engine off: the pre-rc.5 behaviour — full sweep of every DID, then a 30 s idle.
 *
 * Pure so the ordering can be unit-tested; [SlcanConnection] stays the single PCM
 * sender because NRC attribution relies on `lastSentPcmDid`.
 */
object PcmPollSchedule {

    data class Batch(
        val queries: List<String>,
        val gapMs: Long,
        val idleAfterMs: Long,
        val nextSlowIndex: Int
    )

    fun nextBatch(
        slowIndex: Int,
        engineRunning: Boolean,
        fast: List<String> = ObdConstants.PCM_FAST_QUERIES,
        slow: List<String> = ObdConstants.PCM_SLOW_QUERIES
    ): Batch {
        if (!engineRunning || slow.isEmpty()) {
            return Batch(
                queries = fast + slow,
                gapMs = ObdConstants.PCM_QUERY_GAP_MS,
                idleAfterMs = ObdConstants.PCM_POLL_INTERVAL_MS,
                nextSlowIndex = slowIndex
            )
        }
        val i = Math.floorMod(slowIndex, slow.size)
        return Batch(
            queries = fast + slow[i],
            gapMs = ObdConstants.PCM_FAST_GAP_MS,
            idleAfterMs = 0L,
            nextSlowIndex = (i + 1) % slow.size
        )
    }
}
