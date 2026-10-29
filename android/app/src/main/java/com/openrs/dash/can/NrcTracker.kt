package com.openrs.dash.can

/**
 * Session-scoped tracker for OBD-II Negative Response Codes (NRC).
 *
 * When a DID consistently returns NRC from the ECU, the tracker suppresses it
 * from future poll cycles to avoid wasting bus time. Suppression is session-scoped
 * — a new connection resets all state.
 *
 * NRCs must reach [recordNrc] already attributed to the request they answer ([PcmRequestLedger]) — a service-0x22 NRC
 * doesn't echo the DID (#216). Because attribution can still be wrong (a request that got no reply at all), no single
 * NRC suppresses, a positive reply always recovers, and suppression expires into a re-probe:
 *  - NRC 0x31 (requestOutOfRange) / 0x33 (securityAccessDenied): suppress after 2 consecutive attributed occurrences
 *  - NRC 0x22 (conditionsNotCorrect): suppress after 3 consecutive occurrences
 *  - NRC 0x78 (responsePending) never counts (the real answer follows); other codes are logged, not counted
 *  - a positive reply clears the DID's count and any suppression ([recordPositive])
 *  - a suppressed DID is polled again after [REPROBE_MS] ([shouldPoll]); one more rejection suppresses it again
 */
class NrcTracker {

    /** Record of a suppressed DID. */
    data class NrcRecord(
        val did: Int,
        val nrcCode: Int,
        val firstSeenMs: Long,
        val suppressedAfterCount: Int
    )

    companion object {
        const val NRC_REQUEST_OUT_OF_RANGE   = 0x31
        const val NRC_CONDITIONS_NOT_CORRECT = 0x22
        const val NRC_SECURITY_ACCESS_DENIED = 0x33
        const val NRC_RESPONSE_PENDING       = 0x78

        /** Consecutive attributed NRCs before suppression for permanent-looking NRCs (0x31 / 0x33). */
        const val PERMANENT_THRESHOLD = 2
        /** Consecutive NRC count before suppression for temporary NRCs (0x22). */
        const val TEMPORARY_THRESHOLD = 3
        /** A suppressed DID is re-probed this long after it was suppressed. */
        const val REPROBE_MS = 60_000L
    }

    private val suppressed = mutableMapOf<Int, NrcRecord>()
    private val suppressedAtMs = mutableMapOf<Int, Long>()
    private val consecutiveCounts = mutableMapOf<Int, Int>()
    /** Suppressed DIDs out on a re-probe: still reported, and a positive reply counts as a recovery. */
    private val reprobing = mutableMapOf<Int, NrcRecord>()

    /**
     * Record an attributed NRC for [did]. Returns `true` if the DID was newly suppressed.
     */
    @Synchronized fun recordNrc(did: Int, nrcCode: Int, timestampMs: Long): Boolean {
        if (did < 0) return false
        if (did in suppressed) return false
        reprobing.remove(did)
        val threshold = when (nrcCode) {
            NRC_REQUEST_OUT_OF_RANGE, NRC_SECURITY_ACCESS_DENIED -> PERMANENT_THRESHOLD
            NRC_CONDITIONS_NOT_CORRECT -> TEMPORARY_THRESHOLD
            else -> return false                                     // incl. 0x78 response pending
        }
        val count = (consecutiveCounts[did] ?: 0) + 1
        consecutiveCounts[did] = count
        if (count < threshold) return false
        suppressed[did] = NrcRecord(did, nrcCode, timestampMs, count)
        suppressedAtMs[did] = timestampMs
        return true
    }

    /** A positive reply for [did]: clears its count; returns true if it was suppressed (recovered). */
    @Synchronized fun recordPositive(did: Int): Boolean {
        consecutiveCounts.remove(did)
        suppressedAtMs.remove(did)
        val wasReprobing = reprobing.remove(did) != null
        return suppressed.remove(did) != null || wasReprobing
    }

    /**
     * Whether the poller should send [did] now. A suppressed DID is skipped until [REPROBE_MS] has passed, then it is
     * re-probed: it leaves the suppressed set with its count one short of the threshold, so one more attributed
     * rejection suppresses it again and a positive reply clears it.
     */
    @Synchronized fun shouldPoll(did: Int, nowMs: Long): Boolean {
        val at = suppressedAtMs[did] ?: return true
        if (nowMs - at < REPROBE_MS) return false
        val rec = suppressed.remove(did)
        suppressedAtMs.remove(did)
        if (rec != null) { consecutiveCounts[did] = rec.suppressedAfterCount - 1; reprobing[did] = rec }
        return true
    }

    /** Check if a DID is suppressed for this session. */
    @Synchronized fun isSuppressed(did: Int): Boolean = did in suppressed

    /** Snapshot of all suppressed DIDs for diagnostic export (incl. those out on a re-probe until they answer). */
    @Synchronized fun getSuppressed(): Map<Int, NrcRecord> = reprobing + suppressed

    /** Reset all state (call on new connection). */
    @Synchronized fun reset() {
        suppressed.clear()
        suppressedAtMs.clear()
        reprobing.clear()
        consecutiveCounts.clear()
    }
}
