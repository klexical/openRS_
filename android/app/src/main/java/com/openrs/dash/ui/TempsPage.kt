package com.openrs.dash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.Tokens.PagePad
import com.openrs.dash.ui.Tokens.CardGap
import com.openrs.dash.data.ThermalPredictor
import com.openrs.dash.data.ThermalPredictor.ClimbTrend
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.anim.SparklineData
import com.openrs.dash.ui.anim.StaggeredColumn

// ═══════════════════════════════════════════════════════════════════════════
// TEMPS PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun TempsPage(vs: VehicleState, p: UserPrefs) {
    // V1: sparkline ring buffers for oil temp + coolant (sampled at ~1 Hz —
    // temps change slowly, higher rate just duplicates values)
    val session = telemetrySessionKey()   // demo start/reset/exit drops simulated history
    val oilSpark = remember(session) { SparklineData(60) }
    val coolSpark = remember(session) { SparklineData(60) }
    val predictor = remember(session) { ThermalPredictor() }
    val lastTempSample = remember(session) { mutableLongStateOf(0L) }
    SideEffect {
        val now = vs.lastUpdate
        if (now - lastTempSample.longValue >= 1000L) {
            lastTempSample.longValue = now
            if (vs.oilTempC > -90) oilSpark.push(vs.oilTempC.toFloat())
            if (vs.coolantTempC > -90) coolSpark.push(vs.coolantTempC.toFloat())
            // Feed thermal predictor
            val ms = System.currentTimeMillis()
            predictor.recordSample("OIL", ms, vs.oilTempC)
            predictor.recordSample("COOLANT", ms, vs.coolantTempC)
            predictor.recordSample("RDU", ms, vs.rduTempC)
            predictor.recordSample("PTU", ms, vs.ptuTempC)
        }
    }
    val sparkKey = lastTempSample.longValue
    val oilSnap = remember(sparkKey) { oilSpark.snapshot() }
    val coolSnap = remember(sparkKey) { coolSpark.snapshot() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = PagePad, end = PagePad, top = PagePad, bottom = PagePad + Tokens.NavBarHeight),
        verticalArrangement = Arrangement.spacedBy(CardGap)
    ) {
        RtrBanner(vs, p)
        TempPresetBadge(p)

        // ── Predictive thermal alerts ───────────────────────────────────
        if (vs.isConnected) {
            val alertSensors = listOf(
                Triple("OIL", p.oilCritC, 80.0),
                Triple("COOLANT", p.coolCritC, 85.0),
                Triple("RDU", p.rduCritC, 40.0),
                Triple("PTU", p.ptuCritC, 50.0),
            )
            val activeAlerts = alertSensors.mapNotNull { (label, crit, floor) ->
                if (predictor.shouldAlert(label, crit, floor))
                    predictor.predict(label, crit, floor)
                else null
            }
            activeAlerts.forEach { pred ->
                ThermalAlertBanner(pred, p) { predictor.dismiss(pred.sensorLabel) }
            }
        }

        // ── Tires + AWD (migrated from CHASSIS) ─────────────────────────
        UnifiedChassisSection(vs, p)

        fun peakStr(peakC: Double) = if (peakC > -90) "\u25B2 ${p.displayTemp(peakC)}${p.tempLabel}" else ""
        fun avail(valueC: Double, tag: String) =
            tempAvailFor(valueC, vs.fieldLastUpdateMs[tag]).whenDisconnected(vs.isConnected)
        // Suppress WARMING on offline — the whole bus is silent, shouting
        // "WARMING" on ten cells at once is noise, not information. Stale/
        // unavailable are retained since those describe real decode history.
        fun subFor(a: TempAvail, fallback: String) = when (a) {
            TempAvail.WARMING     -> if (vs.isConnected) "WARMING" else ""
            TempAvail.STALE       -> "STALE"
            TempAvail.UNAVAILABLE -> "N/A"
            TempAvail.AVAILABLE   -> fallback
        }
        val oilA     = avail(vs.oilTempC,            "oilTempC")
        val coolA    = avail(vs.coolantTempC,        "coolantTempC")
        val intakeA  = avail(vs.intakeTempC,         "intakeTempC")
        val ptuA     = avail(vs.ptuTempC,            "ptuTempC")
        val rduA     = avail(vs.rduTempC,            "rduTempC")
        val ambA     = avail(vs.ambientTempC,        "ambientTempC")
        val chargeA  = avail(vs.chargeAirTempC,      "chargeAirTempC")
        val manifA   = avail(vs.manifoldChargeTempC, "manifoldChargeTempC")
        val catA     = avail(vs.catalyticTempC,      "catalyticTempC")
        val cabinA   = avail(vs.cabinTempC,          "cabinTempC")
        val battA    = avail(vs.batteryTempC,        "batteryTempC")
        val clutchLA = avail(vs.awdClutchTempL,      "awdClutchTempL")
        val clutchRA = avail(vs.awdClutchTempR,      "awdClutchTempR")
        val transA   = avail(vs.transOilTempC,       "transOilTempC")
        val oilTrend     = predictor.trend("OIL").takeIf { oilA == TempAvail.AVAILABLE }
        val coolTrend    = predictor.trend("COOLANT").takeIf { coolA == TempAvail.AVAILABLE }
        val ptuTrend     = predictor.trend("PTU").takeIf { ptuA == TempAvail.AVAILABLE }
        val rduTrend     = predictor.trend("RDU").takeIf { rduA == TempAvail.AVAILABLE }
        val powertrainItems = listOf(
            TempSpec("ENGINE OIL",
                if (vs.oilTempC > -90) p.displayTemp(vs.oilTempC) else "— —", p.tempLabel,
                vs.oilTempC.takeIf { it > -90 } ?: 0.0,
                p.oilWarnC, p.oilCritC, subFor(oilA, "INFERRED"),
                peakStr(vs.peakOilTempC), oilA,
                sparklineData = oilSnap.takeIf { it.size >= 2 },
                climbTrend = oilTrend),
            TempSpec("COOLANT",
                if (vs.coolantTempC > -90) p.displayTemp(vs.coolantTempC) else "— —", p.tempLabel,
                vs.coolantTempC.takeIf { it > -90 } ?: 0.0,
                p.coolWarnC, p.coolCritC, subFor(coolA, ""),
                peakStr(vs.peakCoolantTempC), coolA,
                sparklineData = coolSnap.takeIf { it.size >= 2 },
                climbTrend = coolTrend),
            TempSpec("INTAKE AIR",
                if (vs.intakeTempC > -90) p.displayTemp(vs.intakeTempC) else "— —", p.tempLabel,
                vs.intakeTempC.takeIf { it > -90 } ?: 0.0,
                p.intakeWarnC, p.intakeCritC, subFor(intakeA, ""),
                avail = intakeA),
            TempSpec("PTU (TRANSFER)",
                if (vs.ptuTempC > -90) p.displayTemp(vs.ptuTempC) else "— —", p.tempLabel,
                vs.ptuTempC.takeIf { it > -90 } ?: 0.0,
                p.ptuWarnC, p.ptuCritC, subFor(ptuA, ""),
                peakStr(vs.peakPtuTempC), ptuA,
                climbTrend = ptuTrend),
            TempSpec("RDU (REAR)",
                if (vs.rduTempC > -90) p.displayTemp(vs.rduTempC) else "— —", p.tempLabel,
                vs.rduTempC.takeIf { it > -90 } ?: 0.0,
                p.rduWarnC, p.rduCritC, subFor(rduA, ""),
                peakStr(vs.peakRduTempC), rduA,
                climbTrend = rduTrend),
            TempSpec("AMBIENT",
                if (vs.ambientTempC > -90) p.displayTemp(vs.ambientTempC) else "— —", p.tempLabel,
                vs.ambientTempC.takeIf { it > -90 } ?: 0.0,
                40.0, 50.0, subFor(ambA, ""),
                avail = ambA),
        )
        val detailItems = listOf(
            TempSpec("CHARGE AIR",
                if (vs.chargeAirTempC > -90) p.displayTemp(vs.chargeAirTempC) else "— —", p.tempLabel,
                vs.chargeAirTempC, 60.0, 80.0, subFor(chargeA, ""),
                peakStr(vs.peakChargeAirTempC), chargeA),
            TempSpec("MANIFOLD",
                if (vs.manifoldChargeTempC > -90) p.displayTemp(vs.manifoldChargeTempC) else "— —", p.tempLabel,
                vs.manifoldChargeTempC, 60.0, 90.0, subFor(manifA, ""),
                avail = manifA),
            TempSpec("CATALYTIC",
                if (vs.catalyticTempC > -90) p.displayTemp(vs.catalyticTempC) else "— —", p.tempLabel,
                vs.catalyticTempC, 700.0, 800.0, subFor(catA, ""),
                avail = catA),
            TempSpec("CABIN",
                if (vs.cabinTempC > -90) p.displayTemp(vs.cabinTempC) else "— —", p.tempLabel,
                vs.cabinTempC.takeIf { it > -90 } ?: 0.0, 35.0, 45.0,
                subFor(cabinA, "BCM"), avail = cabinA),
            TempSpec("BATT TEMP",
                if (vs.batteryTempC > -90) p.displayTemp(vs.batteryTempC) else "— —", p.tempLabel,
                vs.batteryTempC.takeIf { it > -90 } ?: 0.0, 40.0, 60.0,
                subFor(battA, "BCM"), avail = battA),
            TempSpec("CLT LEFT",
                if (vs.awdClutchTempL > -90) p.displayTemp(vs.awdClutchTempL) else "— —", p.tempLabel,
                vs.awdClutchTempL.takeIf { it > -90 } ?: 0.0, 90.0, 120.0,
                subFor(clutchLA, "AWD"), avail = clutchLA),
            TempSpec("CLT RIGHT",
                if (vs.awdClutchTempR > -90) p.displayTemp(vs.awdClutchTempR) else "— —", p.tempLabel,
                vs.awdClutchTempR.takeIf { it > -90 } ?: 0.0, 90.0, 120.0,
                subFor(clutchRA, "AWD"), avail = clutchRA),
            TempSpec("TRANS OIL",
                if (vs.transOilTempC > -90) p.displayTemp(vs.transOilTempC) else "— —", p.tempLabel,
                vs.transOilTempC.takeIf { it > -90 } ?: 0.0, 100.0, 130.0,
                subFor(transA, "AWD"), avail = transA),
        )
        val columns = if (isWideLayout()) 3 else 2

        SectionLabel("POWERTRAIN")
        val ptRows = powertrainItems.chunked(columns)
        StaggeredColumn(itemCount = ptRows.size, modifier = Modifier.fillMaxWidth()) { index, entranceModifier ->
            Row(entranceModifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ptRows[index].forEach { spec -> TempCard(spec, Modifier.weight(1f)) }
                repeat(columns - ptRows[index].size) { Spacer(Modifier.weight(1f)) }
            }
        }

        var detailExpanded by rememberSectionExpanded("THERMAL_DETAIL")
        SectionLabel("DETAIL", collapsible = true, expanded = detailExpanded, onToggle = { detailExpanded = !detailExpanded })
        AnimatedVisibility(visible = detailExpanded, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))) {
            Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                val dRows = detailItems.chunked(columns)
                dRows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { spec -> TempCard(spec, Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * Availability state for a temperature cell's placeholder.
 * Drives pulse timing + sub-label. C6 timing: 800ms Warming, 1200ms Stale/Unavailable.
 */
enum class TempAvail { WARMING, AVAILABLE, STALE, UNAVAILABLE }

/**
 * Classify a sentinel-bearing temperature field into an availability state.
 * @param valueC current reading (sentinel values: <= -90)
 * @param lastUpdateMs epoch ms of last successful decode (null/0 if never)
 * @param staleAfterMs how long since last update before it's considered stale
 */
fun tempAvailFor(
    valueC: Double,
    lastUpdateMs: Long?,
    staleAfterMs: Long = 60_000L,
    now: Long = System.currentTimeMillis(),
): TempAvail = scalarAvailFor(valueC > -90, lastUpdateMs, staleAfterMs, now)

/**
 * An adapter drop leaves the last frame in VehicleState, and its stamps stay "fresh" for up to
 * 60 s — so while disconnected, a held reading is last-known ([TempAvail.STALE]), not current.
 */
fun TempAvail.whenDisconnected(connected: Boolean): TempAvail =
    if (!connected && this == TempAvail.AVAILABLE) TempAvail.STALE else this

/**
 * Per-frame dynamics as they read before the first connect — for composables that would otherwise
 * draw the held last frame as live after a drop. Temps / fuel / battery / TPMS are untouched (they
 * render as last-known via [whenDisconnected]); polled AWD clutch current/pressure go to sentinels.
 */
fun VehicleState.liveOrRest(): VehicleState = if (isConnected) this else copy(
    rpm = 0.0, speedKph = 0.0, throttlePct = 0.0, accelPedalPct = 0.0, brakePressure = 0.0,
    torqueAtTrans = 0.0, lateralG = 0.0, longitudinalG = 0.0, verticalG = 0.0, yawRate = 0.0,
    steeringAngle = 0.0,
    wheelSpeedFL = 0.0, wheelSpeedFR = 0.0, wheelSpeedRL = 0.0, wheelSpeedRR = 0.0,
    awdLeftTorque = 0.0, awdRightTorque = 0.0, awdReqTorqueL = 0.0, awdReqTorqueR = 0.0,
    awdDmdPressure = 0.0, awdPumpCurrent = 0.0,
    awdClutchCurL = -999.0, awdClutchCurR = -999.0, awdClutchPressureL = -1.0, awdClutchPressureR = -1.0,
)

/**
 * Generalized availability classifier for any sentinel-bearing scalar field.
 * Caller provides the "has a real reading" predicate so the sentinel convention
 * (temps ≤ -90, PSI/voltage/percent < 0, etc.) stays at the call site.
 *
 * @param hasReading true when the current value is a real decode (not a sentinel)
 * @param lastUpdateMs epoch ms of last successful decode (null/0 if never seen)
 */
fun scalarAvailFor(
    hasReading: Boolean,
    lastUpdateMs: Long?,
    staleAfterMs: Long = 60_000L,
    now: Long = System.currentTimeMillis(),
): TempAvail {
    val seen = (lastUpdateMs ?: 0L) > 0L
    val fresh = seen && (now - (lastUpdateMs ?: 0L) <= staleAfterMs)
    return when {
        hasReading && fresh -> TempAvail.AVAILABLE
        hasReading          -> TempAvail.STALE
        seen                -> TempAvail.UNAVAILABLE
        else                -> TempAvail.WARMING
    }
}

data class TempSpec(
    val label: String, val value: String, val unit: String,
    val tempC: Double, val warnC: Double, val critC: Double, val sub: String,
    val peakDisplay: String = "",
    val avail: TempAvail = TempAvail.AVAILABLE,
    val sparklineData: List<Float>? = null,
    val climbTrend: ClimbTrend? = null,
)

@Composable fun RtrBanner(vs: VehicleState, p: UserPrefs) {
    // When offline, bypass the readiness computation entirely — we have no
    // warmup data to trust. Show a neutral "awaiting CAN" chip instead of
    // a misleading green RACE READY.
    if (!vs.isConnected) {
        Row(
            Modifier.fillMaxWidth()
                .background(
                    Brush.horizontalGradient(listOf(Dim.copy(alpha = 0.08f), Dim.copy(alpha = 0.04f))),
                    RoundedCornerShape(12.dp)
                )
                .border(CardBorder, Dim.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(Dim.copy(alpha = 0.15f)))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Dim.copy(alpha = 0.6f)))
            }
            Spacer(Modifier.width(12.dp))
            UIText("— AWAITING CAN —", 13.sp, Dim, FontWeight.SemiBold, 0.5.sp)
        }
        return
    }

    // Overheat / missing data / warm-up all block RACE READY (see raceReadiness()).
    val rtr = raceReadiness(vs, RtrLimits.from(p), fmtTemp = { "${p.displayTemp(it)}${p.tempLabel}" })
    val isReady = rtr.isReady
    val warmupDetail = rtr.detail
    val dotColor = when (rtr.state) {
        RtrState.READY    -> Ok
        RtrState.OVERHEAT -> Orange
        else              -> Warn
    }
    val dotAlpha = if (isReady) {
        1f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "rtr")
        val anim by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 0.3f, label = "rtrDot",
            animationSpec = infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse)
        )
        anim
    }
    val bannerBrush = Brush.horizontalGradient(listOf(dotColor.copy(alpha = 0.08f), dotColor.copy(alpha = 0.04f)))
    val bannerBorder = dotColor.copy(alpha = if (rtr.state == RtrState.OVERHEAT) 0.45f else 0.2f)

    Row(
        Modifier.fillMaxWidth()
            .background(bannerBrush, RoundedCornerShape(12.dp))
            .border(CardBorder, bannerBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(dotColor.copy(alpha = 0.2f * dotAlpha)))
            Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor.copy(alpha = dotAlpha)))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            UIText(
                when (rtr.state) {
                    RtrState.READY    -> "RACE READY"
                    RtrState.OVERHEAT -> "OVERHEATING — NOT RACE READY"
                    RtrState.NO_DATA  -> "CHECKING — NOT RACE READY"
                    RtrState.WARMING  -> "WARMING UP — NOT RACE READY"
                },
                13.sp, Frost, FontWeight.SemiBold, 0.5.sp
            )
            if (warmupDetail != null) {
                MonoLabel(warmupDetail, 9.sp, dotColor, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable fun TempPresetBadge(p: UserPrefs) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(CardBorder, Brd, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            MonoLabel("THRESHOLD PRESET", 8.sp, Dim, letterSpacing = 0.15.sp)
            Spacer(Modifier.height(2.dp))
            UIText(p.tempPresetName, 14.sp, Frost, FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("street" to "STREET", "track" to "TRACK", "race" to "RACE").forEach { (id, label) ->
                val isActive = p.tempPreset == id
                val color    = when (id) { "race" -> Orange; "track" -> Warn; else -> Ok }
                Box(
                    Modifier
                        .background(if (isActive) color.copy(alpha = 0.15f) else Surf3, RoundedCornerShape(6.dp))
                        .border(CardBorder, if (isActive) color.copy(alpha = 0.5f) else Brd, RoundedCornerShape(6.dp))
                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.Confirm); UserPrefsStore.update(ctx) { it.copy(tempPreset = id) } }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    MonoLabel(label, 9.sp, if (isActive) color else Dim, letterSpacing = 0.1.sp)
                }
            }
        }
    }
}

