package com.openrs.dash

import com.openrs.dash.can.AdapterState
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.PauseReason
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.LinkPresentation
import com.openrs.dash.ui.LinkUi
import com.openrs.dash.ui.linkUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapter state → phase, notification and UI labels (#212). */
class LinkPresentationTest {

    @Test fun `adapter states map to truthful phases`() {
        assertEquals(LinkPhase.CONNECTING, LinkPresentation.phaseFor(AdapterState.Disconnected))
        assertEquals(LinkPhase.CONNECTING, LinkPresentation.phaseFor(AdapterState.Connecting))
        assertEquals(LinkPhase.CONNECTED, LinkPresentation.phaseFor(AdapterState.Connected))
        assertEquals(LinkPhase.RECONNECTING, LinkPresentation.phaseFor(AdapterState.Reconnecting))
        assertEquals(LinkPhase.FAILED, LinkPresentation.phaseFor(AdapterState.Idle))
        assertNull(LinkPresentation.phaseFor(AdapterState.Error("timeout")))     // transient — no change
    }

    @Test fun `a foreground post reads the published phase, and before a new job's first phase the attempt starting (#296)`() {
        assertEquals(LinkPhase.CONNECTING, LinkPresentation.foregroundPhase(LinkPhase.OFF))      // a promote at a first start
        assertEquals(LinkPhase.CONNECTING, LinkPresentation.foregroundPhase(LinkPhase.FAILED))   // a start after attempts ran out
        for (p in listOf(LinkPhase.CONNECTING, LinkPhase.CONNECTED, LinkPhase.RECONNECTING)) {
            assertEquals(p, LinkPresentation.foregroundPhase(p))
            // every phase a foreground post reads has a text, so the post never falls back
            assertTrue(LinkPresentation.notificationText(LinkPresentation.foregroundPhase(p), false) != null)
        }
    }

    @Test fun `notification text follows the phase and the foreground ends with the work`() {
        assertEquals("Connecting to vehicle…", LinkPresentation.notificationText(LinkPhase.CONNECTING, false))
        assertEquals("Connected via Bluetooth", LinkPresentation.notificationText(LinkPhase.CONNECTED, true))
        assertEquals("Link lost — reconnecting…", LinkPresentation.notificationText(LinkPhase.RECONNECTING, false))
        assertNull(LinkPresentation.notificationText(LinkPhase.FAILED, false))
        assertNull(LinkPresentation.notificationText(LinkPhase.OFF, false))
    }

    /**
     * #316: the notification shows the drive's label (DriveLabel, the same as TRIP and the header pill) beside the link
     * text, with the published phase as the label's connection. A recording drive with the link down is a recording gap and
     * reads REC (staging read "recording paused" for any drive while the link was down).
     */
    @Test fun `notification text carries the drive's label`() {
        fun drive(phase: DrivePhase, reason: PauseReason? = null) = DriveState(phase = phase, pauseReason = reason)
        val rec = drive(DrivePhase.RECORDING)
        val hand = drive(DrivePhase.PAUSED, PauseReason.HAND)
        val link = drive(DrivePhase.PAUSED, PauseReason.LINK)
        assertEquals("Connected to vehicle · REC", LinkPresentation.notificationText(LinkPhase.CONNECTED, false, rec))
        assertEquals("Link lost — reconnecting · REC", LinkPresentation.notificationText(LinkPhase.RECONNECTING, false, rec))
        assertEquals("Connected via Bluetooth · PAUSED", LinkPresentation.notificationText(LinkPhase.CONNECTED, true, hand))
        assertEquals("Link lost — reconnecting · PAUSED", LinkPresentation.notificationText(LinkPhase.RECONNECTING, false, hand))
        assertEquals("Connected to vehicle · PAUSED · ENGINE OFF", LinkPresentation.notificationText(LinkPhase.CONNECTED, false, link))
        assertEquals("Link lost — reconnecting · PAUSED · NO LINK", LinkPresentation.notificationText(LinkPhase.RECONNECTING, false, link))
        assertEquals("Connecting to vehicle… · PAUSED · NO LINK", LinkPresentation.notificationText(LinkPhase.CONNECTING, false, link))
        assertEquals("Connecting to vehicle… · STARTING", LinkPresentation.notificationText(LinkPhase.CONNECTING, false, drive(DrivePhase.STARTING)))
        assertNull(LinkPresentation.notificationText(LinkPhase.OFF, false, link))
    }

    @Test fun `Cancel only for an initial attempt and never while recording`() {
        assertTrue(LinkPresentation.offersCancel(LinkPhase.CONNECTING, recording = false))
        assertFalse(LinkPresentation.offersCancel(LinkPhase.CONNECTING, recording = true))
        assertFalse(LinkPresentation.offersCancel(LinkPhase.RECONNECTING, recording = false))
        assertFalse(LinkPresentation.offersCancel(LinkPhase.CONNECTED, recording = false))
    }

    @Test fun `UI labels — CONN while connecting, RCN only while reconnecting, OFF after failure`() {
        val off = VehicleState()
        assertEquals(LinkUi.CONNECTING, linkUi(off, LinkPhase.CONNECTING, demoActive = false))
        assertEquals("CONN", LinkUi.CONNECTING.pill)
        assertEquals(LinkUi.FAILED, linkUi(off, LinkPhase.FAILED, demoActive = false))
        assertEquals("OFF", LinkUi.FAILED.pill)
        assertEquals(LinkUi.OFFLINE, linkUi(off, LinkPhase.OFF, demoActive = false))
        assertEquals(LinkUi.RECONNECTING, linkUi(off.copy(isReconnecting = true), LinkPhase.RECONNECTING, false))
        assertEquals(LinkUi.LIVE, linkUi(off.copy(isConnected = true), LinkPhase.CONNECTED, false))
    }

    @Test fun `demo frames drive the label — the simulated loss reads RCN, never a real CONN`() {
        val off = VehicleState()
        assertEquals(LinkUi.RECONNECTING, linkUi(off.copy(isReconnecting = true), LinkPhase.OFF, demoActive = true))
        assertEquals(LinkUi.LIVE, linkUi(off.copy(isConnected = true), LinkPhase.OFF, demoActive = true))
        assertEquals(LinkUi.OFFLINE, linkUi(off, LinkPhase.CONNECTING, demoActive = true))
    }
}
