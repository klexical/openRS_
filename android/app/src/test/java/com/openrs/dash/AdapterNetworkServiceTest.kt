package com.openrs.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * #324 at the service's seam (real CanDataService under Robolectric, no adapter): readiness is "a Wi-Fi network is joined",
 * whichever network is the default, so a manual Connect starts while mobile data is the default; and only the network the
 * connection is bound to ends it when it goes away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class AdapterNetworkServiceTest {
    private val controller = TestServices.controller()
    private val service get() = controller.get()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val cm get() = ctx.getSystemService(ConnectivityManager::class.java)
    private fun lines(type: String) = DiagnosticLogger.sessionEvents.filter { it.type == type }.map { it.message }
    private fun field(name: String) = CanDataService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun connectionRunning() = (field("connectionJob").get(service) as kotlinx.coroutines.Job?)?.isActive == true

    private fun wifiMethod() {
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit().putString("connection_method", "WIFI").commit()
    }

    /** Mobile data is the default network; [wifiIds] are joined Wi-Fi networks with no internet (an adapter's access point). */
    private fun networks(vararg wifiIds: Int): List<Network> {
        val shadow = shadowOf(cm)
        shadow.clearAllNetworks()
        val mobile = ShadowNetwork.newInstance(100)
        val mobileInfo = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_MOBILE, 0, true,
            NetworkInfo.State.CONNECTED)
        shadow.addNetwork(mobile, mobileInfo)
        shadow.setNetworkCapabilities(mobile, ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR) })
        shadow.setActiveNetworkInfo(mobileInfo)
        return wifiIds.map { id ->
            ShadowNetwork.newInstance(id).also { net ->
                shadow.addNetwork(net, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0,
                    true, NetworkInfo.State.CONNECTED))
                shadow.setNetworkCapabilities(net, ShadowNetworkCapabilities.newInstance().also {
                    shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
            }
        }
    }

    @Before fun fresh() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }
    @org.junit.After fun destroyServices() { TestServices.destroyAll() }

    @Test fun `a manual Connect starts while mobile data is the default network and a Wi-Fi network is joined`() {
        wifiMethod(); networks(7)
        controller.create()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        val shown = service.startConnection()
        assertNull("nothing to show on screen", shown)
        assertTrue("a connection job runs", connectionRunning())
        assertTrue(lines(DiagnosticLogger.EVENT_LINK_START_REFUSED).toString(),
            lines(DiagnosticLogger.EVENT_LINK_START_REFUSED).none { "Connect" in it })
    }

    @Test fun `a manual Connect with no Wi-Fi network says so on screen`() {
        wifiMethod(); networks()
        controller.create()
        assertEquals(CanDataService.NO_WIFI_MESSAGE, service.startConnection())
        assertTrue(lines(DiagnosticLogger.EVENT_LINK_START_REFUSED).any { it == "not started (Connect): no Wi-Fi network" })
    }

    @Test fun `another Wi-Fi network going away leaves the connection running`() {
        wifiMethod(); val (adapter, other) = networks(7, 8)
        controller.create()
        field("boundNetwork").set(service, adapter)
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        (field("wifiCallback").get(service) as ConnectivityManager.NetworkCallback).onLost(other)
        assertTrue(lines(DiagnosticLogger.EVENT_LINK_STOP_IDLE).toString(), lines(DiagnosticLogger.EVENT_LINK_STOP_IDLE).none { "WIFI_LOST" in it })
        assertEquals(adapter, field("boundNetwork").get(service))
    }

    @Test fun `the bound network going away stops the connection with WIFI_LOST`() {
        wifiMethod(); val (adapter) = networks(7)
        controller.create()
        field("boundNetwork").set(service, adapter)
        (field("wifiCallback").get(service) as ConnectivityManager.NetworkCallback).onLost(adapter)
        assertTrue(lines(DiagnosticLogger.EVENT_LINK_STOP_IDLE).toString(), lines(DiagnosticLogger.EVENT_LINK_STOP_IDLE).any { "reason=WIFI_LOST" in it })
    }
}
