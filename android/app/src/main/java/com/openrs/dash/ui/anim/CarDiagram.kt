package com.openrs.dash.ui.anim

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.LocalThemeAccent
import com.openrs.dash.ui.Mid
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.ShareTechMono
import com.openrs.dash.ui.Surf3
import com.openrs.dash.ui.UnitConversions
import com.openrs.dash.ui.UserPrefs
import kotlin.math.roundToInt

/**
 * Animated top-down car diagram overlaying live TPMS, wheel speeds, and AWD
 * torque distribution at physical wheel positions on a Focus RS MK3 outline.
 */
@Composable
fun CarDiagram(
    vs: VehicleState,
    prefs: UserPrefs,
    modifier: Modifier = Modifier
) {
    val accent = LocalThemeAccent.current
    val outlineColor = accent.copy(alpha = 0.3f)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

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

    // Pre-format display strings
    val flPress = formatTirePressure(vs.tirePressLF, prefs)
    val frPress = formatTirePressure(vs.tirePressRF, prefs)
    val rlPress = formatTirePressure(vs.tirePressLR, prefs)
    val rrPress = formatTirePressure(vs.tirePressRR, prefs)

    val flSpeed = formatWheelSpeed(vs.wheelSpeedFL, prefs)
    val frSpeed = formatWheelSpeed(vs.wheelSpeedFR, prefs)
    val rlSpeed = formatWheelSpeed(vs.wheelSpeedRL, prefs)
    val rrSpeed = formatWheelSpeed(vs.wheelSpeedRR, prefs)

    val rearPct = vs.rearTorquePct.roundToInt()
    val frontPct = 100 - rearPct
    val splitLabel = "F $frontPct% / R $rearPct%"

    // Text styles
    val pressureStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono,
            fontSize = with(density) { 11.sp },
            color = Frost,
            textAlign = TextAlign.Center
        )
    }
    val speedStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono,
            fontSize = with(density) { 9.sp },
            color = Mid,
            textAlign = TextAlign.Center
        )
    }
    val splitStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono,
            fontSize = with(density) { 10.sp },
            color = Frost,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
    val splitLabelStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono,
            fontSize = with(density) { 8.sp },
            color = Dim,
            textAlign = TextAlign.Center
        )
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // ── Car body outline ──────────────────────────────────────────────
        drawCarOutline(w, h, outlineColor)

        // ── Wheel positions ───────────────────────────────────────────────
        val wheelRadius = 5.dp.toPx()

        // FL
        val flX = w * 0.20f
        val flY = h * 0.25f
        drawWheelIndicator(flX, flY, wheelRadius, flColor, flPress, flSpeed,
            textMeasurer, pressureStyle, speedStyle)

        // FR
        val frX = w * 0.80f
        val frY = h * 0.25f
        drawWheelIndicator(frX, frY, wheelRadius, frColor, frPress, frSpeed,
            textMeasurer, pressureStyle, speedStyle)

        // RL
        val rlX = w * 0.20f
        val rlY = h * 0.75f
        drawWheelIndicator(rlX, rlY, wheelRadius, rlColor, rlPress, rlSpeed,
            textMeasurer, pressureStyle, speedStyle)

        // RR
        val rrX = w * 0.80f
        val rrY = h * 0.75f
        drawWheelIndicator(rrX, rrY, wheelRadius, rrColor, rrPress, rrSpeed,
            textMeasurer, pressureStyle, speedStyle)

        // ── AWD torque split (center) ─────────────────────────────────────
        drawTorqueSplit(w, h, frontPct, rearPct, splitLabel, accent,
            textMeasurer, splitStyle, splitLabelStyle)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Drawing helpers
// ═════════════════════════════════════════════════════════════════════════════

private fun DrawScope.drawCarOutline(w: Float, h: Float, outlineColor: Color) {
    val stroke = Stroke(width = 1.5.dp.toPx())
    val bodyW = w * 0.38f
    val bodyH = h * 0.76f
    val bodyX = (w - bodyW) / 2f
    val bodyY = (h - bodyH) / 2f
    val cornerR = 12.dp.toPx()

    // Main body
    drawRoundRect(
        color = outlineColor,
        topLeft = Offset(bodyX, bodyY),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(cornerR),
        style = stroke
    )

    // Front bumper (narrower rect at top)
    val bumpW = bodyW * 0.75f
    val bumpH = h * 0.06f
    val bumpX = (w - bumpW) / 2f
    val bumpY = bodyY - bumpH * 0.4f
    drawRoundRect(
        color = outlineColor,
        topLeft = Offset(bumpX, bumpY),
        size = Size(bumpW, bumpH),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = stroke
    )

    // Rear bumper (narrower rect at bottom)
    val rBumpY = bodyY + bodyH - bumpH * 0.6f
    drawRoundRect(
        color = outlineColor,
        topLeft = Offset(bumpX, rBumpY),
        size = Size(bumpW, bumpH),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = stroke
    )

    // Windshield (trapezoid in upper portion of body)
    val wsTop = bodyY + bodyH * 0.18f
    val wsBot = bodyY + bodyH * 0.34f
    val wsTopInset = bodyW * 0.18f
    val wsBotInset = bodyW * 0.08f
    val windshieldPath = Path().apply {
        moveTo(bodyX + wsTopInset, wsTop)
        lineTo(bodyX + bodyW - wsTopInset, wsTop)
        lineTo(bodyX + bodyW - wsBotInset, wsBot)
        lineTo(bodyX + wsBotInset, wsBot)
        close()
    }
    drawPath(windshieldPath, outlineColor, style = stroke)

    // Rear window (smaller trapezoid in lower portion)
    val rwBot = bodyY + bodyH * 0.82f
    val rwTop = bodyY + bodyH * 0.70f
    val rwTopInset = bodyW * 0.08f
    val rwBotInset = bodyW * 0.18f
    val rearWindowPath = Path().apply {
        moveTo(bodyX + rwTopInset, rwTop)
        lineTo(bodyX + bodyW - rwTopInset, rwTop)
        lineTo(bodyX + bodyW - rwBotInset, rwBot)
        lineTo(bodyX + rwBotInset, rwBot)
        close()
    }
    drawPath(rearWindowPath, outlineColor, style = stroke)
}

