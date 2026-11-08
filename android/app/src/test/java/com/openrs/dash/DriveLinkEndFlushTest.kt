package com.openrs.dash

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * #262: a drive recording when the link ends must not lose its buffered points, and a drive left open by a process death
 * gets a defined end. Runs the real CanDataService stop path (Robolectric, real app class) against a real DriveRecorder on
 * an in-memory Room database, with synthetic GPS through the recorder's own loop:
 *  1. a drive records fewer than FLUSH_SIZE (30) points, so they sit in the buffer;
 *  2. the service's stop path runs (Wi-Fi lost / Disconnect), with auto-record off unless a case says otherwise;
 *  3. the process "dies": the old recorder's DAO refuses every write from then on (cancelling it would run the loop's
 *     final flush, which a killed process never does);
 *  4. a new DriveRecorder opens the same database, as the next launch does.
 * Then Room must hold every point, and the drive an end at its last stored point with its summary from those points.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class DriveLinkEndFlushTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** The production DAO until [dead]; after that every write throws, as nothing runs in a killed process. */
    private class MortalDao(real: DriveDao) {
        @Volatile var dead = false
        val dao: DriveDao = Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            if (dead && (m.name.startsWith("insert") || m.name.startsWith("update") || m.name.startsWith("delete")))
                throw IllegalStateException("process is dead")
            try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as DriveDao
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val gps = FakeGps()
    private val recorders = mutableListOf<DriveRecorder>()
    private lateinit var mortal: MortalDao

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        mortal = MortalDao(db.driveDao())
    }

    @After fun tearDown() {
        recorders.forEach { it.cancel() }
        db.close()
    }

    private val dao get() = db.driveDao()
    private val appVs get() = OpenRSDashApp.instance.vehicleState

    private fun autoRecord(on: Boolean) {
        app.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putBoolean(AppSettings.KEY_AUTO_RECORD_DRIVES, on).commit()
    }

    private fun recorder(d: DriveDao) =
        DriveRecorder(app, appVs, WeatherRepository(""), db, d, { _, _ -> null }, gps, { })
            .also { recorders += it }

    /** The service reads `OpenRSDashApp.instance.driveRecorder` (a `by lazy`); point it at [rec] for this test. */
    private fun install(rec: DriveRecorder) {
        val f = OpenRSDashApp::class.java.getDeclaredField("driveRecorder\$delegate").apply { isAccessible = true }
        f.set(OpenRSDashApp.instance, lazyOf(rec))
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private fun telemetry(i: Int) = VehicleState(
        isConnected = true, speedKph = 60.0 + i, rpm = 2_000.0 + 100 * i, lateralG = 0.1 * i,
        oilTempC = 90.0 + i, coolantTempC = 85.0 + i, driveMode = if (i < 8) DriveMode.NORMAL else DriveMode.SPORT
    )

    private fun fix(i: Int) = Location("test").apply {
        latitude = 51.5 + i * 1e-4; longitude = -0.1; bearing = 0f; speed = 17f; time = System.currentTimeMillis()
    }

    /** Starts a drive on [rec] and records [n] points through its loop; returns the drive id. */
    private fun recordDrive(rec: DriveRecorder, n: Int): Long {
        appVs.value = telemetry(0)
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 }) { "drive did not start" }
        for (i in 0 until n) {
            val before = rec.driveState.value.totalPointCount
            appVs.value = telemetry(i)
            gps.current!!.trySend(fix(i))
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
            Thread.sleep(2)                                    // distinct timestamps
        }
        return rec.driveState.value.driveId
    }

    /** Runs the service stop path for [reason] with [rec] as the app's recorder. */
    private fun stopLink(rec: DriveRecorder, reason: LinkStopReason) {
        install(rec)
        val controller = Robolectric.buildService(CanDataService::class.java).create()
        controller.get().stopConnection(reason)
    }

    /** Waits for the stop path's writes (if any), then kills the old recorder's process and opens the next one. */
    private fun dieAndRelaunch(driveId: Long, expectPoints: Int): DriveEntity {
        waitUntil(3_000) { dao.getPoints(driveId).size >= expectPoints }    // a fix lands within this; the old code never does
        mortal.dead = true
        recorder(dao)                                                       // the next launch: init closes open drives
        check(waitUntil { (dao.getDrive(driveId)?.endTime ?: 0L) != 0L }) { "the orphan was never closed" }
        return dao.getDrive(driveId)!!
    }

    private fun linkEndKeepsEveryPoint(reason: LinkStopReason) {
        autoRecord(false)
        val rec = recorder(mortal.dao)
        val n = 12
        val id = recordDrive(rec, n)
        stopLink(rec, reason)
        assertTrue("auto-record off: the drive keeps recording through the link end",
            rec.driveState.value.isRecording && !rec.driveState.value.isPaused)
        val drive = dieAndRelaunch(id, n)

        val points = dao.getPoints(id)
        assertEquals("[$reason] every recorded point is in Room after the process died", n, points.size)
        assertEquals("[$reason] the drive ends at its last stored point", points.last().timestamp, drive.endTime)
        assertEquals("[$reason] max speed from the stored points", 71.0, drive.maxSpeedKph, 1e-9)
        assertEquals("[$reason] average speed from the stored points", 65.5, drive.avgSpeedKph, 1e-9)
        assertEquals("[$reason] peak RPM from the stored points", 3_100, drive.peakRpm)
        assertEquals("[$reason] peak |lateral G| from the stored points", 1.1, drive.peakLateralG, 1e-9)
        assertEquals("[$reason] peak oil from the stored points", 101.0, drive.peakOilTempC, 1e-9)
        assertEquals("[$reason] start / end oil from the first / last points", 90.0 to 101.0, drive.startOilTempC to drive.endOilTempC)
        assertTrue("[$reason] distance from consecutive stored points (11 × ~11.1 m), got ${drive.distanceKm}",
            drive.distanceKm in 0.11..0.13)
        val modes = org.json.JSONObject(drive.driveModeBreakdown)
        assertEquals("[$reason] mode breakdown from the stored points", 8.0 / 12, modes.getDouble("Normal"), 1e-9)
    }

    @Test fun `Wi-Fi lost with auto-record off - buffered points survive a process death`() =
        linkEndKeepsEveryPoint(LinkStopReason.WIFI_LOST)

    @Test fun `Disconnect with auto-record off - buffered points survive a process death`() =
        linkEndKeepsEveryPoint(LinkStopReason.USER_DISCONNECT)

    @Test fun `auto-record on - the link end still pauses the drive and its points are stored (unchanged)`() {
        autoRecord(true)
        val rec = recorder(mortal.dao)
        val id = recordDrive(rec, 5)
        stopLink(rec, LinkStopReason.WIFI_LOST)
        assertTrue("auto-record on pauses, as before", rec.driveState.value.isPaused)
        val drive = dieAndRelaunch(id, 5)
        assertEquals(5, dao.getPoints(id).size)
        assertEquals(dao.getPoints(id).last().timestamp, drive.endTime)
    }

    @Test fun `the startup cleanup never closes a drive the same recorder has just started`() {
        // Hold the cleanup's query until this recorder's own drive is in Room — the order a START right after the
        // recorder's creation can produce. Found by the cases above: the drive was closed at its start with no points.
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val queried = java.util.concurrent.CountDownLatch(1)
        val real = db.driveDao()
        val held = Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            if (m.name == "getUnfinishedDrives") { entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
                .also { if (m.name == "getUnfinishedDrives") queried.countDown() }
        } as DriveDao
        val rec = recorder(held)
        check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val id = recordDrive(rec, 3)
        release.countDown()
        check(queried.await(5, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(300)                                            // the cleanup's update (old code) lands well within this
        assertEquals("the recorder's own drive is still open", 0L, dao.getDrive(id)!!.endTime)
        assertTrue(rec.driveState.value.isRecording)
    }

    @Test fun `an orphan's distance never spans a gap between stored points`() {
        val id = dao.insertDrive(DriveEntity(startTime = 1_000_000L))
        val pts = listOf(0L, 1_000L, 2_000L, 30_000L, 31_000L).mapIndexed { i, t ->
            DrivePointEntity(driveId = id, timestamp = 1_000_000L + t, lat = 51.5 + i * 1e-3, lng = -0.1, speedKph = 50.0)
        }
        dao.insertPoints(pts)
        recorder(dao)
        check(waitUntil { (dao.getDrive(id)?.endTime ?: 0L) != 0L })
        val d = dao.getDrive(id)!!
        // 3 of the 4 segments (~0.111 km each); the 28 s gap (a pause or a link loss the recorder never bridges) is left out
        assertTrue("got ${d.distanceKm}", d.distanceKm in 0.32..0.35)
        assertEquals(1_031_000L, d.endTime)
    }

    @Test fun `an orphan with no points ends at its start and keeps an empty summary`() {
        val id = dao.insertDrive(DriveEntity(startTime = 5_000L))
        recorder(dao)
        check(waitUntil { (dao.getDrive(id)?.endTime ?: 0L) != 0L })
        val d = dao.getDrive(id)!!
        assertEquals(5_000L, d.endTime)
        assertEquals(0.0, d.distanceKm, 0.0)
        assertEquals(0.0, d.maxSpeedKph, 0.0)
        assertEquals(-99.0, d.peakOilTempC, 0.0)
    }
}
