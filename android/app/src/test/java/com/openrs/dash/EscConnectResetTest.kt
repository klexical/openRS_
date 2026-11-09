package com.openrs.dash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.EscStatus
import com.openrs.dash.diagnostics.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * #288 (QA F1, from QA's scratch QaEscConnectResetTest): a new connection must not inherit the previous link's ESC reading.
 * The real service (TestServices) starts a connection with a Wi-Fi transport present; the previous link left ESC Sport. After
 * startConnection() a session has really started (the check that keeps this from passing at isTransportReady()), and ESC
 * is UNKNOWN until this connection's first 0x1C0 frame — so no ESC command can go out from the old reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class EscConnectResetTest {
    @org.junit.After fun destroyServices() { TestServices.destroyAll() }

    @Test fun `a new connection starts with ESC unknown, not the previous link's reading`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO").putString("wican_host", "192.0.2.1").commit()
        // Wi-Fi transport present (as ExportIdentityScanTest does), so beginConnection gets past isTransportReady()
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)
        // the previous link left ESC Sport behind
        OpenRSDashApp.instance.vehicleState.value = OpenRSDashApp.instance.vehicleState.value.copy(escStatus = EscStatus.PARTIAL)
        // A sentinel session first: only a real beginConnection replaces it (counting SESSION events depended on what earlier
        // tests left behind — sessionStart clears the list)
        DiagnosticLogger.sessionStart("before", 0, transport = "before", prefs = null)
        val service = TestServices.create()
        service.startConnection()
        assertEquals("a session must have started (else the test is vacuous)", "TCP SLCAN (MeatPi Pro)", DiagnosticLogger.sessionTransport)
        assertEquals(EscStatus.UNKNOWN, OpenRSDashApp.instance.vehicleState.value.escStatus)
        service.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
    }
}
