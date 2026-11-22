package com.openrs.dash

import com.openrs.dash.demo.TripPreview
import com.openrs.dash.ui.trip.fixReadout
import com.openrs.dash.ui.trip.tripDot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #318 (QA's pair, from FINAL-318): the debug TRIP preview (`trippreview=recording` / `paused`, the screenshot frames) shows
 * its dot and its GPS readout. TRIP draws a drive's fix only while it is current (at most 2 s old), the preview's fix had no
 * time, and TRIP remembers the preview state once per mode: so the preview's fix is taken at its build time and re-stamped
 * on TRIP's clock (TripPreview.withCurrentFix).
 */
@RunWith(RobolectricTestRunner::class)
class TripPreviewFixTest {
    @Test fun `trippreview recording - TRIP draws the preview's dot`() {
        val s = TripPreview.driveState(paused = false)
        assertNotNull("no dot in trippreview=recording", tripDot(s, null, 0L, System.currentTimeMillis()))
    }

    @Test fun `trippreview paused - TRIP draws the preview's dot`() {
        val s = TripPreview.driveState(paused = true)
        assertNotNull("no dot in trippreview=paused", tripDot(s, null, 0L, System.currentTimeMillis()))
    }

    @Test fun `the remembered preview, 5 s later - re-stamped on TRIP's clock it still shows its dot and readout`() {
        val t0 = 1_800_000_000_000L
        val remembered = TripPreview.driveState(paused = false, now = t0)
        val later = t0 + 5_000L
        assertNull("the rule itself: a 5 s old fix is not current", tripDot(remembered, null, 0L, later))
        val shown = TripPreview.withCurrentFix(remembered, later)
        assertNotNull(tripDot(shown, null, 0L, later))
        assertEquals("±4m", fixReadout(shown, 60.0, com.openrs.dash.ui.UserPrefs(altitudeUnit = "M"), later)!!.accuracy)
    }
}
