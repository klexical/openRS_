package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.MapComparisonMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * #271, owner decision F1: the "MAP comparison" test mode. While on, the production PCM poller sends MAP 0xF40B with TIP every
 * engine-running cycle; off (the default, and after every app start — memory only) it is back in the slow rotation. A drive
 * records whether the mode was on at any of its points, and its exports say so. See MapSlowDefaultTest for the default cadence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MapComparisonModeTest {
    private val events = mutableListOf<Pair<String, String>>()

    @Before fun fresh() { MapComparisonMode.set(false); DiagnosticLogger.sessionStart("test", 0, prefs = null) }
    @After fun off() { MapComparisonMode.set(false); recorders.forEach { it.cancel() }; if (::db.isInitialized) db.close() }

    // ── cadence through the production poller ──────────────────────────────

    private fun counts(writes: List<String>) = writes.count { "22033E" in it } to writes.count { "22F40B" in it }

    @Test fun `mode on - the poller sends MAP with TIP every cycle, and back to the slow rotation once switched off`() = runTest {
        MapComparisonMode.set(true)
        val run = ReplayRun(this, ReplayFixture.load("at-rest-baseline.txt"), rpm = 3_000.0)
        advanceTimeBy(80_000L)                                            // 20 s start delay + ~25 cycles
        val (tipOn, mapOn) = counts(run.transport.writes)
        assertTrue("the fast group ran ($tipOn)", tipOn >= 15)
        assertTrue("mode on: MAP every cycle ($mapOn MAP vs $tipOn TIP)", mapOn >= tipOn - 1)
        MapComparisonMode.set(false)
        val before = run.transport.writes.size
        advanceTimeBy(60_000L)
        val (tipOff, mapOff) = counts(run.transport.writes.drop(before))
        assertTrue("the fast group kept running ($tipOff)", tipOff >= 15)
        assertTrue("mode off again: MAP only in the slow rotation ($mapOff MAP vs $tipOff TIP)", mapOff * 8 <= tipOff)
    }

    @Test fun `the mode starts off, changes are logged once each, and nothing about it is persisted`() {
        assertFalse(MapComparisonMode.isOn)
        MapComparisonMode.set(true) { t, m -> events += t to m }
        MapComparisonMode.set(true) { t, m -> events += t to m }        // no change → no line
        MapComparisonMode.set(false) { t, m -> events += t to m }
        assertEquals(listOf(MapComparisonMode.EVENT, MapComparisonMode.EVENT), events.map { it.first })
        assertTrue(events[0].second, events[0].second.startsWith("on"))
        assertTrue(events[1].second, events[1].second.startsWith("off"))
        // Memory only: no preference file carries it (a new process starts with the default, off)
        val app: Application = ApplicationProvider.getApplicationContext()
        val prefsDir = java.io.File(app.applicationInfo.dataDir, "shared_prefs")
        val mentions = prefsDir.listFiles().orEmpty().filter { it.readText().contains("map", ignoreCase = true) && it.readText().contains("compar", ignoreCase = true) }
        assertTrue("no saved MAP comparison preference: $mentions", mentions.isEmpty())
    }

    // ── per-drive record and exports ────────────────────────────────────────

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var db: DriveDatabase
    private val recorders = mutableListOf<DriveRecorder>()

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    /** One drive of three points; the mode is on during point [modeOnAt] (or never). Returns the saved row. */
    private fun drive(modeOnAt: Int?): DriveEntity {
        val app: Application = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!::db.isInitialized) db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        val gps = FakeGps()
        val rec = DriveRecorder(app, MutableStateFlow(VehicleState(isConnected = true)), WeatherRepository(""), db, db.driveDao(),
            { _, _ -> null }, gps, { }).also { recorders += it }
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 })
        for (i in 0 until 3) {
            MapComparisonMode.set(i == modeOnAt)
            val before = rec.driveState.value.totalPointCount
            gps.current!!.trySend(Location("t").apply { latitude = 51.5 + i * 1e-4; longitude = -0.1; time = System.currentTimeMillis() })
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 })
        }
        MapComparisonMode.set(false)
        val id = rec.driveState.value.driveId
        runBlocking { withTimeout(10_000) { rec.stopDrive().join() } }
        return db.driveDao().getDrive(id)!!
    }

    @Test fun `a drive with the mode on at any point is recorded as a MAP comparison drive, and its exports say so`() {
        val d = drive(modeOnAt = 1)
        assertEquals(true, d.mapComparison)
        assertTrue(DriveExportBuilder.buildSummary(d, emptyList()).contains("MAP compare : test mode ON during this drive"))
        assertEquals(true, JSONObject(DriveExportBuilder.buildProfileJson(d, emptyList())).getJSONObject("drive").getBoolean("mapComparisonMode"))
    }

    @Test fun `a drive with the mode off throughout is recorded as off`() {
        val d = drive(modeOnAt = null)
        assertEquals(false, d.mapComparison)
        assertTrue(DriveExportBuilder.buildSummary(d, emptyList()).contains("MAP compare : off"))
        assertEquals(false, JSONObject(DriveExportBuilder.buildProfileJson(d, emptyList())).getJSONObject("drive").getBoolean("mapComparisonMode"))
    }

    @Test fun `a drive without a record claims nothing in its exports`() {
        val d = DriveEntity(id = 1, startTime = 0, endTime = 1_000)              // before v15, or closed after the app ended
        assertFalse(DriveExportBuilder.buildSummary(d, emptyList()).contains("MAP compare"))
        assertFalse(JSONObject(DriveExportBuilder.buildProfileJson(d, emptyList())).getJSONObject("drive").has("mapComparisonMode"))
    }
}
