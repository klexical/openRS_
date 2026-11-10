package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import com.openrs.dash.testing.HookedPointBuffer
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * #238 review finding 2 — the recorder's point buffer is shared by the drive's loop (GPS add, 30-point batch drain, the
 * final drain at STOP) and the drains from outside the loop (active export, pause flush). The #241 lock
 * (`outsideLoopWrites`) orders the outside drains against each other and against STOP's finalize; the loop takes no
 * lock. A take is "copy, then clear": a point added between the two is cleared without being stored, and two takes that
 * both copy before either clears store the same points twice. The race predates rc.7 (rc.6 had the same unlocked
 * copy / clear in `snapshotActiveDrive` and the loop).
 *
 * Deterministic interleaving: [HookedPointBuffer] replaces the run's buffer (reflection; production code unchanged) and
 * holds the export's take between its copy and its clear until the other side has acted — bounded (1 s), so a fix that
 * makes the take atomic proceeds instead of deadlocking. Production DriveRecorder + Room (Robolectric). Every fix must
 * be stored exactly once, in its drive, with STOP's ordering unchanged (finalize after the loop and the export).
 */
@RunWith(RobolectricTestRunner::class)
class PointBufferRaceTest {
    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** The production DAO; counts down [otherInsert] for an insertPoints from any thread but [exportThread]. */
    private class WatchedDao(real: DriveDao) {
        @Volatile var exportThread: Thread? = null
        @Volatile var otherInsert = CountDownLatch(1)
        val dao: DriveDao = Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            val r = try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
            if (m.name == "insertPoints" && Thread.currentThread() !== exportThread) otherInsert.countDown()
            r
        } as DriveDao
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 3000.0, speedKph = 80.0))
    private val gps = FakeGps()
    private var rec: DriveRecorder? = null

    @Before fun setUp() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
    }

    @After fun tearDown() { rec?.cancel(); db.close() }

    private fun waitUntil(ms: Long = 5_000, c: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (c()) return true; Thread.sleep(5) }
        return c()
    }

    /** Started drive with [HookedPointBuffer] installed as its point buffer. */
    private fun startHooked(w: WatchedDao): Triple<DriveRecorder, Long, HookedPointBuffer> {
        val r = DriveRecorder(app, vs, WeatherRepository(""), db, w.dao, { _, _ -> null }, gps, {}).also { rec = it }
        r.startDrive()
        check(waitUntil { r.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 })
        val runField = DriveRecorder::class.java.getDeclaredField("run").apply { isAccessible = true }
        val run = runField.get(r)
        val buf = HookedPointBuffer()
        run.javaClass.getDeclaredField("pointsBuffer").apply { isAccessible = true }.set(run, buf)
        return Triple(r, r.driveState.value.driveId, buf)
    }

    private fun send(i: Int) = gps.current!!.trySend(Location("t").apply {
        latitude = 50.0 + i * 1e-4; longitude = 8.0; speed = 22f; time = System.currentTimeMillis()
    })

    private fun record(r: DriveRecorder, from: Int, n: Int) {
        for (i in from until from + n) {
            val before = r.driveState.value.totalPointCount
            send(i)
            check(waitUntil { r.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
    }

    /** Fix index of a stored point (from its latitude). */
    private fun fixIndex(lat: Double) = ((lat - 50.0) / 1e-4).roundToInt()

    /** Every fix 0 until [n] exactly once in drive [id], nothing else; "" when so, else what is wrong. */
    private fun exactlyOnce(id: Long, n: Int): String {
        val pts = db.driveDao().getPoints(id)
        val counts = pts.groupingBy { fixIndex(it.lat) }.eachCount()
        val missing = (0 until n).filter { (counts[it] ?: 0) == 0 }
        val dup = counts.filter { it.value > 1 }
        val wrongDrive = pts.count { it.driveId != id }
        return if (missing.isEmpty() && dup.isEmpty() && pts.size == n && wrongDrive == 0) ""
            else "stored=${pts.size} of $n, missing=$missing, duplicated=$dup, wrongDrive=$wrongDrive"
    }

    /** Runs the export on its own thread; returns it once the export's take has copied (and is held). */
    private fun exportHeldAfterCopy(r: DriveRecorder, w: WatchedDao, buf: HookedPointBuffer, holdFor: CountDownLatch,
                                    heldResult: (Boolean) -> Unit): Thread {
        val copied = CountDownLatch(1)
        buf.onTake = Runnable { copied.countDown(); heldResult(holdFor.await(1, TimeUnit.SECONDS)) }
        buf.armTake.set(true)
        val t = thread(name = "export") { runBlocking { r.snapshotActiveDrive() } }
        w.exportThread = t
        check(copied.await(5, TimeUnit.SECONDS)) { "export never took the buffer" }
        return t
    }

    private fun stop(r: DriveRecorder) = runBlocking { withTimeout(15_000) { r.stopDrive().join() } }

    @Test fun `a fix added while an export takes the buffer is stored once`() {
        val w = WatchedDao(db.driveDao())
        val (r, id, buf) = startHooked(w)
        record(r, 0, 7)
        val added = CountDownLatch(1)
        var addDuringTake = false
        val export = exportHeldAfterCopy(r, w, buf, added) { addDuringTake = it }
        buf.onAdd = Runnable { added.countDown() }
        send(7)                                                         // GPS producer → loop add, mid-take
        export.join(10_000)
        check(waitUntil { r.driveState.value.totalPointCount == 8 }) { "fix 7 not recorded by the loop" }
        buf.onAdd = null
        stop(r)
        val verdict = exactlyOnce(id, 8)
        println("CASE export-take-vs-add: addDuringTake=$addDuringTake ${verdict.ifEmpty { "exactly once" }}")
        assertTrue(verdict, verdict.isEmpty())
    }

    @Test fun `the loop's batch drain during an export's take stores each fix once`() {
        val w = WatchedDao(db.driveDao())
        val (r, id, buf) = startHooked(w)
        record(r, 0, 29)
        w.otherInsert = CountDownLatch(1)
        var drainedDuringTake = false
        val export = exportHeldAfterCopy(r, w, buf, w.otherInsert) { drainedDuringTake = it }
        send(29)                                                        // 30th point → the loop's batch drain
        export.join(10_000)
        check(waitUntil { r.driveState.value.totalPointCount == 30 }) { "fix 29 not recorded by the loop" }
        stop(r)
        val verdict = exactlyOnce(id, 30)
        println("CASE export-take-vs-batch: drainedDuringTake=$drainedDuringTake ${verdict.ifEmpty { "exactly once" }}")
        assertTrue(verdict, verdict.isEmpty())
    }

    @Test fun `STOP's final drain during an export's take stores each fix once, finalize after both`() {
        val w = WatchedDao(db.driveDao())
        val (r, id, buf) = startHooked(w)
        record(r, 0, 7)
        w.otherInsert = CountDownLatch(1)
        var drainedDuringTake = false
        val export = exportHeldAfterCopy(r, w, buf, w.otherInsert) { drainedDuringTake = it }
        val finalize = r.stopDrive()                                    // cancels the loop → its final drain
        export.join(10_000)
        runBlocking { withTimeout(15_000) { finalize.join() } }
        val verdict = exactlyOnce(id, 7)
        val drive = db.driveDao().getDrive(id)
        println("CASE export-take-vs-final-drain: drainedDuringTake=$drainedDuringTake ${verdict.ifEmpty { "exactly once" }}")
        assertTrue(verdict, verdict.isEmpty())
        assertTrue("drive finalized after the drains (endTime ${drive?.endTime})", (drive?.endTime ?: 0L) > 0L)
    }
}
