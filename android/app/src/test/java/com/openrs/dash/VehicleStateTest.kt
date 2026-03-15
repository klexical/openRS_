package com.openrs.dash

import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import org.junit.Assert.*
import org.junit.Test

class VehicleStateTest {

    @Test
    fun `boost PSI calculated correctly from kPa`() {
        val state = VehicleState(boostKpa = 200.0)
        assertEquals(14.3, state.boostPsi, 0.1)
    }

    @Test
    fun `boost PSI is negative at atmospheric pressure`() {
        val state = VehicleState(boostKpa = 80.0)
        assertTrue(state.boostPsi < 0)
    }

    @Test
    fun `speed MPH conversion`() {
        val state = VehicleState(speedKph = 100.0)
        assertEquals(62.1, state.speedMph, 0.1)
    }

    @Test
    fun `combined G calculation`() {
        val state = VehicleState(lateralG = 0.6, longitudinalG = 0.8)
        assertEquals(1.0, state.combinedG, 0.01)
    }

    @Test
    fun `AWD split shows 100-0 with no rear torque`() {
        val state = VehicleState(torqueAtTrans = 200.0, awdLeftTorque = 0.0, awdRightTorque = 0.0)
        assertEquals("100/0", state.frontRearSplit)
    }

    @Test
    fun `AWD split shows 50-50 with equal torque`() {
        val state = VehicleState(torqueAtTrans = 200.0, awdLeftTorque = 50.0, awdRightTorque = 50.0)
        assertEquals("50/50", state.frontRearSplit)
    }

    @Test
    fun `AWD split shows 100-0 with zero trans torque`() {
        val state = VehicleState(torqueAtTrans = 0.0, awdLeftTorque = 0.0, awdRightTorque = 0.0)
        assertEquals("100/0", state.frontRearSplit)
    }

    @Test
    fun `rear left-right bias 50-50 with equal rear torque`() {
        val state = VehicleState(awdLeftTorque = 100.0, awdRightTorque = 100.0)
        assertEquals("50/50", state.rearLeftRightBias)
    }

    @Test
    fun `rear left-right bias defaults when no rear torque`() {
        val state = VehicleState(awdLeftTorque = 0.0, awdRightTorque = 0.0)
        assertEquals("50/50", state.rearLeftRightBias)
    }

    @Test
    fun `gear display maps correctly`() {
        assertEquals("N", VehicleState(gear = 0).gearDisplay)
        assertEquals("1", VehicleState(gear = 1).gearDisplay)
        assertEquals("6", VehicleState(gear = 6).gearDisplay)
        assertEquals("R", VehicleState(gear = 7).gearDisplay)
    }

    @Test
    fun `derived gear returns N when stationary`() {
        val state = VehicleState(speedKph = 0.0, rpm = 800.0)
        assertEquals(0, state.derivedGear)
    }

    @Test
    fun `derived gear returns R when reverse engaged`() {
        val state = VehicleState(speedKph = 10.0, rpm = 1500.0, reverseStatus = true)
        assertEquals(7, state.derivedGear)
    }

    @Test
    fun `ready to race requires all temps`() {
        val cold = VehicleState(oilTempC = 40.0, rduTempC = 10.0, ptuTempC = 30.0)
        assertFalse(cold.isReadyToRace)

        val warm = VehicleState(oilTempC = 80.0, rduTempC = 30.0, ptuTempC = 60.0)
        assertTrue(warm.isReadyToRace)
    }

    @Test
    fun `rtrStatus returns null when ready (all temps above thresholds)`() {
        val warm = VehicleState(oilTempC = 85.0, coolantTempC = 90.0, rduTempC = 35.0, ptuTempC = 45.0)
        assertNull(warm.rtrStatus)
        assertTrue(warm.isReadyToRace)
    }

    @Test
    fun `rtrStatus returns description when warming up`() {
        val cold = VehicleState(oilTempC = 40.0, coolantTempC = 50.0, rduTempC = 10.0, ptuTempC = 20.0)
        assertNotNull(cold.rtrStatus)
        assertTrue(cold.rtrStatus!!.contains("Oil"))
        assertTrue(cold.rtrStatus!!.contains("Coolant"))
        assertFalse(cold.isReadyToRace)
    }

    @Test
    fun `rtrStatus ignores sentinel values (not yet received)`() {
        val fresh = VehicleState(oilTempC = -99.0, coolantTempC = -99.0, rduTempC = -99.0, ptuTempC = -99.0)
        assertNull(fresh.rtrStatus)
        assertTrue(fresh.isReadyToRace)
    }

    @Test
    fun `peak tracking updates higher values`() {
        val state = VehicleState(boostKpa = 250.0, rpm = 6000.0, peakBoostPsi = 10.0, peakRpm = 5000.0)
        val updated = state.withPeaksUpdated()
        assertTrue(updated.peakBoostPsi > 10.0)
        assertEquals(6000.0, updated.peakRpm, 0.1)
    }

