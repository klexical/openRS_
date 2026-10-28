package com.openrs.dash.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import com.openrs.dash.R
import com.openrs.dash.can.ObdConstants
import androidx.annotation.DrawableRes
import com.openrs.dash.ui.anim.isShiftPoint
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.anim.ShiftLightBar
import com.openrs.dash.ui.anim.SparklineData
import com.openrs.dash.ui.anim.pageEntrance
import androidx.compose.animation.animateColorAsState
import com.openrs.dash.ui.Tokens.PagePad
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.Tokens.CardGap
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE PAGE — what's happening right now, curated for glance-readability
// Polled fields (temps, fuel economy, odometer, warnings) live on other tabs.
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun DrivePage(vs: VehicleState, p: UserPrefs, onOpenThermal: () -> Unit = {}, onConnect: () -> Unit = {}) {
    var pageEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pageEntered = true }
    val accent = LocalThemeAccent.current
    val (_, boostLbl) = p.displayBoost(vs.boostKpa)

    // B1: raw live values for hero numerics — spring smoothing removed so the
    // digits are 1:1 with the CAN frame.
    // Disconnected: pedal inputs are the last frame before the drop — show rest (0 %), like
    // before the first connect, rather than a frozen mid-pull reading.
    val thr = if (!vs.isConnected) 0.0 else vs.throttleOrPedalPct.coerceIn(0.0, 100.0)
    val brake = if (!vs.isConnected) 0.0 else vs.brakePressure.coerceIn(0.0, 100.0)

    val session     = telemetrySessionKey()   // demo start/reset/exit drops simulated history
    val boostSpark  = remember(session) { SparklineData(60) }
    val rpmSpark    = remember(session) { SparklineData(60) }
    val speedSpark  = remember(session) { SparklineData(60) }
    val lastSample  = remember(session) { mutableLongStateOf(0L) }
    SideEffect {
        val now = vs.lastUpdate
        if (now - lastSample.longValue >= 250L) {
            lastSample.longValue = now
            boostSpark.push(vs.boostKpa.toFloat())
            rpmSpark.push(vs.rpm.toFloat())
            speedSpark.push(vs.speedKph.toFloat())
        }
    }

    // A4: rebuild the List<Float> snapshots only when a new sample lands
    // (≤ 4 Hz), not on every CAN-driven recomposition (~60 Hz).
    val sampleKey = lastSample.longValue
    val boostSnap = remember(sampleKey) { boostSpark.snapshot() }
    val rpmSnap   = remember(sampleKey) { rpmSpark.snapshot() }
    val speedSnap = remember(sampleKey) { speedSpark.snapshot() }

    val stale = !vs.isConnected

    // Disconnected → route heroes through the same "—" placeholder DataCell
    // uses, so dormant hero cards don't read as real "0.0" samples. The row
    // is still lightly dimmed so peak holds + sparklines are visibly inactive.
    val (boostValLive, _) = p.displayBoost(vs.boostKpa)
    val boostVal = if (stale) "—" else boostValLive
    val speedStr = if (stale) "—" else p.displaySpeed(vs.speedKph)
    val rpmStr = if (stale) "—" else "${vs.rpm.toInt()}"
    val heroAlpha by animateFloatAsState(
        if (stale) 0.6f else 1f,
        tween(300),
        label = "heroAlpha"
    )

    // Gear-change haptic tick on a derivedGear delta (skips the first composition). Nothing on DRIVE
    // resizes with speed, RPM or gear — the old auto-zoom / 1.05× gear pop pushed the panels below.
    val haptic = LocalHapticFeedback.current
    var lastGear by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(vs.derivedGear) {
        val g = vs.derivedGear
        if (lastGear != null && lastGear != g && g > 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastGear = g
    }

    val scrollState = rememberScrollState()

    // ── F7: live recording stats ────────────────────────────────────────
    val driveState by OpenRSDashApp.instance.driveState.collectAsState()
    // ── F2: fuel economy ─────────────────────────────────────────────────
    val econState by FuelEconomy.state.collectAsState()
    val knockCount by OpenRSDashApp.instance.knockEventCount.collectAsState()
    val status = vehicleStatus(vs, p, knockCount)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportH = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(start = PagePad, end = PagePad, top = PagePad, bottom = pageBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(CardGap)
        ) {
            // F7: live session stats strip — visible during active recording
            if (driveState.isRecording) {
                val elapsed = driveState.elapsedMs
                val h = elapsed / 3_600_000
                val m = (elapsed % 3_600_000) / 60_000
                val s = (elapsed % 60_000) / 1_000
                val timeStr = if (driveState.isPaused) "PAUSED" else "%02d:%02d:%02d".format(h, m, s)
                val distKm = driveState.cumulativeDistanceKm
                val distStr = if (p.speedUnit == "MPH") "${"%.1f".format(distKm * UnitConversions.KM_TO_MI)} mi"
                              else "${"%.1f".format(distKm)} km"
                val avgSpd = driveState.avgSpeedKph
                val avgStr = p.displaySpeed(avgSpd) + " " + p.speedLabel.lowercase()
                val pts = driveState.totalPointCount

                Row(
                    Modifier.fillMaxWidth()
                        .background(Surf2, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoLabel("REC", 8.sp, if (driveState.isPaused) Warn else Ok, letterSpacing = 0.3.sp)
                    MonoLabel(timeStr, 8.sp, if (driveState.isPaused) Warn else Frost, letterSpacing = 0.3.sp)
                    MonoLabel(distStr, 8.sp, Dim, letterSpacing = 0.2.sp)
                    MonoLabel("avg $avgStr", 8.sp, Dim, letterSpacing = 0.2.sp)
                    MonoLabel("$pts pts", 8.sp, Dim, letterSpacing = 0.2.sp)
                }
            }

            // V3: idle breathing — subtle ±0.03 alpha pulse when engine is idling.
            // One-way duration scales with RPM: ~2.5s at 800 RPM → ~3.0s at 600 RPM.
            // Full cycle (with RepeatMode.Reverse) is double that.
            val isIdling = vs.isConnected && vs.rpm in 400.0..800.0
            val breathDurationMs = if (isIdling) (60_000.0 / vs.rpm * 30.0).toInt().coerceIn(1800, 3000) else 2400
            val breathAlpha by rememberInfiniteTransition(label = "breath").animateFloat(
                initialValue = 0f, targetValue = if (isIdling) 0.03f else 0f,
                animationSpec = infiniteRepeatable(tween(breathDurationMs), RepeatMode.Reverse),
                label = "breathA"
            )

            // Permanent status line: warnings lead the page without ever moving the instruments.
            // Temperatures speak through the shared readiness verdict (same holds as THERMAL);
            // tyre / fuel / knock alerts keep their own 3 s hold.
            val readiness = thermalVerdict(vs)
            val link = when {
                vs.isConnected -> LinkState.LIVE
                vs.isIdle      -> LinkState.RECONNECTING
                else           -> LinkState.OFFLINE
            }
            StatusLine(status.alerts.filterNot { it.temp }, readiness, p, link, onOpenThermal, onConnect)

            // Design pass: readings in ink, BOOST (the car's personality) carries the paint accent,
            // RPM turns Orange only at the shift-light flash point. Equal-height cards with a fixed
            // value box keep the three baselines aligned.
            val atShift = !stale && isShiftPoint(vs.rpm.toFloat())
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max)
                    .alpha((heroAlpha - breathAlpha).coerceIn(0f, 1f))
                    .then(pageEntrance(0, pageEntered)),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InstrumentHero(
                    label = "ENGINE", unit = "RPM", value = rpmStr,
                    valueColor = if (atShift) Orange else Frost,
                    valueSize = 34.sp,
                    peak = "PEAK ${vs.peakRpm.toInt()}",
                    peakHoldValue = vs.peakRpm.toInt().toString(),
                    spark = rpmSnap, sparkColor = accentMid(),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                InstrumentHero(
                    label = "BOOST", unit = boostLbl, value = boostVal,
                    icon = RsIcon.BOOST, iconTone = if (stale) IconTone.UNAVAILABLE else IconTone.NORMAL,
                    valueColor = if (stale) Frost else accent,
                    valueSize = 44.sp,
                    peak = "PEAK ${"%.1f".format(vs.peakBoostPsi)}",
                    peakHoldValue = "%.1f".format(vs.peakBoostPsi),
                    spark = boostSnap, sparkColor = accent,
                    modifier = Modifier.weight(1.25f).fillMaxHeight()
                )
                InstrumentHero(
                    label = "SPEED", unit = p.speedLabel, value = speedStr,
                    valueSize = 34.sp,
                    peak = "PEAK ${p.displaySpeed(vs.peakSpeedKph)}",
                    peakHoldValue = p.displaySpeed(vs.peakSpeedKph),
                    spark = speedSnap, sparkColor = accentMid(),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }

            // Disconnected state is communicated by the header pill + adapter row
            // on GARAGE. Duplicating the banner here was double-signalling.

            // ── Gear module: shift lights, brake | gear | throttle ───────────
            val gearActive = vs.isConnected && (vs.rpm > 0 || vs.speedKph > 0)
            // B.6: mode color for the GEAR caption in SPORT/TRACK/DRIFT (prefs.gearModeTint)
            val isSportish = vs.driveMode in listOf(DriveMode.SPORT, DriveMode.TRACK, DriveMode.DRIFT)
            val modeColor = when (vs.driveMode) {
                DriveMode.SPORT -> Ok
                DriveMode.TRACK -> Warn
                DriveMode.DRIFT -> Orange
                else -> accent
            }
            // One fixed size per screen, from the page viewport only — never from speed / RPM / gear,
            // so the module and everything below it hold still while driving.
            val gearAreaH = (viewportH * 0.22f).coerceIn(124.dp, 176.dp)
            val gearSize = if (viewportH < 680.dp) 72.sp else 84.sp
            val gearDigitH = with(LocalDensity.current) { gearSize.toDp() }
            // Launch control / e-brake live inside this fixed-size module (never inserted above / below it):
            // LC on (0x420) = accent edge + caption, engaged (0x225) = stronger edge + tinted face; e-brake = a tag
            // above the gear digit. Held flags from a dropped link aren't shown.
            val lcOn = !stale && (vs.launchControlActive || vs.launchControlEngaged)
            val lcEngaged = !stale && vs.launchControlEngaged
            val ebrakeOn = !stale && vs.eBrake
            val gearShape = RoundedCornerShape(16.dp)
            Column(
                Modifier.fillMaxWidth()
                    .then(pageEntrance(1, pageEntered))
                    .instrumentSurface(radius = 16.dp)
                    .then(if (lcEngaged) Modifier.background(accent.copy(alpha = pillBgAlpha(0.10f)), gearShape) else Modifier)
                    .then(if (lcOn) Modifier.border(if (lcEngaged) 2.dp else 1.dp, accent.copy(alpha = borderAlpha(if (lcEngaged) 0.95f else 0.7f)), gearShape)
                          else Modifier)
                    .semantics { if (lcOn || ebrakeOn) stateDescription = listOfNotNull(
                        if (ebrakeOn) "E-brake on" else null,
                        if (lcEngaged) "Launch control engaged" else if (lcOn) "Launch control on" else null).joinToString(", ") }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                // Disconnected: last RPM is frozen — never leave shift LEDs lit on stale data.
                ShiftLightBar(rpm = if (stale) 0f else vs.rpm.toFloat(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().height(gearAreaH),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PedalBar("BRAKE", brake, Orange, Modifier.fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    // E-brake tag: overlaid in the space above the digit, so it never moves the gear or caption
                    if (ebrakeOn) EBrakeTag(Modifier.align(Alignment.TopCenter))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedContent(
                            modifier = Modifier.height(gearDigitH),
                            // Gear is derived from RPM ÷ speed — meaningless once those are frozen.
                            targetState = if (stale) "—" else vs.gearDisplay,
                            transitionSpec = {
                                (slideInVertically { -it } + fadeIn()) togetherWith
                                (slideOutVertically { it } + fadeOut())
                            },
                            label = "gear"
                        ) { gear ->
                            HeroNum(gear, gearSize, if (gearActive) Frost else Dim)
                        }
                        val tinted = isSportish && p.gearModeTint && vs.isConnected
                        val gearLabelColor by animateColorAsState(
                            if (tinted) modeColor else Dim, tween(600), label = "gearLbl")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (lcOn) {
                                // Same caption line, same height: launch control replaces "GEAR" while it is on
                                androidx.compose.material3.Text(
                                    if (lcEngaged) "LAUNCH CONTROL · ENGAGED" else "LAUNCH CONTROL · ON",
                                    color = actionInk(accent), fontFamily = JetBrainsMonoFamily, fontSize = 10.sp,
                                    fontWeight = if (lcEngaged) FontWeight.Bold else FontWeight.SemiBold,
                                    letterSpacing = 0.4.sp, maxLines = 1, softWrap = false)
                            } else {
                                // 12 dp inside the caption's line height — the module's fixed height doesn't change
                                TelemetryIcon(RsIcon.GEAR, if (gearActive) IconTone.NORMAL else IconTone.UNAVAILABLE, 12.dp)
                                Spacer(Modifier.width(4.dp))
                                InstrumentLabel(if (tinted) "GEAR · ${vs.driveMode.label.uppercase()}" else "GEAR", color = gearLabelColor)
                            }
                        }
                    }
                    }
                    PedalBar("THROTTLE", thr, accent, Modifier.fillMaxHeight())
                }
            }

            VehicleStatusPanel(status, readiness.vehicleAdvisory(), onOpenThermal, Modifier.then(pageEntrance(2, pageEntered)))

            // Torque split / G / torque are per-frame dynamics — frozen values mislead once stale.
            ChassisPanel(if (stale) vs.copy(awdLeftTorque = 0.0, awdRightTorque = 0.0, torqueAtTrans = 0.0) else vs, stale)

            if (vs.clutchPedalPct > 0.1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("CLUTCH", "${vs.clutchPedalPct.roundToInt()}%", modifier = Modifier.weight(1f))
                }
            }

            // F2: fuel economy — always present (data arriving / leaving never shows, hides or collapses it;
            // only the user's header tap does). Unavailable metrics read "—" with their unit, not 0.
            // Calculations and validity are FuelEconomy's; this only decides what to print.
            var econExpanded by rememberSectionExpanded("DRIVE_ECONOMY")
            val mph = p.speedUnit == "MPH"
            val valid = econState.isValid
            fun num(v: Double, fmt: String, ok: Boolean) = if (ok) fmt.format(v) else "—"
            // IDLE only with the engine running (PCM fast-poll rule) and the car stopped, while connected.
            val idle = vs.isConnected && vs.rpm > ObdConstants.FAST_POLL_MIN_RPM && vs.speedKph < 2.0
            val instantOk = valid && vs.isConnected && (if (mph) econState.instantMpg else econState.instantL100km) > 0
            EconomyPanel(
                expanded = econExpanded,
                onToggle = { econExpanded = !econExpanded },
                // Session aggregates stay readable offline, dimmed like other last-known values.
                dimmed = !vs.isConnected,
                cells = listOf(
                    when {
                        idle -> Triple("IDLE", num(econState.idleFuelLPerHr, "%.1f", valid && econState.idleFuelLPerHr > 0), "L/hr")
                        mph  -> Triple("INSTANT", num(econState.instantMpg, "%.1f", instantOk), "MPG")
                        else -> Triple("INSTANT", num(econState.instantL100km, "%.1f", instantOk), "L/100 km")
                    },
                    if (mph) Triple("AVERAGE", num(econState.avgMpg, "%.1f", valid && econState.avgMpg > 0), "MPG")
                    else Triple("AVERAGE", num(econState.avgL100km, "%.1f", valid && econState.avgL100km > 0), "L/100 km"),
                    if (mph) Triple("TO EMPTY", num(econState.distanceToEmptyKm * UnitConversions.KM_TO_MI, "%.0f", valid && econState.distanceToEmptyKm > 0), "mi")
                    else Triple("TO EMPTY", num(econState.distanceToEmptyKm, "%.0f", valid && econState.distanceToEmptyKm > 0), "km"),
                    Triple("FUEL USED", num(econState.fuelUsedL, "%.1f", valid), "L"),
                ),
            )
        }
    }
}

