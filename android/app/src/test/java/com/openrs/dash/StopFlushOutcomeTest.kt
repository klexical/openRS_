package com.openrs.dash

import android.Manifest
import android.app.Application
import android.database.sqlite.SQLiteFullException
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * STOP's point-write report (#241 follow-up) on the production DriveRecorder + Room (Robolectric SQLite). A point flush
 * runs its insert under NonCancellable; when its caller is cancelled meanwhile (STOP cancels the loop, an export's caller
 * goes away) the cancellation arrives as the IO block returns — after the insert already landed. That must not be
 * counted or reported as a failed write, the points must be stored once, and cancellation still ends the caller. A DAO
 * write that genuinely fails must still be counted, logged and reported at STOP (REC event in the diagnostic export).
 * Built from QA's reproducer (QaStopFlushWarningTest, 7 / 7 points stored while "not saved" was reported).
 */
@RunWith(RobolectricTestRunner::class)
class StopFlushOutcomeTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /**
     * The production DAO. [holdNext] (when set) holds the next insertPoints from a caller whose stack contains it
     * ("snapshotActiveDrive"), [holdBatchOf] the next insertPoints of exactly that many points (the loop's 30-point
     * batch), until [release]; [failInserts] makes that many insertPoints calls throw SQLiteFullException instead of
     * writing (a genuine failure — nothing is stored).
     */
    private class ScriptedDao(real: DriveDao) {
        @Volatile var holdNext: String? = null
        @Volatile var holdBatchOf: Int? = null
        val failInserts = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inserted = AtomicInteger()
        val dao: DriveDao = Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            if (m.name == "insertPoints") {
                val h = holdNext
                val byCaller = h != null && Thread.currentThread().stackTrace.any { it.methodName.contains(h) || it.className.contains(h) }
                val byBatch = holdBatchOf == (args!![0] as List<*>).size
                if (byCaller || byBatch) {
                    holdNext = null; holdBatchOf = null
                    entered.countDown(); release.await(30, TimeUnit.SECONDS)
                }
                if (failInserts.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw SQLiteFullException("test: insert fails")
                inserted.addAndGet((args!![0] as List<*>).size)
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
    private val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 3000.0, speedKph = 80.0))
    private val gps = FakeGps()
    private val recorders = mutableListOf<DriveRecorder>()

    @Before fun setUp() {
        ShadowLog.clear()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)        // the export's REC events start empty
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
    }

    @After fun tearDown() { recorders.forEach { it.cancel() }; db.close() }

    private fun recorder(dao: DriveDao) =
        DriveRecorder(app, vs, WeatherRepository(""), db, dao, { _, _ -> null }, gps, {}).also { recorders += it }

    private fun waitUntil(ms: Long = 5_000, c: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (c()) return true; Thread.sleep(5) }
        return c()
    }

    private fun start(rec: DriveRecorder): Long {
        val opened = gps.opened.get()
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > opened })
        return rec.driveState.value.driveId
    }

    private fun send(i: Int) = gps.current!!.trySend(Location("t").apply {
        latitude = 50.0 + i * 1e-4; longitude = 8.0; speed = 22f; time = System.currentTimeMillis()
    })

    /** [n] fixes through the real loop, each waited for. */
    private fun record(rec: DriveRecorder, from: Int, n: Int) {
        for (i in from until from + n) {
            val before = rec.driveState.value.totalPointCount
            send(i)
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
    }

    private fun stop(rec: DriveRecorder) = runBlocking { withTimeout(30_000) { rec.stopDrive().join() } }

    private fun logs() = ShadowLog.getLogsForTag("DriveRecorder").map { it.msg }
    private fun flushFailedLogs() = logs().count { it.startsWith("Point flush failed") }
    private fun notSavedLogs() = logs().filter { it.contains("failed point write") }
    private fun recFailures() = DiagnosticLogger.sessionEvents.filter { it.type == "REC" && it.message.contains("failed") }
    /** The diagnostic export's own text (SESSION EVENTS section). */
    private fun exportSaysFailed() = DiagnosticReportBuilder.buildSummaryText("t").contains("point write(s) failed")
    private fun stored(id: Long): List<DrivePointEntity> = db.driveDao().getPoints(id)

    private fun Checks.noFailureReported() {
        check("no \"Point flush failed\" log", flushFailedLogs() == 0, logs())
        check("no \"failed point write\" warning at STOP", notSavedLogs().isEmpty(), notSavedLogs())
        check("no failure REC event", recFailures().isEmpty(), recFailures())
        check("diagnostic export text reports no failed write", !exportSaysFailed())
    }

    private fun Checks.storedOnce(id: Long, n: Int) {
        val pts = stored(id)
        check("$n points stored", pts.size == n, pts.size)
        check("each point stored once", pts.map { it.timestamp to it.lat }.toSet().size == pts.size, pts.size)
    }

    // ── 1. Insert completes, then cancellation ────────────────────────────────────────────────────────────────

    @Test fun `STOP final flush - the insert lands and is not reported as failed`() {
        val c = Checks("final flush at STOP")
        val rec = recorder(db.driveDao())
        val id = start(rec)
        record(rec, 0, 7)                                         // < 30: every point is in STOP's final flush
        stop(rec)
        c.storedOnce(id, 7)
        c.noFailureReported()
        c.check("recording ended", !rec.driveState.value.isRecording)
        c.done()
    }

    @Test fun `loop batch flush in flight when STOP cancels - lands once, the point completes, nothing reported`() {
        val c = Checks("batch flush at STOP")
        val h = ScriptedDao(db.driveDao())
        val rec = recorder(h.dao)
        val id = start(rec)
        record(rec, 0, 29)
        h.holdBatchOf = 30                                         // the 30th point's batch flush (FLUSH_SIZE)
        send(29)
        c.check("batch flush held in its insert", h.entered.await(10, TimeUnit.SECONDS))
        val job = rec.stopDrive()                                  // cancels the loop while the insert runs
        h.release.countDown()
        runBlocking { withTimeout(30_000) { job.join() } }
        c.storedOnce(id, 30)
        c.check("the in-flight point completed (count 30)", rec.driveState.value.totalPointCount == 30,
            rec.driveState.value.totalPointCount)
        c.check("recording ended", !rec.driveState.value.isRecording)
        c.noFailureReported()
        c.done()
    }

    @Test fun `export cancelled while its insert lands - stored once, not counted, cancellation ends the export`() {
        val c = Checks("export cancelled")
        val h = ScriptedDao(db.driveDao())
        val rec = recorder(h.dao)
        val id = start(rec)
        record(rec, 0, 5)
        h.holdNext = "snapshotActiveDrive"
        val export = CoroutineScope(Dispatchers.IO).async { rec.snapshotActiveDrive() }
        c.check("export held in its insert", h.entered.await(10, TimeUnit.SECONDS))
        export.cancel()
        h.release.countDown()
        runBlocking { withTimeout(30_000) { export.join() } }
        c.check("export ended cancelled", export.isCancelled)
        c.check("no \"snapshotActiveDrive failed\" log", logs().none { it.startsWith("snapshotActiveDrive failed") }, logs())
        record(rec, 5, 2)
        stop(rec)
        c.storedOnce(id, 7)
        c.noFailureReported()
        c.done()
    }

    // ── 2. The DAO write genuinely fails ──────────────────────────────────────────────────────────────────────

    @Test fun `STOP final flush fails - nothing stored, one failed write logged and reported`() {
        val c = Checks("final flush fails")
        val h = ScriptedDao(db.driveDao())
        val rec = recorder(h.dao)
        val id = start(rec)
        record(rec, 0, 5)
        h.failInserts.set(1)
        stop(rec)
        c.check("no phantom points", stored(id).isEmpty(), stored(id).size)
        c.check("one \"Point flush failed (5 points)\" log", logs().count { it == "Point flush failed (5 points)" } == 1, logs())
        c.check("STOP warns 1 failed point write", notSavedLogs().singleOrNull()?.contains("1 failed point write") == true, notSavedLogs())
        c.check("REC event says 1 write failed", recFailures().singleOrNull()?.message?.contains("1 point write(s) failed") == true, recFailures())
        c.check("diagnostic export text reports it", exportSaysFailed())
        c.done()
    }

    @Test fun `a batch flush fails mid-drive, STOP's flush succeeds - only the stored points, the failure counted once`() {
        val c = Checks("batch fails, final ok")
        val h = ScriptedDao(db.driveDao())
        val rec = recorder(h.dao)
        val id = start(rec)
        h.failInserts.set(1)
        record(rec, 0, 30)                                         // the 30-point batch fails
        record(rec, 30, 3)
        stop(rec)
        c.storedOnce(id, 3)
        c.check("one \"Point flush failed (30 points)\" log", logs().count { it == "Point flush failed (30 points)" } == 1, logs())
        c.check("REC event says 1 write failed", recFailures().singleOrNull()?.message?.contains("1 point write(s) failed") == true, recFailures())
        c.done()
    }
}
