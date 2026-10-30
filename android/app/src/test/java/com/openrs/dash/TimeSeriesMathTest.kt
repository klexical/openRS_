package com.openrs.dash

import com.openrs.dash.ui.trip.TimeSeriesMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TimeSeriesMath — rc.5 (#188): sentinel samples are gaps (never plotted as values),
 * short gaps are bridged, long gaps break the line, and overlaid channels share a range.
 */
class TimeSeriesMathTest {

    @Test
    fun `invalid samples are skipped and short gaps bridged`() {
        val ts = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L)
        val valid = listOf(true, false, true, false, true)
        assertEquals(listOf(listOf(0, 2, 4)), TimeSeriesMath.segments(ts, valid))
    }

    @Test
    fun `gap longer than the break threshold starts a new run`() {
        val ts = listOf(0L, 1_000L, 2_000L, 9_000L, 10_000L)
        val valid = listOf(true, true, false, true, true)
        assertEquals(listOf(listOf(0, 1), listOf(3, 4)), TimeSeriesMath.segments(ts, valid))
    }

    @Test
    fun `all-invalid series has no runs`() {
        assertEquals(emptyList<List<Int>>(),
            TimeSeriesMath.segments(listOf(0L, 1_000L), listOf(false, false)))
    }

    @Test
    fun `shared range spans both channels and ignores sentinels`() {
        // BOOST (gauge PSI) and TARGET where unpolled target would be −14.7 PSI
        val boost  = listOf(-5f, 12f, 18f) to listOf(true, true, true)
        val target = listOf(-14.7f, 20f, -14.7f) to listOf(false, true, false)
        assertEquals(-5f to 20f, TimeSeriesMath.sharedRange(listOf(boost, target)))
    }

    @Test
    fun `range is null when nothing is valid`() {
        assertNull(TimeSeriesMath.sharedRange(listOf(listOf(-1f, -1f) to listOf(false, false))))
    }
    // ── Pressure channels (#207 follow-up): a sample without a reading of the channel's own source breaks the line ──

    @Test
    fun `pressure channel breaks at every unavailable or other-source sample, even a short one`() {
        // TIP, TIP, CAN, TIP, NONE, TIP at 1 Hz — the TIP line must not bridge the CAN or NONE second
        val ts = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L)
        val tipValid = listOf(true, true, false, true, false, true)
        assertEquals(listOf(listOf(0, 1), listOf(3), listOf(5)),
            TimeSeriesMath.segments(ts, tipValid, breakOnInvalid = true))
        assertEquals("other channels keep bridging short gaps", listOf(listOf(0, 1, 3, 5)), TimeSeriesMath.segments(ts, tipValid))
    }

    @Test
    fun `downsampling keeps the boundary — a bucket with any unavailable sample is unavailable for pressure`() {
        // 12 samples into 6 buckets (stride 2): the stride sampler kept indices 0,2,4,6,8,10 and dropped the CAN sample at 3
        val tipValid = listOf(true, true, true, false, true, true, true, true, true, true, true, true)
        val buckets = TimeSeriesMath.buckets(tipValid.size, 6)
        assertEquals(listOf(0, 2, 4, 6, 8, 10), buckets.map { it.first })
        val bucketValid = TimeSeriesMath.bucketValidity(buckets, tipValid, breakOnInvalid = true)
        assertEquals(listOf(true, false, true, true, true, true), bucketValid)
        assertEquals("other channels: the representative sample decides, as before",
            listOf(true, true, true, true, true, true), TimeSeriesMath.bucketValidity(buckets, tipValid, breakOnInvalid = false))
        val ts = buckets.map { it.first * 1_000L }
        assertEquals(listOf(listOf(0), listOf(2, 3, 4, 5)), TimeSeriesMath.segments(ts, bucketValid, breakOnInvalid = true))
    }
}