/** ECONOMY: instant / average / distance to empty / fuel used in one panel; the header row collapses it. */
@Composable private fun EconomyPanel(
    expanded: Boolean, onToggle: () -> Unit, cells: List<Triple<String, String, String>>, dimmed: Boolean = false,
) {
    val chevron by animateFloatAsState(if (expanded) 0f else -90f, label = "econChevron")
    Column(Modifier.fillMaxWidth().instrumentSurface()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clickable(onClickLabel = if (expanded) "Collapse economy" else "Expand economy") { onToggle() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InstrumentLabel("ECONOMY")
            Spacer(Modifier.weight(1f))
            MonoLabel("\u25BE", 12.sp, Dim, modifier = Modifier.graphicsLayer { rotationZ = chevron })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                cells.forEachIndexed { i, (label, value, unit) ->
                    if (i > 0) PanelDivider(Modifier.padding(horizontal = 8.dp))
                    Column(Modifier.weight(1f)) {
                        InstrumentLabel(label)
                        Spacer(Modifier.height(2.dp))
                        DataNum(value, 20.sp, if (dimmed || value == "—") Dim else Frost)
                        InstrumentMeta(unit)
                    }
                }
            }
        }
    }
}

/** Vertical pedal gauge flanking the gear (fill from the bottom, % on top, name below). */
@Composable private fun PedalBar(label: String, pct: Double, color: Color, modifier: Modifier = Modifier) {
    val frac by animateFloatAsState((pct / 100.0).toFloat().coerceIn(0f, 1f),
        tween(120), label = "pedal$label")
    Column(modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DataNum("${pct.roundToInt()}%", 15.sp, if (pct >= 1.0) Frost else Dim)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.weight(1f).width(10.dp)
                .background(Surf3, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(frac).background(color, RoundedCornerShape(5.dp)))
        }
        Spacer(Modifier.height(6.dp))
        MonoLabel(label, 9.sp, Mid, letterSpacing = 0.2.sp)
    }
}

