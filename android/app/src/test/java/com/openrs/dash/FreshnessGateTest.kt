package com.openrs.dash

import com.openrs.dash.data.FreshnessGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FreshnessGate — rc.5 (#184 / #187): polled DIDs are recorded once per decode and
 * never from a decode stamped before the drive started.
 */
class FreshnessGateTest {

    @Test
    fun `stamp from before drive start is rejected`() {
        val gate = FreshnessGate(notBeforeMs = 10_000L)
        assertFalse(gate.takeIfFresh("ignCorrCyl1", mapOf("ignCorrCyl1" to 9_999L)))
        assertFalse(gate.takeIfFresh("ignCorrCyl1", mapOf("ignCorrCyl1" to 10_000L)))
    }

    @Test
    fun `same stamp is consumed only once`() {
        val gate = FreshnessGate(notBeforeMs = 0L)
        val stamps = mapOf("tipActualKpa" to 1_000L)
        assertTrue(gate.takeIfFresh("tipActualKpa", stamps))
        assertFalse(gate.takeIfFresh("tipActualKpa", stamps))
    }

    @Test
    fun `newer stamp is accepted again`() {
        val gate = FreshnessGate(notBeforeMs = 0L)
        assertTrue(gate.takeIfFresh("wgdcDesired", mapOf("wgdcDesired" to 1_000L)))
        assertTrue(gate.takeIfFresh("wgdcDesired", mapOf("wgdcDesired" to 2_200L)))
    }

    @Test
    fun `missing tag is rejected`() {
        val gate = FreshnessGate(notBeforeMs = 0L)
        assertFalse(gate.takeIfFresh("tipDesiredKpa", emptyMap()))
    }

    @Test
    fun `tags are tracked independently`() {
        val gate = FreshnessGate(notBeforeMs = 0L)
        val stamps = mapOf("tipActualKpa" to 1_000L, "tipDesiredKpa" to 1_000L)
        assertTrue(gate.takeIfFresh("tipActualKpa", stamps))
        assertTrue(gate.takeIfFresh("tipDesiredKpa", stamps))
    }
}
