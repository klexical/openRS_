package com.openrs.dash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.Lamps
import com.openrs.dash.diagnostics.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * #199 (QA J1): a new connection must not inherit the previous link's SRS lamp, as for ESC (#288) and launch control (#289).
 * The real service (TestServices) starts a connection with a Wi-Fi transport present; the previous link left the lamp on.
 * After startConnection() a session has really started, and lamps.srs is unknown until this connection's first 0x040 frame.
 * The IPC lamps are not part of this reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class SrsConnectResetTest {
    @org.junit.After fun destroyServices() { TestServices.destroyAll() }

    @Test fun `a new connection starts with the SRS lamp unknown, not the previous link's reading`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO").putString("wican_host", "192.0.2.1").commit()
        // Wi-Fi transport present (as EscConnectResetTest does), so beginConnection gets past isTransportReady()
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)
        // the previous link left the SRS lamp on (and an IPC lamp, which stays)
        OpenRSDashApp.instance.vehicleState.value = OpenRSDashApp.instance.vehicleState.value.copy(lamps = Lamps(mil = false, srs = true))
        DiagnosticLogger.sessionStart("before", 0, transport = "before", prefs = null)
        val service = TestServices.create()
        service.startConnection()
        assertEquals("a session must have started (else the test is vacuous)", "TCP SLCAN (MeatPi Pro)", DiagnosticLogger.sessionTransport)
        assertNull(OpenRSDashApp.instance.vehicleState.value.lamps.srs)
        assertEquals("the IPC lamps are not reset here", false, OpenRSDashApp.instance.vehicleState.value.lamps.mil)
        service.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
    }
}
