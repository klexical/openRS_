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
 * Knock event scatter plot (RPM × boost), colour-coded by retard severity.
 *
 * x = RPM (0 → 7000)
 * y = boost, plotted in the stored gauge psi (-15 bottom → +22 top); the gridlines, their labels and the axis
 *     title follow the boost-pressure setting ([p]) — same gauge values as the live boost readings.
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
    p: UserPrefs? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    // Ticks + titles in the readable secondary colour (full Dim); only the grid stays faint
    val axisStyle = remember(Dim) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = Dim,
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

        // Horizontal gridlines at round values of the user's unit (0 / 10 / 20 psi, 0 / 0.5 / 1.0 bar, 0 / 50 / 100 kPa
        // — all gauge), placed at their psi equivalent on the stored-psi scale. The top label stays below the axis title.
        val boostGrid = knockBoostGrid(p?.boostUnit)
        for ((b, text) in boostGrid) {
            if (b < boostMin || b > boostMax) continue
            val y = plotTop + plotH * (1f - (b - boostMin) / (boostMax - boostMin))
            drawLine(
                color = Brd.copy(alpha = 0.45f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 0.5f
            )
            val label = textMeasurer.measure(text, axisStyle)
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

        // Axis titles — RPM sits in the tick row between 2k and 4k (at 3000 rpm; the plot centre is the 4k tick,
        // which the title used to sit on); PSI above the frame, clear of the +20 tick
        val xTitle = textMeasurer.measure("RPM", axisStyle.copy(fontWeight = FontWeight.Medium))
        val xTitleX = plotLeft + plotW * (3000f - rpmMin) / (rpmMax - rpmMin)
        drawText(
            textLayoutResult = xTitle,
            topLeft = Offset(xTitleX - xTitle.size.width / 2f, plotBottom + 4f)
        )
        val yTitle = textMeasurer.measure(knockBoostUnit(p), axisStyle.copy(fontWeight = FontWeight.Medium))
        drawText(
            textLayoutResult = yTitle,
            topLeft = Offset(plotLeft - yTitle.size.width - 4f, (plotTop - yTitle.size.height - 4f).coerceAtLeast(0f))
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
            AggCell("BOOST", knockBoostText(ev.boostPsi, p), Frost, modifier = Modifier.weight(1f))
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
                style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Dim)
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
    p: UserPrefs? = null,
) {
    if (events.isEmpty()) {
        Row(
            modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Ink, not green — "nothing recorded" isn't a pass; and "running clean" only when pulls were seen
            // (offline / before the first pull there's nothing to vouch for).
            Text(
                "NO KNOCK EVENTS",
                style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Frost, fontWeight = FontWeight.Medium)
            )
            Text(
                if (pulls != null && pulls.total > 0) "— ${pulls.total} pull${if (pulls.total == 1) "" else "s"} running clean"
                else "— no pulls recorded yet",
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
    }
    PullsRow(pulls)
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // A bucket only takes its severity colour when it has events — an amber "0" reads as a warning.
        fun bucket(n: Int, deg: Double) = if (n > 0) severityColor(deg) else Dim
        AggCell("≥4°", "${stats.highCount}", bucket(stats.highCount, KnockStats.HIGH_DEG), modifier = Modifier.weight(1f))
        AggCell("2-4°", "${stats.medCount}", bucket(stats.medCount, KnockStats.MED_DEG), modifier = Modifier.weight(1f))
        AggCell("<2°", "${stats.lowCount}", bucket(stats.lowCount, 1.0), modifier = Modifier.weight(1f))
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
            "Worst cell: ${it.rpm} RPM @ ${knockBoostText(it.boostPsi, p)}$cylLabel",
            style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Dim),
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun AggCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Dim)
        )
        Text(
            value,
            style = TextStyle(fontFamily = RajdhaniFamily, fontSize = 18.sp, color = textColor(color), fontWeight = FontWeight.SemiBold)
        )
    }
}

/**
 * Pulls with KR > 0 over all WOT pulls (#174), on its own row: the count is the reading, the share is
 * secondary text. As one "1/1 · 100%" cell it wrapped on 360 dp and out-weighed COUNT / WORST / AVG.
 */
@Composable
private fun PullsRow(pulls: KnockSessionLog.Pulls?) {
    val (count, share) = pullsParts(pulls)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "PULLS WITH KNOCK",
            style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Dim),
            modifier = Modifier.weight(1f)
        )
        Text(
            count,
            style = TextStyle(fontFamily = RajdhaniFamily, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = textColor(if ((pulls?.knocked ?: 0) > 0) Warn else Frost))
        )
        share?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Dim))
        }
    }
}

/** ("2/5", "40%") — knocked / total pulls and the share; ("—", null) when none recorded (#174). */
internal fun pullsParts(pulls: KnockSessionLog.Pulls?): Pair<String, String?> {
    if (pulls == null || pulls.total <= 0) return "—" to null
    return "${pulls.knocked}/${pulls.total}" to "${(pulls.knocked * 100.0 / pulls.total).roundToInt()}%"
}

/**
 * KnockStats bands (unchanged) in theme colours: ≥ HIGH Orange (critical), ≥ MED Warn, minor (< MED) Mid,
 * zero Dim. The fixed red / orange / yellow hexes ignored the theme — the yellow was unreadable on DAY.
 */
internal fun severityColor(krDeg: Double): Color = when {
    krDeg >= KnockStats.HIGH_DEG -> Orange
    krDeg >= KnockStats.MED_DEG  -> Warn
    krDeg > 0.0                  -> Mid
    else                         -> Dim
}

// ── Boost in the user's unit (knock events store gauge psi) ─────────────────

/** Stored gauge psi → the boost-pressure setting's value + unit, exactly like the live boost readings. */
internal fun knockBoostText(gaugePsi: Double, p: UserPrefs?): String {
    if (p == null) return "${"%.1f".format(gaugePsi)} PSI"
    val (v, unit) = p.displayBoost(gaugePsi / UnitConversions.KPA_TO_PSI + UnitConversions.STD_ATM_KPA)
    return "$v $unit"
}

private fun knockBoostUnit(p: UserPrefs?): String = when (p?.boostUnit) { "BAR" -> "BAR"; "KPA" -> "kPa"; else -> "PSI" }

/** Gridlines as (gauge psi, label in the user's unit). */
internal fun knockBoostGrid(boostUnit: String?): List<Pair<Float, String>> = when (boostUnit) {
    "BAR" -> listOf(0.0, 0.5, 1.0).map { bar -> (bar / UnitConversions.PSI_TO_BAR).toFloat() to if (bar == 0.0) "0" else "+%.1f".format(bar) }
    "KPA" -> listOf(0, 50, 100).map { kpa -> (kpa * UnitConversions.KPA_TO_PSI).toFloat() to if (kpa == 0) "0" else "+$kpa" }
    else  -> listOf(0, 10, 20).map { psi -> psi.toFloat() to if (psi == 0) "0" else "+$psi" }
}
