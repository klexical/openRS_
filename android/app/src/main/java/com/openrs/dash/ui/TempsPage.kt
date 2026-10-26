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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
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
    val lastTempSample = remember(session) { mutableLongStateOf(0L) }
    SideEffect {
        val now = vs.lastUpdate
        if (now - lastTempSample.longValue >= 1000L) {
            lastTempSample.longValue = now
            if (vs.oilTempC > -90) oilSpark.push(vs.oilTempC.toFloat())
            if (vs.coolantTempC > -90) coolSpark.push(vs.coolantTempC.toFloat())
        }
    }
    // Readiness, trends and forecasts come from the session-level evaluator (fed whichever tab is open).
    val verdict = thermalVerdict(vs)
    val snoozed by ThermalReadinessSession.snoozed.collectAsState()
    val sparkKey = lastTempSample.longValue
    val oilSnap = remember(sparkKey) { oilSpark.snapshot() }
    val coolSnap = remember(sparkKey) { coolSpark.snapshot() }

    // Short viewports (≈ 360 × 720 dp phones) trim padding and gaps — never the reading sizes — so
    // the drivetrain group starts higher up.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = if (maxHeight < 760.dp) TightThermal else RoomyThermal
    CompositionLocalProvider(LocalThermalDensity provides density) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = PagePad, end = PagePad, top = density.pageTop, bottom = pageBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(density.gap)
    ) {
        ReadinessCard(verdict, p)

        // ── Predictive thermal alerts (DISMISS snoozes the banner 2 min — never the verdict) ──
        // One banner: the soonest forecast, naming the others — four stacked banners pushed oil and
        // coolant off a 360 dp screen.
        val wallNow = System.currentTimeMillis()
        val shownForecasts = verdict.banners.filter { (snoozed[it.sensorLabel] ?: 0L) <= wallNow }
        forecastBanner(shownForecasts)?.let { b ->
            ThermalAlertBanner(b.lead, p, alsoReached = b.alsoReached, alsoForecast = b.alsoForecast) {
                shownForecasts.forEach { ThermalReadinessSession.snooze(it.sensorLabel) }
            }
        }

        fun peakStr(peakC: Double) = if (peakC > -90) "PEAK ${p.displayTemp(peakC)}${p.tempLabel}" else ""
        fun avail(valueC: Double, tag: String) = vs.tempAvail(valueC, tag)
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
        // Arrows only for fresh readings with a judged trend (the verdict's predictor, 10-sample minimum).
        val oilTrend     = verdict.reading(ThermalSensor.OIL)?.trend
        val coolTrend    = verdict.reading(ThermalSensor.COOLANT)?.trend
        val ptuTrend     = verdict.reading(ThermalSensor.PTU)?.trend
        val rduTrend     = verdict.reading(ThermalSensor.RDU)?.trend
        val engineItems = listOf(
            TempSpec("ENGINE OIL",
                if (vs.oilTempC > -90) p.displayTemp(vs.oilTempC) else "— —", p.tempLabel,
                vs.oilTempC.takeIf { it > -90 } ?: 0.0,
                p.oilWarnC, p.oilCritC, subFor(oilA, "INFERRED"),
                peakStr(vs.peakOilTempC), oilA, peakC = vs.peakOilTempC,
                sparklineData = oilSnap.takeIf { it.size >= 2 },
                climbTrend = oilTrend),
            TempSpec("COOLANT",
                if (vs.coolantTempC > -90) p.displayTemp(vs.coolantTempC) else "— —", p.tempLabel,
                vs.coolantTempC.takeIf { it > -90 } ?: 0.0,
                p.coolWarnC, p.coolCritC, subFor(coolA, ""),
                peakStr(vs.peakCoolantTempC), coolA, peakC = vs.peakCoolantTempC,
                sparklineData = coolSnap.takeIf { it.size >= 2 },
                climbTrend = coolTrend),
        )
        // PTU / RDU use the preset limits; the AWD clutch + trans oil rows keep their fixed limits.
        val drivetrainItems = listOf(
            TempSpec("PTU",
                if (vs.ptuTempC > -90) p.displayTemp(vs.ptuTempC) else "— —", p.tempLabel,
                vs.ptuTempC.takeIf { it > -90 } ?: 0.0,
                p.ptuWarnC, p.ptuCritC, subFor(ptuA, "TRANSFER"),
                peakStr(vs.peakPtuTempC), ptuA, peakC = vs.peakPtuTempC,
                climbTrend = ptuTrend),
            TempSpec("RDU",
                if (vs.rduTempC > -90) p.displayTemp(vs.rduTempC) else "— —", p.tempLabel,
                vs.rduTempC.takeIf { it > -90 } ?: 0.0,
                p.rduWarnC, p.rduCritC, subFor(rduA, "REAR DRIVE"),
                peakStr(vs.peakRduTempC), rduA, peakC = vs.peakRduTempC,
                climbTrend = rduTrend),
            TempSpec("CLUTCH L",
                if (vs.awdClutchTempL > -90) p.displayTemp(vs.awdClutchTempL) else "— —", p.tempLabel,
                vs.awdClutchTempL.takeIf { it > -90 } ?: 0.0, p.clutchWarnC, p.clutchCritC,
                subFor(clutchLA, "AWD"), avail = clutchLA),
            TempSpec("CLUTCH R",
                if (vs.awdClutchTempR > -90) p.displayTemp(vs.awdClutchTempR) else "— —", p.tempLabel,
                vs.awdClutchTempR.takeIf { it > -90 } ?: 0.0, p.clutchWarnC, p.clutchCritC,
                subFor(clutchRA, "AWD"), avail = clutchRA),
            TempSpec("TRANS OIL",
                if (vs.transOilTempC > -90) p.displayTemp(vs.transOilTempC) else "— —", p.tempLabel,
                vs.transOilTempC.takeIf { it > -90 } ?: 0.0, 100.0, 130.0,
                subFor(transA, "AWD"), avail = transA),
        )
        val airItems = listOf(
            TempSpec("INTAKE",
                if (vs.intakeTempC > -90) p.displayTemp(vs.intakeTempC) else "— —", p.tempLabel,
                vs.intakeTempC.takeIf { it > -90 } ?: 0.0,
                p.intakeWarnC, p.intakeCritC, subFor(intakeA, ""),
                avail = intakeA),
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
                peakStr(vs.peakChargeAirTempC), chargeA, peakC = vs.peakChargeAirTempC),
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
        )

        // Hierarchy: engine oil + coolant (full-width, scale + legend) → drivetrain group (one panel,
        // one row each) → air temps (compact) → tyres & AWD → collapsible detail.
        SectionLabel("ENGINE")
        StaggeredColumn(itemCount = engineItems.size, modifier = Modifier.fillMaxWidth(),
            spacing = Arrangement.spacedBy(density.gap)) { index, entranceModifier ->
            EngineTempCard(engineItems[index], p, entranceModifier.fillMaxWidth())
        }

        SectionLabel("DRIVETRAIN")
        DrivetrainPanel(drivetrainItems)

        SectionLabel("AIR")
        CompactTempPanel(airItems, columns = 2)

        // ── Tires + AWD (migrated from CHASSIS) — after the temps that decide readiness ──
        UnifiedChassisSection(vs, p)

        var detailExpanded by rememberSectionExpanded("THERMAL_DETAIL")
        SectionLabel("DETAIL", collapsible = true, expanded = detailExpanded, onToggle = { detailExpanded = !detailExpanded })
        AnimatedVisibility(visible = detailExpanded, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))) {
            CompactTempPanel(detailItems, columns = if (isWideLayout()) 5 else 3)
        }
    }
    }
    }
}

