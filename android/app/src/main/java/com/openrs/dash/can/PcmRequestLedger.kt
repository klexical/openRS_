package com.openrs.dash.can

import kotlinx.coroutines.CompletableDeferred

/**
 * Outstanding PCM Mode 22 requests, oldest first (#216). A service-0x22 negative response (`7F 22 xx`) doesn't echo
 * the DID, so it can only be charged to a request when *which* request it answers is unambiguous:
 *  - a request registered before its write is [withdraw]n when the write fails — it never reached the bus;
 *  - a positive reply echoes its DID and resolves only that DID's oldest request — never the older ones (a reply may
 *    still come for them: send A, B; B answers; send C; A's late NRC must not land on C);
 *  - an NRC is attributed only when every request still waiting is for the same DID (one request, or repeats of one
 *    DID); otherwise it is ambiguous and charged to nobody ([negative] returns null);
 *  - a request older than [maxAgeMs] got no reply and is dropped.
 * The poller waits up to [REPLY_WINDOW_MS] for each reply before the next request ([Request.reply]), so the normal
 * case — one request in flight — stays attributable. The 2-NRC threshold and the 60 s re-probe ([NrcTracker]) are
 * provisional recovery settings; they don't make attribution correct, this does.
 * The poller and the frame loop run on different coroutines, hence the lock.
 */
class PcmRequestLedger(private val maxAgeMs: Long = 2_000L) {
    class Request internal constructor(val did: Int, val sentAtMs: Long) {
        /** Completed when a final reply (positive, or an NRC attributed to it) arrives. */
        val reply = CompletableDeferred<Unit>()
    }

    private val outstanding = ArrayDeque<Request>()

    /** [did] is being written to the bus at [nowMs]; [withdraw] it if the write fails. */
    @Synchronized fun sent(did: Int, nowMs: Long): Request {
        val r = Request(did, nowMs)
        outstanding.addLast(r)
        while (outstanding.size > 32) outstanding.removeFirst()
        return r
    }

    /** The write for [r] failed: it can't own a reply. */
    @Synchronized fun withdraw(r: Request) { outstanding.remove(r) }

    /** A positive reply echoed [did]: its oldest waiting request is answered. False = not outstanding (late / unsolicited). */
    @Synchronized fun positive(did: Int): Boolean {
        val i = outstanding.indexOfFirst { it.did == did }
        if (i < 0) return false
        outstanding.removeAt(i).reply.complete(Unit)
        return true
    }

    /**
     * A final negative reply at [nowMs]: the DID it answers, or null when nothing is waiting or the waiting requests
     * are for different DIDs (ambiguous — it must not count against any of them).
     */
    @Synchronized fun negative(nowMs: Long): Int? {
        prune(nowMs)
        val first = outstanding.firstOrNull() ?: return null
        if (outstanding.any { it.did != first.did }) return null
        outstanding.removeFirst().reply.complete(Unit)
        return first.did
    }

    /** DIDs waiting at [nowMs] (diagnostics for an ambiguous NRC). */
    @Synchronized fun waiting(nowMs: Long): List<Int> { prune(nowMs); return outstanding.map { it.did } }

    @Synchronized fun clear() = outstanding.clear()

    private fun prune(nowMs: Long) {
        while (outstanding.isNotEmpty() && nowMs - outstanding.first().sentAtMs > maxAgeMs) outstanding.removeFirst()
    }

    companion object {
        /** How long the poller waits for a reply before sending the next PCM request. */
        const val REPLY_WINDOW_MS = 300L
    }
}
