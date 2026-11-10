package com.openrs.dash

import com.openrs.dash.ui.trip.TimeSeriesMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * TRIP chart time axis (#285): drawn samples sit at their elapsed time, so a tick is under the sample recorded then,
 * across a gap or uneven spacing too. Gaps are inferred from the timestamps alone (a stored drive records no cause):
 * a spacing over max(5 s, 4 × the drive's typical spacing).
 */
class DriveTimeAxisTest {

    /** |position on the axis − elapsed time| of every drawn sample, ms. */
    private fun offsets(axis: TimeSeriesMath.TimeAxis, elapsed: List<Long>): List<Double> =
        axis.buckets.mapIndexed { j, r -> abs(axis.xFrac[j].toDouble() * axis.totalMs - elapsed[r.first]) }

    /** Samples at [ms] from the start (any epoch offset; only differences count). */
    private fun drive(vararg ms: Long) = ms.map { 1_700_000_000_000L + it }

    // ── The issue's acceptance: the smoke drive's shape (317 points, one 32 s link loss) ──

    /** 1 Hz, samples 0..259 at 0..259 s, nothing for 32 s, sample 260 at 291 s … sample 316 at 347 s. */
    private val smoke: List<Long> = List(317) { i -> 1_700_000_000_000L + (if (i <= 259) i else i + 31) * 1_000L }

    @Test
    fun `smoke drive - every drawn sample sits within one bucket of its timestamp`() {
        val axis = TimeSeriesMath.timeAxis(smoke, 360)
        val elapsed = smoke.map { it - smoke[0] }
        assertEquals(347_000L, axis.totalMs)
        // 317 samples ≤ 360: one per bucket, 1 s apart — so one bucket is 1 s (by index, sample 259 was 25.4 s off)
        val worst = offsets(axis, elapsed).max()
        assertTrue("worst offset $worst ms", worst <= 1_000.0)
    }

    @Test
    fun `smoke drive - the 32 s loss is one gap between samples 259 and 260`() {
        val axis = TimeSeriesMath.timeAxis(smoke, 360)
        assertEquals(listOf(TimeSeriesMath.Gap(259, 259_000L, 291_000L)), axis.gaps)
        assertTrue(axis.breakBefore[260])
        assertEquals(listOf((0..259).toList(), (260..316).toList()), axis.runs(List(317) { true }, false))
    }

    // ── The recommendation's case: fixes at 0, 1, 61 and 62 s ──

    @Test
    fun `fixes at 0, 1, 61 and 62 s - inner points near 1·6 and 98·4 percent, one gap, line broken`() {
        val axis = TimeSeriesMath.timeAxis(drive(0, 1_000, 61_000, 62_000), 360)
        assertEquals(0f, axis.xFrac[0], 1e-6f)
        assertEquals(1f / 62, axis.xFrac[1], 1e-4f)
        assertEquals(61f / 62, axis.xFrac[2], 1e-4f)
        assertEquals(1f, axis.xFrac[3], 1e-6f)
        assertEquals(listOf(TimeSeriesMath.Gap(1, 1_000L, 61_000L)), axis.gaps)
        assertEquals(listOf(false, false, true, false), axis.breakBefore)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), axis.runs(List(4) { true }, false))
    }

    @Test
    fun `three fixes 0, 1, 61 s - the last spacing is a gap`() {
        val axis = TimeSeriesMath.timeAxis(drive(0, 1_000, 61_000), 360)
        assertEquals(listOf(TimeSeriesMath.Gap(1, 1_000L, 61_000L)), axis.gaps)
    }

    // ── Controls: sparse GPS is not a gap ──

    @Test
    fun `uniformly sparse GPS - fixes every 20 to 40 s - no gap and each fix at its time`() {
        // Spacings 30, 40, 20, 30, 40, 20 … s
        var t = 0L
        val ms = LongArray(30) { i -> if (i > 0) t += 20_000L + ((i * 7) % 3) * 10_000L; t }
        val ts = drive(*ms)
        val axis = TimeSeriesMath.timeAxis(ts, 360)
        assertEquals(emptyList<TimeSeriesMath.Gap>(), axis.gaps)
        assertTrue(axis.breakBefore.none { it })
        val worst = offsets(axis, ms.toList()).max()
        assertTrue("worst offset $worst ms", worst <= 1.0)
    }

    @Test
    fun `threshold boundary at 1 Hz - 5000 ms is no gap, 5001 ms is one`() {
        fun oneLongSpacing(spacing: Long) = drive(*LongArray(20) { i -> if (i <= 9) i * 1_000L else 9_000L + spacing + (i - 10) * 1_000L })
        assertEquals(emptyList<TimeSeriesMath.Gap>(), TimeSeriesMath.timeAxis(oneLongSpacing(5_000L), 360).gaps)
        assertEquals(listOf(TimeSeriesMath.Gap(9, 9_000L, 14_001L)), TimeSeriesMath.timeAxis(oneLongSpacing(5_001L), 360).gaps)
    }

    @Test
    fun `threshold boundary at a 2 s cadence - 4 x median - 8000 ms is no gap, 8001 ms is one`() {
        fun oneLongSpacing(spacing: Long) = drive(*LongArray(20) { i -> if (i <= 9) i * 2_000L else 18_000L + spacing + (i - 10) * 2_000L })
        assertEquals(emptyList<TimeSeriesMath.Gap>(), TimeSeriesMath.timeAxis(oneLongSpacing(8_000L), 360).gaps)
        assertEquals(listOf(TimeSeriesMath.Gap(9, 18_000L, 26_001L)), TimeSeriesMath.timeAxis(oneLongSpacing(8_001L), 360).gaps)
    }

    @Test
    fun `one-point and two-point drives - no gap, no crash`() {
        val one = TimeSeriesMath.timeAxis(drive(0), 360)
        assertEquals(emptyList<TimeSeriesMath.Gap>(), one.gaps)
        assertEquals(listOf(0f), one.xFrac)
        val two = TimeSeriesMath.timeAxis(drive(0, 600_000), 360)    // one spacing is its own median
        assertEquals(emptyList<TimeSeriesMath.Gap>(), two.gaps)
        assertEquals(listOf(0f, 1f), two.xFrac)
    }

    // ── No time base, repeated and out-of-order timestamps ──

    @Test
    fun `no time base - samples spread evenly, no ticks, no gaps`() {
        val axis = TimeSeriesMath.timeAxis(drive(0, 0, 0, 0), 360)
        assertEquals(false, axis.hasTimeBase)
        assertEquals(listOf(0f, 1f / 3, 2f / 3, 1f), axis.xFrac)
        assertEquals(emptyList<TimeSeriesMath.Gap>(), axis.gaps)
        assertEquals(emptyList<Pair<String, Float>>(), TimeSeriesMath.timeTicks(axis.totalMs, 1_000f, 50f))
    }

    @Test
    fun `repeated and out-of-order timestamps never run backwards`() {
        val axis = TimeSeriesMath.timeAxis(drive(0, 1_000, 1_000, 3_000, 2_000, 4_000), 360)
        assertEquals(listOf(0L, 1_000L, 1_000L, 3_000L, 3_000L, 4_000L), axis.drawnElapsedMs)
        assertTrue(axis.xFrac.all { it in 0f..1f })
        assertTrue(axis.xFrac.zipWithNext().all { (a, b) -> a <= b })
    }

    // ── Downsampled: a gap inside a bucket still breaks the line ──

    @Test
    fun `720 points at 1 Hz with a 60 s loss inside bucket 180 - one gap, line broken before bucket 181`() {
        val ts = drive(*LongArray(720) { i -> i * 1_000L + if (i > 360) 60_000L else 0L })
        val axis = TimeSeriesMath.timeAxis(ts, 360)
        assertEquals(360, axis.buckets.size)
        assertEquals(360..361, axis.buckets[180])
        assertEquals(listOf(TimeSeriesMath.Gap(360, 360_000L, 421_000L)), axis.gaps)
        assertEquals(listOf(181), axis.breakBefore.indices.filter { axis.breakBefore[it] })
        assertEquals(2, axis.runs(List(720) { true }, false).size)
        val worst = offsets(axis, ts.map { it - ts[0] }).max()
        assertTrue("worst offset $worst ms", worst <= 1.0)
    }

    @Test
    fun `ticks label the time under them`() {
        // 0:00 at the left edge, 1:00 at 60 / 120 of the width
        val ticks = TimeSeriesMath.timeTicks(120_000L, 1_000f, 50f)
        assertEquals("0:00" to 0f, ticks.first())
        assertEquals(0.5f, ticks.first { it.first == "1:00" }.second, 1e-6f)
    }
}