/**
 * One vehicle condition past an existing limit. Shared by the DRIVE priority banner (top item)
 * and the VEHICLE panel (coloured cells + chips for conditions that have no cell).
 */
private data class VehicleAlert(
    val title: String,           // "OIL CRITICAL"
    val value: String,           // "268°F"
    val level: Level,
    @DrawableRes val icon: Int,
    val chip: String? = null,    // chip text when the condition has no VEHICLE cell
    /** Temperature alert — on the status row the readiness verdict speaks for it. */
    val temp: Boolean = false,
)

private enum class LinkState { LIVE, RECONNECTING, OFFLINE }

private data class VehicleStatus(
    val connected: Boolean,
    val oil: String, val oilLvl: Level,
    val cool: String, val coolLvl: Level,
    val fuel: String, val fuelLvl: Level,
    val batt: String, val battLvl: Level,
    /** Highest severity first (CRIT before WARN), then oil → coolant → PTU → RDU → tyre → fuel → knock. */
    val alerts: List<VehicleAlert>,
)

/** Every rule here is an existing limit (THERMAL preset, TPMS low / warn psi, fuel < 10 %, knock counter). */
private fun vehicleStatus(vs: VehicleState, p: UserPrefs, knockCount: Int): VehicleStatus {
    val connected = vs.isConnected
    fun avail(c: Double, tag: String) = vs.tempAvail(c, tag)
    fun temp(c: Double) = if (c > -90) "${p.displayTemp(c)}${p.tempLabel}" else "— —"
    val oilLvl  = tempLevel(vs.oilTempC, p.oilWarnC, p.oilCritC, avail(vs.oilTempC, "oilTempC"))
    val coolLvl = tempLevel(vs.coolantTempC, p.coolWarnC, p.coolCritC, avail(vs.coolantTempC, "coolantTempC"))
    val hasFuel = vs.fuelLevelPct > 0
    val fuelLvl = when {
        !hasFuel -> Level.NONE
        !connected -> Level.STALE
        vs.fuelLevelPct < 10.0 -> Level.WARN          // DRIVE "LOW FUEL" rule (< 10 %)
        else -> Level.NORMAL
    }
    val hasBatt = vs.batteryVoltage > 0
    val battLvl = when { !hasBatt -> Level.NONE; !connected -> Level.STALE; else -> Level.NORMAL }
    val fuelStr = if (hasFuel) "${vs.fuelLevelPct.roundToInt()}%" else "— —"

    fun hotTitle(name: String, l: Level) = if (l == Level.CRIT) "$name CRITICAL" else "$name HOT"
    val alerts = buildList {
        if (oilLvl.isAlert) add(VehicleAlert(hotTitle("OIL", oilLvl), temp(vs.oilTempC), oilLvl, R.drawable.ic_tt_oil, temp = true))
        if (coolLvl.isAlert) add(VehicleAlert(hotTitle("COOLANT", coolLvl), temp(vs.coolantTempC), coolLvl, R.drawable.ic_tt_coolant, temp = true))
        if (connected) {
            listOf(
                Triple("PTU", vs.ptuTempC, Triple("ptuTempC", p.ptuWarnC, p.ptuCritC)),
                Triple("RDU", vs.rduTempC, Triple("rduTempC", p.rduWarnC, p.rduCritC)),
            ).forEach { (name, c, lim) ->
                val lvl = tempLevel(c, lim.second, lim.third, avail(c, lim.first))
                if (lvl.isAlert) add(VehicleAlert(hotTitle(name, lvl), temp(c), lvl, R.drawable.ic_tt_coolant, chip = "$name ${temp(c)}", temp = true))
            }
            if (vs.hasTpmsData) {
                // Same classification as THERMAL's tyre cards (tireLevel) — including over-inflation past High,
                // which DRIVE used to ignore (ALL NORMAL while THERMAL showed a tyre critical).
                tyreIssues(
                    listOf("FL" to vs.tirePressLF, "FR" to vs.tirePressRF, "RL" to vs.tirePressLR, "RR" to vs.tirePressRR),
                    p.tireLowPsi.toDouble(), p.tireWarnPsi.toDouble(), p.tireHighPsi.toDouble(),
                ).forEach { (pos, psi, issue) ->
                    val v = "${p.displayTire(psi)} ${p.tireLabel}"
                    when (issue) {
                        TyreIssue.LOW -> add(VehicleAlert("$pos TYRE LOW", v, Level.CRIT, R.drawable.ic_tt_tyre, chip = "$pos $v LOW"))
                        TyreIssue.CAUTION -> add(VehicleAlert("$pos TYRE PRESSURE", v, Level.WARN, R.drawable.ic_tt_tyre, chip = "$pos $v"))
                        TyreIssue.HIGH -> add(VehicleAlert("$pos TYRE HIGH", v, Level.CRIT, R.drawable.ic_tt_tyre, chip = "$pos $v HIGH"))
                    }
                }
            }
        }
        if (fuelLvl == Level.WARN) add(VehicleAlert("FUEL LOW", fuelStr, Level.WARN, R.drawable.ic_tt_fuel))
        if (knockCount > 0) add(VehicleAlert("KNOCK DETECTED", "×$knockCount", Level.WARN, R.drawable.ic_tt_alert, chip = "KNOCK ×$knockCount"))
    }.sortedBy { if (it.level == Level.CRIT) 0 else 1 }   // stable: keeps the priority order within a level

    return VehicleStatus(
        connected,
        temp(vs.oilTempC), oilLvl, temp(vs.coolantTempC), coolLvl,
        fuelStr, fuelLvl, if (hasBatt) "${"%.1f".format(vs.batteryVoltage)}V" else "— —", battLvl,
        alerts,
    )
}