private fun DrawScope.drawWheelIndicator(
    cx: Float, cy: Float, radius: Float,
    statusColor: Color,
    pressureText: String,
    speedText: String,
    textMeasurer: TextMeasurer,
    pressureStyle: TextStyle,
    speedStyle: TextStyle
) {
    // Status circle (filled)
    drawCircle(statusColor.copy(alpha = 0.25f), radius = radius * 1.8f, center = Offset(cx, cy))
    drawCircle(statusColor, radius = radius, center = Offset(cx, cy))

    // Pressure text (above circle)
    val pressResult = textMeasurer.measure(pressureText, pressureStyle)
    drawText(
        pressResult,
        topLeft = Offset(cx - pressResult.size.width / 2f, cy - radius - pressResult.size.height - 2.dp.toPx())
    )

    // Speed text (below circle)
    val speedResult = textMeasurer.measure(speedText, speedStyle)
    drawText(
        speedResult,
        topLeft = Offset(cx - speedResult.size.width / 2f, cy + radius + 2.dp.toPx())
    )
}

private fun DrawScope.drawTorqueSplit(
    w: Float, h: Float,
    frontPct: Int, rearPct: Int,
    splitLabel: String,
    accent: Color,
    textMeasurer: TextMeasurer,
    splitStyle: TextStyle,
    labelStyle: TextStyle
) {
    val barW = 6.dp.toPx()
    val barH = h * 0.28f
    val barX = w / 2f - barW / 2f
    val barY = h / 2f - barH / 2f

    // Background bar
    drawRoundRect(
        color = Surf3,
        topLeft = Offset(barX, barY),
        size = Size(barW, barH),
        cornerRadius = CornerRadius(3.dp.toPx())
    )

    // Front portion (accent, from top)
    val frontH = barH * (frontPct / 100f).coerceIn(0.02f, 0.98f)
    drawRoundRect(
        color = accent.copy(alpha = 0.8f),
        topLeft = Offset(barX, barY),
        size = Size(barW, frontH),
        cornerRadius = CornerRadius(3.dp.toPx())
    )

    // Rear portion (green, from bottom)
    val rearH = barH - frontH
    drawRoundRect(
        color = Ok.copy(alpha = 0.8f),
        topLeft = Offset(barX, barY + frontH),
        size = Size(barW, rearH),
        cornerRadius = CornerRadius(3.dp.toPx())
    )

    // "F/R" label above bar
    val frLabel = textMeasurer.measure("F/R", labelStyle)
    drawText(
        frLabel,
        topLeft = Offset(w / 2f - frLabel.size.width / 2f, barY - frLabel.size.height - 2.dp.toPx())
    )

    // Split text below bar
    val splitResult = textMeasurer.measure(splitLabel, splitStyle)
    drawText(
        splitResult,
        topLeft = Offset(w / 2f - splitResult.size.width / 2f, barY + barH + 3.dp.toPx())
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// Formatting helpers
// ═════════════════════════════════════════════════════════════════════════════

private fun tireStatusColor(psi: Double, lowThreshold: Double): Color = when {
    psi < 0          -> Dim       // no data yet
    psi < lowThreshold -> Orange  // low pressure
    psi > 40.0       -> Orange    // over-inflated
    else             -> Ok        // normal
}

private fun formatTirePressure(psi: Double, prefs: UserPrefs): String {
    if (psi < 0) return "—"
    return prefs.displayTire(psi)
}

private fun formatWheelSpeed(kph: Double, prefs: UserPrefs): String {
    val value = if (prefs.speedUnit == "MPH") kph * UnitConversions.KM_TO_MI else kph
    val label = if (prefs.speedUnit == "MPH") "mph" else "kph"
    return "${value.roundToInt()} $label"
}
