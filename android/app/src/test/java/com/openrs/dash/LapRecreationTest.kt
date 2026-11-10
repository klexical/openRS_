package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.LapTimer
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * #238 review finding 3 (QA openrs-9d, built from its QaLapRecreationTest) — the lap timer across an activity recreation.
 * TripPage used `remember { LapTimer() }` + `recorder.lapTimer = it`: `remember` doesn't survive a recreation (rotation,
 * "Don't keep activities"; MainActivity declares no configChanges), so the recreated TRIP put a NEW, unarmed timer on the
 * app-scoped recorder mid-drive — the line vanished and STOP (`lapTimer?.takeLaps`) saved none of the drive's laps. TRIP
 * now takes the recorder's own ([DriveRecorder.lapTimerForUi]) on every composition; the UI stand-in here is exactly
 * that call. Production path: LapReplay fixes → the recording loop → the timer → STOP → `lap_sessions` / `laps` (Room).
 */
@RunWith(RobolectricTestRunner::class)
class LapRecreationTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = java.util.concurrent.atomic.AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 5000.0, speedKph = LapReplay.CAN_SPEED_KPH))
    private val gps = FakeGps()
    private lateinit var rec: DriveRecorder

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        ShadowLog.clear()
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        rec = DriveRecorder(app, vs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { })
    }

    @After fun tearDown() { rec.cancel(); db.close() }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(2) }
        return cond()
    }

    private fun start(previousId: Long = 0): Long {
        val opened = gps.opened.get()
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 && it.driveId != previousId } && gps.opened.get() > opened })
        return rec.driveState.value.driveId
    }

    private fun lapLines() = ShadowLog.getLogsForTag("LapTimer").map { it.msg }.filter { " fix t=" in it }

    private fun deliver(f: LapReplay.Fix, timeBase: Long): String {
        val logged = lapLines().size
        val loc = Location("fused").apply {
            latitude = f.lat; longitude = f.lng
            f.bearing?.let { bearing = it }
            speed = LapReplay.SPEED_MS.toFloat(); time = timeBase + f.tMs
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - f.ageMs * 1_000_000L
        }
        val points = rec.driveState.value.totalPointCount
        gps.current!!.trySend(loc)
        // A timer without a line answers NO_LINE, which the recorder doesn't log — wait for the recorded point instead
        check(waitUntil { rec.driveState.value.totalPointCount == points + 1 }) { "fix ${f.n} not handled" }
        return if (lapLines().size > logged) lapLines().last().substringAfter("-> ") else "NO_LINE (not logged)"
    }

    private fun route(timeBase: Long, set: Boolean): List<LapReplay.Fix> =
        LapReplay.fixes(true).filter { it.kind != LapReplay.Kind.REPLAYED && it.kind != LapReplay.Kind.STALE }
            .let { if (set) it else it.drop(1) }
            .onEach { deliver(it, timeBase) }

    private fun savedLaps(driveId: Long) =
        db.driveDao().getLapSessionsForDrive(driveId).flatMap { db.driveDao().getLaps(it.id) }.map { it.lapNumber to it.lapTimeMs }

    private class Checks(private val case: String) {
        val failed = mutableListOf<String>()
        fun check(name: String, ok: Boolean, detail: Any? = null) {
            println("CHECK ${if (ok) "pass" else "FAIL"} [$case] $name${if (detail == null) "" else " — $detail"}")
            if (!ok) failed += name
        }
        fun done() = assertTrue("[$case] failed: $failed", failed.isEmpty())
    }

    /** What TRIP does on each composition (first, or after a recreation): the recorder's own timer. */

    /**
     * A drive's laps when the same timer saw both passes: the 3 before, ONE lap spanning the test's 600 s break between
     * the passes (the car is still lapping — it is a real crossing-to-crossing lap, just long), then the 3 after. 7 in all,
     * numbered 1–7. (QA's draft expected 6; the break lap is the harness's, not a loss or a duplicate.)
     */
    private fun wholeDrive(saved: List<Pair<Int, Long>>, before: List<Pair<Int, Long>>) =
        saved.size == 7 && saved.map { it.first } == (1..7).toList() && saved.take(3) == before &&
            saved[3].second > 300_000L && saved.takeLast(3).map { it.second } == LapReplay.EXPECTED_LAPS_MS
    private fun composeTrip(): LapTimer = rec.lapTimerForUi()

    @Test fun `R1 recreation mid-drive - the line stays and STOP saves every lap of the drive`() {
        val c = Checks("R1 recreation")
        val t1 = composeTrip()                                        // TRIP composed before the drive
        val a = start()
        val sf = LapReplay.fixes(true).first()
        t1.setStartFinish(sf.lat, sf.lng, sf.bearing)                 // SET S/F
        route(1_700_000_000_000L, set = true)
        val before = t1.laps.map { it.lapNumber to it.lapTimeMs }
        c.check("before recreation: 3 laps", before.map { it.second } == LapReplay.EXPECTED_LAPS_MS, before)
        val t2 = composeTrip()                                        // rotation → new composition
        c.check("after recreation: the same timer, line still set (TRIP shows CLEAR S/F)", t2 === t1 && t2.startFinish != null,
            "same=${t2 === t1} startFinish=${t2.startFinish}")
        route(1_700_000_600_000L, set = false)                        // keep driving the same circuit
        runBlocking { withTimeout(15_000) { rec.stopDrive().join() } }
        val saved = savedLaps(a)
        c.check("STOP saves the 3 laps completed before recreation", saved.take(3) == before, "saved=$saved")
        c.check("and every lap after it: break lap + 3 laps, numbered 1–7, nothing lost or repeated", wholeDrive(saved, before), "saved=$saved")
        c.check("one lap session for the drive", db.driveDao().getLapSessionsForDrive(a).size == 1)
        c.done()
    }

    @Test fun `R2 recreation, then STOP and START - the line is kept, each drive keeps only its own laps`() {
        val c = Checks("R2 next drive")
        val t1 = composeTrip()
        val a = start()
        val sf = LapReplay.fixes(true).first()
        t1.setStartFinish(sf.lat, sf.lng, sf.bearing)
        route(1_700_000_000_000L, set = true)
        val before = t1.laps.map { it.lapNumber to it.lapTimeMs }
        composeTrip()                                                 // recreation mid-drive (no re-SET needed: line shown)
        route(1_700_000_600_000L, set = false)
        runBlocking { withTimeout(15_000) { rec.stopDrive().join() } }
        val saved = savedLaps(a)
        c.check("drive A: every lap, no duplicate numbers", wholeDrive(saved, before), "saved=$saved")
        val b = start(previousId = a)
        c.check("drive B starts with the same line (kept across drives)", composeTrip().startFinish != null)
        route(1_700_001_200_000L, set = false)
        runBlocking { withTimeout(15_000) { rec.stopDrive().join() } }
        val savedB = savedLaps(b)
        c.check("drive B: exactly its own 3 laps, numbered from 1", savedB.map { it.second } == LapReplay.EXPECTED_LAPS_MS &&
            savedB.map { it.first } == listOf(1, 2, 3), "savedB=$savedB")
        c.check("drive A unchanged by drive B", savedLaps(a) == saved, "A=${savedLaps(a)}")
        c.done()
    }

    @Test fun `R3 recreation on DRIVE or PERF, TRIP composed later mid-drive - no late swap`() {
        // With beyondViewportPageCount = 1, TRIP isn't composed from DRIVE / PERF: after a recreation there the
        // recorder kept the old timer until TRIP was next composed, and the swap happened then
        val c = Checks("R3 late compose")
        val t1 = composeTrip()
        val a = start()
        val sf = LapReplay.fixes(true).first()
        t1.setStartFinish(sf.lat, sf.lng, sf.bearing)
        route(1_700_000_000_000L, set = true)                         // recreation happens here, TRIP not composed
        val before = t1.laps.map { it.lapNumber to it.lapTimeMs }
        route(1_700_000_600_000L, set = false)
        val t2 = composeTrip()                                        // the user swipes to TRIP
        c.check("TRIP gets the timer that has been timing", t2 === t1 && t2.laps.size == 7, "same=${t2 === t1} laps=${t2.laps.size}")
        runBlocking { withTimeout(15_000) { rec.stopDrive().join() } }
        c.check("STOP saves every lap of the drive", wholeDrive(savedLaps(a), before), "saved=${savedLaps(a)}")
        c.done()
    }
}
