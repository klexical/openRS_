package com.openrs.dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openrs.dash.data.VehicleState
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// POWER PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun PowerPage(vs: VehicleState, p: UserPrefs) {
    val accent = LocalThemeAccent.current
    val hasAfr = vs.afrActual > 0
    val ph = "— —"

    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfrCard("AFR ACT",  if (hasAfr) "%.2f".format(vs.afrActual)    else ph, ":1",
                if (hasAfr) accent else Dim, Modifier.weight(1f))
            AfrCard("AFR DES",  if (hasAfr) "%.2f".format(vs.afrDesired)   else ph, ":1",
                if (hasAfr) Frost else Dim,  Modifier.weight(1f))
            AfrCard("LAMBDA",   if (hasAfr) "%.3f".format(vs.lambdaActual) else ph, "λ",
                if (hasAfr) Ok else Dim,     Modifier.weight(1f))
        }

        SectionLabel("THROTTLE & BOOST")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("ETC ACT", if (vs.etcAngleActual > 0) "${"%.1f".format(vs.etcAngleActual)}°" else ph, modifier = Modifier.weight(1f))
            DataCell("ETC DES", if (vs.etcAngleDesired > 0) "${"%.1f".format(vs.etcAngleDesired)}°" else ph, modifier = Modifier.weight(1f))
            DataCell("WGDC",    if (vs.wgdcDesired > 0) "${"%.0f".format(vs.wgdcDesired)}%" else ph, valueColor = accent, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (tipActVal, tipLbl) = p.displayBoost(vs.tipActualKpa)
            val (tipDesVal, _)      = p.displayBoost(vs.tipDesiredKpa)
            val hasTip = vs.tipActualKpa > 50
            DataCell("TIP ACT", if (hasTip) "$tipActVal $tipLbl" else ph, modifier = Modifier.weight(1f))
            DataCell("TIP DES", if (hasTip) "$tipDesVal $tipLbl" else ph, modifier = Modifier.weight(1f))
            DataCell("HP FUEL", if (vs.hpFuelRailPsi >= 0) "${"%.0f".format(vs.hpFuelRailPsi)} PSI" else ph, modifier = Modifier.weight(1f))
        }

        SectionLabel("ENGINE MANAGEMENT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("TIMING", if (vs.timingAdvance != 0.0) "${"%.1f".format(vs.timingAdvance)}°" else ph, modifier = Modifier.weight(1f))
            DataCell("LOAD",   if (vs.calcLoad > 0) "${"%.0f".format(vs.calcLoad)}%" else ph,              modifier = Modifier.weight(1f))
            DataCell("OAR",    if (vs.octaneAdjustRatio != 0.0) "${"%.0f".format(vs.octaneAdjustRatio * 100)}%" else ph, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val krColor = if (vs.ignCorrCyl1 < -1.0) Warn else Ok
            DataCell("KR CYL1", if (vs.ignCorrCyl1 != 0.0) "${"%.2f".format(vs.ignCorrCyl1)}°" else ph, valueColor = krColor, modifier = Modifier.weight(1f))
            DataCell("VCT-I",   if (vs.vctIntakeAngle != 0.0) "${"%.1f".format(vs.vctIntakeAngle)}°" else ph, modifier = Modifier.weight(1f))
            DataCell("VCT-E",   if (vs.vctExhaustAngle != 0.0) "${"%.1f".format(vs.vctExhaustAngle)}°" else ph, modifier = Modifier.weight(1f))
        }

        SectionLabel("FUEL TRIMS & AFR")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val stftColor = fuelTrimColor(vs.shortFuelTrim)
            val ltftColor = fuelTrimColor(vs.longFuelTrim)
            DataCell("SHORT FT", if (vs.shortFuelTrim != 0.0) "${"%.1f".format(vs.shortFuelTrim)}%" else ph, valueColor = stftColor, modifier = Modifier.weight(1f))
            DataCell("LONG FT",  if (vs.longFuelTrim != 0.0) "${"%.1f".format(vs.longFuelTrim)}%" else ph,  valueColor = ltftColor, modifier = Modifier.weight(1f))
            DataCell("BARO",     "${vs.barometricPressure.roundToInt()} kPa", modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("OIL LIFE", if (vs.oilLifePct >= 0) "${vs.oilLifePct.roundToInt()}%" else ph, modifier = Modifier.weight(1f))
            DataCell("AFR SEN1", if (vs.afrSensor1 > 0) "${"%.2f".format(vs.afrSensor1)}" else ph, modifier = Modifier.weight(1f))
            DataCell("O2 VOLT",  if (vs.o2Voltage > 0) "${"%.3f".format(vs.o2Voltage)}V" else ph,  modifier = Modifier.weight(1f))
        }
    }
}

private fun fuelTrimColor(trim: Double) = when {
    trim > 10.0  -> Warn
    trim < -10.0 -> Red
    else         -> Ok
}
