package com.openrs.dash

import com.openrs.dash.ui.trip.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {

    @Test
    fun `zero and negative durations read 0 00`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(-1))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun `formats hours once the drive passes an hour`() {
        assertEquals("1:01:01", formatDuration(3_661_000))
    }
}
