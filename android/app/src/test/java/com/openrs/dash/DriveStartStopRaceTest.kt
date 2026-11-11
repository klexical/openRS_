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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * A STOP (or a second START) that arrives while a drive is still being created. The drive's job used to be assigned
 * on an IO thread only after its row was inserted and its loop launched, so such a STOP neither cancelled nor joined
 * anything: the drive went on recording after STOP, and a later START was refused. Seen once in CI as
 * StopFlushOutcomeTest losing its final flush unreported. Now the job is assigned in startDrive, before it runs.
 *
 * Production DriveRecorder + Room (Robolectric); the DAO is the real one behind a proxy that can hold one call, so
 * each window is reached deterministically (no sleeps decide the order). Synthetic GPS through the LocationSource seam.
 */
@RunWith(RobolectricTestRunner::class)
class DriveStartStopRaceTest {

    private class FakeGps : DriveRecorder.LocationSource {
        val opened = AtomicInteger()
        @Volatile var current: Channel<Location>? = null
        /** Runs on the loop's thread as the loop asks for GPS (the moment the loop is running). */
        @Volatile var onOpen: (() -> Unit)? = null
        override fun updates(): Flow<Location> {
            onOpen?.invoke()
            return Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        }
        override suspend fun last(): Location? = null
    }

    /** One held DAO call: the [nth] call of [method] is held [before] or after the real call. */
    private class Hold(val method: String, val before: Boolean, val nth: Int = 1) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val used = AtomicInteger()
        fun pause() { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "hold never released" } }
    }

    private fun heldDao(real: DriveDao, hold: Hold): DriveDao =
        Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            val mine = m.name == hold.method && hold.used.incrementAndGet() == hold.nth
            if (mine && hold.before) hold.pause()
            val result = try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
            if (mine && !hold.before) hold.pause()
            result
        } as DriveDao

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 2_400.0, speedKph = 50.0))
    private val gps = FakeGps()
    private val recorders = mutableListOf<DriveRecorder>()
    private val dao get() = db.driveDao()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
    }

    @After fun tearDown() { recorders.forEach { it.cancel() }; db.close() }

    private val summaries = AtomicInteger()
    private fun recorder(hold: Hold) =
        DriveRecorder(app, vs, WeatherRepository(""), db, heldDao(dao, hold), { _, _ -> null }, gps, { summaries.incrementAndGet() })
            .also { recorders += it }

    /** Drive 1: started, one fix, stopped and finalized (no holds reached). */
    private fun firstDrive(rec: DriveRecorder): com.openrs.dash.data.DriveEntity {
        rec.startDrive()
        check(waitUntil { rec.driveState.value.isRecording && gps.opened.get() == 1 })
        gps.current!!.trySend(Location("t").apply { latitude = 10.0; longitude = 20.0; time = System.currentTimeMillis() })
        check(waitUntil { rec.driveState.value.totalPointCount == 1 })
        join(rec.stopDrive())
        return dao.getRecentDrives().single().also { check(it.endTime > 0 && summaries.get() == 1) }
    }

    private fun waitUntil(ms: Long = 5_000, c: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (c()) return true; Thread.sleep(5) }
        return c()
    }

    private fun join(job: kotlinx.coroutines.Job) = runBlocking { withTimeout(10_000) { job.join() } }

    /** Whatever the recorder does on its own after the race: given time to go wrong (a drive that keeps going would). */
    private fun settled(rec: DriveRecorder) { waitUntil(1_000) { rec.driveState.value.isRecording }; Thread.sleep(200) }

    private fun closedRows() = dao.getRecentDrives().filter { it.endTime > 0 }
    private fun liveRows() = dao.getUnfinishedDrives()
    private fun recEvents() = DiagnosticLogger.sessionEvents.filter { it.type == "REC" && it.message.contains("start cancelled by STOP") }

    // (a) STOP during the insert: the drive ends, nothing listens to GPS, a later START is accepted
    @Test fun `STOP while the new drive's row is being written ends the drive - a later START is accepted`() {
        val hold = Hold("insertDrive", before = true)
        val rec = recorder(hold)
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        val stop = rec.stopDrive()
        hold.release.countDown()
        join(stop)
        settled(rec)
        assertFalse("not recording after STOP", rec.driveState.value.isRecording)
        assertEquals("no GPS listener was opened for the stopped drive", 0, gps.opened.get())
        assertEquals("the cancelled start leaves no row (no empty drive, nothing for the next app start to close)", 0, dao.getDriveCount())
        assertEquals(1, recEvents().size)
        assertEquals("nothing finalized", 0, summaries.get())

        rec.startDrive()
        assertTrue("a later START is accepted", waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } })
        assertTrue(waitUntil { gps.opened.get() == 1 })
        assertEquals(1, dao.getDriveCount())
    }

    // (c) STOP before the insert: no row, no finalize, no GPS listener
    @Test fun `STOP before the row is written leaves no drive at all`() {
        val hold = Hold("getDriveCount", before = true)         // the prune, the job's first step
        val rec = recorder(hold)
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        val stop = rec.stopDrive()
        hold.release.countDown()
        join(stop)
        settled(rec)
        assertFalse("not recording after STOP", rec.driveState.value.isRecording)
        assertEquals("no row", 0, dao.getDriveCount())
        assertEquals(0, gps.opened.get())
        assertEquals("nothing to close", listOf<Any>(), recEvents())
    }

    // (d) STOP after the insert, before the drive's state: the row is removed, nothing finalized
    @Test fun `STOP after the row is written but before recording starts removes that row - nothing finalized`() {
        val hold = Hold("insertDrive", before = false)
        val rec = recorder(hold)
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))   // the row exists, the insert has not returned
        val stop = rec.stopDrive()
        hold.release.countDown()
        join(stop)
        settled(rec)
        assertFalse("not recording after STOP", rec.driveState.value.isRecording)
        assertEquals(0, gps.opened.get())
        assertEquals("no row", 0, dao.getDriveCount())
        assertEquals("one REC event", 1, recEvents().size)
        assertEquals("nothing finalized", 0, summaries.get())
    }

    // (e) START, STOP, START in quick succession: exactly one live drive, the first one closed
    @Test fun `START, STOP, START during the first drive's creation gives exactly one live drive`() {
        val hold = Hold("insertDrive", before = true)
        val rec = recorder(hold)
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        val stop = rec.stopDrive()
        rec.startDrive()                                         // the second drive's insert is not held
        hold.release.countDown()
        join(stop)
        settled(rec)
        val live = liveRows()
        assertEquals("one live drive", 1, live.size)
        assertEquals("it is the one recording", live.single().id, rec.driveState.value.driveId)
        assertTrue(rec.driveState.value.isRecording)
        assertEquals("one GPS listener", 1, gps.opened.get())
        assertEquals("the cancelled first start left no row", 1, dao.getDriveCount())
    }

    // A second START while the first is still being created (a double tap, or auto-record and a tap) is ignored
    @Test fun `a second START during the first drive's creation is ignored - one drive`() {
        val hold = Hold("insertDrive", before = true)
        val rec = recorder(hold)
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        rec.startDrive()
        hold.release.countDown()
        assertTrue(waitUntil { rec.driveState.value.isRecording })
        Thread.sleep(200)
        assertEquals("one drive row", 1, dao.getDriveCount())
        assertEquals("one GPS listener", 1, gps.opened.get())
    }

    // L2 (QA): the start is held inside its "recording" state update, before the loop; STOP; release
    @Test fun `STOP while the start publishes its recording state - no loop starts afterwards, a later START works`() {
        val rec = recorder(Hold("none", before = true))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val held = java.util.concurrent.atomic.AtomicBoolean()
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            rec.driveState.collect { if (it.isRecording && held.compareAndSet(false, true)) { entered.countDown(); release.await(10, TimeUnit.SECONDS) } }
        }
        try {
            rec.startDrive()
            assertTrue(entered.await(5, TimeUnit.SECONDS))      // the start's thread is inside the state update
            val stop = rec.stopDrive()
            release.countDown()
            join(stop)
            settled(rec)
            assertFalse("not recording after STOP", rec.driveState.value.isRecording)
            assertEquals("no loop, no GPS listener", 0, gps.opened.get())
            assertEquals("the drive that showed as recording is finalized as an ordinary STOP", 1, closedRows().size)
            rec.startDrive()
            assertTrue("a later START is accepted", waitUntil { rec.driveState.value.isRecording && gps.opened.get() == 1 })
        } finally { watcher.cancel() }
    }

    // L4 (QA): drive 1 finished; drive 2's insert held; STOP; release
    @Test fun `STOP during the next drive's insert never touches the previous drive`() {
        val hold = Hold("insertDrive", before = true, nth = 2)
        val rec = recorder(hold)
        val first = firstDrive(rec)
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        val stop = rec.stopDrive()
        hold.release.countDown()
        join(stop)
        settled(rec)
        assertEquals("drive 1 is unchanged", first, dao.getDrive(first.id))
        assertEquals("drive 1's summary is not posted again", 1, summaries.get())
        assertEquals("drive 2 left no row", 1, dao.getDriveCount())
        assertFalse(rec.driveState.value.isRecording)
    }

    // The same before the insert: drive 2 is held in its prune
    @Test fun `STOP during the next drive's prune never touches the previous drive`() {
        val hold = Hold("getDriveCount", before = true, nth = 2)
        val rec = recorder(hold)
        val first = firstDrive(rec)
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        val stop = rec.stopDrive()
        hold.release.countDown()
        join(stop)
        settled(rec)
        assertEquals(first, dao.getDrive(first.id))
        assertEquals(1, summaries.get())
        assertEquals(1, dao.getDriveCount())
    }

    @Test fun `a second STOP does nothing - the drive it ended is not rewritten`() {
        val rec = recorder(Hold("none", before = true))
        val first = firstDrive(rec)
        Thread.sleep(5)
        join(rec.stopDrive())
        assertEquals(first, dao.getDrive(first.id))
        assertEquals(1, summaries.get())
    }

    // The CI window (loop running, its job not yet known to STOP) cannot exist when the job is set before it runs.
    // Pin: a STOP issued on the loop's own thread as it opens GPS ends the drive and runs its finalize.
    @Test fun `a STOP at the moment the loop starts ends the drive and finalizes it`() {
        val rec = recorder(Hold("none", before = true))
        val stopped = CountDownLatch(1)
        var stop: kotlinx.coroutines.Job? = null
        gps.onOpen = { gps.onOpen = null; stop = rec.stopDrive(); stopped.countDown() }
        rec.startDrive()
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        join(stop!!)
        settled(rec)
        assertFalse(rec.driveState.value.isRecording)
        assertEquals("finalized", 1, summaries.get())
        assertEquals(1, closedRows().size)
        assertEquals(listOf<Any>(), liveRows())
    }
}
