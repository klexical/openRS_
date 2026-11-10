package com.openrs.dash

import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.Level
import com.openrs.dash.ui.Mid
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.TempAvail
import com.openrs.dash.ui.ThemeMode
import com.openrs.dash.ui.alertChipInk
import com.openrs.dash.ui.pillBgAlpha
import com.openrs.dash.ui.setThemeMode
import com.openrs.dash.ui.anim.lastKnownWheels
import com.openrs.dash.ui.anim.LAST_KNOWN_TYRE_ALPHA
import com.openrs.dash.ui.anim.tyreArtColor
import com.openrs.dash.ui.anim.tyreStateColor
import com.openrs.dash.ui.tyreAgeText
import com.openrs.dash.ui.tyrePanel
import com.openrs.dash.ui.tyreStatusText
import com.openrs.dash.ui.tyreView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-wheel view behind THERMAL's TYRES panel (`ui/TyreFreshness.kt`): a pressure is judged only while its own
 * reading is current; a held one keeps its value, its age and, outside Low / High, the fact that it was. Limits here
 * are the defaults (low 30, warn 32, high 45 psi); the readings are synthetic.
 */
class TyreFreshnessTest {
    private val now = 1_000_000_000L
    private fun view(psi: Double, ageMs: Long? = 0L, connected: Boolean = true, name: String = "Front Left") =
        tyreView(name, psi, ageMs?.let { now - it }, connected, 30.0, 32.0, 45.0, now)

    @Test fun `a current reading carries its pressure level`() {
        assertEquals(Level.CRIT, view(25.0).level)
        assertEquals(Level.NORMAL, view(38.0).level)
        assertTrue(view(25.0).current); assertFalse(view(25.0).stale); assertFalse(view(25.0).wasLow)
    }

    @Test fun `59 s is current, 61 s is last-known`() {
        assertTrue(view(25.0, 59_000L).current)
        val held = view(25.0, 61_000L)
        assertEquals(TempAvail.STALE, held.avail); assertEquals(Level.STALE, held.level)
        assertTrue(held.wasLow); assertFalse(held.wasHigh); assertEquals(61_000L, held.ageMs)
    }

    @Test fun `the limit itself - 60 000 ms is current, one millisecond more is last-known`() {
        assertTrue(view(25.0, 60_000L).current); assertFalse(view(25.0, 60_000L).stale)
        assertFalse(view(25.0, 60_001L).current); assertTrue(view(25.0, 60_001L).stale)
    }

    @Test fun `link down - a fresh stamp is last-known at once`() {
        val held = view(47.0, 5_000L, connected = false)
        assertEquals(Level.STALE, held.level); assertTrue(held.wasHigh); assertFalse(held.wasLow)
    }

    @Test fun `no reading - never stale, never low`() {
        val none = view(-1.0, null)
        assertFalse(none.current); assertFalse(none.stale); assertFalse(none.underLow); assertNull(none.ageMs)
        assertEquals(Level.NONE, view(-1.0, null, connected = false).level)
    }

    @Test fun `a zero pressure with a current stamp is a low reading, not a missing one`() {
        val flat = view(0.0)
        assertTrue(flat.current); assertTrue(flat.underLow)
    }

    @Test fun `age text steps - seconds, minutes, hours, floored`() {
        assertEquals(listOf("0s", "59s", "1m", "59m", "1h", "26h"),
            listOf(0L, 59_999L, 60_000L, 3_599_000L, 3_600_000L, 26 * 3_600_000L + 5L).map(::tyreAgeText))
    }

    @Test fun `status line - delta while current, the held fact with its age otherwise`() {
        assertEquals("+0.4", tyreStatusText(view(38.0), "+0.4"))
        assertEquals("STALE 2m", tyreStatusText(view(38.0, 125_000L), "+0.4"))
        assertEquals("WAS LOW 3m", tyreStatusText(view(25.0, 185_000L), "+0.4"))
        assertEquals("WAS HIGH 12h", tyreStatusText(view(47.0, 12 * 3_600_000L), ""))
        assertEquals("the caution band has no word, live or held", "STALE 2m", tyreStatusText(view(31.0, 125_000L), ""))
    }

