package com.openrs.dash

import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import com.openrs.dash.data.Refusal
import com.openrs.dash.demo.DemoMode
import com.openrs.dash.service.DriveRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #316, demo mode (DESIGN-SUMMARY "Demo mode", SPEC stories 34–36, spec difference 3), at seam 1: entering demo mode ends
 * whatever the recorder is doing — a recording or paused drive is stopped and saved, a start under way is a cancelled
 * start — and START is refused while demo mode is active (U1).
 */
@RunWith(RobolectricTestRunner::class)
class DemoModeEnteredTest {
    private val h = RecorderHarness()
    @After fun tearDown() = h.close()

    private fun state(rec: DriveRecorder) = rec.driveState.value.phase to rec.driveState.value.pauseReason

    /** The drive [id] is saved: its end is written at the recorder's clock when demo mode was entered. */
    private fun assertSavedAt(id: Long, at: Long) {
        assertTrue("saved", h.waitUntil { h.dao.getDrive(id)!!.endTime != 0L })
        assertEquals(at, h.dao.getDrive(id)!!.endTime)
    }

    @Test fun `a recording drive is stopped and saved`() {
        val rec = with(h) { recorder().recording() }
        val id = rec.driveState.value.driveId
        h.now += 60_000L
        rec.onDemoModeEntered()
        assertEquals(DrivePhase.IDLE, rec.driveState.value.phase)
        assertSavedAt(id, h.now)
    }

    @Test fun `a hand-paused drive is stopped and saved`() {
        val rec = with(h) { recorder().recording() }
        val id = rec.driveState.value.driveId
        assertEquals(CommandResult.Done, rec.pauseDrive())
        h.now += 60_000L
        rec.onDemoModeEntered()
        assertEquals(DrivePhase.IDLE, rec.driveState.value.phase)
        assertSavedAt(id, h.now)
    }

    @Test fun `a link-paused drive is stopped and saved`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val id = rec.driveState.value.driveId
        rec.onConnectionLost(atMs = h.now)
        assertEquals("premise", DrivePhase.PAUSED to PauseReason.LINK, state(rec))
        h.now += 60_000L
        rec.onDemoModeEntered()
        assertEquals(DrivePhase.IDLE, rec.driveState.value.phase)
        assertSavedAt(id, h.now)
    }

    @Test fun `a start under way is cancelled - no drive begins, no row is left`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold)
        rec.startDrive()
        hold.awaitEntered()
        assertEquals("premise", DrivePhase.STARTING, rec.driveState.value.phase)
        rec.onDemoModeEntered()
        hold.release.countDown()
        assertTrue(h.waitUntil { h.dao.getDriveCount() == 0 && rec.driveState.value.phase == DrivePhase.IDLE })
        h.settle()
        assertEquals(DrivePhase.IDLE, rec.driveState.value.phase)
        assertEquals(0, h.dao.getDriveCount())
    }

    @Test fun `with nothing under way nothing happens`() {
        val rec = h.recorder()
        rec.onDemoModeEntered()
        h.settle()
        assertEquals(DrivePhase.IDLE, rec.driveState.value.phase)
        assertEquals(0, h.dao.getDriveCount())
    }

    /**
     * U1 (QA, commit 1): START is refused while demo mode is active. Demo's status flag is set by reflection, as in
     * TaskRemovalTest, so the test runs in both variants (DemoMode.start returns early in release builds).
     */
    @Test fun `U1 - START is refused in demo mode - nothing starts, nothing is written`() {
        val rec = h.recorder()
        @Suppress("UNCHECKED_CAST")
        val status = DemoMode::class.java.getDeclaredField("_status").apply { isAccessible = true }
            .get(DemoMode) as MutableStateFlow<DemoMode.Status>
        status.value = DemoMode.Status(active = true)
        try {
            assertEquals(CommandResult.Refused(Refusal.DEMO), rec.startDrive())
            h.settle()
            assertEquals(DrivePhase.IDLE, rec.driveState.value.phase)
            assertEquals(0, h.dao.getDriveCount())
        } finally {
            status.value = DemoMode.Status()
        }
    }
}
