package com.openrs.dash

import com.openrs.dash.data.ThermalPredictor
import com.openrs.dash.data.ThermalPredictor.ClimbTrend
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ThermalPredictorTest {

    private lateinit var predictor: ThermalPredictor

    @Before
    fun setUp() {
        predictor = ThermalPredictor(windowMs = 120_000L, minSamples = 10)
    }

    // ── Prediction logic ────────────────────────────────────────────────────

    @Test
    fun `constant rise predicts correct time to critical`() {
        // 2 °C/min rise from 100°C → 130°C threshold → 15 min
        val t0 = 1_000_000L
        for (i in 0 until 60) {
            val temp = 100.0 + (2.0 / 60.0) * i  // +2°C/min at 1Hz
            predictor.recordSample("OIL", t0 + i * 1000L, temp)
        }
        val pred = predictor.predict("OIL", critThresholdC = 130.0, operatingFloorC = 80.0)
        assertNotNull(pred)
        assertEquals(2.0, pred!!.climbRateCPerMin, 0.1)
        assertNotNull(pred.timeToCriticalMin)
        assertEquals(15.0, pred.timeToCriticalMin!!, 1.0)
    }

    @Test
    fun `warmup gate prevents false alerts below operating floor`() {
        val t0 = 1_000_000L
        for (i in 0 until 30) {
            predictor.recordSample("OIL", t0 + i * 1000L, 40.0 + i * 0.5)
        }
        val pred = predictor.predict("OIL", critThresholdC = 130.0, operatingFloorC = 80.0)
        assertNull("Should return null when current temp is below operating floor", pred)
    }

    @Test
    fun `cooling returns no time to critical`() {
        val t0 = 1_000_000L
        for (i in 0 until 30) {
            predictor.recordSample("OIL", t0 + i * 1000L, 120.0 - i * 0.5)
        }
        val pred = predictor.predict("OIL", critThresholdC = 130.0, operatingFloorC = 80.0)
        assertNotNull(pred)
        assertNull("Cooling should have no time-to-critical", pred!!.timeToCriticalMin)
    }

    @Test
    fun `already at critical returns zero time`() {
        val t0 = 1_000_000L
        for (i in 0 until 15) {
            predictor.recordSample("OIL", t0 + i * 1000L, 130.0 + i * 0.1)
        }
        val pred = predictor.predict("OIL", critThresholdC = 130.0, operatingFloorC = 80.0)
        assertNotNull(pred)
        assertEquals(0.0, pred!!.timeToCriticalMin!!, 0.01)
    }

    @Test
    fun `insufficient samples returns null`() {
        val t0 = 1_000_000L
        for (i in 0 until 5) {
            predictor.recordSample("OIL", t0 + i * 1000L, 100.0 + i)
        }
        assertNull(predictor.predict("OIL", critThresholdC = 130.0, operatingFloorC = 80.0))
    }

    @Test
    fun `unknown sensor returns null`() {
        assertNull(predictor.predict("NONEXISTENT", critThresholdC = 130.0, operatingFloorC = 80.0))
    }

    @Test
    fun `sentinel values are ignored`() {
        val t0 = 1_000_000L
        for (i in 0 until 20) {
            predictor.recordSample("OIL", t0 + i * 1000L, -99.0)
        }
        assertNull(predictor.predict("OIL", critThresholdC = 130.0, operatingFloorC = 80.0))
    }

    // ── Trend classification ────────────────────────────────────────────────

    @Test
    fun `rising trend classified correctly`() {
        val t0 = 1_000_000L
        for (i in 0 until 30) {
            predictor.recordSample("OIL", t0 + i * 1000L, 90.0 + i * (2.0 / 60.0))
        }
        assertEquals(ClimbTrend.RISING, predictor.trend("OIL"))
    }

    @Test
    fun `cooling trend classified correctly`() {
        val t0 = 1_000_000L
        for (i in 0 until 30) {
            predictor.recordSample("OIL", t0 + i * 1000L, 120.0 - i * (2.0 / 60.0))
        }
        assertEquals(ClimbTrend.COOLING, predictor.trend("OIL"))
    }

    @Test
    fun `stable trend classified correctly`() {
        val t0 = 1_000_000L
        for (i in 0 until 30) {
            predictor.recordSample("OIL", t0 + i * 1000L, 100.0 + i * 0.001)
        }
        assertEquals(ClimbTrend.STABLE, predictor.trend("OIL"))
    }

    @Test
    fun `unknown sensor trend is stable`() {
        assertEquals(ClimbTrend.STABLE, predictor.trend("NONEXISTENT"))
    }

    // ── Dismiss / snooze ────────────────────────────────────────────────────

    @Test
    fun `shouldAlert returns true when approaching critical`() {
        val t0 = 1_000_000L
        for (i in 0 until 30) {
            predictor.recordSample("OIL", t0 + i * 1000L, 120.0 + i * (5.0 / 60.0))
        }
        assertTrue(predictor.shouldAlert("OIL", 130.0, 80.0, horizonMin = 5.0))
    }

    @Test
    fun `shouldAlert returns false after dismiss`() {
        val t0 = 1_000_000L
        for (i in 0 until 30) {
            predictor.recordSample("OIL", t0 + i * 1000L, 120.0 + i * (5.0 / 60.0))
        }
        predictor.dismiss("OIL")
        assertFalse(predictor.shouldAlert("OIL", 130.0, 80.0))
    }

    @Test
    fun `shouldAlert returns false when cooling`() {
        val t0 = 1_000_000L
        for (i in 0 until 30) {
            predictor.recordSample("OIL", t0 + i * 1000L, 120.0 - i * 0.5)
        }
        assertFalse(predictor.shouldAlert("OIL", 130.0, 80.0))
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all state`() {
        val t0 = 1_000_000L
        for (i in 0 until 20) {
            predictor.recordSample("OIL", t0 + i * 1000L, 100.0 + i)
        }
        predictor.dismiss("OIL")
        predictor.reset()
        assertNull(predictor.predict("OIL", 130.0, 80.0))
        assertEquals(ClimbTrend.STABLE, predictor.trend("OIL"))
    }

    // ── Window trimming ─────────────────────────────────────────────────────

    @Test
    fun `old samples are trimmed from window`() {
        val t0 = 1_000_000L
        // Feed 200 seconds of data (window is 120s)
        for (i in 0 until 200) {
            predictor.recordSample("OIL", t0 + i * 1000L, 90.0 + i * (1.0 / 60.0))
        }
        val pred = predictor.predict("OIL", critThresholdC = 130.0, operatingFloorC = 80.0)
        assertNotNull(pred)
        // Current temp should be near 90 + 200/60 ≈ 93.3, not the first sample
        assertTrue(pred!!.currentC > 92.0)
    }

    // ── Multiple sensors ────────────────────────────────────────────────────

    @Test
    fun `multiple sensors tracked independently`() {
        val t0 = 1_000_000L
        for (i in 0 until 30) {
            predictor.recordSample("OIL", t0 + i * 1000L, 100.0 + i * (2.0 / 60.0))
            predictor.recordSample("COOLANT", t0 + i * 1000L, 95.0 - i * (1.0 / 60.0))
        }
        assertEquals(ClimbTrend.RISING, predictor.trend("OIL"))
        assertEquals(ClimbTrend.COOLING, predictor.trend("COOLANT"))
    }
}
