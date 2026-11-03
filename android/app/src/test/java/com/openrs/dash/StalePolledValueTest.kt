package com.openrs.dash

import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.defaultCellFor
import com.openrs.dash.data.resolveValue
import com.openrs.dash.ui.UserPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #217 — a polled reading that stopped refreshing (DID suppressed, or no longer answering) must not be shown as current.
 * The 2026-10-28 PM drive held desired throttle at one value for ~451 s; here a knock-retard / TIP / WGDC reading
 * stamped long ago goes through the production Custom Dashboard formatter.
 */
class StalePolledValueTest {
    private val prefs = UserPrefs()
    private val placeholder = "— —"

    private fun running(stampAgeMs: Long, tag: String) = VehicleState(
        isConnected = true, rpm = 2_500.0,
        ignCorrCyl4 = 1.5, wgdcDesired = 42.0, tipActualKpa = 140.0,
        fieldLastUpdateMs = mapOf(tag to System.currentTimeMillis() - stampAgeMs),
    )

    @Test fun `knock retard polled 451 s ago is not shown as a current value`() {
        val vs = running(451_000L, "ignCorrCyl4")
        assertEquals(placeholder, resolveValue(vs, prefs, defaultCellFor("ignCorrCyl4")!!).first)
    }

    @Test fun `WGDC and TIP held past their fast-poll refresh read as unavailable`() {
        assertEquals(placeholder, resolveValue(running(30_000L, "wgdcDesired"), prefs, defaultCellFor("wgdcDesired")!!).first)
        assertEquals(placeholder, resolveValue(running(30_000L, "tipActualKpa"), prefs, defaultCellFor("tipActualKpa")!!).first)
    }

    @Test fun `a fresh reading still shows`() {
        assertNotEquals(placeholder, resolveValue(running(500L, "ignCorrCyl4"), prefs, defaultCellFor("ignCorrCyl4")!!).first)
        assertNotEquals(placeholder, resolveValue(running(500L, "wgdcDesired"), prefs, defaultCellFor("wgdcDesired")!!).first)
    }

    @Test fun `a reading never decoded this session is unavailable, not zero`() {
        val vs = VehicleState(isConnected = true, rpm = 2_500.0)
        assertEquals(placeholder, resolveValue(vs, prefs, defaultCellFor("ignCorrCyl1")!!).first)
    }

    @Test fun `freshness boundaries follow the poll schedule`() {
        val now = 1_000_000L
        fun vs(rpm: Double, tag: String, age: Long) = VehicleState(rpm = rpm, fieldLastUpdateMs = mapOf(tag to now - age))
        val fast = com.openrs.dash.data.PolledFreshness
        assertEquals(true, fast.isFresh(vs(2_500.0, "tipActualKpa", 4_000L), "tipActualKpa", now))      // 2 × 2 s
        assertEquals(false, fast.isFresh(vs(2_500.0, "tipActualKpa", 4_001L), "tipActualKpa", now))
        assertEquals(true, fast.isFresh(vs(0.0, "tipActualKpa", 60_000L), "tipActualKpa", now))         // engine off: slow sweep
        assertEquals(true, fast.isFresh(vs(2_500.0, "etcAngleDesired", 80_000L), "etcAngleDesired", now)) // 2 × 40 s
        assertEquals(false, fast.isFresh(vs(2_500.0, "etcAngleDesired", 451_000L), "etcAngleDesired", now))
    }
}
