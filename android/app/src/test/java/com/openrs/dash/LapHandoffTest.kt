package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.LapTimer
import com.openrs.dash.data.LapTimerState
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos

/**
 * STOP → START with a fix of drive A still in flight (#237, reviewer N1), through the real recording loop. A's fix is
 * held inside the lap feed itself — after the loop's `isActive` check, while the timer reads its bearing — so A is
 * stopped (its laps taken, the timer re-armed) and B starts and drives up to the line before A's fix reaches the timer.
 * That fix lies just past the line: it must neither start nor advance B's timer; B's own crossing must start it.
 */
@RunWith(RobolectricTestRunner::class)
class LapHandoffTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = java.util.concurrent.atomic.AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** A fix whose bearing read blocks once until [release] — the lap feed is the only reader of the bearing. */
    private class HeldFix : Location("test") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun getBearing(): Float {
            entered.countDown(); release.await(10, TimeUnit.SECONDS)
            return super.getBearing()
        }
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 5000.0, speedKph = 72.0))
    private val gps = FakeGps()
    private val lapTimer = LapTimer()
    private lateinit var rec: DriveRecorder

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        rec = DriveRecorder(app, vs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { })
        rec.lapTimer = lapTimer
    }

    @After fun tearDown() { rec.cancel(); db.close() }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private var clock = 1_000_000L
    /** A fresh eastbound fix [x] m east of the S/F point, fix time = now on a synthetic clock (+1 s per fix). */
    private fun <L : Location> at(loc: L, x: Double): L = loc.apply {
        latitude = LapReplay.SF_LAT; longitude = LapReplay.SF_LNG + x / (111_320.0 * cos(Math.toRadians(LapReplay.SF_LAT)))
        bearing = 90f; speed = 20f; clock += 1_000; time = clock; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun send(x: Double) {
        val before = rec.driveState.value.totalPointCount
        gps.current!!.trySend(at(Location("test"), x))
        check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "fix at $x not recorded" }
    }

    private fun start(previousId: Long = 0): Long {
        val opened = gps.opened.get()
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 && it.driveId != previousId } && gps.opened.get() > opened })
        return rec.driveState.value.driveId
    }

    @Test
    fun `a fix of drive A still in flight at STOP neither starts nor advances drive B's timer`() {
        val failed = mutableListOf<String>()
        fun check(name: String, ok: Boolean, detail: Any? = null) {
            println("CHECK ${if (ok) "pass" else "FAIL"} [handoff] $name${if (ok || detail == null) "" else " — got $detail"}")
            if (!ok) failed += name
        }
        lapTimer.setStartFinish(LapReplay.SF_LAT, LapReplay.SF_LNG, 90f)
        val a = start()
        listOf(-100.0, -80.0, -60.0).forEach(::send)                              // A approaches from the west, away from the line
        val held = at(HeldFix(), 10.0)                                            // A's fix just past the line, held in the feed
        gps.current!!.trySend(held)
        check("A's fix held inside the lap feed", held.entered.await(5, TimeUnit.SECONDS))

        val finalizeA = rec.stopDrive()
        val b = start(previousId = a)
        listOf(-100.0, -50.0, -20.0).forEach(::send)                              // B: away from the line, then 20 m before it
        check("B armed before A's fix lands", lapTimer.state == LapTimerState.ARMED, lapTimer.state)
        held.release.countDown()                                                  // A's fix reaches the timer now
        runBlocking { withTimeout(15_000) { finalizeA.join() } }
        Thread.sleep(100)
        check("A's late fix did not start B's timer", lapTimer.state == LapTimerState.ARMED, lapTimer.state)
        check("B's timer has no lap from A", lapTimer.laps.isEmpty(), lapTimer.laps)

        send(10.0)                                                                // B's own crossing
        check("B's own crossing starts its timer", lapTimer.state == LapTimerState.TIMING, lapTimer.state)
        check("B still recording", rec.driveState.value.let { it.isRecording && it.driveId == b })
        assertTrue("[handoff] failed: $failed", failed.isEmpty())
    }
}
