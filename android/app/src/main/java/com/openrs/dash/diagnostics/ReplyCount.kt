package com.openrs.dash.diagnostics

import com.openrs.dash.can.Mode22
import com.openrs.dash.can.NrcTracker

/**
 * Mode 22 replies per module for the diagnostic export (#514): of the requests the app wrote, how many got a reply of any
 * kind within [NO_REPLY_MS], how many got none, and how quickly the answered ones came. Counts only: no frame, no clock
 * time. Fed by [DiagnosticLogger] from the `# TX` line of each request (the time taken before its write, #209) and from
 * every module reply the frame loop logs, on the same session clock.
 *
 * This is a count, not attribution: the request ledgers ([com.openrs.dash.can.PcmRequestLedger]) keep deciding which DID
 * an NRC is charged to, and nothing here changes that or NRC suppression (#216). Per module the totals are exact whichever
 * request a reply is matched to; the per-DID miss run and the answer times are exact for positive replies (they echo their
 * DID) and a best match around rejections (which name none).
 *
 * - A positive reply answers the oldest waiting request for its DID. A request with no reply within [NO_REPLY_MS] counts
 *   as no reply.
 * - A reply can be logged before its request's `# TX` line (a Bluetooth write returns after the module answered, #209 T1).
 *   So a positive that answers no waiting request, and every rejection and response pending, is held: the next request of
 *   its module whose write began at or before it takes it (for a positive: a request for its DID).
 * - A held rejection otherwise answers the newest request of the module that began before it: settled when a later
 *   request is written, before any request it could answer runs out of time, or after [EARLY_KEEP_MS]. Polls are
 *   paced well apart and answers take about 50 ms, so an older waiting request is more likely lost than answered late.
 * - A held reply that no request takes is a late answer (to a request counted as no reply at most [LATE_KEEP_MS] before
 *   it: counted late as well) or else unmatched. Late counts therefore trail by up to [EARLY_KEEP_MS].
 * - `7F 22 78` (response pending) is a reply that is not the answer. It is held and matched like a rejection; the request
 *   it marks then waits for its final reply up to [LATE_KEEP_MS] and, if none comes, counts as pending only, not as no
 *   reply.
 * - Requests certainly not sent are not counted. DID-prober requests count like polls (they go to the same modules).
 *
 * Every queue is bounded; a request that falls off a full queue is counted in [Module.dropped], so `requests = positive +
 * negative + noReply + pendingOnly + waiting + dropped` always holds (`ReplyCountTest`). Thread-safe: each call locks.
 */
class ReplyCount {
    private class Request(val did: Int, val stampMs: Long) { var pending = false }
    private class Lapsed(val did: Int, val stampMs: Long)
    /** A reply not yet matched: [did] for a positive, null for a rejection; [pending] for `7F 22 78`. */
    private class Held(val did: Int?, val atMs: Long, val pending: Boolean = false)

    private class Live(val responseId: Int) {
        val waiting = ArrayDeque<Request>()          // stamp order
        val lapsed = ArrayDeque<Lapsed>()            // counted as no reply; a late answer may still come
        val held = ArrayDeque<Held>()                // arrival order
        var requests = 0; var positive = 0; var negative = 0; var noReply = 0; var late = 0; var pendingOnly = 0
        var dropped = 0; var unmatched = 0
        val latency = IntArray(LATENCY_EDGES_MS.size + 1)
        var longestMs = -1L
        /** Consecutive no-replies per DID, and the longest run seen. */
        val missRun = HashMap<Int, Int>()
        var longestRun = 0; var longestRunDid = -1
    }

    /** One module's figures at the time of reading. */
    class Module(
        val responseId: Int, val requests: Int, val positive: Int, val negative: Int, val noReply: Int, val late: Int,
        val pendingOnly: Int, val waiting: Int, val dropped: Int, val unmatched: Int,
        /** Answered requests by time from request to reply: ≤ each of [LATENCY_EDGES_MS], then over the last edge. */
        val latency: List<Int>,
        /** Slowest answered request in ms, or -1 when none was answered. */
        val longestMs: Long,
        /** Longest run of consecutive no-replies for one DID, and that DID (-1 when there was no miss). */
        val longestMissRun: Int, val longestMissRunDid: Int,
    ) {
        /** Requests with an outcome (not waiting, not dropped). */
        val resolved: Int get() = positive + negative + noReply + pendingOnly
    }

