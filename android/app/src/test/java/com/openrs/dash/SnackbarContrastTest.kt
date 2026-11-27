package com.openrs.dash

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.openrs.dash.ui.RsPaints
import com.openrs.dash.ui.ThemeMode
import com.openrs.dash.ui.legibleAccent
import com.openrs.dash.ui.setThemeMode
import com.openrs.dash.ui.snackbarColors
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * #566: the message bar's action text (the dock's SWITCH, an ESC result's action) reaches WCAG AA, 4.5:1, against the
 * bar for every RS paint in NIGHT, DAY and ULTRA. The paint arrives as [legibleAccent] provides it (LocalThemeAccent).
 * Contrast is the WCAG 2.x relative-luminance formula, written out here from the spec rather than taken from Compose.
 */
class SnackbarContrastTest {
    @After fun reset() = setThemeMode(ThemeMode.NIGHT)

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Int): Double =
        0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a.toArgb()), luminance(b.toArgb())).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(mode: ThemeMode) {
        setThemeMode(mode)
        val failures = RsPaints.mapNotNull { paint ->
            val colors = snackbarColors(legibleAccent(paint.accent, isDay = mode == ThemeMode.DAY))
            val ratio = contrast(colors.action, colors.container)
            if (ratio < 4.5) "${paint.name} %.2f:1".format(ratio) else null
        }
        assertTrue("$mode action text below 4.5:1 on the bar: $failures", failures.isEmpty())
    }

    @Test fun `NIGHT - action text is at least 4_5 to 1 for every paint`() = check(ThemeMode.NIGHT)
    @Test fun `DAY - action text is at least 4_5 to 1 for every paint`() = check(ThemeMode.DAY)
    @Test fun `ULTRA - action text is at least 4_5 to 1 for every paint`() = check(ThemeMode.ULTRA)

    /** The lift is only as much as AA needs: a paint that already passes keeps its own colour. */
    @Test fun `NIGHT - a paint that already passes is drawn unchanged`() {
        setThemeMode(ThemeMode.NIGHT)
        val blue = Color(0xFF0091EA)
        assertTrue(snackbarColors(blue).action == blue)
    }
}
