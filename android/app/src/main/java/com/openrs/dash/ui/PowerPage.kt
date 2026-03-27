package com.openrs.dash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import com.openrs.dash.data.VehicleState
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// POWER PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun PowerPage(vs: VehicleState, p: UserPrefs) {
    val accent = LocalThemeAccent.current
    val hasAfr = vs.afrActual > 0
    val ph = "— —"

    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    fun isExpanded(key: String) = expandedSections.getOrDefault(key, true)

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

        SectionLabel("THROTTLE & BOOST", collapsible = true, expanded = isExpanded("THROTTLE"), onToggle = { expandedSections["THROTTLE"] = !isExpanded("THROTTLE") })
        AnimatedVisibility(visible = isExpanded("THROTTLE"), enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
        }

        SectionLabel("ENGINE MANAGEMENT", collapsible = true, expanded = isExpanded("ENGINE"), onToggle = { expandedSections["ENGINE"] = !isExpanded("ENGINE") })
        AnimatedVisibility(visible = isExpanded("ENGINE"), enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("TIMING", if (vs.calcLoad > 0) "${"%.1f".format(vs.timingAdvance)}°" else ph, modifier = Modifier.weight(1f))
                    DataCell("LOAD",   if (vs.calcLoad > 0) "${"%.0f".format(vs.calcLoad)}%" else ph,              modifier = Modifier.weight(1f))
                    DataCell("OAR",    if (vs.calcLoad > 0) "${"%.0f".format(vs.octaneAdjustRatio * 100)}%" else ph, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("VCT-I",   if (vs.calcLoad > 0) "${"%.1f".format(vs.vctIntakeAngle)}°" else ph, modifier = Modifier.weight(1f))
                    DataCell("VCT-E",   if (vs.calcLoad > 0) "${"%.1f".format(vs.vctExhaustAngle)}°" else ph, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun krColor(v: Double) = if (v < -1.0) Warn else Ok
                    val hasData = vs.calcLoad > 0

                    // Animated flash backgrounds for knock events
                    val krBg1 by animateColorAsState(if (vs.ignCorrCyl1 < -1.0) Warn.copy(alpha = 0.08f) else Color.Transparent, tween(400), label = "kr1")
                    val krBg2 by animateColorAsState(if (vs.ignCorrCyl2 < -1.0) Warn.copy(alpha = 0.08f) else Color.Transparent, tween(400), label = "kr2")
                    val krBg3 by animateColorAsState(if (vs.ignCorrCyl3 < -1.0) Warn.copy(alpha = 0.08f) else Color.Transparent, tween(400), label = "kr3")
                    val krBg4 by animateColorAsState(if (vs.ignCorrCyl4 < -1.0) Warn.copy(alpha = 0.08f) else Color.Transparent, tween(400), label = "kr4")

                    DataCell("KR C1", if (hasData) "${"%.2f".format(vs.ignCorrCyl1)}°" else ph, valueColor = krColor(vs.ignCorrCyl1), modifier = Modifier.weight(1f).background(krBg1, RoundedCornerShape(10.dp)))
                    DataCell("KR C2", if (hasData) "${"%.2f".format(vs.ignCorrCyl2)}°" else ph, valueColor = krColor(vs.ignCorrCyl2), modifier = Modifier.weight(1f).background(krBg2, RoundedCornerShape(10.dp)))
                    DataCell("KR C3", if (hasData) "${"%.2f".format(vs.ignCorrCyl3)}°" else ph, valueColor = krColor(vs.ignCorrCyl3), modifier = Modifier.weight(1f).background(krBg3, RoundedCornerShape(10.dp)))
                    DataCell("KR C4", if (hasData) "${"%.2f".format(vs.ignCorrCyl4)}°" else ph, valueColor = krColor(vs.ignCorrCyl4), modifier = Modifier.weight(1f).background(krBg4, RoundedCornerShape(10.dp)))
                }
            }
        }

        SectionLabel("FUEL TRIMS & AFR", collapsible = true, expanded = isExpanded("FUEL"), onToggle = { expandedSections["FUEL"] = !isExpanded("FUEL") })
        AnimatedVisibility(visible = isExpanded("FUEL"), enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val stftColor = fuelTrimColor(vs.shortFuelTrim)
                    val ltftColor = fuelTrimColor(vs.longFuelTrim)
                    DataCell("SHORT FT", if (vs.calcLoad > 0) "${"%.1f".format(vs.shortFuelTrim)}%" else ph, valueColor = stftColor, modifier = Modifier.weight(1f))
                    DataCell("LONG FT",  if (vs.calcLoad > 0) "${"%.1f".format(vs.longFuelTrim)}%" else ph,  valueColor = ltftColor, modifier = Modifier.weight(1f))
                    DataCell("BARO",     "${vs.barometricPressure.roundToInt()} kPa", modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("CMD AFR", if (vs.commandedAfr > 0) "${"%.3f".format(vs.commandedAfr)}λ" else ph, modifier = Modifier.weight(1f))
                    DataCell("AFR SEN1", if (vs.afrSensor1 > 0) "${"%.2f".format(vs.afrSensor1)}" else ph, modifier = Modifier.weight(1f))
                    DataCell("O2 VOLT",  if (vs.o2Voltage > 0) "${"%.3f".format(vs.o2Voltage)}V" else ph,  modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("LP FUEL", if (vs.fuelRailPsi > 0) "${"%.0f".format(vs.fuelRailPsi)} PSI" else ph, modifier = Modifier.weight(1f))
                    DataCell("OIL LIFE", if (vs.oilLifePct >= 0) "${vs.oilLifePct.roundToInt()}%" else ph, modifier = Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun fuelTrimColor(trim: Double) = when {
    trim > 10.0  -> Warn
    trim < -10.0 -> Orange
    else         -> Ok
}
