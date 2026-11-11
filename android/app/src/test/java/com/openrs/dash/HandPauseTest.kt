package com.openrs.dash

import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #316, hand pause (DESIGN-SUMMARY "Pauses", SPEC stories 13–16), at seam 1: a pause the user asked for. Only the user's
 * RESUME or STOP ends it; a lost or new connection changes nothing, and no second drive starts.
 */
@RunWith(RobolectricTestRunner::class)
class HandPauseTest {
    private val h = RecorderHarness()
    @After fun tearDown() = h.close()

    private fun handPaused(autoRecord: Boolean) = with(h) {
        recorder(autoRecord = autoRecord).recording().also { assertEquals(CommandResult.Done, it.pauseDrive()) }
    }

    @Test fun `PAUSE on a recording drive is a hand pause`() {
        val rec = handPaused(autoRecord = false)
        assertEquals(DrivePhase.PAUSED, rec.driveState.value.phase)
        assertEquals(PauseReason.HAND, rec.driveState.value.pauseReason)
    }

    @Test fun `a lost connection changes nothing during a hand pause - auto-record on or off`() {
        for (auto in listOf(true, false)) {
            val rec = handPaused(auto)
            rec.onConnectionLost(atMs = h.now)
            h.settle()
            assertEquals("auto-record $auto", DrivePhase.PAUSED, rec.driveState.value.phase)
            assertEquals("auto-record $auto", PauseReason.HAND, rec.driveState.value.pauseReason)
            h.join(rec.stopDrive())
        }
    }

    @Test fun `a new connection with the engine running changes nothing during a hand pause - no second drive`() {
        val rec = handPaused(autoRecord = true)
        val id = rec.driveState.value.driveId
        rec.onConnectionLost(atMs = h.now)
        h.now += 60 * 60_000L                                  // long after: the 15-minute rule is for link pauses only
        rec.onConnectionBack()
        rec.onEngineRunning()
        h.settle()
        assertEquals(DrivePhase.PAUSED, rec.driveState.value.phase)
        assertEquals(PauseReason.HAND, rec.driveState.value.pauseReason)
        assertEquals("the same drive", id, rec.driveState.value.driveId)
        assertEquals("one drive", 1, h.dao.getDriveCount())
        assertEquals("still open", 0L, h.dao.getDrive(id)!!.endTime)
    }

    @Test fun `RESUME ends a hand pause - recording, no pause reason`() {
        val rec = handPaused(autoRecord = true)
        assertEquals(CommandResult.Done, rec.resumeDrive())
        assertEquals(DrivePhase.RECORDING, rec.driveState.value.phase)
        assertEquals(null, rec.driveState.value.pauseReason)
    }

    @Test fun `STOP ends a hand pause and the drive is saved`() {
        val rec = handPaused(autoRecord = true)
        val id = rec.driveState.value.driveId
        h.join(rec.stopDrive())
        assertEquals(DrivePhase.IDLE, rec.driveState.value.phase)
        assertEquals(null, rec.driveState.value.pauseReason)
        assertTrue(h.dao.getDrive(id)!!.endTime > 0)
    }
}
