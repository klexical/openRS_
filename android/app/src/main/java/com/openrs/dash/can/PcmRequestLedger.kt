package com.openrs.dash.can

import kotlinx.coroutines.CompletableDeferred

/**
 * Outstanding PCM Mode 22 requests, oldest first (#216). A service-0x22 negative response (`7F 22 xx`) doesn't echo
 * the DID, so it can only be charged to a request when *which* request it answers is unambiguous:
 *  - a request registered before its write is [withdraw]n when the write fails — it never reached the bus;
 *  - a positive reply echoes its DID and resolves only that DID's oldest request — never the older ones (a reply may
 *    still come for them: send A, B; B answers; send C; A's late NRC must not land on C);
 *  - an NRC is attributed only when every request still waiting is for the same DID (one request, or repeats of one
 *    DID) and no unanswered request has lapsed recently; otherwise it is ambiguous and charged to nobody ([negative]
 *    returns null);
 *  - a request unanswered for [maxAgeMs] (or [P2_STAR_MS] after a `7F 22 78` response-pending) **lapses**: it no longer
 *    owns an NRC, but its late reply may still arrive, so for [LAPSED_HOLD_MS] it keeps every NRC ambiguous — unless a
 *    late positive reply resolves it. Expiry never turns an ambiguous NRC into an attributed one (A 0 ms, B 1,900 ms,
 *    A's late NRC 2,101 ms is not B's). A lapsed request is never charged either.
 * The poller waits up to [REPLY_WINDOW_MS] for each reply before the next request ([Request.reply]), so the normal
 * case — one request in flight — stays attributable. The hold is ISO 14229-2's P2*server max (5 s) — protocol timing,
 * not measured on the car. The 2-NRC threshold and the 60 s re-probe ([NrcTracker]) are provisional recovery settings;
 * they don't make attribution correct, this does.
 * The poller and the frame loop run on different coroutines, hence the lock.
 */
class PcmRequestLedger(private val maxAgeMs: Long = 2_000L) {
    class Request internal constructor(val did: Int, val sentAtMs: Long) {
        /** Completed when a final reply (positive, or an NRC attributed to it) arrives. */
        val reply = CompletableDeferred<Unit>()
        /** Waiting until this time (sent + maxAge, or later after a response-pending). */
        internal var expiresAtMs: Long = 0L
    }
    private class Lapsed(val did: Int, var holdUntilMs: Long)

    private val outstanding = ArrayDeque<Request>()
    private val lapsed = ArrayDeque<Lapsed>()

    /** [did] is being written to the bus at [nowMs]; [withdraw] it if the write fails. */
    @Synchronized fun sent(did: Int, nowMs: Long): Request {
        val r = Request(did, nowMs).also { it.expiresAtMs = nowMs + maxAgeMs }
        outstanding.addLast(r)
        while (outstanding.size > 32) lapse(outstanding.removeFirst())
        return r
    }

    /** The write for [r] failed: it can't own a reply. */
    @Synchronized fun withdraw(r: Request) { outstanding.remove(r) }

    /**
     * A positive reply echoed [did]: its oldest waiting request is answered — or, if none is waiting, a lapsed one (a
     * late answer). False = neither (unsolicited).
     */
    @Synchronized fun positive(did: Int): Boolean {
        val i = outstanding.indexOfFirst { it.did == did }
        if (i >= 0) { outstanding.removeAt(i).reply.complete(Unit); return true }
        val j = lapsed.indexOfFirst { it.did == did }
        if (j >= 0) { lapsed.removeAt(j); return true }
        return false
    }

    /**
     * `7F 22 78` (response pending) at [nowMs]: the server is still working on one of the requests — which one isn't
     * said, so every waiting or lapsed request may still get its final reply within [P2_STAR_MS].
     */
    @Synchronized fun pending(nowMs: Long) {
        prune(nowMs)
        val until = nowMs + P2_STAR_MS
        outstanding.forEach { it.expiresAtMs = maxOf(it.expiresAtMs, until) }
        lapsed.forEach { it.holdUntilMs = maxOf(it.holdUntilMs, until) }
    }

    /**
     * A final negative reply at [nowMs]: the DID it answers, or null when nothing is waiting, the waiting requests are
     * for different DIDs, or a lapsed request may still be answering (ambiguous — it must not count against any of them).
     */
    @Synchronized fun negative(nowMs: Long): Int? {
        prune(nowMs)
        if (lapsed.isNotEmpty()) return null
        val first = outstanding.firstOrNull() ?: return null
        if (outstanding.any { it.did != first.did }) return null
        outstanding.removeFirst().reply.complete(Unit)
        return first.did
    }

    /** DIDs that may own a reply at [nowMs], lapsed first (diagnostics for an ambiguous NRC). */
    @Synchronized fun waiting(nowMs: Long): List<Int> { prune(nowMs); return lapsed.map { it.did } + outstanding.map { it.did } }

    @Synchronized fun clear() { outstanding.clear(); lapsed.clear() }

    private fun prune(nowMs: Long) {
        lapsed.removeAll { nowMs > it.holdUntilMs }
        val expired = outstanding.filter { nowMs > it.expiresAtMs }
        expired.forEach { outstanding.remove(it); lapse(it) }
    }

    private fun lapse(r: Request) {
        lapsed.addLast(Lapsed(r.did, r.expiresAtMs + LAPSED_HOLD_MS))
        while (lapsed.size > 32) lapsed.removeFirst()
    }

    companion object {
        /** How long the poller waits for a reply before sending the next PCM request. */
        const val REPLY_WINDOW_MS = 300L
        /** ISO 14229-2 P2*server max: how long a final reply may follow a response-pending. */
        const val P2_STAR_MS = 5_000L
        /** How long a lapsed (unanswered) request keeps NRCs ambiguous. */
        const val LAPSED_HOLD_MS = P2_STAR_MS
    }
}
