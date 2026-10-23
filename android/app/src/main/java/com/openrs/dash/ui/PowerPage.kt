package com.openrs.dash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.can.ObdConstants
import com.openrs.dash.ui.Tokens.PagePad
import com.openrs.dash.ui.Tokens.CardGap
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.anim.SparklineData
import com.openrs.dash.ui.anim.StaggeredColumn
import com.openrs.dash.ui.anim.pageEntrance
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// POWER PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun PowerPage(vs: VehicleState, p: UserPrefs) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = PagePad, end = PagePad, top = PagePad, bottom = PagePad + Tokens.NavBarHeight),
        verticalArrangement = Arrangement.spacedBy(CardGap)
    ) {
        PowerPageContent(vs, p)
    }
}

/**
 * Inner content for POWER — usable inside any scrolling parent (e.g. [PerfPage]).
 */
@Composable fun PowerPageContent(vs: VehicleState, p: UserPrefs) {
    val accent = LocalThemeAccent.current
    val hasAfr = vs.afrActual > 0
    val ph = "— —"
    // Per-field gating (#198): a cell shows once its DID has actually been decoded this
    // session; engine-dependent cells also need the engine running. NRC-suppressed DIDs
    // (e.g. KR cyl 2/3 on some PCMs) never stamp, so they stay "— —" instead of 0.00.
    val running = vs.rpm > ObdConstants.FAST_POLL_MIN_RPM
    fun polled(tag: String) = vs.fieldLastUpdateMs[tag] != null
    fun eng(tag: String) = running && polled(tag)

    var throttleExpanded by rememberSectionExpanded("POWER_THROTTLE")
    var engineExpanded   by rememberSectionExpanded("POWER_ENGINE")
    var fuelExpanded     by rememberSectionExpanded("POWER_FUEL")

    var pageEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pageEntered = true }

    // V1: sparkline ring buffers for fuel trims + AFR (sampled at ~4 Hz)
    val session   = telemetrySessionKey()   // demo start/reset/exit drops simulated history
    val stftSpark = remember(session) { SparklineData(60) }
    val ltftSpark = remember(session) { SparklineData(60) }
    val afrSpark  = remember(session) { SparklineData(60) }
    val lastSparkSample = remember(session) { mutableLongStateOf(0L) }
    SideEffect {
        val now = vs.lastUpdate
        if (now - lastSparkSample.longValue >= 250L) {
            lastSparkSample.longValue = now
            stftSpark.push(vs.shortFuelTrim.toFloat())
            ltftSpark.push(vs.longFuelTrim.toFloat())
            if (vs.afrActual > 0) afrSpark.push(vs.afrActual.toFloat())
        }
    }
    val sparkKey = lastSparkSample.longValue
    val stftSnap = remember(sparkKey) { stftSpark.snapshot() }
    val ltftSnap = remember(sparkKey) { ltftSpark.snapshot() }
    val afrSnap  = remember(sparkKey) { afrSpark.snapshot() }

    // When offline, every DataCell in these sections resolves to "— —" which
    // turns the PERF tab into a wall of fog. Force sections closed (without
    // overwriting the user's remembered preference) and show a compact
    // offline hint so the page has a clear anchor.
    val offline = !vs.isConnected
    val throttleEff = throttleExpanded && !offline
    val engineEff   = engineExpanded && !offline
    val fuelEff     = fuelExpanded && !offline

    Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
        if (offline) {
            Box(
                Modifier.fillMaxWidth()
                    .then(pageEntrance(0, pageEntered))
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                MonoLabel(
                    "\u2014 CONNECT ADAPTER TO POPULATE \u2014",
                    9.sp, Dim, letterSpacing = 0.2.sp
                )
            }
        }
        Row(Modifier.fillMaxWidth().then(pageEntrance(1, pageEntered)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfrCard("AFR ACT",  if (hasAfr) "%.2f".format(vs.afrActual)    else ph, ":1",
                if (hasAfr) accent else Dim, Modifier.weight(1f))
            AfrCard("AFR DES",  if (hasAfr) "%.2f".format(vs.afrDesired)   else ph, ":1",
                if (hasAfr) Frost else Dim,  Modifier.weight(1f))
            AfrCard("LAMBDA",   if (hasAfr) "%.3f".format(vs.lambdaActual) else ph, "λ",
                if (hasAfr) Ok else Dim,     Modifier.weight(1f))
        }

        SectionLabel("THROTTLE & BOOST", modifier = pageEntrance(2, pageEntered), collapsible = true, expanded = throttleEff, onToggle = { if (!offline) throttleExpanded = !throttleExpanded })
        AnimatedVisibility(visible = throttleEff, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))) {
            Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("ETC ACT", if (vs.etcAngleActual > 0) "${"%.1f".format(vs.etcAngleActual)}°" else ph, modifier = Modifier.weight(1f))
                    DataCell("ETC DES", if (vs.etcAngleDesired > 0) "${"%.1f".format(vs.etcAngleDesired)}°" else ph, modifier = Modifier.weight(1f))
                    DataCell("WGDC",    if (vs.wgdcDesired > 0) "${"%.0f".format(vs.wgdcDesired)}%" else ph, valueColor = accent, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (tipActVal, tipLbl) = p.displayBoost(vs.tipActualKpa)
                    val (tipDesVal, _)      = p.displayBoost(vs.tipDesiredKpa)
                    // TIP is absolute kPa (~baro at rest); < 50 kPa can only be a bad decode
                    val hasTipAct = polled("tipActualKpa") && vs.tipActualKpa > 50
                    val hasTipDes = polled("tipDesiredKpa") && vs.tipDesiredKpa > 50
                    DataCell("TIP ACT", if (hasTipAct) "$tipActVal $tipLbl" else ph, modifier = Modifier.weight(1f))
                    DataCell("TIP DES", if (hasTipDes) "$tipDesVal $tipLbl" else ph, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("HP FUEL", if (vs.hpFuelRailPsi >= 0) "${"%.0f".format(vs.hpFuelRailPsi)} PSI" else ph, modifier = Modifier.weight(1f))
                    val da = vs.densityAltitudeFt
                    DataCell("DENS ALT", if (da != null) "${da} ft" else ph, modifier = Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
                // Live boost sweep (#175) — target vs actual + WGDC across RPM, last 90 s
                if (running) {
                    val sweep by com.openrs.dash.data.BoostSessionLog.samples.collectAsState()
                    BoostSweepChart(sweep, p, accent)
                }
            }
        }

        val knockCount by OpenRSDashApp.instance.knockEventCount.collectAsState()
        SectionLabel(
            "ENGINE MANAGEMENT",
            modifier = pageEntrance(3, pageEntered),
            collapsible = true,
            expanded = engineEff,
            onToggle = { if (!offline) engineExpanded = !engineExpanded },
            badge = if (knockCount > 0) "$knockCount KNOCK" else null,
            badgeColor = if (knockCount > 0) Warn else null
        )
        AnimatedVisibility(visible = engineEff, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))) {
            Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("TIMING", if (eng("timingAdvance")) "${"%.1f".format(vs.timingAdvance)}°" else ph, modifier = Modifier.weight(1f))
                    DataCell("LOAD",   if (eng("calcLoad")) "${"%.0f".format(vs.calcLoad)}%" else ph,              modifier = Modifier.weight(1f))
                    DataCell("OAR",    if (eng("octaneAdjustRatio")) "${"%.0f".format(vs.octaneAdjustRatio * 100)}%" else ph, modifier = Modifier.weight(1f))
                    DataCell("SPARK",  if (eng("sparkAdvance")) "${"%.1f".format(vs.sparkAdvance)}°" else ph, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("VCT-I",   if (eng("vctIntakeAngle")) "${"%.1f".format(vs.vctIntakeAngle)}°" else ph, modifier = Modifier.weight(1f))
                    DataCell("VCT-E",   if (eng("vctExhaustAngle")) "${"%.1f".format(vs.vctExhaustAngle)}°" else ph, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Retard is POSITIVE (parser raw / −512) — > 1° = knock, matching the header counter
                    fun krColor(v: Double) = if (v > 1.0) Warn else Ok

                    // Animated flash backgrounds for knock events
                    val krBg1 by animateColorAsState(if (vs.ignCorrCyl1 > 1.0) Warn.copy(alpha = 0.08f) else Color.Transparent, tween(400), label = "kr1")
                    val krBg2 by animateColorAsState(if (vs.ignCorrCyl2 > 1.0) Warn.copy(alpha = 0.08f) else Color.Transparent, tween(400), label = "kr2")
                    val krBg3 by animateColorAsState(if (vs.ignCorrCyl3 > 1.0) Warn.copy(alpha = 0.08f) else Color.Transparent, tween(400), label = "kr3")
                    val krBg4 by animateColorAsState(if (vs.ignCorrCyl4 > 1.0) Warn.copy(alpha = 0.08f) else Color.Transparent, tween(400), label = "kr4")

                    DataCell("KR C1", if (eng("ignCorrCyl1")) formatKr(vs.ignCorrCyl1) else ph, valueColor = krColor(vs.ignCorrCyl1), modifier = Modifier.weight(1f).background(krBg1, RoundedCornerShape(10.dp)))
                    DataCell("KR C2", if (eng("ignCorrCyl2")) formatKr(vs.ignCorrCyl2) else ph, valueColor = krColor(vs.ignCorrCyl2), modifier = Modifier.weight(1f).background(krBg2, RoundedCornerShape(10.dp)))
                    DataCell("KR C3", if (eng("ignCorrCyl3")) formatKr(vs.ignCorrCyl3) else ph, valueColor = krColor(vs.ignCorrCyl3), modifier = Modifier.weight(1f).background(krBg3, RoundedCornerShape(10.dp)))
                    DataCell("KR C4", if (eng("ignCorrCyl4")) formatKr(vs.ignCorrCyl4) else ph, valueColor = krColor(vs.ignCorrCyl4), modifier = Modifier.weight(1f).background(krBg4, RoundedCornerShape(10.dp)))
                }
            }
        }

        SectionLabel("FUEL TRIMS & AFR", modifier = pageEntrance(4, pageEntered), collapsible = true, expanded = fuelEff, onToggle = { if (!offline) fuelExpanded = !fuelExpanded })
        AnimatedVisibility(visible = fuelEff, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))) {
            Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val stftState = com.openrs.dash.data.fieldState(
                        value = vs.shortFuelTrim,
                        lastUpdateMs = vs.fieldLastUpdateMs["shortFuelTrim"],
                        pollIntervalMs = com.openrs.dash.can.ObdConstants.PCM_SLOW_REFRESH_MS
                    )
                    val ltftState = com.openrs.dash.data.fieldState(
                        value = vs.longFuelTrim,
                        lastUpdateMs = vs.fieldLastUpdateMs["longFuelTrim"],
                        pollIntervalMs = com.openrs.dash.can.ObdConstants.PCM_SLOW_REFRESH_MS
                    )
                    AvailCell(
                        label = "SHORT FT", state = stftState,
                        format = { "%.1f%%".format(it) },
                        valueColor = fuelTrimColor(vs.shortFuelTrim),
                        modifier = Modifier.weight(1f)
                    )
                    AvailCell(
                        label = "LONG FT", state = ltftState,
                        format = { "%.1f%%".format(it) },
                        valueColor = fuelTrimColor(vs.longFuelTrim),
                        modifier = Modifier.weight(1f)
                    )
                    DataCell("BARO", "${vs.barometricPressure.roundToInt()} kPa", modifier = Modifier.weight(1f))
                }
                // V1: fuel trim sparklines — STFT/LTFT trend strips
                if (stftSnap.size >= 2 || ltftSnap.size >= 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (stftSnap.size >= 2) {
                            com.openrs.dash.ui.anim.Sparkline(
                                data = stftSnap,
                                lineColor = fuelTrimColor(vs.shortFuelTrim),
                                modifier = Modifier.weight(1f).height(20.dp),
                                strokeWidth = 1.dp, fillAlpha = 0.12f
                            )
                        } else Spacer(Modifier.weight(1f))
                        if (ltftSnap.size >= 2) {
                            com.openrs.dash.ui.anim.Sparkline(
                                data = ltftSnap,
                                lineColor = fuelTrimColor(vs.longFuelTrim),
                                modifier = Modifier.weight(1f).height(20.dp),
                                strokeWidth = 1.dp, fillAlpha = 0.12f
                            )
                        } else Spacer(Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("CMD AFR", if (polled("commandedAfr") && vs.commandedAfr > 0) "${"%.3f".format(vs.commandedAfr)}λ" else ph, modifier = Modifier.weight(1f))
                    DataCell("AFR SEN1", if (polled("afrSensor1") && vs.afrSensor1 > 0) "${"%.2f".format(vs.afrSensor1)}" else ph, modifier = Modifier.weight(1f),
                        sparklineData = afrSnap.takeIf { it.size >= 2 })
                    DataCell("O2 VOLT",  if (eng("o2Voltage")) "${"%.3f".format(vs.o2Voltage)}V" else ph,  modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("LP FUEL", if (polled("fuelRailPressure") && vs.fuelRailPsi > 0) "${"%.0f".format(vs.fuelRailPsi)} PSI" else ph, modifier = Modifier.weight(1f))
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

/** KR in degrees; values that round to zero print as "0.00°" (no "-0.00°" from the /−512 scale). */
internal fun formatKr(deg: Double): String =
    if (kotlin.math.abs(deg) < 0.005) "0.00°" else "${"%.2f".format(deg)}°"
