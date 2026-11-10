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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Lap timing through the real recording path (#237): the LapReplay route as fixes from DriveRecorder's location
 * source → its recording loop → the TRIP lap timer → STOP → `lap_sessions` / `laps` in the production Room database.
 * The timer is never fed directly. Fix times come from the route (no sleeps); fix ages from the monotonic clock.
 *
 * Every delivered fix must get one `LapTimer` log line from the recorder — the line an emulator run greps — so the test
 * compares what the provider delivered with what the timer accepted, per fix, the way logcat will.
 */
@RunWith(RobolectricTestRunner::class)
class LapFeedTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = java.util.concurrent.atomic.AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(moving())
    private val gps = FakeGps()
    private val lapTimer = LapTimer()
    private lateinit var rec: DriveRecorder

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))                       // room for fix ages on the monotonic clock
        ShadowLog.clear()
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        rec = DriveRecorder(app, vs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { })
        rec.lapTimer = lapTimer
    }

    @After fun tearDown() { rec.cancel(); db.close() }

    private fun moving(connected: Boolean = true) = VehicleState(isConnected = connected, rpm = 5000.0, speedKph = LapReplay.CAN_SPEED_KPH)

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

    /** One provider fix through the loop; waits until the timer's verdict for it is logged. Returns that verdict. */
    private fun deliver(f: LapReplay.Fix, timeBase: Long, expectRecorded: Boolean = true): String {
        val logged = lapLines().size
        val loc = Location("fused").apply {
            latitude = f.lat; longitude = f.lng
            f.bearing?.let { bearing = it }                                       // none → hasBearing() false
            speed = LapReplay.SPEED_MS.toFloat(); time = timeBase + f.tMs
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - f.ageMs * 1_000_000L
        }
        val points = rec.driveState.value.totalPointCount
        gps.current!!.trySend(loc)
        if (!expectRecorded) {
            check(waitUntil { rec.driveState.value.currentLocation?.time == loc.time }) { "fix ${f.n} not seen" }
            return "SKIPPED"
        }
        check(waitUntil { lapLines().size == logged + 1 && rec.driveState.value.totalPointCount == points + 1 }) { "fix ${f.n} not handled" }
        return lapLines().last().substringAfter("-> ")
    }

    private class Checks(private val case: String) {
        val failed = mutableListOf<String>()
        fun check(name: String, ok: Boolean, detail: Any? = null) {
            println("CHECK ${if (ok) "pass" else "FAIL"} [$case] $name${if (ok || detail == null) "" else " — got $detail"}")
            if (!ok) failed += name
        }
        fun done() = assertTrue("[$case] failed: $failed", failed.isEmpty())
    }

    /** Drives the route for the current drive; returns (fix, verdict) per delivered fix, printed as a table. */
    private fun drive(case: String, fixes: List<LapReplay.Fix>, timeBase: Long): List<Pair<LapReplay.Fix, String>> =
        fixes.map { f ->
            val v = deliver(f, timeBase)
            println("REPLAY [$case] #${f.n} ${f.kind} x=${"%.1f".format(f.x)} y=${"%.1f".format(f.y)} t=${f.tMs} age=${f.ageMs} " +
                "bearing=${f.bearing ?: "none"} ${f.note} -> $v")
            f to v
        }

    private fun replayThroughRecorder(withBearings: Boolean) {
        val case = if (withBearings) "feed bearings" else "feed no-bearings"
        val c = Checks(case)
        val fixes = LapReplay.fixes(withBearings)
        val a = start()
        val set = fixes.first()
        lapTimer.setStartFinish(set.lat, set.lng, set.bearing)                    // TRIP's SET S/F at the car's position
        val baseA = 1_700_000_000_000L
        val rows = drive("$case A", fixes, baseA)
        fun of(kind: LapReplay.Kind) = rows.filter { it.first.kind == kind }.map { it.second.substringBefore(" ") }.toSet()

        c.check("A: every delivered fix has a timer verdict in the log", lapLines().size == fixes.size, "${lapLines().size} / ${fixes.size}")
        c.check("A: dwell starts nothing", "TIMING_STARTED" !in of(LapReplay.Kind.DWELL), of(LapReplay.Kind.DWELL))
        c.check("A: replayed fix OUT_OF_ORDER", of(LapReplay.Kind.REPLAYED) == setOf("OUT_OF_ORDER"), of(LapReplay.Kind.REPLAYED))
        c.check("A: stale fixes STALE", of(LapReplay.Kind.STALE) == setOf("STALE"), of(LapReplay.Kind.STALE))
        c.check("A: nearby pass NO_CROSSING", of(LapReplay.Kind.NEARBY) == setOf("NO_CROSSING"), of(LapReplay.Kind.NEARBY))
        c.check("A: repeated position NO_CROSSING", of(LapReplay.Kind.REPEAT) == setOf("NO_CROSSING"), of(LapReplay.Kind.REPEAT))
        c.check("A: displayed laps 28.000 / 28.000 / 43.200 s", lapTimer.laps.map { it.lapTimeMs } == LapReplay.EXPECTED_LAPS_MS,
            lapTimer.laps.map { it.lapTimeMs })

        // STOP → START at once (auto-record's finalize + start); the line stays, B drives the same route again
        val finalizeA = rec.stopDrive()
        val b = start(previousId = a)
        runBlocking { withTimeout(15_000) { finalizeA.join() } }
        val savedA = db.driveDao().getLapSessionsForDrive(a)
        c.check("A: one lap session", savedA.size == 1, savedA.size)
        c.check("A: saved laps 28.000 / 28.000 / 43.200 s", savedA.flatMap { db.driveDao().getLaps(it.id) }.map { it.lapTimeMs } == LapReplay.EXPECTED_LAPS_MS,
            savedA.flatMap { db.driveDao().getLaps(it.id) }.map { it.lapTimeMs })
        c.check("A: line saved eastbound (90°)", savedA.singleOrNull()?.startFinishBearing == 90f, savedA.singleOrNull()?.startFinishBearing)
        c.check("B: re-armed, no laps carried", lapTimer.state == LapTimerState.ARMED && lapTimer.laps.isEmpty(), lapTimer.state to lapTimer.laps.size)

        val baseB = baseA + 600_000L
        drive("$case B", fixes.drop(1), baseB)                                    // line already set: no SET S/F
        c.check("B: displayed laps 28.000 / 28.000 / 43.200 s", lapTimer.laps.map { it.lapTimeMs } == LapReplay.EXPECTED_LAPS_MS,
            lapTimer.laps.map { it.lapTimeMs })
        runBlocking { withTimeout(15_000) { rec.stopDrive().join() } }
        val savedB = db.driveDao().getLapSessionsForDrive(b).flatMap { db.driveDao().getLaps(it.id) }.map { it.lapTimeMs }
        c.check("B: saved laps 28.000 / 28.000 / 43.200 s", savedB == LapReplay.EXPECTED_LAPS_MS, savedB)
        c.check("A: still its own 3 laps", savedA.flatMap { db.driveDao().getLaps(it.id) }.size == 3)
        c.check("two lap sessions in total (A, B)", db.driveDao().getLapSessionsForDrive(a).size + db.driveDao().getLapSessionsForDrive(b).size == 2)
        c.done()
    }

    @Test fun `route through the recording loop - 3 valid loops give 3 displayed and 3 saved laps per drive (no bearings)`() =
        replayThroughRecorder(false)

    @Test fun `route through the recording loop - 3 valid loops give 3 displayed and 3 saved laps per drive (bearings)`() =
        replayThroughRecorder(true)

    @Test
    fun `a crossing across a pause or an adapter gap is not formed`() {
        val c = Checks("gaps")
        start()
        lapTimer.setStartFinish(LapReplay.SF_LAT, LapReplay.SF_LNG, 90f)
        fun at(n: Int, x: Double, t: Long) = LapReplay.Fix(n, LapReplay.Kind.ROUTE, x, 0.0, t, t, 90f, "")
        deliver(at(0, -60.0, 0), 0); deliver(at(1, -10.0, 1_000), 0)
        rec.pauseDrive()
        deliver(at(2, 10.0, 2_000), 0, expectRecorded = false)                    // paused: not recorded, not fed
        rec.resumeDrive()
        c.check("after a pause: no segment across it", deliver(at(3, 30.0, 3_000), 0) == "NO_SEGMENT")
        c.check("not timing", lapTimer.state == LapTimerState.ARMED, lapTimer.state)
        deliver(at(4, -60.0, 20_000), 0); deliver(at(5, -10.0, 21_000), 0)
        vs.value = moving(connected = false)
        deliver(at(6, 10.0, 22_000), 0, expectRecorded = false)                   // held telemetry: not a sample
        vs.value = moving()
        c.check("after an adapter gap: no segment across it", deliver(at(7, 30.0, 23_000), 0) == "NO_SEGMENT")
        c.check("still not timing", lapTimer.state == LapTimerState.ARMED, lapTimer.state)
        c.done()
    }
}
