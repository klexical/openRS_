package com.openrs.dash

import com.openrs.dash.ui.trip.ReplayTimeline
import com.openrs.dash.ui.trip.TimeSeriesMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TRIP replay (#285): playback, the slider, its labels and the ±10 s seeks share one elapsed-time playhead. At 1× a
 * gap plays as long as it lasted (it was one tick, one stored point per second), the sample shown is the last one
 * recorded at or before the playhead, and inside a gap the replay offers to skip to the next sample.
 */
class ReplayTimelineTest {

    private fun timeline(vararg ms: Long) = ReplayTimeline(ms.map { 1_700_000_000_000L + it })
    private fun every(stepMs: Long, n: Int) = ReplayTimeline(List(n) { 1_700_000_000_000L + it * stepMs })

    /** [ticks] playback ticks of 100 ms wall time at [speed]. */
    private fun ReplayTimeline.play(from: Long, ticks: Int, speed: Float): Long {
        var p = from
        repeat(ticks) { p = advance(p, 100L, speed) }
        return p
    }

    private val gapDrive = timeline(0, 1_000, 61_000, 62_000)

    @Test
    fun `at 1x a 60 s gap takes 60 s - after 31 s the playhead is in it, still on sample 2`() {
        val p = gapDrive.play(0L, 310, 1f)
        assertEquals(31_000L, p)
        assertEquals(1, gapDrive.indexAt(p))
        assertEquals("0:31", gapDrive.positionText(p))
        assertEquals(TimeSeriesMath.Gap(1, 1_000L, 61_000L), gapDrive.gapAt(p))
    }

    @Test
    fun `2x and 4x advance the playhead two and four times as fast, and stop at the end`() {
        assertEquals(20_000L, gapDrive.play(0L, 100, 2f))
        assertEquals(40_000L, gapDrive.play(0L, 100, 4f))
        assertEquals(62_000L, gapDrive.play(0L, 1_000, 4f))
        assertEquals(3, gapDrive.indexAt(62_000L))
    }

    @Test
    fun `pause keeps the playhead, resume carries on at the new speed`() {
        val paused = gapDrive.play(0L, 50, 1f)          // 5 s, then no ticks while paused
        assertEquals(5_000L, paused)
        assertEquals(25_000L, gapDrive.play(paused, 50, 4f))
    }

    @Test
    fun `a seek into a gap shows the skip, and the skip lands on the next sample`() {
        val p = gapDrive.seek(gapDrive.positionOf(1), 10_000L)
        assertEquals(11_000L, p)
        val g = assertNotNullGap(gapDrive.gapAt(p))
        assertEquals(2, gapDrive.indexAt(g.toMs))
        assertNull(gapDrive.gapAt(g.toMs))
        assertNull(gapDrive.gapAt(1_000L))                // on the sample before the gap: not in it yet
    }

    private fun assertNotNullGap(g: TimeSeriesMath.Gap?): TimeSeriesMath.Gap { assertNotNull(g); return g!! }

    @Test
    fun `seeks clamp to the drive`() {
        assertEquals(0L, gapDrive.seek(3_000L, -10_000L))
        assertEquals(62_000L, gapDrive.seek(60_000L, 10_000L))
    }

    @Test
    fun `labels - playhead time and drive length`() {
        assertEquals("0:00", gapDrive.positionText(0L))
        assertEquals("1:02", gapDrive.durationText())
        assertEquals(62_000L, gapDrive.durationMs)
    }

    @Test
    fun `smoke drive - 270 s in, the playhead is in the 32 s loss and the label says 4 30`() {
        val smoke = ReplayTimeline(List(317) { i -> 1_700_000_000_000L + (if (i <= 259) i else i + 31) * 1_000L })
        val p = smoke.play(0L, 2_700, 1f)
        assertEquals(259, smoke.indexAt(p))
        assertEquals("4:30", smoke.positionText(p))
        assertNotNull(smoke.gapAt(p))
        assertEquals("5:47", smoke.durationText())
    }

    @Test
    fun `repeated and out-of-order timestamps - the last sample at or before the playhead`() {
        val t = timeline(0, 1_000, 1_000, 500, 3_000)
        assertEquals(3_000L, t.durationMs)
        assertEquals(0, t.indexAt(999L))
        assertEquals(3, t.indexAt(1_000L))
        assertEquals(4, t.indexAt(3_000L))
    }

    @Test
    fun `no time base - the playhead counts samples and the labels say so`() {
        val t = timeline(0, 0, 0, 0)
        assertEquals(false, t.hasTimeBase)
        assertEquals(3_000L, t.durationMs)
        assertEquals(2, t.indexAt(2_000L))
        assertEquals("SAMPLE 3 / 4", t.positionText(2_000L))
        assertEquals("", t.durationText())
        assertEquals(emptyList<TimeSeriesMath.Gap>(), t.gaps)
    }

    // ── The ±10 s cases from ReplaySeekTest, on the playhead ──

    private fun ReplayTimeline.seekFrom(index: Int, deltaMs: Long) = indexAt(seek(positionOf(index), deltaMs))

    @Test
    fun `plus and minus ten seconds land ten seconds away at 1 Hz and at 5 s spacing`() {
        assertEquals(20, every(1_000, 60).seekFrom(10, 10_000))
        assertEquals(0, every(1_000, 60).seekFrom(10, -10_000))
        assertEquals(12, every(5_000, 60).seekFrom(10, 10_000))
        assertEquals(8, every(5_000, 60).seekFrom(10, -10_000))
    }

    @Test
    fun `seeks clamp at the ends, and 10 s into a 60 s spacing stays on the sample before it`() {
        assertEquals(59, every(1_000, 60).seekFrom(55, 10_000))
        assertEquals(0, every(1_000, 60).seekFrom(3, -10_000))
        // Was "always move at least one sample" (index 6, 60 s on): the playhead moves 10 s, inside the spacing
        assertEquals(5, every(60_000, 10).seekFrom(5, 10_000))
    }
}
