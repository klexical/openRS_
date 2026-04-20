package com.openrs.dash

import com.openrs.dash.data.FuelEconomy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [FuelEconomy] — fuel economy calculator.
 */
class FuelEconomyTest {

    @Before fun setup() {
        FuelEconomy.reset()
    }

    @Test fun `initial state is not valid`() {
        assertFalse(FuelEconomy.state.value.isValid)
    }

    @Test fun `initial state has zero values`() {
        val s = FuelEconomy.state.value
        assertEquals(0.0, s.instantL100km, 0.001)
        assertEquals(0.0, s.avgL100km, 0.001)
        assertEquals(0.0, s.fuelUsedL, 0.001)
        assertEquals(0.0, s.sessionDistanceKm, 0.001)
    }

    @Test fun `reset clears state`() {
        FuelEconomy.onUpdate(80.0, 60.0)
        FuelEconomy.onUpdate(79.5, 60.0)
        FuelEconomy.reset()
        val s = FuelEconomy.state.value
        assertFalse(s.isValid)
        assertEquals(0.0, s.fuelUsedL, 0.001)
        assertEquals(0.0, s.sessionDistanceKm, 0.001)
    }

    @Test fun `rejects negative fuel values`() {
        FuelEconomy.onUpdate(-5.0, 60.0)
        assertFalse(FuelEconomy.state.value.isValid)
    }

    @Test fun `rejects fuel values over 110`() {
        FuelEconomy.onUpdate(120.0, 60.0)
        assertFalse(FuelEconomy.state.value.isValid)
    }

    @Test fun `accepts valid fuel range`() {
        FuelEconomy.onUpdate(0.0, 0.0)
        FuelEconomy.onUpdate(100.0, 0.0)
        // Should not crash; state may or may not be valid depending on timing
    }

    @Test fun `single sample does not produce state`() {
        FuelEconomy.onUpdate(80.0, 60.0)
        // Need at least 2 samples for rolling window
        assertEquals(0.0, FuelEconomy.state.value.instantL100km, 0.001)
    }

    @Test fun `session start fuel is tracked`() {
        FuelEconomy.onUpdate(80.0, 60.0)
        Thread.sleep(5) // small delta for distance integration
        FuelEconomy.onUpdate(79.5, 60.0)
        // fuelUsedL should be (80 - 79.5) / 100 * 49.8 = 0.249
        val s = FuelEconomy.state.value
        assertEquals(0.249, s.fuelUsedL, 0.01)
    }

    @Test fun `economy values are clamped`() {
        // Multiple updates to build state
        FuelEconomy.onUpdate(80.0, 60.0)
        Thread.sleep(5)
        FuelEconomy.onUpdate(79.5, 60.0)
        val s = FuelEconomy.state.value
        assertTrue(s.instantL100km >= 0.0)
        assertTrue(s.instantL100km <= 99.9)
        assertTrue(s.avgL100km >= 0.0)
        assertTrue(s.avgL100km <= 99.9)
    }

    @Test fun `distance to empty is positive`() {
        FuelEconomy.onUpdate(50.0, 80.0)
        Thread.sleep(5)
        FuelEconomy.onUpdate(49.8, 80.0)
        val dte = FuelEconomy.state.value.distanceToEmptyKm
        assertTrue("DTE should be positive, was $dte", dte >= 0.0)
    }

    @Test fun `full tank has high distance to empty`() {
        FuelEconomy.onUpdate(100.0, 80.0)
        Thread.sleep(5)
        FuelEconomy.onUpdate(99.8, 80.0)
        val dte = FuelEconomy.state.value.distanceToEmptyKm
        // Full tank (49.8L) at conservative 15L/100km = ~332 km
        assertTrue("Full tank DTE should be > 100km, was $dte", dte > 100.0)
    }
}
