package com.openrs.dash

import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.tyreImbalanceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** THERMAL's imbalance chip: the 4 psi trigger is unit-independent; the spread is shown in the selected unit. */
class TyreImbalanceTest {
    @Test fun `35 and 39 psi warn the same in either unit and show the spread in that unit`() {
        val psi = UserPrefs(tireUnit = "PSI")
        val bar = UserPrefs(tireUnit = "BAR")
        assertEquals("PRESSURE IMBALANCE 4.0 PSI", tyreImbalanceText(39.0 - 35.0, psi))
        assertEquals("PRESSURE IMBALANCE 0.28 BAR", tyreImbalanceText(39.0 - 35.0, bar))
        assertNull(tyreImbalanceText(38.9 - 35.0, psi))
        assertNull(tyreImbalanceText(38.9 - 35.0, bar))
        // The real spread getter feeds it
        val vs = VehicleState(tirePressLF = 35.0, tirePressRF = 39.0, tirePressLR = 36.0, tirePressRR = 37.0)
        assertEquals("PRESSURE IMBALANCE 0.28 BAR", tyreImbalanceText(vs.maxTirePressSpread, bar))
    }

}
