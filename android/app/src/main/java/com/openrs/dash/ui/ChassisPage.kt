package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.VehicleState
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
            val lrDisp = if (p.speedUnit == "MPH") lrDelta * 0.621371 else lrDelta
            val frDisp = if (p.speedUnit == "MPH") frDelta * 0.621371 else frDelta
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
    }
}

@Composable fun GForceSection(vs: VehicleState, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel("G-FORCE & DYNAMICS")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GfCard("LAT G",    "${"%.2f".format(vs.lateralG)}",      "▲ ${"%.2f".format(vs.peakLateralG)}",     Modifier.weight(1f))
            GfCard("LON G",    "${"%.2f".format(vs.longitudinalG)}", "▲ ${"%.2f".format(vs.peakLongitudinalG)}", Modifier.weight(1f))
            GfCard("VERT G",   "${"%.2f".format(vs.verticalG)}",    "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GfCard("YAW",      "${"%.1f".format(vs.yawRate)}°/s",    "", Modifier.weight(1f))
            GfCard("STEER",    "${"%.1f".format(vs.steeringAngle)}°", "", Modifier.weight(1f))
            GfCard("COMBINED", "${"%.2f".format(vs.combinedG)}",     "", Modifier.weight(1f))
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
    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel("TPMS — TIRE PRESSURE")

        if (!vs.hasTpmsData) {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CarOutlinePlaceholder()
                    Spacer(Modifier.height(12.dp))
                    MonoLabel("WAITING FOR SENSOR DATA", 10.sp, Dim, letterSpacing = 0.15.sp)
                    Spacer(Modifier.height(4.dp))
                    MonoLabel("Sensors transmit when wheels are rolling", 9.sp, Dim.copy(alpha = 0.7f))
                }
            }
        } else {
            val lowThreshold = p.tireLowPsi.toDouble()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TireCard("LF", vs.tirePressLF, p, lowThreshold)
                    TireCard("LR", vs.tirePressLR, p, lowThreshold)
                }
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    CarOutlinePlaceholder(compact = true)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TireCard("RF", vs.tirePressRF, p, lowThreshold)
                    TireCard("RR", vs.tirePressRR, p, lowThreshold)
                }
            }
            if (vs.anyTireLow(lowThreshold)) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(Red.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .border(1.dp, Red.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("⚠ LOW TIRE PRESSURE", 10.sp, Red, letterSpacing = 0.2.sp)
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