/** THERMAL spacing. Tight on short viewports: padding and gaps shrink, reading sizes don't. */
data class ThermalDensity(val pageTop: Dp, val gap: Dp, val cardV: Dp, val inner: Dp, val rowMin: Dp)
private val RoomyThermal = ThermalDensity(pageTop = PagePad, gap = CardGap, cardV = 12.dp, inner = 6.dp, rowMin = 48.dp)
private val TightThermal = ThermalDensity(pageTop = 8.dp, gap = 6.dp, cardV = 8.dp, inner = 3.dp, rowMin = 40.dp)
val LocalThermalDensity = staticCompositionLocalOf { RoomyThermal }

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
    /** Session peak in °C (sentinel ≤ -90 = none) — drawn as a tick on the threshold bar. */
    val peakC: Double? = null,
    val sparklineData: List<Float>? = null,
    val climbTrend: ClimbTrend? = null,
)

/**
 * The session readiness verdict for this frame. Offline, or computed in an older demo session (the
 * evaluator re-runs on the epoch change within milliseconds), it reads NO LIVE DATA at once.
 */
@Composable fun thermalVerdict(vs: VehicleState): ThermalVerdict {
    val v by ThermalReadinessSession.verdict.collectAsState()
    val epoch = telemetrySessionKey()
    return if (vs.isConnected && v.epoch == epoch) v else ThermalVerdict.noLiveData(epoch)
}

