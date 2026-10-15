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
}
