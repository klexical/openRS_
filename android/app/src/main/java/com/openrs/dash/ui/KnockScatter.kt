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

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
    ) {
        val pad = 24f
        val plotLeft = pad * 2.5f
        val plotRight = size.width - pad
        val plotTop = pad
        val plotBottom = size.height - pad * 2f
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

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
            val rpm = ev.rpm.toFloat().coerceIn(rpmMin, rpmMax)
            val psi = ev.boostPsi.toFloat().coerceIn(boostMin, boostMax)
            val x = plotLeft + plotW * (rpm - rpmMin) / (rpmMax - rpmMin)
            val y = plotTop + plotH * (1f - (psi - boostMin) / (boostMax - boostMin))
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
    }
}

/** Aggregate card: event count, worst cell, severity breakdown. */
@Composable
fun KnockAggregate(
    events: List<KnockEventEntity>,
    modifier: Modifier = Modifier,
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
                "— pulls running clean",
                style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = Dim)
            )
        }
        return
    }

    val worst = events.maxByOrNull { it.krDeg }
    val worstDeg = worst?.krDeg ?: 0.0
    val sum = events.sumOf { it.krDeg }
    val avgDeg = if (events.isNotEmpty()) sum / events.size else 0.0
    val highCount = events.count { it.krDeg >= 4.0 }
    val medCount = events.count { it.krDeg in 2.0..3.9999 }
    val lowCount = events.count { it.krDeg in 0.1..1.9999 }

    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AggCell("COUNT", "${events.size}", Frost, modifier = Modifier.weight(1f))
        AggCell("WORST", "${"%.1f".format(worstDeg)}°", severityColor(worstDeg), modifier = Modifier.weight(1f))
        AggCell("AVG", "${"%.1f".format(avgDeg)}°", Frost, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AggCell("≥4°", "$highCount", severityColor(4.0), modifier = Modifier.weight(1f))
        AggCell("2-4°", "$medCount", severityColor(2.5), modifier = Modifier.weight(1f))
        AggCell("<2°", "$lowCount", severityColor(1.0), modifier = Modifier.weight(1f))
    }
    worst?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            "Worst cell: ${it.rpm} RPM @ ${"%.1f".format(it.boostPsi)} PSI",
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

internal fun severityColor(krDeg: Double): Color = when {
    krDeg >= 4.0 -> Color(0xFFE53935)       // red — serious retard
    krDeg >= 2.0 -> Color(0xFFFF9800)       // orange — moderate
    krDeg >= 0.1 -> Color(0xFFFFEB3B)       // yellow — minor
    else         -> Color(0xFF9E9E9E)       // grey — zero/noise
}
