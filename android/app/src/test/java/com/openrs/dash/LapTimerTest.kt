package com.openrs.dash

import com.openrs.dash.data.FixReport
import com.openrs.dash.data.FixVerdict
import com.openrs.dash.data.LapFix
import com.openrs.dash.data.LapTimer
import com.openrs.dash.data.LapTimerState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

/**
 * LapTimer geometry and rules (#237): a lap is a crossing of the S/F gate between two fixes, not a fix near the point.
 * Positions are local metres from the S/F point (x east, y north); the line is crossed eastbound (90°) unless stated.
 * The thresholds are the shipped ones (15 m, 20 km/h, ±30°, 10 s) — a crossing case approaches from x < 0 to x > 0.
 */
class LapTimerTest {

    private lateinit var timer: LapTimer
    private var drive = 1L

    @Before
    fun setUp() {
        timer = LapTimer()
    }

    private fun fix(
        x: Double, y: Double, t: Long, bearing: Float? = 90f, speed: Double = 80.0, age: Long = 0,
        rpm: Int = 5000, boost: Double = 15.0, latG: Double = 0.5, d: Long = drive,
    ): FixReport {
        val lat = SF_LAT + Math.toDegrees(y / 6371000.0)
        val lng = SF_LNG + Math.toDegrees(x / (6371000.0 * cos(Math.toRadians(SF_LAT))))
        return timer.onFix(d, LapFix(lat, lng, bearing, t, age, t, speed, rpm, boost, latG))
    }

    /** Leave the line (60 m before it), then cross it at exactly [t] (fixes 10 m either side, 1 s apart). */
    private fun lapCrossing(t: Long, rpm: Int = 5000, boost: Double = 15.0, latG: Double = 0.5, speed: Double = 80.0): FixReport {
        fix(-60.0, 0.0, t - 1_500, rpm = rpm, boost = boost, latG = latG, speed = speed)
        fix(-10.0, 0.0, t - 500, rpm = rpm, boost = boost, latG = latG, speed = speed)
        return fix(10.0, 0.0, t + 500, rpm = rpm, boost = boost, latG = latG, speed = speed)
    }

    private fun armed() = timer.setStartFinish(SF_LAT, SF_LNG, 90f)

    // ── Initial state / line management ─────────────────────────────────────

    @Test
    fun `initial state is IDLE`() {
        assertEquals(LapTimerState.IDLE, timer.state)
        assertNull(timer.startFinish)
        assertTrue(timer.laps.isEmpty())
        assertEquals(Long.MAX_VALUE, timer.bestLapMs)
    }

    @Test
    fun `setStartFinish arms the timer`() {
        armed()
        assertEquals(LapTimerState.ARMED, timer.state)
        assertEquals(SF_LAT, timer.startFinish!!.lat, 0.0001)
        assertEquals(SF_LNG, timer.startFinish!!.lng, 0.0001)
        assertEquals(90f, timer.startFinish!!.bearing)
    }

    @Test
    fun `clearStartFinish resets to IDLE`() {
        armed()
        timer.clearStartFinish()
        assertEquals(LapTimerState.IDLE, timer.state)
        assertNull(timer.startFinish)
        assertTrue(timer.laps.isEmpty())
    }

    @Test
    fun `reset clears laps but keeps S-F line`() {
        armed()
        lapCrossing(0); lapCrossing(20_000)
        assertTrue(timer.laps.isNotEmpty())
        timer.reset()
        assertTrue(timer.laps.isEmpty())
        assertEquals(Long.MAX_VALUE, timer.bestLapMs)
        assertNotNull(timer.startFinish)
    }

    @Test
    fun `updates in IDLE state return NO_LINE`() {
        assertEquals(FixVerdict.NO_LINE, fix(10.0, 0.0, 1000).verdict)
        assertEquals(LapTimerState.IDLE, timer.state)
    }

    // ── Crossing, not proximity ─────────────────────────────────────────────

