package com.openrs.dash.can

import kotlinx.coroutines.CompletableDeferred

/**
 * Outstanding PCM Mode 22 requests, oldest first (#216). A service-0x22 negative response (`7F 22 xx`) doesn't echo
 * the DID, so it can only be charged to a request when *which* request it answers is unambiguous:
 *  - a request registered before its write is [withdraw]n when the write fails — it never reached the bus;
 *  - a positive reply echoes its DID and resolves only that DID's oldest request — never the older ones (a reply may
 *    still come for them: send A, B; B answers; send C; A's late NRC must not land on C);
 *  - a request unanswered for [maxAgeMs] **lapses**: it stops pacing the poller but stays a possible owner of every later
 *    NRC until a positive reply for its own DID resolves it — no timeout ever releases it (a configured time is not a
 *    bound on when an ECU answers and identifies nothing). While any lapsed request is unresolved, NRCs are charged to
 *    nobody;
 *  - a request that got `7F 22 78` (response pending) [pending] keeps waiting for its final reply without a time limit;
 *  - the queues are capped (memory, not ownership): an evicted request leaves the ledger **unresolved** for good — every
 *    later NRC is charged to nobody until [clear] (a new connection builds a new ledger);
 *  - an NRC is attributed only when none of the above applies and every request still waiting is for one DID.
 * Consequence: after one unanswered request, NRC suppression stops for the rest of the connection unless that DID later
 * answers; positive replies keep updating readings regardless. [REPLY_WINDOW_MS] only paces the poller — it is a resource
 * limit, not proof of which request a reply answers. The poller and the frame loop run on different coroutines, hence
 * the lock.
 */
class PcmRequestLedger(private val maxAgeMs: Long = 2_000L) {
    class Request internal constructor(val did: Int, val sentAtMs: Long) {
        /** Completed when a final reply (positive, or an NRC attributed to it) arrives. */
        val reply = CompletableDeferred<Unit>()
        /** Got a response-pending: waits for its final reply without a time limit. */
        internal var awaitingFinal = false
    }

    private val outstanding = ArrayDeque<Request>()
    private val lapsed = ArrayDeque<Int>()            // DIDs of unanswered requests, oldest first
    private var evicted = false                       // a request was dropped unresolved (queue cap)

    /** [did] is being written to the bus at [nowMs]; [withdraw] it if the write fails. */
    @Synchronized fun sent(did: Int, nowMs: Long): Request {
        val r = Request(did, nowMs)
        outstanding.addLast(r)
        if (outstanding.size > MAX_TRACKED) { outstanding.removeFirst(); evicted = true }
        return r
    }

    /** The write for [r] failed: it can't own a reply. */
    @Synchronized fun withdraw(r: Request) { outstanding.remove(r) }

    /**
     * A positive reply echoed [did]: its oldest waiting request is answered — or, if none is waiting, a lapsed one (a
     * late answer). False = neither (unsolicited, or an evicted request's).
     */
    @Synchronized fun positive(did: Int): Boolean {
        val i = outstanding.indexOfFirst { it.did == did }
        if (i >= 0) { outstanding.removeAt(i).reply.complete(Unit); return true }
        val j = lapsed.indexOf(did)
        if (j >= 0) { lapsed.removeAt(j); return true }
        return false
    }

    /** `7F 22 78` (response pending): which request it belongs to isn't said — every waiting request keeps waiting. */
    @Synchronized fun pending(nowMs: Long) {
        prune(nowMs)
        outstanding.forEach { it.awaitingFinal = true }
    }

    /**
     * A final negative reply at [nowMs]: the DID it answers, or null when nothing is waiting, the waiting requests are
     * for different DIDs, or an unanswered (lapsed / evicted) request may still be answering — ambiguous, it must not
     * count against any of them.
     */
    @Synchronized fun negative(nowMs: Long): Int? {
        prune(nowMs)
        if (evicted || lapsed.isNotEmpty()) return null
        val first = outstanding.firstOrNull() ?: return null
        if (outstanding.any { it.did != first.did }) return null
        outstanding.removeFirst().reply.complete(Unit)
        return first.did
    }

    /** DIDs that may own a reply at [nowMs], lapsed first (diagnostics for an ambiguous NRC). */
    @Synchronized fun waiting(nowMs: Long): List<Int> { prune(nowMs); return lapsed.toList() + outstanding.map { it.did } }

    /** True while an earlier request is unresolved (lapsed or evicted): NRCs are charged to nobody. */
    @Synchronized fun unresolved(nowMs: Long): Boolean { prune(nowMs); return evicted || lapsed.isNotEmpty() }

    @Synchronized fun clear() { outstanding.clear(); lapsed.clear(); evicted = false }

    private fun prune(nowMs: Long) {
        val expired = outstanding.filter { !it.awaitingFinal && nowMs - it.sentAtMs > maxAgeMs }
        expired.forEach { outstanding.remove(it); lapsed.addLast(it.did) }
        if (lapsed.size > MAX_TRACKED) { while (lapsed.size > MAX_TRACKED) lapsed.removeFirst(); evicted = true }
    }

    companion object {
        /** Poll pacing: how long the poller waits for a reply before sending the next PCM request. Not ownership. */
        const val REPLY_WINDOW_MS = 300L
        /** Queue cap (memory). Overflow marks the ledger unresolved — it never erases uncertainty. */
        const val MAX_TRACKED = 32
    }
}
