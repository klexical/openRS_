package com.openrs.dash

import com.openrs.dash.data.PullTracker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PullTracker — rc.6 (#174): WOT pull detection behind "% of pulls with KR > 0".
 * Pull = throttle ≥ 80 % at ≥ 2500 RPM for ≥ 2 s; gaps ≤ 2 s (gear change) don't end it.
 */
class PullTrackerTest {

    private fun PullTracker.wot(fromMs: Long, toMs: Long, stepMs: Long = 1_000L, rpm: Double = 5000.0) {
        var t = fromMs
        while (t <= toMs) { update(t, rpm, 100.0); t += stepMs }
    }

    @Test
    fun `sustained WOT above min rpm counts once`() {
        val p = PullTracker()
        p.wot(0, 6_000)
        assertEquals(1, p.pullCount)
        assertEquals(0, p.knockPullCount)
    }

    @Test
    fun `stab shorter than 2 s is not a pull`() {
        val p = PullTracker()
        p.wot(0, 1_000)
        p.update(2_000, 5000.0, 10.0)
        p.update(5_000, 900.0, 0.0)
        assertEquals(0, p.pullCount)
    }

    @Test
    fun `low rpm or part throttle never qualifies`() {
        val p = PullTracker()
        p.wot(0, 5_000, rpm = 2000.0)
        var t = 6_000L
        while (t <= 12_000) { p.update(t, 5000.0, 60.0); t += 1_000 }
        assertEquals(0, p.pullCount)
    }

    @Test
    fun `short lift is one pull, long lift starts a second`() {
        val p = PullTracker()
        p.wot(0, 3_000)
        p.update(4_000, 4000.0, 20.0)   // gear change, 1 s lift
        p.wot(5_000, 8_000)
        assertEquals(1, p.pullCount)
        p.update(9_000, 2000.0, 0.0)
        p.update(12_000, 2000.0, 0.0)   // > 2 s since last WOT sample → pull ended
        p.wot(13_000, 16_000)
        assertEquals(2, p.pullCount)
    }

    @Test
    fun `knock during a pull marks it once, knock outside a pull does not`() {
        val p = PullTracker()
        p.markKnock(100)                // cruising — no pull active
        p.wot(1_000, 3_000)
        p.markKnock(2_500)
        p.markKnock(2_800)              // same pull, counted once
        p.wot(4_000, 6_000)
        assertEquals(1, p.pullCount)
        assertEquals(1, p.knockPullCount)
    }

    @Test
    fun `knock before the pull reaches 2 s is credited when it qualifies`() {
        val p = PullTracker()
        p.update(0, 5000.0, 100.0)
        p.markKnock(500)
        p.update(1_000, 5000.0, 100.0)
        assertEquals(0, p.pullCount)
        p.update(2_000, 5000.0, 100.0)
        assertEquals(1, p.pullCount)
        assertEquals(1, p.knockPullCount)
    }

    @Test
    fun `KR read just after the last WOT sample still belongs to the pull`() {
        val p = PullTracker()
        p.wot(0, 3_000)
        p.markKnock(4_200)              // KR polled ~1.2 s after lift
        assertEquals(1, p.knockPullCount)
    }
}
