package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DriveExportBuilder
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
 * #336 — a drive's "Fuel Used" read 0.80 L after five minutes of idling: the fuel level (0x380) moves in 0.4 % steps and
 * clamps at 100 %, and the run went 100 → 99.6 → 99.2 → 98.8 → 98.4 %, gauge noise, not fuel. A figure is given only after
 * a drop of more than three steps (owner, Q155 / Q156 and the follow-up), measured from the first reading below the
 * clamp when the drive starts on it; no figure is stored as 0.0 and the export's text prints "—". The drive's figure
 * through the real recorder (in-memory Room, a fake location source); DRIVE's session cell through FuelEconomy.
 */
@RunWith(RobolectricTestRunner::class)
class FuelUsedNoiseTest {

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

    /** A drive whose fuel level reads [levels] at START and at each recorded point (one point per level after the first). */
    private fun storedFuelUsed(vararg levels: Double): Double = storedDrive(*levels).fuelUsedL

    /** The same drive's stored row. */
    private fun storedDrive(vararg levels: Double): DriveEntity {
        val rec = DriveRecorder(app, vs, WeatherRepository(""), db, dao, { _, _ -> null }, gps, { }).also { recorders += it }
        vs.value = VehicleState(isConnected = true, rpm = 850.0, fuelLevelPct = levels[0])
        // This drive's own subscription (#462): a test that records two drives shares one fake source, and "opened at
        // least once" is already true from the first, so a fix could go to the first drive's channel, which nobody reads
        val openedBefore = gps.opened.get()
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > openedBefore }) { "no start" }
        levels.forEachIndexed { i, pct ->
            val before = rec.driveState.value.totalPointCount
            vs.value = VehicleState(isConnected = true, rpm = 850.0, fuelLevelPct = pct)
            gps.current!!.trySend(Location("test").apply { latitude = 51.5; longitude = -0.1; time = System.currentTimeMillis() })
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
        val id = rec.driveState.value.driveId
        runBlocking { withTimeout(15_000) { rec.stopDrive().join() } }
        return dao.getDrive(id)!!
    }

    /** DRIVE's session economy over about 1 km (36,000 km/h for 0.1 s, integrated from the speed) with the level falling [fromPct] → [toPct]. */
    private fun sessionOverOneKm(fromPct: Double, toPct: Double) {
        FuelEconomy.onUpdate(fromPct, 36_000.0)
        Thread.sleep(100)
        FuelEconomy.onUpdate(toPct, 36_000.0)
    }

    @Test fun `the issue's run - a start on the clamp, four steps down while idling - gives no figure`() {
        assertEquals(0.0, storedFuelUsed(100.0, 99.6, 99.2, 98.8, 98.4), 0.0)
    }

    @Test fun `a drop of one or two steps from an unclamped start gives no figure`() {
        assertEquals(0.0, storedFuelUsed(80.0, 79.6), 0.0)
        assertEquals(0.0, storedFuelUsed(80.0, 79.6, 79.2), 0.0)
    }

    @Test fun `control - a drop well beyond the gauge's steps gives the same figure as before`() {
        assertEquals(4.98, storedFuelUsed(80.0, 75.0, 70.0), 1e-9)                   // 10 % of 49.8 L
    }

    @Test fun `a start on the clamp is measured from the first reading below it`() {
        // 100 (clamp) → 99.6 is the start; 99.6 → 97.6 = 2.0 % = 0.996 L. Before: 100 → 97.6 = 1.195 L
        assertEquals(0.996, storedFuelUsed(100.0, 99.6, 99.2, 98.8, 98.4, 98.0, 97.6), 1e-9)
    }

    @Test fun `exactly four steps from an unclamped start give a figure, three give none (Q161)`() {
        assertEquals(0.7968, storedFuelUsed(80.0, 79.6, 79.2, 78.8, 78.4), 1e-9)       // 1.6 % of 49.8 L
        assertEquals(0.0, storedFuelUsed(80.0, 79.6, 79.2, 78.8), 0.0)
    }

    @Test fun `a start before the level was received (0) is measured from the first reading`() {
        // START at 0 (not received), then 79.6 → 78.0: four steps from the first reading
        assertEquals(0.7968, storedFuelUsed(0.0, 79.6, 79.2, 78.8, 78.4, 78.0), 1e-9)
    }

    @Test fun `TRIP's compare sheet shows a dash and no difference for a drive with no figure`() {
        // Litres to 1 decimal, the fuel unit's decimals (#464)
        val litres = com.openrs.dash.ui.UserPrefs(fuelUnit = "L")
        val none = com.openrs.dash.ui.trip.fuelUsedCompare(0.0, 1.2, litres)
        assertEquals(listOf("—", "1.2 L", ""), listOf(none.valueA, none.valueB, none.delta))
        assertEquals(false, none.comparable)
        val both = com.openrs.dash.ui.trip.fuelUsedCompare(1.0, 1.2, litres)
        assertEquals(listOf("1.0 L", "1.2 L", "+0.2 L"), listOf(both.valueA, both.valueB, both.delta))
    }

    @Test fun `no average economy without a fuel-used figure - 1 km on a two-step drop (owner Q165)`() {
        // Before: 0.8 % of 49.8 L over about 1 km read about 39.8 L/100 km
        sessionOverOneKm(80.0, 79.2)
        assertEquals("DRIVE's AVERAGE", 0.0 to 0.0, FuelEconomy.state.value.avgL100km to FuelEconomy.state.value.avgMpg)
    }

    @Test fun `the drive stores no average economy without a fuel-used figure`() {
        sessionOverOneKm(80.0, 79.2)                                         // what STOP reads, as at the car
        val d = storedDrive(80.0, 79.6, 79.2)
        assertEquals(0.0 to 0.0, d.avgFuelL100km to d.avgFuelMpg)
    }

    @Test fun `control - with a fuel-used figure the average is as before`() {
        sessionOverOneKm(80.0, 78.0)                                         // five steps: 0.996 L over about 1 km
        assertEquals(true, FuelEconomy.state.value.avgL100km > 10.0)
    }

    @Test fun `DRIVE's session figure takes the same rule`() {
        listOf(100.0, 99.6, 99.2, 98.8, 98.4).forEach { FuelEconomy.onUpdate(it, 0.0) }
        assertEquals("the issue's run", 0.0, FuelEconomy.state.value.fuelUsedL, 0.0)
        FuelEconomy.reset()
        listOf(80.0, 75.0, 70.0).forEach { FuelEconomy.onUpdate(it, 0.0) }
        assertEquals("control", 4.98, FuelEconomy.state.value.fuelUsedL, 1e-9)
    }

    @Test fun `the export's text prints a dash for no figure, never 0,00 L`() {
        val text = DriveExportBuilder.buildSummary(DriveEntity(id = 1, startTime = 0L, endTime = 60_000L, fuelUsedL = 0.0), emptyList())
        assertEquals(listOf("  Fuel Used   : —"), text.lines().filter { it.contains("Fuel Used") })
    }
}
