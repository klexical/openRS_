package com.openrs.dash.ui.anim

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mutable sparkline data holder backed by a RingBuffer.
 * Call push() on each sample; call snapshot() to get the current data for rendering.
 */
class SparklineData(capacity: Int = 60) {
    private val buffer = RingBuffer<Float>(capacity)
    fun push(value: Float) = buffer.push(value)
    fun snapshot(): List<Float> = buffer.toList()
    val size: Int get() = buffer.size
}

/**
 * Tiny inline trend chart with neon glow effect.
 * Draws a glow line (2× width, low alpha) + crisp line + gradient fill + live endpoint dot.
 */
@Composable
fun Sparkline(
    data: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.5.dp,
    fillAlpha: Float = 0.20f
) {
    if (data.size < 2) return
    val linePath = remember { Path() }
    val fillPath = remember { Path() }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val minVal = data.min()
        val maxVal = data.max()
        val range = (maxVal - minVal).coerceAtLeast(0.01f)
        val step = w / (data.size - 1)

        linePath.reset()
        fillPath.reset()

        var lastX = 0f
        var lastY = 0f

        data.forEachIndexed { i, v ->
            val x = i * step
            val y = h - ((v - minVal) / range) * h * 0.85f - h * 0.05f
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }

        // Gradient fill below the line
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()
        drawPath(
            fillPath,
            brush = Brush.verticalGradient(
                listOf(lineColor.copy(alpha = fillAlpha), Color.Transparent)
            )
        )

        // Glow line — wider, low alpha
        drawPath(
            linePath,
            lineColor.copy(alpha = 0.15f),
            style = Stroke(width = strokeWidth.toPx() * 2.5f, cap = StrokeCap.Round)
        )

        // Crisp line stroke
        drawPath(
            linePath,
            lineColor.copy(alpha = 0.8f),
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        )

        // Live endpoint dot with bloom
        val dotRadius = 2.5.dp.toPx()
        val bloomRadius = 7.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                listOf(lineColor.copy(alpha = 0.3f), Color.Transparent),
                center = Offset(lastX, lastY),
                radius = bloomRadius
            ),
            radius = bloomRadius,
            center = Offset(lastX, lastY)
        )
        drawCircle(
            color = lineColor,
            radius = dotRadius,
            center = Offset(lastX, lastY)
        )
    }
}
