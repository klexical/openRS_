package com.openrs.dash

import com.openrs.dash.data.DriveLabel
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import com.openrs.dash.ui.trip.chipLines
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #316 D-B (S3 on 9c65a0b: at 360 dp, font 1.3, "❙❙ PAUSED · ENGINE OFF" ran under the route-stats card): TRIP's map chip
 * shows a pause reason on a second line inside the same chip, so the chip is no wider than plain PAUSED; REC, STARTING and
 * a hand pause stay one line. The no-overlap at 360 dp is an emulator check (S3), not this test.
 */
class TripChipLinesTest {
    private fun lines(phase: DrivePhase, reason: PauseReason? = null, connected: Boolean = true) =
        chipLines(DriveLabel.of(phase, reason, connected)!!)

    @Test fun `a link pause - PAUSED on the first line, the reason on the second`() {
        assertEquals("PAUSED" to "NO LINK", lines(DrivePhase.PAUSED, PauseReason.LINK, connected = false))
        assertEquals("PAUSED" to "ENGINE OFF", lines(DrivePhase.PAUSED, PauseReason.LINK, connected = true))
    }

    @Test fun `a hand pause, REC and STARTING - one line`() {
        assertEquals("PAUSED" to null, lines(DrivePhase.PAUSED, PauseReason.HAND))
        assertEquals("REC" to null, lines(DrivePhase.RECORDING))
        assertEquals("STARTING" to null, lines(DrivePhase.STARTING))
    }
}
