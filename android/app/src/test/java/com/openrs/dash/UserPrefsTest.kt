package com.openrs.dash

import com.openrs.dash.ui.UnitConversions
import com.openrs.dash.ui.UserPrefs
import org.junit.Assert.*
import org.junit.Test

class UserPrefsTest {

    private fun prefs(
        speedUnit: String = "MPH",
        tempUnit: String = "F",
        boostUnit: String = "PSI",
        tireUnit: String = "PSI",
        tireLowPsi: Float = 30f
    ) = UserPrefs(
        speedUnit = speedUnit,
        tempUnit = tempUnit,
        boostUnit = boostUnit,
        tireUnit = tireUnit,
        tireLowPsi = tireLowPsi
    )

    // ═════════════════════════════════════════════════════════════════════════
    // displayTemp — C→F conversion formula
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `displayTemp fahrenheit converts 0C to 32F`() {
        assertEquals("32", prefs(tempUnit = "F").displayTemp(0.0))
    }

    @Test fun `displayTemp fahrenheit converts 100C to 212F`() {
        assertEquals("212", prefs(tempUnit = "F").displayTemp(100.0))
    }

    @Test fun `displayTemp negative crossover point`() {
        // -40°C = -40°F — the only temperature where both scales match
        assertEquals("-40", prefs(tempUnit = "F").displayTemp(-40.0))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // displaySpeed — KPH→MPH conversion factor
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `displaySpeed mph converts kph correctly`() {
        val expected = "%.0f".format(100.0 * UnitConversions.KM_TO_MI)
        assertEquals(expected, prefs(speedUnit = "MPH").displaySpeed(100.0))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // displayBoost — absolute kPa → gauge pressure with atmospheric offset
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `displayBoost psi at atmospheric is zero`() {
        val (value, _) = prefs(boostUnit = "PSI").displayBoost(UnitConversions.STD_ATM_KPA)
        assertEquals("0.0", value)
    }

    @Test fun `displayBoost psi positive boost`() {
        // 200 kPa absolute → gauge PSI via atmospheric offset
        val (value, _) = prefs(boostUnit = "PSI").displayBoost(200.0)
        val expected = (200.0 - UnitConversions.STD_ATM_KPA) * UnitConversions.KPA_TO_PSI
        assertEquals("%.1f".format(expected), value)
    }

    @Test fun `displayBoost bar converts through PSI`() {
        val (value, _) = prefs(boostUnit = "BAR").displayBoost(200.0)
        val psi = (200.0 - UnitConversions.STD_ATM_KPA) * UnitConversions.KPA_TO_PSI
        assertEquals("%.2f".format(psi * UnitConversions.PSI_TO_BAR), value)
    }

    @Test fun `displayBoost kpa shows gauge not absolute`() {
        val (value, _) = prefs(boostUnit = "KPA").displayBoost(200.0)
        assertEquals("%.0f".format(200.0 - UnitConversions.STD_ATM_KPA), value)
    }

    @Test fun `displayBoost vacuum shows negative`() {
        val (value, _) = prefs(boostUnit = "PSI").displayBoost(80.0)
        assertTrue("Vacuum should be negative", value.startsWith("-"))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // displayTire — PSI→BAR conversion
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `displayTire bar converts correctly`() {
        val expected = "%.2f".format(35.0 * UnitConversions.PSI_TO_BAR)
        assertEquals(expected, prefs(tireUnit = "BAR").displayTire(35.0))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // isTireLow — sentinel exclusion is the critical contract here
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `isTireLow below threshold returns true`() {
        assertTrue(prefs(tireLowPsi = 30f).isTireLow(25.0))
    }

    @Test fun `isTireLow above threshold returns false`() {
        assertFalse(prefs(tireLowPsi = 30f).isTireLow(35.0))
    }

    @Test fun `isTireLow zero excluded — sentinel not-yet-received`() {
        // 0.0 means "TPMS hasn't polled yet", must NOT trigger low-pressure warning
        assertFalse(prefs(tireLowPsi = 30f).isTireLow(0.0))
    }

    @Test fun `isTireLow just above zero is real data`() {
        assertTrue(prefs(tireLowPsi = 30f).isTireLow(0.01))
    }

    @Test fun `isTireLow negative sentinel excluded`() {
        // -1.0 is the "not yet polled" sentinel
        assertFalse(prefs(tireLowPsi = 30f).isTireLow(-1.0))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // tireLowDisplay — threshold conversion for settings display
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `tireLowDisplay bar converts threshold`() {
        val expected = 30f * UnitConversions.PSI_TO_BAR.toFloat()
        assertEquals(expected, prefs(tireUnit = "BAR", tireLowPsi = 30f).tireLowDisplay, 0.01f)
    }
}
