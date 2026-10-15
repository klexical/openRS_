package com.openrs.dash

import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.FuelTrimAccumulator
import com.openrs.dash.data.FuelTrimDrift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fuel trim drift — rc.5 (roadmap 2.2): per-drive averages use only fresh, warm-engine
 * polls; the drift alert needs two consecutive drives beyond ±8 % LTFT in one direction.
 */
class FuelTrimTest {

    private fun FuelTrimAccumulator.warm(stft: Double, ltft: Double, fresh: Boolean = true) =
        add(fresh, stft, fresh, ltft, coolantC = 90.0, rpm = 2500.0)

    @Test
    fun `averages fresh warm samples`() {
        val acc = FuelTrimAccumulator()
        acc.warm(2.0, 5.0); acc.warm(4.0, 7.0); acc.warm(0.0, 6.0)
        val r = acc.result()
        assertEquals(2.0, r.avgStftPct!!, 1e-9)
        assertEquals(6.0, r.avgLtftPct!!, 1e-9)
        assertEquals(3, r.samples)
    }

    @Test
    fun `cold engine and engine-off samples are excluded`() {
        val acc = FuelTrimAccumulator()
        acc.add(true, 20.0, true, 20.0, coolantC = 45.0, rpm = 2500.0)   // open-loop warm-up
        acc.add(true, 20.0, true, 20.0, coolantC = 90.0, rpm = 0.0)      // key on, engine off
        repeat(3) { acc.warm(1.0, 2.0) }
        assertEquals(2.0, acc.result().avgLtftPct!!, 1e-9)
        assertEquals(3, acc.result().samples)
    }

    @Test
    fun `held (non-fresh) values are not re-counted`() {
        val acc = FuelTrimAccumulator()
        repeat(3) { acc.warm(1.0, 3.0) }
        repeat(10) { acc.warm(9.0, 9.0, fresh = false) }
        assertEquals(3.0, acc.result().avgLtftPct!!, 1e-9)
    }

    @Test
    fun `fewer than minimum samples stores no average`() {
        val acc = FuelTrimAccumulator()
        acc.warm(1.0, 3.0); acc.warm(1.0, 3.0)
        val r = acc.result()
        assertNull(r.avgStftPct)
        assertNull(r.avgLtftPct)
        assertEquals(2, r.samples)
    }

    private fun drive(ltft: Double?) = DriveEntity(startTime = 0, avgLtftPct = ltft)

    @Test
    fun `two consecutive lean drives raise a LEAN alert`() {
        assertEquals(FuelTrimDrift.Direction.LEAN,
            FuelTrimDrift.alert(listOf(drive(9.5), drive(8.4), drive(1.0))))
    }

    @Test
    fun `two consecutive rich drives raise a RICH alert`() {
        assertEquals(FuelTrimDrift.Direction.RICH, FuelTrimDrift.alert(listOf(drive(-8.5), drive(-11.0))))
    }

    @Test
    fun `single excursion, mixed direction, or threshold value raise nothing`() {
        assertNull(FuelTrimDrift.alert(listOf(drive(12.0), drive(3.0))))
        assertNull(FuelTrimDrift.alert(listOf(drive(9.0), drive(-9.0))))
        assertNull(FuelTrimDrift.alert(listOf(drive(8.0), drive(8.0))))   // must EXCEED 8 %
        assertNull(FuelTrimDrift.alert(listOf(drive(9.0))))
    }

    @Test
    fun `drives without an LTFT average are skipped`() {
        assertEquals(FuelTrimDrift.Direction.LEAN,
            FuelTrimDrift.alert(listOf(drive(9.0), drive(null), drive(10.0))))
    }
}
