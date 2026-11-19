package com.openrs.dash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.diagnostics.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * #328: DIAG's ENGINE reads 0x360 byte 7 (engineState360). A new connection must not show the previous link's engine state:
 * after startConnection() it is not received (-1) until this connection's first 0x360 frame. The real service
 * (TestServices), as EscConnectResetTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class EngineStateConnectResetTest {
    @org.junit.After fun destroyServices() { TestServices.destroyAll() }

    @Test fun `a new connection starts with the engine state not received, not the previous link's`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO").putString("wican_host", "192.0.2.1").commit()
        // Wi-Fi transport present (as ExportIdentityScanTest does), so beginConnection gets past isTransportReady()
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)
        // the previous link left "Running" behind
        OpenRSDashApp.instance.vehicleState.value = OpenRSDashApp.instance.vehicleState.value.copy(engineState360 = 0xB2)
        // A sentinel session first: only a real beginConnection replaces it (counting SESSION events depended on what earlier
        // tests left behind — sessionStart clears the list)
        DiagnosticLogger.sessionStart("before", 0, transport = "before", prefs = null)
        val service = TestServices.create()
        service.startConnection()
        assertEquals("a session must have started (else the test is vacuous)", "TCP SLCAN (MeatPi Pro)", DiagnosticLogger.sessionTransport)
        assertEquals(-1, OpenRSDashApp.instance.vehicleState.value.engineState360)
        service.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
    }
}