    private val modules = LinkedHashMap<Int, Live>()

    private fun module(responseId: Int): Live? {
        modules[responseId]?.let { return it }
        if (modules.size >= MAX_MODULES) return null
        return Live(responseId).also { modules[responseId] = it }
    }

    /**
     * A frame the app wrote: [slcanFrame] as written, [stampMs] taken before the write, [result] its write word
     * ([DiagnosticLogger.WRITE_SENT] …), [nowMs] the session clock now. Only a single-frame Mode 22 read counts; a write
     * certainly not sent ([DiagnosticLogger.WRITE_NOT_SENT]) does not.
     */
    @Synchronized fun request(slcanFrame: String, stampMs: Long, result: String, nowMs: Long) {
        if (result == DiagnosticLogger.WRITE_NOT_SENT) return
        val (requestId, did) = Mode22.request(slcanFrame) ?: return
        val m = module(requestId + RESPONSE_OFFSET) ?: return
        prune(m, nowMs)
        // A rejection or response pending that came before this write began belongs to an earlier request
        m.held.filter { it.did == null && it.atMs < stampMs }.forEach { m.held.remove(it); settle(m, it) }
        m.requests++
        // A reply that came in while this write was still returning is this request's: a response pending marks it, a
        // final reply answers it
        var pendingSeen = false
        for (h in m.held.toList()) {
            if (h.atMs < stampMs || (h.did != null && h.did != did)) continue
            m.held.remove(h)
            if (h.pending) { pendingSeen = true; continue }
            if (h.did == null) m.negative++ else m.positive++
            answered(m, did, h.atMs - stampMs)
            return
        }
        val r = Request(did, stampMs).also { it.pending = pendingSeen }
        // In stamp order: two pollers write to one module (AWD), and each line is written when its write returns
        val at = m.waiting.indexOfFirst { it.stampMs > stampMs }
        if (at < 0) m.waiting.addLast(r) else m.waiting.add(at, r)
        if (m.waiting.size > MAX_WAITING) { m.waiting.removeFirst(); m.dropped++ }
    }

    /** A frame from [canId] at [nowMs] (the session clock): a Mode 22 reply is matched; anything else is ignored. */
    @Synchronized fun reply(canId: Int, d: ByteArray, nowMs: Long) {
        val did = Mode22.positiveDid(d)
        val code = if (did == null) Mode22.negativeCode(d) ?: return else null
        val m = module(canId) ?: return
        prune(m, nowMs)
        if (code == NrcTracker.NRC_RESPONSE_PENDING) {
            // Held like a rejection: over Bluetooth it can come before its request's line (#514 review)
            m.held.addLast(Held(null, nowMs, pending = true))
            if (m.held.size > MAX_HELD) settle(m, m.held.removeFirst())
            return
        }
        if (did != null) {
            val w = m.waiting.indexOfFirst { it.did == did }
            if (w >= 0) { val r = m.waiting.removeAt(w); m.positive++; final(m, r, nowMs); return }
        }
        m.held.addLast(Held(did, nowMs))
        if (m.held.size > MAX_HELD) settle(m, m.held.removeFirst())
    }

    /** Every module's figures at [nowMs], in the order modules were first seen. */
    @Synchronized fun read(nowMs: Long): List<Module> = modules.values.map { m ->
        prune(m, nowMs)
        Module(m.responseId, m.requests, m.positive, m.negative, m.noReply, m.late, m.pendingOnly, m.waiting.size,
            m.dropped, m.unmatched, m.latency.toList(), m.longestMs, m.longestRun, m.longestRunDid)
    }

    @Synchronized fun reset() = modules.clear()

    private fun answered(m: Live, did: Int, ms: Long) {
        val t = ms.coerceAtLeast(0L)
        val b = LATENCY_EDGES_MS.indexOfFirst { t <= it }
        m.latency[if (b < 0) LATENCY_EDGES_MS.size else b]++
        if (t > m.longestMs) m.longestMs = t
        m.missRun.remove(did)
    }

    /**
     * [r] got its final reply at [atMs]: a response pending still held from its own wait was its own, unless a newer
     * waiting request began before it (then it is that request's, the newest that began before it).
     */
    private fun final(m: Live, r: Request, atMs: Long) {
        m.held.removeAll { h -> h.pending && h.atMs >= r.stampMs && h.atMs <= atMs &&
            m.waiting.none { w -> w.stampMs > r.stampMs && w.stampMs <= h.atMs } }
        answered(m, r.did, atMs - r.stampMs)
    }

