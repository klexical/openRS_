package com.openrs.dash

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.AppVisibility
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.ForegroundController
import com.openrs.dash.service.LinkPresentation
import com.openrs.dash.service.WeatherRepository
import com.openrs.dash.ui.AppSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * #296 production path: a real CanDataService (Robolectric, real app class) promoted as a running connection is, its link
 * published as a connection job publishes it, and a real DriveRecorder on in-memory Room. A recording's start / pause /
 * resume / stop changes the foreground's location type (#261), and each type change re-posts the foreground notification.
 * After each step the notification on show (the last one posted for the service's id) must read
 * `LinkPresentation.notificationText` for the current phase and recording state, and offer Cancel only where
 * `LinkPresentation.offersCancel` does (#212: never once connected). Red on staging a8723016: the re-type posted
 * "Connecting to vehicle…" (and, after a stop, a Cancel action) until the next phase change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ForegroundNotificationTextTest {
    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private val CD = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val CD_LOC = CD or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private lateinit var rec: DriveRecorder
    private lateinit var service: CanDataService
    private val gps = FakeGps()
    private val appVs get() = OpenRSDashApp.instance.vehicleState
    private val link get() = CanDataService.link
    private var gen = 0

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        app.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit().putBoolean(AppSettings.KEY_AUTO_RECORD_DRIVES, false).commit()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        rec = DriveRecorder(app, appVs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { })
        OpenRSDashApp::class.java.getDeclaredField("driveRecorder\$delegate").apply { isAccessible = true }.set(OpenRSDashApp.instance, lazyOf(rec))
        service = TestServices.create()
    }

    // The service first (its scope collects rec.driveState and AppVisibility), then the recorder; the process-wide link
    // and visibility back to their defaults
    @After fun tearDown() {
        TestServices.destroyAll(); rec.cancel(); db.close()
        AppVisibility.visible.value = false
        link.transition { link.end(LinkPhase.OFF) }
    }

    private val fg get() = CanDataService::class.java.getDeclaredField("foreground").apply { isAccessible = true }.get(service) as ForegroundController
    private val applyPhase by lazy {
        CanDataService::class.java.getDeclaredMethod("applyPhase", LinkPhase::class.java).apply { isAccessible = true }
    }
    private val type get() = service.foregroundServiceType

    /** Promote, as beginConnection does (real host): the previous job's phase is still published. */
    private fun promote() = link.transition { fg.promote() }

    /** A connection job takes the link and publishes [phase] through the service's own applyPhase (notification included). */
    private fun publish(phase: LinkPhase) = link.transition {
        if (phase == LinkPhase.CONNECTING) gen = link.begin()
        check(link.publish(gen, phase) { applyPhase.invoke(service, it) })
    }

    /** The notification the system shows for the service: the last one posted under its id (startForeground or notify). */
    private fun shown(): Notification =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(CanDataService.NOTIF_ID)
    private fun text(n: Notification = shown()) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun actions(n: Notification = shown()) = n.actions.orEmpty().map { it.title.toString() }

    private fun expected(phase: LinkPhase) = rec.driveState.value.let {
        LinkPresentation.notificationText(phase, bluetooth = false, recording = it.isRecording, paused = it.isPaused)
    }

    private fun assertShows(step: String, phase: LinkPhase) {
        val n = shown()
        assertEquals("$step: notification text", expected(phase), text(n))
        val cancel = LinkPresentation.offersCancel(phase, rec.driveState.value.isRecording)
        assertEquals("$step: Cancel offered = $cancel (actions ${actions(n)})", cancel, "Cancel" in actions(n))
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private fun startRecording() {
        appVs.value = VehicleState(isConnected = true, speedKph = 50.0)
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 })
    }

    @Test fun `connected - start, pause, resume and stop a recording keep the connected text, and no Cancel`() {
        AppVisibility.visible.value = true
        promote()
        assertEquals("control: the attempt starting reads Connecting…", "Connecting to vehicle…", text())
        assertTrue("control: Cancel offered for the initial attempt", "Cancel" in actions())
        publish(LinkPhase.CONNECTING)
        publish(LinkPhase.CONNECTED)
        assertShows("connected", LinkPhase.CONNECTED)

        startRecording()
        assertTrue("location added on start (type $type)", waitUntil { type == CD_LOC })
        assertShows("after START", LinkPhase.CONNECTED)

        rec.pauseDrive()
        assertTrue("location dropped on pause (type $type)", waitUntil { type == CD })
        assertShows("after PAUSE", LinkPhase.CONNECTED)

        rec.resumeDrive()
        assertTrue("location added on resume (type $type)", waitUntil { type == CD_LOC })
        assertShows("after RESUME", LinkPhase.CONNECTED)

        rec.stopDrive()
        assertTrue("location dropped on stop (type $type)", waitUntil { type == CD })
        assertShows("after STOP", LinkPhase.CONNECTED)
        assertTrue("still foreground for the connection", fg.isForeground)
    }

    @Test fun `a recording started behind gains location when the app is opened, and the text stays the recording one`() {
        AppVisibility.visible.value = false
        promote(); publish(LinkPhase.CONNECTING); publish(LinkPhase.CONNECTED)
        startRecording()
        assertTrue("deferred while behind", waitUntil { fg.locationDeferred })
        assertShows("recording, app behind", LinkPhase.CONNECTED)
        AppVisibility.visible.value = true                                // the user opens the app: the visibility re-type
        assertTrue("location added on return (type $type)", waitUntil { type == CD_LOC })
        assertShows("after the app is opened", LinkPhase.CONNECTED)
    }

    @Test fun `a stop while connected offers no Cancel - checked before the text`() {
        AppVisibility.visible.value = true
        promote(); publish(LinkPhase.CONNECTING); publish(LinkPhase.CONNECTED)
        startRecording()
        assertTrue(waitUntil { type == CD_LOC })
        rec.stopDrive()
        assertTrue("location dropped on stop (type $type)", waitUntil { type == CD })
        assertTrue("no Cancel once connected (#212), actions ${actions()}", "Cancel" !in actions())
        assertShows("after STOP", LinkPhase.CONNECTED)
    }

    @Test fun `a pause while reconnecting keeps the reconnecting text`() {
        AppVisibility.visible.value = true
        promote(); publish(LinkPhase.CONNECTING); publish(LinkPhase.CONNECTED)
        startRecording()
        assertTrue(waitUntil { type == CD_LOC })
        publish(LinkPhase.RECONNECTING)                                   // the link drops; the job keeps retrying
        assertShows("reconnecting", LinkPhase.RECONNECTING)
        rec.pauseDrive()
        assertTrue("location dropped on pause (type $type)", waitUntil { type == CD })
        assertShows("pause while reconnecting", LinkPhase.RECONNECTING)
    }
}
