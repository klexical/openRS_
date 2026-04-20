package com.openrs.dash

import com.openrs.dash.data.LapTimer
import com.openrs.dash.data.LapTimerState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LapTimerTest {

    private lateinit var timer: LapTimer

    @Before
    fun setUp() {
        timer = LapTimer()
    }

    // ── Initial state ──────────────────────────────────────────────────────

    @Test
    fun `initial state is IDLE`() {
        assertEquals(LapTimerState.IDLE, timer.state)
        assertNull(timer.startFinish)
        assertTrue(timer.laps.isEmpty())
        assertEquals(Long.MAX_VALUE, timer.bestLapMs)
    }

    // ── Start/finish line management ────────────────────────────────────────

    @Test
    fun `setStartFinish arms the timer`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        assertEquals(LapTimerState.ARMED, timer.state)
        assertNotNull(timer.startFinish)
        assertEquals(52.0, timer.startFinish!!.lat, 0.0001)
        assertEquals(-1.0, timer.startFinish!!.lng, 0.0001)
        assertEquals(90f, timer.startFinish!!.bearing)
    }

    @Test
    fun `clearStartFinish resets to IDLE`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        timer.clearStartFinish()
        assertEquals(LapTimerState.IDLE, timer.state)
        assertNull(timer.startFinish)
        assertTrue(timer.laps.isEmpty())
    }

    @Test
    fun `reset clears laps but keeps S-F line`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        // Complete a lap first
        completeSingleLap()
        assertTrue(timer.laps.isNotEmpty())

        timer.reset()
        assertTrue(timer.laps.isEmpty())
        assertEquals(Long.MAX_VALUE, timer.bestLapMs)
        assertNotNull(timer.startFinish) // S/F preserved
    }

    // ── Geofence crossing logic ─────────────────────────────────────────────

    @Test
    fun `first crossing arms to TIMING`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        // Cross S/F at speed with correct heading
        val result = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 1000L, 5000, 15.0, 0.5)
        assertNull(result) // First crossing starts lap, no result
        assertEquals(LapTimerState.TIMING, timer.state)
    }

    @Test
    fun `too far from S-F line ignored`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        // 500m away — outside 15m geofence
        val result = timer.onLocationUpdate(52.005, -1.0, 90f, 80.0, 1000L, 5000, 15.0, 0.5)
        assertNull(result)
        assertEquals(LapTimerState.ARMED, timer.state) // Still armed, not timing
    }

    @Test
    fun `too slow near S-F line ignored`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        // Near S/F but only 15 kph (below 20 kph threshold)
        val result = timer.onLocationUpdate(52.0, -1.0, 90f, 15.0, 1000L, 2000, 5.0, 0.1)
        assertNull(result)
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `wrong heading near S-F line ignored`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        // Near S/F, fast enough, but heading opposite direction (270°, diff=180°)
        val result = timer.onLocationUpdate(52.0, -1.0, 270f, 80.0, 1000L, 5000, 15.0, 0.5)
        assertNull(result)
        assertEquals(LapTimerState.ARMED, timer.state)
    }

    @Test
    fun `heading within 30 degrees accepted`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        // 25° off — within ±30° tolerance
        val result = timer.onLocationUpdate(52.0, -1.0, 115f, 80.0, 1000L, 5000, 15.0, 0.5)
        assertNull(result) // First crossing starts, no lap result
        assertEquals(LapTimerState.TIMING, timer.state)
    }

    @Test
    fun `heading 31 degrees off rejected`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        val result = timer.onLocationUpdate(52.0, -1.0, 121f, 80.0, 1000L, 5000, 15.0, 0.5)
        assertNull(result)
        assertEquals(LapTimerState.ARMED, timer.state) // Still armed
    }

    // ── Lap completion ──────────────────────────────────────────────────────

    @Test
    fun `second crossing within 10s debounced`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        // First crossing — start timing
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 1000L, 5000, 15.0, 0.5)
        assertEquals(LapTimerState.TIMING, timer.state)

        // Second crossing 5s later — debounced (min 10s lap)
        val result = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 6000L, 5000, 15.0, 0.5)
        assertNull(result)
        assertTrue(timer.laps.isEmpty())
    }

    @Test
    fun `crossing after 10s completes a lap`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        // First crossing
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 1000L, 5000, 15.0, 0.5)

        // Feed some mid-lap updates (away from S/F)
        for (i in 1..9) {
            timer.onLocationUpdate(52.005, -1.005, 90f, 120.0, 1000L + i * 1000L, 6000, 18.0, 0.8)
        }

        // Cross S/F again at 15s
        val result = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 16000L, 5500, 16.0, 0.6)
        assertNotNull(result)
        assertEquals(1, result!!.lapNumber)
        assertEquals(15000L, result.lapTimeMs) // 16000 - 1000
        assertEquals(1, timer.laps.size)
    }

    @Test
    fun `lap tracks peak values`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        // Start
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 0L, 3000, 5.0, 0.2)

        // Mid-lap updates with varying telemetry
        timer.onLocationUpdate(52.005, -1.005, 90f, 120.0, 5000L, 6800, 22.0, 1.5)
        timer.onLocationUpdate(52.005, -1.005, 90f, 150.0, 8000L, 7200, 20.0, 1.2)
        timer.onLocationUpdate(52.005, -1.005, 90f, 100.0, 10000L, 5000, 18.0, 0.8)

        // Complete lap
        val result = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 15000L, 4000, 10.0, 0.3)
        assertNotNull(result)
        assertEquals(7200, result!!.peakRpm)
        assertEquals(22.0, result.peakBoostPsi, 0.01)
        assertEquals(1.5, result.peakLateralG, 0.01)
        assertEquals(150.0, result.peakSpeedKph, 0.01)
    }

    @Test
    fun `lateral G peak uses absolute value`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 0L, 3000, 5.0, 0.2)

        // Negative lateral G (right turn) higher magnitude than positive
        timer.onLocationUpdate(52.005, -1.005, 90f, 100.0, 5000L, 5000, 15.0, -1.8)
        timer.onLocationUpdate(52.005, -1.005, 90f, 100.0, 8000L, 5000, 15.0, 1.2)

        val result = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 15000L, 4000, 10.0, 0.3)
        assertNotNull(result)
        assertEquals(1.8, result!!.peakLateralG, 0.01)
    }

    // ── Best lap tracking ───────────────────────────────────────────────────

    @Test
    fun `best lap updated on faster lap`() {
        timer.setStartFinish(52.0, -1.0, 90f)

        // Lap 1: 60s
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 0L, 5000, 15.0, 0.5)
        timer.onLocationUpdate(52.005, -1.005, 90f, 120.0, 30000L, 6000, 18.0, 0.8)
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 60000L, 5000, 15.0, 0.5)
        assertEquals(60000L, timer.bestLapMs)

        // Lap 2: 50s (faster)
        timer.onLocationUpdate(52.005, -1.005, 90f, 130.0, 85000L, 6500, 20.0, 1.0)
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 110000L, 5000, 15.0, 0.5)
        assertEquals(50000L, timer.bestLapMs)
        assertEquals(2, timer.laps.size)
    }

    @Test
    fun `best lap not updated on slower lap`() {
        timer.setStartFinish(52.0, -1.0, 90f)

        // Lap 1: 30s
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 0L, 5000, 15.0, 0.5)
        timer.onLocationUpdate(52.005, -1.005, 90f, 120.0, 15000L, 6000, 18.0, 0.8)
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 30000L, 5000, 15.0, 0.5)
        assertEquals(30000L, timer.bestLapMs)

        // Lap 2: 40s (slower)
        timer.onLocationUpdate(52.005, -1.005, 90f, 100.0, 50000L, 5500, 16.0, 0.7)
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 70000L, 5000, 15.0, 0.5)
        assertEquals(30000L, timer.bestLapMs) // Still lap 1
    }

    // ── Multiple laps ───────────────────────────────────────────────────────

    @Test
    fun `three consecutive laps numbered correctly`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 0L, 5000, 15.0, 0.5) // start

        for (lap in 1..3) {
            val startMs = (lap - 1) * 20000L
            timer.onLocationUpdate(52.005, -1.005, 90f, 120.0, startMs + 10000L, 6000, 18.0, 0.8)
            val result = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, lap * 20000L, 5000, 15.0, 0.5)
            assertNotNull("Lap $lap should complete", result)
            assertEquals(lap, result!!.lapNumber)
        }
        assertEquals(3, timer.laps.size)
    }

    // ── IDLE state ignores updates ──────────────────────────────────────────

    @Test
    fun `updates in IDLE state return null`() {
        val result = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 1000L, 5000, 15.0, 0.5)
        assertNull(result)
        assertEquals(LapTimerState.IDLE, timer.state)
    }

    @Test
    fun `no S-F line returns null`() {
        // Directly set to armed without S/F (shouldn't happen, but defensive)
        val result = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 1000L, 5000, 15.0, 0.5)
        assertNull(result)
    }

    // ── Haversine ───────────────────────────────────────────────────────────

    @Test
    fun `haversine returns 0 for same point`() {
        val d = LapTimer.haversineMeters(52.0, -1.0, 52.0, -1.0)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun `haversine known distance London to Paris`() {
        // London (51.5074, -0.1278) to Paris (48.8566, 2.3522) ≈ 343.5 km
        val d = LapTimer.haversineMeters(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(343_500.0, d, 5000.0) // within 5km
    }

    @Test
    fun `haversine short distance about 10 meters`() {
        // ~10m north at 52°N latitude
        val d = LapTimer.haversineMeters(52.0, -1.0, 52.00009, -1.0)
        assertEquals(10.0, d, 2.0)
    }

    // ── angleDiff ───────────────────────────────────────────────────────────

    @Test
    fun `angleDiff same angle returns 0`() {
        assertEquals(0f, LapTimer.angleDiff(90f, 90f))
    }

    @Test
    fun `angleDiff 45 degrees apart`() {
        assertEquals(45f, LapTimer.angleDiff(135f, 90f))
    }

    @Test
    fun `angleDiff wraps around 360`() {
        // 350° to 10° should be -20° (or 20° magnitude)
        val diff = LapTimer.angleDiff(350f, 10f)
        assertEquals(-20f, diff, 0.001f)
    }

    @Test
    fun `angleDiff opposite directions`() {
        val diff = LapTimer.angleDiff(0f, 180f)
        assertEquals(-180f, diff, 0.001f)
    }

    @Test
    fun `angleDiff wrap north crossing`() {
        // 5° to 355° = 10° difference
        val diff = LapTimer.angleDiff(5f, 355f)
        assertEquals(10f, diff, 0.001f)
    }

    @Test
    fun `angleDiff negative wrap`() {
        // 355° to 5° = -10°
        val diff = LapTimer.angleDiff(355f, 5f)
        assertEquals(-10f, diff, 0.001f)
    }

    // ── Peak resets between laps ─────────────────────────────────────────────

    @Test
    fun `peaks reset between laps`() {
        timer.setStartFinish(52.0, -1.0, 90f)
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 0L, 3000, 5.0, 0.2) // start

        // Lap 1: high peaks
        timer.onLocationUpdate(52.005, -1.005, 90f, 140.0, 5000L, 7000, 22.0, 1.5)
        val lap1 = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 15000L, 4000, 10.0, 0.3)
        assertNotNull(lap1)
        assertEquals(7000, lap1!!.peakRpm)

        // Lap 2: lower peaks — should reflect lap 2 only, not lap 1
        timer.onLocationUpdate(52.005, -1.005, 90f, 100.0, 20000L, 5000, 12.0, 0.6)
        val lap2 = timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 30000L, 4500, 10.0, 0.3)
        assertNotNull(lap2)
        assertEquals(5000, lap2!!.peakRpm)  // Not 7000 from lap 1
        assertEquals(12.0, lap2.peakBoostPsi, 0.01)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun completeSingleLap() {
        timer.setStartFinish(52.0, -1.0, 90f)
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 0L, 5000, 15.0, 0.5)
        timer.onLocationUpdate(52.005, -1.005, 90f, 120.0, 10000L, 6000, 18.0, 0.8)
        timer.onLocationUpdate(52.0, -1.0, 90f, 80.0, 20000L, 5000, 15.0, 0.5)
    }
}