    @Test fun `panel - only current readings alarm, tint the edge or count for the spread`() {
        val p = tyrePanel(listOf(view(25.0, 185_000L), view(38.0, name = "Front Right"),
            view(38.5, name = "Rear Left"), view(37.5, name = "Rear Right")))
        assertEquals(Level.NORMAL, p.level)
        assertEquals(emptyList<String>(), p.liveLow)
        assertEquals("FRONT LEFT WAS LOW · 3m AGO", p.staleLowText)
        assertEquals("spread of the three current readings, not 13.5", 1.0, p.currentSpreadPsi, 1e-9)
    }

    @Test fun `panel - a current low wheel beside a held low one - both said, each as what it is`() {
        val p = tyrePanel(listOf(view(25.0, 185_000L), view(24.0, name = "Front Right"),
            view(26.0, 600_000L, name = "Rear Left"), view(38.0, name = "Rear Right")))
        assertEquals(Level.CRIT, p.level)
        assertEquals(listOf("Front Right"), p.liveLow)
        assertEquals("the chip claims the older of the two ages", "FRONT LEFT, REAR LEFT WAS LOW · 10m AGO", p.staleLowText)
    }

    @Test fun `panel - one current reading has no spread, none held low has no chip`() {
        val p = tyrePanel(listOf(view(38.0), view(20.0, null, name = "Front Right").copy(avail = TempAvail.STALE)))
        assertEquals(0.0, p.currentSpreadPsi, 0.0)
        assertEquals("a held low value without a stamp claims no age", "FRONT RIGHT WAS LOW", p.staleLowText)
        assertNull(tyrePanel(listOf(view(38.0), view(38.0, 125_000L))).staleLowText)
    }

    @Test fun `colours - a held reading is never tinted by its pressure`() {
        val accent = Color(0xFF0091EA)
        assertEquals(Orange, tyreStateColor(view(25.0), 30.0, 32.0, 45.0))
        assertEquals(Dim, tyreStateColor(view(25.0, 185_000L), 30.0, 32.0, 45.0))
        assertEquals(Dim, tyreStateColor(view(-1.0, null), 30.0, 32.0, 45.0))
        assertEquals(accent, tyreArtColor(view(38.0), 30.0, 32.0, 45.0, accent))
        assertEquals(Orange, tyreArtColor(view(25.0), 30.0, 32.0, 45.0, accent))
        assertEquals(Dim.copy(alpha = LAST_KNOWN_TYRE_ALPHA), tyreArtColor(view(25.0, 185_000L), 30.0, 32.0, 45.0, accent))
        assertEquals(Dim.copy(alpha = LAST_KNOWN_TYRE_ALPHA), tyreArtColor(view(38.0, 185_000L), 30.0, 32.0, 45.0, accent))
        assertTrue(Ok != accent)
    }

    @Test fun `artwork - only a held reading is banded, never a current or a missing one`() {
        assertEquals(listOf(true, false, false, true), lastKnownWheels(listOf(
            view(25.0, 185_000L), view(38.0), view(-1.0, null), view(38.0, 5_000L, connected = false))))
    }

    @Test fun `DAY - the muted WAS LOW chip text reaches 4,5 to 1 on its own fill`() {
        setThemeMode(ThemeMode.DAY)
        try {
            val fill = Dim.copy(alpha = pillBgAlpha(0.12f)).compositeOver(Surf)
            assertTrue("Dim on its own fill is the reason for the rule", contrast(Dim, fill) < 4.5)
            assertEquals(Mid, alertChipInk(Level.STALE, day = true))
            assertTrue(contrast(alertChipInk(Level.STALE, day = true), fill) >= 4.5)
            assertEquals("live chips keep their level colour", Orange, alertChipInk(Level.CRIT, day = true))
        } finally { setThemeMode(ThemeMode.NIGHT) }
        assertEquals("NIGHT is unchanged", Dim, alertChipInk(Level.STALE, day = false))
    }

    /** WCAG 2 contrast ratio of two opaque colours. */
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