@Composable fun TempCard(spec: TempSpec, modifier: Modifier) {
    val tempColor = tempColorShade(spec.tempC, spec.warnC, spec.critC)
    val barPct = if (spec.critC > 0) (spec.tempC / spec.critC).toFloat().coerceIn(0f, 1f) else 0f
    val barColor = when {
        spec.tempC <= 0         -> Surf3
        spec.tempC < spec.warnC -> Ok.copy(alpha = 0.6f)
        spec.tempC < spec.critC -> Warn.copy(alpha = 0.7f)
        else                    -> Orange
    }
    val isPlaceholder = spec.value == "— —"
    val isWarn = !isPlaceholder && spec.tempC >= spec.warnC
    val borderGlow = when {
        isPlaceholder           -> Brd.copy(alpha = 0.3f)
        spec.tempC >= spec.critC -> Orange.copy(alpha = 0.4f)
        spec.tempC >= spec.warnC -> Warn.copy(alpha = 0.3f)
        else                    -> Ok.copy(alpha = 0.1f)
    }

    // Peak fraction for tick mark
    val peakBarPct = if (spec.peakDisplay.isNotEmpty() && spec.critC > 0) {
        // Extract peak temp from peakDisplay string (format "▲ 123°F")
        val peakStr = spec.peakDisplay.removePrefix("▲ ").replace(Regex("[^0-9.-]"), "")
        val peakVal = peakStr.toDoubleOrNull() ?: 0.0
        (peakVal / spec.critC).toFloat().coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier
            .background(Surf2, RoundedCornerShape(14.dp))
            .border(CardBorder, if (!isPlaceholder) borderGlow else Brd.copy(alpha = borderAlpha(0.15f)), RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(spec.label, 8.sp, Dim, letterSpacing = 0.12.sp)
                if (spec.sub.isNotEmpty()) MonoLabel(spec.sub, 7.sp, Dim.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(6.dp))
            if (isPlaceholder) {
                // C6: pulse cadence hints at meaning. Warming = hopeful (800ms),
                // Stale/Unavailable = calmer/less alarming (1200ms).
                val pulseMs = when (spec.avail) {
                    TempAvail.WARMING     -> 800
                    TempAvail.STALE       -> 1200
                    TempAvail.UNAVAILABLE -> 1200
                    TempAvail.AVAILABLE   -> 900
                }
                val alphaLo = if (spec.avail == TempAvail.UNAVAILABLE) 0.15f else 0.3f
                val alphaHi = if (spec.avail == TempAvail.UNAVAILABLE) 0.35f else 0.7f
                val phAlpha by rememberInfiniteTransition(label = "ph").animateFloat(
                    initialValue = alphaLo, targetValue = alphaHi,
                    animationSpec = infiniteRepeatable(tween(pulseMs), RepeatMode.Reverse), label = "phA"
                )
                MonoText("— —", 24.sp, Dim.copy(alpha = phAlpha))
            } else {
                // Value with bloom glow when above warn threshold
                Box(
                    Modifier.fillMaxWidth()
                        .then(if (isWarn) Modifier.drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(tempColor.copy(alpha = 0.15f), Color.Transparent),
                                    center = center,
                                    radius = size.minDimension * 0.9f
                                )
                            )
                        } else Modifier)
                ) {
                    HeroNum(spec.value, 24.sp, tempColor)
                }
            }
            if (spec.peakDisplay.isNotEmpty()) {
                val accent = LocalThemeAccent.current
                MonoText(spec.peakDisplay, 9.sp, accent)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MonoLabel(spec.unit, 10.sp, Dim)
                spec.climbTrend?.let { trend ->
                    val (arrow, color) = when (trend) {
                        ClimbTrend.RISING  -> "\u2191" to Warn
                        ClimbTrend.COOLING -> "\u2193" to Ok
                        ClimbTrend.STABLE  -> "\u2192" to Dim
                    }
                    MonoLabel(arrow, 10.sp, color)
                }
            }
            // V1: optional sparkline trend between unit label and bar
            if (spec.sparklineData != null && spec.sparklineData.size >= 2) {
                Spacer(Modifier.height(4.dp))
                com.openrs.dash.ui.anim.Sparkline(
                    data = spec.sparklineData,
                    lineColor = tempColor,
                    modifier = Modifier.fillMaxWidth().height(18.dp),
                    strokeWidth = 1.dp,
                    fillAlpha = 0.12f
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Surf3, RoundedCornerShape(2.dp))) {
                if (!isPlaceholder && barPct > 0) {
                    Box(Modifier.fillMaxWidth(barPct).height(3.dp)
                        .background(barColor, RoundedCornerShape(2.dp))
                    )
                }
                // Peak tick mark
                if (peakBarPct > 0.05f) {
                    val accent = LocalThemeAccent.current
                    Box(
                        Modifier.fillMaxWidth(peakBarPct)
                            .height(3.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Box(Modifier.width(1.5.dp).height(5.dp).background(accent.copy(alpha = 0.6f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThermalAlertBanner(
    pred: ThermalPredictor.SensorPrediction,
    p: UserPrefs,
    onDismiss: () -> Unit
) {
    val ttc = pred.timeToCriticalMin ?: return
    fun t(c: Double) = "${p.displayTemp(c)}${p.tempLabel}"
    // At/over the limit it's a current warning, not a forecast — no "will reach … in <1 min".
    val message = if (pred.reached)
        "${pred.sensorLabel} ${t(pred.currentC)} — at/above critical ${t(pred.critThresholdC)}"
    else {
        val timeStr = if (ttc < 1.0) "<1 min" else "~${ttc.toInt()} min"
        "${pred.sensorLabel} will reach ${t(pred.critThresholdC)} in $timeStr at current rate"
    }
    // Rate in the user's unit (°F/min = °C/min × 1.8)
    val rate = pred.climbRateCPerMin * (if (p.tempUnit == "C") 1.0 else 1.8)

    val dotAlpha by rememberInfiniteTransition(label = "alert").animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600, easing = EaseInOut), RepeatMode.Reverse),
        label = "alertDot"
    )

    Row(
        Modifier.fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(Orange.copy(alpha = 0.1f), Orange.copy(alpha = 0.05f))),
                RoundedCornerShape(12.dp)
            )
            .border(Tokens.CardBorder, Orange.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(Orange.copy(alpha = 0.2f * dotAlpha)))
            Box(Modifier.size(8.dp).clip(CircleShape).background(Orange.copy(alpha = dotAlpha)))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MonoLabel(message, 9.sp, Frost)
            if (!pred.reached) {
                MonoLabel("+${"%.1f".format(rate)}${p.tempLabel}/min", 8.sp, Orange)
            }
        }
        Box(
            Modifier
                .background(Surf3, RoundedCornerShape(6.dp))
                .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                .clickable { onDismiss() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            MonoLabel("DISMISS", 8.sp, Dim)
        }
    }
}
