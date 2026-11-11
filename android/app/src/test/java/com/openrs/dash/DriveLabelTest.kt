package com.openrs.dash

import com.openrs.dash.data.DriveAction.PAUSE
import com.openrs.dash.data.DriveAction.RESUME
import com.openrs.dash.data.DriveAction.STOP
import com.openrs.dash.data.DriveLabel
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #316 seam 3 (DESIGN-SUMMARY "What the user sees", SPEC stories 10, 40–42): one pure function gives the label and the
 * offered buttons for TRIP, the header pill and the notification. Every cell of the table, each with the connection up
 * and down.
 */
class DriveLabelTest {
    private fun label(phase: DrivePhase, reason: PauseReason?, connected: Boolean) = DriveLabel.of(phase, reason, connected)

    @Test fun `a hand pause reads PAUSED, connection up or down - RESUME and STOP`() {
        for (c in listOf(true, false))
            assertEquals("connected $c", DriveLabel("PAUSED", listOf(RESUME, STOP)), label(DrivePhase.PAUSED, PauseReason.HAND, c))
    }

    @Test fun `a link pause with the connection down reads PAUSED - NO LINK - RESUME and STOP`() {
        assertEquals(DriveLabel("PAUSED · NO LINK", listOf(RESUME, STOP)), label(DrivePhase.PAUSED, PauseReason.LINK, false))
    }

    @Test fun `a link pause with the connection back (engine not running yet) reads PAUSED - ENGINE OFF - RESUME and STOP`() {
        assertEquals(DriveLabel("PAUSED · ENGINE OFF", listOf(RESUME, STOP)), label(DrivePhase.PAUSED, PauseReason.LINK, true))
    }

    @Test fun `a recording drive reads REC, also with the connection down (a recording gap) - PAUSE and STOP`() {
        for (c in listOf(true, false))
            assertEquals("connected $c", DriveLabel("REC", listOf(PAUSE, STOP)), label(DrivePhase.RECORDING, null, c))
    }

    @Test fun `a start under way reads STARTING with no button`() {
        for (c in listOf(true, false))
            assertEquals("connected $c", DriveLabel("STARTING", emptyList()), label(DrivePhase.STARTING, null, c))
    }

    @Test fun `idle has no label`() {
        for (c in listOf(true, false)) assertNull(label(DrivePhase.IDLE, null, c))
    }
}
