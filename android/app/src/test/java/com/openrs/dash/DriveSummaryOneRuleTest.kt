package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * #321 — a drive's summary is built one way, whichever way the drive ends or is exported. STOP scored aggression from the
 * points kept in memory (the last 3,600), a drive closed at the next launch (#262) from all its stored points, and an
 * export made during the drive carried no score, mode breakdown, start / peak temperatures, fuel economy, trims, pulls or
 * thermal record. The owner's rule (2026-11-11): the score covers the whole drive, on every path. The real DriveRecorder on
 * an in-memory Room database, fixes through its own loop from a fake location source.
 */
@RunWith(RobolectricTestRunner::class)
class DriveSummaryOneRuleTest {

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

    private fun waitUntil(timeoutMs: Long = 60_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private var fixN = 0
    private fun fix() = Location("test").apply {
        val i = fixN++
        latitude = 51.5 + (i % 500) * 1e-5; longitude = -0.1; bearing = 0f; speed = 10f; time = System.currentTimeMillis()
    }

    /** Full throttle, 1 g lateral, 6,800 rpm (each term of the score at its full scale); no boost reading. */
    private fun aggressive() = VehicleState(isConnected = true, speedKph = 50.0, rpm = 6_800.0, throttlePct = 100.0,
        throttleHasSource = true, lateralG = 1.0)
    /** Every term of the score at 0. */
    private fun calm() = VehicleState(isConnected = true, speedKph = 50.0, rpm = 0.0, throttlePct = 0.0,
        throttleHasSource = true, lateralG = 0.0)
    /** Warm engine readings with fresh stamps, so the thermal record and the start / peak temperatures are filled. */
    private fun warm(oil: Double): VehicleState {
        val now = System.currentTimeMillis()
        return VehicleState(isConnected = true, speedKph = 60.0, rpm = 3_000.0, throttlePct = 40.0, throttleHasSource = true,
            lateralG = 0.3, oilTempC = oil, coolantTempC = 88.0,
            driveMode = com.openrs.dash.data.DriveMode.NORMAL,    // the car reports Normal (a new state's mode is unknown, #516)
            fieldLastUpdateMs = mapOf("oilTempC" to now, "coolantTempC" to now))
    }

    private fun start(rec: DriveRecorder): Long {
        rec.startDrive()
        check(waitUntil(5_000) { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 }) { "no start" }
        return rec.driveState.value.driveId
    }

    /** [n] fixes in one send, all recorded with [state]; waits once for the count. */
    private fun bulk(rec: DriveRecorder, n: Int, state: VehicleState) {
        val target = rec.driveState.value.totalPointCount + n
        vs.value = state
        repeat(n) { gps.current!!.trySend(fix()) }
        check(waitUntil { rec.driveState.value.totalPointCount == target }) { "only ${rec.driveState.value.totalPointCount} of $target" }
    }

    /** One at a time, each with its own readings. */
    private fun each(rec: DriveRecorder, n: Int, mk: (Int) -> VehicleState) = repeat(n) { i ->
        val before = rec.driveState.value.totalPointCount
        vs.value = mk(i)
        gps.current!!.trySend(fix())
        check(waitUntil(5_000) { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
    }

    private fun join(job: Job) = runBlocking { withTimeout(30_000) { job.join() } }

    @Test fun `a drive longer than 3,600 points gets the whole drive's score at STOP, in an export during it, and when closed at launch`() {
        val rec = recorder()
        val id = start(rec)
        bulk(rec, 700, aggressive())
        bulk(rec, 3_300, calm())
        // Whole drive: 700 of 4,000 points at full scale on three terms → 0.175 of the full score → 17.
        // (The last 3,600 alone hold 300 of them → 8, which STOP used to store.)
        val exported = runBlocking { rec.snapshotActiveDrive() }!!.first
        join(rec.stopDrive())
        val stopped = dao.getDrive(id)!!

        // The same stored points, left open as by a process death, closed by the next launch (#262)
        val openId = dao.insertDrive(DriveEntity(startTime = stopped.startTime - 1))
        dao.insertPoints(dao.getPoints(id).map { it.copy(id = 0, driveId = openId) })
        recorder()
        check(waitUntil(10_000) { (dao.getDrive(openId)?.endTime ?: 0L) != 0L }) { "the open drive was never closed" }
        val closed = dao.getDrive(openId)!!

        assertEquals("export during the drive / STOP / closed at launch",
            listOf(17, 17, 17), listOf(exported.aggressionScore, stopped.aggressionScore, closed.aggressionScore))
    }

    @Test fun `an export during a drive carries the summary STOP writes at that moment - end time and name stay unset`() {
        val rec = recorder()
        vs.value = warm(90.0)                                  // the start temperatures are taken at START
        val id = start(rec)
        each(rec, 40) { i -> warm(90.0 + i * 0.5) }
        val exported = runBlocking { rec.snapshotActiveDrive() }!!.first
        join(rec.stopDrive())                                  // nothing recorded in between: the same moment
        val stopped = dao.getDrive(id)!!

        assertEquals("end time unset", 0L, exported.endTime)
        assertNull("name unset", exported.name)
        assertNotNull("the thermal record is in the export", exported.thermalPeaksJson)
        assertEquals("mode breakdown", 1.0, org.json.JSONObject(exported.driveModeBreakdown).getDouble("Normal"), 0.0)
        assertEquals("start / peak oil", 90.0 to 109.5, exported.startOilTempC to exported.peakOilTempC)
        assertEquals("pull count (no WOT pull in this drive)", 0, exported.pullCount)
        // The text summary's note names only what an open drive really lacks (owner, Q160)
        val text = com.openrs.dash.diagnostics.DriveExportBuilder.buildSummary(exported, dao.getPoints(id))
        assertEquals(listOf("  Status      : IN PROGRESS — end time, name and laps are written at drive end"),
            text.lines().filter { it.contains("IN PROGRESS") })
        fun summary(d: DriveEntity) = listOf(d.distanceKm, d.avgSpeedKph, d.maxSpeedKph, d.peakRpm, d.peakLateralG,
            d.driveModeBreakdown, d.aggressionScore, d.startOilTempC, d.peakOilTempC, d.endOilTempC, d.startCoolantTempC,
            d.peakCoolantTempC, d.endCoolantTempC, d.avgFuelL100km, d.avgFuelMpg, d.thermalPeaksJson, d.avgStftPct, d.avgLtftPct,
            d.trimSamples, d.pullCount, d.knockPullCount, d.fuelUsedL)
        assertEquals(summary(stopped), summary(exported))
    }
}
