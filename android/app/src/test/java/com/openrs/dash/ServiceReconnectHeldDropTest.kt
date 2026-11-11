package com.openrs.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.LinkStopReason
import com.openrs.dash.ui.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
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
 * #316 point (b), owner "Yes, carry it" (seams 4 and 5, no new seam): an established connection drops, the user taps
 * reconnect (RCN) while its attempts are still under way. The held loss goes to the new attempts: if they fail, one
 * "connection lost" with the moment of the original drop; a connect clears it; the tap itself reports nothing.
 *
 * The adapter is a real TCP server on 127.0.0.1 standing in for a MeatPi Pro (raw SLCAN lines): the first connection
 * answers the SLCAN handshake (the 0x090 line SlcanConnectionLifecycleTest uses) and the test then closes it (the drop);
 * later connections are refused (the server closed), held 2.5 s with no answer and closed (each attempt fails then), or
 * answered. Auto-reconnect is off (one attempt per job) and the reconnect interval 30 s, so the old job sits in its
 * backoff when the user taps. Wall clock: the discriminating check is the pause's age, which is the gap between the drop
 * and the report (the tap's moment, or the report's, would give an age near 0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ServiceReconnectHeldDropTest {
    private val h = RecorderHarness()
    private val link get() = CanDataService.link
    private lateinit var rec: DriveRecorder
    private lateinit var adapter: FakeAdapter

    /** What the fake adapter does with every connection after the first. */
    private enum class Later { REFUSE, SILENT, ANSWER }

    private class FakeAdapter(private val later: Later) {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val open = Collections.synchronizedList(mutableListOf<Socket>())
        @Volatile private var first: Socket? = null
        @Volatile var accepted = 0

        init {
            thread(isDaemon = true, name = "fake-adapter") {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    open += s
                    accepted++
                    if (accepted == 1) first = s
                    if (accepted == 1 || later == Later.ANSWER) answer(s)
                    // SILENT: no answer, then the adapter closes the connection itself after 2.5 s (the handshake's 3 s
                    // timeout cannot interrupt a blocking TCP read, which would hold the attempt for the 20 s read timeout)
                    else if (later == Later.SILENT) thread(isDaemon = true) { Thread.sleep(2_500L); runCatching { s.close() } }
                }
            }
        }

        private fun answer(s: Socket) = try {
            s.getOutputStream().apply { write("t09080000C1FF617D0000\r".toByteArray()); flush() }
        } catch (_: Exception) { }

        /** The established connection drops; with [Later.REFUSE] every later attempt is refused at once. */
        fun drop() {
            if (later == Later.REFUSE) server.close()
            first?.close()
        }

        fun close() { runCatching { server.close() }; synchronized(open) { open.forEach { runCatching { it.close() } } } }
    }

    private fun wifi(on: Boolean) {
        val cm = h.app.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        if (on) Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    /** A recording drive, auto-record on, auto-reconnect off, connected to the fake adapter. */
    private fun connected(later: Later): CanDataService {
        adapter = FakeAdapter(later)
        h.app.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO")
            .putString("wican_host", "127.0.0.1").putInt(AppSettings.KEY_PORT, adapter.port)
            .putBoolean(AppSettings.KEY_AUTO_RECONNECT, false).putInt(AppSettings.KEY_RECONNECT_INTERVAL, 30)
            .putBoolean(AppSettings.KEY_AUTO_RECORD_DRIVES, true).commit()
        com.openrs.dash.ui.UserPrefsStore.load(h.app)                    // the service tells the recorder at its creation
        wifi(on = true)
        link.clearManualConnect()
        link.requireManualConnect()                                     // the test starts the connection itself
        rec = with(h) { recorder(clock = System::currentTimeMillis).recording() }
        CanDataService.recorderProvider = { rec }
        val svc = TestServices.create()
        svc.startConnection()
        assertTrue("premise: connected", until { link.phase == LinkPhase.CONNECTED })
        return svc
    }

    /** The adapter drops the connection; returns once the app has found it (RECONNECTING published). */
    private fun dropped() {
        adapter.drop()
        assertTrue("premise: the drop was found", until { link.phase == LinkPhase.RECONNECTING })
    }

    /** Waits on the wall clock with the main looper running (the service publishes its phases there). */
    private fun until(ms: Long = 8_000, c: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (c()) return true
            Thread.sleep(5)
        }
        return c()
    }

    private fun pass(ms: Long) = until(ms) { false }

    private val linkPaused get() = rec.driveState.value.let { it.phase == DrivePhase.PAUSED && it.pauseReason == PauseReason.LINK }

    @Before fun fresh() { com.openrs.dash.diagnostics.DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @After fun tidy() {
        TestServices.destroyAll()                                        // the service first (it collects rec's state)
        if (::adapter.isInitialized) adapter.close()
        link.clearManualConnect()
        h.close()
    }

    @Test fun `QA trace - a drop, RCN during the attempts, the new attempt fails - one loss with the drop's moment`() {
        val svc = connected(Later.REFUSE)
        dropped()
        pass(1_500L)                                                     // the old job is in its 30 s backoff
        assertEquals("premise: still recording", DrivePhase.RECORDING, rec.driveState.value.phase)
        svc.reconnect()
        assertTrue("the new attempt's failure reports the held loss", until { linkPaused })
        val age = rec.pausedDurationMs()
        assertTrue("paused from the drop, not the tap: $age ms", age in 1_400L..8_000L)
    }

    @Test fun `the tap itself reports nothing and pauses nothing`() {
        val svc = connected(Later.SILENT)                                 // the new attempt fails after 2.5 s
        dropped()
        svc.reconnect()
        pass(1_000L)
        assertEquals(DrivePhase.RECORDING, rec.driveState.value.phase)
        assertTrue("then its failure reports", until { linkPaused })
    }

    @Test fun `a second tap during the new attempts keeps the first moment`() {
        val svc = connected(Later.SILENT)
        dropped()
        pass(1_000L)
        svc.reconnect()
        pass(1_000L)
        assertEquals("premise", DrivePhase.RECORDING, rec.driveState.value.phase)
        svc.reconnect()
        assertTrue(until { linkPaused })
        val age = rec.pausedDurationMs()
        assertTrue("paused from the drop: $age ms", age in 1_900L..10_000L)
    }

    @Test fun `a closure during the new attempts reports the held moment`() {
        val svc = connected(Later.SILENT)
        dropped()
        pass(1_000L)
        svc.reconnect()
        pass(500L)
        svc.stopConnection(LinkStopReason.USER_DISCONNECT)
        assertTrue(linkPaused)
        val age = rec.pausedDurationMs()
        assertTrue("paused from the drop: $age ms", age in 1_400L..8_000L)
    }

    @Test fun `RCN that cannot begin a connection reports the held moment`() {
        val svc = connected(Later.REFUSE)
        dropped()
        wifi(on = false)
        pass(1_000L)
        assertEquals("premise: the old job still retrying", LinkPhase.RECONNECTING, link.phase)
        svc.reconnect()
        assertEquals("premise: nothing started", false, link.phase == LinkPhase.CONNECTING)
        assertTrue(linkPaused)
        val age = rec.pausedDurationMs()
        assertTrue("paused from the drop: $age ms", age in 900L..8_000L)
    }

    @Test fun `RCN from a healthy connection reports nothing and pauses nothing`() {
        val svc = connected(Later.ANSWER)
        svc.reconnect()
        assertTrue("premise: connected again", until { link.phase == LinkPhase.CONNECTED && adapter.accepted == 2 })
        pass(500L)
        assertEquals(DrivePhase.RECORDING, rec.driveState.value.phase)
    }

    @Test fun `a drop, RCN, the new attempt connects - no loss, then or later`() {
        val svc = connected(Later.ANSWER)
        dropped()
        svc.reconnect()
        assertTrue("premise: connected again", until { link.phase == LinkPhase.CONNECTED && adapter.accepted >= 2 })
        pass(2_000L)                                                       // a drop kept by mistake would read ≥ 2 s below
        assertEquals(DrivePhase.RECORDING, rec.driveState.value.phase)
        svc.stopConnection(LinkStopReason.USER_DISCONNECT)                 // a later closure: its own moment, not the drop's
        assertTrue(linkPaused)
        // ≤ 1 s: the closure's synchronous teardown (endConnection) counts against it on a loaded CI machine (#313 / #314)
        assertTrue("paused from the closure: ${rec.pausedDurationMs()} ms", rec.pausedDurationMs() in 0L..1_000L)
    }
}
