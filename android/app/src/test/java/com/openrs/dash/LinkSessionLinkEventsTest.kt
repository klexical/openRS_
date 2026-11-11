package com.openrs.dash

import com.openrs.dash.can.LinkPhase
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #316 seam 4: the link events a connection job reports ("connection lost" with its moment, "connection back") reach the
 * recorder through the job's [LinkSession], and only while that job owns the link (#212): a late report from a job that
 * was stopped or replaced changes nothing.
 */
class LinkSessionLinkEventsTest {

    private class Rec : LinkSession.Recorder {
        val events = mutableListOf<String>()
        override val activeDriveId: Long? = null
        override fun onEngineRunning(sessionId: Long) { events += "engine:$sessionId" }
        override fun onConnectionLost(atMs: Long) { events += "lost:$atMs" }
        override fun onConnectionBack() { events += "back" }
    }

    private val own = LinkOwnership()
    private fun session(gen: Int, rec: Rec, hook: () -> Unit = {}) =
        LinkSession(own, gen, MutableStateFlow(VehicleState()), rec, onFrameCounted = {}, perfRunSink = {}, afterOwnershipCheck = hook)

    @Test fun `the current job's events reach the recorder, the loss with its own moment`() {
        val rec = Rec()
        val s = session(own.begin(), rec)
        s.onConnectionBack()
        s.onConnectionLost(atMs = 7_000L)
        assertEquals(listOf("back", "lost:7000"), rec.events)
    }

    @Test fun `a job that no longer owns the link reports nothing - stopped, or replaced by a new one`() {
        val rec = Rec()
        val old = session(own.begin(), rec)
        own.transition { own.end(LinkPhase.OFF) }
        old.onConnectionLost(atMs = 7_000L)
        old.onConnectionBack()
        val current = session(own.begin(), rec)
        old.onConnectionLost(atMs = 8_000L)
        old.onConnectionBack()
        assertEquals(emptyList<String>(), rec.events)
        current.onConnectionLost(atMs = 9_000L)
        assertEquals(listOf("lost:9000"), rec.events)
    }

    private val running = VehicleState(isConnected = true, rpm = 2_000.0)

    /** A 0x090 (RPM) frame on the session's connection. */
    private fun LinkSession.rpmFrame() = onCanFrame(0x090, byteArrayOf(0, 0, 0xC1.toByte(), 0xFF.toByte(), 0x61, 0x7D, 0, 0), 50.0)

    @Test fun `engine running is reported once per connection - again after the same job's connection is back`() {
        val rec = Rec()
        val s = session(own.begin(), rec)
        s.onConnectionBack()
        s.rpmFrame()
        s.onVehicleState(running) { 7L }
        s.onVehicleState(running) { 7L }
        assertEquals(listOf("back", "engine:7"), rec.events)
        s.onConnectionLost(atMs = 1_000L)
        s.onConnectionBack()                                            // the same job reconnects (QA F3)
        s.rpmFrame()
        s.onVehicleState(running) { 7L }
        assertEquals(listOf("back", "engine:7", "lost:1000", "back", "engine:7"), rec.events)
    }

    @Test fun `engine running is reported only on an established connection and above the start RPM`() {
        val rec = Rec()
        val s = session(own.begin(), rec)
        s.rpmFrame()
        s.onVehicleState(running.copy(isConnected = false)) { 7L }
        s.onVehicleState(running.copy(rpm = LinkSession.AUTO_START_MIN_RPM)) { 7L }
        assertEquals(emptyList<String>(), rec.events)
    }

    @Test fun `engine running is reported only after an RPM frame on this connection - a held RPM is not enough (QA F5)`() {
        val rec = Rec()
        val s = session(own.begin(), rec)
        s.onConnectionBack()
        s.onVehicleState(running) { 7L }                                // the RPM held from before: no report
        assertEquals(listOf("back"), rec.events)
        s.rpmFrame()
        s.onConnectionBack()                                            // a reconnect clears the frame seen before it
        s.onVehicleState(running) { 7L }
        assertEquals(listOf("back", "back"), rec.events)
        s.rpmFrame()
        s.onVehicleState(running) { 7L }
        assertEquals(listOf("back", "back", "engine:7"), rec.events)
    }

    // ── QA's review of f5a9006: G1, G2 ────────────────────────────────────────────────────────────────────────────

    @Test fun `G1 - only an RPM frame marks the engine seen - another frame on the new connection is not enough`() {
        val rec = Rec()
        val s = session(own.begin(), rec)
        s.onConnectionBack()
        s.onCanFrame(0x130, byteArrayOf(0, 0, 0, 0, 0, 0, 0x27, 0x10), 50.0)   // speed, not RPM
        s.onVehicleState(running) { 7L }                                    // RPM held from before the drop
        assertEquals(listOf("back"), rec.events)
        s.rpmFrame()
        s.onVehicleState(running) { 7L }
        assertEquals(listOf("back", "engine:7"), rec.events)
    }

    @Test fun `G2 - a connection back between the pre-check and the lock - the report is re-checked and not sent`() {
        val rec = Rec()
        var s: LinkSession? = null
        var reconnectInHook = false
        s = session(own.begin(), rec) { if (reconnectInHook) { reconnectInHook = false; s!!.onConnectionBack() } }
        s.onConnectionBack()
        s.rpmFrame()                                                        // the old connection saw its RPM
        reconnectInHook = true                                              // the pre-check passes, then CONNECTED clears it
        s.onVehicleState(running) { 7L }
        assertEquals(listOf("back", "back"), rec.events)
    }

    @Test fun `a 0x090 frame that does not decode does not mark the engine seen - no report on the held RPM`() {
        val rec = Rec()
        val s = session(own.begin(), rec)
        s.onConnectionBack()
        s.onCanFrame(0x090, byteArrayOf(0, 0, 0xC1.toByte(), 0xFF.toByte(), 0x61), 50.0)   // 5 bytes: RPM needs 6
        s.onVehicleState(running) { 7L }                                    // RPM held from before the drop
        assertEquals(listOf("back"), rec.events)
        s.rpmFrame()
        s.onVehicleState(running) { 7L }
        assertEquals(listOf("back", "engine:7"), rec.events)
    }
}
