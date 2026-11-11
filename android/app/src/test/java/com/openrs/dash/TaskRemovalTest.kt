package com.openrs.dash

import com.openrs.dash.data.DrivePhase
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * #212 option 2: a task removal ends only an initial attempt, exactly where the app offers Cancel, and saves nothing, so the next launch auto-connects. Established
 * links, reconnects and open drives are untouched. The real CanDataService and LinkOwnership under Robolectric; an actual swipe, Back and the STOP race are QA's emulator work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class TaskRemovalTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val link get() = CanDataService.link
    private val phaseNow get() = OpenRSDashApp.instance.linkPhase.value
    private lateinit var service: CanDataService
    private val recorder get() = OpenRSDashApp.instance.driveRecorder

    private fun lines(type: String) = DiagnosticLogger.sessionEvents.filter { it.type == type }.map { it.message }

    private fun wifiUp() {
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO").putString("wican_host", "192.0.2.1").commit()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifi = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)
    }

    @Suppress("UNCHECKED_CAST")
    private fun recording(recording: Boolean, paused: Boolean = false) {
        val f = recorder.javaClass.getDeclaredField("_driveState").apply { isAccessible = true }
        val flow = f.get(recorder) as MutableStateFlow<com.openrs.dash.data.DriveState>
        flow.value = flow.value.copy(phase = DrivePhase.of(recording, paused))
    }

    /**
     * CONNECTING: a real running attempt. Any other phase: ownership with that phase through the same publish a job uses, but no job, whose own connecting
     * state would otherwise race the phase a test needs.
     */
    private fun attempt(phase: LinkPhase = LinkPhase.CONNECTING) {
        if (phase == LinkPhase.CONNECTING) {
            service.startConnection()
            assertEquals("premise", LinkPhase.CONNECTING, link.phase)
        } else {
            val gen = link.begin()
            assertTrue(link.publish(gen, phase) { OpenRSDashApp.instance.linkPhase.value = it })
        }
    }

    @Before fun fresh() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        wifiUp()
        link.clearManualConnect()
        service = TestServices.create()
        service.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
        recording(false)
    }

    @After fun tidy() {
        recording(false)
        service.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
        link.clearManualConnect()
        TestServices.destroyAll()                                       // this test's services, `next` ones included
    }

    @Test fun `an initial attempt ends, with its reason, and no Cancel is saved`() {
        attempt()
        service.onTaskRemoved(null)
        assertEquals(LinkPhase.OFF, phaseNow)
        assertEquals(LinkPhase.OFF, link.phase)
        val stop = lines(DiagnosticLogger.EVENT_LINK_STOP) + lines(DiagnosticLogger.EVENT_LINK_STOP_IDLE)
        assertTrue(stop.toString(), stop.any { it.contains("reason=TASK_REMOVED") })
        assertFalse("a swipe isn't a Cancel", link.userCancelled)
    }

    @Test fun `control - an established link stays`() {
        attempt(LinkPhase.CONNECTED)
        service.onTaskRemoved(null)
        assertEquals(LinkPhase.CONNECTED, link.phase)
    }

    @Test fun `control - a reconnect stays`() {
        attempt(LinkPhase.RECONNECTING)
        service.onTaskRemoved(null)
        assertEquals(LinkPhase.RECONNECTING, link.phase)
    }

    @Test fun `control - an attempt with a drive recording stays, and the drive with it`() {
        attempt()
        recording(true)
        service.onTaskRemoved(null)
        assertEquals(LinkPhase.CONNECTING, link.phase)
        assertTrue(recorder.driveState.value.isRecording)
    }

    @Test fun `control - an attempt with a paused drive stays, still paused`() {
        attempt()
        recording(true, paused = true)
        service.onTaskRemoved(null)
        assertEquals(LinkPhase.CONNECTING, link.phase)
        assertTrue(recorder.driveState.value.isPaused)
    }

    @Test fun `control - with nothing running a removal changes nothing`() {
        service.onTaskRemoved(null)
        assertEquals(LinkPhase.OFF, link.phase)
        assertFalse(link.manualConnectRequired)
    }

    // ── a finalize, the hold, and the next launch ───────────────────────────────────────────────────────────────────

    @Test fun `an attempt stays while a stopped drive is being finalized, whose write the foreground service protects`() {
        attempt()
        val job = kotlinx.coroutines.Job()
        recorder.javaClass.getDeclaredField("finalizeJob").apply { isAccessible = true }.set(recorder, job)
        assertTrue("premise", recorder.isFinalizing)
        service.onTaskRemoved(null)
        assertEquals(LinkPhase.CONNECTING, link.phase)
        job.cancel()
        assertFalse(recorder.isFinalizing)
        service.onTaskRemoved(null)
        assertEquals("the same removal, once it's finished", LinkPhase.OFF, link.phase)
    }

    @Test fun `a network callback between the removal and process death doesn't restart the attempt`() {
        attempt()
        service.onTaskRemoved(null)
        assertTrue(link.manualConnectRequired)
        val cb = CanDataService::class.java.getDeclaredField("wifiCallback").apply { isAccessible = true }.get(service) as ConnectivityManager.NetworkCallback
        cb.onAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(7))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(LinkPhase.OFF, link.phase)
    }

    @Test fun `the next launch auto-connects, this service or process having survived`() {
        attempt()
        service.onTaskRemoved(null)
        service.onActivityBound()
        assertEquals(LinkPhase.CONNECTING, link.phase)
        assertFalse(link.manualConnectRequired)
    }

    @Test fun `the next launch auto-connects with a new service and nothing saved`() {
        attempt()
        service.onTaskRemoved(null)
        val next = TestServices.create()
        assertEquals("the hold still covers the service's own start", LinkPhase.OFF, link.phase)
        next.onActivityBound()
        assertEquals(LinkPhase.CONNECTING, link.phase)
        next.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
    }

    @Test fun `the next launch's refused service start names the task removal's hold`() {
        attempt()
        service.onTaskRemoved(null)
        val next = TestServices.create()
        assertEquals(listOf("auto-start held: a task removal, until the app is opened"), lines(DiagnosticLogger.EVENT_LINK_START_REFUSED))
        next.onActivityBound()
        assertEquals(LinkPhase.CONNECTING, link.phase)
        next.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
    }

    @Test fun `control - a refused start names a saved Cancel, and demo mode, as what holds it`() {
        attempt()
        assertTrue(service.cancelConnection())
        TestServices.create()
        link.requireManualConnect()
        TestServices.create()
        assertEquals(listOf("auto-start held: a saved Cancel", "auto-start held: a saved Cancel + demo mode"),
            lines(DiagnosticLogger.EVENT_LINK_START_REFUSED))
    }

    /**
     * The guard in onTaskRemoved: while demo mode owns the vehicle state a removal leaves the link alone. It is defensive: through today's service paths
     * removing it changes nothing, because enterDemoMode stops the link before demo starts and every start path (Connect, Retry, callbacks, reconnect)
     * refuses while demo is active, so demo mode and a running attempt never meet. The test therefore sets demo's status flag by reflection while a real
     * attempt runs: DemoMode.start returns early in release builds, and this keeps the test in both variants. It pins the guard against a future start path.
     */
    @Test fun `in demo mode a removal leaves an attempt alone and holds nothing`() {
        attempt()
        @Suppress("UNCHECKED_CAST")
        val status = com.openrs.dash.demo.DemoMode::class.java.getDeclaredField("_status").apply { isAccessible = true }
            .get(com.openrs.dash.demo.DemoMode) as MutableStateFlow<com.openrs.dash.demo.DemoMode.Status>
        status.value = com.openrs.dash.demo.DemoMode.Status(active = true)
        try {
            service.onTaskRemoved(null)
            assertTrue(lines(DiagnosticLogger.EVENT_LINK_TASK_REMOVED).last(), lines(DiagnosticLogger.EVENT_LINK_TASK_REMOVED).last().endsWith("demo=yes"))
            assertEquals(LinkPhase.CONNECTING, link.phase)
            assertFalse(link.manualConnectRequired)
            assertTrue(lines(DiagnosticLogger.EVENT_LINK_STOP).none { "TASK_REMOVED" in it })
        } finally {
            status.value = com.openrs.dash.demo.DemoMode.Status()
        }
    }

    @Test fun `binding with no removal behind it starts nothing`() {
        service.onActivityBound()
        assertEquals(LinkPhase.OFF, link.phase)
    }

    @Test fun `a real Cancel stays saved through a bind`() {
        attempt()
        assertTrue(service.cancelConnection())
        service.onActivityBound()
        assertEquals(LinkPhase.OFF, link.phase)
        assertTrue(link.userCancelled)
    }

    @Test fun `an explicit connect clears the hold`() {
        attempt()
        service.onTaskRemoved(null)
        link.clearManualConnect()
        assertFalse(link.manualConnectRequired)
    }

    @Test fun `a removal late for a link that came up does nothing, and its line still says what it saw`() {
        attempt(LinkPhase.CONNECTED)
        service.onTaskRemoved(null)
        assertTrue(lines(DiagnosticLogger.EVENT_LINK_TASK_REMOVED).last(), lines(DiagnosticLogger.EVENT_LINK_TASK_REMOVED).last().startsWith("phase=CONNECTED"))
        assertFalse(link.manualConnectRequired)
    }
}
