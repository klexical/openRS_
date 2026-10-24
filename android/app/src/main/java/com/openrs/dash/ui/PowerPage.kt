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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
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
            .padding(start = PagePad, end = PagePad, top = PagePad, bottom = pageBottomPadding()),
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
                    .instrumentSurface()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                InstrumentLabel("— CONNECT ADAPTER TO POPULATE —")
            }
        }
        // AFR / λ — one panel, readings in ink (was accent / green at rest)
        Row(
            Modifier.fillMaxWidth().then(pageEntrance(1, pageEntered))
                .instrumentSurface().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                Triple("AFR ACTUAL", if (hasAfr) "%.2f".format(vs.afrActual) else ph, ":1"),
                Triple("AFR DESIRED", if (hasAfr) "%.2f".format(vs.afrDesired) else ph, ":1"),
                Triple("LAMBDA", if (hasAfr) "%.3f".format(vs.lambdaActual) else ph, "λ"),
            ).forEachIndexed { i, (label, value, unit) ->
                if (i > 0) PanelDivider(Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    InstrumentLabel(label)
                    Row(verticalAlignment = Alignment.Bottom) {
                        DataNum(value, 24.sp, if (hasAfr) Frost else Dim)
                        if (hasAfr) { Spacer(Modifier.width(3.dp)); InstrumentMeta(unit, Modifier.padding(bottom = 3.dp)) }
                    }
                }
            }
        }

        SectionLabel("THROTTLE & BOOST", modifier = pageEntrance(2, pageEntered), collapsible = true, expanded = throttleEff, onToggle = { if (!offline) throttleExpanded = !throttleExpanded })
        AnimatedVisibility(visible = throttleEff, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))) {
            Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                val (tipActVal, tipLbl) = p.displayBoost(vs.tipActualKpa)
                val (tipDesVal, _)      = p.displayBoost(vs.tipDesiredKpa)
                // TIP is absolute kPa (~baro at rest); < 50 kPa can only be a bad decode
                val hasTipAct = polled("tipActualKpa") && vs.tipActualKpa > 50
                val hasTipDes = polled("tipDesiredKpa") && vs.tipDesiredKpa > 50
                val da = vs.densityAltitudeFt
                ReadoutGrid(listOf(
                    listOf(
                        Readout("ETC ACT", if (vs.etcAngleActual > 0) "${"%.1f".format(vs.etcAngleActual)}°" else ph),
                        Readout("ETC DES", if (vs.etcAngleDesired > 0) "${"%.1f".format(vs.etcAngleDesired)}°" else ph),
                        Readout("WGDC",    if (vs.wgdcDesired > 0) "${"%.0f".format(vs.wgdcDesired)}%" else ph),
                    ),
                    listOf(
                        Readout("TIP ACT", if (hasTipAct) "$tipActVal $tipLbl" else ph),
                        Readout("TIP DES", if (hasTipDes) "$tipDesVal $tipLbl" else ph),
                        null,
                    ),
                    listOf(
                        Readout("HP FUEL", if (vs.hpFuelRailPsi >= 0) "${"%.0f".format(vs.hpFuelRailPsi)} PSI" else ph),
                        Readout("DENS ALT", if (da != null) "${da} ft" else ph),
                        null,
                    ),
                ))
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
            // Retard is POSITIVE (parser raw / −512) — > 1° = knock, matching the header counter.
            // In range reads in ink; only a knocking cylinder turns amber.
            fun kr(cyl: Int, tag: String, v: Double) =
                Readout("CYL $cyl", if (eng(tag)) formatKr(v) else ph, if (eng(tag) && v > 1.0) Warn else Frost)
            val krLevel = if (listOf(vs.ignCorrCyl1, vs.ignCorrCyl2, vs.ignCorrCyl3, vs.ignCorrCyl4).any { it > 1.0 } && running) Level.WARN else Level.NORMAL
            ReadoutGrid(listOf(
                listOf(
                    Readout("TIMING", if (eng("timingAdvance")) "${"%.1f".format(vs.timingAdvance)}°" else ph),
                    Readout("LOAD",   if (eng("calcLoad")) "${"%.0f".format(vs.calcLoad)}%" else ph),
                    Readout("OAR",    if (eng("octaneAdjustRatio")) "${"%.0f".format(vs.octaneAdjustRatio * 100)}%" else ph),
                    Readout("SPARK",  if (eng("sparkAdvance")) "${"%.1f".format(vs.sparkAdvance)}°" else ph),
                ),
                listOf(
                    Readout("VCT INTAKE",  if (eng("vctIntakeAngle")) "${"%.1f".format(vs.vctIntakeAngle)}°" else ph),
                    Readout("VCT EXHAUST", if (eng("vctExhaustAngle")) "${"%.1f".format(vs.vctExhaustAngle)}°" else ph),
                    null, null,
                ),
                listOf(kr(1, "ignCorrCyl1", vs.ignCorrCyl1), kr(2, "ignCorrCyl2", vs.ignCorrCyl2),
                       kr(3, "ignCorrCyl3", vs.ignCorrCyl3), kr(4, "ignCorrCyl4", vs.ignCorrCyl4)),
            ), level = krLevel, captions = mapOf(2 to "KNOCK RETARD"))
        }

        SectionLabel("FUEL TRIMS & AFR", modifier = pageEntrance(4, pageEntered), collapsible = true, expanded = fuelEff, onToggle = { if (!offline) fuelExpanded = !fuelExpanded })
        AnimatedVisibility(visible = fuelEff, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))) {
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
            // Same availability semantics as AvailCell: value, value + "STALE Ns", or "— —".
            fun trim(label: String, st: com.openrs.dash.data.FieldState<Double>, color: Color, spark: List<Float>) = when (st) {
                is com.openrs.dash.data.FieldState.Available ->
                    Readout(label, "%.1f%%".format(st.value), color, spark = spark.takeIf { it.size >= 2 })
                is com.openrs.dash.data.FieldState.Stale ->
                    Readout(label, "%.1f%%".format(st.value), Dim, sub = "STALE ${st.ageSeconds}s", spark = spark.takeIf { it.size >= 2 })
                else -> Readout(label, ph)
            }
            val trimLevel = worstLevel(listOf(trimLevel(vs.shortFuelTrim, 10.0), trimLevel(vs.longFuelTrim, com.openrs.dash.data.FuelTrimDrift.ALERT_LTFT_PCT))
                .takeIf { stftState is com.openrs.dash.data.FieldState.Available || ltftState is com.openrs.dash.data.FieldState.Available } ?: emptyList())
            ReadoutGrid(listOf(
                listOf(
                    trim("SHORT FT", stftState, fuelTrimColor(vs.shortFuelTrim), stftSnap),
                    trim("LONG FT", ltftState, ltftColor(vs.longFuelTrim), ltftSnap),
                    Readout("BARO", "${vs.barometricPressure.roundToInt()} kPa"),
                ),
                listOf(
                    Readout("CMD AFR", if (polled("commandedAfr") && vs.commandedAfr > 0) "${"%.3f".format(vs.commandedAfr)}λ" else ph),
                    Readout("AFR SEN1", if (polled("afrSensor1") && vs.afrSensor1 > 0) "%.2f".format(vs.afrSensor1) else ph,
                        spark = afrSnap.takeIf { it.size >= 2 }),
                    Readout("O2 VOLT", if (eng("o2Voltage")) "${"%.3f".format(vs.o2Voltage)}V" else ph),
                ),
                listOf(
                    Readout("LP FUEL", if (polled("fuelRailPressure") && vs.fuelRailPsi > 0) "${"%.0f".format(vs.fuelRailPsi)} PSI" else ph),
                    Readout("OIL LIFE", if (vs.oilLifePct >= 0) "${vs.oilLifePct.roundToInt()}%" else ph),
                    null,
                ),
            ), level = trimLevel)
        }
    }
}

