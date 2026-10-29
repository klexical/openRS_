package com.openrs.dash.can

/**
 * Outstanding PCM Mode 22 requests, oldest first (#216). A service-0x22 negative response (`7F 22 xx`) doesn't echo
 * the DID, and the ECU answers in request order — so a negative response belongs to the oldest request still waiting,
 * not to the one sent last. The poller ([sent]) and the frame loop ([positive] / [negative]) run on different
 * coroutines, hence the lock.
 */
class PcmRequestLedger(private val maxAgeMs: Long = 2_000L) {
    private val outstanding = ArrayDeque<Pair<Int, Long>>()   // DID, sent at

    @Synchronized fun sent(did: Int, nowMs: Long) {
        outstanding.addLast(did to nowMs)
        while (outstanding.size > 32) outstanding.removeFirst()
    }

    /**
     * A positive reply echoed [did]: that request is answered, and any older request still waiting got no reply (the
     * ECU answers in order). Returns false when [did] wasn't outstanding (an unsolicited or late reply).
     */
    @Synchronized fun positive(did: Int): Boolean {
        val i = outstanding.indexOfFirst { it.first == did }
        if (i < 0) return false
        repeat(i + 1) { outstanding.removeFirst() }
        return true
    }

    /**
     * A negative reply: attributed to the oldest request still waiting, if any. Requests older than [maxAgeMs] are
     * dropped first (no reply ever came). Returns null when nothing is outstanding — the reply can't be attributed and
     * must not suppress anything.
     */
    @Synchronized fun negative(nowMs: Long): Int? {
        while (outstanding.isNotEmpty() && nowMs - outstanding.first().second > maxAgeMs) outstanding.removeFirst()
        return outstanding.removeFirstOrNull()?.first
    }

    @Synchronized fun clear() = outstanding.clear()
}
