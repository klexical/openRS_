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

    // ── QA's review of d7a810e: U3–U5 pinned (they feed the 15-minute rule), F2 ──────────────────────────────────────

    @Test fun `U3 - a second loss while link-paused keeps the first moment`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        rec.onConnectionLost(atMs = h.now)
        h.now += 30_000L
        rec.onConnectionLost(atMs = h.now)
        h.now += 1_000L
        assertEquals(31_000L, rec.pausedDurationMs())
    }

    @Test fun `U4 - a link pause counts from the loss time the event carries, not from when the event arrives`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val lostAt = h.now
        h.now += 40_000L                                       // the event arrives after the reconnect attempts failed
        rec.onConnectionLost(atMs = lostAt)
        h.now += 1_000L
        assertEquals(41_000L, rec.pausedDurationMs())
    }

    @Test fun `U4 - a loss held during a start counts from its own time, not from when the start completes`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = true)
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        h.now += 40_000L                                       // the start completes later
        hold.release.countDown()
        assertTrue(h.waitUntil { state(rec) == linkPaused })
        h.now += 1_000L
        assertEquals(41_000L, rec.pausedDurationMs())
    }

    @Test fun `U5 - the engine running alone does not end a link pause - the connection must be back too`() {
        val rec = linkPausedDrive()
        rec.onEngineRunning()
        h.settle()
        assertEquals(linkPaused, state(rec))
    }

    @Test fun `F2 - lost, back and engine running all during a start - the drive records, both end conditions hold`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = true)
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        rec.onConnectionBack()
        rec.onEngineRunning()
        hold.release.countDown()
        assertTrue(h.waitUntil { rec.driveState.value.phase == DrivePhase.RECORDING })
        h.settle()
        assertEquals(recording, state(rec))
    }

    @Test fun `F2 control - lost and back during a start with the engine not running - the drive begins link-paused`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = true)
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        rec.onConnectionBack()
        hold.release.countDown()
        assertTrue(h.waitUntil { state(rec) == linkPaused })
    }

    @Test fun `F2 control - engine running reported before the loss in a start does not count - the drive begins link-paused`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = true)
        rec.onConnectionBack()
        rec.onEngineRunning()
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        hold.release.countDown()
        assertTrue(h.waitUntil { state(rec) == linkPaused })
    }

    // ── QA's review of 3a4967b: N2, N3 pinned; F4 ──────────────────────────────────────────────────────────────────────

    @Test fun `N2 - an engine report from before the loss does not count - back without the engine, the drive begins link-paused`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = true)
        rec.onConnectionBack(); rec.onEngineRunning()          // before the start
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        rec.onConnectionBack()                                  // back, but no engine report since the loss
        hold.release.countDown()
        assertTrue(h.waitUntil { state(rec) == linkPaused })
    }

    @Test fun `N3 - the engine reported while the link is down does not count - the drive begins link-paused`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = true)
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        rec.onEngineRunning()                                   // the link is down
        rec.onConnectionBack()
        hold.release.countDown()
        assertTrue(h.waitUntil { state(rec) == linkPaused })
    }

    @Test fun `F4 - a loss that ended during a start, then a new one - the pause counts from the new loss`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = true)
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        h.now += 10_000L
        rec.onConnectionBack(); rec.onEngineRunning()          // the first loss is over
        h.now += 10_000L
        rec.onConnectionLost(atMs = h.now)
        hold.release.countDown()
        assertTrue(h.waitUntil { state(rec) == linkPaused })
        h.now += 1_000L
        assertEquals(1_000L, rec.pausedDurationMs())
    }
}
