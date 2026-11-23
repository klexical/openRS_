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
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.FuelSpan
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * #517 — the average economy divided the fuel used since the fuel start by the distance since the connection (or the
 * drive) began. The fuel start moves off the 100 % clamp, or waits for the first reading, long after the distance began:
 * one full-tank drive stored 8.24 L/100 km against 12.66 over the span measured. The average now divides by the distance
 * since the fuel start. Separately, a drop faster than [com.openrs.dash.data.FuelUsed.MAX_STANDSTILL_L_PER_HR] (after one
 * gauge step) while the car stands still is taken for the level settling, not fuel (a cold start fell 7 steps in 48 s, standing
 * still), and is not counted. DRIVE's session (FuelEconomy, which the drive stores as its average) and the drive's own
 * figures through the real recorder (in-memory Room, a fake location source).
 */
@RunWith(RobolectricTestRunner::class)
class FuelSpanTest {

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
        FuelEconomy.reset()
    }

    @After fun tearDown() {
        recorders.forEach { it.cancel() }
        db.close()
        FuelEconomy.reset()
    }

    private val dao: DriveDao get() = db.driveDao()

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private val hour = 3_600_000L

    /**
     * DRIVE's session: each (hours, level) read at a steady [kph] (distance = kph × hours). Each reading is sent twice,
     * 1 ms apart, as the car's updates arrive many times a second: FuelEconomy publishes once its 60 s window holds two.
     */
    private fun session(kph: Double, vararg readings: Pair<Double, Double>) =
        readings.forEach { (h, pct) ->
            val t = t0 + (h * hour).toLong()
            FuelEconomy.onUpdate(pct, kph, t)
            FuelEconomy.onUpdate(pct, kph, t + 1)
        }

    private val t0 = 1_700_000_000_000L
    /** The 1 ms repeats add up to 0.1 m at 100 km/h. */
    private val tol = 1e-5

    // ── DRIVE's session average (and the drive's stored average, which is this figure) ────────────────────────────────

    @Test fun `a start on the 100 % clamp - the average is over the distance since the fuel start`() {
        // 100 km on the clamp, then 99.6 → 89.6 % (10 % of 49.8 L = 4.98 L) over the next 100 km. Before: 4.98 / 200 km
        session(100.0, 0.0 to 100.0, 1.0 to 99.6, 2.0 to 89.6)
        val s = FuelEconomy.state.value
        assertEquals("fuel used", 4.98, s.fuelUsedL, 1e-9)
        assertEquals("average", 4.98, s.avgL100km, tol)
        assertEquals("mpg", 282.48 / 4.98, s.avgMpg, 1e-3)
    }

    @Test fun `no fuel reading for the first stretch - the average is over the distance since the first reading`() {
        // 0 % = not received for the first 100 km, then 80 → 70 % over 100 km. Before: 4.98 / 200 km
        session(100.0, 0.0 to 0.0, 1.0 to 80.0, 2.0 to 70.0)
        assertEquals(4.98, FuelEconomy.state.value.avgL100km, tol)
    }

    @Test fun `control - a measurable start gives the same average as before`() {
        session(100.0, 0.0 to 80.0, 1.0 to 70.0)
        assertEquals(4.98, FuelEconomy.state.value.avgL100km, tol)
    }

    @Test fun `the drive stores the average over the distance since the fuel start`() {
        session(100.0, 0.0 to 100.0, 1.0 to 99.6, 2.0 to 89.6)       // what STOP reads, as at the car
        val d = storedDrive(levels = listOf(80.0, 79.6))
        assertEquals("average", 4.98, d.avgFuelL100km, tol)
        assertEquals("mpg", 282.48 / 4.98, d.avgFuelMpg, 1e-3)
    }

    // ── A drop at a standstill faster than the car can burn ───────────────────────────────────────────────────────────

    @Test fun `DRIVE - seven steps in 48 s standing still are not counted as fuel`() {
        // 80 → 77.2 % (2.8 %, 1.39 L) at speed 0; one step plus 4 L/h × 48 s is allowed, under the four steps of a figure
        session(0.0, *(0..7).map { it * 48.0 / 7 / 3600 to 80.0 - it * 0.4 }.toTypedArray())
        assertEquals(0.0, FuelEconomy.state.value.fuelUsedL, 0.0)
    }

    @Test fun `control - DRIVE - an idle burning 1 L per hour counts in full`() {
        // One step (0.2 L) every 12 minutes for an hour: 2.0 % = 0.996 L
        session(0.0, *(0..5).map { it * 0.2 to 80.0 - it * 0.4 }.toTypedArray())
        assertEquals(0.996, FuelEconomy.state.value.fuelUsedL, 1e-9)
    }

    @Test fun `control - DRIVE - a fast drop while moving counts in full`() {
        session(100.0, 0.0 to 80.0, 1.0 / 3600 to 70.0)
        assertEquals(4.98, FuelEconomy.state.value.fuelUsedL, 1e-9)
    }

    @Test fun `DRIVE - a dip at a standstill that comes back up is not subtracted for good`() {
        // Standing: 80 → 78.8 % for 2 s → 80 %; then driving to 78 %: 2.0 % of 49.8 L. At 3a92de5 the dip moved the
        // fuel start down to 79.2 % for good, and 1.2 % gave no figure
        listOf(0L to 80.0, 2_000L to 78.8, 4_000L to 80.0).forEach { (ms, pct) ->
            FuelEconomy.onUpdate(pct, 0.0, t0 + ms); FuelEconomy.onUpdate(pct, 0.0, t0 + ms + 1)
        }
        FuelEconomy.onUpdate(78.0, 60.0, t0 + 600_000); FuelEconomy.onUpdate(78.0, 60.0, t0 + 600_001)
        assertEquals(0.996, FuelEconomy.state.value.fuelUsedL, 1e-9)
    }

    /** [FuelSpan] from a measurable [startPct] after each (ms, level, standing) reading, the odometer held at 0. */
    private fun span(startPct: Double, vararg readings: Triple<Long, Double, Boolean>): FuelSpan =
        readings.fold(FuelSpan.start(startPct)) { s, (ms, pct, standing) -> s.next(pct, 0.0, t0 + ms, standing, DriveState.TANK_L) }

    @Test fun `the drive's span - a standstill dip that comes back up once moving is restored`() {
        val s = span(80.0, Triple(0L, 80.0, true), Triple(2_000L, 78.8, true), Triple(4_000L, 80.0, false),
            Triple(600_000L, 78.0, false))
        assertEquals(0.996, s.litres(78.0, DriveState.TANK_L)!!, 1e-9)
    }

    @Test fun `control - the drive's span - a drop settled at a standstill stays uncounted through a long idle`() {
        // Seven steps in 48 s, then 30 minutes at the settled level: the allowance grows, the settled drop is still not fuel
        val settle = (0..7).map { Triple(it * 48_000L / 7, 80.0 - it * 0.4, true) }
        val s = span(80.0, *(settle + Triple(30 * 60_000L, 77.2, true)).toTypedArray())
        assertEquals(null, s.litres(77.2, DriveState.TANK_L))
        // …and an idle at 1 L/h after it counts in full: five steps, one each 12 minutes, on top of the settling's allowance
        // (one step plus 4 L/h over its 48 s)
        val idle = (1..5).map { Triple(30 * 60_000L + it * 12 * 60_000L, 77.2 - it * 0.4, true) }
        val after = idle.fold(s) { a, (ms, pct, st) -> a.next(pct, 0.0, t0 + ms, st, DriveState.TANK_L) }
        val settlingAllowedPct = 0.4 + 4.0 * 48.0 / 3600 / DriveState.TANK_L * 100
        assertEquals((2.0 + settlingAllowedPct) / 100 * DriveState.TANK_L, after.litres(75.2, DriveState.TANK_L)!!, 1e-9)
    }

    // ── The drive's own figures through the recorder ──────────────────────────────────────────────────────────────────

    /**
     * Starts a drive with the level [levels][0] and sends one fix per level, the car moving north 0.01° (about 1.1 km) per
     * fix, or standing at one spot when [parked] (speed 0, its 0x130 stamp fresh); returns the recorder, still recording.
     */
    private fun record(levels: List<Double>, parked: Boolean = false): DriveRecorder {
        val rec = DriveRecorder(app, vs, WeatherRepository(""), db, dao, { _, _ -> null }, gps, { }).also { recorders += it }
        fun state(pct: Double) = VehicleState(isConnected = true, rpm = 850.0, fuelLevelPct = pct,
            speedKph = if (parked) 0.0 else 60.0,
            fieldLastUpdateMs = mapOf(CanDecoder.SPEED_TAG to System.currentTimeMillis()))
        vs.value = state(levels[0])
        val openedBefore = gps.opened.get()
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > openedBefore }) { "no start" }
        levels.forEachIndexed { i, pct ->
            val before = rec.driveState.value.totalPointCount
            vs.value = state(pct)
            val lat = 51.5 + if (parked) 0.0 else i * 0.01
            gps.current!!.trySend(Location("test").apply { latitude = lat; longitude = -0.1; time = System.currentTimeMillis() })
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
        return rec
    }

    private fun storedDrive(levels: List<Double>, parked: Boolean = false): DriveEntity {
        val rec = record(levels, parked)
        val id = rec.driveState.value.driveId
        runBlocking { withTimeout(15_000) { rec.stopDrive().join() } }
        return dao.getDrive(id)!!
    }

    @Test fun `TRIP - a drive started on the clamp - the live average is over the distance since the fuel start`() {
        // Fixes 0-4 on the clamp (4 steps of driving), fix 5 at 99.6 % is the fuel start, then 99.2 → 97.6 % over 5 steps
        val levels = listOf(100.0, 100.0, 100.0, 100.0, 100.0, 99.6, 99.2, 98.8, 98.4, 98.0, 97.6)
        val s = record(levels).driveState.value
        val sinceStartKm = DriveState.haversineKm(51.55, -0.1, 51.6, -0.1)
        assertEquals("fuel used", 0.996, s.fuelUsedL, 1e-9)
        assertEquals("average", 0.996 / sinceStartKm * 100.0, s.avgFuelL100km, 1e-6)
    }

    @Test fun `control - TRIP - a measurable start gives the same live average as before`() {
        val s = record(listOf(80.0, 79.6, 79.2, 78.8, 78.4, 78.0)).driveState.value
        val km = DriveState.haversineKm(51.5, -0.1, 51.55, -0.1)
        assertEquals(0.996 / km * 100.0, s.avgFuelL100km, 1e-6)
    }

    @Test fun `the drive - seven steps standing still within seconds are not counted as fuel`() {
        assertEquals(0.0, storedDrive((0..7).map { 80.0 - it * 0.4 }, parked = true).fuelUsedL, 0.0)
    }

    @Test fun `control - the drive - the same drop while moving counts in full`() {
        assertEquals(1.3944, storedDrive((0..7).map { 80.0 - it * 0.4 }).fuelUsedL, 1e-9)    // 2.8 % of 49.8 L
    }
}
