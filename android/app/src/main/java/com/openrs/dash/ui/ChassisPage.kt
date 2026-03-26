package com.openrs.dash.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.anim.CarDiagram
import com.openrs.dash.ui.anim.GForcePlot
import com.openrs.dash.ui.anim.RingBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// CHASSIS PAGE (AWD + G-Force + TPMS)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun ChassisPage(vs: VehicleState, p: UserPrefs, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GForceSection(vs, onReset)
        AwdSection(vs, p)
        TpmsSection(vs, p)
    }
}

@Composable fun AwdSection(vs: VehicleState, p: UserPrefs) {
    val accent   = LocalThemeAccent.current
    val total    = vs.totalRearTorque
    val leftPct  = if (total > 0) (vs.awdLeftTorque / total).toFloat() else 0.5f
    val rightPct = (1f - leftPct).coerceIn(0.01f, 0.99f)
    val leftPctC = leftPct.coerceIn(0.01f, 0.99f)
    val avgF = (vs.wheelSpeedFL + vs.wheelSpeedFR) / 2
    val avgR = (vs.wheelSpeedRL + vs.wheelSpeedRR) / 2
    val frDelta = avgR - avgF
    val lrDelta = vs.wheelSpeedRR - vs.wheelSpeedRL

    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel("AWD — GKN TWINSTER")

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WheelCell("FL", "${"%.1f".format(vs.wheelSpeedFL)}", front = true)
                WheelCell("RL", "${"%.1f".format(vs.wheelSpeedRL)}", front = false)
            }
            Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeroNum(vs.frontRearSplit, 20.sp, accent)
                MonoLabel("F / R", 8.sp, Dim, letterSpacing = 0.1.sp)
                Spacer(Modifier.height(4.dp))
                val rduStr = if (vs.rduTempC > -90) "${p.displayTemp(vs.rduTempC)}${p.tempLabel}" else "—"
                MonoLabel("RDU", 8.sp, Dim, letterSpacing = 0.1.sp)
                MonoText(rduStr, 12.sp, Mid)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WheelCell("FR", "${"%.1f".format(vs.wheelSpeedFR)}", front = true)
                WheelCell("RR", "${"%.1f".format(vs.wheelSpeedRR)}", front = false)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText("L ${vs.awdLeftTorque.roundToInt()} Nm", 10.sp, accent)
            MonoText("${vs.awdRightTorque.roundToInt()} Nm R", 10.sp, Ok)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(8.dp).background(Surf3, RoundedCornerShape(4.dp))) {
            Box(Modifier.weight(leftPctC).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(accent, accent.copy(0.4f))),
                    RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
            Box(Modifier.weight(rightPct).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Ok.copy(0.4f), Ok)),
                    RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("REAR BIAS", vs.rearLeftRightBias, modifier = Modifier.weight(1f))
            val spdLabel = if (p.speedUnit == "MPH") "mph" else "km/h"
            val lrDisp = if (p.speedUnit == "MPH") lrDelta * UnitConversions.KM_TO_MI else lrDelta
            val frDisp = if (p.speedUnit == "MPH") frDelta * UnitConversions.KM_TO_MI else frDelta
            DataCell("L/R DELTA", "${"%.1f".format(lrDisp)} $spdLabel", modifier = Modifier.weight(1f))
            DataCell("F/R DELTA", "${"%.1f".format(frDisp)} $spdLabel", modifier = Modifier.weight(1f))
        }
        if (vs.awdMaxTorque > 0) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataCell("AWD MAX", "${vs.awdMaxTorque.roundToInt()} Nm", modifier = Modifier.weight(1f))
                val ptuStr = if (vs.ptuTempC > -90) "${p.displayTemp(vs.ptuTempC)}${p.tempLabel}" else "—"
                DataCell("PTU TEMP", ptuStr, modifier = Modifier.weight(1f))
                DataCell("RDU TEMP",
                    if (vs.rduTempC > -90) "${p.displayTemp(vs.rduTempC)}${p.tempLabel}" else "—",
                    modifier = Modifier.weight(1f))
            }
        }
        val hasAwdExpanded = vs.awdClutchTempL > -90 || vs.awdReqTorqueL > 0 || vs.awdDmdPressure > 0
        if (hasAwdExpanded) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataCell("CLT L", if (vs.awdClutchTempL > -90) "${p.displayTemp(vs.awdClutchTempL)}${p.tempLabel}" else "—", modifier = Modifier.weight(1f))
                DataCell("CLT R", if (vs.awdClutchTempR > -90) "${p.displayTemp(vs.awdClutchTempR)}${p.tempLabel}" else "—", modifier = Modifier.weight(1f))
                DataCell("TRANS", if (vs.transOilTempC > -90) "${p.displayTemp(vs.transOilTempC)}${p.tempLabel}" else "—", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataCell("REQ L", "${vs.awdReqTorqueL.roundToInt()} Nm", modifier = Modifier.weight(1f))
                DataCell("REQ R", "${vs.awdReqTorqueR.roundToInt()} Nm", modifier = Modifier.weight(1f))
                DataCell("PUMP", "${"%.1f".format(vs.awdPumpCurrent)} A", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable fun GForceSection(vs: VehicleState, onReset: () -> Unit) {
    val accent = LocalThemeAccent.current
    val animLatG by animateFloatAsState(vs.lateralG.toFloat(), spring(stiffness = Spring.StiffnessHigh), label = "latG")
    val animLonG by animateFloatAsState(vs.longitudinalG.toFloat(), spring(stiffness = Spring.StiffnessHigh), label = "lonG")

    // G-force trail (sampled at ~10 Hz)
    val gTrail = remember { RingBuffer<Pair<Float, Float>>(30) }
    val lastTrailTime = remember { mutableLongStateOf(0L) }
    val now = vs.lastUpdate
    if (now - lastTrailTime.longValue >= 100L) {
        lastTrailTime.longValue = now
        gTrail.push(vs.lateralG.toFloat() to vs.longitudinalG.toFloat())
    }

    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel("G-FORCE & DYNAMICS")

        // G-Force dot plot
        val gPlotModifier = if (isWideLayout())
            Modifier.fillMaxWidth().aspectRatio(1f).heightIn(max = 280.dp).padding(8.dp)
        else
            Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)
        GForcePlot(
            lateralG = animLatG,
            longitudinalG = animLonG,
            trail = gTrail.toList(),
            modifier = gPlotModifier,
            dotColor = accent
        )

        Spacer(Modifier.height(8.dp))

        // Condensed numeric readout
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("LAT G",  "${"%.2f".format(animLatG)}",  modifier = Modifier.weight(1f))
            DataCell("LON G",  "${"%.2f".format(animLonG)}",  modifier = Modifier.weight(1f))
            DataCell("VERT G", "${"%.2f".format(vs.verticalG)}", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("YAW",      "${"%.1f".format(vs.yawRate)}°/s",    modifier = Modifier.weight(1f))
            DataCell("STEER",    "${"%.1f".format(vs.steeringAngle)}°", modifier = Modifier.weight(1f))
            DataCell("COMBINED", "${"%.2f".format(vs.combinedG)}",     modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(8.dp))
                .border(1.dp, Brd, RoundedCornerShape(8.dp))
                .clickable { onReset() }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            MonoLabel("↺ RESET PEAKS", 9.sp, Dim, letterSpacing = 0.15.sp)
        }
    }
}

