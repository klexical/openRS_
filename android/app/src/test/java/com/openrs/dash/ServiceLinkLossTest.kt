package com.openrs.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * #316 seams 4 and 5: the service reporting "connection lost" when it closes a connection, with the recorder handed in
 * through the provider (no reflection). A closure is reported always, with the moment of the closure: for a connection
 * job under way, also one that never connected, and (as the pause at a link end always was) with none. What the recorder
 * does with the report is seam 1's (LinkPauseTest); here the drive's phase only shows that the report arrived.
 *
 * The attempt is a real one to an unreachable test address (192.0.2.1, TEST-NET-1), as in TaskRemovalTest: it never
 * connects, so the drop-then-closure case (the connection's held drop moment) is covered at the connection
 * (SlcanConnectionLifecycleTest), not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ServiceLinkLossTest {
    private val h = RecorderHarness()
    private val link get() = CanDataService.link
    private lateinit var rec: DriveRecorder

    private fun settings(autoRecord: Boolean) {
        val ctx = h.app
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", "MEATPI_PRO").putString("wican_host", "192.0.2.1")
            .putBoolean(AppSettings.KEY_AUTO_RECORD_DRIVES, autoRecord).commit()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifi = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)
    }

    /** A recording drive on the service's recorder (wall clock: the service reports wall-clock moments). */
    private fun service(autoRecord: Boolean, holdAutoConnect: Boolean = false): CanDataService {
        settings(autoRecord)
        link.clearManualConnect()
        // Wi-Fi is up, so a created service connects by itself unless automatic connects are held
        if (holdAutoConnect) link.requireManualConnect()
        // The recorder is told nothing about auto-record here: the service hands it the setting at connection start
        rec = with(h) { recorder(clock = System::currentTimeMillis).recording() }
        CanDataService.recorderProvider = { rec }
        return TestServices.create()
    }

    @Before fun fresh() { com.openrs.dash.diagnostics.DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @After fun tidy() {
        TestServices.destroyAll()                                        // the service first (it collects rec's state)
        link.clearManualConnect()
        h.close()
    }

    @Test fun `closing an attempt that never connected is a loss at the closure - auto-record on, the drive link-pauses`() {
        val svc = service(autoRecord = true)
        svc.startConnection()
        assertEquals("premise: an attempt under way", LinkPhase.CONNECTING, link.phase)
        svc.stopConnection(LinkStopReason.USER_DISCONNECT)
        assertEquals(DrivePhase.PAUSED, rec.driveState.value.phase)
        assertEquals(PauseReason.LINK, rec.driveState.value.pauseReason)
        val paused = rec.pausedDurationMs()
        assertTrue("paused from the closure's moment: $paused ms", paused in 0L..5_000L)
    }

    @Test fun `closing an attempt with auto-record off - the drive keeps recording`() {
        val svc = service(autoRecord = false)
        svc.startConnection()
        assertEquals("premise", LinkPhase.CONNECTING, link.phase)
        svc.stopConnection(LinkStopReason.USER_DISCONNECT)
        h.settle()
        assertEquals(DrivePhase.RECORDING, rec.driveState.value.phase)
    }

    @Test fun `a stop with no connection under way is reported too - auto-record on, the drive link-pauses`() {
        val svc = service(autoRecord = true, holdAutoConnect = true)
        assertEquals("premise: nothing under way", false, link.phase == LinkPhase.CONNECTING)
        svc.stopConnection(LinkStopReason.WIFI_LOST)
        assertEquals(DrivePhase.PAUSED, rec.driveState.value.phase)
        assertEquals(PauseReason.LINK, rec.driveState.value.pauseReason)
    }
}
