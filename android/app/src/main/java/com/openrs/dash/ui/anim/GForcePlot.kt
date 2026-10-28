package com.openrs.dash.ui.anim

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.textMutedAlpha
import com.openrs.dash.ui.JetBrainsMonoFamily
import com.openrs.dash.ui.LocalThemeAccent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Rounds up to the nearest 0.25 increment, minimum 0.5. */
private fun ceilToQuarter(v: Float): Float =
    max(0.5f, ceil(v * 4f) / 4f)

/** Stable style for peak labels — color is applied per-draw via .copy(color = ...). */
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
    dotColor: Color = LocalThemeAccent.current,
    // When false, the plot fades to a dimmed skeleton + centred "awaiting data"
    // hint, so a parked-at-zero plot doesn't read as a real live trace.
    isConnected: Boolean = true
) {
    val skeletonAlpha = if (isConnected) 1f else 0.45f
    val accent = LocalThemeAccent.current
    val textMeasurer = rememberTextMeasurer()

    // Styles keyed on Dim (brightness-scaled) so they refresh on theme/brightness change.
    // peakStyle has no theme-dependent color and is reused across all instances.
    val axisTitleStyle = remember(Dim) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = Dim,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
    val scaleStyle = remember(Dim) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = Dim.copy(alpha = textMutedAlpha(0.8f)),
            fontWeight = FontWeight.Normal
        )
    }
    val peakStyle = remember(Frost) {
        TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Frost, fontWeight = FontWeight.Medium)
    }

    var trailStarted by remember { mutableStateOf(false) }
    LaunchedEffect(trail) { if (trail.isNotEmpty()) trailStarted = true }
    val trailEntrance by animateFloatAsState(
        targetValue = if (trailStarted) 1f else 0f,
        animationSpec = tween(600, easing = EaseOut),
        label = "trailFade"
    )

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
        // Row 1 (top):    "ACCEL ↑" (top of the Y axis)  ...  "PEAK LAT X.XX"
        // Row 2:          scale + plot area
        // Row 3 (bottom): X scale numbers with "LATERAL G" centred between them
        // Row 4:          "BRAKE ↓"  ...  "PEAK LON X.XX"
        //
        // Left column:    Y scale numbers
        // Right column:   (nothing, scale at bottom-right)

        val topLabelH = 18.dp.toPx()    // ACCEL / peak row
        val topGap = 4.dp.toPx()
        val botScaleH = 14.dp.toPx()    // scale numbers below plot
        val botLabelH = 18.dp.toPx()    // BRAKE / LATERAL G / peak row
        val botAxisH = 0f
        val leftScaleW = 36.dp.toPx()   // Y scale numbers

        // Square plot area, centred in the space left by the label rows — equal g per px on both axes
        val availW = size.width - 4.dp.toPx() - leftScaleW
        val availH = size.height - botScaleH - botLabelH - botAxisH - topLabelH - topGap
        val side = min(availW, availH)
        val plotLeft = leftScaleW + (availW - side) / 2f
        val plotTop = topLabelH + topGap
        val plotRight = plotLeft + side
        val plotBottom = plotTop + side
        val plotW = side
        val plotH = side
        val cx = plotLeft + plotW / 2f
        val cy = plotTop + plotH / 2f
        val scaleX = (plotW / 2f) / maxG
        val scaleY = (plotH / 2f) / maxG

        // ── Grid lines (dense, ~8-10 divisions per axis) ──────────
        val gridColor = Dim.copy(alpha = 0.30f * skeletonAlpha)
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
        val borderColor = Dim.copy(alpha = 0.30f * skeletonAlpha)
        val borderStroke = 1.dp.toPx()
        drawLine(borderColor, Offset(plotLeft, plotTop), Offset(plotRight, plotTop), borderStroke)       // top
        drawLine(borderColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), borderStroke)  // bottom
        drawLine(borderColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), borderStroke)      // left
        drawLine(borderColor, Offset(plotRight, plotTop), Offset(plotRight, plotBottom), borderStroke)    // right

        // ── Center crosshair ──────────────────────────────────────
        val crossColor = Dim.copy(alpha = 0.50f * skeletonAlpha)
        val crossStroke = 2.5.dp.toPx()
        drawLine(crossColor, Offset(cx, plotTop), Offset(cx, plotBottom), crossStroke)
        drawLine(crossColor, Offset(plotLeft, cy), Offset(plotRight, cy), crossStroke)

        // ── Corner brackets ───────────────────────────────────────
        // rc.2: neutral (not accent) so brackets read as structural, not an "error state"
        // on any of the 6 paints. Uses Dim rather than accent for cross-paint consistency.
        val bracketColor = Dim.copy(alpha = 0.55f * skeletonAlpha)
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

        // ── Axis titles + peaks. X = lateral, Y = longitudinal (the old layout labelled them the
        // other way round: "LAT G" beside the vertical axis, "ACCEL / BRAKE G" under the horizontal).
        val accelM = textMeasurer.measure("ACCEL \u2191", axisTitleStyle)
        drawText(accelM, topLeft = Offset(plotLeft, 0f))
        val rowY = plotBottom + botScaleH + 2.dp.toPx()
        val brakeM = textMeasurer.measure("BRAKE \u2193", axisTitleStyle)
        drawText(brakeM, topLeft = Offset(plotLeft, rowY))
        val latTitleM = textMeasurer.measure("LATERAL G", axisTitleStyle)
        drawText(latTitleM, topLeft = Offset(cx - latTitleM.size.width / 2f, plotBottom + 3.dp.toPx()))
        if (peakLatG > 0f) {
            val m = textMeasurer.measure("PEAK LAT %.2f".format(peakLatG), peakStyle)
            drawText(m, topLeft = Offset(plotRight - m.size.width, 0f))
        }
        if (peakLonG > 0f) {
            val m = textMeasurer.measure("PEAK LON %.2f".format(peakLonG), peakStyle)
            drawText(m, topLeft = Offset(plotRight - m.size.width, rowY))
        }

        // ── Awaiting-data hint — center of plot when disconnected ──
        if (!isConnected) {
            val hintStyle = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = Dim.copy(alpha = textMutedAlpha(0.7f)),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            val hintM = textMeasurer.measure("— AWAITING DATA —", hintStyle)
            drawText(
                hintM,
                topLeft = Offset(cx - hintM.size.width / 2f, cy - hintM.size.height / 2f)
            )
            return@Canvas
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

                val dotAlpha = (0.15f + ageFrac * 0.65f) * trailEntrance
                val dotR = minDotR + ageFrac * (maxDotR - minDotR)

                // Age gradient only (accent → ink). The V5 direction tints (orange / blue / green /
                // red) were decoration in alarm colours, against the design-pass colour rule.
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
