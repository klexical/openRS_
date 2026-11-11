package com.openrs.dash

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.AppVisibility
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.ForegroundController
import com.openrs.dash.service.LinkStopReason
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
 * #261 production path: a real CanDataService (Robolectric, real app class) with its own ForegroundController and host,
 * promoted as a running connection is, and a real DriveRecorder on in-memory Room. The service's foreground type is read
 * back from the framework (`Service.getForegroundServiceType`). Red on 83d009b: while a drive records with the app in front,
 * the service carried connectedDevice only (the emulator baseline: ~0 points / s once the app left the screen).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class LocationFgsServiceTest {
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

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        app.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit().putBoolean(AppSettings.KEY_AUTO_RECORD_DRIVES, false).commit()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        rec = DriveRecorder(app, appVs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { })
        OpenRSDashApp::class.java.getDeclaredField("driveRecorder\$delegate").apply { isAccessible = true }.set(OpenRSDashApp.instance, lazyOf(rec))
        service = TestServices.create()
    }

    // The service first (its scope collects rec.driveState and AppVisibility), then the recorder; visibility back to default
    @After fun tearDown() { TestServices.destroyAll(); rec.cancel(); db.close(); AppVisibility.visible.value = false }

    private val fg get() = CanDataService::class.java.getDeclaredField("foreground").apply { isAccessible = true }.get(service) as ForegroundController
    private fun promote() = CanDataService.link.transition { fg.promote() }      // as beginConnection does, real host
    private val type get() = service.foregroundServiceType
    private fun events() = DiagnosticLogger.sessionEvents.filter { it.type == DiagnosticLogger.EVENT_FGS_LOCATION }.map { it.message }
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

    @Test fun `recording with the app in front adds location, a pause drops it, a resume adds it, the link end removes the foreground`() {
        AppVisibility.visible.value = true
        promote()
        assertEquals("a connection starts with connectedDevice only", CD, type)
        startRecording()
        assertTrue("location added while recording (type ${type})", waitUntil { type == CD_LOC })
        rec.pauseDrive()
        assertTrue("dropped on pause (type ${type})", waitUntil { type == CD })
        rec.resumeDrive()
        assertTrue("added again on resume (type ${type})", waitUntil { type == CD_LOC })
        service.stopConnection(LinkStopReason.USER_DISCONNECT)
        assertTrue("the link end leaves the foreground (N2's path, after the flush)", waitUntil { shadowOf(service).isForegroundStopped })
        val ev = events()
        assertEquals(ev.toString(), 2, ev.count { it.startsWith("location type added") })
        assertEquals(ev.toString(), 1, ev.count { it.startsWith("location type dropped (paused)") })
    }

    @Test fun `recording started with the app behind - connectedDevice only, one event, location added when the app is opened`() {
        AppVisibility.visible.value = false
        promote()
        startRecording()
        assertTrue(waitUntil { events().isNotEmpty() })
        Thread.sleep(200)
        assertEquals("no crash, no location type while behind", CD, type)
        assertEquals(events().toString(), 1, events().count { it.startsWith("recording while the app is not visible") })
        AppVisibility.visible.value = true                               // the user opens the app
        assertTrue("added on return (type ${type})", waitUntil { type == CD_LOC })
    }

    @Test fun `fine location denied - recording keeps connectedDevice only, nothing to report`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        AppVisibility.visible.value = true
        promote()
        appVs.value = VehicleState(isConnected = true, speedKph = 50.0)
        rec.startDrive()
        Thread.sleep(500)
        assertEquals(CD, type)
        assertTrue(events().toString(), events().isEmpty())
    }

    @Test fun `stopping the drive drops the location type and keeps the connection's foreground`() {
        AppVisibility.visible.value = true
        promote()
        startRecording()
        assertTrue(waitUntil { type == CD_LOC })
        rec.stopDrive()
        assertTrue("dropped on stop (type ${type})", waitUntil { type == CD })
        assertTrue("still foreground for the connection", fg.isForeground)
    }
}
