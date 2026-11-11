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
        override val isRecording = true
        override val isPaused = false
        override val activeDriveId: Long? = null
        override fun pausedDurationMs() = 0L
        override fun resumeDrive() { events += "resume" }
        override fun stopDrive() { events += "stop" }
        override fun startDrive(sessionId: Long) { events += "start" }
        override fun onConnectionLost(atMs: Long) { events += "lost:$atMs" }
        override fun onConnectionBack() { events += "back" }
    }

    private val own = LinkOwnership()
    private fun session(gen: Int, rec: Rec) =
        LinkSession(own, gen, MutableStateFlow(VehicleState()), rec, onFrameCounted = {}, perfRunSink = {})

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
}
