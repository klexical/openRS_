package com.openrs.dash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.data.KnockStats
import com.openrs.dash.data.KnockSessionLog
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt

/**
 * Knock event scatter plot (RPM × boost PSI), colour-coded by retard severity.
 *
 * x = RPM (0 → 7000)
 * y = boost PSI (-15 bottom → +22 top)
 * dot color = krDeg severity (yellow <2°, orange 2-4°, red ≥4°)
 */
@Composable
fun KnockScatter(
    events: List<KnockEventEntity>,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {},
    rpmMin: Float = 0f,
    rpmMax: Float = 7000f,
    boostMin: Float = -15f,
    boostMax: Float = 22f,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = remember(Dim) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 8.sp,
            color = Dim.copy(alpha = textMutedAlpha(0.5f)),
            fontWeight = FontWeight.Normal
        )
    }

    val ringColor = Frost
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            // Tap a dot for its snapshot (#174); tapping empty space clears the selection
            .pointerInput(events) {
                detectTapGestures { tap ->
                    val plot = KnockScatterMath.plot(size.width.toFloat(), size.height.toFloat())
                    onSelect(KnockScatterMath.nearest(events, tap.x, tap.y, plot, TAP_RADIUS_DP.dp.toPx(),
                        rpmMin, rpmMax, boostMin, boostMax))
                }
            }
    ) {
        val plot = KnockScatterMath.plot(size.width, size.height)
        val plotLeft = plot.left
        val plotRight = plot.right
        val plotTop = plot.top
        val plotBottom = plot.bottom
        val plotW = plot.width
        val plotH = plot.height

        // Frame
        drawRect(
            color = Brd,
            topLeft = Offset(plotLeft, plotTop),
            size = Size(plotW, plotH),
            style = Stroke(width = 0.8f)
        )

        // Horizontal gridlines @ 0, +10, +20 PSI
        val boostGrid = listOf(0f, 10f, 20f)
        for (b in boostGrid) {
            if (b < boostMin || b > boostMax) continue
            val y = plotTop + plotH * (1f - (b - boostMin) / (boostMax - boostMin))
            drawLine(
                color = Brd.copy(alpha = 0.45f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 0.5f
            )
            val label = textMeasurer.measure(if (b == 0f) "0" else "+${b.toInt()}", axisStyle)
            drawText(
                textLayoutResult = label,
                topLeft = Offset(plotLeft - label.size.width - 4f, y - label.size.height / 2f)
            )
        }

        // Vertical gridlines @ every 2000 RPM
        val rpmGrid = listOf(2000f, 4000f, 6000f)
        for (r in rpmGrid) {
            if (r < rpmMin || r > rpmMax) continue
            val x = plotLeft + plotW * (r - rpmMin) / (rpmMax - rpmMin)
            drawLine(
                color = Brd.copy(alpha = 0.45f),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = 0.5f
            )
            val label = textMeasurer.measure("${r.toInt() / 1000}k", axisStyle)
            drawText(
                textLayoutResult = label,
                topLeft = Offset(x - label.size.width / 2f, plotBottom + 4f)
            )
        }

        // Axis titles
        val xTitle = textMeasurer.measure("RPM", axisStyle.copy(fontWeight = FontWeight.Medium))
        drawText(
            textLayoutResult = xTitle,
            topLeft = Offset(plotLeft + plotW / 2f - xTitle.size.width / 2f, plotBottom + 16f)
        )
        val yTitle = textMeasurer.measure("PSI", axisStyle.copy(fontWeight = FontWeight.Medium))
        drawText(
            textLayoutResult = yTitle,
            topLeft = Offset(2f, plotTop - 2f)
        )

        // Events
        for (ev in events) {
            val (x, y) = KnockScatterMath.project(ev, plot, rpmMin, rpmMax, boostMin, boostMax)
            val color = severityColor(ev.krDeg)
            drawCircle(
                color = color.copy(alpha = 0.35f),
                radius = 6f,
                center = Offset(x, y)
            )
            drawCircle(
                color = color,
                radius = 3f,
                center = Offset(x, y)
            )
        }
        selected?.let { events.getOrNull(it) }?.let { ev ->
            val (x, y) = KnockScatterMath.project(ev, plot, rpmMin, rpmMax, boostMin, boostMax)
            drawCircle(color = ringColor, radius = 9f, center = Offset(x, y), style = Stroke(width = 1.5f))
        }
    }
}

private const val TAP_RADIUS_DP = 16