/**
 * Permanent status line at the top of DRIVE — the same 48 dp in every state, so warnings appearing
 * or clearing only change its content and never push the instruments. Order: OVERHEATING › critical
 * tyre alerts › TEMPERATURE HIGH / HEAT RISING › other alerts (tyre caution, fuel, knock) › the
 * rest of the readiness verdict. Temperatures come only from [ThermalVerdict] (the THERMAL card's
 * verdict, with the same holds); the other alerts hold 3 s after clearing so a reading hovering at
 * a limit doesn't flicker. Link down: RECONNECTING, or offline the TAP TO CONNECT action — here
 * rather than a strip above the page, so a drop / recovery only changes this row's content.
 * The headline is measured first; the reason gives way (ellipsis) on narrow phones. Tap opens THERMAL.
 */
@Composable private fun StatusLine(
    alerts: List<VehicleAlert>, rtr: ThermalVerdict, p: UserPrefs, link: LinkState,
    onOpen: () -> Unit, onConnect: () -> Unit,
) {
    var held by remember { mutableStateOf(alerts) }
    LaunchedEffect(alerts) {
        if (alerts.isEmpty()) kotlinx.coroutines.delay(3_000)
        held = alerts
    }
    // The 3 s alert hold is for flicker between live frames — a dropped link replaces it at once.
    val others = if (link == LinkState.LIVE) held else emptyList()
    if (link == LinkState.OFFLINE) { ConnectActionRow(onConnect); return }
    val heat = link == LinkState.LIVE && rtr.state.isHeat
    val top: VehicleAlert? = when {
        rtr.state == ThermalState.OVERHEATING && heat -> null
        others.any { it.level == Level.CRIT } -> others.first { it.level == Level.CRIT }
        heat -> null
        else -> others.firstOrNull()
    }
    // "+N": other active conditions — alerts not shown, plus the verdict's other deciding / missing readings.
    val moreCount = if (top == null) others.size + (if (heat || rtr.state == ThermalState.CHECKING) rtr.extraCount else 0)
                    else others.size - 1 + (if (heat) 1 else 0)
    val shape = RoundedCornerShape(12.dp)
    val accent = LocalThemeAccent.current
    // Colour = state: orange at crit, amber at warn / forecast; warming is a normal phase (accent).
    val (accentColor, title, value) = when {
        link == LinkState.RECONNECTING -> Triple(Warn, "RECONNECTING", "")
        top != null -> Triple(levelColor(top.level), top.title, top.value)
        else -> Triple(
            when (rtr.state) {
                ThermalState.OVERHEATING -> Orange
                ThermalState.TEMPERATURE_HIGH, ThermalState.HEAT_RISING, ThermalState.CHECKING -> Warn
                ThermalState.ENGINE_WARMING, ThermalState.DRIVETRAIN_WARMING -> accent
                ThermalState.TEMPS_READY -> Ok
                ThermalState.NO_LIVE_DATA -> Dim
            },
            rtr.state.headline, rtr.shortReason(p),
        )
    }
    val alerting = top != null || (heat && link == LinkState.LIVE)
    val heatIcon = if (rtr.deciding.firstOrNull()?.sensor == ThermalSensor.OIL) R.drawable.ic_tt_oil else R.drawable.ic_tt_coolant
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .background(InstrumentFill, shape)
            .then(if (alerting) Modifier.background(accentColor.copy(alpha = if (isDayModeNow()) 0.16f else 0.14f), shape) else Modifier)
            .border(if (alerting) 1.dp else Tokens.CardBorder,
                if (alerting) accentColor.copy(alpha = borderAlpha(0.8f))
                else if (isDayModeNow()) Brd.copy(alpha = 0.75f) else Brd.copy(alpha = 0.15f), shape)
            .then(if (link == LinkState.LIVE) Modifier.clickable(onClickLabel = "Open THERMAL") { onOpen() } else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            top != null -> Telltale(top.icon, accentColor, 22.dp, contentDescription = null)
            alerting -> Telltale(heatIcon, accentColor, 22.dp, contentDescription = null)
            else -> Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(9.dp).background(accentColor, androidx.compose.foundation.shape.CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.Text(
            title, color = textColor(if (alerting) accentColor else Frost), fontFamily = RajdhaniFamily,
            fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.5.sp, maxLines = 1, softWrap = false,
        )
        Spacer(Modifier.width(10.dp))
        // The reason takes all the width the headline leaves (it used to share it with the spacer).
        if (top != null) { DataNum(value, 17.sp, Frost); Spacer(Modifier.weight(1f)) }
        else androidx.compose.material3.Text(
            value, color = textColor(if (alerting) Frost else Dim), fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp, letterSpacing = 0.1.sp, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (moreCount > 0 && link == LinkState.LIVE) {
            Box(
                Modifier.background(Surf3, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)
            ) { MonoLabel("+$moreCount", 11.sp, Frost, FontWeight.Bold) }
            Spacer(Modifier.width(8.dp))
        }
        if (link == LinkState.LIVE) MonoLabel("›", 16.sp, Dim)
    }
}

/** Offline: the whole 48 dp row is the connect action — accent tint, label in [actionInk] (dark on DAY). */
@Composable private fun ConnectActionRow(onConnect: () -> Unit) {
    val accent = LocalThemeAccent.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .background(InstrumentFill, shape)
            .background(accent.copy(alpha = if (isDayModeNow()) 0.14f else 0.12f), shape)
            .border(1.dp, accent.copy(alpha = borderAlpha(0.7f)), shape)
            .clickable(onClickLabel = "Connect to the adapter") { onConnect() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        androidx.compose.material3.Text(
            "TAP TO CONNECT", color = actionInk(accent), fontFamily = RajdhaniFamily,
            fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 1.sp, maxLines = 1
        )
    }
}

/**
 * VEHICLE panel: the readings that decide whether to keep pushing — oil, coolant, fuel, battery —
 * plus chips for active conditions that have no cell (PTU / RDU temps, tyre pressure, knock).
 * Narrow screens (< 340 dp of panel) switch the cells to a 2 × 2 grid. Tap opens THERMAL.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun VehicleStatusPanel(
    st: VehicleStatus, advisory: Pair<String, Level>?, onOpenThermal: () -> Unit, modifier: Modifier = Modifier,
) {
    fun tempSub(l: Level) = when (l) {
        Level.COLD -> "COLD"; Level.WARN -> "HIGH"; Level.CRIT -> "CRITICAL"; Level.STALE -> "STALE"
        Level.NONE -> if (st.connected) "WAITING" else ""; Level.NORMAL -> ""
    }
    val fuelSub = when (st.fuelLvl) { Level.WARN -> "LOW"; Level.STALE -> "STALE"; else -> "" }
    val battSub = if (st.battLvl == Level.STALE) "STALE" else ""
    // Only reserve the tag line when a cell has a tag — otherwise it's dead space.
    val subs = listOf(tempSub(st.oilLvl), tempSub(st.coolLvl), fuelSub, battSub)
    fun sub(i: Int) = if (subs.any { it.isNotEmpty() }) subs[i] else null
    val chips = st.alerts.filter { it.chip != null }
    val panelLvl = worstLevel(st.alerts.map { it.level })

    Column(
        modifier.fillMaxWidth()
            .instrumentSurface(panelLvl)
            .clickable(onClickLabel = "Open THERMAL") { onOpenThermal() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InstrumentLabel("VEHICLE")
            Spacer(Modifier.weight(1f))
            val summary = when {
                st.alerts.isNotEmpty() -> st.alerts.first().title + if (st.alerts.size > 1) "  +${st.alerts.size - 1}" else ""
                st.oilLvl == Level.NONE && st.fuelLvl == Level.NONE -> ""
                // Held / aged readings are not a current all-clear: ALL NORMAL only when no cell is STALE
                // (whenDisconnected + tempAvailFor 60 s decide that, as for the cells themselves).
                !st.connected -> "OFFLINE · LAST KNOWN"
                listOf(st.oilLvl, st.coolLvl, st.fuelLvl, st.battLvl).any { it == Level.STALE } -> "STALE DATA"
                // The shared readiness verdict (forecast, recovery hold, missing data) — never ALL NORMAL under it
                advisory != null -> advisory.first
                else -> "ALL NORMAL"
            }
            val advisoryShown = st.alerts.isEmpty() && st.connected && advisory != null && summary == advisory.first
            val summaryColor = when {
                st.alerts.isNotEmpty() -> levelColor(panelLvl)
                advisoryShown && advisory!!.second.isAlert -> levelColor(advisory.second)
                else -> Dim
            }
            MonoLabel(summary, 10.sp, summaryColor,
                if (st.alerts.isNotEmpty() || advisoryShown) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.4.sp)
            Spacer(Modifier.width(6.dp))
            MonoLabel("›", 13.sp, Dim)
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cells: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> StatusCell(RsIcon.OIL, "OIL", st.oil, st.oilLvl, m, sub(0)) },
                { m -> StatusCell(RsIcon.COOLANT, "COOLANT", st.cool, st.coolLvl, m, sub(1)) },
                { m -> StatusCell(RsIcon.FUEL, "FUEL", st.fuel, st.fuelLvl, m, sub(2)) },
                { m -> StatusCell(RsIcon.BATTERY, "BATT", st.batt, st.battLvl, m, sub(3)) },
            )
            if (maxWidth < 340.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    cells.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            pair[0](Modifier.weight(1f))
                            PanelDivider(Modifier.padding(horizontal = 8.dp))
                            pair[1](Modifier.weight(1f))
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    cells.forEachIndexed { i, cell ->
                        if (i > 0) PanelDivider(Modifier.padding(horizontal = 8.dp))
                        cell(Modifier.weight(if (i < 2) 1f else 0.8f))
                    }
                }
            }
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { AlertChip(it.chip!!, it.level, it.icon) }
            }
        }
    }
}

/** AWD split + per-frame dynamics in one panel (was a split card + three separate cells). */
@Composable private fun ChassisPanel(vs: VehicleState, stale: Boolean) {
    Column(
        Modifier.fillMaxWidth()
            .instrumentSurface()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InstrumentLabel("AWD SPLIT")
            Spacer(Modifier.weight(1f))
            InstrumentMeta(
                if (stale) "L — · R — Nm" else "L ${vs.awdLeftTorque.roundToInt()} · R ${vs.awdRightTorque.roundToInt()} Nm"
            )
        }
        Spacer(Modifier.height(8.dp))
        AwdSplitBar(vs, stale)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.4f))))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            MiniReadout("LAT G",  if (stale) "— —" else "${"%.2f".format(vs.lateralG)}g",      Modifier.weight(1f))
            MiniReadout("LON G",  if (stale) "— —" else "${"%.2f".format(vs.longitudinalG)}g", Modifier.weight(1f))
            MiniReadout("TORQUE", if (stale) "— —" else "${vs.torqueAtTrans.roundToInt()} Nm",  Modifier.weight(1f))
        }
    }
}

