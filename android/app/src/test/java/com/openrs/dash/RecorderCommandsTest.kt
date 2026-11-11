package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.Refusal
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
 * #316, the Commands table (SPEC-316, Implementation Decisions), at seam 1: commands go in, the command result and the
 * drive state come out, saved drives are read from the in-memory database handed to the constructor.
 *
 * | Command | Refused when | Otherwise |
 * | START | demo mode; a drive is open (recording or paused); a start is under way | starts, also while the previous drive is still being saved |
 * | PAUSE | no drive is recording (a start under way, an already paused drive) | hand pause |
 * | RESUME | no drive is paused | recording |
 * | STOP | never | stops the open drive, or cancels the start; nothing if neither exists |
 *
 * The demo-mode refusal is tested with the "demo mode entered" event. A start is held open by holding the DAO's insert.
 */
@RunWith(RobolectricTestRunner::class)
class RecorderCommandsTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> = Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    /** Holds the first call of [method] before the real call, until [release]. */
    private class Hold(val method: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val used = AtomicInteger()
    }

    private fun dao(real: DriveDao, hold: Hold?): DriveDao =
        Proxy.newProxyInstance(DriveDao::class.java.classLoader, arrayOf(DriveDao::class.java)) { _, m, args ->
            if (hold != null && m.name == hold.method && hold.used.incrementAndGet() == 1) {
                hold.entered.countDown(); check(hold.release.await(10, TimeUnit.SECONDS))
            }
            try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as DriveDao

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 2_000.0, speedKph = 40.0))
    private val gps = FakeGps()
    private val recorders = mutableListOf<DriveRecorder>()
    private var now = 1_000_000L

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
    }

    @After fun tearDown() { recorders.forEach { it.cancel() }; db.close() }

    private fun recorder(hold: Hold? = null) =
        DriveRecorder(app, vs, WeatherRepository(""), db, dao(db.driveDao(), hold), { _, _ -> null }, gps, { }, clock = { now })
            .also { recorders += it }

    private fun waitUntil(ms: Long = 5_000, c: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (c()) return true; Thread.sleep(5) }
        return c()
    }

    private fun DriveRecorder.phase() = driveState.value.phase
    private fun DriveRecorder.recording(): DriveRecorder { assertEquals(CommandResult.Done, startDrive()); check(waitUntil { phase() == DrivePhase.RECORDING }); return this }
    private fun join(job: kotlinx.coroutines.Job) = runBlocking { withTimeout(10_000) { job.join() } }
    private fun refused(r: Refusal) = CommandResult.Refused(r)

    // ── START ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `START from idle is done - the phase is starting at once, then recording`() {
        val hold = Hold("insertDrive")
        val rec = recorder(hold)
        assertEquals(CommandResult.Done, rec.startDrive())
        assertEquals("starting before the row exists", DrivePhase.STARTING, rec.phase())
        assertFalse("a start is not an open drive (the recording flag keeps its meaning)", rec.driveState.value.isRecording)
        hold.release.countDown()
        assertTrue(waitUntil { rec.phase() == DrivePhase.RECORDING })
        assertTrue(rec.driveState.value.isRecording)
    }

    @Test fun `START while a start is under way is refused - starting`() {
        val hold = Hold("insertDrive")
        val rec = recorder(hold)
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertEquals(refused(Refusal.STARTING), rec.startDrive())
        hold.release.countDown()
        assertTrue(waitUntil { rec.phase() == DrivePhase.RECORDING })
        assertEquals("one drive", 1, db.driveDao().getDriveCount())
    }

    @Test fun `START while a drive is recording or paused is refused - a drive is open`() {
        val rec = recorder().recording()
        assertEquals(refused(Refusal.DRIVE_OPEN), rec.startDrive())
        rec.pauseDrive()
        assertEquals(refused(Refusal.DRIVE_OPEN), rec.startDrive())
        assertEquals(1, db.driveDao().getDriveCount())
    }

    @Test fun `START is allowed while the previous drive is still being saved`() {
        // Held after the save's database transaction (its thumbnail step reads the points): a hold inside the transaction
        // would keep every database write waiting, the new drive's row included
        val hold = Hold("getPoints")
        val rec = recorder(hold).recording()
        val first = rec.driveState.value.driveId
        val save = rec.stopDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))   // drive 1 is being saved
        assertTrue(rec.isFinalizing)
        assertEquals(CommandResult.Done, rec.startDrive())
        assertTrue(waitUntil { rec.phase() == DrivePhase.RECORDING && rec.driveState.value.driveId != first })
        hold.release.countDown()
        join(save)
        assertTrue("drive 1 saved", db.driveDao().getDrive(first)!!.endTime > 0)
    }

    @Test fun `a drive's times come from the clock handed to the recorder`() {
        now = 5_000_000L
        val rec = recorder().recording()
        assertEquals(5_000_000L, db.driveDao().getDrive(rec.driveState.value.driveId)!!.startTime)
        now = 5_090_000L
        val id = rec.driveState.value.driveId
        join(rec.stopDrive())
        assertEquals(5_090_000L, db.driveDao().getDrive(id)!!.endTime)
    }

    // ── PAUSE ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `PAUSE on a recording drive is done - a pause`() {
        val rec = recorder().recording()
        assertEquals(CommandResult.Done, rec.pauseDrive())
        assertEquals(DrivePhase.PAUSED, rec.phase())
        assertTrue("a paused drive is still open", rec.driveState.value.isRecording)
    }

    @Test fun `PAUSE is refused when no drive is recording - idle, during a start, already paused`() {
        val hold = Hold("insertDrive")
        val rec = recorder(hold)
        assertEquals("idle", refused(Refusal.NOT_RECORDING), rec.pauseDrive())
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertEquals("during a start", refused(Refusal.NOT_RECORDING), rec.pauseDrive())
        assertEquals(DrivePhase.STARTING, rec.phase())
        hold.release.countDown()
        assertTrue(waitUntil { rec.phase() == DrivePhase.RECORDING })
        rec.pauseDrive()
        assertEquals("already paused", refused(Refusal.NOT_RECORDING), rec.pauseDrive())
        assertEquals(DrivePhase.PAUSED, rec.phase())
    }

    // ── RESUME ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `RESUME on a paused drive is done - recording`() {
        val rec = recorder().recording()
        rec.pauseDrive()
        assertEquals(CommandResult.Done, rec.resumeDrive())
        assertEquals(DrivePhase.RECORDING, rec.phase())
    }

    @Test fun `RESUME is refused when no drive is paused - idle, during a start, recording`() {
        val hold = Hold("insertDrive")
        val rec = recorder(hold)
        assertEquals("idle", refused(Refusal.NOT_PAUSED), rec.resumeDrive())
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertEquals("during a start", refused(Refusal.NOT_PAUSED), rec.resumeDrive())
        hold.release.countDown()
        assertTrue(waitUntil { rec.phase() == DrivePhase.RECORDING })
        assertEquals("recording", refused(Refusal.NOT_PAUSED), rec.resumeDrive())
    }

    @Test fun `a refused command writes one REC line with its reason`() {
        val rec = recorder()
        rec.pauseDrive()
        assertEquals(listOf("PAUSE refused: not recording"),
            DiagnosticLogger.sessionEvents.filter { it.type == "REC" && "refused" in it.message }.map { it.message })
    }

    // ── STOP ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `STOP with nothing open does nothing`() {
        val rec = recorder()
        join(rec.stopDrive())
        assertEquals(DrivePhase.IDLE, rec.phase())
        assertEquals(0, db.driveDao().getDriveCount())
    }

    @Test fun `STOP during a start is a cancelled start - idle, no drive in history`() {
        val hold = Hold("insertDrive")
        val rec = recorder(hold)
        rec.startDrive()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        val stop = rec.stopDrive()
        assertEquals("idle at once", DrivePhase.IDLE, rec.phase())
        hold.release.countDown()
        join(stop)
        Thread.sleep(100)
        assertEquals(DrivePhase.IDLE, rec.phase())
        assertEquals(0, db.driveDao().getDriveCount())
    }

    @Test fun `STOP on a recording or a paused drive saves it`() {
        val rec = recorder().recording()
        val a = rec.driveState.value.driveId
        join(rec.stopDrive())
        assertEquals(DrivePhase.IDLE, rec.phase())
        assertTrue(db.driveDao().getDrive(a)!!.endTime > 0)
        rec.recording()
        val b = rec.driveState.value.driveId
        rec.pauseDrive()
        join(rec.stopDrive())
        assertEquals(DrivePhase.IDLE, rec.phase())
        assertTrue(db.driveDao().getDrive(b)!!.endTime > 0)
    }
}
