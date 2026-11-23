package com.openrs.dash

import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.ReplyCount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #514: the per-module Mode 22 reply count's rules, on hand-fed requests and replies (the production path is
 * `Mode22RepliesExportTest`). SYNTHETIC frames, session-relative times only.
 */
class ReplyCountTest {
    private val sent = DiagnosticLogger.WRITE_SENT
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun req(id: String, did: String) = "t${id}80322${did}00000000\r"
    private fun pos(did: String) = hex("0462${did}1200")
    private val nrc = hex("037F223100000000")
    private val pending = hex("037F227800000000")
    private fun ReplyCount.pcm(now: Long) = read(now).first { it.responseId == 0x7E8 }

    @Test fun `an answer within the window counts by its time, and none counts as no reply only past the window`() {
        val c = ReplyCount()
        c.request(req("7E0", "033E"), 0, sent, 0); c.reply(0x7E8, pos("033E"), 45)
        c.request(req("7E0", "0466"), 100, sent, 100); c.reply(0x7E8, pos("0466"), 1_100)   // exactly 1,000 ms: answered
        c.request(req("7E0", "0462"), 200, sent, 200)
        assertEquals("still inside its window", 1, c.pcm(1_200).waiting)
        val m = c.pcm(1_201)                                                                  // 1,001 ms: no reply
        assertEquals(3, m.requests); assertEquals(2, m.positive); assertEquals(1, m.noReply); assertEquals(0, m.waiting)
        assertEquals("≤ 50 ms once, ≤ 1,000 ms once", listOf(1, 0, 0, 0, 1, 0), m.latency)
        assertEquals(1_000L, m.longestMs)
    }

    @Test fun `a reply after the window is late, and the request stays counted as no reply`() {
        val c = ReplyCount()
        c.request(req("7E0", "033E"), 0, sent, 0)
        c.reply(0x7E8, pos("033E"), 3_000)
        assertEquals("held first: its request may still be returning from its write", 0, c.pcm(3_000).late)
        val m = c.pcm(3_000 + ReplyCount.EARLY_KEEP_MS + 1)
        assertEquals(1, m.noReply); assertEquals(1, m.late); assertEquals(0, m.positive); assertEquals(0, m.unmatched)
        c.request(req("7E0", "033E"), 4_000, sent, 4_000)
        c.reply(0x7E8, pos("033E"), 15_000)                                                   // past the late limit: forgotten
        assertEquals(1, c.pcm(17_001).late); assertEquals(1, c.pcm(17_001).unmatched)
    }

    @Test fun `a reply while its request's write returns is that request's, not a late answer to an older miss`() {
        val c = ReplyCount()
        c.request(req("7E0", "033E"), 0, sent, 40)                                            // lost
        c.reply(0x7E8, pos("033E"), 1_300)                                                   // the next TIP's answer…
        c.request(req("7E0", "033E"), 1_290, DiagnosticLogger.WRITE_POSSIBLY_DELIVERED, 1_330)  // …logged before its line
        val m = c.pcm(10_000)
        assertEquals(1, m.positive); assertEquals(1, m.noReply); assertEquals(0, m.late); assertEquals(0, m.unmatched)
    }

    @Test fun `a rejection names no DID - it answers the newest request that began before it`() {
        val c = ReplyCount()
        c.request(req("7E0", "03ED"), 0, sent, 0); c.request(req("7E0", "03EE"), 150, sent, 190)
        c.reply(0x7E8, nrc, 200)
        c.request(req("7E0", "0461"), 300, sent, 340)                                          // written after it: not its owner
        c.reply(0x7E8, pos("0461"), 345)
        var m = c.pcm(2_000)
        assertEquals(1, m.negative); assertEquals(1, m.positive); assertEquals(1, m.noReply)
        assertEquals("the older request is the one counted lost", 0x03ED, m.longestMissRunDid)
        assertEquals("answered in 50 ms each", 2, m.latency[0])
        // Held, it still answers a request before that request's window ends, with no later write to settle it
        c.request(req("7E0", "03EE"), 3_000, sent, 3_040)
        c.reply(0x7E8, nrc, 3_900)
        m = c.pcm(4_001)
        assertEquals(2, m.negative); assertEquals(1, m.noReply); assertEquals(1, m.latency[4])
    }

