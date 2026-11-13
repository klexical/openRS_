package com.openrs.dash

import com.openrs.dash.can.ReceptionStall
import com.openrs.dash.can.ReceptionStall.Companion.TYPE_LOW
import com.openrs.dash.can.ReceptionStall.Companion.TYPE_STALL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #356 — [ReceptionStall] on its own, monotonic clock passed in (ms). SYNTHETIC frame times, except the first test, which
 * uses the issue's capture figures (clean rate 2,017 frames/s; the stall's first 0.1 s held 343 frames, then frames in
 * pairs; the burst was 2,406 frames in 50 ms). The connection-level behaviour is ReceptionStallReplayTest.
 */
class ReceptionStallTest {
    private val reports = mutableListOf<ReceptionStall.Report>()

    /** [count] frames spread evenly over [fromMs, toMs). */
    private fun ReceptionStall.frames(fromMs: Long, toMs: Long, count: Int) {
        for (i in 0 until count) reports += onFrame(fromMs + (toMs - fromMs) * i / count)
    }
    /** [perSecond] frames a second over [fromMs, toMs). */
    private fun ReceptionStall.rate(fromMs: Long, toMs: Long, perSecond: Int) =
        frames(fromMs, toMs, ((toMs - fromMs) * perSecond / 1_000).toInt())
    private fun ReceptionStall.end(atMs: Long) { reports += onEnd(atMs) }
    private fun shown() = reports.joinToString { "${it.type}: ${it.message}" }

    @Test fun `the capture's stall is one stall, from its first low quarter second, with its length`() {
        val d = ReceptionStall()
        d.rate(0, 20_400, 2_017)
        d.frames(20_400, 20_500, 343)                                     // the stall's first 0.1 s
        for (t in listOf(22_000L, 23_500L, 25_000L, 26_500L, 28_000L)) { d.frames(t, t + 9, 2) }   // pairs
        d.frames(31_060, 31_110, 2_406)                                   // the burst
        d.rate(31_110, 40_000, 2_017)
        assertEquals(shown(), 1, reports.size)
        val r = reports.single()
        assertEquals(TYPE_STALL, r.type)
        assertEquals(20_500L, r.startMs)
        assertTrue(r.message, r.message.startsWith("10.50 s below 25 % of this connection's rate (2016 frames/s"))
        assertTrue(r.message, r.message.contains("; 10 frames in it; then "))
    }

    @Test fun `the capture's ending quarter second passes the burst factor, a bus waking at its normal rate does not`() {
        val rate = 2_017 / 4.0                                            // frames per quarter second
        val burstQuarter = 2_406 + (31_250 - 31_110) * 2_017 / 1_000     // the burst plus the normal rate after it
        assertTrue("$burstQuarter vs $rate", burstQuarter >= ReceptionStall.BURST_FACTOR * rate)
        assertTrue(rate < ReceptionStall.BURST_FACTOR * rate)
    }

