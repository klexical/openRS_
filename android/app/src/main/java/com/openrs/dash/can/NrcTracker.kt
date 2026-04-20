package com.openrs.dash.can

/**
 * Session-scoped tracker for OBD-II Negative Response Codes (NRC).
 *
 * When a DID consistently returns NRC from the ECU, the tracker suppresses it
 * from future poll cycles to avoid wasting bus time. Suppression is session-scoped
 * — a new connection resets all state.
 *
 * Suppression rules:
 *  - NRC 0x31 (requestOutOfRange): suppress after 1 occurrence
 *  - NRC 0x33 (securityAccessDenied): suppress after 1 occurrence
 *  - NRC 0x22 (conditionsNotCorrect): suppress after 3 consecutive occurrences
 *  - All other NRC codes: logged but not suppressed
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

        /** Consecutive NRC count before suppression for temporary NRCs (0x22). */
        const val TEMPORARY_THRESHOLD = 3
    }

    private val suppressed = mutableMapOf<Int, NrcRecord>()
    private val consecutiveCounts = mutableMapOf<Int, Int>()

    /**
     * Record an NRC for [did]. Returns `true` if the DID was newly suppressed.
     */
    fun recordNrc(did: Int, nrcCode: Int, timestampMs: Long): Boolean {
        if (did < 0) return false
        if (did in suppressed) return false

        return when (nrcCode) {
            NRC_REQUEST_OUT_OF_RANGE, NRC_SECURITY_ACCESS_DENIED -> {
                suppressed[did] = NrcRecord(did, nrcCode, timestampMs, 1)
                consecutiveCounts.remove(did)
                true
            }
            NRC_CONDITIONS_NOT_CORRECT -> {
                val count = (consecutiveCounts[did] ?: 0) + 1
                consecutiveCounts[did] = count
                if (count >= TEMPORARY_THRESHOLD) {
                    suppressed[did] = NrcRecord(did, nrcCode, timestampMs, count)
                    consecutiveCounts.remove(did)
                    true
                } else false
            }
            else -> false
        }
    }

    /** Check if a DID is suppressed for this session. */
    fun isSuppressed(did: Int): Boolean = did in suppressed

    /** Snapshot of all suppressed DIDs for diagnostic export. */
    fun getSuppressed(): Map<Int, NrcRecord> = suppressed.toMap()

    /** Reset all state (call on new connection). */
    fun reset() {
        suppressed.clear()
        consecutiveCounts.clear()
    }
}
