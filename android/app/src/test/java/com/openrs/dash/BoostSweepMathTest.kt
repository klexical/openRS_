package com.openrs.dash

import com.openrs.dash.data.BoostSessionLog
import com.openrs.dash.ui.BoostSweepMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BoostSweepMath — rc.6 (#175): RPM binning behind the POWER boost sweep chart.
 */
class BoostSweepMathTest {

    private fun s(rpm: Double, actual: Double, target: Double? = null, wgdc: Double? = null) =
        BoostSessionLog.Sample(tMs = 0L, rpm = rpm, actualKpa = actual, targetKpa = target, wgdcPct = wgdc)

    @Test
    fun `bin edges are half-open and out-of-range rpm is dropped`() {
        assertNull(BoostSweepMath.binIndex(1_499.0))
        assertEquals(0, BoostSweepMath.binIndex(1_500.0))
        assertEquals(0, BoostSweepMath.binIndex(1_749.9))
        assertEquals(1, BoostSweepMath.binIndex(1_750.0))
        assertEquals(BoostSweepMath.BIN_COUNT - 1, BoostSweepMath.binIndex(6_999.0))
        assertNull(BoostSweepMath.binIndex(7_000.0))
    }

    @Test
    fun `bins average each channel and ignore nulls per channel`() {
        val bins = BoostSweepMath.bins(listOf(
            s(4_010.0, 200.0, target = 220.0, wgdc = 50.0),
            s(4_200.0, 210.0, target = null, wgdc = 70.0)
        ))
        val b = bins[BoostSweepMath.binIndex(4_100.0)!!]!!
        assertEquals(2, b.samples)
        assertEquals(205.0, b.actualKpa, 1e-9)
        assertEquals(220.0, b.targetKpa!!, 1e-9)
        assertEquals(60.0, b.wgdcPct!!, 1e-9)
        assertEquals(4_125.0, b.rpmMid, 1e-9)
    }

    @Test
    fun `empty bins are null so the chart draws a gap`() {
        val bins = BoostSweepMath.bins(listOf(s(2_000.0, 120.0), s(5_000.0, 230.0), s(800.0, 100.0)))
        assertEquals(BoostSweepMath.BIN_COUNT, bins.size)
        assertEquals(2, bins.count { it != null })   // 800 rpm dropped
        assertNull(bins[BoostSweepMath.binIndex(3_000.0)!!])
    }

    @Test
    fun `kpa range covers actual and target with a minimum span`() {
        val wide = BoostSweepMath.kpaRange(BoostSweepMath.bins(listOf(s(3_000.0, 110.0, target = 260.0))))
        assertEquals(110.0 to 260.0, wide)
        val flat = BoostSweepMath.kpaRange(BoostSweepMath.bins(listOf(s(2_000.0, 100.0))))
        assertTrue(flat.second - flat.first >= BoostSweepMath.MIN_SPAN_KPA)
        assertEquals(100.0, (flat.first + flat.second) / 2, 1e-9)
    }
}
