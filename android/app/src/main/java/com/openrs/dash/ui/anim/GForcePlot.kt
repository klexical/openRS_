package com.openrs.dash.ui.anim

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.JetBrainsMonoFamily
import com.openrs.dash.ui.LocalThemeAccent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Rounds up to the nearest 0.25 increment, minimum 0.5. */
private fun ceilToQuarter(v: Float): Float =
    max(0.5f, ceil(v * 4f) / 4f)

/**
 * 2D G-force scatter plot with rectangular grid, corner brackets, auto-scaling axes,
 * peak labels, and age-graded dot trail.
 *
 * X axis = lateral G (positive = right), Y axis = longitudinal G (positive = accel).
 */
@Composable
fun GForcePlot(
    lateralG: Float,
    longitudinalG: Float,
    trail: List<Pair<Float, Float>>,
    peakLatG: Float,
    peakLonG: Float,
    modifier: Modifier = Modifier,
    dotColor: Color = LocalThemeAccent.current
) {
    val accent = LocalThemeAccent.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val labelStyle = remember(density) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = Dim.copy(alpha = 0.6f),
            fontWeight = FontWeight.Normal
        )
    }
    val peakStyle = remember(density) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
    val currentStyle = remember(density) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = Dim.copy(alpha = 0.5f),
            fontWeight = FontWeight.Normal
        )
    }
    val axisTitleStyle = remember(density) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = Dim.copy(alpha = 0.45f),
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
    val scaleStyle = remember(density) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 8.sp,
            color = Dim.copy(alpha = 0.4f),
            fontWeight = FontWeight.Normal
        )
    }

    Canvas(modifier) {
        // ── Auto-scale ─────────────────────────────────────────────
        val maxFromData = max(
            max(abs(peakLatG), abs(peakLonG)),
            max(abs(lateralG), abs(longitudinalG))
        )
        var trailMax = 0f
        for ((tLat, tLon) in trail) {
            trailMax = max(trailMax, max(abs(tLat), abs(tLon)))
        }
        val maxG = ceilToQuarter(max(maxFromData, trailMax))

        // ── Layout ─────────────────────────────────────────────────
        // Row 1 (top):    "Current"  ...  "X.XX Lat"
        // Row 2:          scale + plot area + scale
        // Row 3 (bottom): scale numbers
        // Row 4:          "Current"  ...  "X.XX Accel"
        // Row 5:          "ACCEL / BRAKE G"
        //
        // Left column:    Y scale numbers
        // Right column:   (nothing, scale at bottom-right)

        val topLabelH = 18.dp.toPx()    // "Current" / peak row
        val topGap = 4.dp.toPx()
        val botScaleH = 14.dp.toPx()    // scale numbers below plot
        val botLabelH = 16.dp.toPx()    // "Current" / peak row
        val botAxisH = 16.dp.toPx()     // "ACCEL / BRAKE G"
        val leftScaleW = 36.dp.toPx()   // Y scale numbers

        val plotLeft = leftScaleW
        val plotTop = topLabelH + topGap
        val plotRight = size.width - 4.dp.toPx()
        val plotBottom = size.height - botScaleH - botLabelH - botAxisH
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop
        val cx = plotLeft + plotW / 2f
        val cy = plotTop + plotH / 2f
        val scaleX = (plotW / 2f) / maxG
        val scaleY = (plotH / 2f) / maxG

        // ── Grid lines (dense, ~8-10 divisions per axis) ──────────
        val gridColor = Dim.copy(alpha = 0.30f)
        val gridStroke = 0.5.dp.toPx()
        // Target ~8-10 lines per half-axis: pick step so totalLines ≈ 8
        val step = when {
            maxG <= 0.5f  -> 0.1f
            maxG <= 1.0f  -> 0.125f
            maxG <= 1.5f  -> 0.25f
            else          -> 0.25f
        }
        var g = -maxG + step
        while (g < maxG - 0.001f) {
            if (abs(g) > 0.001f) {  // skip center (drawn as crosshair)
                val gx = cx + g * scaleX
                if (gx in plotLeft..plotRight) {
                    drawLine(gridColor, Offset(gx, plotTop), Offset(gx, plotBottom), gridStroke)
                }
                val gy = cy - g * scaleY
                if (gy in plotTop..plotBottom) {
                    drawLine(gridColor, Offset(plotLeft, gy), Offset(plotRight, gy), gridStroke)
                }
            }
            g += step
        }

        // ── Plot border (outer edge lines) ────────────────────────
        val borderColor = Dim.copy(alpha = 0.30f)
        val borderStroke = 1.dp.toPx()
        drawLine(borderColor, Offset(plotLeft, plotTop), Offset(plotRight, plotTop), borderStroke)       // top
        drawLine(borderColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), borderStroke)  // bottom
        drawLine(borderColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), borderStroke)      // left
        drawLine(borderColor, Offset(plotRight, plotTop), Offset(plotRight, plotBottom), borderStroke)    // right

        // ── Center crosshair ──────────────────────────────────────
        val crossColor = Dim.copy(alpha = 0.50f)
        val crossStroke = 2.5.dp.toPx()
        drawLine(crossColor, Offset(cx, plotTop), Offset(cx, plotBottom), crossStroke)
        drawLine(crossColor, Offset(plotLeft, cy), Offset(plotRight, cy), crossStroke)

        // ── Corner brackets ───────────────────────────────────────
        val bracketColor = accent.copy(alpha = 0.4f)
        val bracketStroke = 1.5.dp.toPx()
        val arm = 12.dp.toPx()
        // Top-left
        drawLine(bracketColor, Offset(plotLeft, plotTop), Offset(plotLeft + arm, plotTop), bracketStroke, cap = StrokeCap.Round)
        drawLine(bracketColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotTop + arm), bracketStroke, cap = StrokeCap.Round)
        // Top-right
        drawLine(bracketColor, Offset(plotRight, plotTop), Offset(plotRight - arm, plotTop), bracketStroke, cap = StrokeCap.Round)
        drawLine(bracketColor, Offset(plotRight, plotTop), Offset(plotRight, plotTop + arm), bracketStroke, cap = StrokeCap.Round)
        // Bottom-left
        drawLine(bracketColor, Offset(plotLeft, plotBottom), Offset(plotLeft + arm, plotBottom), bracketStroke, cap = StrokeCap.Round)
        drawLine(bracketColor, Offset(plotLeft, plotBottom), Offset(plotLeft, plotBottom - arm), bracketStroke, cap = StrokeCap.Round)
        // Bottom-right
        drawLine(bracketColor, Offset(plotRight, plotBottom), Offset(plotRight - arm, plotBottom), bracketStroke, cap = StrokeCap.Round)
        drawLine(bracketColor, Offset(plotRight, plotBottom), Offset(plotRight, plotBottom - arm), bracketStroke, cap = StrokeCap.Round)

        // ── Y-axis scale numbers (left of plot) ───────────────────
        val posLabel = "%.2f".format(maxG)
        val negLabel = "-%.2f".format(maxG)
        val topScaleM = textMeasurer.measure(posLabel, scaleStyle)
        drawText(topScaleM, topLeft = Offset(
            plotLeft - topScaleM.size.width - 4.dp.toPx(),
            plotTop - topScaleM.size.height / 2f
        ))
        val botScaleM = textMeasurer.measure(negLabel, scaleStyle)
        drawText(botScaleM, topLeft = Offset(
            plotLeft - botScaleM.size.width - 4.dp.toPx(),
            plotBottom - botScaleM.size.height / 2f
        ))

        // ── X-axis scale numbers (below plot) ─────────────────────
        val leftXM = textMeasurer.measure(negLabel, scaleStyle)
        drawText(leftXM, topLeft = Offset(
            plotLeft,
            plotBottom + 3.dp.toPx()
        ))
        val rightXM = textMeasurer.measure(posLabel, scaleStyle)
        drawText(rightXM, topLeft = Offset(
            plotRight - rightXM.size.width,
            plotBottom + 3.dp.toPx()
        ))

        // ── "LAT G" label (left, vertically centered) ─────────────
        val latGM = textMeasurer.measure("LAT G", axisTitleStyle)
        drawText(latGM, topLeft = Offset(
            0f,
            cy - latGM.size.height / 2f
        ))

        // ── "ACCEL / BRAKE G" label (bottom center) ───────────────
        val accelM = textMeasurer.measure("ACCEL / BRAKE G", axisTitleStyle)
        drawText(accelM, topLeft = Offset(
            cx - accelM.size.width / 2f,
            size.height - botAxisH + 2.dp.toPx()
        ))

        // ── Top row: "Current" + peak Lat ─────────────────────────
        val curTopM = textMeasurer.measure("Current", currentStyle)
        drawText(curTopM, topLeft = Offset(plotLeft, 0f))
        if (peakLatG > 0f) {
            val peakLatText = "%.2f Lat".format(peakLatG)
            val peakLatM = textMeasurer.measure(peakLatText, peakStyle.copy(color = accent.copy(alpha = 0.85f)))
            drawText(peakLatM, topLeft = Offset(plotRight - peakLatM.size.width, 0f))
        }

        // ── Bottom row: "Current" + peak Accel ────────────────────
        val curBotY = plotBottom + botScaleH + 2.dp.toPx()
        val curBotM = textMeasurer.measure("Current", currentStyle)
        drawText(curBotM, topLeft = Offset(plotLeft, curBotY))
        if (peakLonG > 0f) {
            val peakLonText = "%.2f Accel".format(peakLonG)
            val peakLonM = textMeasurer.measure(peakLonText, peakStyle.copy(color = accent.copy(alpha = 0.85f)))
            drawText(peakLonM, topLeft = Offset(plotRight - peakLonM.size.width, curBotY))
        }

        // ── Trail dots ────────────────────────────────────────────
        val trailSize = trail.size
        if (trailSize > 0) {
            val minDotR = 2.dp.toPx()
            val maxDotR = 3.5.dp.toPx()
            val haloThreshold = trailSize * 0.7f

            for (i in trail.indices) {
                val (tLat, tLon) = trail[i]
                val ageFrac = i.toFloat() / trailSize

                val dotAlpha = 0.15f + ageFrac * 0.65f
                val dotR = minDotR + ageFrac * (maxDotR - minDotR)

                val blendedColor = androidx.compose.ui.graphics.lerp(
                    dotColor.copy(alpha = dotAlpha),
                    Frost.copy(alpha = dotAlpha),
                    ageFrac
                )

                val tx = cx + tLat * scaleX
                val ty = cy - tLon * scaleY

                if (tx < plotLeft || tx > plotRight || ty < plotTop || ty > plotBottom) continue

                if (i >= haloThreshold) {
                    val haloR = dotR * 2.5f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(blendedColor.copy(alpha = dotAlpha * 0.3f), Color.Transparent),
                            center = Offset(tx, ty),
                            radius = haloR
                        ),
                        radius = haloR,
                        center = Offset(tx, ty)
                    )
                }

                drawCircle(blendedColor, radius = dotR, center = Offset(tx, ty))
            }
        }

        // ── Live dot with glow ────────────────────────────────────
        val dotX = (cx + lateralG * scaleX).coerceIn(plotLeft, plotRight)
        val dotY = (cy - longitudinalG * scaleY).coerceIn(plotTop, plotBottom)

        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.25f), Color.Transparent),
                center = Offset(dotX, dotY),
                radius = 12.dp.toPx()
            ),
            radius = 12.dp.toPx(),
            center = Offset(dotX, dotY)
        )
        drawCircle(Frost, radius = 5.dp.toPx(), center = Offset(dotX, dotY))
        drawCircle(Color.White.copy(alpha = 0.6f), radius = 2.dp.toPx(), center = Offset(dotX, dotY))
    }
}
