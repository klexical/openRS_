package com.openrs.dash.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
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
import com.openrs.dash.ui.Mid
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

/**
 * Channel descriptor.
 * @param axis   channels with the same key share one min/max so their lines are comparable
 *               (null = own scale)
 * @param dashed draw as a dashed line (used for a target overlaid on its actual)
 * @param valid  false for absent / sentinel samples — skipped, not plotted as values
 * @param sparse polled channel: no area fill, chip hidden when the drive has no valid data
 */
private data class Channel(
    val label: String,
    val color: @Composable () -> Color,
    val axis: String? = null,
    val dashed: Boolean = false,
    val sparse: Boolean = false,
    val valid: (DrivePointEntity) -> Boolean = { true },
    val extract: (DrivePointEntity) -> Double
)

private val CHANNELS = listOf(
    Channel("SPD",    { Accent })  { it.speedKph },
    Channel("RPM",    { Warn })    { it.rpm.toDouble() },
    Channel("BOOST",  { Ok }, axis = "boost") { it.boostPsi },
    Channel("OIL",    { Orange }, valid = { it.oilTempC > -90.0 }) { it.oilTempC },  // -99 = not yet received
    Channel("G-LAT",  { Frost })   { it.lateralG },
    Channel("THRTL",  { Dim })     { it.throttlePct },
    // Boost sweep (#175/#188): TARGET shares BOOST's axis + colour, dashed. Recorder writes
    // 0 kPa target / −1 % WGDC when the DID wasn't freshly polled (#184) — those are gaps.
    Channel("TARGET", { Ok }, axis = "boost", dashed = true, sparse = true,
        valid = { it.boostTargetKpa > 0.0 }) { (it.boostTargetKpa - 101.325) * 0.14503773 },
    Channel("WGDC",   { Mid }, sparse = true,
        valid = { it.wgdcPct >= 0.0 }) { it.wgdcPct },
)

private val DEFAULT_ACTIVE = setOf(0, 2) // SPD + BOOST

