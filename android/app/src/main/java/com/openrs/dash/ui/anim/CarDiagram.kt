package com.openrs.dash.ui.anim

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openrs.dash.R
import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.LocalThemeAccent
import com.openrs.dash.ui.Mid
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.TyreView
import com.openrs.dash.ui.Warn
import com.openrs.dash.ui.UnitConversions
import com.openrs.dash.ui.UserPrefs
import kotlin.math.roundToInt

/**
 * Focus RS MK3 top-down artwork with each tyre tinted by its pressure state ([tyres] = FL, FR, RL, RR).
 *
 * Designed to sit between flanking tire cards in the unified
 * Chassis section ("Neon Connect" layout). Connector lines are
 * drawn by the parent composable via [WHEEL_ANCHORS].
 */
@Composable
internal fun CarDiagram(
    tyres: List<TyreView>,
    prefs: UserPrefs,
    modifier: Modifier = Modifier
) {
    val accent = LocalThemeAccent.current
    val low = prefs.tireLowPsi.toDouble()
    val warn = prefs.tireWarnPsi.toDouble()
    val high = prefs.tireHighPsi.toDouble()
    // Normal tyres keep the sheet's blue (the paint accent); warning / critical / no data take over. A held reading is
    // never tinted by its pressure: the wheel is muted and banded (last-known), like its card.
    val colors = tyres.mapIndexed { i, t ->
        animateColorAsState(tyreArtColor(t, low, warn, high, accent), tween(400), label = "tyre$i").value
    }
    TopDownCar(bodyColor = Mid, tyreColors = colors, modifier = modifier, lastKnown = tyres.map { it.stale })
}

/** Lead-line / wheel colour for one tyre: its pressure state while the reading is current, muted otherwise. */
internal fun tyreStateColor(t: TyreView, low: Double, warn: Double, high: Double): Color =
    if (t.current) tireStatusColor(t.psi, low, warn, high) else Dim

/** The wheel's tint in the artwork: [tyreStateColor], with in-range shown in the paint [accent] and a held reading faded. */
internal fun tyreArtColor(t: TyreView, low: Double, warn: Double, high: Double, accent: Color): Color {
    val c = tyreStateColor(t, low, warn, high)
    return when {
        t.stale -> Dim.copy(alpha = LAST_KNOWN_TYRE_ALPHA)
        c == Ok -> accent
        else    -> c
    }
}

/** Opacity of a last-known wheel in the artwork (a wheel with no reading at all stays solid [Dim]). */
internal const val LAST_KNOWN_TYRE_ALPHA = 0.7f

/**
 * The approved top-down Focus RS artwork (concept sheet) in two layers: line art tinted [bodyColor]
 * (theme ink — light on NIGHT, dark on DAY, no background box) and the four tyres, each tinted with its
 * own colour in [tyreColors] (FL, FR, RL, RR). Draw it at [TOPDOWN_ASPECT] so it never stretches.
 * A wheel flagged in [lastKnown] is drawn through horizontal bands (the same tyre shape, dashed), so a held reading is
 * told apart from a current one without relying on colour.
 */
@Composable
fun TopDownCar(
    bodyColor: Color, tyreColors: List<Color>, modifier: Modifier = Modifier,
    lastKnown: List<Boolean> = emptyList(),
) {
    val body = ImageBitmap.imageResource(R.drawable.rs_topdown_body)
    val tyres = ImageBitmap.imageResource(R.drawable.rs_topdown_tyres)
    Canvas(modifier) {
        val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
        drawImage(body, dstSize = dst, colorFilter = ColorFilter.tint(bodyColor), filterQuality = FilterQuality.High)
        // Quadrants: the art's front is at the top, so the viewer's left is the car's left
        tyreColors.forEachIndexed { i, c ->
            val left = if (i % 2 == 0) 0f else size.width / 2f
            val top = if (i < 2) 0f else size.height / 2f
            clipRect(left, top, left + size.width / 2f, top + size.height / 2f) {
                if (lastKnown.getOrElse(i) { false }) {
                    val band = 2.dp.toPx()
                    val bands = Path().apply {
                        var y = top
                        while (y < top + size.height / 2f) { addRect(Rect(left, y, left + size.width / 2f, y + band)); y += band * 2 }
                    }
                    clipPath(bands) {
                        drawImage(tyres, dstSize = dst, colorFilter = ColorFilter.tint(c), filterQuality = FilterQuality.High)
                    }
                } else {
                    drawImage(tyres, dstSize = dst, colorFilter = ColorFilter.tint(c), filterQuality = FilterQuality.High)
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Public helpers — wheel anchor positions for connector lines
// ═════════════════════════════════════════════════════════════════════════════

/** Width / height of the top-down artwork (330 × 574 px). */
const val TOPDOWN_ASPECT = 0.5749f

/**
 * Where each lead line meets its tyre: the outer edge of the tyre at its centre height, as fractions of
 * the artwork — measured from the tyre layer (rs_topdown_tyres.png centroids ± half the tyre width).
 */
data class WheelAnchor(val xFraction: Float, val yFraction: Float)

val WHEEL_ANCHORS = listOf(
    WheelAnchor(0.065f, 0.240f),  // FL — centre (0.132, 0.240)
    WheelAnchor(0.931f, 0.241f),  // FR — centre (0.866, 0.241)
    WheelAnchor(0.059f, 0.821f),  // RL — centre (0.130, 0.821)
    WheelAnchor(0.936f, 0.822f),  // RR — centre (0.866, 0.822)
)

// ═════════════════════════════════════════════════════════════════════════════
// Formatting helpers
// ═════════════════════════════════════════════════════════════════════════════

internal fun tireStatusColor(
    psi: Double,
    lowThreshold: Double,
    warnThreshold: Double,
    highThreshold: Double
): Color = when {
    psi < 0              -> Dim       // no data yet
    psi < lowThreshold   -> Orange    // critically under-inflated
    psi < warnThreshold  -> Warn      // getting low
    psi > highThreshold  -> Orange    // over-inflated
    else                 -> Ok        // optimal range
}

internal fun formatTirePressure(psi: Double, prefs: UserPrefs): String {
    if (psi < 0) return "—"
    return prefs.displayTire(psi)
}

internal fun formatWheelSpeed(kph: Double, prefs: UserPrefs): String {
    val value = if (prefs.speedUnit == "MPH") kph * UnitConversions.KM_TO_MI else kph
    val label = if (prefs.speedUnit == "MPH") "mph" else "kph"
    return "${value.roundToInt()} $label"
}
