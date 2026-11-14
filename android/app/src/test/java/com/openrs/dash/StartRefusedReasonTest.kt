package com.openrs.dash

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/**
 * #324: every start that is refused because the transport is not ready writes a refused-start line with its reason, on
 * every start path (launch, the Wi-Fi callback, a manual Connect), as the other refusals in `beginConnection` already do.
 * Hosted by Robolectric with the real app class; no adapter, so nothing ever connects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class StartRefusedReasonTest {
    private val controller = TestServices.controller()
    private val service get() = controller.get()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun refused() = DiagnosticLogger.sessionEvents.filter { it.type == DiagnosticLogger.EVENT_LINK_START_REFUSED }.map { it.message }
    private fun field(name: String) = CanDataService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun connectionMethod(m: String) {
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit().putString("connection_method", m).commit()
    }

    /** No network at all: neither a Wi-Fi default nor any other. */
    private fun noNetwork() {
        shadowOf(ctx.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
    }

    @Before fun fresh() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }
    @org.junit.After fun destroyServices() { TestServices.destroyAll() }

    private fun assertOneWifiReason(lines: List<String>) =
        assertTrue("expected one refused-start line naming Wi-Fi, got $lines", lines.size == 1 && "Wi-Fi" in lines.single())

    @Test fun `the launch start refused for no Wi-Fi writes its reason`() {
        connectionMethod("WIFI"); noNetwork()
        controller.create()
        assertOneWifiReason(refused())
    }

    @Test fun `a manual Connect refused for no Wi-Fi writes its reason`() {
        connectionMethod("WIFI"); noNetwork()
        controller.create()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)   // only the Connect's own line counts
        service.startConnection()
        assertOneWifiReason(refused())
    }

    @Test fun `the Wi-Fi callback's start refused for no Wi-Fi writes its reason`() {
        connectionMethod("WIFI"); noNetwork()
        controller.create()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        (field("wifiCallback") as ConnectivityManager.NetworkCallback).onAvailable(ShadowNetwork.newInstance(7))
        assertOneWifiReason(refused())
    }

    @Test fun `a manual Connect refused with Bluetooth off writes its reason`() {
        connectionMethod("BLUETOOTH")
        controller.create()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        service.startConnection()
        val lines = refused()
        assertTrue("expected one refused-start line naming Bluetooth, got $lines", lines.size == 1 && "Bluetooth" in lines.single())
    }
}