/** Pre-computed per-channel draw inputs. [range] is shared across an axis group. */
private data class ChannelData(
    val idx: Int,
    val values: List<Float>,
    val runs: List<List<Int>>,
    val range: Pair<Float, Float>
)

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

    // Polled channels with nothing to show in this drive (pre-v10 drives, PCM silent, or a
    // WGDC series that's all zero) get no chip at all.
    val available = remember(points) {
        CHANNELS.map { ch ->
            !ch.sparse || points.any { ch.valid(it) && ch.extract(it) != 0.0 }
        }
    }

    Column(modifier.fillMaxWidth()) {
        // ── Channel selector pills ──────────────────────────────────────
        // Wrap onto further rows — on 360 dp the last chip (WGDC) was squeezed into a vertical sliver.
        // Each chip is a 48 dp-tall touch area around a compact visible pill.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CHANNELS.forEachIndexed { idx, channel ->
                if (!available[idx]) return@forEachIndexed
                val active = idx in activeIndices
                val color = channelColors[idx]
                val bg = if (active) color.copy(alpha = 0.18f) else Color.Transparent
                val border = if (active) color.copy(alpha = 0.5f) else Brd.copy(alpha = 0.4f)
                val textColor = if (active) color else Dim

                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = "${channel.label} ${if (active) "off" else "on"}") {
                            if (active) activeIndices.remove(idx)
                            else activeIndices.add(idx)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg)
                            .border(Tokens.CardBorder, border, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        MonoLabel(channel.label, 10.sp, textColor, FontWeight.Medium)
                    }
                }
            }
        }

        // ── Chart area ──────────────────────────────────────────────────
        val sampled = remember(points) { downsample(points, 360) }
        val dataWidthDp = (sampled.size * 2).dp

        val textMeasurer = rememberTextMeasurer()
        // Time labels in the readable secondary colour (full Dim); the grid stays faint.
        val axisStyle = TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal,
            color = Dim
        )
        val brdColor = Brd
        val surfColor = Surf2

        // Pre-extract active channel data: values, validity, runs, and an axis range shared
        // by channels in the same axis group (BOOST + TARGET).
        val activeChannelData = remember(sampled, activeIndices.toList(), available) {
            val timestamps = sampled.map { it.timestamp }
            val visible = activeIndices.filter { available[it] }
            val extracted = visible.associateWith { idx ->
                val ch = CHANNELS[idx]
                sampled.map { ch.extract(it).toFloat() } to sampled.map { ch.valid(it) }
            }
            visible.mapNotNull { idx ->
                val ch = CHANNELS[idx]
                val (values, valid) = extracted.getValue(idx)
                val group = if (ch.axis == null) listOf(idx)
                            else visible.filter { CHANNELS[it].axis == ch.axis }
                val range = TimeSeriesMath.sharedRange(group.map { extracted.getValue(it) })
                    ?: return@mapNotNull null
                ChannelData(idx, values, TimeSeriesMath.segments(timestamps, valid), range)
            }
        }

        val totalDurationMs = remember(sampled) {
            val first = sampled.firstOrNull()?.timestamp ?: 0L
            val last = sampled.lastOrNull()?.timestamp ?: 0L
            (last - first).coerceAtLeast(1L)
        }

        // The chart is at least the viewport wide (was a fixed 360 dp — wider than a 360 dp phone's viewport, so
        // it scrolled and the last label sat cut at the edge). Time labels draw in a non-scrolling overlay and
        // only where a whole label fits inside the viewport, so none is ever shown partly clipped.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chartWidth = maxOf(maxWidth, dataWidthDp)
        val scroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(Tokens.CardRadius))
                .border(Tokens.CardBorder, brdColor, RoundedCornerShape(Tokens.CardRadius))
        ) {
            Box(Modifier.matchParentSize().horizontalScroll(scroll)) {
            Box(
                Modifier
                    .width(chartWidth)
                    .height(200.dp)
                    .background(surfColor)
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        // Bottom strip sized to the measured label (was a fixed 18 px — labels were clipped)
                        val sampleLabel = textMeasurer.measure("00:00", axisStyle)
                        val padBottom = sampleLabel.size.height + 8.dp.toPx()
                        val padTop = 6f
                        val drawH = h - padBottom - padTop

                        // Build paths for each active channel
                        data class ChannelDraw(
                            val linePath: Path,
                            val fillPath: Path?,
                            val dots: List<Offset>,
                            val color: Color,
                            val dashed: Boolean
                        )

                        val draws = activeChannelData.map { cd ->
                            val ch = CHANNELS[cd.idx]
                            val (minV, maxV) = cd.range
                            val span = (maxV - minV).coerceAtLeast(0.01f)
                            val lastIdx = (cd.values.size - 1).coerceAtLeast(1)
                            fun xAt(i: Int) = i.toFloat() / lastIdx * w
                            fun yAt(i: Int) = padTop + drawH * (1f - (cd.values[i] - minV) / span)

                            val linePath = Path()
                            val dots = ArrayList<Offset>()
                            // Area fill only for continuous CAN channels — a gapped polled
                            // series would fill misleading wedges across its holes.
                            val fillPath = if (ch.sparse) null else Path()
                            for (run in cd.runs) {
                                if (run.size == 1) { dots += Offset(xAt(run[0]), yAt(run[0])); continue }
                                run.forEachIndexed { k, i ->
                                    if (k == 0) linePath.moveTo(xAt(i), yAt(i)) else linePath.lineTo(xAt(i), yAt(i))
                                }
                                fillPath?.apply {
                                    moveTo(xAt(run.first()), padTop + drawH)
                                    run.forEach { i -> lineTo(xAt(i), yAt(i)) }
                                    lineTo(xAt(run.last()), padTop + drawH)
                                    close()
                                }
                            }
                            ChannelDraw(linePath, fillPath, dots, channelColors[cd.idx], ch.dashed)
                        }
                        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

                        // Pre-measure time labels. The interval comes from the width available: the smallest
                        // step that keeps labels a label-width + 12 dp apart (was every 60 s — they overlapped).
                        val tickFracs = timeTickFracs(totalDurationMs, w, sampleLabel.size.width + 12.dp.toPx())

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

                            // Time ticks (labels: overlay below)
                            tickFracs.forEach { (_, frac) ->
                                val x = frac * w
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
                                val fill = d.fillPath ?: return@forEach
                                drawPath(
                                    fill,
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
                                    style = Stroke(
                                        width = 2f, cap = StrokeCap.Round,
                                        pathEffect = if (d.dashed) dash else null
                                    )
                                )
                                d.dots.forEach { drawCircle(d.color.copy(alpha = 0.85f), radius = 2f, center = it) }
                            }
                        }
                    }
            )
            }
            // Time labels — fixed to the viewport, positioned from the scroll offset.
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val chartW = chartWidth.toPx()
                val sample = textMeasurer.measure("00:00", axisStyle)
                val y = size.height - sample.size.height - 4.dp.toPx()
                // Centred under its tick; a tick on screen whose label would cross the edge (0:00 at the start,
                // the last one at the end) is edge-aligned instead — if that keeps 4 dp clear of its neighbours.
                // Otherwise it is left out; a partly clipped label is never drawn.
                val edge = 2f; val clear = 4.dp.toPx()
                val placed = timeTickFracs(totalDurationMs, chartW, sample.size.width + 12.dp.toPx()).map { (label, frac) ->
                    val m = textMeasurer.measure(label, axisStyle)
                    val x = frac * chartW - scroll.value
                    Triple(m, x, x - m.size.width / 2f)
                }
                placed.forEachIndexed { i, (m, x, centred) ->
                    val lw = m.size.width.toFloat()
                    val left = when {
                        centred >= edge && centred + lw <= size.width - edge -> centred
                        x in 0f..size.width -> centred.coerceIn(edge, size.width - lw - edge)
                        else -> return@forEachIndexed
                    }
                    val prevRight = placed.getOrNull(i - 1)?.let { (pm, _, pc) -> pc + pm.size.width } ?: Float.NEGATIVE_INFINITY
                    val nextLeft = placed.getOrNull(i + 1)?.third ?: Float.POSITIVE_INFINITY
                    if (left - prevRight >= clear && nextLeft - (left + lw) >= clear) drawText(m, topLeft = Offset(left, y))
                }
            }
        }
        }
    }
}

/**
 * Time ticks as (label, fraction of the drive): the smallest step (15 s … 1 h) that keeps ticks
 * [minGapPx] apart across [widthPx] — every 60 s overlapped on short charts.
 */
private fun timeTickFracs(totalMs: Long, widthPx: Float, minGapPx: Float): List<Pair<String, Float>> {
    val step = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L)
        .firstOrNull { it.toFloat() / totalMs * widthPx >= minGapPx } ?: 3_600_000L
    return buildList {
        var t = 0L
        while (t <= totalMs) {
            add("%d:%02d".format((t / 60_000).toInt(), ((t % 60_000) / 1000).toInt()) to t.toFloat() / totalMs)
            t += step
        }
    }
}