@Composable fun TpmsSection(vs: VehicleState, p: UserPrefs) {
    val hasTempData = vs.hasTireTempData
    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel(if (hasTempData) "TPMS — PRESSURE & TEMPERATURE" else "TPMS — TIRE PRESSURE")

        if (!vs.hasTpmsData) {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusRsOutline()
                    Spacer(Modifier.height(12.dp))
                    MonoLabel("WAITING FOR SENSOR DATA", 10.sp, Dim, letterSpacing = 0.15.sp)
                    Spacer(Modifier.height(4.dp))
                    MonoLabel("Sensors transmit when wheels are rolling", 9.sp, Dim.copy(alpha = 0.7f))
                }
            }
        } else {
            val lowThreshold = p.tireLowPsi.toDouble()
            val deltaLF = tpmsDeltaText(vs.tirePressLF, vs.tireStartLF)
            val deltaRF = tpmsDeltaText(vs.tirePressRF, vs.tireStartRF)
            val deltaLR = tpmsDeltaText(vs.tirePressLR, vs.tireStartLR)
            val deltaRR = tpmsDeltaText(vs.tirePressRR, vs.tireStartRR)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TireCard("LF", vs.tirePressLF, p, lowThreshold, vs.tireTempLF, deltaLF)
                    TireCard("LR", vs.tirePressLR, p, lowThreshold, vs.tireTempLR, deltaLR)
                }
                CarDiagram(
                    vs = vs,
                    prefs = p,
                    modifier = Modifier.width(120.dp).aspectRatio(0.6f)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TireCard("RF", vs.tirePressRF, p, lowThreshold, vs.tireTempRF, deltaRF)
                    TireCard("RR", vs.tirePressRR, p, lowThreshold, vs.tireTempRR, deltaRR)
                }
            }
            if (hasTempData) {
                Spacer(Modifier.height(8.dp))
                TireTempLegend(p)
            }
            if (vs.anyTireLow(lowThreshold)) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(Orange.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .border(1.dp, Orange.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("⚠ LOW TIRE PRESSURE", 10.sp, Orange, letterSpacing = 0.2.sp)
                }
            }
            val spread = vs.maxTirePressSpread
            if (spread >= 4.0 && !vs.anyTireLow(lowThreshold)) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(Warn.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .border(1.dp, Warn.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("⚠ PRESSURE IMBALANCE — ${"%.1f".format(spread)} PSI spread", 10.sp, Warn, letterSpacing = 0.1.sp)
                }
            }
        }
    }
}

@Composable private fun TireTempLegend(p: UserPrefs) {
    val isF = p.tempUnit == "F"
    fun fmt(c: Int): String = if (isF) "${(c * 9 / 5) + 32}" else "$c"
    val deg = if (isF) "F" else "C"
    Row(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDot(Mid,    "<${fmt(15)}°$deg")
        LegendDot(Ok,     "${fmt(15)}-${fmt(27)}°$deg")
        LegendDot(Warn,   "${fmt(28)}-${fmt(40)}°$deg")
        LegendDot(Orange, ">${fmt(40)}°$deg")
    }
}

@Composable private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        MonoLabel(label, 7.sp, color)
    }
}

/** Formats a TPMS delta string with arrow prefix. Returns "" when delta is below threshold or data is missing. */
private fun tpmsDeltaText(currentPsi: Double, startPsi: Double): String {
    if (startPsi < 0 || currentPsi <= 0) return ""
    val delta = currentPsi - startPsi
    if (abs(delta) <= 0.5) return ""
    return if (delta > 0) "\u25B2 +${"%.1f".format(delta)}"
    else "\u25BC ${"%.1f".format(delta)}"
}