    @Test fun `a reply logged before its request's line answers it, within the early limit`() {
        val c = ReplyCount()
        c.reply(0x7E8, pos("033E"), 30)                                                      // arrived while the write returned
        c.request(req("7E0", "033E"), 0, DiagnosticLogger.WRITE_POSSIBLY_DELIVERED, 40)
        c.reply(0x7E8, nrc, 120)
        c.request(req("7E0", "03ED"), 100, sent, 140)
        var m = c.pcm(5_000)
        assertEquals(2, m.requests); assertEquals(1, m.positive); assertEquals(1, m.negative); assertEquals(0, m.noReply)
        assertEquals(listOf(2, 0, 0, 0, 0, 0), m.latency)
        // A reply older than the request's write is not its answer; it ends unmatched after the early limit
        c.reply(0x7E8, pos("0466"), 6_000)
        c.request(req("7E0", "0466"), 6_010, sent, 6_020)
        m = c.pcm(6_000 + ReplyCount.EARLY_KEEP_MS + 1)
        assertEquals(1, m.noReply); assertEquals(1, m.unmatched)
    }

    @Test fun `response pending waits for the final reply, else counts as pending only, never as no reply`() {
        val c = ReplyCount()
        c.request(req("7E0", "0304"), 0, sent, 0)
        c.reply(0x7E8, pending, 50)
        assertEquals(1, c.pcm(5_000).waiting)
        c.reply(0x7E8, pos("0304"), 5_000)
        c.request(req("7E0", "0461"), 6_000, sent, 6_000)
        c.reply(0x7E8, pending, 6_050)
        val m = c.pcm(16_001)
        assertEquals(1, m.positive); assertEquals(1, m.pendingOnly); assertEquals(0, m.noReply)
        assertEquals("answered after 5 s: over the last edge", 1, m.latency.last())
    }

    @Test fun `only Mode 22 reads that may have gone out count, per module`() {
        val c = ReplyCount()
        c.request(req("7E0", "033E"), 0, DiagnosticLogger.WRITE_NOT_SENT, 0)                 // certainly not sent
        c.request("t7E0802100300000000\r", 0, sent, 0)                                       // extended session
        c.request("t7E0803190204FF000000\r", 0, sent, 0)                                     // DTC read
        c.request("t72683000000000000000\r", 0, sent, 0)                                     // flow control
        assertEquals(emptyList<ReplyCount.Module>(), c.read(5_000))
        c.request(req("726", "4028"), 0, sent, 0)
        c.reply(0x7E8, pos("4028"), 20)                                                      // another module's reply
        c.reply(0x72E, hex("100A62280B010203"), 30)                                          // a first frame for another DID
        c.reply(0x72E, hex("2104050607080900"), 31)                                          // consecutive frame: not a reply
        val bcm = c.read(5_000).first { it.responseId == 0x72E }
        assertEquals(1, bcm.requests); assertEquals(1, bcm.noReply); assertEquals(1, bcm.unmatched)
        assertEquals(1, c.read(5_000).first { it.responseId == 0x7E8 }.unmatched)
    }

    @Test fun `the longest miss run is per DID and an answer in time ends it`() {
        val c = ReplyCount()
        var t = 0L
        repeat(4) { c.request(req("7E0", "0461"), t, sent, t); t += 2_000 }                  // 4 in a row, no reply
        c.request(req("7E0", "0461"), t, sent, t); c.reply(0x7E8, pos("0461"), t + 40); t += 2_000
        repeat(2) { c.request(req("7E0", "0461"), t, sent, t); t += 2_000 }
        repeat(3) { c.request(req("7E0", "033E"), t, sent, t); t += 2_000 }
        val m = c.pcm(t)
        assertEquals(4, m.longestMissRun); assertEquals(0x0461, m.longestMissRunDid); assertEquals(9, m.noReply)
    }

