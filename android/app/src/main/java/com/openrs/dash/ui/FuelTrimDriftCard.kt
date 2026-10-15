package com.openrs.dash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.FuelTrimDrift
import com.openrs.dash.ui.Tokens.CardShape
import com.openrs.dash.ui.anim.cardGlow

// ═══════════════════════════════════════════════════════════════════════════
// FUEL TRIM DRIFT — rc.5 (roadmap 2.2) — per-drive STFT/LTFT averages over time
// ═══════════════════════════════════════════════════════════════════════════

private const val TRIM_DRIVES = 20
private const val Y_LIMIT = 15f   // chart clamps to ±15 %

@Composable
fun FuelTrimDriftCard() {
    val dao = OpenRSDashApp.instance.driveDb.driveDao()
    val newestFirst by remember { dao.observeTrimDrives(TRIM_DRIVES) }.collectAsState(initial = emptyList())
    val drives = remember(newestFirst) { newestFirst.reversed() }   // chart oldest → newest
    val alert = remember(newestFirst) { FuelTrimDrift.alert(newestFirst) }
    val latestLtft = newestFirst.firstOrNull()?.avgLtftPct
    val expanded = rememberSectionExpanded("perf_fuel_trim", default = false)

    Column(
        Modifier.fillMaxWidth()
            .cardGlow(cornerRadius = Tokens.CardRadius)
            .background(Surf.copy(alpha = 0.65f), CardShape)
            .border(Tokens.CardBorder, Brd.copy(alpha = borderAlpha(0.85f)), CardShape)
            .padding(10.dp)
    ) {
        SectionLabel(
            text = if (drives.isEmpty()) "FUEL TRIM DRIFT" else "FUEL TRIM DRIFT · ${drives.size} DRIVES",
            collapsible = true,
            expanded = expanded.value,
            onToggle = { expanded.value = !expanded.value },
            badge = if (alert != null) alert.label
                    else latestLtft?.let { "LTFT %+.1f%%".format(it) },
            badgeColor = if (alert != null) Warn else null
        )
        if (!expanded.value) return@Column

        Spacer(Modifier.height(6.dp))
        if (alert != null) {
            val hint = if (alert == FuelTrimDrift.Direction.LEAN)
                "PCM adding fuel — check for vacuum/boost leaks or MAF drift"
            else "PCM pulling fuel — check injectors, purge valve or fuel pressure"
            Text(
                "LTFT beyond ${if (alert == FuelTrimDrift.Direction.LEAN) "+" else "−"}" +
                    "${FuelTrimDrift.ALERT_LTFT_PCT.toInt()}% on the last 2 drives — running ${alert.label}. $hint",
                style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = Warn),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Warn.copy(alpha = pillBgAlpha(0.10f)), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            Spacer(Modifier.height(6.dp))
        }

        if (drives.size < 2) {
            Text(
                if (drives.isEmpty()) "NO TRIM DATA YET — averages are saved at drive end once the engine is warm"
                else "1 DRIVE RECORDED — the trend appears after the next warm drive",
                style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = Dim),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
            drives.firstOrNull()?.let { TrimReadout(it) }
            return@Column
        }

        TrimChart(drives)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MonoLabel("━ LTFT", 9.sp, Accent, FontWeight.Medium)
            MonoLabel("┅ STFT", 9.sp, Mid, FontWeight.Medium)
            MonoLabel("+ = lean · − = rich", 9.sp, Dim.copy(alpha = textMutedAlpha(0.6f)))
        }
        Spacer(Modifier.height(4.dp))
        TrimReadout(newestFirst.first())
    }
}

@Composable
private fun TrimReadout(drive: DriveEntity) {
    fun pct(v: Double?) = v?.let { "%+.1f%%".format(it) } ?: "— —"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DataCell("LAST LTFT", pct(drive.avgLtftPct), modifier = Modifier.weight(1f))
        DataCell("LAST STFT", pct(drive.avgStftPct), modifier = Modifier.weight(1f))
        DataCell("SAMPLES", "${drive.trimSamples}", modifier = Modifier.weight(1f))
    }
}

/** X = drive (oldest → newest), Y = trim % clamped to ±[Y_LIMIT], with ±5 / ±10 % bands. */
@Composable
private fun TrimChart(drives: List<DriveEntity>) {
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 8.sp, color = Dim.copy(alpha = textMutedAlpha(0.5f)))
    val okCol = Ok; val warnCol = Warn; val hotCol = Orange; val brd = Brd
    val ltftCol = Accent; val stftCol = Mid

    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val left = 30f; val right = size.width - 6f
        val top = 6f; val bottom = size.height - 6f
        val plotW = right - left; val plotH = bottom - top
        fun y(pct: Float) = top + plotH * (1f - (pct.coerceIn(-Y_LIMIT, Y_LIMIT) + Y_LIMIT) / (2 * Y_LIMIT))
        fun x(i: Int) = left + plotW * i / (drives.size - 1).coerceAtLeast(1)

        // Bands: |trim| ≤ 5 nominal, 5–10 watch, > 10 outside normal adaptation
        fun band(from: Float, to: Float, c: Color) =
            drawRect(c.copy(alpha = 0.08f), Offset(left, y(to)), Size(plotW, y(from) - y(to)))
        band(-FuelTrimDrift.BAND_OK_PCT.toFloat(), FuelTrimDrift.BAND_OK_PCT.toFloat(), okCol)
        band(FuelTrimDrift.BAND_OK_PCT.toFloat(), FuelTrimDrift.BAND_WARN_PCT.toFloat(), warnCol)
        band(-FuelTrimDrift.BAND_WARN_PCT.toFloat(), -FuelTrimDrift.BAND_OK_PCT.toFloat(), warnCol)
        band(FuelTrimDrift.BAND_WARN_PCT.toFloat(), Y_LIMIT, hotCol)
        band(-Y_LIMIT, -FuelTrimDrift.BAND_WARN_PCT.toFloat(), hotCol)

        for (g in listOf(-10f, 0f, 10f)) {
            drawLine(brd.copy(alpha = if (g == 0f) 0.6f else 0.3f), Offset(left, y(g)), Offset(right, y(g)), strokeWidth = 1f)
            val label = textMeasurer.measure(if (g == 0f) "0" else "%+d".format(g.toInt()), axisStyle)
            drawText(label, topLeft = Offset(left - label.size.width - 4f, y(g) - label.size.height / 2f))
        }

        fun series(get: (DriveEntity) -> Double?, color: Color, dashed: Boolean) {
            val path = Path()
            var started = false
            drives.forEachIndexed { i, d ->
                val v = get(d)?.toFloat() ?: return@forEachIndexed
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
                drawCircle(color, radius = 2.5f, center = Offset(x(i), y(v)))
            }
            drawPath(path, color.copy(alpha = 0.85f), style = Stroke(
                width = 2f, cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null
            ))
        }
        series({ it.avgStftPct }, stftCol, dashed = true)
        series({ it.avgLtftPct }, ltftCol, dashed = false)
    }
}
