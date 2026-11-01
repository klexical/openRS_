package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Lap timing through the real recording path (#237): synthetic GPS → DriveRecorder's location source → its recording
 * loop → the TRIP lap timer → STOP → `lap_sessions` / `laps` in the production Room database. The timer is never fed
 * directly here (LapTimerTest covers its geometry). The timer's lap rule is ≥ 10 s on the wall clock, so this test takes
 * about 21 s: one real lap, then a crossing while paused and one while disconnected, neither of which may count.
 */
@RunWith(RobolectricTestRunner::class)
class LapFeedTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        override fun updates(): Flow<Location> = Channel<Location>(Channel.UNLIMITED).also { current = it }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState())
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

    private fun moving(connected: Boolean = true) = VehicleState(isConnected = connected, rpm = 5000.0, speedKph = 80.0)

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private var sent = 0
    /** One fix through the location source; waits until the loop has handled it (recorded, or skipped + located). */
    private fun fix(lat: Double, lng: Double, expectRecorded: Boolean) {
        val before = rec.driveState.value.totalPointCount
        val loc = Location("test").apply { latitude = lat; longitude = lng; bearing = 90f; speed = 22f; time = System.currentTimeMillis() + sent++ }
        gps.current!!.trySend(loc)
        if (expectRecorded) check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "fix not recorded" }
        else check(waitUntil { rec.driveState.value.currentLocation?.time == loc.time }) { "fix not seen" }
    }

    private fun lapAway() { fix(SF_LAT + 0.004, SF_LNG, true); fix(SF_LAT + 0.004, SF_LNG + 0.006, true); fix(SF_LAT, SF_LNG + 0.006, true) }

    @Test
    fun `synthetic GPS through the recording loop completes and persists a lap - paused and disconnected fixes don't`() {
        vs.value = moving()
        rec.startDrive()
        assertTrue(waitUntil { rec.driveState.value.isRecording && gps.current != null })
        val id = rec.driveState.value.driveId

        lapTimer.setStartFinish(SF_LAT, SF_LNG, 90f)                              // TRIP's SET S/F at the car's position
        fix(SF_LAT, SF_LNG, true)                                                 // first crossing: timing starts
        assertEquals("first crossing starts timing", LapTimerState.TIMING, lapTimer.state)
        lapAway()
        Thread.sleep(10_300)                                                      // the timer's minimum lap is 10 s
        fix(SF_LAT, SF_LNG, true)                                                 // second crossing: lap 1
        assertEquals("lap completed from recorded fixes", 1, lapTimer.laps.size)
        val lapMs = lapTimer.laps.single().lapTimeMs
        assertTrue("lap time $lapMs ms", lapMs in 10_000L..15_000L)

        // Paused: the fix is not recorded, so it must not reach the timer either
        lapAway()
        Thread.sleep(10_300)
        rec.pauseDrive()
        fix(SF_LAT, SF_LNG, false)
        rec.resumeDrive()
        // Disconnected: held telemetry is not a sample — same rule
        vs.value = moving(connected = false)
        fix(SF_LAT, SF_LNG, false)
        vs.value = moving()
        assertEquals("paused / disconnected crossings add no lap", 1, lapTimer.laps.size)

        runBlocking { withTimeout(15_000) { rec.stopDrive().join() } }
        val sessions = db.driveDao().getLapSessionsForDrive(id)
        assertEquals("one lap session for the drive", 1, sessions.size)
        assertEquals("its lap, as timed", listOf(lapMs), db.driveDao().getLaps(sessions.single().id).map { it.lapTimeMs })
        assertEquals("S/F line saved", SF_LAT, sessions.single().startFinishLat, 0.0)
        assertTrue("timer re-armed for the next drive", lapTimer.laps.isEmpty() && lapTimer.state == LapTimerState.ARMED)
    }

    private companion object {
        const val SF_LAT = 51.6
        const val SF_LNG = -0.2
    }
}