/** Dot / headline colour per state — orange at crit, amber at warn / forecast / missing data, accent while warming. */
@Composable fun thermalStateColor(state: ThermalState): Color = when (state) {
    ThermalState.OVERHEATING -> Orange
    ThermalState.TEMPERATURE_HIGH, ThermalState.HEAT_RISING, ThermalState.CHECKING -> Warn
    ThermalState.ENGINE_WARMING, ThermalState.DRIVETRAIN_WARMING -> LocalThemeAccent.current
    ThermalState.TEMPS_READY -> Ok
    ThermalState.NO_LIVE_DATA -> Dim
}

/**
 * Readiness summary + threshold preset in one card: headline, one reason, the STREET / TRACK / RACE
 * chips and a CHECKLIST toggle (collapsed by default, not persisted) — about the height of the old
 * readiness card, so oil and coolant stay where they were on a 360 dp phone.
 */
@Composable fun ReadinessCard(verdict: ThermalVerdict, p: UserPrefs) {
    val state = verdict.state
    val live = state != ThermalState.NO_LIVE_DATA
    val dotColor = thermalStateColor(state)
    val pulsing = live && !verdict.isReady
    val dotAlpha = if (pulsing) {
        val anim by rememberInfiniteTransition(label = "rtr").animateFloat(
            initialValue = 1f, targetValue = 0.3f, label = "rtrDot",
            animationSpec = infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse)
        )
        anim
    } else 1f
    val level = when (state) {
        ThermalState.OVERHEATING -> Level.CRIT
        ThermalState.TEMPERATURE_HIGH, ThermalState.HEAT_RISING -> Level.WARN
        else -> Level.NORMAL
    }
    var expanded by rememberSaveable { mutableStateOf(false) }

    val d = LocalThermalDensity.current
    val toggleLabel = if (expanded) "Hide readiness checklist" else "Show readiness checklist"
    Column(Modifier.fillMaxWidth().instrumentSurface(level).padding(horizontal = 14.dp, vertical = d.cardV + 2.dp)) {
        // The summary itself also toggles the checklist — a bigger target than the CHECKLIST label.
        Row(Modifier.fillMaxWidth().clickable(enabled = live, onClickLabel = toggleLabel) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(dotColor.copy(alpha = 0.2f * dotAlpha)))
                Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor.copy(alpha = if (!live) 0.6f else dotAlpha)))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    state.headline, color = textColor(if (level.isAlert || state == ThermalState.TEMPS_READY) dotColor else if (live) Frost else Dim),
                    fontFamily = RajdhaniFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.5.sp,
                    maxLines = 1, softWrap = false,
                )
                androidx.compose.material3.Text(
                    verdict.reason(p), color = textColor(if (level.isAlert) Frost else Mid), fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp, maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(d.inner * 2))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.4f))))
        Spacer(Modifier.height(d.inner + 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PresetSegments(p)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.heightIn(min = 44.dp)
                    .clickable(enabled = live, onClickLabel = toggleLabel) { expanded = !expanded }
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoLabel("CHECKLIST", 10.sp, if (live) Mid else Dim.copy(alpha = 0.5f), FontWeight.SemiBold, 0.6.sp)
                Spacer(Modifier.width(4.dp))
                MonoLabel(if (expanded && live) "▾" else "▸", 12.sp, if (live) Mid else Dim.copy(alpha = 0.5f))
            }
        }
        AnimatedVisibility(visible = expanded && live, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))) {
            Column(Modifier.padding(top = d.inner), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                verdict.checklist(p).forEach { ChecklistRow(it) }
            }
        }
    }
}

