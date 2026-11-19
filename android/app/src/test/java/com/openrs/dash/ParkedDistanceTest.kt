package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * #335 — a drive recorded with the car parked counted GPS noise as distance: fixes wandering a few metres while the car's
 * own speed (CAN 0x130) read 0 were joined into 56.6 m and a summary of 0.06 km (one run, indoors). A step between two
 * fixes counts only while the car's speed at the later fix is above zero; every fix is still stored. The real
 * DriveRecorder on an in-memory Room database, fixes through its own loop from a fake location source; and the drive
 * closed at the next launch (#262), whose distance comes from its stored points by the same rule.
 */
@RunWith(RobolectricTestRunner::class)
class ParkedDistanceTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState())
    private val gps = FakeGps()
    private val recorders = mutableListOf<DriveRecorder>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        recorders.forEach { it.cancel() }
        db.close()
    }

    private val dao: DriveDao get() = db.driveDao()
    private fun recorder() = DriveRecorder(app, vs, WeatherRepository(""), db, dao, { _, _ -> null }, gps, { })
        .also { recorders += it }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    /** Parked GPS noise: a few metres around one spot (1e-5° ≈ 1.1 m), some steps exactly zero, the largest about 8 m. */
    private val wander = listOf(0.0 to 0.0, 0.0 to 0.0, 2e-5 to -1e-5, -3e-5 to 4e-5, 0.0 to 0.0, 5e-5 to 2e-5,
        -2e-5 to -6e-5, 1e-5 to 0.0, 1e-5 to 0.0, -4e-5 to 3e-5, 2e-5 to 2e-5, 0.0 to -1e-5)

    private fun fix(dLat: Double, dLng: Double) = Location("test").apply {
        latitude = 51.5 + dLat; longitude = -0.1 + dLng; bearing = 0f; speed = 0f; time = System.currentTimeMillis()
    }

    /**
     * Starts a drive and sends [fixes] through the loop with the car's speed [speedKph], its 0x130 stamp [speedAgeMs] old
     * at each fix (as CanDecoder stamps it; null = no 0x130 received on this connection); returns the drive id.
     */
    private fun record(rec: DriveRecorder, fixes: List<Location>, speedAgeMs: Long? = 0L, speedKph: (Int) -> Double): Long {
        fun state(i: Int) = VehicleState(isConnected = true, rpm = 850.0, speedKph = speedKph(i),
            fieldLastUpdateMs = speedAgeMs?.let { mapOf(CanDecoder.SPEED_TAG to System.currentTimeMillis() - it) } ?: emptyMap())
        vs.value = state(0)
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 }) { "no start" }
        fixes.forEachIndexed { i, f ->
            val before = rec.driveState.value.totalPointCount
            vs.value = state(i)
            gps.current!!.trySend(f)
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
            Thread.sleep(2)
        }
        return rec.driveState.value.driveId
    }

    private fun join(job: Job) = runBlocking { withTimeout(15_000) { job.join() } }

    @Test fun `parked - fixes wandering a few metres at speed 0 add no distance, and every fix is stored`() {
        val rec = recorder()
        val id = record(rec, wander.map { (a, b) -> fix(a, b) }) { 0.0 }
        assertEquals("live distance", 0.0, rec.driveState.value.cumulativeDistanceKm, 0.0)
        join(rec.stopDrive())
        assertEquals("the drive's summary distance", 0.0, dao.getDrive(id)!!.distanceKm, 0.0)
        assertEquals("every fix is stored", wander.size, dao.getPoints(id).size)
    }

    @Test fun `control - moving, the steps count as before`() {
        val rec = recorder()
        val id = record(rec, (0 until 12).map { fix(it * 1e-4, 0.0) }) { 60.0 }   // 11 steps of ~11.1 m
        join(rec.stopDrive())
        val km = dao.getDrive(id)!!.distanceKm
        assertTrue("got $km", km in 0.11..0.13)
    }

    @Test fun `a drive that stops - the steps while moving count, the parked noise after does not`() {
        val rec = recorder()
        val moving = (0 until 6).map { fix(it * 1e-4, 0.0) }                       // 5 steps of ~11.1 m
        val parked = wander.map { (a, b) -> fix(5e-4 + a, b) }
        val id = record(rec, moving + parked) { i -> if (i < moving.size) 40.0 else 0.0 }
        join(rec.stopDrive())
        val km = dao.getDrive(id)!!.distanceKm
        assertTrue("got $km", km in 0.054..0.058)
    }

    // ── The car's speed not known: the GPS step counts, as before #335 ────────────────────────────────────────────────

    @Test fun `moving with no 0x130 received on this connection - the GPS steps still count`() {
        val rec = recorder()
        val id = record(rec, (0 until 12).map { fix(it * 1e-4, 0.0) }, speedAgeMs = null) { 0.0 }
        join(rec.stopDrive())
        val km = dao.getDrive(id)!!.distanceKm
        assertTrue("got $km", km in 0.11..0.13)
    }

    @Test fun `moving with a speed of 0 older than the link limit (a held reading) - the GPS steps still count`() {
        val rec = recorder()
        val id = record(rec, (0 until 12).map { fix(it * 1e-4, 0.0) }, speedAgeMs = 5_000L) { 0.0 }
        join(rec.stopDrive())
        val km = dao.getDrive(id)!!.distanceKm
        assertTrue("got $km", km in 0.11..0.13)
    }

    // ── A drive closed at the next launch (#262): a stored point's 0 can't say "not received" ──────────────────────────

    private fun orphan(speeds: List<Double>, lat: (Int) -> Double, lng: (Int) -> Double): Long {
        val id = dao.insertDrive(DriveEntity(startTime = 5_000L))
        speeds.forEachIndexed { i, v ->
            dao.insertPoints(listOf(DrivePointEntity(driveId = id, timestamp = 6_000L + i * 1_000L,
                lat = lat(i), lng = lng(i), speedKph = v)))
        }
        recorder()
        check(waitUntil { (dao.getDrive(id)?.endTime ?: 0L) != 0L }) { "the orphan was never closed" }
        return id
    }

    @Test fun `closed at launch - a drive that moved and then parked drops its parked steps`() {
        val moving = 6
        val id = orphan(List(moving) { 40.0 } + List(wander.size) { 0.0 },
            { i -> if (i < moving) 51.5 + i * 1e-4 else 51.5 + 5e-4 + wander[i - moving].first },
            { i -> if (i < moving) -0.1 else -0.1 + wander[i - moving].second })
        val km = dao.getDrive(id)!!.distanceKm
        assertTrue("got $km", km in 0.054..0.058)
    }

    @Test fun `closed at launch - a drive with every point at 0 (no 0x130) keeps its GPS distance`() {
        val id = orphan(List(12) { 0.0 }, { i -> 51.5 + i * 1e-4 }, { -0.1 })
        val km = dao.getDrive(id)!!.distanceKm
        assertTrue("got $km", km in 0.11..0.13)
    }
}
