package com.openrs.dash

import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.ui.KnockScatterMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** KnockScatterMath — rc.6 (#174): tap hit-test uses the same projection as the draw pass. */
class KnockScatterMathTest {

    private val plot = KnockScatterMath.plot(700f, 400f)
    private fun ev(rpm: Int, psi: Double, kr: Double = 1.0) =
        KnockEventEntity(driveId = 0, timestamp = 0, krDeg = kr, rpm = rpm, boostPsi = psi)

    @Test
    fun `tap on a drawn dot selects it, empty space selects nothing`() {
        val events = listOf(ev(2000, 0.0), ev(5000, 18.0))
        val (x, y) = KnockScatterMath.project(events[1], plot, 0f, 7000f, -15f, 22f)
        assertEquals(1, KnockScatterMath.nearest(events, x + 3f, y - 3f, plot, 24f, 0f, 7000f, -15f, 22f))
        assertNull(KnockScatterMath.nearest(events, plot.left + 5f, plot.top + 5f, plot, 24f, 0f, 7000f, -15f, 22f))
    }

    @Test
    fun `overlapping dots resolve to the worst retard`() {
        val events = listOf(ev(4000, 10.0, kr = 1.0), ev(4000, 10.0, kr = 4.5))
        val (x, y) = KnockScatterMath.project(events[0], plot, 0f, 7000f, -15f, 22f)
        assertEquals(1, KnockScatterMath.nearest(events, x, y, plot, 24f, 0f, 7000f, -15f, 22f))
    }
}