    @Test
    fun `first crossing starts timing`() {
        armed()
        assertEquals(FixVerdict.TIMING_STARTED, lapCrossing(1_000).verdict)
        assertEquals(LapTimerState.TIMING, timer.state)
    }

    @Test
    fun `a fix at the point is not a crossing`() {
        armed()
        fix(-60.0, 0.0, 0)
        assertEquals(FixVerdict.NO_CROSSING, fix(-1.0, 0.0, 1_000).verdict)        // 1 m from the point, not past it
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `nearby pass that turns away does not cross`() {
        armed()
        fix(-60.0, 0.0, 0)
        assertEquals(FixVerdict.NO_CROSSING, fix(-8.0, 0.0, 2_500).verdict)       // inside the old 15 m circle
        assertEquals(FixVerdict.NO_CROSSING, fix(-8.0, -20.0, 3_500, bearing = 180f).verdict)
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `crossing outside the gate width does not count`() {
        armed()
        fix(-60.0, 16.0, 0); fix(-10.0, 16.0, 2_500)
        assertEquals(FixVerdict.NO_CROSSING, fix(10.0, 16.0, 3_500).verdict)      // 16 m from the point across the line
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `crossing between samples is interpolated`() {
        armed()
        fix(-90.0, 0.0, 0)
        fix(-30.0, 0.0, 3_000)
        assertEquals(FixVerdict.TIMING_STARTED, fix(30.0, 0.0, 6_000).verdict)    // both 30 m from the line; crossing at 4.5 s
        fix(100.0, 0.0, 9_500); fix(100.0, 80.0, 13_500); fix(-100.0, 80.0, 23_500); fix(-100.0, 0.0, 27_500, bearing = 180f)
        fix(-30.0, 0.0, 31_000)
        val lap = fix(30.0, 0.0, 34_000)                                          // crossing at 32.5 s
        assertEquals(FixVerdict.LAP, lap.verdict)
        assertEquals(28_000L, lap.lap!!.lapTimeMs)
    }

    @Test
    fun `no crossing is interpolated across a gap longer than 5 s`() {
        armed()
        fix(-60.0, 0.0, 0)
        fix(-30.0, 0.0, 1_000)
        assertEquals(FixVerdict.NO_SEGMENT, fix(30.0, 0.0, 6_100).verdict)
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `a recording gap breaks the track`() {
        armed()
        fix(-60.0, 0.0, 0); fix(-10.0, 0.0, 1_000)
        timer.breakTrack(drive)                                                   // paused / adapter gap in between
        assertEquals(FixVerdict.NO_SEGMENT, fix(10.0, 0.0, 2_000).verdict)
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    // ── Departure ───────────────────────────────────────────────────────────

    @Test
    fun `dwelling at the line after SET does not start timing`() {
        armed()
        listOf(-1.0, 2.0, -0.5, 1.5, -2.0, 1.0).forEachIndexed { i, x ->          // jitter back and forth across it
            assertNotEquals(FixVerdict.TIMING_STARTED, fix(x, 0.0, i * 1_000L).verdict)
        }
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `the next lap needs the car to leave the line first`() {
        armed()
        lapCrossing(0)
        fix(25.0, 0.0, 2_000)                                                     // 25 m past it, not 30
        fix(20.0, -15.0, 6_000, bearing = 180f)                                   // 25 m away
        fix(-10.0, -5.0, 11_000, bearing = 270f)                                  // back round inside 30 m
        assertEquals(FixVerdict.NOT_DEPARTED, fix(10.0, -5.0, 12_000).verdict)
        assertTrue(timer.laps.isEmpty())
    }

    @Test
    fun `stationary repeated position past the line never counts`() {
        armed()
        lapCrossing(0)
        for (i in 1..15) assertEquals(FixVerdict.NO_CROSSING, fix(6.0, 0.0, 1_000L + i * 1_000).verdict)
        assertTrue(timer.laps.isEmpty())
    }

    // ── Fresh, ordered fixes ────────────────────────────────────────────────

    @Test
    fun `a replayed fix is refused`() {
        armed()
        lapCrossing(0)
        assertEquals(FixVerdict.OUT_OF_ORDER, fix(10.0, 0.0, 500).verdict)
        assertEquals(FixVerdict.OUT_OF_ORDER, fix(-10.0, 0.0, -500).verdict)
    }

    @Test
    fun `a stale fix is refused`() {
        armed()
        fix(-60.0, 0.0, 0); fix(-10.0, 0.0, 1_000)
        assertEquals(FixVerdict.STALE, fix(10.0, 0.0, 2_000, age = 2_001).verdict)
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    // ── Speed / heading (unchanged thresholds) ──────────────────────────────

    @Test
    fun `too slow crossing ignored`() {
        armed()
        assertEquals(FixVerdict.TOO_SLOW, lapCrossing(1_000, speed = 15.0).verdict)
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `driving the line backwards is not a crossing`() {
        armed()
        fix(60.0, 0.0, 0, bearing = 270f); fix(10.0, 0.0, 2_500, bearing = 270f)
        assertEquals(FixVerdict.NO_CROSSING, fix(-10.0, 0.0, 3_500, bearing = 270f).verdict)
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `wrong reported heading on a crossing ignored`() {
        armed()
        fix(-60.0, 0.0, 0, bearing = 270f); fix(-10.0, 0.0, 2_500, bearing = 270f)
        assertEquals(FixVerdict.WRONG_HEADING, fix(10.0, 0.0, 3_500, bearing = 270f).verdict)
    }

    @Test
    fun `heading within 30 degrees accepted`() {
        armed()
        fix(-60.0, 0.0, 0); fix(-10.0, 0.0, 2_500)
        assertEquals(FixVerdict.TIMING_STARTED, fix(10.0, 0.0, 3_500, bearing = 115f).verdict)
    }

    @Test
    fun `heading 31 degrees off rejected`() {
        armed()
        fix(-60.0, 0.0, 0); fix(-10.0, 0.0, 2_500)
        assertEquals(FixVerdict.WRONG_HEADING, fix(10.0, 0.0, 3_500, bearing = 121f).verdict)
    }

    // ── Missing bearing vs 0° north ─────────────────────────────────────────

    @Test
    fun `a fix without a bearing uses its course - not 0 degrees`() {
        armed()
        fix(-60.0, 0.0, 0, bearing = null); fix(-10.0, 0.0, 2_500, bearing = null)
        assertEquals(FixVerdict.TIMING_STARTED, fix(10.0, 0.0, 3_500, bearing = null).verdict)
    }

    @Test
    fun `a reported 0 degrees is north - on an eastbound crossing it is the wrong heading`() {
        armed()
        fix(-60.0, 0.0, 0); fix(-10.0, 0.0, 2_500)
        assertEquals(FixVerdict.WRONG_HEADING, fix(10.0, 0.0, 3_500, bearing = 0f).verdict)
    }

    @Test
    fun `a genuine north line is crossed northbound with bearing 0`() {
        timer.setStartFinish(SF_LAT, SF_LNG, 0f)
        fix(0.0, -60.0, 0, bearing = 0f); fix(0.0, -10.0, 2_500, bearing = 0f)
        assertEquals(FixVerdict.TIMING_STARTED, fix(0.0, 10.0, 3_500, bearing = 0f).verdict)
    }

    @Test
    fun `a line set without a bearing takes the departure course and is stored with it`() {
        timer.setStartFinish(SF_LAT, SF_LNG, null)
        assertNull(timer.startFinish!!.bearing)
        assertEquals(FixVerdict.NOT_DEPARTED, fix(10.0, 0.0, 1_000, bearing = null).verdict)
        assertEquals(FixVerdict.LINE_DIRECTION, fix(35.0, 0.0, 2_000, bearing = null).verdict)
        assertEquals(90f, timer.startFinish!!.bearing!!, 0.01f)
        fix(100.0, 0.0, 5_500, bearing = null); fix(100.0, 80.0, 9_500, bearing = null)
        fix(-100.0, 80.0, 19_500, bearing = null); fix(-100.0, 0.0, 23_500, bearing = null)
        lapCrossing(28_000); lapCrossing(56_000)
        val (sf, laps) = timer.takeLaps(drive)!!
        assertEquals(90f, sf.bearing!!, 0.01f)
        assertEquals(listOf(28_000L), laps.map { it.lapTimeMs })
    }

    // ── Laps, peaks, best ───────────────────────────────────────────────────

    @Test
    fun `second crossing within 10s debounced`() {
        armed()
        lapCrossing(1_000)
        assertEquals(FixVerdict.TOO_SOON, lapCrossing(6_000).verdict)
        assertTrue(timer.laps.isEmpty())
    }

    @Test
    fun `crossing after 10s completes a lap`() {
        armed()
        lapCrossing(1_000)
        val result = lapCrossing(16_000)
        assertEquals(FixVerdict.LAP, result.verdict)
        assertEquals(1, result.lap!!.lapNumber)
        assertEquals(15_000L, result.lap!!.lapTimeMs)
        assertEquals(1, timer.laps.size)
    }

    @Test
    fun `lap tracks peak values`() {
        armed()
        lapCrossing(0, rpm = 3000, boost = 5.0, latG = 0.2)
        fix(100.0, 0.0, 5_000, rpm = 6800, boost = 22.0, latG = 1.5, speed = 120.0)
        fix(100.0, 80.0, 8_000, rpm = 7200, boost = 20.0, latG = 1.2, speed = 150.0)
        fix(-100.0, 80.0, 10_000, rpm = 5000, boost = 18.0, latG = 0.8, speed = 100.0)
        fix(-100.0, 0.0, 12_000, rpm = 4000, boost = 10.0, latG = 0.3, speed = 80.0)
        val result = lapCrossing(15_000, rpm = 4000, boost = 10.0, latG = 0.3).lap!!
        assertEquals(7200, result.peakRpm)
        assertEquals(22.0, result.peakBoostPsi, 0.01)
        assertEquals(1.5, result.peakLateralG, 0.01)
        assertEquals(150.0, result.peakSpeedKph, 0.01)
    }

    @Test
    fun `lateral G peak uses absolute value`() {
        armed()
        lapCrossing(0, latG = 0.2)
        fix(100.0, 0.0, 5_000, latG = -1.8); fix(-100.0, 80.0, 8_000, latG = 1.2)
        assertEquals(1.8, lapCrossing(15_000, latG = 0.3).lap!!.peakLateralG, 0.01)
    }

    @Test
    fun `best lap updated on faster lap and not on a slower one`() {
        armed()
        lapCrossing(0)
        lapCrossing(60_000); assertEquals(60_000L, timer.bestLapMs)
        lapCrossing(110_000); assertEquals(50_000L, timer.bestLapMs)
        lapCrossing(170_000); assertEquals(50_000L, timer.bestLapMs)
        assertEquals(3, timer.laps.size)
    }

    @Test
    fun `three consecutive laps numbered correctly`() {
        armed()
        lapCrossing(0)
        for (lap in 1..3) assertEquals(lap, lapCrossing(lap * 20_000L).lap!!.lapNumber)
        assertEquals(3, timer.laps.size)
    }

    @Test
    fun `peaks reset between laps`() {
        armed()
        lapCrossing(0, rpm = 3000, boost = 5.0)
        fix(100.0, 0.0, 5_000, rpm = 7000, boost = 22.0)
        assertEquals(7000, lapCrossing(15_000, rpm = 4000, boost = 10.0).lap!!.peakRpm)
        fix(100.0, 0.0, 20_000, rpm = 5000, boost = 12.0)
        val lap2 = lapCrossing(30_000, rpm = 4500, boost = 10.0).lap!!
        assertEquals(5000, lap2.peakRpm)
        assertEquals(12.0, lap2.peakBoostPsi, 0.01)
    }

    // ── Drive ownership: STOP → START ───────────────────────────────────────

    @Test
    fun `a fix of a stopped drive is refused and cannot start the next drive's timer`() {
        armed()
        lapCrossing(0); lapCrossing(30_000)
        assertEquals(listOf(30_000L), timer.takeLaps(1)!!.second.map { it.lapTimeMs })
        assertEquals(LapTimerState.ARMED, timer.state)
        assertEquals(FixVerdict.CLOSED_DRIVE, fix(-60.0, 0.0, 40_000, d = 1).verdict)
        fix(-60.0, 0.0, 41_000, d = 2); fix(-20.0, 0.0, 43_000, d = 2)          // drive 2 up to 20 m before the line
        assertEquals(FixVerdict.CLOSED_DRIVE, fix(10.0, 0.0, 44_000, d = 1).verdict)  // drive 1's late fix past it
        assertEquals(LapTimerState.ARMED, timer.state)
        assertEquals(FixVerdict.TIMING_STARTED, fix(10.0, 0.0, 44_500, d = 2).verdict)
    }

    @Test
    fun `the next drive's first fix does not pair with the previous drive's last`() {
        armed()
        fix(-60.0, 0.0, 0); fix(-10.0, 0.0, 1_000)
        timer.takeLaps(1)
        assertEquals(FixVerdict.NO_SEGMENT, fix(10.0, 0.0, 2_000, d = 2).verdict)
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `an older drive's STOP does not take or reset a newer drive's laps`() {
        armed()
        drive = 2
        lapCrossing(0); lapCrossing(30_000)
        assertNull(timer.takeLaps(1))
        assertEquals(1, timer.laps.size)
        assertEquals(1, timer.takeLaps(2)!!.second.size)
    }

    @Test
    fun `takeLaps with no laps returns null and re-arms`() {
        armed()
        lapCrossing(0)
        assertNull(timer.takeLaps(1))
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    // ── Geometry helpers ────────────────────────────────────────────────────

    @Test
    fun `haversine returns 0 for same point`() {
        assertEquals(0.0, LapTimer.haversineMeters(52.0, -1.0, 52.0, -1.0), 0.001)
    }

    @Test
    fun `haversine known distance London to Paris`() {
        assertEquals(343_500.0, LapTimer.haversineMeters(51.5074, -0.1278, 48.8566, 2.3522), 5000.0)
    }

    @Test
    fun `haversine short distance about 10 meters`() {
        assertEquals(10.0, LapTimer.haversineMeters(52.0, -1.0, 52.00009, -1.0), 2.0)
    }

    @Test
    fun `angleDiff wraps`() {
        assertEquals(0f, LapTimer.angleDiff(90f, 90f))
        assertEquals(45f, LapTimer.angleDiff(135f, 90f))
        assertEquals(-20f, LapTimer.angleDiff(350f, 10f), 0.001f)
        assertEquals(-180f, LapTimer.angleDiff(0f, 180f), 0.001f)
        assertEquals(10f, LapTimer.angleDiff(5f, 355f), 0.001f)
        assertEquals(-10f, LapTimer.angleDiff(355f, 5f), 0.001f)
    }

    @Test
    fun `courseDeg - north is 0, east 90`() {
        assertEquals(0f, LapTimer.courseDeg(0.0, 0.0, 0.0, 10.0), 0.001f)
        assertEquals(90f, LapTimer.courseDeg(0.0, 0.0, 10.0, 0.0), 0.001f)
        assertEquals(270f, LapTimer.courseDeg(0.0, 0.0, -10.0, 0.0), 0.001f)
    }

    private companion object {
        const val SF_LAT = 52.0
        const val SF_LNG = -1.0
    }
}
