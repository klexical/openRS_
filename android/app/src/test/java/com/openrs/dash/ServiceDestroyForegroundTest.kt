package com.openrs.dash

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.AppVisibility
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.ForegroundController
import com.openrs.dash.service.LinkStopReason
import com.openrs.dash.service.WeatherRepository
import com.openrs.dash.ui.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
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
 * #294: a service destroyed while a drive records. The link end defers its demote until the drive's points are stored
 * (#262), `onDestroy` cancels the scope that demote waits in, and a collector body that has already passed its
 * cancellation check (it waits for `CanDataService.link`'s lock, which `onDestroy` → `stopConnection` holds) runs after
 * `onDestroy` returns. Before the fix the controller still read foreground there, so that body changed the foreground's
 * types (`startForeground`) on the destroyed service.
 *
 * Production path: a real CanDataService (Robolectric, real app class) and its own scope and collectors, with a counting
 * ForegroundController host promoted as a running connection has it, and a real DriveRecorder on in-memory Room whose point
 * inserts are held, so the deferred demote is still waiting when the service is destroyed. The order is made certain, not
 * raced: the test takes the link lock, wakes a collector and waits until it is blocked on that lock, then calls `onDestroy`
 * on the same thread (the lock is reentrant), and lets go. The body then runs after `onDestroy` has returned, as it can in
 * production when the collector's thread loses the lock to `onDestroy`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ServiceDestroyForegroundTest {
    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** Point inserts are held while [hold] is set, until [release]. */
    private class GatedDao(real: DriveDao) {
        @Volatile var hold = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dao: DriveDao = Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            if (m.name == "insertPoints" && hold) { entered.countDown(); release.await(20, TimeUnit.SECONDS) }
            try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as DriveDao
    }

    /** Every foreground call, in order ("enter", "enter+location", "exit", "stopSelf"). */
    private class CountingHost : ForegroundController.Host {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun startSelf() = Unit
        override fun enterForeground(location: Boolean) { calls += if (location) "enter+location" else "enter" }
        override fun exitForeground() { calls += "exit" }
        override fun stopSelf() { calls += "stopSelf" }
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
        AppVisibility.visible.value = false
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        gated = GatedDao(db.driveDao())
        rec = DriveRecorder(app, appVs, WeatherRepository(""), db, gated.dao, { _, _ -> null }, gps, { })
        CanDataService.recorderProvider = { rec }                      // #316 seam 5
    }

    @After fun tearDown() {
        gated.release.countDown()
        TestServices.destroyAll()
        rec.cancel(); db.close()
        AppVisibility.visible.value = false
    }

    private fun autoRecord(on: Boolean) {
        app.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit().putBoolean(AppSettings.KEY_AUTO_RECORD_DRIVES, on).commit()
        com.openrs.dash.ui.UserPrefsStore.load(app)                      // #316: a change reaches the recorder through the store
    }

    private fun createPromoted() {
        service = TestServices.create()
        fg = ForegroundController(host)
        CanDataService::class.java.getDeclaredField("foreground").apply { isAccessible = true }.set(service, fg)
        CanDataService.link.transition { fg.promote() }                  // as a running connection has it
        assertTrue(fg.isForeground)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(2) }
        return cond()
    }

    private fun recordPoints(n: Int) {
        appVs.value = VehicleState(isConnected = true, speedKph = 50.0)
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 })
        for (i in 0 until n) {
            val before = rec.driveState.value.totalPointCount
            gps.current!!.trySend(Location("t").apply { latitude = 51.5 + i * 1e-4; longitude = -0.1; time = System.currentTimeMillis() })
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
    }

    private fun scopeJob(): Job =
        (CanDataService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as CoroutineScope).coroutineContext.job

    /** A scope worker blocked on a monitor inside a CanDataService frame: a collector body waiting for the link lock. */
    private fun collectorBlockedOnLink() = Thread.getAllStackTraces().any { (t, st) ->
        t.name.startsWith("DefaultDispatcher-worker") && t.state == Thread.State.BLOCKED &&
            st.any { it.className.startsWith(CanDataService::class.java.name) }
    }

    /** The collectors' first emissions have run: no scope worker is inside a CanDataService frame. */
    private fun collectorsIdle() = Thread.getAllStackTraces().none { (t, st) ->
        t.name.startsWith("DefaultDispatcher-worker") && st.any { it.className.startsWith(CanDataService::class.java.name) }
    }

    /**
     * Holding the link lock: [wake] makes a collector run, the test waits until that body is blocked on the lock, then
     * destroys the service. Returns the foreground calls made after `onDestroy` returned, once the body has run.
     */
    private fun destroyWithLateBody(wake: () -> Unit): List<String> {
        val job = scopeJob()
        var atDestroyEnd = -1
        CanDataService.link.transition {
            wake()
            assertTrue("a collector body waits for the link lock", waitUntil { collectorBlockedOnLink() })
            service.onDestroy()
            atDestroyEnd = host.calls.size
        }
        // The late body runs now; the scope is cancelled, so the job completes once it has
        assertTrue("every coroutine of the destroyed service has finished", waitUntil { job.isCompleted })
        assertFalse("the late body ran (no collector still blocked)", collectorBlockedOnLink())
        return host.calls.toList().drop(atDestroyEnd)
    }

    @Test fun `a visibility change landing after onDestroy - nothing enters the foreground or changes its types`() {
        autoRecord(false)
        createPromoted()
        recordPoints(12)                                                  // below the batch size: all 12 in the buffer
        assertTrue(waitUntil { collectorsIdle() })
        assertTrue("location waits for the app to be visible", host.calls.none { it == "enter+location" })
        gated.hold = true                                                 // the link-end flush is held: the demote is deferred
        val late = destroyWithLateBody { AppVisibility.visible.value = true }
        assertTrue("the drive's flush was under way at the destroy", gated.entered.await(5, TimeUnit.SECONDS))
        assertEquals("no foreground call after onDestroy", emptyList<String>(), late)
        assertFalse("the bookkeeping ends as not foreground", fg.isForeground)
        assertFalse(fg.hasLocation)
    }

    @Test fun `a drive paused by the link loss, its event landing after onDestroy - the location type is not dropped on a dead service`() {
        autoRecord(true)
        AppVisibility.visible.value = true
        createPromoted()
        recordPoints(12)
        assertTrue("recording with the app in front gives the location type", waitUntil { fg.hasLocation })
        assertTrue(waitUntil { collectorsIdle() })
        gated.hold = true
        // The link ends under the held lock: auto-record pauses the drive, which wakes the drive-state collector
        val late = destroyWithLateBody { service.stopConnection(LinkStopReason.WIFI_LOST) }
        assertTrue(rec.driveState.value.isPaused)
        assertTrue("the pause flush was under way at the destroy", gated.entered.await(5, TimeUnit.SECONDS))
        assertEquals("no foreground call after onDestroy", emptyList<String>(), late)
        assertFalse("the bookkeeping ends as not foreground", fg.isForeground)
        assertFalse(fg.hasLocation)
    }
}