/** Snapshot of one tapped knock event (#174). */
@Composable
fun KnockEventDetail(ev: KnockEventEntity, p: UserPrefs, modifier: Modifier = Modifier) {
    val time = remember(ev.timestamp) {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ev.timestamp))
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            buildString {
                append(time)
                if (ev.cylinder in 1..4) append(" · CYL ${ev.cylinder}")
                append(" · ${"%.2f".format(ev.krDeg)}° RETARD")
            },
            style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp,
                color = severityColor(ev.krDeg), fontWeight = FontWeight.Medium)
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AggCell("RPM", "${ev.rpm}", Frost, modifier = Modifier.weight(1f))
            AggCell("BOOST", "${"%.1f".format(ev.boostPsi)} PSI", Frost, modifier = Modifier.weight(1f))
            AggCell("IAT", if (ev.iatC > -90) "${p.displayTemp(ev.iatC)}${p.tempLabel}" else "—", Frost, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AggCell("THROTTLE", "${"%.0f".format(ev.throttlePct)}%", Frost, modifier = Modifier.weight(1f))
            AggCell("GEAR", if (ev.gearEst > 0) "${ev.gearEst}" else "—", Frost, modifier = Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
        if (ev.lat != null && ev.lng != null) {
            Text(
                "GPS ${"%.5f".format(ev.lat)}, ${"%.5f".format(ev.lng)}",
                style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = Dim)
            )
        }
    }
}

/** Aggregate card: event count, worst cell, severity breakdown. */
@Composable
fun KnockAggregate(
    events: List<KnockEventEntity>,
    modifier: Modifier = Modifier,
    pulls: KnockSessionLog.Pulls? = null,
) {
    if (events.isEmpty()) {
        Row(
            modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "NO KNOCK EVENTS",
                style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Ok, fontWeight = FontWeight.Medium)
            )
            Text(
                if (pulls != null && pulls.total > 0) "— ${pulls.total} pull${if (pulls.total == 1) "" else "s"} running clean"
                else "— pulls running clean",
                style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Dim)
            )
        }
        return
    }

    val stats = KnockStats.from(events)
    val worstDeg = stats.worst?.krDeg ?: 0.0

    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AggCell("COUNT", "${stats.count}", Frost, modifier = Modifier.weight(1f))
        AggCell("WORST", "${"%.1f".format(worstDeg)}°", severityColor(worstDeg), modifier = Modifier.weight(1f))
        AggCell("AVG", "${"%.1f".format(stats.avgDeg)}°", Frost, modifier = Modifier.weight(1f))
        AggCell("PULLS", pullsLabel(pulls), if ((pulls?.knocked ?: 0) > 0) Warn else Frost, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AggCell("≥4°", "${stats.highCount}", severityColor(KnockStats.HIGH_DEG), modifier = Modifier.weight(1f))
        AggCell("2-4°", "${stats.medCount}", severityColor(KnockStats.MED_DEG), modifier = Modifier.weight(1f))
        AggCell("<2°", "${stats.lowCount}", severityColor(1.0), modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    // Per-cylinder split (#186). Cyl 2–4 stay 0 if the PCM rejects DIDs 0x03ED–EF.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (cyl in 1..4) {
            val n = stats.perCylinder[cyl - 1]
            AggCell("CYL $cyl", "$n", if (n > 0) Frost else Dim, modifier = Modifier.weight(1f))
        }
    }
    stats.worst?.let {
        Spacer(Modifier.height(4.dp))
        val cylLabel = if (it.cylinder in 1..4) " · cyl ${it.cylinder}" else ""
        Text(
            "Worst cell: ${it.rpm} RPM @ ${"%.1f".format(it.boostPsi)} PSI$cylLabel",
            style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = Dim),
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun AggCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 8.sp, color = Dim.copy(alpha = textMutedAlpha(0.7f)))
        )
        Text(
            value,
            style = TextStyle(fontFamily = RajdhaniFamily, fontSize = 18.sp, color = color, fontWeight = FontWeight.SemiBold)
        )
    }
}

/** "2/5 · 40%" — pulls with KR > 0 over all WOT pulls; "—" when none recorded (#174). */
internal fun pullsLabel(pulls: KnockSessionLog.Pulls?): String {
    if (pulls == null || pulls.total <= 0) return "—"
    return "${pulls.knocked}/${pulls.total} · ${(pulls.knocked * 100.0 / pulls.total).roundToInt()}%"
}

internal fun severityColor(krDeg: Double): Color = when {
    krDeg >= KnockStats.HIGH_DEG -> Color(0xFFE53935)   // red — serious retard
    krDeg >= KnockStats.MED_DEG  -> Color(0xFFFF9800)   // orange — moderate
    krDeg > 0.0                  -> Color(0xFFFFEB3B)   // yellow — minor (counted in <2° bucket)
    else         -> Color(0xFF9E9E9E)       // grey — zero/noise
}
