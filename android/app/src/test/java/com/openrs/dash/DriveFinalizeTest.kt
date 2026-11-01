package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.data.LapTimer
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import com.openrs.dash.ui.trip.DriveHistory
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * DriveRecorder's STOP finalization (#236) on the production Room database + DAO (Robolectric SQLite), driven through
 * the recorder's real recording loop with synthetic GPS. The address lookup and A's final point flush can be held, so
 * the windows the issue describes are entered deterministically:
 *  - a rename / tag after the finalize read the row (held lookup) must survive;
 *  - a drive deleted there must leave no lap records and post no summary;
 *  - drive A stopping while drive B starts (auto-record's finalize + start) must save only A's data for A, and B must
 *    keep recording with its own;
 *  - an ordinary STOP posts exactly one summary (guard).
 * Every expectation is a named check; all are evaluated and listed in the test output ("CHECK pass|FAIL …") before the
 * test fails, so a run on the old recorder shows exactly which ones it breaks. Laps are fed to the timer directly here
 * — the production feed is missing (#237).
 */
@RunWith(RobolectricTestRunner::class)
class DriveFinalizeTest {

    // ── Fixtures ────────────────────────────────────────────────────────────

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** The address lookup: returns [name]; when [held], blocks until [release] (after signalling [entered]). */
    private class Geocoder(private val name: String?, held: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (held) 1 else 0)
        fun lookup(@Suppress("UNUSED_PARAMETER") lat: Double, @Suppress("UNUSED_PARAMETER") lng: Double): String? {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            return name
        }
    }

    /**
     * The production DAO, except that drive [holdDriveId]'s knock-event write can be held. A's recording loop is then
     * stopped mid-point, so its final point flush can only run after the test has started B (and let it record) —
     * the order auto-record's finalize + start can produce. Every point insert is logged per drive.
     */
    private class HoldingDao(real: DriveDao) {
        @Volatile var holdDriveId = Long.MIN_VALUE
        val knockEntered = CountDownLatch(1)
        val knockRelease = CountDownLatch(1)
        val pointInserts = CopyOnWriteArrayList<Pair<Long, Int>>()                 // (driveId of first point, size)
        val dao: DriveDao = Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            if (m.name == "insertKnockEvents" && ((args[0] as List<*>).firstOrNull() as? KnockEventEntity)?.driveId == holdDriveId) {
                knockEntered.countDown()
                knockRelease.await(10, TimeUnit.SECONDS)
            }
            if (m.name == "insertPoints") (args[0] as List<*>).let { pts ->
                pointInserts += ((pts.firstOrNull() as? DrivePointEntity)?.driveId ?: -1L) to pts.size
            }
            try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as DriveDao
    }

    private class Checks(private val case: String) {
        private val failed = mutableListOf<String>()
        fun check(name: String, ok: Boolean, detail: Any? = null) {
            println("CHECK ${if (ok) "pass" else "FAIL"} [$case] $name${if (ok || detail == null) "" else " — got $detail"}")
            if (!ok) failed += "$name${if (detail == null) "" else " (got $detail)"}"
        }
        fun done() = assertTrue("[$case] failed: ${failed.joinToString("; ")}", failed.isEmpty())
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState())
    private val gps = FakeGps()
    private val lapTimer = LapTimer()
    private val summaries = CopyOnWriteArrayList<DriveEntity>()
    private val recorders = mutableListOf<DriveRecorder>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        File(app.filesDir, "route_thumbs").deleteRecursively()
    }

    @After fun tearDown() {
        recorders.forEach { it.cancel() }
        db.close()
    }

    private fun recorder(geo: Geocoder, dao: DriveDao = db.driveDao()) =
        DriveRecorder(app, vs, WeatherRepository(""), db, dao, geo::lookup, gps, { summaries += it })
            .also { it.lapTimer = lapTimer; recorders += it }

    private val dao get() = db.driveDao()

    private fun telemetry(oil: Double, coolant: Double, rpm: Double, pedal: Double, latG: Double): VehicleState {
        val now = System.currentTimeMillis()
        return VehicleState(
            isConnected = true, oilTempC = oil, coolantTempC = coolant, rpm = rpm, accelPedalPct = pedal,
            lateralG = latG, speedKph = 80.0, fieldLastUpdateMs = mapOf("oilTempC" to now, "coolantTempC" to now)
        )
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    /** startDrive and wait until its loop takes GPS; returns the new drive id (or -1 if it never started). */
    private fun start(rec: DriveRecorder, previousId: Long = 0): Long {
        val opened = gps.opened.get()
        rec.startDrive()
        val ok = waitUntil { rec.driveState.value.let { it.isRecording && it.driveId != previousId && it.driveId > 0 } && gps.opened.get() > opened }
        return if (ok) rec.driveState.value.driveId else -1
    }

    private fun fix(i: Int) = Location("test").apply {
        latitude = 51.5 + i * 1e-4; longitude = -0.1 + i * 1e-4; bearing = 90f; speed = 22f; time = System.currentTimeMillis()
    }

    /** [n] fixes through the real loop, one at a time (each with its own telemetry). */
    private fun record(rec: DriveRecorder, n: Int, from: Int = 0, mk: (Int) -> VehicleState) {
        val channel = gps.current!!
        for (i in from until from + n) {
            val before = rec.driveState.value.totalPointCount
            vs.value = mk(i)
            channel.trySend(fix(i))
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
    }

    /** Laps straight into the shared timer: first crossing starts timing, each later one completes a lap. */
    private fun crossings(vararg atMs: Long) = atMs.forEach {
        lapTimer.onLocationUpdate(SF_LAT, SF_LNG, 90f, 100.0, it, 6000, 0.0, 0.5)
    }

    private fun join(job: Job) = runBlocking { withTimeout(15_000) { job.join() } }

    private fun lapTimes(driveId: Long) = dao.getLapSessionsForDrive(driveId).flatMap { s -> dao.getLaps(s.id).map { it.lapTimeMs } }

    private fun scalar(sql: String): Long = db.openHelper.writableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }

    private fun oilPeak(json: String?): Double? = json?.let {
        val segs = JSONObject(it).getJSONArray("segments")
        (0 until segs.length()).mapNotNull { i -> segs.getJSONObject(i).getJSONObject("peaks").optDouble("Oil").takeIf { v -> !v.isNaN() } }.maxOrNull()
    }

    private val hot = { i: Int -> telemetry(96.0 + i * 2, 89.0, 6000.0, 90.0, 0.8) }    // oil 96 → 118 over 12 fixes

    // ── Cases ───────────────────────────────────────────────────────────────

    @Test
    fun `rename and tag after the finalize read the row - the user's edits survive`() {
        val c = Checks("rename+tag")
        val geo = Geocoder("Geocoded Rd", held = true)
        val rec = recorder(geo)
        vs.value = telemetry(95.0, 88.0, 900.0, 0.0, 0.0)
        val id = start(rec)
        record(rec, 12, mk = hot)
        val job = rec.stopDrive()
        c.check("finalize reached the address lookup", geo.entered.await(5, TimeUnit.SECONDS))

        DriveHistory.rename(dao, id, "Hill Run")
        DriveHistory.setTags(dao, id, "TRACK")
        geo.release.countDown()
        join(job)

        val row = dao.getDrive(id)
        c.check("name kept", row?.name == "Hill Run", row?.name)
        c.check("tags kept", row?.tags == "TRACK", row?.tags)
        c.check("drive ended", (row?.endTime ?: 0) > 0, row?.endTime)
        c.check("thermal record written", row?.thermalPeaksJson != null)
        c.check("all points stored", dao.getPointCount(id) == 12, dao.getPointCount(id))
        c.check("one summary", summaries.size == 1, summaries.size)
        c.check("summary carries the edits", summaries.singleOrNull()?.let { it.name == "Hill Run" && it.tags == "TRACK" } == true,
            summaries.map { it.name to it.tags })
        c.done()
    }

    @Test
    fun `tag only after the finalize read the row - tags survive and the auto-name still applies`() {
        val c = Checks("tag-only")
        val geo = Geocoder("Geocoded Rd", held = true)
        val rec = recorder(geo)
        vs.value = telemetry(95.0, 88.0, 900.0, 0.0, 0.0)
        val id = start(rec)
        record(rec, 12, mk = hot)
        val job = rec.stopDrive()
        c.check("finalize reached the address lookup", geo.entered.await(5, TimeUnit.SECONDS))
        DriveHistory.setTags(dao, id, "COMMUTE")
        geo.release.countDown()
        join(job)

        val row = dao.getDrive(id)
        c.check("tags kept", row?.tags == "COMMUTE", row?.tags)
        c.check("auto-name applied (no user name)", row?.name == "Geocoded Rd", row?.name)
        c.done()
    }

    @Test
    fun `delete after the finalize read the row - no lap records, no summary, nothing left`() {
        val c = Checks("delete")
        val geo = Geocoder("Geocoded Rd", held = true)
        val rec = recorder(geo)
        vs.value = telemetry(95.0, 88.0, 900.0, 0.0, 0.0)
        val id = start(rec)
        lapTimer.setStartFinish(SF_LAT, SF_LNG, 90f)
        crossings(1_000_000, 1_030_000, 1_061_000)                                // 2 laps in this drive
        record(rec, 12, mk = hot)
        val job = rec.stopDrive()
        c.check("finalize reached the address lookup", geo.entered.await(5, TimeUnit.SECONDS))

        DriveHistory.delete(dao, id)
        geo.release.countDown()
        join(job)
        Thread.sleep(200)                                                         // anything late would land here

        c.check("drive gone", dao.getDrive(id) == null)
        c.check("drive count 0", dao.getDriveCount() == 0, dao.getDriveCount())
        c.check("points gone (cascade)", dao.getPointCount(id) == 0, dao.getPointCount(id))
        c.check("no lap session for the drive", dao.getLapSessionsForDrive(id).isEmpty(), dao.getLapSessionsForDrive(id).size)
        c.check("no orphan lap session", scalar("SELECT COUNT(*) FROM lap_sessions WHERE driveId NOT IN (SELECT id FROM drives)") == 0L,
            scalar("SELECT COUNT(*) FROM lap_sessions"))
        c.check("no lap rows", scalar("SELECT COUNT(*) FROM laps") == 0L, scalar("SELECT COUNT(*) FROM laps"))
        c.check("no summary posted", summaries.none { it.id == id }, summaries.map { it.id })
        c.check("no thumbnail file", !File(app.filesDir, "route_thumbs/drive_$id.png").exists())
        c.done()
    }

    @Test
    fun `ordinary STOP posts exactly one summary - the stored row`() {
        val c = Checks("ordinary")
        val rec = recorder(Geocoder("Geocoded Rd", held = false))
        vs.value = telemetry(95.0, 88.0, 900.0, 0.0, 0.0)
        val id = start(rec)
        record(rec, 12, mk = hot)
        join(rec.stopDrive())
        Thread.sleep(300)

        val row = dao.getDrive(id)
        c.check("exactly one summary", summaries.size == 1, summaries.size)
        c.check("summary is the stored row", summaries.singleOrNull() == row)
        c.check("auto-name applied", row?.name == "Geocoded Rd", row?.name)
        c.check("all points stored", dao.getPointCount(id) == 12, dao.getPointCount(id))
        c.check("not recording", !rec.driveState.value.isRecording)
        c.done()
    }

    @Test
    fun `ordinary STOP with no address - one summary, name stays empty`() {
        val c = Checks("ordinary-no-address")
        val rec = recorder(Geocoder(null, held = false))
        vs.value = telemetry(95.0, 88.0, 900.0, 0.0, 0.0)
        val id = start(rec)
        record(rec, 12, mk = hot)
        join(rec.stopDrive())
        Thread.sleep(300)
        c.check("exactly one summary", summaries.size == 1, summaries.size)
        c.check("name null", dao.getDrive(id)?.name == null, dao.getDrive(id)?.name)
        c.done()
    }

    @Test
    fun `A stops while B starts - A keeps its own data and B keeps recording with its own`() {
        val c = Checks("A/B")
        val holding = HoldingDao(db.driveDao())
        val rec = recorder(Geocoder("Geocoded Rd", held = false), holding.dao)

        // Drive A: warm start, hard driving, 2 laps; 11 fixes buffered (< FLUSH_SIZE, nothing flushed yet)
        vs.value = telemetry(95.0, 88.0, 900.0, 0.0, 0.0)
        val a = start(rec)
        lapTimer.setStartFinish(SF_LAT, SF_LNG, 90f)
        crossings(1_000_000, 1_030_000, 1_061_000)                                // laps 30.0 s, 31.0 s
        record(rec, 11, mk = hot)
        // A's 12th fix carries fresh knock (KR 2°): A's loop is held inside that point's knock write
        holding.holdDriveId = a
        vs.value = hot(11).let { it.copy(ignCorrCyl1 = 2.0, fieldLastUpdateMs = it.fieldLastUpdateMs + ("ignCorrCyl1" to System.currentTimeMillis())) }
        gps.current!!.trySend(fix(11))
        c.check("A's loop held in its 12th point", holding.knockEntered.await(5, TimeUnit.SECONDS))
        vs.value = telemetry(117.0, 92.0, 6000.0, 90.0, 0.8)                      // A's state at STOP

        // STOP A and start B at once (auto-record's FINALIZE_AND_START); B records while A's loop is still held
        val beforeStop = System.currentTimeMillis()
        val finalizeA = rec.stopDrive()
        val afterStop = System.currentTimeMillis()
        vs.value = telemetry(60.0, 50.0, 900.0, 0.0, 0.0)                         // B: cold start, gentle
        val b = start(rec, previousId = a)
        c.check("B started", b > 0 && b != a, b)
        crossings(1_200_000, 1_245_000)                                           // B: one 45.0 s lap
        if (b > 0) record(rec, 5, from = 100) { i -> telemetry(60.0 + (i - 100), 50.0 + (i - 100), 900.0, 0.0, 0.0) }
        c.check("A's points not yet flushed while B records", holding.pointInserts.none { it.first == a }, holding.pointInserts)

        // Release A's loop: its point completes and its final flush runs now, after B started
        holding.knockRelease.countDown()
        join(finalizeA)
        Thread.sleep(200)

        val rowA = dao.getDrive(a)
        c.check("A: STOP timestamp", rowA != null && rowA.endTime in beforeStop..afterStop, rowA?.endTime?.let { "+${it - afterStop} ms after STOP returned" })
        c.check("A: start temps (oil 95 / coolant 88)", rowA?.startOilTempC == 95.0 && rowA.startCoolantTempC == 88.0,
            rowA?.let { it.startOilTempC to it.startCoolantTempC })
        c.check("A: end temps (oil 117 / coolant 92)", rowA?.endOilTempC == 117.0 && rowA.endCoolantTempC == 92.0,
            rowA?.let { it.endOilTempC to it.endCoolantTempC })
        c.check("A: thermal record peak oil 118", oilPeak(rowA?.thermalPeaksJson) == 118.0, oilPeak(rowA?.thermalPeaksJson))
        c.check("A: aggression score from A's driving (>= 50)", (rowA?.aggressionScore ?: 0) >= 50, rowA?.aggressionScore)
        c.check("A: all 12 points", dao.getPointCount(a) == 12, dao.getPointCount(a))
        c.check("A: its 2 laps only (30.0 s, 31.0 s)", lapTimes(a) == listOf(30_000L, 31_000L), lapTimes(a))
        c.check("A: its knock event", scalar("SELECT COUNT(*) FROM knock_events WHERE driveId = $a") == 1L,
            scalar("SELECT COUNT(*) FROM knock_events WHERE driveId = $a"))
        c.check("A: one summary", summaries.count { it.id == a } == 1, summaries.count { it.id == a })

        val live = rec.driveState.value
        c.check("B still recording after A's finalize", live.isRecording && live.driveId == b, live.isRecording to live.driveId)
        c.check("B: live point count 5 (no A point counted)", live.totalPointCount == 5, live.totalPointCount)
        c.check("B: no lap session while recording", lapTimes(b).isEmpty(), lapTimes(b))

        // Stop B: everything it saves is B's
        join(rec.stopDrive())
        val rowB = dao.getDrive(b)
        c.check("B: start temps (oil 60 / coolant 50)", rowB?.startOilTempC == 60.0 && rowB.startCoolantTempC == 50.0,
            rowB?.let { it.startOilTempC to it.startCoolantTempC })
        c.check("B: thermal record peak oil 64", oilPeak(rowB?.thermalPeaksJson) == 64.0, oilPeak(rowB?.thermalPeaksJson))
        c.check("B: its 5 points", dao.getPointCount(b) == 5, dao.getPointCount(b))
        c.check("B: its 1 lap only (45.0 s)", lapTimes(b) == listOf(45_000L), lapTimes(b))
        c.check("B: no knock event", scalar("SELECT COUNT(*) FROM knock_events WHERE driveId = $b") == 0L)
        c.check("no point stored under the wrong drive", dao.getPoints(a).all { it.driveId == a } && dao.getPoints(b).all { it.driveId == b })
        c.done()
    }

    private companion object {
        const val SF_LAT = 51.6
        const val SF_LNG = -0.2
    }
}