    @Test fun `key-on at the normal rate after a quiet span is a low span`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 2_017)
        d.rate(30_000, 40_000, 2_017)
        assertEquals(shown(), 1, reports.size)
        assertEquals(TYPE_LOW, reports.single().type)
        assertTrue(shown(), reports.single().message.endsWith(
            "${ReceptionStall.CAME_BACK}. ${ReceptionStall.NOT_TOLD_APART}"))
    }

    @Test fun `key-on ramping up to the normal rate is a low span`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 2_017)
        for (k in 1..8) d.rate(30_000L + (k - 1) * 250, 30_000L + k * 250, 2_017 * k / 8)
        d.rate(32_000, 40_000, 2_017)
        assertEquals(shown(), listOf(TYPE_LOW), reports.map { it.type })
    }

    @Test fun `exactly the span length is a span, a quarter second less is not`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        d.rate(20_000 + ReceptionStall.STALL_MS - 250, 30_000, 100)     // silent 4.75 s
        assertEquals(shown(), 0, reports.size)
        d.rate(30_000 + ReceptionStall.STALL_MS, 40_000, 100)           // silent 5.00 s
        assertEquals(shown(), 1, reports.size)
        assertTrue(shown(), reports.single().message.startsWith("5.00 s below"))
    }

    @Test fun `a quarter second at exactly the share is not low`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 400)                                            // 100 per quarter second
        d.rate(20_000, 30_000, 100)                                       // exactly 25 per quarter second
        d.rate(30_000, 32_000, 400)
        d.end(32_000)
        assertEquals(shown(), 0, reports.size)
    }

    @Test fun `two stalls are two reports, and a burst does not raise the rate`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        d.frames(26_000, 26_010, 200)                                     // a burst ends 6 s of silence
        d.rate(26_250, 40_000, 100)
        d.frames(47_000, 47_010, 200)                                     // and 7 s of silence
        d.rate(47_250, 50_000, 100)
        assertEquals(shown(), listOf(TYPE_STALL, TYPE_STALL), reports.map { it.type })
        assertTrue(shown(), reports[1].message.startsWith("7.00 s below 25 % of this connection's rate (100 frames/s"))
        assertEquals(40_000L, reports[1].startMs)
    }

    @Test fun `the end of the connection reports a low span that was not silent`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        for (t in 20_500L until 30_000L step 1_500L) d.frames(t, t + 1, 1)
        d.end(30_000)
        assertEquals(shown(), listOf(TYPE_LOW), reports.map { it.type })
        assertTrue(shown(), reports.single().message.endsWith(
            "${ReceptionStall.CONNECTION_ENDED}. ${ReceptionStall.NOT_TOLD_APART}"))
    }

    @Test fun `the end of the connection after total silence reports nothing`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        for (t in 20_500L until 24_000L step 1_500L) d.frames(t, t + 1, 1)   // last frame at 23.5 s
        d.end(23_500 + ReceptionStall.STALL_MS)
        assertEquals(shown(), 0, reports.size)
    }

    @Test fun `a silent span that reaches the bound restarts the rate without a report`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 400)
        d.rate(20_000 + ReceptionStall.MAX_LOW_MS + 1_000, 20_000 + ReceptionStall.MAX_LOW_MS + 21_000, 100)   // a new rate
        assertEquals(shown(), 0, reports.size)
        val t = 20_000 + ReceptionStall.MAX_LOW_MS + 21_000
        d.frames(t + 6_000, t + 6_010, 200)                               // 6 s of silence, ended by a burst
        d.rate(t + 6_250, t + 10_000, 100)
        assertEquals(shown(), listOf(TYPE_STALL), reports.map { it.type })
        assertTrue(shown(), reports.single().message.contains("(100 frames/s"))   // measured afresh
    }

    @Test fun `a low span with frames that reaches the bound is reported still low`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        for (t in 20_500L until 20_000L + ReceptionStall.MAX_LOW_MS + 5_000 step 1_500L) d.frames(t, t + 1, 1)
        assertEquals(shown(), listOf(TYPE_LOW), reports.map { it.type })
        assertEquals(20_000L, reports.single().startMs)
        assertTrue(shown(), reports.single().message.startsWith("300.00 s below"))
        assertTrue(shown(), reports.single().message.contains("still low after 300 s; the rate is measured afresh."))
    }

    @Test fun `a bus slower than the minimum rate never reports`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, ReceptionStall.MIN_RATE_FPS - 4)
        d.rate(40_000, 41_000, ReceptionStall.MIN_RATE_FPS - 4)
        d.end(60_000)
        assertEquals(shown(), 0, reports.size)
    }

    @Test fun `a rate that has sunk below the minimum reports nothing`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 80)                                             // 20 per quarter second
        d.rate(20_000, 40_000, 40)                                        // not low (≥ 5); the rate sinks to 10 a quarter
        for (t in 40_500L until 50_000L step 1_500L) d.frames(t, t + 1, 1)
        d.rate(50_000, 52_000, 40)
        d.end(52_000)
        assertEquals(shown(), 0, reports.size)
    }

    @Test fun `nothing is reported before the connection has a rate of its own`() {
        val d = ReceptionStall()
        d.rate(0, 9_750, 100)                                             // 39 quarter seconds
        for (t in 10_500L until 30_000L step 1_500L) d.frames(t, t + 1, 1)
        d.end(30_000)
        assertEquals(shown(), 0, reports.size)
    }
}
