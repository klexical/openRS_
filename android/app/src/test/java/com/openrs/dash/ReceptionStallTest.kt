package com.openrs.dash

import com.openrs.dash.can.ReceptionStall
import com.openrs.dash.can.ReceptionStall.Companion.TYPE_QUIET
import com.openrs.dash.can.ReceptionStall.Companion.TYPE_STALL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #356 — [ReceptionStall] on its own, clock passed in (ms). SYNTHETIC frame times built from the issue's capture figures
 * where named: clean rate 2,017 frames/s; the stall's first 0.1 s held 343 frames, then frames mostly in pairs about
 * 1.5 s apart; it ended after about 10.66 s with 2,406 frames in 50 ms; before it, 0.55–0.6 s delays each ended by a
 * burst (about 90 % of the frames due). The cases that depend on where a gap falls against the quarter seconds run at
 * every phase from 0 to 249 ms. The connection-level behaviour is ReceptionStallReplayTest.
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
    private fun rateOf(r: ReceptionStall.Report) = Regex("""\((\d+) frames/s""").find(r.message)!!.groupValues[1].toInt()

    /** The capture's stall from [t] (343 frames in its first 0.1 s, then pairs), its burst at t + 10.66 s, then clean. */
    private fun ReceptionStall.captureStall(t: Long) {
        frames(t, t + 100, 343)
        for (k in 0 until 6) frames(t + 1_600 + k * 1_500L, t + 1_609 + k * 1_500L, 2)
        frames(t + 10_660, t + 10_710, 2_406)
        rate(t + 10_710, t + 20_000, 2_017)
    }

    /** Runs [scenario] at every phase from 0 to 249 ms; returns the phases whose reports fail [ok], with what they were. */
    private fun sweep(scenario: ReceptionStall.(Long) -> Unit, ok: (List<ReceptionStall.Report>) -> Boolean): List<String> =
        (0L until ReceptionStall.BUCKET_MS).mapNotNull { p ->
            reports.clear()
            ReceptionStall().scenario(p)
            if (ok(reports)) null else "phase $p: ${shown()}"
        }.also { reports.clear() }

    // ── The issue's stall, at every phase (#356 review 2, N3 and N4) ────────────────────────────────────────────────────

    @Test fun `the capture's stall after a clean rate is one stall at every phase`() {
        val bad = sweep({ p -> rate(p, p + 20_400, 2_017); captureStall(p + 20_400) }) { r ->
            r.size == 1 && r[0].type == TYPE_STALL && rateOf(r[0]) in 1_950..2_085   // 2,017 ± about 3 %
        }
        assertTrue("${bad.size} of 250 phases: ${bad.take(3)}", bad.isEmpty())
    }

    @Test fun `the capture's stall after its delay phase is one stall at every phase, with the rate near the true one`() {
        val bad = sweep({ p ->
            rate(p, p + 20_000, 2_017)
            for (k in 0 until 70) frames(p + 20_000 + k * 600L + 590, p + 20_000 + k * 600L + 600, 1_089)   // 90 % of 1,210
            captureStall(p + 62_000)
        }) { r -> r.size == 1 && r[0].type == TYPE_STALL && rateOf(r[0]) in 1_700..2_100 }
        assertTrue("${bad.size} of 250 phases: ${bad.take(3)}", bad.isEmpty())
    }

    @Test fun `the capture's stall is reported from its first low quarter second, with its length and frames`() {
        val d = ReceptionStall()
        d.rate(0, 20_400, 2_017)
        d.captureStall(20_400)
        assertEquals(shown(), 1, reports.size)
        val r = reports.single()
        assertEquals(TYPE_STALL, r.type)
        assertEquals(20_500L, r.startMs)
        assertTrue(r.message, r.message.startsWith("10.50 s below 25 % of this connection's rate ("))
        assertTrue(r.message, rateOf(r) in 1_950..2_085)        // 2,017 ± about 3 %: the rate counts the stall's first quarter
        assertTrue(r.message, r.message.contains("; 12 frames in it; then "))
        assertTrue(r.message, r.message.endsWith("frames in the half second after it (a catch-up burst)"))
    }

    // ── Quiet spans ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `key-on at the normal rate after a silence is a quiet span at every phase`() {
        val bad = sweep({ p -> rate(p, p + 20_000, 2_017); rate(p + 30_000, p + 40_000, 2_017) }) { r ->
            r.size == 1 && r[0].type == TYPE_QUIET &&
                r[0].message.endsWith("${ReceptionStall.CAME_BACK}. ${ReceptionStall.NOT_TOLD_APART}")
        }
        assertTrue("${bad.size} of 250 phases: ${bad.take(3)}", bad.isEmpty())
    }

    @Test fun `key-on ramping up to the normal rate is a quiet span`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 2_017)
        for (k in 1..8) d.rate(30_000L + (k - 1) * 250, 30_000L + k * 250, 2_017 * k / 8)
        d.rate(32_000, 40_000, 2_017)
        assertEquals(shown(), listOf(TYPE_QUIET), reports.map { it.type })
    }

    // ── The bound on a connection with no read timeout (#356 review 2, N1) ────────────────────────────────────────────

    @Test fun `a silence of 299 s then key-on is a quiet span of 299 s`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        d.rate(319_000, 330_000, 100)
        assertEquals(shown(), listOf(TYPE_QUIET), reports.map { it.type })
        assertTrue(shown(), reports.single().message.startsWith("299.00 s below"))
    }

    @Test fun `a silence of 301 s is a quiet span at the bound, and the rate is measured afresh`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 800)                                            // 200 a quarter second: the old rate
        d.rate(321_000, 340_000, 100)                                     // key-on at a quarter of it: a new rate
        assertEquals(shown(), listOf(TYPE_QUIET), reports.map { it.type })
        assertEquals(20_000L, reports.single().startMs)
        assertTrue(shown(), reports.single().message.startsWith("300.00 s below"))
        assertTrue(shown(), reports.single().message.contains("; 0 frames in it; still low after 300 s; the rate is measured afresh."))
        d.frames(346_000, 346_010, 200)                                   // 6 s of silence, ended by a burst
        d.rate(346_250, 350_000, 100)
        assertEquals(shown(), listOf(TYPE_QUIET, TYPE_STALL), reports.map { it.type })
        assertTrue(shown(), reports[1].message.contains("(100 frames/s"))   // the new rate
    }

    @Test fun `a silence of 600 s is one quiet span at the bound`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        d.rate(620_000, 630_000, 100)
        assertEquals(shown(), listOf(TYPE_QUIET), reports.map { it.type })
        assertTrue(shown(), reports.single().message.contains("still low after 300 s"))
    }

    @Test fun `a gap with frames that reaches the bound is a quiet span, still low`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        for (t in 20_500L until 20_000L + ReceptionStall.MAX_LOW_MS + 5_000 step 1_500L) d.frames(t, t + 1, 1)
        assertEquals(shown(), listOf(TYPE_QUIET), reports.map { it.type })
        assertEquals(20_000L, reports.single().startMs)
        assertTrue(shown(), reports.single().message.startsWith("300.00 s below"))
    }

    // ── The end of the connection (#356 review 2, N2) ──────────────────────────────────────────────────────────────────

    @Test fun `a trickle, then silence until the read timeout, is a quiet span`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        for (t in 20_500L until 30_000L step 1_500L) d.frames(t, t + 1, 1)   // last frame at 29.5 s
        d.end(49_500)                                                     // the 20 s read timeout
        assertEquals(shown(), listOf(TYPE_QUIET), reports.map { it.type })
        assertTrue(shown(), reports.single().message.endsWith(
            "${ReceptionStall.CONNECTION_ENDED}. ${ReceptionStall.NOT_TOLD_APART}"))
    }

    @Test fun `total silence until the read timeout reports nothing, though the first quarter second held the last traffic`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        d.frames(20_000, 20_050, 5)                                       // the tail of the traffic, in the gap's first quarter
        d.end(40_050)
        assertEquals(shown(), 0, reports.size)
    }

    @Test fun `a frame in the quarter second under way at the end makes the gap a quiet span`() {
        val d = ReceptionStall()
        d.rate(0, 20_000, 100)
        d.frames(29_900, 29_901, 1)
        d.end(29_950)
        assertEquals(shown(), listOf(TYPE_QUIET), reports.map { it.type })
    }

    // ── Boundaries and the rate ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `exactly the gap length is a gap, a quarter second less is not`() {
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
        d.rate(20_000, 40_000, 40)                                        // not low; the rate sinks to 10 a quarter
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
