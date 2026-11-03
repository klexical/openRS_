package com.openrs.dash

import com.openrs.dash.ui.TyreThreshold
import com.openrs.dash.ui.UnitConversions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #219 — TPMS thresholds edited in the selected tyre unit; one canonical PSI set, never drifted by rounding. */
class TyreThresholdTest {

    @Test fun `stored PSI shows in BAR and an unchanged save keeps the exact PSI`() {
        val low = TyreThreshold.of(30f, "PSI").convert("PSI", "BAR")
        assertEquals("2.07", low.text)
        assertEquals(30f, low.resolvePsi("BAR")!!, 0f)                // not 2.07 / 0.0689476 = 30.02
    }

    @Test fun `switching PSI ↔ BAR repeatedly never moves the stored value`() {
        var t = TyreThreshold.of(32.5f, "PSI")
        repeat(5) { t = t.convert("PSI", "BAR").convert("BAR", "PSI") }
        assertEquals(32.5f, t.resolvePsi("PSI")!!, 0f)
        assertEquals("32.5", t.text)
    }

    @Test fun `a value typed in BAR is converted to PSI once`() {
        val t = TyreThreshold.of(30f, "BAR").edit("2.20")
        assertEquals((2.20 / UnitConversions.PSI_TO_BAR).toFloat(), t.resolvePsi("BAR")!!, 0.0001f)   // ≈ 31.91 psi
    }

    @Test fun `an edit made in BAR survives a switch to PSI as the same pressure`() {
        val t = TyreThreshold.of(30f, "BAR").edit("2.40").convert("BAR", "PSI")
        assertEquals("34.8", t.text)
        assertEquals((2.40 / UnitConversions.PSI_TO_BAR).toFloat(), t.resolvePsi("PSI")!!, 0.0001f)
    }

    @Test fun `decimal comma input parses, nonsense doesn't`() {
        assertEquals((2.1 / UnitConversions.PSI_TO_BAR).toFloat(), TyreThreshold.of(30f, "BAR").edit("2,1").resolvePsi("BAR")!!, 0.0001f)
        assertNull(TyreThreshold.of(30f, "PSI").edit("abc").resolvePsi("PSI"))
    }

    @Test fun `PSI editing is unchanged`() {
        assertEquals(28.5f, TyreThreshold.of(30f, "PSI").edit("28.5").resolvePsi("PSI")!!, 0f)
        assertEquals("30.0", TyreThreshold.of(30f, "PSI").text)
    }
}