    /** A held reply no request took in time. */
    private fun settle(m: Live, h: Held) = when {
        h.pending -> settlePending(m, h)
        h.did == null -> settleRejection(m, h)
        else -> lateOrUnmatched(m, h)
    }

    /**
     * A response pending marks the newest waiting request that began before it. One that came for a request already
     * counted as no reply changes nothing (its final answer, if any, counts late); otherwise it is unmatched.
     */
    private fun settlePending(m: Live, h: Held) {
        val w = m.waiting.indexOfLast { it.stampMs <= h.atMs }
        if (w >= 0) { m.waiting[w].pending = true; return }
        if (m.lapsed.none { it.stampMs <= h.atMs && h.atMs - it.stampMs <= LATE_KEEP_MS }) m.unmatched++
    }

    /** A rejection answers the newest waiting request that began before it, else it is late or unmatched. */
    private fun settleRejection(m: Live, h: Held) {
        val w = m.waiting.indexOfLast { it.stampMs <= h.atMs }
        if (w >= 0) { val r = m.waiting.removeAt(w); m.negative++; final(m, r, h.atMs) }
        else lateOrUnmatched(m, h)
    }

    private fun lateOrUnmatched(m: Live, h: Held) {
        val l = m.lapsed.indexOfLast { it.stampMs <= h.atMs && h.atMs - it.stampMs <= LATE_KEEP_MS && (h.did == null || it.did == h.did) }
        if (l >= 0) { m.lapsed.removeAt(l); m.late++ } else m.unmatched++
    }

    private fun prune(m: Live, nowMs: Long) {
        while (m.held.isNotEmpty() && nowMs - m.held.first().atMs > EARLY_KEEP_MS) settle(m, m.held.removeFirst())
        while (true) {
            val r = m.waiting.firstOrNull { (!it.pending && nowMs - it.stampMs > NO_REPLY_MS) ||
                                            (it.pending && nowMs - it.stampMs > LATE_KEEP_MS) } ?: break
            // A rejection or response pending that came inside this request's window settles first: it may be this
            // request's reply
            val h = m.held.firstOrNull { it.did == null && it.atMs >= r.stampMs && it.atMs - r.stampMs <= NO_REPLY_MS }
            if (h != null) { m.held.remove(h); settle(m, h); continue }
            m.waiting.remove(r)
            if (r.pending) { m.pendingOnly++; continue }
            m.noReply++
            m.lapsed.addLast(Lapsed(r.did, r.stampMs))
            if (m.lapsed.size > MAX_LAPSED) m.lapsed.removeFirst()
            if (m.missRun.size < MAX_DIDS || r.did in m.missRun) {
                val run = (m.missRun[r.did] ?: 0) + 1
                m.missRun[r.did] = run
                if (run > m.longestRun) { m.longestRun = run; m.longestRunDid = r.did }
            }
        }
        while (m.lapsed.isNotEmpty() && nowMs - m.lapsed.first().stampMs > LATE_KEEP_MS + EARLY_KEEP_MS) m.lapsed.removeFirst()
    }

    companion object {
        /**
         * A request with no reply of any kind within this time counts as no reply. #514's own measure (1 s); answered
         * replies take about 50 ms (p99 about 100 ms) over Bluetooth and Wi-Fi alike, so a reply this late is not a slow one.
         */
        const val NO_REPLY_MS = 1_000L
        /** A reply this long after its request still counts as late; after it the request is forgotten. */
        const val LATE_KEEP_MS = 10_000L
        /** How long a reply waits for the request it may answer: a Bluetooth write's own wait is bounded at 2 s. */
        const val EARLY_KEEP_MS = 2_000L
        /** Answered requests are counted by time to reply: at or under each edge, then over the last. */
        val LATENCY_EDGES_MS = listOf(50L, 100L, 250L, 500L, 1_000L)
        /** Every polled module answers at its request ID + 8 (the ECU table). */
        const val RESPONSE_OFFSET = 8
        const val MAX_MODULES = 16
        const val MAX_WAITING = 256
        const val MAX_LAPSED = 256
        const val MAX_HELD = 32
        const val MAX_DIDS = 256
    }
}
