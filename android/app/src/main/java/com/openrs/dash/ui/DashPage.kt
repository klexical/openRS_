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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.VehicleState
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// DASH PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun DashPage(vs: VehicleState, p: UserPrefs) {
    val accent = LocalThemeAccent.current
    val (boostVal, boostLbl) = p.displayBoost(vs.boostKpa)
    val brakeStr = "%.0f".format(vs.brakePressure.coerceIn(0.0, 100.0))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Hero Row: BOOST | RPM | SPEED ──────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroCard(
                unit = boostLbl, value = boostVal, label = "BOOST",
                valueColor = Warn,
                borderAccent = Warn.copy(alpha = 0.25f),
                modifier = Modifier.weight(1f)
            )
            HeroCard(
                unit = "RPM", value = "${vs.rpm.toInt()}", label = "ENGINE",
                valueColor = Red,
                borderAccent = Red.copy(alpha = 0.2f),
                modifier = Modifier.weight(1f)
            )
            HeroCard(
                unit = p.speedLabel, value = p.displaySpeed(vs.speedKph), label = "SPEED",
                valueColor = accent,
                borderAccent = accent.copy(alpha = 0.25f),
                modifier = Modifier.weight(1f)
            )
        }

        // ── Inputs & Resources bar grid ─────────────────────────────────────
        SectionLabel("INPUTS & RESOURCES")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarCard(
                name = "THROTTLE", value = "${vs.throttlePct.roundToInt()}%",
                fraction = (vs.throttlePct / 100.0).toFloat(),
                barBrush = Brush.horizontalGradient(listOf(accent.copy(0.4f), accent)),
                modifier = Modifier.weight(1f)
            )
            BarCard(
                name = "BRAKE", value = "$brakeStr%",
                fraction = (vs.brakePressure / 100.0).toFloat().coerceIn(0f, 1f),
                barBrush = Brush.horizontalGradient(listOf(Red.copy(0.4f), Red)),
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarCard(
                name = "FUEL", value = "${vs.fuelLevelPct.roundToInt()}%",
                fraction = (vs.fuelLevelPct / 100.0).toFloat(),
                barBrush = Brush.horizontalGradient(listOf(Ok.copy(0.4f), Ok)),
                modifier = Modifier.weight(1f)
            )
            BarCard(
                name = "BATTERY", value = "${"%.1f".format(vs.batteryVoltage)}V",
                fraction = ((vs.batteryVoltage - 10.0) / 6.0).toFloat().coerceIn(0f, 1f),
                barBrush = Brush.horizontalGradient(listOf(Warn.copy(0.4f), Warn)),
                modifier = Modifier.weight(1f)
            )
        }

        // ── AWD Split ──────────────────────────────────────────────────────
        SectionLabel("AWD SPLIT")
        AwdSplitBar(vs)

        // ── Temps Quick ────────────────────────────────────────────────────
        SectionLabel("TEMPS QUICK")
        val oilColor  = if (vs.oilTempC > -90) tempColorShade(vs.oilTempC, p.oilWarnC, p.oilCritC) else Mid
        val coolColor = if (vs.coolantTempC > -90) tempColorShade(vs.coolantTempC, p.coolWarnC, p.coolCritC) else Mid
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("OIL",
                if (vs.oilTempC > -90) "${p.displayTemp(vs.oilTempC)}${p.tempLabel}" else "—",
                valueColor = oilColor, modifier = Modifier.weight(1f))
            DataCell("COOLANT",
                if (vs.coolantTempC > -90) "${p.displayTemp(vs.coolantTempC)}${p.tempLabel}" else "—",
                valueColor = coolColor, modifier = Modifier.weight(1f))
            DataCell("INTAKE",   "${p.displayTemp(vs.intakeTempC)}${p.tempLabel}",  modifier = Modifier.weight(1f))
            DataCell("OIL LIFE", if (vs.oilLifePct >= 0) "${vs.oilLifePct.roundToInt()}%" else "—", modifier = Modifier.weight(1f))
        }

        // ── G-Force mini ──────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("LAT G",  "${"%.2f".format(vs.lateralG)}g",      modifier = Modifier.weight(1f))
            DataCell("LON G",  "${"%.2f".format(vs.longitudinalG)}g", modifier = Modifier.weight(1f))
            DataCell("TORQUE", "${vs.torqueAtTrans.roundToInt()} Nm",  modifier = Modifier.weight(1f))
        }

        // ── Odometer toggle ───────────────────────────────────────────────
        var odomInMiles by remember { mutableStateOf(false) }
        val odomLabel = if (odomInMiles) "ODO (mi)" else "ODO (km)"
        val odomValue = when {
            vs.odometerKm < 0 -> "—"
            odomInMiles       -> "${"%.0f".format(vs.odometerKm * 0.621371)} mi"
            else              -> "${"%.0f".format(vs.odometerKm.toDouble())} km"
        }
        Box(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(12.dp))
                .border(1.dp, Brd, RoundedCornerShape(12.dp))
                .clickable(enabled = vs.odometerKm >= 0) { odomInMiles = !odomInMiles }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoLabel(odomLabel, 9.sp, Dim, letterSpacing = 0.15.sp)
                MonoText(odomValue, 16.sp, if (vs.odometerKm >= 0) Frost else Dim)
            }
            if (vs.odometerKm >= 0) {
                MonoLabel("tap to toggle", 8.sp, Dim,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 74.dp))
            }
        }
    }
}

@Composable fun AwdSplitBar(vs: VehicleState) {
    val accent = LocalThemeAccent.current
    val rearPct  = vs.rearTorquePct.coerceIn(0.0, 100.0).toFloat()
    val frontPct = (100f - rearPct).coerceIn(0.01f, 99.99f)
    val rearF    = rearPct.coerceIn(0.01f, 99.99f)

    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(12.dp))
            .border(1.dp, Brd, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                MonoLabel("FRONT", 8.sp, Dim, letterSpacing = 0.12.sp)
                HeroNum("${(100 - rearPct).roundToInt()}%", 18.sp, accent)
            }
            Row(
                Modifier.weight(1f).padding(horizontal = 12.dp).height(10.dp)
                    .background(Surf3, RoundedCornerShape(5.dp))
            ) {
                Box(Modifier.weight(frontPct).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(accent, accent.copy(0.5f))),
                        RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp)))
                Box(Modifier.weight(rearF).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Ok.copy(0.5f), Ok)),
                        RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)))
            }
            Column(horizontalAlignment = Alignment.End) {
                MonoLabel("REAR", 8.sp, Dim, letterSpacing = 0.12.sp)
                HeroNum("${rearPct.roundToInt()}%", 18.sp, Ok)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText("L ${vs.awdLeftTorque.roundToInt()} Nm", 11.sp, accent)
            MonoLabel(vs.frontRearSplit, 10.sp, Mid)
            MonoText("${vs.awdRightTorque.roundToInt()} Nm R", 11.sp, Ok)
        }
    }
}

internal fun tempColorShade(c: Double, warnC: Double, critC: Double) = when {
    c <= 0      -> Dim
    c >= critC  -> Red
    c >= warnC  -> Warn
    c >= warnC * 0.6 -> Ok
    else        -> Frost
}
