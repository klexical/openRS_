package com.openrs.dash

import com.openrs.dash.diagnostics.FrameTimes
import com.openrs.dash.diagnostics.LinkLockWait
import com.openrs.dash.diagnostics.MainThreadLag
import com.openrs.dash.diagnostics.TimingCounts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #421: the UI timing counters at their pure seams — main-thread lateness from an injected clock, screen frames by tab,
 * and the OBD merge's wait for the link lock from two injected times. "Over" is strictly over: a value exactly on an
 * edge is not counted. Synthetic times only.
 */
class UiTimingTest {
    private val ms = 1_000_000L

    private fun TimingCounts.over() = overMs.toSortedMap().values.toList()

    // ── Main-thread lateness (seam 1) ────────────────────────────────────────

    private var now = 0L
    private val lag = MainThreadLag { now }

    /** One tick that wakes [lateNanos] after its 100 ms. */
    private fun tick(lateNanos: Long) { lag.asking(); now += MainThreadLag.PERIOD_MS * ms + lateNanos; lag.woke() }

    @Test fun `main thread - lateness is the time past the 100 ms asked for, counted strictly over 50, 200, 700 ms`() {
        listOf(0L, 50 * ms, 50 * ms + 1, 200 * ms, 200 * ms + 1, 700 * ms, 700 * ms + 1).forEach { tick(it) }
        val c = lag.read()
        assertEquals(7L, c.count)
        assertEquals("over 50 / 200 / 700", listOf(5L, 3L, 1L), c.over())
        assertEquals(700 * ms + 1, c.longestNanos)
        assertEquals(700.0, c.longestMs, 0.0)
    }

    @Test fun `main thread - the longest is the largest lateness, not the last`() {
        tick(120 * ms); tick(30 * ms); tick(80 * ms)
        assertEquals(120 * ms, lag.read().longestNanos)
        assertEquals(120.0, lag.read().longestMs, 0.0)
    }

    @Test fun `main thread - a wake with no ask before it is not a sample`() {
        now = 5_000 * ms; lag.woke()
        tick(60 * ms); lag.woke()                     // a second wake for the same ask
        assertEquals(1L, lag.read().count)
        assertEquals(listOf(1L, 0L, 0L), lag.read().over())
    }

    @Test fun `main thread - a reset on a new connection starts from zero and drops an ask in flight`() {
        tick(300 * ms)
        lag.asking(); lag.reset(); now += 900 * ms; lag.woke()
        val c = lag.read()
        assertEquals(0L, c.count); assertEquals(listOf(0L, 0L, 0L), c.over()); assertEquals(0L, c.longestNanos)
        tick(10 * ms)
        assertEquals(1L, lag.read().count)
    }

    // ── Screen frames by tab (seam 2) ────────────────────────────────────────

    @Test fun `frames - counted to the tab in front, strictly over 32, 100, 700 ms`() {
        val f = FrameTimes()
        listOf(16 * ms, 32 * ms, 32 * ms + 1, 100 * ms, 100 * ms + 1, 700 * ms, 700 * ms + 1).forEach { f.frame(0, it) }
        f.frame(2, 40 * ms); f.frame(2, 10 * ms)
        val r = f.read()
        val drive = r.getValue("DRIVE")
        assertEquals(7L, drive.count)
        assertEquals("over 32 / 100 / 700", listOf(5L, 3L, 1L), drive.over())
        assertEquals(700 * ms + 1, drive.longestNanos)
        val thermal = r.getValue("THERMAL")
        assertEquals(2L, thermal.count); assertEquals(listOf(1L, 0L, 0L), thermal.over()); assertEquals(40.0, thermal.longestMs, 0.0)
    }

    @Test fun `frames - every tab is listed in pager order, one with no frames reads zero`() {
        val f = FrameTimes()
        f.frame(3, 5 * ms)
        val r = f.read()
        assertEquals(listOf("DRIVE", "PERF", "THERMAL", "TRIP", "GARAGE"), r.keys.toList())
        val garage = r.getValue("GARAGE")
        assertEquals(0L, garage.count); assertEquals(listOf(0L, 0L, 0L), garage.over()); assertEquals(0L, garage.longestNanos)
        assertEquals(1L, r.getValue("TRIP").count)
    }

    @Test fun `frames - a page outside the five tabs is not counted, and a reset clears every tab`() {
        val f = FrameTimes()
        f.frame(5, 900 * ms); f.frame(-1, 900 * ms)
        assertEquals(0L, f.read().values.sumOf { it.count })
        f.frame(1, 50 * ms); f.reset()
        assertEquals(0L, f.read().values.sumOf { it.count })
        assertEquals(5, f.read().size)
    }

    @Test fun `frames - the longest keeps one decimal of a millisecond`() {
        val f = FrameTimes()
        f.frame(0, 33_449_999L)
        assertEquals(33.4, f.read().getValue("DRIVE").longestMs, 0.0)
        f.frame(0, 33_450_000L)
        assertEquals(33.5, f.read().getValue("DRIVE").longestMs, 0.0)
    }

    // ── The OBD merge's wait for the link lock (seam 3) ──────────────────────

    @Test fun `lock wait - the time from before deliver to inside it, strictly over 5, 50, 200 ms`() {
        val w = LinkLockWait()
        val t0 = 7_000 * ms
        listOf(0L, 5 * ms, 5 * ms + 1, 50 * ms, 50 * ms + 1, 200 * ms, 200 * ms + 1).forEach { w.waited(t0, t0 + it) }
        val c = w.read()
        assertEquals(7L, c.count)
        assertEquals("over 5 / 50 / 200", listOf(5L, 3L, 1L), c.over())
        assertEquals(200 * ms + 1, c.longestNanos)
    }

    @Test fun `lock wait - the order of the two times matters, a clock step back counts as no wait`() {
        val w = LinkLockWait()
        w.waited(100 * ms, 40 * ms)
        val c = w.read()
        assertEquals(1L, c.count); assertEquals(listOf(0L, 0L, 0L), c.over()); assertEquals(0L, c.longestNanos)
        w.waited(40 * ms, 100 * ms)
        assertEquals(60 * ms, w.read().longestNanos)
        w.reset()
        assertEquals(0L, w.read().count)
    }
}
