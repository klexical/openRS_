package com.openrs.dash

import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.ui.KnockScatterMath
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.knockBoostGrid
import com.openrs.dash.ui.knockBoostText
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

    @Test
    fun `knock boost follows the boost unit and matches the live boost readings`() {
        val psi = 14.5   // stored gauge psi
        val kpaAbs = psi / 0.14503773 + 101.325
        for (unit in listOf("PSI", "BAR", "KPA")) {
            val p = UserPrefs(boostUnit = unit)
            val (v, label) = p.displayBoost(kpaAbs)
            assertEquals("$v $label", knockBoostText(psi, p))
        }
        assertEquals("1.00 BAR", knockBoostText(psi, UserPrefs(boostUnit = "BAR")))
        assertEquals("14.5 PSI", knockBoostText(psi, null))
    }

    @Test
    fun `knock gridlines are round values of the unit and stay on the stored psi scale`() {
        assertEquals(listOf("0", "+10", "+20"), knockBoostGrid("PSI").map { it.second })
        assertEquals(listOf("0", "+0.5", "+1.0"), knockBoostGrid("BAR").map { it.second })
        assertEquals(listOf("0", "+50", "+100"), knockBoostGrid("KPA").map { it.second })
        // 1.0 bar ≈ 14.5 psi and 100 kPa ≈ 14.5 psi gauge — same gridline height as each other, below the +20 psi line
        knockBoostGrid("BAR").last().first.let { assertEquals(14.50f, it, 0.02f) }
        knockBoostGrid("KPA").last().first.let { assertEquals(14.50f, it, 0.02f) }
    }
}
