package com.openrs.dash

import com.openrs.dash.ui.Level
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.tireLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THERMAL tyre state — must follow the saved Low / Warn / High thresholds. Warn and High were hard-coded to
 * 34 / 50 psi there, so custom values changed DRIVE's alerts but not THERMAL's cards.
 */
class TyrePressureLevelTest {

    private fun level(psi: Double, p: UserPrefs) =
        tireLevel(psi, p.tireLowPsi.toDouble(), p.tireWarnPsi.toDouble(), p.tireHighPsi.toDouble())

    @Test
    fun `default thresholds keep the existing boundaries`() {
        val p = UserPrefs()   // 30 / 34 / 50 psi
        assertEquals(Level.NONE, level(-1.0, p))
        assertEquals(Level.CRIT, level(29.99, p))
        assertEquals(Level.WARN, level(30.0, p))      // Low itself is caution, below it critical
        assertEquals(Level.WARN, level(33.99, p))
        assertEquals(Level.NORMAL, level(34.0, p))
        assertEquals(Level.NORMAL, level(50.0, p))    // High itself is fine, above it over-inflated
        assertEquals(Level.CRIT, level(50.01, p))
    }

    @Test
    fun `custom thresholds are used for every boundary`() {
        val p = UserPrefs(tireLowPsi = 28f, tireWarnPsi = 36f, tireHighPsi = 40f)
        assertEquals(Level.CRIT, level(27.99, p))
        assertEquals(Level.WARN, level(28.0, p))
        assertEquals(Level.WARN, level(35.99, p))     // was NORMAL with the hard-coded 34
        assertEquals(Level.NORMAL, level(36.0, p))
        assertEquals(Level.NORMAL, level(40.0, p))
        assertEquals(Level.CRIT, level(40.01, p))     // was NORMAL with the hard-coded 50
    }

    @Test
    fun `display unit does not change the state`() {
        // Levels are computed on psi; BAR only changes the label. Same pressures, same thresholds → same state.
        val psi = UserPrefs(tireUnit = "PSI", tireLowPsi = 28f, tireWarnPsi = 36f, tireHighPsi = 40f)
        val bar = psi.copy(tireUnit = "BAR")
        listOf(27.5, 28.0, 35.9, 36.0, 40.0, 40.5).forEach { v ->
            assertEquals("at $v psi", level(v, psi), level(v, bar))
        }
    }
}
