package com.openrs.dash

import com.openrs.dash.can.AdapterState
import com.openrs.dash.can.LinkPhase
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
            assertTrue(LinkPresentation.notificationText(LinkPresentation.foregroundPhase(p), false, false) != null)
        }
    }

    @Test fun `notification text follows the phase and the foreground ends with the work`() {
        assertEquals("Connecting to vehicle…", LinkPresentation.notificationText(LinkPhase.CONNECTING, false, false))
        assertEquals("Connected via Bluetooth", LinkPresentation.notificationText(LinkPhase.CONNECTED, true, false))
        assertEquals("Connected to vehicle · recording", LinkPresentation.notificationText(LinkPhase.CONNECTED, false, true))
        assertEquals("Link lost — reconnecting · recording paused",
            LinkPresentation.notificationText(LinkPhase.RECONNECTING, false, true))
        assertEquals("Connected to vehicle · recording paused",
            LinkPresentation.notificationText(LinkPhase.CONNECTED, false, true, paused = true))
        assertEquals("Connecting to vehicle… · recording paused",
            LinkPresentation.notificationText(LinkPhase.CONNECTING, false, true))
        assertNull(LinkPresentation.notificationText(LinkPhase.FAILED, false, false))
        assertNull(LinkPresentation.notificationText(LinkPhase.OFF, false, false))
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
