package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.data.ObdMerge
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * N1 (issue to be filed), stale polled readings: a point stores a polled value only from a decode made since the last fix the
 * recorder skipped (paused / disconnected) and not older than its poll budget. Real DriveRecorder → in-memory Room, real
 * ObdResponseParser + ObdMerge, synthetic GPS. Every per-point polled field: TIP, TIP desired, WGDC, MAP (drive_points); the
 * fuel trims (the drive's trim samples); KR → knock events. Before the fix, a decode made after the last stored point and
 * before a pause / link drop was stored at the first fix after it, however old (the gate checked only the drive start and the
 * last consumed stamp). Only APIs that exist on 9222835, so this file runs there (red) and after the fix (green).
 */
@RunWith(RobolectricTestRunner::class)
class RecorderFreshnessTest {
    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var db: DriveDatabase
    private lateinit var rec: DriveRecorder
    // Engine running and warm (the trim accumulator's gate: coolant ≥ 70 °C, rpm ≥ 500)
    private val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 3_000.0, speedKph = 60.0, coolantTempC = 88.0))
    private val gps = FakeGps()
    private var driveId = 0L

    @Before fun setUp() {
        val app: Application = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        rec = DriveRecorder(app, vs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { })
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 })
        driveId = rec.driveState.value.driveId
        Thread.sleep(5)
    }
    @After fun tearDown() { rec.cancel(); db.close() }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    // ── synthetic PCM replies through the production parse + merge ─────────
    private fun reply(vararg bytes: Int) {
        Thread.sleep(3)
        ObdResponseParser.parsePcmResponse(ByteArray(8) { (bytes.getOrElse(it) { 0 }).toByte() }, vs.value) { vs.value = ObdMerge.merge(vs.value, it) }
    }
    private fun map(kpa: Int) = reply(0x04, 0x62, 0xF4, 0x0B, kpa)
    private fun tip(raw: Int) = reply(0x05, 0x62, 0x03, 0x3E, raw shr 8, raw and 0xFF)
    private fun tipDesired(raw: Int) = reply(0x05, 0x62, 0x04, 0x66, raw shr 8, raw and 0xFF)
    private fun wgdc(raw: Int) = reply(0x04, 0x62, 0x04, 0x62, raw)
    private fun trims() { reply(0x04, 0x62, 0xF4, 0x06, 0x84); reply(0x04, 0x62, 0xF4, 0x07, 0x82) }
    private fun kr1Retard2deg() = reply(0x05, 0x62, 0x03, 0xEC, 0xFC, 0x00)   // (0xFC00 signed) / −512 = +2.0° retard
    private fun everyField() { map(150); tip(0x5800); tipDesired(0x5A00); wgdc(0x40); trims() }

    private fun loc(i: Int) = Location("t").apply { latitude = 51.5 + i * 1e-3; longitude = -0.1 + i * 1e-3; time = System.currentTimeMillis() }
    private fun fix(i: Int) {
        val before = rec.driveState.value.totalPointCount
        gps.current!!.trySend(loc(i))
        check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "fix $i not recorded" }
    }
    private fun skippedFix(i: Int) { gps.current!!.trySend(loc(i)); Thread.sleep(150) }      // a fix the loop skips
    private fun pauseAndResume() { rec.pauseDrive(); skippedFix(90); Thread.sleep(300); rec.resumeDrive() }
    private fun linkGap() {
        vs.value = vs.value.copy(isConnected = false); skippedFix(91); Thread.sleep(300)
        vs.value = vs.value.copy(isConnected = true)                    // back; the PCM poller sends nothing for 20 s
    }
    private fun stop(): Triple<DriveEntity, List<DrivePointEntity>, List<KnockEventEntity>> {
        runBlocking { withTimeout(10_000) { rec.stopDrive().join() } }
        return Triple(db.driveDao().getDrive(driveId)!!, db.driveDao().getPoints(driveId), db.driveDao().getKnockEvents(driveId))
    }
    private fun assertNoPolledValue(p: DrivePointEntity, where: String) {
        assertNull("$where: MAP ${p.mapKpa}", p.mapKpa)
        assertEquals("$where: TIP", 0.0, p.tipActualKpa, 0.0)
        assertEquals("$where: TIP desired", 0.0, p.boostTargetKpa, 0.0)
        assertEquals("$where: WGDC", -1.0, p.wgdcPct, 0.0)
    }

    // ── the six reproduced cases ───────────────────────────────────────────

    @Test fun `pause - no value decoded before the pause is stored at the first fix after resume (every per-point field)`() {
        fix(0); everyField(); pauseAndResume(); fix(1)
        val (_, p, _) = stop()
        assertNoPolledValue(p[1], "first point after resume")
    }

    @Test fun `pause - TIP alone`() {
        tip(0x4F00); fix(0); tip(0x5800); pauseAndResume(); fix(1)
        assertEquals(0.0, stop().second[1].tipActualKpa, 0.0)
    }

    @Test fun `pause - a knock decoded before the pause is not logged as a knock event after resume`() {
        fix(0); kr1Retard2deg(); pauseAndResume(); fix(1)
        val (_, _, knocks) = stop()
        assertEquals("knock events: $knocks", 0, knocks.size)
    }

    @Test fun `pause - a trim decoded before the pause is not counted after resume`() {
        fix(0); trims(); pauseAndResume(); fix(1)
        assertEquals(0, stop().first.trimSamples)
    }

    @Test fun `link gap - no value decoded before a disconnect is stored at the first fix after reconnect`() {
        fix(0); everyField(); linkGap(); fix(1)
        assertNoPolledValue(stop().second[1], "first point after reconnect")
    }

    @Test fun `long gap - a decode older than its poll budget is not stored, even with no pause or link drop`() {
        fix(0); map(150); tip(0x5800); wgdc(0x40)
        Thread.sleep(4_500)                                              // past twice the 2 s fast refresh, engine running
        fix(1)
        val p = stop().second[1]
        assertNull("MAP ${p.mapKpa}", p.mapKpa); assertEquals(0.0, p.tipActualKpa, 0.0); assertEquals(-1.0, p.wgdcPct, 0.0)
    }

    // ── what must still happen ─────────────────────────────────────────────

    @Test fun `control - fresh decodes are stored normally, once each`() {
        everyField(); fix(0); fix(1)                                    // decoded before point 0 → point 0; nothing new → point 1 empty
        map(160); tip(0x5900); fix(2)
        val (d, p, _) = stop()
        assertEquals(150.0, p[0].mapKpa!!, 0.0); assertEquals(0x5800 / 128.0, p[0].tipActualKpa, 1e-9)
        assertEquals(0x5A00 / 128.0, p[0].boostTargetKpa, 1e-9); assertEquals(0x40 * 100.0 / 128.0, p[0].wgdcPct, 1e-9)
        assertNoPolledValue(p[1], "a point with no new decode")
        assertEquals(160.0, p[2].mapKpa!!, 0.0); assertEquals(0x5900 / 128.0, p[2].tipActualKpa, 1e-9)
        assertEquals(1, d.trimSamples)
    }

    @Test fun `after a gap, nothing is stored until a new decode arrives, then it is`() {
        fix(0); everyField(); pauseAndResume(); fix(1); fix(2)
        map(170); tip(0x5A00); fix(3)
        val p = stop().second
        assertNoPolledValue(p[1], "point 1"); assertNoPolledValue(p[2], "point 2")
        assertEquals(170.0, p[3].mapKpa!!, 0.0); assertEquals(0x5A00 / 128.0, p[3].tipActualKpa, 1e-9)
    }

    @Test fun `a knock event carries the time and place of the fix after its decode`() {
        fix(0); kr1Retard2deg(); fix(1)
        val (_, p, knocks) = stop()
        val k = knocks.single()
        assertEquals(2.0, k.krDeg, 1e-9)
        assertEquals(p[1].timestamp, k.timestamp)
        assertEquals(p[1].lat, k.lat!!, 1e-12); assertEquals(p[1].lng, k.lng!!, 1e-12)
    }
}
