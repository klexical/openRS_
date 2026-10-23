package com.openrs.dash

import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.demo.DemoLink
import com.openrs.dash.demo.DemoScenario
import com.openrs.dash.demo.DemoSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * DemoSimulator — debug demo mode. Frames must be repeatable, honour the real sentinel and
 * freshness-stamp contracts, and mirror what a real adapter drop / reconnect does to state.
 */
class DemoSimulatorTest {

    private val now = 1_700_000_000_000L

    private fun frame(
        scenario: DemoScenario, tMs: Long, cur: VehicleState = VehicleState(isConnected = true),
        stamp: Boolean = true,
    ) = DemoSimulator.apply(cur, scenario, tMs, DriveMode.NORMAL, EscStatus.ON, now, stamp)

    /** Runs the scenario from t = 0 the way DemoMode's 40 ms tick does, feeding state forward. */
    private fun run(scenario: DemoScenario, untilMs: Long, onFrame: (Long, VehicleState) -> Unit = { _, _ -> }): VehicleState {
        var vs = VehicleState(isConnected = true)
        var t = 0L
        while (t <= untilMs) {
            vs = frame(scenario, t, vs)
            onFrame(t, vs)
            t += 40
        }
        return vs
    }

    @Test
    fun `same scenario and time give identical frames`() {
        for (sc in DemoScenario.entries) {
            assertEquals(sc.name, frame(sc, 17_320), frame(sc, 17_320))
        }
    }

    @Test
    fun `lost link freezes telemetry and only flips connection flags`() {
        val live = frame(DemoScenario.CONNECTION_LOSS, 9_000)
        val lost = frame(DemoScenario.CONNECTION_LOSS, 15_000, live)
        assertEquals(DemoLink.LOST, DemoSimulator.link(DemoScenario.CONNECTION_LOSS, 15.0))
        assertEquals(live.copy(isConnected = false, isIdle = true), lost)
        // Already flagged lost → same instance, no re-emit
        assertSame(lost, frame(DemoScenario.CONNECTION_LOSS, 16_000, lost))
    }

    @Test
    fun `recovery resets polled fields to sentinels and stamps CAN tags only`() {
        val rec = frame(DemoScenario.CONNECTION_LOSS, 23_000, VehicleState())
        assertEquals(DemoLink.RECOVERING, DemoSimulator.link(DemoScenario.CONNECTION_LOSS, 23.0))
        assertTrue(rec.isConnected)
        assertEquals(-1.0, rec.tirePressLF, 0.0)
        assertEquals(-99.0, rec.rduTempC, 0.0)
        assertEquals(-1L, rec.odometerKm)
        assertEquals(-1.0, rec.batterySoc, 0.0)
        assertTrue(rec.oilTempC > -90)                           // CAN-sourced stays live
        assertEquals(now, rec.fieldLastUpdateMs["oilTempC"])
        assertNull(rec.fieldLastUpdateMs["tirePressLF"])
    }

    @Test
    fun `live frames stamp every polled tag and stamp false leaves stamps untouched`() {
        val stamped = frame(DemoScenario.DRIVING, 30_000)
        for (tag in DemoSimulator.CAN_TAGS + DemoSimulator.OBD_TAGS) {
            assertEquals(tag, now, stamped.fieldLastUpdateMs[tag])
        }
        assertTrue(frame(DemoScenario.DRIVING, 30_000, stamp = false).fieldLastUpdateMs.isEmpty())
    }

    @Test
    fun `fields with no real decoder stay at their sentinels`() {
        for (sc in DemoScenario.entries) {
            val vs = frame(sc, 45_000)
            assertEquals(sc.name, -99.0, vs.manifoldChargeTempC, 0.0)
            assertNull(sc.name, vs.warnMil)
            assertNull(sc.name, vs.warnTempHigh)
            assertNull(sc.name, vs.warnCharge)
            assertEquals(sc.name, 0, vs.gear)                     // 0x230 never broadcasts — derivedGear only
        }
    }

    @Test
    fun `warning scenario crosses the app's warning thresholds`() {
        var maxKr = 0.0
        val vs = run(DemoScenario.WARNING, 60_000) { _, s ->
            maxKr = maxOf(maxKr, s.ignCorrCyl1, s.ignCorrCyl2, s.ignCorrCyl3, s.ignCorrCyl4)
        }
        assertTrue("coolant ${vs.coolantTempC}", vs.coolantTempC > 108)      // thermalBandForCoolant CRITICAL
        assertTrue("oil ${vs.oilTempC}", vs.oilTempC > 125)                   // thermalBandForOil CRITICAL
        assertTrue("LR ${vs.tirePressLR}", vs.anyTireLow(30.0))
        assertTrue("fuel ${vs.fuelLevelPct}", vs.fuelLevelPct < 10)
        assertTrue("battery ${vs.batteryVoltage}", vs.batteryVoltage < 12.5)
        assertTrue("KR $maxKr", maxKr > 1.0)                                  // knock counter threshold
    }

    @Test
    fun `driving loop stays plausible and changes smoothly`() {
        var prev: VehicleState? = null
        val gears = mutableSetOf<String>()
        run(DemoScenario.DRIVING, 120_000) { t, s ->
            assertTrue("rpm ${s.rpm} @ $t", s.rpm in 600.0..6950.0)
            assertTrue("speed ${s.speedKph} @ $t", s.speedKph in 0.0..130.0)
            assertTrue("boost ${s.boostPsi} @ $t", s.boostPsi in -15.0..26.0)
            assertTrue("lat ${s.lateralG} @ $t", abs(s.lateralG) < 1.2)
            prev?.let { p -> assertTrue("speed step @ $t", abs(s.speedKph - p.speedKph) < 2.0) }
            gears += s.gearDisplay
            prev = s
        }
        assertTrue("gears seen $gears", gears.containsAll(listOf("N", "1", "2", "3")))
    }
}