    @Test
    fun `peak tracking does not lower peaks`() {
        val state = VehicleState(boostKpa = 101.325, rpm = 3000.0, peakBoostPsi = 20.0, peakRpm = 7000.0)
        val updated = state.withPeaksUpdated()
        assertEquals(20.0, updated.peakBoostPsi, 0.01)
        assertEquals(7000.0, updated.peakRpm, 0.01)
    }

    @Test
    fun `peak reset clears all peaks`() {
        val state = VehicleState(peakBoostPsi = 25.0, peakRpm = 7000.0, peakLateralG = 1.2, peakLongitudinalG = 0.9)
        val reset = state.withPeaksReset()
        assertEquals(0.0, reset.peakBoostPsi, 0.01)
        assertEquals(0.0, reset.peakRpm, 0.01)
        assertEquals(0.0, reset.peakLateralG, 0.01)
        assertEquals(0.0, reset.peakLongitudinalG, 0.01)
    }

    @Test
    fun `TPMS data detection`() {
        val noData = VehicleState(tirePressLF = -1.0)
        assertFalse(noData.hasTpmsData)

        val hasData = VehicleState(tirePressLF = 35.0, tirePressRF = 35.0, tirePressLR = 35.0, tirePressRR = 35.0)
        assertTrue(hasData.hasTpmsData)
        assertFalse(hasData.anyTireLow())
    }

    @Test
    fun `low tire pressure detection`() {
        val lowTire = VehicleState(tirePressLF = 25.0, tirePressRF = 35.0, tirePressLR = 35.0, tirePressRR = 35.0)
        assertTrue(lowTire.anyTireLow())
    }

    @Test
    fun `flat tire (0 PSI) is not flagged as low`() {
        // Current implementation uses 0.01..< threshold which excludes 0.0
        // A flat tire reading of exactly 0.0 PSI won't trigger the warning.
        // This test documents the current behavior.
        val flatTire = VehicleState(tirePressLF = 0.0, tirePressRF = 35.0, tirePressLR = 35.0, tirePressRR = 35.0)
        assertFalse(flatTire.anyTireLow())
    }

    @Test
    fun `sentinel tire pressure not flagged as low`() {
        val sentinel = VehicleState(tirePressLF = -1.0, tirePressRF = -1.0, tirePressLR = -1.0, tirePressRR = -1.0)
        assertFalse(sentinel.anyTireLow())
    }

    @Test
    fun `tire pressure spread calculation`() {
        val state = VehicleState(tirePressLF = 32.0, tirePressRF = 35.0, tirePressLR = 33.0, tirePressRR = 34.0)
        assertEquals(3.0, state.maxTirePressSpread, 0.01)
    }

    @Test
    fun `tire pressure spread with sentinel values`() {
        val state = VehicleState(tirePressLF = 32.0, tirePressRF = -1.0, tirePressLR = 33.0, tirePressRR = -1.0)
        assertEquals(1.0, state.maxTirePressSpread, 0.01)
    }

    @Test
    fun `tire pressure spread with single valid returns zero`() {
        val state = VehicleState(tirePressLF = 32.0, tirePressRF = -1.0, tirePressLR = -1.0, tirePressRR = -1.0)
        assertEquals(0.0, state.maxTirePressSpread, 0.01)
    }

    @Test
    fun `TIP PSI conversion`() {
        val state = VehicleState(tipActualKpa = 200.0)
        assertTrue(state.tipActualPsi > 0)
    }

    @Test
    fun `fuel rail PSI conversion`() {
        val state = VehicleState(fuelRailPressure = 5000.0)
        assertTrue(state.fuelRailPsi > 0)
        assertEquals(5000.0 * 0.14503773, state.fuelRailPsi, 0.1)
    }

    @Test
    fun `drive mode enum mapping`() {
        assertEquals(DriveMode.NORMAL, DriveMode.fromInt(0))
        assertEquals(DriveMode.SPORT, DriveMode.fromInt(1))
        assertEquals(DriveMode.DRIFT, DriveMode.fromInt(2))
        assertEquals(DriveMode.UNKNOWN, DriveMode.fromInt(99))
    }

    @Test
    fun `drive mode fromInt(3) returns UNKNOWN (Track resolved via CanDecoder)`() {
        // Track (3) is NOT in the DBC DriveMode VAL_ 432; it's resolved by CanDecoder
        // combining 0x1B0 nibble=1 with 0x420 byte7 bit0=1. fromInt only maps DBC values.
        assertEquals(DriveMode.UNKNOWN, DriveMode.fromInt(3))
    }

    @Test
    fun `ESC status enum mapping`() {
        assertEquals(EscStatus.ON, EscStatus.fromInt(0))
        assertEquals(EscStatus.PARTIAL, EscStatus.fromInt(1))
        assertEquals(EscStatus.OFF, EscStatus.fromInt(2))
        assertEquals(EscStatus.UNKNOWN, EscStatus.fromInt(99))
    }
}
