package com.openrs.dash

import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import com.openrs.dash.service.DriveRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #316, "auto-record switched" (DESIGN-SUMMARY "Pauses", SPEC stories 30–31), at seam 1: switching auto-record off during
 * a link pause turns it into a hand pause (only RESUME or STOP end it); switching it on never changes an open pause.
 */
@RunWith(RobolectricTestRunner::class)
class AutoRecordSwitchedTest {
    private val h = RecorderHarness()
    @After fun tearDown() = h.close()

    private fun state(rec: DriveRecorder) = rec.driveState.value.phase to rec.driveState.value.pauseReason
    private val linkPaused = DrivePhase.PAUSED to PauseReason.LINK
    private val handPaused = DrivePhase.PAUSED to PauseReason.HAND
    private val recording = DrivePhase.RECORDING to null

    private fun linkPausedDrive() = with(h) { recorder(autoRecord = true).recording() }.also {
        it.onConnectionLost(atMs = h.now)
        check(h.waitUntil { state(it) == linkPaused }) { "not link-paused" }
    }

    @Test fun `switched off during a link pause - a hand pause, still dated from the loss`() {
        val rec = linkPausedDrive()
        h.now += 30_000L
        rec.onAutoRecordSwitched(false)
        assertEquals(handPaused, state(rec))
        assertEquals("the pause keeps its start", 30_000L, rec.pausedDurationMs())
        assertTrue("still open", rec.driveState.value.isRecording)
    }

    @Test fun `switched off during a link pause - the connection back with the engine running changes nothing, RESUME does`() {
        val rec = linkPausedDrive()
        val id = rec.driveState.value.driveId
        rec.onAutoRecordSwitched(false)
        rec.onConnectionBack()
        rec.onEngineRunning()
        h.settle()
        assertEquals(handPaused, state(rec))
        assertEquals(CommandResult.Done, rec.resumeDrive())
        assertEquals(recording, state(rec))
        assertEquals(id, rec.driveState.value.driveId)
    }

    @Test fun `switched off during a link pause - past 15 minutes nothing is saved and nothing starts`() {
        val rec = linkPausedDrive()
        val id = rec.driveState.value.driveId
        rec.onAutoRecordSwitched(false)
        h.now += 16 * 60_000L
        rec.onConnectionBack()
        rec.onEngineRunning()
        h.settle()
        assertEquals(handPaused, state(rec))
        assertEquals(id, rec.driveState.value.driveId)
        assertEquals("nothing started", 1, h.dao.getDriveCount())
        assertEquals("nothing saved", 0L, h.dao.getDrive(id)!!.endTime)
    }

    @Test fun `switched on again during a link pause - the pause is unchanged`() {
        val rec = linkPausedDrive()
        rec.onAutoRecordSwitched(true)
        assertEquals(linkPaused, state(rec))
    }

    @Test fun `switched off then on - the hand pause stays a hand pause`() {
        val rec = linkPausedDrive()
        rec.onAutoRecordSwitched(false)
        rec.onAutoRecordSwitched(true)
        assertEquals(handPaused, state(rec))
        rec.onConnectionBack()
        rec.onEngineRunning()
        h.settle()
        assertEquals(handPaused, state(rec))
    }

    @Test fun `switched on during a hand pause - the hand pause is unchanged`() {
        val rec = with(h) { recorder(autoRecord = false).recording() }
        assertEquals(CommandResult.Done, rec.pauseDrive())
        rec.onAutoRecordSwitched(true)
        assertEquals(handPaused, state(rec))
        rec.onConnectionBack()
        rec.onEngineRunning()
        h.settle()
        assertEquals(handPaused, state(rec))
    }

    @Test fun `switched off while recording - recording, and a later loss is a recording gap`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        rec.onAutoRecordSwitched(false)
        assertEquals(recording, state(rec))
        rec.onConnectionLost(atMs = h.now)
        h.settle()
        assertEquals(recording, state(rec))
    }

    @Test fun `switched off during a start that saw a loss - the drive begins recording`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold, autoRecord = true)
        rec.startDrive()
        hold.awaitEntered()
        rec.onConnectionLost(atMs = h.now)
        rec.onAutoRecordSwitched(false)                         // the rule is applied when the start completes
        hold.release.countDown()
        assertTrue(h.waitUntil { state(rec) == recording })
        h.settle()
        assertEquals(recording, state(rec))
    }
}