@Composable private fun ChecklistRow(item: CheckItem) {
    val (mark, color) = when (item.mark) {
        CheckMark.OK -> "✓" to Ok
        CheckMark.WAIT -> "○" to Dim
        CheckMark.ALERT -> "!" to levelColor(item.level)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(18.dp)) { MonoLabel(mark, 12.sp, color, FontWeight.Bold) }
        // The rule name keeps its line; a long value wraps right-aligned under itself instead of squeezing it.
        androidx.compose.material3.Text(item.label, color = textColor(Mid), fontFamily = JetBrainsMonoFamily, fontSize = 10.sp,
            letterSpacing = 0.2.sp, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.Text(item.value, color = textColor(if (item.mark == CheckMark.ALERT) color else Frost),
            fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** STREET / TRACK / RACE segmented control. Selection uses the paint accent, not warning colours. */
@Composable private fun PresetSegments(p: UserPrefs) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val accent = LocalThemeAccent.current
    Row(
        Modifier.background(Surf3, RoundedCornerShape(8.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // CUSTOM appears once a custom profile exists (Settings → THERMAL); four chips use tighter padding.
        val profiles = listOf("street" to "STREET", "track" to "TRACK", "race" to "RACE") +
            (if (p.customThermal != null) listOf("custom" to "CUSTOM") else emptyList())
        val chipPad = if (profiles.size > 3) 8.dp else 12.dp
        profiles.forEach { (id, label) ->
            val active = p.tempPreset == id
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .background(if (active) accent.copy(alpha = pillBgAlpha(0.18f)) else Color.Transparent, RoundedCornerShape(6.dp))
                    .border(Tokens.CardBorder, if (active) accent.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Use the $label thermal profile") {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        UserPrefsStore.update(ctx) { it.copy(tempPreset = id) }
                    }
                    .padding(horizontal = chipPad),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel(label, 10.sp, if (active) Frost else Dim,
                    if (active) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.6.sp)
            }
        }
    }
}

private fun TempSpec.level(): Level =
    if (value == "— —") Level.NONE else tempLevel(tempC, warnC, critC, avail)

/** Trend arrow — only coloured when the reading is already warm / critical. */
@Composable private fun TrendArrow(trend: ClimbTrend?, level: Level) {
    val arrow = when (trend) {
        ClimbTrend.RISING  -> "↑"
        ClimbTrend.COOLING -> "↓"
        else               -> return
    }
    MonoLabel(arrow, 12.sp, if (trend == ClimbTrend.RISING && level.isAlert) levelColor(level) else Dim)
}

/** Pulsing "— —" for a reading that hasn't arrived (C6 cadence: warming 800 ms, stale/N/A 1200 ms). */
@Composable private fun TempPlaceholder(avail: TempAvail, size: androidx.compose.ui.unit.TextUnit) {
    val pulseMs = if (avail == TempAvail.WARMING) 800 else 1200
    val lo = if (avail == TempAvail.UNAVAILABLE) 0.15f else 0.3f
    val hi = if (avail == TempAvail.UNAVAILABLE) 0.35f else 0.7f
    val a by rememberInfiniteTransition(label = "ph").animateFloat(
        initialValue = lo, targetValue = hi,
        animationSpec = infiniteRepeatable(tween(pulseMs), RepeatMode.Reverse), label = "phA"
    )
    MonoText("— —", size, Dim.copy(alpha = a))
}

/** Engine oil / coolant: the two temps that decide whether to keep pushing — full width, scale + legend. */
@Composable fun EngineTempCard(spec: TempSpec, p: UserPrefs, modifier: Modifier = Modifier) {
    val level = spec.level()
    val valueColor = levelColor(level)
    val d = LocalThermalDensity.current
    Column(modifier.instrumentSurface(level).padding(horizontal = 14.dp, vertical = d.cardV)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InstrumentLabel(spec.label, Modifier.weight(1f))
            TrendArrow(spec.climbTrend, level)
            if (spec.sub.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                InstrumentMeta(spec.sub, color = if (spec.avail == TempAvail.STALE) WarnInk else Dim)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (level == Level.NONE) TempPlaceholder(spec.avail, 34.sp)
            else {
                DriveNum(spec.value, 44.sp, valueColor)
                Spacer(Modifier.width(4.dp))
                InstrumentMeta(spec.unit, Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            // ≥ 10 samples (~10 s): a two-point trace drew as a stray dash beside the value.
            if (spec.sparklineData != null && spec.sparklineData.size >= 10) {
                com.openrs.dash.ui.anim.Sparkline(
                    data = spec.sparklineData,
                    lineColor = if (level.isAlert) valueColor else accentMid(),
                    modifier = Modifier.width(132.dp).height(30.dp).padding(bottom = 6.dp),
                    strokeWidth = 1.5.dp, fillAlpha = 0.12f
                )
            }
        }
        Spacer(Modifier.height(d.inner))
        ThresholdBar(spec.tempC, spec.warnC, spec.critC, level, peakC = spec.peakC)
        Spacer(Modifier.height(d.inner))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InstrumentMeta(spec.peakDisplay)
            Spacer(Modifier.weight(1f))
            ThresholdLegend(
                "${p.displayLimit(spec.warnC)}${p.tempLabel}",
                "${p.displayLimit(spec.critC)}${p.tempLabel}"
            )
        }
    }
}

/** PTU, RDU, AWD clutches and trans oil as one group: name · scale · value per row, one legend. */
@Composable fun DrivetrainPanel(items: List<TempSpec>) {
    val d = LocalThermalDensity.current
    Column(Modifier.fillMaxWidth().instrumentSurface(worstLevel(items.map { it.level() }))
        .padding(horizontal = 14.dp, vertical = d.cardV - 2.dp)) {
        items.forEachIndexed { i, spec ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.35f))))
            val level = spec.level()
            Row(Modifier.fillMaxWidth().heightIn(min = d.rowMin), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(92.dp)) {
                    InstrumentLabel(spec.label, color = Mid)
                    if (spec.sub.isNotEmpty())
                        MonoLabel(spec.sub, 9.sp, if (spec.avail == TempAvail.STALE) WarnInk else Dim, letterSpacing = 0.2.sp)
                }
                ThresholdBar(spec.tempC, spec.warnC, spec.critC, level,
                    Modifier.weight(1f).padding(horizontal = 8.dp), peakC = spec.peakC)
                Row(Modifier.width(76.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
                    TrendArrow(spec.climbTrend, level)
                    if (level == Level.NONE) TempPlaceholder(spec.avail, 18.sp)
                    else {
                        DataNum(spec.value, 22.sp, levelColor(level))
                        Spacer(Modifier.width(2.dp))
                        InstrumentMeta(spec.unit, Modifier.padding(bottom = 3.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        ThresholdLegend(null, null, Modifier.align(Alignment.End))
    }
}

/** Secondary temps (air, detail): caption + value only, no scale — they don't decide readiness. */
@Composable fun CompactTempPanel(items: List<TempSpec>, columns: Int) {
    val d = LocalThermalDensity.current
    Column(Modifier.fillMaxWidth().instrumentSurface(worstLevel(items.map { it.level() }))
        .padding(horizontal = 14.dp, vertical = d.cardV),
        verticalArrangement = Arrangement.spacedBy(d.cardV)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { spec ->
                    val level = spec.level()
                    Column(Modifier.weight(1f)) {
                        InstrumentLabel(spec.label)
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (level == Level.NONE) TempPlaceholder(spec.avail, 18.sp)
                            else {
                                DataNum(spec.value, 22.sp, levelColor(level))
                                Spacer(Modifier.width(2.dp))
                                InstrumentMeta(spec.unit, Modifier.padding(bottom = 3.dp))
                            }
                        }
                        if (spec.sub.isNotEmpty())
                            MonoLabel(spec.sub, 9.sp, if (spec.avail == TempAvail.STALE) WarnInk else Dim, letterSpacing = 0.2.sp)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ThermalAlertBanner(
    pred: ThermalPredictor.SensorPrediction,
    p: UserPrefs,
    alsoReached: List<String> = emptyList(),
    alsoForecast: List<String> = emptyList(),
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
                MonoLabel("+${p.displayTempRate(pred.climbRateCPerMin)}${p.tempLabel}/min", 8.sp, Orange)
            }
            if (alsoReached.isNotEmpty()) MonoLabel("Also at/above critical: ${alsoReached.joinToString(" · ")}", 8.sp, Mid)
            if (alsoForecast.isNotEmpty()) MonoLabel("Also forecast: ${alsoForecast.joinToString(" · ")}", 8.sp, Mid)
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
