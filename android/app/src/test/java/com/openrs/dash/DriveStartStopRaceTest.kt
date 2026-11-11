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
        override fun updates(): Flow<Location> = Channel<Location>(Channel.UNLIMITED).also { opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** One held DAO call: [method] is held [before] or after the real call, the first time only. */
    private class Hold(val method: String, val before: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val used = AtomicInteger()
        fun pause() { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "hold never released" } }
    }

    private fun heldDao(real: DriveDao, hold: Hold): DriveDao =
        Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            val mine = m.name == hold.method && hold.used.getAndIncrement() == 0
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

    private fun recorder(hold: Hold) =
        DriveRecorder(app, vs, WeatherRepository(""), db, heldDao(dao, hold), { _, _ -> null }, gps, { }).also { recorders += it }

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
    private fun recEvents() = DiagnosticLogger.sessionEvents.filter { it.type == "REC" && it.message.contains("stopped before recording started") }

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
        assertEquals("its row is closed, none left open", listOf<Any>(), liveRows())
        val first = closedRows().single().id

        rec.startDrive()
        assertTrue("a later START is accepted", waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } })
        assertNotEquals(first, rec.driveState.value.driveId)
        assertTrue(waitUntil { gps.opened.get() == 1 })
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

    // (d) STOP after the insert, before the drive's state: the row is closed from what Room holds, once
    @Test fun `STOP after the row is written but before recording starts closes that row once`() {
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
        val row = dao.getRecentDrives().single()
        assertEquals("closed with no points: end = start", row.startTime, row.endTime)
        assertEquals(listOf<Any>(), liveRows())
        assertEquals("closed once", 1, recEvents().size)
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
        assertEquals("the first drive is closed", 1, closedRows().size)
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
}
