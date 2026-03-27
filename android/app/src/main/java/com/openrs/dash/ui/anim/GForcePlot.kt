package com.openrs.dash.ui.anim

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Dim
import kotlin.math.min

/**
 * 2D G-force visualization with concentric rings, crosshairs, comet trail, and live dot.
 * X axis = lateral G (positive = right), Y axis = longitudinal G (positive = accel, negative = brake).
 */
@Composable
fun GForcePlot(
    lateralG: Float,
    longitudinalG: Float,
    trail: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    maxG: Float = 1.5f,
    dotColor: Color = Accent
) {
    val density = LocalDensity.current
    val textPaint = remember(density) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(100, 61, 90, 114)
            textSize = with(density) { 9.dp.toPx() }
            isAntiAlias = true
        }
    }
    val axisPaint = remember(density) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(90, 61, 90, 114)
            textSize = with(density) { 10.dp.toPx() }
            isAntiAlias = true
        }
    }

    val ringSteps = remember { listOf(0.5f, 1.0f, 1.5f) }
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy) * 0.88f
        val scale = radius / maxG

        // Concentric rings
        for (g in ringSteps) {
            val r = g * scale
            drawCircle(
                color = Dim.copy(alpha = 0.22f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Crosshairs
        drawLine(Dim.copy(alpha = 0.12f), Offset(cx, cy - radius), Offset(cx, cy + radius), 1.dp.toPx())
        drawLine(Dim.copy(alpha = 0.12f), Offset(cx - radius, cy), Offset(cx + radius, cy), 1.dp.toPx())

        // Ring labels
        for (g in ringSteps) {
            val r = g * scale
            val labelX = cx + r * 0.71f + 4.dp.toPx()
            val labelY = cy - r * 0.71f - 2.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText("%.1f".format(g), labelX, labelY, textPaint)
        }

        // Axis labels
        axisPaint.textAlign = android.graphics.Paint.Align.CENTER
        drawContext.canvas.nativeCanvas.drawText("ACCEL", cx, cy - radius - 6.dp.toPx(), axisPaint)
        drawContext.canvas.nativeCanvas.drawText("BRAKE", cx, cy + radius + 14.dp.toPx(), axisPaint)
        axisPaint.textAlign = android.graphics.Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText("L", cx - radius - 12.dp.toPx(), cy + 4.dp.toPx(), axisPaint)
        axisPaint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText("R", cx + radius + 12.dp.toPx(), cy + 4.dp.toPx(), axisPaint)

        // Comet trail
        if (trail.size >= 2) {
            for (i in trail.indices) {
                val (tLat, tLon) = trail[i]
                val alpha = 0.05f + (i.toFloat() / trail.size) * 0.45f
                val tx = cx + tLat * scale
                val ty = cy - tLon * scale
                drawCircle(dotColor.copy(alpha = alpha), radius = 2.5.dp.toPx(), center = Offset(tx, ty))
                if (i > 0) {
                    val (pLat, pLon) = trail[i - 1]
                    val px = cx + pLat * scale
                    val py = cy - pLon * scale
                    drawLine(dotColor.copy(alpha = alpha * 0.6f), Offset(px, py), Offset(tx, ty),
                        strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }

        // Live dot with glow
        val dotX = cx + lateralG * scale
        val dotY = cy - longitudinalG * scale
        drawCircle(
            brush = Brush.radialGradient(
                listOf(dotColor.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(dotX, dotY),
                radius = 14.dp.toPx()
            ),
            radius = 14.dp.toPx(),
            center = Offset(dotX, dotY)
        )
        drawCircle(dotColor, radius = 5.dp.toPx(), center = Offset(dotX, dotY))
        drawCircle(Color.White.copy(alpha = 0.5f), radius = 2.dp.toPx(), center = Offset(dotX, dotY))
    }
}
