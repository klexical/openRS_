package com.openrs.dash

import com.openrs.dash.data.BoostSweepStats
import com.openrs.dash.data.DrivePointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BoostSweepStats — rc.4 — target-vs-actual delta analytics for a drive.
 *
 * Verifies:
 *   • Loaded-sample filter (target >= 110 kPa) excludes idle/cruise points.
 *   • Average delta and worst-cell math are correct.
 *   • WGDC saturation flag flips at the 95% threshold.
 *   • Empty / all-idle input returns null (no stats shown).
 */
class BoostSweepStatsTest {

    private fun pt(
        rpm: Int,
        actualKpa: Double,
        targetKpa: Double,
        wgdc: Double = 0.0,
    ) = DrivePointEntity(
        driveId = 1L,
        timestamp = 0L,
        lat = 0.0,
        lng = 0.0,
        rpm = rpm,
        tipActualKpa = actualKpa,
        boostTargetKpa = targetKpa,
        wgdcPct = wgdc
    )

    @Test
    fun `empty points returns null`() {
        assertNull(BoostSweepStats.from(emptyList()))
    }

    @Test
    fun `all idle points returns null`() {
        // All targets below the loaded threshold (110 kPa) → no analyzable sweep
        val pts = listOf(
            pt(900, 100.0, 101.0),
            pt(1000, 101.0, 102.0),
            pt(1200, 102.0, 103.0)
        )
        assertNull(BoostSweepStats.from(pts))
    }

    @Test
    fun `perfect tracking yields near-zero avg delta`() {
        // Target == Actual at every loaded sample
        val pts = listOf(
            pt(3000, 150.0, 150.0, wgdc = 40.0),
            pt(4000, 180.0, 180.0, wgdc = 55.0),
            pt(5000, 200.0, 200.0, wgdc = 70.0)
        )
        val s = BoostSweepStats.from(pts)
        assertNotNull(s)
        assertEquals(0.0, s!!.avgDeltaPsi, 0.001)
        assertEquals(3, s.sampleCount)
    }

    @Test
    fun `underboost shows negative worst delta at correct rpm`() {
        // Target 200 kPa, actual 180 kPa at RPM 4800 → delta ~ -2.9 PSI
        val pts = listOf(
            pt(3000, 150.0, 150.0, wgdc = 40.0),
            pt(4800, 180.0, 200.0, wgdc = 85.0),  // 20 kPa short
            pt(5500, 200.0, 200.0, wgdc = 65.0)
        )
        val s = BoostSweepStats.from(pts)
        assertNotNull(s)
        assertEquals(4800, s!!.worstRpm)
        // 20 kPa × 0.14503773 ≈ -2.9 PSI
        assertTrue("worst delta is negative", s.worstDeltaPsi < -2.5)
        assertTrue("worst delta is not more than -3.0 PSI", s.worstDeltaPsi > -3.1)
    }

    @Test
    fun `wgdc saturation flag flips at 95pct threshold`() {
        val below = listOf(pt(5000, 180.0, 200.0, wgdc = 94.9))
        val at = listOf(pt(5000, 180.0, 200.0, wgdc = 95.0))
        val above = listOf(pt(5000, 180.0, 200.0, wgdc = 98.5))

        assertEquals(false, BoostSweepStats.from(below)!!.wgdcSaturated)
        assertEquals(true, BoostSweepStats.from(at)!!.wgdcSaturated)
        assertEquals(true, BoostSweepStats.from(above)!!.wgdcSaturated)
    }

    @Test
    fun `idle points filtered out of average`() {
        val pts = listOf(
            pt(800, 100.0, 100.0, wgdc = 0.0),      // idle — excluded
            pt(1200, 100.0, 101.0, wgdc = 0.0),     // idle — excluded
            pt(5000, 180.0, 180.0, wgdc = 50.0)     // loaded — included
        )
        val s = BoostSweepStats.from(pts)
        assertNotNull(s)
        assertEquals(1, s!!.sampleCount)
        assertEquals(0.0, s.avgDeltaPsi, 0.001)
    }

    @Test
    fun `maxWgdc returns peak across loaded samples`() {
        val pts = listOf(
            pt(3000, 150.0, 150.0, wgdc = 42.0),
            pt(5000, 180.0, 180.0, wgdc = 88.5),
            pt(6000, 200.0, 200.0, wgdc = 71.2)
        )
        val s = BoostSweepStats.from(pts)
        assertEquals(88.5, s!!.maxWgdc, 0.01)
    }
}
