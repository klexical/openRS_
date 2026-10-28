package com.openrs.dash

import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.ui.trip.seekIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/** Replay ±10s seeks by time, not sample count. */
class ReplaySeekTest {

    private fun pts(stepMs: Long, n: Int) = List(n) { DrivePointEntity(driveId = 1, timestamp = it * stepMs, lat = 0.0, lng = 0.0) }

    @Test
    fun `plus and minus ten seconds land ten seconds away at 1 Hz and at 5 s spacing`() {
        assertEquals(20, seekIndex(pts(1_000, 60), 10, 10_000))
        assertEquals(0, seekIndex(pts(1_000, 60), 10, -10_000))
        assertEquals(12, seekIndex(pts(5_000, 60), 10, 10_000))   // was +10 samples = 50 s
        assertEquals(8, seekIndex(pts(5_000, 60), 10, -10_000))
    }

    @Test
    fun `seeks clamp at the ends and always move across a long gap`() {
        assertEquals(59, seekIndex(pts(1_000, 60), 55, 10_000))
        assertEquals(0, seekIndex(pts(1_000, 60), 3, -10_000))
        assertEquals(6, seekIndex(pts(60_000, 10), 5, 10_000))    // next point is 60 s away — still steps
    }
}
