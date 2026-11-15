package com.openrs.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.can.RequestCapture
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.LinkStopReason
import com.openrs.dash.ui.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * #209 Step B, C6 (the spec review): a manual reconnect or a stop with Request capture on. The service ends the capture
 * itself, with its reason, before the old session ends, so the "off" event belongs to the old session and the new session
 * starts clean; the new link's switch can be turned on. The real service over a fake MeatPi Pro on 127.0.0.1 that answers
 * the SLCAN handshake on every connection (as `ServiceReconnectHeldDropTest`'s ANSWER adapter). Wall clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class RequestCaptureServiceTest {
    private val h = RecorderHarness()
    private val link get() = CanDataService.link
    private lateinit var adapter: Adapter

    private class Adapter {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val open = Collections.synchronizedList(mutableListOf<Socket>())
        init {
            thread(isDaemon = true, name = "fake-adapter") {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    open += s
                    try { s.getOutputStream().apply { write("t09080000C1FF617D0000\r".toByteArray()); flush() } } catch (_: Exception) { }
                }
            }
        }
        fun close() { runCatching { server.close() }; synchronized(open) { open.forEach { runCatching { it.close() } } } }
    }

    private fun connected(): CanDataService {
        adapter = Adapter()
        h.app.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO")
            .putString("wican_host", "127.0.0.1").putInt(AppSettings.KEY_PORT, adapter.port)
            .putBoolean(AppSettings.KEY_AUTO_RECONNECT, false).putInt(AppSettings.KEY_RECONNECT_INTERVAL, 30).commit()
        com.openrs.dash.ui.UserPrefsStore.load(h.app)
        val cm = h.app.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        link.clearManualConnect()
        link.requireManualConnect()
        val svc = TestServices.create()
        svc.startConnection()
        assertTrue("premise: connected", until { link.phase == LinkPhase.CONNECTED })
        assertTrue("premise: the switch turns on", until { svc.setRequestCapture(true) })
        return svc
    }

    private fun until(ms: Long = 8_000, c: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (c()) return true
            Thread.sleep(5)
        }
        return c()
    }

    private fun captureEvents() = DiagnosticLogger.sessionEvents.filter { it.type == DiagnosticLogger.EVENT_REQUEST_CAPTURE }.map { it.message }

    @Before fun fresh() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @After fun tidy() {
        TestServices.destroyAll()
        if (::adapter.isInitialized) adapter.close()
        link.clearManualConnect()
        RequestCapture.shared.linkDown(null, "test end")
        h.close()
    }

    @Test fun `a manual reconnect ends the capture in the old session, and the new session starts clean`() {
        val svc = connected()
        svc.reconnect()
        assertFalse("off at once, by the service", RequestCapture.shared.on.value)
        assertTrue("premise: the new link is up", until { link.phase == LinkPhase.CONNECTED })
        until(1_500L) { false }                                       // the old connection's own end has run by now
        assertEquals("the new session holds no capture event: ${captureEvents()}", emptyList<String>(), captureEvents())
        assertTrue("the new link's switch turns on", svc.setRequestCapture(true))
        assertTrue("and stays on", until(1_000L) { false } || RequestCapture.shared.on.value)
    }

    @Test fun `a stop ends the capture with its reason before the session ends`() {
        val svc = connected()
        val seen = mutableListOf<String>()
        svc.stopConnection(LinkStopReason.USER_DISCONNECT)
        seen += captureEvents()
        assertTrue("the stop's reason is in the session it ends: $seen",
            "Request capture off: ${RequestCapture.REASON_STOPPED}" in seen)
        until(1_500L) { false }
        assertEquals("one off event, not a second from the connection's own end: ${captureEvents()}",
            1, captureEvents().count { it.startsWith("Request capture off") })
    }
}
