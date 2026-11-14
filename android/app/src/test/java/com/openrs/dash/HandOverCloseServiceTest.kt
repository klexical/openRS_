package com.openrs.dash

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.LinkStopReason
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * #324, one rule: a transport the adapter-network probe opened and handed over is always closed when no connection uses it
 * (QA's L1, the spec review's C2). The real CanDataService under Robolectric; the probe's transport counts its closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class HandOverCloseServiceTest {
    private class Counting : SlcanTransport {
        val closes = AtomicInteger()
        private val closed = CompletableDeferred<Unit>()
        override suspend fun open() {}
        override suspend fun readLine(): String? { closed.await(); return null }
        override suspend fun writeLine(frame: String) {}
        override fun close() { closes.incrementAndGet(); closed.complete(Unit) }
        override val label = "test"; override val stockFirmwareLabel = "test"
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val cm get() = ctx.getSystemService(ConnectivityManager::class.java)
    private val link get() = CanDataService.link
    private lateinit var service: CanDataService
    private lateinit var wifi: Network

    private fun prefs(method: String) = ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
        .putString("connection_method", method).putString("adapter_type", "MEATPI_PRO").putString("wican_host", "192.0.2.1").commit()

    /** Mobile data is the default; Wi-Fi 7 is joined. */
    private fun networks() {
        val shadow = shadowOf(cm)
        shadow.clearAllNetworks()
        val mobileInfo = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_MOBILE, 0, true, NetworkInfo.State.CONNECTED)
        val mobile = ShadowNetwork.newInstance(100)
        shadow.addNetwork(mobile, mobileInfo)
        shadow.setNetworkCapabilities(mobile, ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR) })
        shadow.setActiveNetworkInfo(mobileInfo)
        wifi = ShadowNetwork.newInstance(7)
        shadow.addNetwork(wifi, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED))
        shadow.setNetworkCapabilities(wifi, ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
    }

    /** The probe found the adapter on [wifi] and hands [t] over (the service's own entry point, as the finder calls it). */
    private fun handOver(t: Counting) {
        CanDataService::class.java.getDeclaredMethod("startOnAdapterNetwork", Network::class.java, SlcanTransport::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(service, wifi, t, emptyList<String>())
    }

    private fun until(ms: Long = 5_000, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
        return ok()
    }

    @Before fun fresh() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        link.clearManualConnect()
        prefs("WIFI"); networks()
        service = TestServices.create()
    }

    @After fun tidy() {
        link.clearManualConnect()
        TestServices.destroyAll()
    }

    @Test fun `an exception after the hand-over closes the transport (L1)`() {
        // The first read is the foreground promote's (its own catch closes the transport); the second, after the promote,
        // throws out of beginConnection: the start fails after the hand-over and before any connection exists
        val reads = AtomicInteger()
        CanDataService.recorderProvider = {
            if (reads.incrementAndGet() == 2) throw IllegalStateException("test: the start fails after the hand-over")
            CanDataService.APP_RECORDER()
        }
        val t = Counting()
        try { handOver(t) } finally { CanDataService.recorderProvider = CanDataService.APP_RECORDER }   // nothing else throws
        assertTrue("the failure was after the promote",
            DiagnosticLogger.sessionEvents.none { "foreground not allowed" in it.message })
        assertEquals("closed once", 1, t.closes.get())
    }

    @Test fun `a switch to Bluetooth during the probe closes the Wi-Fi transport (C2)`() {
        prefs("BLUETOOTH")
        com.openrs.dash.ui.AppSettings.saveBleDevice(ctx, "00:11:22:33:44:55", "test")
        shadowOf(ctx.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        val t = Counting()
        handOver(t)
        assertTrue("closed", until { t.closes.get() >= 1 })
        assertEquals("closed once", 1, t.closes.get())
    }

    @Test fun `a hold closes the transport (guard)`() {
        link.requireManualConnect()
        val t = Counting()
        handOver(t)
        assertEquals(1, t.closes.get())
    }

    @Test fun `a stop right after the hand-over closes the transport, whether or not the connection took it (guard)`() {
        val t = Counting()
        handOver(t)
        service.stopConnection(LinkStopReason.USER_DISCONNECT)
        assertTrue("closed", until { t.closes.get() >= 1 })
    }
}