    @Test fun `every request has exactly one place - outcome, waiting or dropped`() {
        val rnd = java.util.Random(514)
        val c = ReplyCount()
        val dids = listOf("033E", "0466", "0462", "03EC", "03ED")
        var t = 0L
        repeat(5_000) {
            t += rnd.nextInt(120)
            when (rnd.nextInt(6)) {
                0, 1 -> c.request(req("7E0", dids[rnd.nextInt(dids.size)]), t - rnd.nextInt(40), sent, t)
                2 -> c.reply(0x7E8, pos(dids[rnd.nextInt(dids.size)]), t)
                3 -> c.reply(0x7E8, if (rnd.nextInt(10) == 0) pending else nrc, t)
                4 -> c.request(req("726", "4028"), t, sent, t)
                else -> c.reply(0x72E, pos("4028"), t)
            }
            if (rnd.nextInt(50) == 0) c.read(t).forEach { m ->
                assertEquals(m.requests, m.positive + m.negative + m.noReply + m.pendingOnly + m.waiting + m.dropped)
            }
        }
        c.read(t + 20_000).forEach { m ->
            assertEquals(m.requests, m.positive + m.negative + m.noReply + m.pendingOnly + m.dropped)
            assertEquals(0, m.waiting)
            assertEquals(m.positive + m.negative, m.latency.sum())
        }
        c.reset()
        assertEquals(emptyList<ReplyCount.Module>(), c.read(t))
    }

    /** One module's counts as a comparable list (everything but its ID). */
    private fun ReplyCount.Module.counts() = listOf(requests, positive, negative, noReply, late, pendingOnly, waiting, dropped,
        unmatched, longestMissRun) + latency

    @Test fun `response pending counts the same whether it is logged before or after its request's line`() {
        for (finalAt in listOf(400L, 1_500L)) {
            // Wi-Fi order: the request's line, then 7F 22 78, then the answer
            val wifi = ReplyCount()
            wifi.request(req("7E0", "0304"), 0, sent, 10)
            wifi.reply(0x7E8, pending, 50)
            wifi.reply(0x7E8, pos("0304"), finalAt)
            // Bluetooth order: 7F 22 78 arrives while the write is still returning, before the request's line
            val ble = ReplyCount()
            ble.reply(0x7E8, pending, 50)
            ble.request(req("7E0", "0304"), 0, DiagnosticLogger.WRITE_POSSIBLY_DELIVERED, 60)
            ble.reply(0x7E8, pos("0304"), finalAt)
            val w = wifi.pcm(20_000); val b = ble.pcm(20_000)
            assertEquals("final answer at $finalAt ms: one positive, nothing lost or unmatched", listOf(1, 0, 0, 0), listOf(w.positive, w.noReply, w.unmatched, w.late))
            assertEquals("final answer at $finalAt ms: both orders count alike", w.counts(), b.counts())
        }
        // Both the pending and the final answer before the line
        val ble = ReplyCount()
        ble.reply(0x7E8, pending, 30); ble.reply(0x7E8, pos("0304"), 45)
        ble.request(req("7E0", "0304"), 0, DiagnosticLogger.WRITE_POSSIBLY_DELIVERED, 60)
        val m = ble.pcm(20_000)
        assertEquals(listOf(1, 0, 0, 0), listOf(m.positive, m.noReply, m.unmatched, m.pendingOnly))
        assertEquals(listOf(1, 0, 0, 0, 0, 0), m.latency)
    }

    @Test fun `response pending holds only the newest request that began before it, and no final answer is pending only`() {
        val c = ReplyCount()
        c.request(req("703", "DD01"), 0, sent, 40)                                           // lost
        c.request(req("703", "DD02"), 100, sent, 140)
        c.reply(0x70B, pending, 150)
        c.reply(0x70B, pos("DD02"), 600)
        c.request(req("703", "DD03"), 2_000, sent, 2_040)
        c.reply(0x70B, pending, 2_050)                                                       // never a final answer
        val m = c.read(20_000).first { it.responseId == 0x70B }
        assertEquals("the older request is lost, not pending", 1, m.noReply)
        assertEquals(1, m.positive); assertEquals(1, m.pendingOnly); assertEquals(0, m.unmatched)
    }
}
