package com.openrs.dash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.diagnostics.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * #289 (AUDIT.md r2 §3 item 3, "reconnect"): a new connection must not show a launch-control flag held from the previous
 * link. The real service (TestServices) starts a connection with a Wi-Fi transport present, as EscConnectResetTest does; the
 * previous link left both flags set with recent stamps. After startConnection() a session has really started, and both
 * flags are false with their stamps gone, so nothing shows until this connection's own 0x420.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class LaunchControlConnectResetTest {
    @org.junit.After fun destroyServices() { TestServices.destroyAll() }

    @Test fun `a new connection starts with no launch-control flag and no launch-control stamp`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO").putString("wican_host", "192.0.2.1").commit()
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)
        val t = System.currentTimeMillis()
        val app = OpenRSDashApp.instance.vehicleState
        app.value = app.value.copy(launchControlActive = true, launchControlEngaged = true,
            fieldLastUpdateMs = app.value.fieldLastUpdateMs + mapOf("lcStatus420" to t, "lc225" to t))
        DiagnosticLogger.sessionStart("before", 0, transport = "before", prefs = null)
        val service = TestServices.create()
        service.startConnection()
        assertEquals("a session must have started (else the test is vacuous)", "TCP SLCAN (MeatPi Pro)", DiagnosticLogger.sessionTransport)
        val vs = app.value
        assertFalse(vs.launchControlActive); assertFalse(vs.launchControlEngaged)
        assertNull(vs.fieldLastUpdateMs["lcStatus420"]); assertNull(vs.fieldLastUpdateMs["lc225"])
        service.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
    }
}
