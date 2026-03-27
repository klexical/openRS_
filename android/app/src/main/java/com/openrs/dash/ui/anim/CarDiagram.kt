package com.openrs.dash.ui.anim

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.openrs.dash.R
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.LocalThemeAccent
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.UnitConversions
import com.openrs.dash.ui.UserPrefs
import kotlin.math.roundToInt

/**
 * Focus RS MK3 wireframe with tire highlight markers at wheel positions.
 *
 * Designed to sit between flanking tire cards in the unified
 * Chassis section ("Neon Connect" layout). Connector lines are
 * drawn by the parent composable via [WHEEL_ANCHORS].
 */
@Composable
fun CarDiagram(
    vs: VehicleState,
    prefs: UserPrefs,
    modifier: Modifier = Modifier
) {
    val accent = LocalThemeAccent.current

    // Tire status colors — animated transitions
    val lowThreshold = prefs.tireLowPsi.toDouble()
    val flColor by animateColorAsState(
        tireStatusColor(vs.tirePressLF, lowThreshold), tween(400), label = "flCol"
    )
    val frColor by animateColorAsState(
        tireStatusColor(vs.tirePressRF, lowThreshold), tween(400), label = "frCol"
    )
    val rlColor by animateColorAsState(
        tireStatusColor(vs.tirePressLR, lowThreshold), tween(400), label = "rlCol"
    )
    val rrColor by animateColorAsState(
        tireStatusColor(vs.tirePressRR, lowThreshold), tween(400), label = "rrCol"
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        // RS wireframe image — tinted to theme accent
        Image(
            painter = painterResource(R.drawable.focus_rs_wireframe),
            contentDescription = "Focus RS",
            colorFilter = ColorFilter.tint(accent, BlendMode.SrcIn),
            modifier = Modifier.fillMaxSize()
        )

        // Data overlay drawn on top of the wireframe
        Box(
            Modifier.fillMaxSize().drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height

                // Tire highlight rectangles at wheel positions
                drawTireHighlight(w * 0.14f, h * 0.26f, flColor)
                drawTireHighlight(w * 0.86f, h * 0.26f, frColor)
                drawTireHighlight(w * 0.14f, h * 0.74f, rlColor)
                drawTireHighlight(w * 0.86f, h * 0.74f, rrColor)
            }
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Drawing helpers
// ═════════════════════════════════════════════════════════════════════════════

/** Tire highlight rectangle with layered glow — matches wheel arch positions. */
private fun DrawScope.drawTireHighlight(cx: Float, cy: Float, color: Color) {
    val tireW = 10.dp.toPx()   // narrow cross-section (top-down view)
    val tireH = 20.dp.toPx()   // elongated along car's length
    val cr = CornerRadius(3.dp.toPx())

    // Outer bloom
    val bloom = 5.dp.toPx()
    drawRoundRect(
        color = color.copy(alpha = 0.12f),
        topLeft = Offset(cx - tireW / 2 - bloom, cy - tireH / 2 - bloom),
        size = Size(tireW + bloom * 2, tireH + bloom * 2),
        cornerRadius = CornerRadius(cr.x + bloom)
    )

    // Mid glow
    val mid = 2.dp.toPx()
    drawRoundRect(
        color = color.copy(alpha = 0.3f),
        topLeft = Offset(cx - tireW / 2 - mid, cy - tireH / 2 - mid),
        size = Size(tireW + mid * 2, tireH + mid * 2),
        cornerRadius = CornerRadius(cr.x + mid)
    )

    // Tire body
    drawRoundRect(
        color = color.copy(alpha = 0.65f),
        topLeft = Offset(cx - tireW / 2, cy - tireH / 2),
        size = Size(tireW, tireH),
        cornerRadius = cr
    )

    // Inner bright core
    drawRoundRect(
        color = color.copy(alpha = 0.95f),
        topLeft = Offset(cx - tireW * 0.3f, cy - tireH * 0.3f),
        size = Size(tireW * 0.6f, tireH * 0.6f),
        cornerRadius = CornerRadius(2.dp.toPx())
    )

    // Edge definition stroke
    drawRoundRect(
        color = color.copy(alpha = 0.5f),
        topLeft = Offset(cx - tireW / 2, cy - tireH / 2),
        size = Size(tireW, tireH),
        cornerRadius = cr,
        style = Stroke(width = 1f)
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// Public helpers — wheel anchor positions for connector lines
// ═════════════════════════════════════════════════════════════════════════════

/** Fractional wheel positions within the wireframe (matches drawable geometry). */
data class WheelAnchor(val xFraction: Float, val yFraction: Float)

val WHEEL_ANCHORS = listOf(
    WheelAnchor(0.14f, 0.26f),  // FL
    WheelAnchor(0.86f, 0.26f),  // FR
    WheelAnchor(0.14f, 0.74f),  // RL
    WheelAnchor(0.86f, 0.74f),  // RR
)

// ═════════════════════════════════════════════════════════════════════════════
// Formatting helpers
// ═════════════════════════════════════════════════════════════════════════════

internal fun tireStatusColor(psi: Double, lowThreshold: Double): Color = when {
    psi < 0            -> Dim       // no data yet
    psi < lowThreshold -> Orange    // low pressure
    psi > 40.0         -> Orange    // over-inflated
    else               -> Ok        // normal
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
