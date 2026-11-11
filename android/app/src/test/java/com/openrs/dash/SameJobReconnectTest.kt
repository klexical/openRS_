package com.openrs.dash

import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** A 0x090 frame on this connection (RPM 762): the engine seen running by this connection itself. */
    private fun LinkSession.rpmFrame() = onCanFrame(0x090, byteArrayOf(0, 0, 0xC1.toByte(), 0xFF.toByte(), 0x61, 0x7D, 0, 0), 50.0)

    @Test fun `a link pause from a drop ends when the same job reconnects with the engine running`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val id = rec.driveState.value.driveId
        val own = LinkOwnership()
        val running = VehicleState(isConnected = true, rpm = 2_000.0)
        val s = LinkSession(own, own.begin(), h.vs, adapter(rec), onFrameCounted = {}, perfRunSink = {})
        s.onConnectionBack()
        s.rpmFrame()
        s.onVehicleState(running) { 0L }                               // the job's first connected state: the drive is kept
        s.onConnectionLost(atMs = h.now)                               // the attempts failed: link pause
        assertEquals("premise", DrivePhase.PAUSED to PauseReason.LINK, rec.driveState.value.phase to rec.driveState.value.pauseReason)
        h.now += 60_000L
        s.onVehicleState(running.copy(isConnected = false)) { 0L }    // reconnecting: not an established connection
        s.onConnectionBack()                                           // the same job is CONNECTED again
        s.rpmFrame()
        s.onVehicleState(running) { 0L }                               // and sees the engine running
        assertEquals(DrivePhase.RECORDING, rec.driveState.value.phase)
        assertEquals("the same drive", id, rec.driveState.value.driveId)
        assertEquals(1, h.dao.getDriveCount())
    }

    // ── QA F5 on 65c2421: an RPM held from before the drop is not "engine running" (QA's probe QaStaleRpmTest) ─────────

    /** A drive recording at 2,000 rpm on [s]'s connection, then a drop whose attempts failed: link-paused, RPM held. */
    private fun droppedWithHeldRpm(s: LinkSession, rec: DriveRecorder) {
        s.onConnectionBack()
        s.rpmFrame()
        h.vs.value = h.vs.value.copy(isConnected = true, rpm = 2_000.0)       // driving
        s.onVehicleState(h.vs.value) { 0L }
        s.onConnectionLost(atMs = h.now)
        h.vs.value = h.vs.value.copy(isConnected = false)                     // as applyPhase(RECONNECTING): rpm untouched
        assertEquals("premise", DrivePhase.PAUSED to PauseReason.LINK, rec.driveState.value.phase to rec.driveState.value.pauseReason)
    }

    /** CONNECTED again on the same job, no frame yet: the state carries the RPM held from before the drop. */
    private fun backWithHeldRpm(s: LinkSession) {
        s.onConnectionBack()
        h.vs.value = h.vs.value.copy(isConnected = true)                      // as applyPhase(CONNECTED): rpm still 2000
        s.onVehicleState(h.vs.value) { 0L }
        h.settle()
    }

    @Test fun `F5 - back within 15 minutes with only the held RPM - still link-paused, until this connection's own RPM`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val id = rec.driveState.value.driveId
        val own = LinkOwnership()
        val s = LinkSession(own, own.begin(), h.vs, adapter(rec), onFrameCounted = {}, perfRunSink = {})
        droppedWithHeldRpm(s, rec)
        h.now += 60_000L                                                      // parked, engine off
        backWithHeldRpm(s)
        assertEquals(DrivePhase.PAUSED to PauseReason.LINK, rec.driveState.value.phase to rec.driveState.value.pauseReason)
        s.rpmFrame()                                                          // the engine runs: this connection's own RPM
        s.onVehicleState(h.vs.value) { 0L }
        assertEquals(DrivePhase.RECORDING, rec.driveState.value.phase)
        assertEquals("the same drive", id, rec.driveState.value.driveId)
    }

    @Test fun `F5 - back after 15 minutes with only the held RPM - nothing saved, nothing started, until this connection's own RPM`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val old = rec.driveState.value.driveId
        val own = LinkOwnership()
        val s = LinkSession(own, own.begin(), h.vs, adapter(rec), onFrameCounted = {}, perfRunSink = {})
        droppedWithHeldRpm(s, rec)
        h.now += 16 * 60_000L
        backWithHeldRpm(s)
        assertEquals(DrivePhase.PAUSED to PauseReason.LINK, rec.driveState.value.phase to rec.driveState.value.pauseReason)
        assertEquals("nothing started", 1, h.dao.getDriveCount())
        assertEquals("nothing saved", 0L, h.dao.getDrive(old)!!.endTime)
        s.rpmFrame()
        s.onVehicleState(h.vs.value) { 0L }
        assertTrue(h.waitUntil { rec.driveState.value.phase == DrivePhase.RECORDING && rec.driveState.value.driveId != old })
        assertEquals(2, h.dao.getDriveCount())
    }
}
