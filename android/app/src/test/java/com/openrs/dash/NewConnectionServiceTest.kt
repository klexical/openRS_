package com.openrs.dash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.Lamps
import com.openrs.dash.data.LaunchControl
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.LinkStopReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Every start path of the real service tells Live telemetry "new connection" (android/docs/adr/0001): Connect, the
 * automatic start once the adapter answers on its Wi-Fi network (#324), and RETRY. What a new connection forgets is
 * Live telemetry's own rule (LiveTelemetryTest, ADR 0002: every reading); this test checks that each path reaches it, with
 * held readings of several kinds: ESC (#288), the lamps (#199), the speed stamp (#335), the 0x360 engine state (#328),
 * launch control's flags and stamps (#289), and polled and CAN temperatures, a tyre pressure and the odometer.
 *
 * A sentinel diagnostic session first: only a real connection start replaces it, so each case also proves a connection
 * really began (else the test would be vacuous).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class NewConnectionServiceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val telemetry get() = OpenRSDashApp.instance.liveTelemetry

    @Before fun wifiPro() {
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO").putString("wican_host", "192.0.2.1").commit()
        // Wi-Fi transport present (as ExportIdentityScanTest does), so beginConnection gets past isTransportReady()
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)
        CanDataService.link.clearManualConnect()
    }

    @After fun destroyServices() {
        TestServices.destroyAll()
        CanDataService.probeOverride = null
    }

    /** What the previous link left behind. */
    private fun holdPreviousLink() {
        val now = System.currentTimeMillis()
        telemetry.showForTest(VehicleState(
            escStatus = EscStatus.PARTIAL, lamps = Lamps(mil = false, srs = true), engineState360 = 0xB2, speedKph = 0.0,
            launchControlActive = true, launchControlEngaged = true,
            tirePressLF = 2.4, odometerKm = 12_345L, oilTempC = 95.0, coolantTempC = 90.0,
            fieldLastUpdateMs = mapOf(CanDecoder.SPEED_TAG to now, LaunchControl.TAG_420 to now, LaunchControl.TAG_225 to now),
        ))
        DiagnosticLogger.sessionStart("before", 0, transport = "before", prefs = null)
    }

    private fun assertNewConnection(path: String) {
        assertEquals("$path: a session must have started", "TCP SLCAN (MeatPi Pro)", DiagnosticLogger.sessionTransport)
        val vs = telemetry.readings.value
        assertEquals("$path: ESC", EscStatus.UNKNOWN, vs.escStatus)
        assertNull("$path: SRS lamp", vs.lamps.srs)
        assertNull("$path: the IPC lamps are unknown too (ADR 0002: every reading)", vs.lamps.mil)
        assertNull("$path: speed stamp", vs.fieldLastUpdateMs[CanDecoder.SPEED_TAG])
        assertEquals("$path: engine state", -1, vs.engineState360)
        assertFalse("$path: LC flag", vs.launchControlActive)
        assertFalse("$path: LC engaged", vs.launchControlEngaged)
        assertTrue("$path: LC stamps", LaunchControl.CAN_TAGS.none { it in vs.fieldLastUpdateMs })
        // ADR 0002: polled and CAN readings alike are "not received" until this connection reads them
        assertEquals("$path: tyre pressure", -1.0, vs.tirePressLF, 0.0)
        assertEquals("$path: odometer", -1L, vs.odometerKm)
        assertEquals("$path: oil temp", -99.0, vs.oilTempC, 0.0)
        assertEquals("$path: coolant temp", -99.0, vs.coolantTempC, 0.0)
    }

    @Test fun `Connect is a new connection`() {
        val service = TestServices.create()
        holdPreviousLink()
        service.startConnection()
        assertNewConnection("Connect")
        service.stopConnection(LinkStopReason.USER_DISCONNECT)
    }

    @Test fun `the automatic start is a new connection`() {
        CanDataService.probeOverride = { com.openrs.dash.can.ProbeOutcome.Answered(OpenUntilClosed(), "CR", 0) }
        holdPreviousLink()
        val service = TestServices.create()                 // onCreate: the transport is up, so the automatic start runs
        assertTrue("an automatic start began", until { DiagnosticLogger.sessionTransport == "TCP SLCAN (MeatPi Pro)" })
        assertNewConnection("automatic start")
        service.stopConnection(LinkStopReason.USER_DISCONNECT)
    }

    @Test fun `RETRY is a new connection`() {
        val service = TestServices.create()
        service.startConnection()
        holdPreviousLink()
        service.reconnect()
        assertNewConnection("RETRY")
        service.stopConnection(LinkStopReason.USER_DISCONNECT)
    }

    /** A probe transport that stays open (the attempt stays CONNECTING) until closed, as TaskRemovalTest's. */
    private class OpenUntilClosed : com.openrs.dash.can.SlcanTransport {
        private val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun open() {}
        override suspend fun readLine(): String? { closed.await(); return null }
        override suspend fun writeLine(frame: String) {}
        override fun close() { closed.complete(Unit) }
        override val label = "test"; override val stockFirmwareLabel = "test"
    }

    private fun until(ms: Long = 5_000, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        return ok()
    }
}
