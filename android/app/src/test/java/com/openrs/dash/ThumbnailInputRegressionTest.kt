package com.openrs.dash

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import com.openrs.dash.ui.trip.RouteThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * #241 (regression and controls from QA, openrs-9d): the route thumbnail written at STOP must be built from every point
 * the drive recorded before STOP, also when an active diagnostic export ([DriveRecorder.snapshotActiveDrive]) or a pause
 * flush is still writing points it took from the buffer — and STOP must still finish when that write fails or its
 * caller is cancelled.
 *
 * Path: real recorder loop → real active export → real stopDrive finalize → production DAO / Room (Robolectric SQLite)
 * → RouteThumbnail.generateAndCache. The export's point insert is held by an explicit latch in a Proxy over the real DAO;
 * the finalize's own point read (its thumbnail input) is identified by its stack (DriveRecorder.stopDrive's finalize).
 * The rendered PNG is checked too (Robolectric NATIVE graphics), against a render of the drive's final points.
 *
 * Synthetic fixes: drive tag = integer part of the longitude (1 = A, 2 = B); seq from the latitude; a zig-zag in the
 * longitude so a partial route renders differently.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailInputRegressionTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** Real DAO. [hold] = "export" / "flush": that writer's next insertPoints waits on [release] after signalling
     *  [entered]; with [failHeld] it then throws instead of writing. Records the finalize's thumbnail input and a trace
     *  of point writes / reads. */
    private class TracingDao(real: DriveDao) {
        @Volatile var hold: String? = null
        @Volatile var failHeld = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thumbnailInputs = CopyOnWriteArrayList<Pair<Long, List<String>>>()   // (driveId, point keys) per finalize
        val trace = CopyOnWriteArrayList<String>()
        private fun from(fragment: String) = Thread.currentThread().stackTrace.any { it.className.contains(fragment) }
        val dao: DriveDao = Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            val who = when {
                from("snapshotActiveDrive") -> "export"
                from("DriveRecorder\$stopDrive") -> "finalize"
                from("DriveRecorder\$startRecordingLoop") -> "loop"
                from("DriveRecorder\$flushBuffer") -> "flush"
                else -> "other"
            }
            if (m.name == "insertPoints" && who == hold) {
                hold = null
                trace += "$who insertPoints ${keys(args!![0] as List<*>)} HELD"
                entered.countDown(); release.await(30, TimeUnit.SECONDS)
                trace += "$who insertPoints released"
                if (failHeld) { trace += "$who insertPoints FAILED"; throw android.database.sqlite.SQLiteFullException("test: insert fails") }
            }
            val r = try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
            when (m.name) {
                "insertPoints" -> trace += "$who insertPoints ${keys(args!![0] as List<*>)}"
                "getPoints" -> {
                    val k = keys(r as List<*>)
                    trace += "$who getPoints(${args!![0]}) -> ${k.size} $k"
                    if (who == "finalize") thumbnailInputs += (args[0] as Long) to k
                }
                "updateDrive" -> if (who == "finalize") trace += "finalize updateDrive(${(args!![0] as DriveEntity).id}) endTime set"
            }
            r
        } as DriveDao
    }

    private class Checks(private val case: String) {
        private val failed = mutableListOf<String>()
        fun check(name: String, ok: Boolean, detail: Any? = null) {
            println("CHECK ${if (ok) "pass" else "FAIL"} [$case] $name${if (detail == null) "" else " — $detail"}")
            if (!ok) failed += name
        }
        fun note(s: String) = println("NOTE [$case] $s")
        fun done() = assertTrue("[$case] failed: ${failed.joinToString("; ")}", failed.isEmpty())
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 3000.0, speedKph = 80.0))
    private val gps = FakeGps()
    private val recorders = mutableListOf<DriveRecorder>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        File(app.filesDir, "route_thumbs").deleteRecursively()
    }

    @After fun tearDown() { recorders.forEach { it.cancel() }; db.close() }

    private fun recorder(dao: DriveDao) =
        DriveRecorder(app, vs, WeatherRepository(""), db, dao, { _, _ -> null }, gps, { }).also { recorders += it }

    private fun waitUntil(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(2) }
        return cond()
    }

    private fun start(rec: DriveRecorder, previousId: Long = 0): Long {
        val opened = gps.opened.get()
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId != previousId && it.driveId > 0 } && gps.opened.get() > opened }) { "drive did not start" }
        return rec.driveState.value.driveId
    }

    private fun record(rec: DriveRecorder, tag: Int, from: Int, n: Int) {
        val ch = gps.current!!
        for (s in from until from + n) {
            val before = rec.driveState.value.totalPointCount
            ch.trySend(Location("qa").apply {
                latitude = 10.0 + s * 1e-4; longitude = tag + (s % 5) * 2e-5; time = System.currentTimeMillis()
            })
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $tag/$s not recorded" }
        }
    }

    private fun join(job: Job) = runBlocking { withTimeout(30_000) { job.join() } }
    private fun finishesWithin(job: Job, ms: Long = 1_000) = runBlocking { withTimeoutOrNull(ms) { job.join(); true } } == true

    private fun exportAsync(rec: DriveRecorder): () -> Pair<DriveEntity, List<DrivePointEntity>>? {
        var result: Pair<DriveEntity, List<DrivePointEntity>>? = null
        val t = Thread { result = runBlocking(Dispatchers.IO) { rec.snapshotActiveDrive() } }.apply { start() }
        return { t.join(30_000); result }
    }

    private fun expect(tag: String, r: IntRange) = r.map { "$tag$it" }
    private fun dupes(k: List<String>) = k.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun complete(c: Checks, label: String, got: List<String>, want: List<String>) {
        val missing = want - got.toSet(); val extra = got.toSet() - want.toSet(); val d = dupes(got)
        c.check("$label: complete, no duplicates, no other drive's points", missing.isEmpty() && extra.isEmpty() && d.isEmpty(),
            "${got.size} points${if (missing.isEmpty()) "" else "; missing ${missing.sorted()}"}${if (extra.isEmpty()) "" else "; extra $extra"}${if (d.isEmpty()) "" else "; dupes $d"}")
    }

    /** The end-of-drive thumbnail: its input (the finalize's own point read) and the cached PNG vs a render of the final points. */
    private fun thumbnail(c: Checks, h: TracingDao, id: Long, want: List<String>) {
        val inputs = h.thumbnailInputs.filter { it.first == id }
        c.check("drive $id: finalize read its points once for the thumbnail", inputs.size == 1, inputs.size)
        val input = inputs.lastOrNull()?.second.orEmpty()
        c.check("drive $id: thumbnail INPUT = every point recorded before STOP", input.sorted() == want.sorted(),
            "input ${input.size} / ${want.size}${if (input.size == want.size) "" else ": has ${input.sorted()}"}")
        val cached = RouteThumbnail.getCached(app, id)
        c.check("drive $id: thumbnail PNG cached", cached != null)
        if (cached != null) {
            val png: Bitmap? = BitmapFactory.decodeFile(cached.path)
            val full = RouteThumbnail.generate(db.driveDao().getPoints(id))
            val rendersDiffer = full != null && png != null && run {
                // Sanity: does a partial route render differently at all? (else the rendered check proves nothing)
                val partial = RouteThumbnail.generate(db.driveDao().getPoints(id).takeLast(3))
                partial != null && !partial.sameAs(full)
            }
            c.note("drive $id: rendered check meaningful (partial ≠ full render): $rendersDiffer")
            c.check("drive $id: RENDERED thumbnail = render of the drive's final points", png != null && full != null && png.sameAs(full),
                "png ${png?.width}x${png?.height}, full render ${full?.width}x${full?.height}")
        }
    }

    // ── Control 1: no export at all ─────────────────────────────────────────
    @Test fun control_noExport() {
        val c = Checks("control: no export")
        val h = TracingDao(db.driveDao()); val rec = recorder(h.dao)
        val a = start(rec)
        record(rec, 1, 0, 23)
        join(rec.stopDrive())
        thumbnail(c, h, a, expect("A", 0..22))
        complete(c, "DB A", db.driveDao().getPoints(a).map(::key), expect("A", 0..22))
        h.trace.forEach { c.note(it) }
        c.done()
    }

    // ── Control 2: export completes before STOP (no overlap) ────────────────
    @Test fun control_exportBeforeStop() {
        val c = Checks("control: export before STOP")
        val h = TracingDao(db.driveDao()); val rec = recorder(h.dao)
        val a = start(rec)
        record(rec, 1, 0, 20)
        val snap = exportAsync(rec)()
        complete(c, "export", snap?.second.orEmpty().map(::key), expect("A", 0..19))
        record(rec, 1, 20, 3)
        join(rec.stopDrive())
        thumbnail(c, h, a, expect("A", 0..22))
        complete(c, "DB A", db.driveDao().getPoints(a).map(::key), expect("A", 0..22))
        h.trace.forEach { c.note(it) }
        c.done()
    }

    // ── Regression: export's point insert in flight across STOP A (→ START B) ──
    @Test fun regression_exportInsertInFlightAtStop() {
        val c = Checks("regression: export insert in flight at STOP")
        val h = TracingDao(db.driveDao()); val rec = recorder(h.dao)
        val a = start(rec)
        record(rec, 1, 0, 20)                                      // A0–19 buffered
        h.hold = "export"
        val export = exportAsync(rec)
        c.check("export took A0–19 from the buffer and is held in its insert", h.entered.await(10, TimeUnit.SECONDS))
        record(rec, 1, 20, 3)                                      // A20–22 (before STOP)
        val stop = rec.stopDrive()
        // Before #241 the finalize completed here without waiting for the export's insert (thumbnail from A20–22)
        c.check("finalize waits for the export's insert (not done while it is held)", !finishesWithin(stop))
        val b = start(rec, a)                                      // B starts while A's finalize waits — B is not held
        record(rec, 2, 0, 5)
        h.release.countDown()
        join(stop)
        val snap = export()
        thumbnail(c, h, a, expect("A", 0..22))
        complete(c, "export of A", snap?.second.orEmpty().map(::key), expect("A", 0..22))
        complete(c, "DB A", db.driveDao().getPoints(a).map(::key), expect("A", 0..22))
        join(rec.stopDrive())
        complete(c, "DB B", db.driveDao().getPoints(b).map(::key), expect("B", 0..4))
        thumbnail(c, h, b, expect("B", 0..4))
        h.trace.forEach { c.note(it) }
        c.done()
    }

    // ── The in-flight insert FAILS: STOP still finishes, the export fails, the thumbnail = what Room holds ──
    @Test fun inFlightExportInsertFails() {
        val c = Checks("export insert fails at STOP")
        ShadowLog.clear()
        val h = TracingDao(db.driveDao()); val rec = recorder(h.dao)
        val a = start(rec)
        record(rec, 1, 0, 20)
        h.hold = "export"; h.failHeld = true
        val export = exportAsync(rec)
        c.check("export took A0–19 and is held in its insert", h.entered.await(10, TimeUnit.SECONDS))
        record(rec, 1, 20, 3)
        val stop = rec.stopDrive()
        c.check("finalize waits for the export's insert", !finishesWithin(stop))
        h.release.countDown()
        c.check("STOP finishes after the insert failed", finishesWithin(stop, 30_000))
        c.check("the export reports failure (null), not a snapshot", export() == null)
        val saved = db.driveDao().getPoints(a).map(::key)
        complete(c, "DB A = the points that were written", saved, expect("A", 20..22))
        thumbnail(c, h, a, expect("A", 20..22))
        val warned = ShadowLog.getLogsForTag("DriveRecorder").any { it.msg.contains("failed point write") }
        c.check("STOP reports the failed point write", warned)
        h.trace.forEach { c.note(it) }
        c.done()
    }

    // ── The export's caller is CANCELLED while its insert runs: the insert lands, STOP finishes ─────────────
    @Test fun inFlightExportCancelled() {
        val c = Checks("export cancelled at STOP")
        val h = TracingDao(db.driveDao()); val rec = recorder(h.dao)
        val a = start(rec)
        record(rec, 1, 0, 20)
        h.hold = "export"
        val export = CoroutineScope(Dispatchers.IO).async { rec.snapshotActiveDrive() }
        c.check("export took A0–19 and is held in its insert", h.entered.await(10, TimeUnit.SECONDS))
        record(rec, 1, 20, 3)
        val stop = rec.stopDrive()
        export.cancel()
        c.check("finalize waits for the cancelled export's insert", !finishesWithin(stop))
        h.release.countDown()
        c.check("STOP finishes", finishesWithin(stop, 30_000))
        runBlocking { withTimeout(30_000) { export.join() } }
        c.check("export ended cancelled", export.isCancelled)
        complete(c, "DB A", db.driveDao().getPoints(a).map(::key), expect("A", 0..22))
        thumbnail(c, h, a, expect("A", 0..22))
        h.trace.forEach { c.note(it) }
        c.done()
    }

    // ── A pause flush in flight at STOP (pause → resume → STOP): same wait, not export-specific ─────────────
    @Test fun pauseFlushInFlightAtStop() {
        val c = Checks("pause flush in flight at STOP")
        val h = TracingDao(db.driveDao()); val rec = recorder(h.dao)
        val a = start(rec)
        record(rec, 1, 0, 20)                                      // fewer than a full batch: only the pause flushes
        h.hold = "flush"
        rec.pauseDrive()
        c.check("pause flush took A0–19 and is held in its insert", h.entered.await(10, TimeUnit.SECONDS))
        rec.resumeDrive()
        record(rec, 1, 20, 3)
        val stop = rec.stopDrive()
        c.check("finalize waits for the pause flush", !finishesWithin(stop))
        h.release.countDown()
        join(stop)
        complete(c, "DB A", db.driveDao().getPoints(a).map(::key), expect("A", 0..22))
        thumbnail(c, h, a, expect("A", 0..22))
        h.trace.forEach { c.note(it) }
        c.done()
    }

    companion object {
        fun key(p: DrivePointEntity) = "${if (p.lng.toInt() == 1) "A" else if (p.lng.toInt() == 2) "B" else "?"}${((p.lat - 10.0) / 1e-4).roundToInt()}"
        fun keys(ps: List<*>) = ps.map { key(it as DrivePointEntity) }
    }
}