/** Front/rear split bar. [stale]: no live split — dashes and an empty track, not a fake 100 / 0. */
@Composable fun AwdSplitBar(vs: VehicleState, stale: Boolean = false) {
    val accent = LocalThemeAccent.current
    val rearColor = accentMid()
    val rearPct  = vs.rearTorquePct.coerceIn(0.0, 100.0).toFloat()
    val frontPct = (100f - rearPct).coerceIn(0.01f, 99.99f)
    val rearF    = rearPct.coerceIn(0.01f, 99.99f)

    val torqueDelta = kotlin.math.abs(vs.awdLeftTorque - vs.awdRightTorque).toFloat()
    val flowSpeed = (2000 - (torqueDelta * 10).toInt().coerceIn(0, 1200)).coerceIn(800, 2000)
    val flowProgress by rememberInfiniteTransition(label = "awdFlow").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(flowSpeed, easing = LinearEasing), RepeatMode.Restart),
        label = "awdFlowP"
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column {
            MonoLabel("FRONT", 9.sp, Dim, letterSpacing = 0.3.sp)
            DataNum(if (stale) "—" else "${(100 - rearPct).roundToInt()}%", 18.sp, if (stale) Dim else Frost)
        }
        Box(Modifier.weight(1f).padding(horizontal = 12.dp).height(8.dp)) {
            if (!stale) Row(Modifier.matchParentSize().background(Surf3, RoundedCornerShape(4.dp))) {
                Box(Modifier.weight(frontPct).fillMaxHeight()
                    .background(accent, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
                Spacer(Modifier.width(2.dp))
                Box(Modifier.weight(rearF).fillMaxHeight()
                    .background(rearColor, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
            }
            else Box(Modifier.matchParentSize().background(Surf3, RoundedCornerShape(4.dp)))
            if (!stale && vs.totalRearTorque > 5) {
                // V6: dot count scales with torque intensity
                val totalTorque = vs.totalRearTorque.toFloat()
                val dotCount = when {
                    totalTorque > 800 -> 5
                    totalTorque > 400 -> 4
                    totalTorque > 100 -> 3
                    else -> 2
                }
                val dotColor = Frost
                Canvas(Modifier.matchParentSize()) {
                    val dotRadius = 1.6.dp.toPx()
                    val rearDominant = rearPct > 55f
                    for (i in 0 until dotCount) {
                        val phase = (flowProgress + i.toFloat() / dotCount) % 1f
                        val x = if (rearDominant) size.width * (1f - phase) else size.width * phase
                        val dotAlpha = (0.5f * (1f - kotlin.math.abs(phase - 0.5f) * 2f)).coerceIn(0f, 0.5f)
                        drawCircle(dotColor.copy(alpha = dotAlpha), dotRadius, Offset(x, size.height / 2f))
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            MonoLabel("REAR", 9.sp, Dim, letterSpacing = 0.3.sp)
            DataNum(if (stale) "—" else "${rearPct.roundToInt()}%", 18.sp, if (stale) Dim else Frost)
        }
    }
}

internal fun tempColorShade(c: Double, warnC: Double, critC: Double) = when {
    c <= 0      -> Dim
    c >= critC  -> Orange
    c >= warnC  -> Warn
    c >= warnC * 0.6 -> Ok
    else        -> Frost
}

/** E-brake tag above the gear digit: orange (the e-brake colour), compact, never in the layout flow. */
@Composable private fun EBrakeTag(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(5.dp)
    Box(modifier.padding(top = 2.dp).background(Orange.copy(alpha = pillBgAlpha(0.14f)), shape)
        .border(1.dp, Orange.copy(alpha = borderAlpha(0.7f)), shape).padding(horizontal = 7.dp, vertical = 2.dp)) {
        MonoLabel("E-BRAKE", 10.sp, textColor(Orange), FontWeight.Bold, 0.4.sp)
    }
}
