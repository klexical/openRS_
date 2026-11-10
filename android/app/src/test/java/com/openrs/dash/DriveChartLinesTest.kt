package com.openrs.dash

import com.openrs.dash.ui.trip.TimeSeriesMath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TRIP chart lines on long or sparse drives: the chart draws at most 360 samples, and two drawn samples further apart
 * than the bridge threshold are not joined. Measured between drawn samples, a fixed 5 s broke every pair of a 1 Hz drive
 * longer than 36 min (drawn ≥ 6 s apart) and of GPS that is sparse throughout — dots only, no line, no fill. The bridge
 * follows the drawn cadence; genuine gaps still break through the full-resolution gap rule (#285).
 */
class DriveChartLinesTest {

    private fun drive(n: Int, at: (Int) -> Long) = List(n) { 1_700_000_000_000L + at(it) }
    private fun runs(ts: List<Long>, valid: List<Boolean> = List(ts.size) { true }, breakOnInvalid: Boolean = false) =
        TimeSeriesMath.timeAxis(ts, 360).runs(valid, breakOnInvalid)

    @Test
    fun `a one-hour drive at 1 Hz draws one connected line`() {
        assertEquals(1, runs(drive(3_600) { it * 1_000L }).size)
    }

    @Test
    fun `GPS sparse throughout - fixes 20 to 40 s apart - draws one connected line`() {
        var t = 0L
        val ms = LongArray(30) { i -> if (i > 0) t += 20_000L + ((i * 7) % 3) * 10_000L; t }
        assertEquals(1, runs(drive(30) { ms[it] }).size)
    }

    @Test
    fun `a genuine 25 s gap in a one-hour 1 Hz drive still breaks the line once`() {
        // Drawn samples are 10 s apart; the pair across the gap is 35 s apart — the gap rule breaks it, not the spacing
        val ts = drive(3_600) { it * 1_000L + if (it > 1_800) 25_000L else 0L }
        assertEquals(2, runs(ts).size)
    }

    @Test
    fun `a pressure channel still breaks at an unavailable sample on a long drive`() {
        val ts = drive(3_600) { it * 1_000L }
        val valid = List(3_600) { it != 1_805 }                 // inside drawn bucket 180 (1800..1809)
        assertEquals(listOf(0..179, 181..359).map { it.toList() }, runs(ts, valid, breakOnInvalid = true))
    }

    @Test
    fun `a short 1 Hz drive keeps the 5 s bridge - a 6 s spacing breaks`() {
        val ts = drive(300) { it * 1_000L + if (it > 150) 5_000L else 0L }
        assertEquals(2, runs(ts).size)
    }
}
