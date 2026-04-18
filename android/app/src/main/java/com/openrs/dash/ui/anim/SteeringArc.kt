package com.openrs.dash.ui.anim

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.LocalThemeAccent
import kotlin.math.abs

/**
 * Semicircle arc indicator showing current steering angle.
 * Center = straight ahead, left/right sweep proportional to angle.
 * Subtle at rest (~0 deg), sweeps dynamically during cornering.
 *
 * @param angle  steering angle in degrees (negative = left, positive = right)
 * @param maxAngle  full-scale deflection in degrees (default 540 — lock-to-lock)
 */
@Composable
fun SteeringArc(
    angle: Float,
    modifier: Modifier = Modifier,
    maxAngle: Float = 540f,
    isConnected: Boolean = true
) {
    val accent = LocalThemeAccent.current

    Canvas(modifier) {
        val trackStroke = 2.dp.toPx()
        val indicatorStroke = 3.dp.toPx()
        val pad = indicatorStroke + 2.dp.toPx()

        // Arc occupies full width, semicircle opening downward
        val arcW = size.width - pad * 2
        val arcH = size.height - pad
        val arcSize = Size(arcW, arcH * 2)  // full ellipse size (we draw top half)
        val arcTopLeft = Offset(pad, pad)

        // Track arc: 180 degrees from left to right (startAngle=180, sweep=180)
        val trackAlpha = if (isConnected) 0.25f else 0.12f
        drawArc(
            color = Dim.copy(alpha = trackAlpha),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = trackStroke, cap = StrokeCap.Round)
        )

        if (!isConnected) return@Canvas

        // Indicator position: map angle to 0-180 arc sweep
        // -maxAngle → 0° (left), 0 → 90° (center/top), +maxAngle → 180° (right)
        val normalized = (angle / maxAngle).coerceIn(-1f, 1f)
        val indicatorDeg = 90f + normalized * 90f  // 0-180 on the arc

        // Draw the active sweep from center to current position
        val sweepAbs = abs(normalized) * 90f
        if (sweepAbs > 0.5f) {
            val sweepStart = if (normalized < 0) 270f - sweepAbs else 270f
            val sweepEnd = sweepAbs
            val sweepAlpha = (abs(normalized) * 0.6f).coerceIn(0.05f, 0.5f)
            drawArc(
                color = accent.copy(alpha = sweepAlpha),
                startAngle = sweepStart,
                sweepAngle = sweepEnd,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = indicatorStroke, cap = StrokeCap.Round)
            )
        }

        // Indicator dot at current angle
        val dotAngleRad = Math.toRadians((180.0 + indicatorDeg))
        val rx = arcW / 2f
        val ry = arcH
        val dotX = pad + rx + rx * kotlin.math.cos(dotAngleRad).toFloat()
        val dotY = pad + ry + ry * kotlin.math.sin(dotAngleRad).toFloat()
        val dotAlpha = (0.3f + abs(normalized) * 0.7f).coerceIn(0.3f, 1f)
        val dotR = 3.5.dp.toPx()

        // Glow behind dot
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = dotAlpha * 0.3f), Color.Transparent),
                center = Offset(dotX, dotY),
                radius = dotR * 3f
            ),
            radius = dotR * 3f,
            center = Offset(dotX, dotY)
        )
        drawCircle(
            color = accent.copy(alpha = dotAlpha),
            radius = dotR,
            center = Offset(dotX, dotY)
        )
    }
}
