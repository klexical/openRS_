package com.openrs.dash

import com.openrs.dash.ui.Level
import com.openrs.dash.ui.TyreIssue
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.tireLevel
import com.openrs.dash.ui.tyreIssues
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

    // ── DRIVE's alerts use the same classification ──────────────────────────────

    private fun issues(fl: Double, fr: Double, rl: Double, rr: Double, low: Double = 30.0, warn: Double = 34.0, high: Double = 50.0) =
        tyreIssues(listOf("FL" to fl, "FR" to fr, "RL" to rl, "RR" to rr), low, warn, high)

    @Test
    fun `DRIVE tyre alerts - normal, low, caution and high`() {
        assertEquals(emptyList<Any>(), issues(36.0, 36.0, 35.0, 35.0))
        assertEquals(listOf(Triple("RL", 29.0, TyreIssue.LOW)), issues(36.0, 36.0, 29.0, 35.0))
        assertEquals(listOf(Triple("RL", 33.0, TyreIssue.CAUTION)), issues(36.0, 36.0, 33.0, 35.0))
        assertEquals(listOf(Triple("FR", 52.0, TyreIssue.HIGH)), issues(36.0, 52.0, 35.0, 35.0))   // was ALL NORMAL on DRIVE
    }

    @Test
    fun `DRIVE tyre alerts - boundaries match THERMAL and missing readings are ignored`() {
        assertEquals(TyreIssue.CAUTION, issues(36.0, 36.0, 30.0, 35.0).single().third)   // exactly Low = caution, as THERMAL
        assertEquals(emptyList<Any>(), issues(36.0, 36.0, 34.0, 35.0))                     // exactly Warn = normal
        assertEquals(emptyList<Any>(), issues(50.0, 36.0, 34.0, 35.0))                     // exactly High = normal
        assertEquals(TyreIssue.HIGH, issues(50.01, 36.0, 34.0, 35.0).single().third)
        assertEquals(emptyList<Any>(), issues(-1.0, -1.0, -1.0, -1.0))                     // not polled yet
        assertEquals(emptyList<Any>(), issues(-1.0, 36.0, -1.0, 35.0))
    }

    @Test
    fun `DRIVE tyre alerts - custom thresholds, low listed before high`() {
        val both = issues(41.0, 36.0, 27.0, 35.0, low = 28.0, warn = 33.0, high = 40.0)
        assertEquals(listOf(Triple("RL", 27.0, TyreIssue.LOW), Triple("FL", 41.0, TyreIssue.HIGH)), both)
    }
}
