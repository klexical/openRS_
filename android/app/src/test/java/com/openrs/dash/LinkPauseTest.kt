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
 * #316, link pause (DESIGN-SUMMARY "Pauses" and "Connection lost", SPEC stories 19–24, 29), at seam 1. A pause the app
 * applies when the connection is lost, only while auto-record is on. It ends when the connection is back and the
 * engine is running, or by the user's RESUME or STOP; a hand RESUME wins with the connection down.
 */
@RunWith(RobolectricTestRunner::class)
class LinkPauseTest {
    private val h = RecorderHarness()
    @After fun tearDown() = h.close()

    private fun state(rec: com.openrs.dash.service.DriveRecorder) = rec.driveState.value.phase to rec.driveState.value.pauseReason
    private val linkPaused = DrivePhase.PAUSED to PauseReason.LINK
    private val recording = DrivePhase.RECORDING to null

    private fun linkPausedDrive() = with(h) { recorder(autoRecord = true).recording() }.also {
        it.onConnectionLost(atMs = h.now)
        check(h.waitUntil { state(it) == linkPaused }) { "not link-paused" }
    }

    @Test fun `with auto-record on a lost connection link-pauses a recording drive`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        rec.onConnectionLost(atMs = h.now)
        assertEquals(linkPaused, state(rec))
        assertTrue("still open", rec.driveState.value.isRecording)
    }

    @Test fun `with auto-record off a lost connection pauses nothing - a recording gap`() {
        val rec = with(h) { recorder(autoRecord = false).recording() }
        rec.onConnectionLost(atMs = h.now)
        h.settle()
        assertEquals(recording, state(rec))
    }

    @Test fun `a reconnect with the engine off leaves the drive link-paused`() {
        val rec = linkPausedDrive()
        rec.onConnectionBack()
        h.settle()
        assertEquals(linkPaused, state(rec))
    }

    @Test fun `the connection back and the engine running end a link pause - recording, the same drive`() {
        val rec = linkPausedDrive()
        val id = rec.driveState.value.driveId
        rec.onConnectionBack()
        rec.onEngineRunning()
        assertEquals(recording, state(rec))
        assertEquals(id, rec.driveState.value.driveId)
        assertEquals(1, h.dao.getDriveCount())
    }

    @Test fun `a hand RESUME ends a link pause with the connection down - a later reconnect changes nothing`() {
        val rec = linkPausedDrive()
        val id = rec.driveState.value.driveId
        assertEquals(CommandResult.Done, rec.resumeDrive())
        assertEquals(recording, state(rec))
        rec.onConnectionBack()
        rec.onEngineRunning()
        h.settle()
        assertEquals(recording, state(rec))
        assertEquals(id, rec.driveState.value.driveId)
        assertEquals(1, h.dao.getDriveCount())
    }

    @Test fun `a drive resumed by hand is link-paused again by the next lost connection`() {
        val rec = linkPausedDrive()
        rec.resumeDrive()
        h.now += 60_000L
        rec.onConnectionLost(atMs = h.now)
        assertEquals(linkPaused, state(rec))
    }

    @Test fun `a lost connection while the drive is paused changes nothing - a link pause stays a link pause`() {
        val rec = linkPausedDrive()
        h.now += 30_000L
        rec.onConnectionLost(atMs = h.now)
        assertEquals(linkPaused, state(rec))
    }

    @Test fun `STOP ends a link pause and the drive is saved`() {
        val rec = linkPausedDrive()
        val id = rec.driveState.value.driveId
        h.join(rec.stopDrive())
        assertEquals(DrivePhase.IDLE to null, state(rec))
        assertTrue(h.dao.getDrive(id)!!.endTime > 0)
    }

    @Test fun `a connection lost during a start is acted on when the start completes - auto-record on, link-paused`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = true)
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        assertEquals("still starting", DrivePhase.STARTING, rec.driveState.value.phase)
        hold.release.countDown()
        assertTrue(h.waitUntil { state(rec) == linkPaused })
    }

    @Test fun `a connection lost during a start with auto-record off - the drive records`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = false)
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        hold.release.countDown()
        assertTrue(h.waitUntil { rec.driveState.value.phase == DrivePhase.RECORDING })
        h.settle()
        assertEquals(recording, state(rec))
    }
}
