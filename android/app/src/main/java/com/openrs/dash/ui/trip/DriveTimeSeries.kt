package com.openrs.dash.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Brd
import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.JetBrainsMonoFamily
import com.openrs.dash.ui.MonoLabel
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.Surf2
import com.openrs.dash.ui.Tokens
import com.openrs.dash.ui.Warn
import com.openrs.dash.ui.textMutedAlpha

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE TIME SERIES — horizontally scrollable multi-channel chart
// ═══════════════════════════════════════════════════════════════════════════

/** Channel descriptor: label, color, and value extractor from a DrivePointEntity. */
private data class Channel(
    val label: String,
    val color: @Composable () -> Color,
    val extract: (DrivePointEntity) -> Double
)

private val CHANNELS = listOf(
    Channel("SPD",   { Accent })  { it.speedKph },
    Channel("RPM",   { Warn })    { it.rpm.toDouble() },
    Channel("BOOST", { Ok })      { it.boostPsi },
    Channel("OIL",   { Orange })  { it.oilTempC },
    Channel("G-LAT", { Frost })   { it.lateralG },
    Channel("THRTL", { Dim })     { it.throttlePct },
)

private val DEFAULT_ACTIVE = setOf(0, 2) // SPD + BOOST

/** Downsample a list to at most [maxPoints] entries using stride-based sampling. */
private fun <T> downsample(data: List<T>, maxPoints: Int): List<T> {
    if (data.size <= maxPoints) return data
    val stride = data.size.toFloat() / maxPoints
    return List(maxPoints) { i -> data[(i * stride).toInt().coerceAtMost(data.lastIndex)] }
}

@Composable
fun DriveTimeSeries(
    points: List<DrivePointEntity>,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) return

    val activeIndices = remember { mutableStateListOf<Int>().apply { addAll(DEFAULT_ACTIVE) } }

    // Resolve channel colors in composable context
    val channelColors = CHANNELS.map { it.color() }

    Column(modifier.fillMaxWidth()) {
        // ── Channel selector pills ──────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CHANNELS.forEachIndexed { idx, channel ->
                val active = idx in activeIndices
                val color = channelColors[idx]
                val bg = if (active) color.copy(alpha = 0.18f) else Color.Transparent
                val border = if (active) color.copy(alpha = 0.5f) else Brd.copy(alpha = 0.4f)
                val textColor = if (active) color else Dim.copy(alpha = textMutedAlpha(0.5f))

                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .border(Tokens.CardBorder, border, RoundedCornerShape(6.dp))
                        .clickable {
                            if (active) activeIndices.remove(idx)
                            else activeIndices.add(idx)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    MonoLabel(channel.label, 9.sp, textColor, FontWeight.Medium)
                }
            }
        }

        // ── Chart area ──────────────────────────────────────────────────
        val sampled = remember(points) { downsample(points, 360) }
        val density = LocalDensity.current
        val parentWidthDp = 360.dp  // reasonable default
        val dataWidthDp = (sampled.size * 2).dp
        val chartWidth = maxOf(parentWidthDp, dataWidthDp)

        val textMeasurer = rememberTextMeasurer()
        val axisStyle = TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 8.sp,
            fontWeight = FontWeight.Normal,
            color = Dim.copy(alpha = textMutedAlpha(0.45f))
        )
        val brdColor = Brd
        val surfColor = Surf2

        // Pre-extract active channel data
        val activeChannelData = remember(sampled, activeIndices.toList()) {
            activeIndices.map { idx ->
                val ch = CHANNELS[idx]
                val values = sampled.map { ch.extract(it).toFloat() }
                val min = values.min()
                val max = values.max()
                Triple(idx, values, min to max)
            }
        }

        val totalDurationMs = remember(sampled) {
            val first = sampled.firstOrNull()?.timestamp ?: 0L
            val last = sampled.lastOrNull()?.timestamp ?: 0L
            (last - first).coerceAtLeast(1L)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(Tokens.CardRadius))
                .border(Tokens.CardBorder, brdColor, RoundedCornerShape(Tokens.CardRadius))
                .horizontalScroll(rememberScrollState())
        ) {
            Box(
                Modifier
                    .width(chartWidth)
                    .height(200.dp)
                    .background(surfColor)
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val padBottom = 18f  // room for time labels
                        val padTop = 6f
                        val drawH = h - padBottom - padTop

                        // Build paths for each active channel
                        data class ChannelDraw(
                            val linePath: Path,
                            val fillPath: Path,
                            val color: Color
                        )

                        val draws = activeChannelData.map { (idx, values, range) ->
                            val (minV, maxV) = range
                            val span = (maxV - minV).coerceAtLeast(0.01f)
                            val color = channelColors[idx]

                            val linePath = Path()
                            val fillPath = Path()

                            values.forEachIndexed { i, v ->
                                val frac = i.toFloat() / (values.size - 1).coerceAtLeast(1)
                                val x = frac * w
                                val norm = (v - minV) / span
                                val y = padTop + drawH * (1f - norm)

                                if (i == 0) {
                                    linePath.moveTo(x, y)
                                    fillPath.moveTo(x, y)
                                } else {
                                    linePath.lineTo(x, y)
                                    fillPath.lineTo(x, y)
                                }
                            }

                            // Close fill path along bottom
                            fillPath.lineTo(w, padTop + drawH)
                            fillPath.lineTo(0f, padTop + drawH)
                            fillPath.close()

                            ChannelDraw(linePath, fillPath, color)
                        }

                        // Pre-measure time labels
                        val labelInterval = 60_000L  // 60s
                        val timeLabels = buildList {
                            var t = 0L
                            while (t <= totalDurationMs) {
                                val min = (t / 60_000).toInt()
                                val sec = ((t % 60_000) / 1000).toInt()
                                val label = "%d:%02d".format(min, sec)
                                val frac = t.toFloat() / totalDurationMs.toFloat()
                                add(Triple(label, frac, textMeasurer.measure(label, axisStyle)))
                                t += labelInterval
                            }
                        }

                        onDrawBehind {
                            // Grid lines (25%, 50%, 75%)
                            for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                                val y = padTop + drawH * (1f - frac)
                                drawLine(
                                    color = brdColor.copy(alpha = 0.3f),
                                    start = Offset(0f, y),
                                    end = Offset(w, y),
                                    strokeWidth = 1f
                                )
                            }

                            // Time axis labels
                            timeLabels.forEach { (_, frac, measured) ->
                                val x = frac * w
                                drawText(
                                    measured,
                                    topLeft = Offset(
                                        x - measured.size.width / 2f,
                                        h - padBottom + 4f
                                    )
                                )
                                // Vertical tick
                                drawLine(
                                    color = brdColor.copy(alpha = 0.2f),
                                    start = Offset(x, padTop),
                                    end = Offset(x, padTop + drawH),
                                    strokeWidth = 1f
                                )
                            }

                            // Draw channels (fill first, then lines on top)
                            draws.forEach { d ->
                                drawPath(
                                    d.fillPath,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            d.color.copy(alpha = 0.3f),
                                            Color.Transparent
                                        ),
                                        startY = padTop,
                                        endY = padTop + drawH
                                    )
                                )
                            }
                            draws.forEach { d ->
                                drawPath(
                                    d.linePath,
                                    d.color.copy(alpha = 0.85f),
                                    style = Stroke(width = 2f, cap = StrokeCap.Round)
                                )
                            }
                        }
                    }
            )
        }
    }
}
