package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.MapF40b
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * #271, owner's final decision F1 ("Only while recording"): the production PCM poller sends MAP 0xF40B in the fast group while
 * its `mapPoll` input is true — CanDataService feeds it [MapF40b.pollActive] of the recorder's state (recording, not paused) —
 * and not at all otherwise; the input is read for every batch, so starting or stopping a drive switches it within one cycle.
 * The recorder stores MAP only from a decode made during that drive, so no MAP is carried across a stop and a new start.
 * See MapRecordingOnlyTest for "no recording → no MAP" on the unchanged poller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MapRecordingPollTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }
    @After fun close() { recorders.forEach { it.cancel() }; if (::db.isInitialized) db.close() }

    private fun counts(writes: List<String>) = writes.count { "22033E" in it } to writes.count { "22F40B" in it }

    // ── the poller ──────────────────────────────────────────────────────────

    @Test fun `while recording the poller sends MAP with TIP every cycle, and none once the recording ends`() = runTest {
        var recording = true
        val run = ReplayRun(this, ReplayFixture.load("at-rest-baseline.txt"), rpm = 3_000.0, mapPoll = { recording })
        advanceTimeBy(80_000L)                                            // 20 s start delay + ~25 cycles
        val (tipOn, mapOn) = counts(run.transport.writes)
        assertTrue("the fast group ran ($tipOn)", tipOn >= 15)
        assertTrue("recording: MAP every cycle ($mapOn MAP vs $tipOn TIP)", mapOn >= tipOn - 1)
        recording = false                                                 // STOP
        advanceTimeBy(3_000L)                                             // the batch in flight finishes
        val before = run.transport.writes.size
        advanceTimeBy(60_000L)
        val (tipOff, mapOff) = counts(run.transport.writes.drop(before))
        assertTrue("the fast group kept running ($tipOff)", tipOff >= 15)
        assertEquals("not recording: no MAP request", 0, mapOff)
    }

    @Test fun `the poll rule - recording yes, paused no, not recording no`() {
        assertTrue(MapF40b.pollActive(DriveState(isRecording = true, isPaused = false)))
        assertFalse("a paused drive stores no points", MapF40b.pollActive(DriveState(isRecording = true, isPaused = true)))
        assertFalse(MapF40b.pollActive(DriveState(isRecording = false)))
    }

    @Test fun `a paused recording sends no MAP`() = runTest {
        val run = ReplayRun(this, ReplayFixture.load("at-rest-baseline.txt"), rpm = 3_000.0,
            mapPoll = { MapF40b.pollActive(DriveState(isRecording = true, isPaused = true)) })
        advanceTimeBy(80_000L)
        val (tip, map) = counts(run.transport.writes)
        assertTrue(tip >= 15); assertEquals(0, map)
    }

    // ── the recorder across a stop and a new start ─────────────────────────

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var db: DriveDatabase
    private val recorders = mutableListOf<DriveRecorder>()

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    @Test fun `no MAP is carried across a stop and a new start - only a decode made during the drive is stored`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        val vs = MutableStateFlow(VehicleState(isConnected = true))
        val gps = FakeGps()
        val rec = DriveRecorder(app, vs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { }).also { recorders += it }
        fun decode(kpa: Int) {
            Thread.sleep(5)
            ObdResponseParser.parsePcmResponse(byteArrayOf(0x04, 0x62, 0xF4.toByte(), 0x0B, kpa.toByte(), 0, 0, 0), vs.value) { vs.value = ObdMerge.merge(vs.value, it) }
        }
        fun start(): Long {
            val opened = gps.opened.get(); val prev = rec.driveState.value.driveId
            rec.startDrive()
            check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 && it.driveId != prev } && gps.opened.get() > opened })
            Thread.sleep(5)
            return rec.driveState.value.driveId
        }
        fun point(i: Int) {
            val before = rec.driveState.value.totalPointCount
            gps.current!!.trySend(Location("t").apply { latitude = 51.5 + i * 1e-4; longitude = -0.1; time = System.currentTimeMillis() })
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 })
        }
        val a = start(); decode(120); point(0)                             // drive A: a fresh decode → stored
        runBlocking { withTimeout(10_000) { rec.stopDrive().join() } }
        val b = start(); point(1)                                         // drive B: A's decode is still in the state → not stored
        decode(130); point(2)                                             // a decode during B → stored
        runBlocking { withTimeout(10_000) { rec.stopDrive().join() } }
        assertEquals(listOf(120.0), db.driveDao().getPoints(a).map { it.mapKpa })
        assertEquals(listOf(null, 130.0), db.driveDao().getPoints(b).map { it.mapKpa })
    }
}
