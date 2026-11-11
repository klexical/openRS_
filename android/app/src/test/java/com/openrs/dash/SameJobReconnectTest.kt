package com.openrs.dash

import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #316 seam 4 (QA F3 on 3a4967b, from QA's probe QaSameJobReconnectTest): one connection job, the real recorder wired as
 * CanDataService wires it. Auto-record on, a drive records; the job's adapter drops and its attempts fail (link pause);
 * the adapter answers again on the SAME job and the engine is running: the link pause ends, the same drive records.
 */
@RunWith(RobolectricTestRunner::class)
class SameJobReconnectTest {
    private val h = RecorderHarness()
    @After fun tearDown() = h.close()

    /** As CanDataService.newLinkSession's adapter. */
    private fun adapter(r: DriveRecorder) = object : LinkSession.Recorder {
        override val activeDriveId get() = r.driveState.value.let { if (it.isRecording) it.driveId else null }
        override fun onEngineRunning(sessionId: Long) { r.onEngineRunning(sessionId) }
        override fun onConnectionLost(atMs: Long) { r.onConnectionLost(atMs) }
        override fun onConnectionBack() { r.onConnectionBack() }
    }

    @Test fun `a link pause from a drop ends when the same job reconnects with the engine running`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val id = rec.driveState.value.driveId
        val own = LinkOwnership()
        val running = VehicleState(isConnected = true, rpm = 2_000.0)
        val s = LinkSession(own, own.begin(), h.vs, adapter(rec), onFrameCounted = {}, perfRunSink = {})
        s.onConnectionBack()
        s.onVehicleState(running) { 0L }                               // the job's first connected state: the drive is kept
        s.onConnectionLost(atMs = h.now)                               // the attempts failed: link pause
        assertEquals("premise", DrivePhase.PAUSED to PauseReason.LINK, rec.driveState.value.phase to rec.driveState.value.pauseReason)
        h.now += 60_000L
        s.onVehicleState(running.copy(isConnected = false)) { 0L }    // reconnecting: not an established connection
        s.onConnectionBack()                                           // the same job is CONNECTED again
        s.onVehicleState(running) { 0L }                               // and sees the engine running
        assertEquals(DrivePhase.RECORDING, rec.driveState.value.phase)
        assertEquals("the same drive", id, rec.driveState.value.driveId)
        assertEquals(1, h.dao.getDriveCount())
    }
}