/** One reading in a [ReadoutGrid]: caption, value (with its unit), optional tag and trend line. */
private data class Readout(
    val label: String,
    val value: String,
    val color: Color = Color.Unspecified,
    val sub: String? = null,
    val spark: List<Float>? = null,
)

/**
 * A section's readings as one instrument panel (was one card per value). Rows of equal-width
 * columns; null = empty slot. [captions] inserts a sub-heading before the given row index.
 */
@Composable private fun ReadoutGrid(
    rows: List<List<Readout?>>, level: Level = Level.NORMAL, captions: Map<Int, String> = emptyMap(),
) {
    Column(
        Modifier.fillMaxWidth().instrumentSurface(level).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEachIndexed { i, row ->
            captions[i]?.let { InstrumentLabel(it, color = Mid) }
            Row(Modifier.fillMaxWidth()) {
                row.forEach { r ->
                    Column(Modifier.weight(1f)) {
                        if (r != null) {
                            val placeholder = r.value.startsWith("—")
                            InstrumentLabel(r.label)
                            Spacer(Modifier.height(2.dp))
                            DataNum(r.value, 18.sp,
                                if (placeholder) Dim else if (r.color == Color.Unspecified) Frost else r.color)
                            r.sub?.let { MonoLabel(it, 9.sp, WarnInk, letterSpacing = 0.2.sp) }
                            if (r.spark != null && !placeholder) {
                                Spacer(Modifier.height(3.dp))
                                com.openrs.dash.ui.anim.Sparkline(
                                    data = r.spark,
                                    lineColor = if (r.color == Color.Unspecified || r.color == Frost) accentMid() else r.color,
                                    modifier = Modifier.fillMaxWidth().padding(end = 10.dp).height(16.dp),
                                    strokeWidth = 1.dp, fillAlpha = 0.12f
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Trim level for the panel edge: past ±[limit] warns (same bands as the value colour). */
private fun trimLevel(trim: Double, limit: Double) = if (kotlin.math.abs(trim) > limit) Level.WARN else Level.NORMAL

/** ±10 % trim colour bands (unchanged); in range reads in ink instead of green. */
private fun fuelTrimColor(trim: Double) = when {
    trim > 10.0  -> Warn
    trim < -10.0 -> Orange
    else         -> Frost
}

/** LTFT uses the drift alert threshold (±[FuelTrimDrift.ALERT_LTFT_PCT]) — same direction colours. */
private fun ltftColor(trim: Double) = when {
    trim > com.openrs.dash.data.FuelTrimDrift.ALERT_LTFT_PCT  -> Warn
    trim < -com.openrs.dash.data.FuelTrimDrift.ALERT_LTFT_PCT -> Orange
    else                                                      -> Frost
}

/** KR in degrees; values that round to zero print as "0.00°" (no "-0.00°" from the /−512 scale). */
internal fun formatKr(deg: Double): String =
    if (kotlin.math.abs(deg) < 0.005) "0.00°" else "${"%.2f".format(deg)}°"
