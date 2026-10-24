package com.openrs.dash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.BoostSessionLog

// ═══════════════════════════════════════════════════════════════════════════
// BOOST SWEEP — rc.6 (#175) — live target vs actual + WGDC across the rev range
// ═══════════════════════════════════════════════════════════════════════════

/** Fewer samples than this and the chart shows a hint instead. */
internal const val BOOST_SWEEP_MIN_SAMPLES = 3

/**
 * X = RPM (1,500–7,000), left Y = boost in the user's unit, right Y = WGDC 0–100 %.
 * Built from the last 90 s of fresh TIP decodes ([BoostSessionLog]), averaged per 250-rpm
 * bin; the newest sample is marked so the sweep reads live.
 */
@Composable
fun BoostSweepChart(samples: List<BoostSessionLog.Sample>, p: UserPrefs, accent: Color) {
    if (samples.size < BOOST_SWEEP_MIN_SAMPLES) {
        Text(
            "BOOST SWEEP — builds as you drive (fresh TIP only, last 90 s)",
            style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = Dim),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )
        return
    }
    val bins = remember(samples) { BoostSweepMath.bins(samples) }
    val (kpaLo, kpaHi) = remember(bins) { BoostSweepMath.kpaRange(bins) }
    val latest = samples.last()
    val textMeasurer = rememberTextMeasurer()
    // Ticks in the readable secondary colour (full Dim); frame + grid stay faint
    val axisStyle = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = Dim)
    val targetCol = Ok; val actualCol = accent; val wgdcCol = Mid; val brd = Brd; val dotCol = Frost
    val loLabel = p.displayBoost(kpaLo).first
    val hiLabel = p.displayBoost(kpaHi).first
    val unit = p.displayBoost(kpaHi).second

    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            // Gutters sized to the measured tick labels so none clip at the canvas edge
            val hiText = textMeasurer.measure(hiLabel, axisStyle)
            val loText = textMeasurer.measure(loLabel, axisStyle)
            val pctText = textMeasurer.measure("100%", axisStyle)
            val left = maxOf(hiText.size.width, loText.size.width) + 8f
            val right = size.width - pctText.size.width - 6f
            val top = hiText.size.height / 2f; val bottom = size.height - hiText.size.height - 4f
            val plotW = right - left; val plotH = bottom - top
            fun x(rpm: Double) = left + plotW * ((rpm.coerceIn(BoostSweepMath.RPM_MIN, BoostSweepMath.RPM_MAX) -
                BoostSweepMath.RPM_MIN) / (BoostSweepMath.RPM_MAX - BoostSweepMath.RPM_MIN)).toFloat()
            fun yKpa(kpa: Double) = top + plotH * (1f - ((kpa - kpaLo) / (kpaHi - kpaLo)).toFloat().coerceIn(0f, 1f))
            fun yPct(pct: Double) = top + plotH * (1f - (pct / 100.0).toFloat().coerceIn(0f, 1f))

            // Frame + RPM grid every 1,000
            drawRect(brd.copy(alpha = 0.4f), Offset(left, top), androidx.compose.ui.geometry.Size(plotW, plotH), style = Stroke(1f))
            var g = 2_000.0
            while (g < BoostSweepMath.RPM_MAX) {
                drawLine(brd.copy(alpha = 0.25f), Offset(x(g), top), Offset(x(g), bottom), strokeWidth = 1f)
                val l = textMeasurer.measure("${(g / 1000).toInt()}k", axisStyle)
                drawText(l, topLeft = Offset(x(g) - l.size.width / 2f, bottom + 2f))
                g += 1_000.0
            }
            // Axis labels: boost (left), WGDC (right)
            drawText(hiText, topLeft = Offset(left - hiText.size.width - 4f, top - hiText.size.height / 2f))
            drawText(loText, topLeft = Offset(left - loText.size.width - 4f, bottom - loText.size.height))
            drawText(pctText, topLeft = Offset(right + 3f, top - pctText.size.height / 2f))
            textMeasurer.measure("0%", axisStyle).let { drawText(it, topLeft = Offset(right + 3f, bottom - it.size.height)) }

            // One path per channel; an empty bin breaks the line
            fun series(get: (BoostSweepMath.Bin) -> Double?, y: (Double) -> Float, color: Color, dashed: Boolean, width: Float) {
                val path = Path()
                var pen = false
                for (b in bins) {
                    val v = b?.let(get)
                    if (b == null || v == null) { pen = false; continue }
                    if (pen) path.lineTo(x(b.rpmMid), y(v)) else path.moveTo(x(b.rpmMid), y(v))
                    pen = true
                }
                drawPath(path, color.copy(alpha = 0.9f), style = Stroke(
                    width = width, cap = StrokeCap.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null
                ))
            }
            series({ it.wgdcPct }, ::yPct, wgdcCol, dashed = false, width = 1.5f)
            series({ it.targetKpa }, ::yKpa, targetCol, dashed = true, width = 2f)
            series({ it.actualKpa }, ::yKpa, actualCol, dashed = false, width = 2.5f)

            // Newest sample — where the engine is right now
            if (latest.rpm >= BoostSweepMath.RPM_MIN) {
                drawCircle(dotCol, radius = 4f, center = Offset(x(latest.rpm), yKpa(latest.actualKpa)))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MonoLabel("━ ACTUAL", 9.sp, actualCol, FontWeight.Medium)
            MonoLabel("┅ TARGET", 9.sp, targetCol, FontWeight.Medium)
            MonoLabel("━ WGDC %", 9.sp, wgdcCol, FontWeight.Medium)
            MonoLabel(unit, 9.sp, Dim)
        }
    }
}
