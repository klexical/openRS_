package com.openrs.dash

import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.tpmsDeltaText
import com.openrs.dash.ui.units.UnitIds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * #509: the change since the session's first reading under each tyre on THERMAL (`ChassisPage.kt`, `tpmsDeltaText`)
 * prints in the chosen tyre unit with its unit text, through the units formatter (psi 1, bar 2, kPa 0 decimals, as the
 * pressure above it). It shows only when the change is over 0.5 psi, compared in psi whatever the unit. Readings are
 * synthetic.
 */
class TyreDeltaTextTest {
    private val psi = UserPrefs(tireUnit = UnitIds.PSI)
    private val bar = UserPrefs(tireUnit = UnitIds.BAR)
    private val kpa = UserPrefs(tireUnit = UnitIds.KPA)
    private val all = listOf(psi, bar, kpa)

    private var savedLocale: Locale = Locale.getDefault()
    @Before fun pinLocale() { savedLocale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(savedLocale) }

    @Test fun `a rise prints in psi, bar and kPa with the unit text`() {
        // +1.0 psi = +0.0689 bar = +6.89 kPa
        assertEquals("▲ +1.0 psi", tpmsDeltaText(37.0, 36.0, psi))
        assertEquals("▲ +0.07 bar", tpmsDeltaText(37.0, 36.0, bar))
        assertEquals("▲ +7 kPa", tpmsDeltaText(37.0, 36.0, kpa))
    }

    @Test fun `a drop prints in psi, bar and kPa with the unit text`() {
        // -1.5 psi = -0.103 bar = -10.3 kPa
        assertEquals("▼ -1.5 psi", tpmsDeltaText(34.5, 36.0, psi))
        assertEquals("▼ -0.10 bar", tpmsDeltaText(34.5, 36.0, bar))
        assertEquals("▼ -10 kPa", tpmsDeltaText(34.5, 36.0, kpa))
    }

    @Test fun `the trigger stays 0_5 psi in every unit`() {
        for (p in all) {
            assertEquals(p.tireUnit, "", tpmsDeltaText(36.5, 36.0, p))     // exactly 0.5 psi: hidden
            assertEquals(p.tireUnit, "", tpmsDeltaText(35.5, 36.0, p))
            assertEquals(p.tireUnit, "", tpmsDeltaText(36.4, 36.0, p))     // 0.4 psi = 2.8 kPa: hidden, not "3 kPa"
        }
        // Just over 0.5 psi shows in every unit, never as a zero
        assertEquals("▲ +0.6 psi", tpmsDeltaText(36.6, 36.0, psi))
        assertEquals("▲ +0.04 bar", tpmsDeltaText(36.6, 36.0, bar))
        assertEquals("▲ +4 kPa", tpmsDeltaText(36.6, 36.0, kpa))
        assertEquals("▼ -0.04 bar", tpmsDeltaText(35.4, 36.0, bar))
    }

    @Test fun `no start reading or no current reading - no change line`() {
        for (p in all) {
            assertEquals(p.tireUnit, "", tpmsDeltaText(37.0, -1.0, p))     // start not yet recorded (sentinel)
            assertEquals(p.tireUnit, "", tpmsDeltaText(-1.0, 36.0, p))     // not yet polled
            assertEquals(p.tireUnit, "", tpmsDeltaText(0.0, 36.0, p))
        }
    }
}
