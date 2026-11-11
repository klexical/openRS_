package com.openrs.dash

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.VehicleState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * N2 (#262): at a link end the foreground service stays until the drive's points are durable — the buffered ones AND a batch
 * insert already in flight — and then leaves it exactly once; a new attempt started meanwhile keeps it. Real CanDataService
 * (Robolectric, real app class) promoted through its own ForegroundController (a counting host), real DriveRecorder on
 * in-memory Room behind a DAO whose point inserts can be held or failed. Only APIs that exist on c1c739d (red there: the
 * service left the foreground at once, with the insert still in flight).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ServiceFlushDemoteTest {
    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** Point inserts can be held (until [release]) or failed. */
    private class GatedDao(real: DriveDao) {
        @Volatile var hold = false
        @Volatile var fail = false
        /** #316 P1: holds the next drive read (the STOP's finalize reads the row first), until [release]. */
        @Volatile var holdRead = false
        val readEntered = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dao: DriveDao = Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            if (m.name == "getDrive" && holdRead) { holdRead = false; readEntered.countDown(); release.await(20, TimeUnit.SECONDS) }
            if (m.name == "insertPoints") {
                if (fail) throw IllegalStateException("simulated write failure")
                if (hold) { entered.countDown(); release.await(20, TimeUnit.SECONDS) }
            }
            try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as DriveDao
    }

    /** Counts what the service's foreground does. */
    private class CountingHost : ForegroundController.Host {
        val exits = AtomicInteger(); val stops = AtomicInteger()
        override fun startSelf() = Unit
        override fun enterForeground(location: Boolean) = Unit
        override fun exitForeground() { exits.incrementAndGet() }
        override fun stopSelf() { stops.incrementAndGet() }
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private lateinit var gated: GatedDao
    private lateinit var rec: DriveRecorder
    private lateinit var service: CanDataService
    private val host = CountingHost()
    private lateinit var fg: ForegroundController
    private val gps = FakeGps()
    private val appVs get() = OpenRSDashApp.instance.vehicleState

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        gated = GatedDao(db.driveDao())
        rec = DriveRecorder(app, appVs, WeatherRepository(""), db, gated.dao, { _, _ -> null }, gps, { })
        CanDataService.recorderProvider = { rec }                      // #316 seam 5
        service = TestServices.create()
        fg = ForegroundController(host)
        CanDataService::class.java.getDeclaredField("foreground").apply { isAccessible = true }.set(service, fg)
        fg.promote()                                                    // as a running connection has it
        assertTrue(fg.isForeground)
    }

    // Destroy the service first: its scope collects rec.driveState, and a live collector would publish a notification
    // from a worker thread after Robolectric's reset (an uncaught NPE charged to a later test, gate on 16c5c2c).
    @After fun tearDown() { gated.release.countDown(); TestServices.destroyAll(); rec.cancel(); db.close() }

    private fun autoRecord(on: Boolean) {
        app.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit().putBoolean(AppSettings.KEY_AUTO_RECORD_DRIVES, on).commit()
        com.openrs.dash.ui.UserPrefsStore.load(app)                      // #316: a change reaches the recorder through the store
    }
    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }
    private fun recordPoints(n: Int): Long {
        appVs.value = VehicleState(isConnected = true, speedKph = 50.0)
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 })
        for (i in 0 until n) {
            val before = rec.driveState.value.totalPointCount
            gps.current!!.trySend(Location("t").apply { latitude = 51.5 + i * 1e-4; longitude = -0.1; time = System.currentTimeMillis() })
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
        return rec.driveState.value.driveId
    }
    private fun rows(id: Long) = db.driveDao().getPoints(id).size

    @Test fun `a full 30-point batch insert in flight - the service stays foreground until it lands, then leaves once`() {
        autoRecord(false)
        gated.hold = true
        val id = recordPoints(30)                                       // the 30th point makes the loop take all 30 and insert
        assertTrue("the batch insert is in flight", gated.entered.await(5, TimeUnit.SECONDS))
        service.stopConnection(LinkStopReason.WIFI_LOST)
        Thread.sleep(300)
        assertTrue("still foreground while 30 points are not durable", fg.isForeground)
        assertEquals(0, host.exits.get())
        gated.release.countDown()
        assertTrue("30 rows", waitUntil { rows(id) == 30 })
        assertTrue("left the foreground", waitUntil { !fg.isForeground })
        Thread.sleep(200)
        assertEquals("exactly one demotion", 1, host.exits.get()); assertEquals(1, host.stops.get())
        assertEquals(30, rows(id))
    }

    @Test fun `a partial buffer - the link-end flush is held - the service stays until it lands, then leaves once`() {
        autoRecord(false)
        val id = recordPoints(12)                                       // below the batch size: all 12 in the buffer
        gated.hold = true
        service.stopConnection(LinkStopReason.USER_DISCONNECT)
        assertTrue("the link-end flush is writing", gated.entered.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertTrue("still foreground while 12 points are not durable", fg.isForeground)
        gated.release.countDown()
        assertTrue(waitUntil { rows(id) == 12 })
        assertTrue(waitUntil { !fg.isForeground })
        Thread.sleep(200)
        assertEquals(1, host.exits.get())
    }

    @Test fun `auto-record on - the pause flush is held - the service stays until it lands`() {
        autoRecord(true)
        val id = recordPoints(12)
        gated.hold = true
        service.stopConnection(LinkStopReason.WIFI_LOST)
        assertTrue(gated.entered.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertTrue("still foreground while the pause flush writes", fg.isForeground)
        gated.release.countDown()
        assertTrue(waitUntil { rows(id) == 12 })
        assertTrue(waitUntil { !fg.isForeground })
        assertTrue(rec.driveState.value.isPaused)
    }

    @Test fun `a failed write - the service still leaves the foreground, once, and nothing hangs`() {
        autoRecord(false)
        recordPoints(5)
        gated.fail = true
        service.stopConnection(LinkStopReason.WIFI_LOST)
        assertTrue(waitUntil { !fg.isForeground })
        Thread.sleep(200)
        assertEquals(1, host.exits.get())
    }

    @Test fun `a write that never completes can't hold the foreground - it is released after the bounded wait, once`() {
        autoRecord(false)
        recordPoints(5)
        gated.hold = true                                               // never released during the test
        val before = CanDataService.flushDemoteWaitMs
        CanDataService.flushDemoteWaitMs = 500L
        try {
            service.stopConnection(LinkStopReason.WIFI_LOST)
            assertTrue(gated.entered.await(5, TimeUnit.SECONDS))
            assertTrue("released after the bounded wait", waitUntil(3_000) { !fg.isForeground })
            Thread.sleep(200)
            assertEquals(1, host.exits.get())
        } finally { CanDataService.flushDemoteWaitMs = before }
    }

    @Test fun `a new attempt started while the flush is held keeps the foreground`() {
        autoRecord(false)
        recordPoints(12)
        gated.hold = true
        service.stopConnection(LinkStopReason.WIFI_LOST)
        assertTrue(gated.entered.await(5, TimeUnit.SECONDS))
        val gen = CanDataService.link.begin()                            // a new attempt (as beginConnection does), still promoted
        try {
            gated.release.countDown()
            Thread.sleep(800)
            assertTrue("the new attempt's foreground is kept", fg.isForeground)
            assertEquals(0, host.exits.get())
        } finally {
            if (CanDataService.link.owns(gen)) CanDataService.link.end(LinkPhase.OFF)
        }
        assertFalse(rec.driveState.value.isPaused)
    }

    /**
     * #316 P1 (QA on 8335545): entering demo mode stops the drive BEFORE the service closes the connection, so the closure's
     * durability barrier covers the saved drive's finalize and the foreground stays until it is written (#262 / N2). In the
     * other order the barrier would cover only the point flush, and the finalize would run after the foreground was gone.
     * Release builds have no demo mode: there entering it changes nothing.
     */
    @Test fun `entering demo mode - the service stays foreground until the stopped drive is saved, then leaves once`() {
        autoRecord(false)
        val id = recordPoints(5)
        gated.holdRead = true
        service.enterDemoMode(com.openrs.dash.demo.DemoScenario.IDLE)
        try {
            if (!com.openrs.dash.demo.DemoMode.AVAILABLE) {
                assertTrue("release: no demo mode", rec.driveState.value.isRecording && fg.isForeground)
                return
            }
            assertTrue("the finalize is reading the drive", gated.readEntered.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertTrue("still foreground while the stopped drive is not saved", fg.isForeground)
            assertEquals(0, host.exits.get())
            gated.release.countDown()
            assertTrue("saved", waitUntil { db.driveDao().getDrive(id)!!.endTime != 0L })
            assertTrue("left the foreground", waitUntil { !fg.isForeground })
            Thread.sleep(200)
            assertEquals("exactly one demotion", 1, host.exits.get())
        } finally {
            service.exitDemoMode()
        }
    }
}
