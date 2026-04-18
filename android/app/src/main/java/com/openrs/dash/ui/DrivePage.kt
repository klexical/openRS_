package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
@Composable fun DrivePage(vs: VehicleState, p: UserPrefs) {
    var pageEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pageEntered = true }
    val accent = LocalThemeAccent.current
    val (_, boostLbl) = p.displayBoost(vs.boostKpa)

    // B1: raw live values for hero numerics — spring smoothing removed so the
    // digits are 1:1 with the CAN frame. Bar values follow suit for consistency.
    // Fuel + battery keep an 800 ms tween: they update slowly and the smooth
    // fill on BarCard reads better than a snap.
    val thr = (if (vs.throttleHasSource) vs.throttlePct else vs.accelPedalPct)
        .coerceIn(0.0, 100.0)
    val brake = vs.brakePressure.coerceIn(0.0, 100.0)
    val animFuel by animateFloatAsState(vs.fuelLevelPct.toFloat(), tween(800), label = "fuel")
    val animBatt by animateFloatAsState(vs.batteryVoltage.toFloat(), tween(800), label = "batt")

    val boostSpark  = remember { SparklineData(60) }
    val rpmSpark    = remember { SparklineData(60) }
    val speedSpark  = remember { SparklineData(60) }
    val thrSpark    = remember { SparklineData(60) }
    val brakeSpark  = remember { SparklineData(60) }
    val lastSample  = remember { mutableLongStateOf(0L) }
    SideEffect {
        val now = vs.lastUpdate
        if (now - lastSample.longValue >= 250L) {
            lastSample.longValue = now
            boostSpark.push(vs.boostKpa.toFloat())
            rpmSpark.push(vs.rpm.toFloat())
            speedSpark.push(vs.speedKph.toFloat())
            thrSpark.push(thr.toFloat())
            brakeSpark.push(brake.toFloat())
        }
    }

    // A4: rebuild the List<Float> snapshots only when a new sample lands
    // (≤ 4 Hz), not on every CAN-driven recomposition (~60 Hz).
    val sampleKey = lastSample.longValue
    val boostSnap = remember(sampleKey) { boostSpark.snapshot() }
    val rpmSnap   = remember(sampleKey) { rpmSpark.snapshot() }
    val speedSnap = remember(sampleKey) { speedSpark.snapshot() }
    val thrSnap   = remember(sampleKey) { thrSpark.snapshot() }
    val brakeSnap = remember(sampleKey) { brakeSpark.snapshot() }

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

    // ── Adaptive density: once speed exceeds ~10 mph for 3s, collapse
    // secondary content and inflate the gear digit. Hysteresis returns to
    // full density below ~5 mph. Toggle via UserPrefs.driveAutoZoom.
    var zoomed by remember { mutableStateOf(false) }
    var aboveSince by remember { mutableLongStateOf(0L) }
    SideEffect {
        if (!p.driveAutoZoom) {
            if (zoomed) zoomed = false
            if (aboveSince != 0L) aboveSince = 0L
            return@SideEffect
        }
        val now = System.currentTimeMillis()
        when {
            vs.speedKph < 8.0 -> {
                if (aboveSince != 0L) aboveSince = 0L
                if (zoomed) zoomed = false
            }
            vs.speedKph > 16.0 -> {
                if (aboveSince == 0L) aboveSince = now
                else if (!zoomed && now - aboveSince > 3000L) zoomed = true
            }
        }
    }
    // rc.2 hierarchy: drop resting gear size 72→52sp so at idle the gear
    // stops dominating. BOOST/RPM/SPEED become the at-rest personality;
    // gear only grows to 140sp once the car is actually moving.
    val gearSizeSp by animateFloatAsState(
        if (zoomed) 140f else 52f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "gearSize"
    )
    val gearPadDp by animateFloatAsState(
        if (zoomed) 42f else 12f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "gearPad"
    )

    // V8 — Gear-change pop: 1.05× scale bounce + haptic tick on derivedGear delta.
    // Skip initial composition (first value == previous).
    val haptic = LocalHapticFeedback.current
    val gearPopTarget = remember { mutableStateOf(1f) }
    val gearPopScale by animateFloatAsState(
        targetValue = gearPopTarget.value,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "gearPop"
    )
    var lastGear by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(vs.derivedGear) {
        val g = vs.derivedGear
        if (lastGear != null && lastGear != g && g > 0) {
            gearPopTarget.value = 1.05f
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            kotlinx.coroutines.delay(180)
            gearPopTarget.value = 1f
        }
        lastGear = g
    }

    val scrollState = rememberScrollState()

    // ── F7: live recording stats ────────────────────────────────────────
    val driveState by OpenRSDashApp.instance.driveState.collectAsState()
    // ── F2: fuel economy ─────────────────────────────────────────────────
    val econState by FuelEconomy.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(start = PagePad, end = PagePad, top = PagePad, bottom = PagePad + Tokens.NavBarHeight),
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

            // C2: hero border alpha scales with valueFraction so the card
            // gains visual weight under active input and stays quiet at rest.
            val boostFrac = (vs.boostPsi.toFloat() / 30f).coerceIn(0f, 1f)
            val rpmFrac = (vs.rpm.toFloat() / 6800f).coerceIn(0f, 1f)
            val speedFrac = (vs.speedKph.toFloat() / 250f).coerceIn(0f, 1f)

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

            // rc.2 hierarchy: BOOST is the personality of this car — promote
            // it with extra weight (1.25 vs 1.0) and a larger value font (36sp
            // vs 24sp). RPM and SPEED become supporting heroes.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max)
                    .alpha((heroAlpha - breathAlpha).coerceIn(0f, 1f))
                    .then(pageEntrance(0, pageEntered)),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeroCard(
                    unit = "RPM", value = rpmStr, label = "ENGINE",
                    valueColor = Orange,
                    borderAccent = Orange.copy(alpha = 0.15f + 0.2f * rpmFrac),
                    peak = "▲ ${vs.peakRpm.toInt()}",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    sparklineData = rpmSnap,
                    valueFraction = rpmFrac,
                    peakHoldValue = vs.peakRpm.toInt().toString(),
                    valueFontSize = 24.sp
                )
                HeroCard(
                    unit = boostLbl, value = boostVal, label = "BOOST",
                    valueColor = Warn,
                    borderAccent = Warn.copy(alpha = 0.15f + 0.2f * boostFrac),
                    peak = "▲ ${"%.1f".format(vs.peakBoostPsi)}",
                    modifier = Modifier.weight(1.25f).fillMaxHeight(),
                    sparklineData = boostSnap,
                    valueFraction = boostFrac,
                    peakHoldValue = "%.1f".format(vs.peakBoostPsi),
                    valueFontSize = 36.sp
                )
                HeroCard(
                    unit = p.speedLabel, value = speedStr, label = "SPEED",
                    valueColor = accent,
                    borderAccent = accent.copy(alpha = 0.15f + 0.2f * speedFrac),
                    peak = "▲ ${p.displaySpeed(vs.peakSpeedKph)}",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    sparklineData = speedSnap,
                    valueFraction = speedFrac,
                    peakHoldValue = p.displaySpeed(vs.peakSpeedKph),
                    valueFontSize = 24.sp
                )
            }

            // Disconnected state is communicated by the header pill + adapter row
            // on GARAGE. Duplicating the banner here was double-signalling.

            ShiftLightBar(rpm = vs.rpm.toFloat(), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).then(pageEntrance(1, pageEntered)))

            val gearActive = vs.isConnected && (vs.rpm > 0 || vs.speedKph > 0)
            // B.6: mode color for GEAR panel accents (label tint + hairline)
            val isSportish = vs.driveMode in listOf(DriveMode.SPORT, DriveMode.TRACK, DriveMode.DRIFT)
            val modeColor = when (vs.driveMode) {
                DriveMode.SPORT -> Ok
                DriveMode.TRACK -> Warn
                DriveMode.DRIFT -> Orange
                else -> accent
            }
            // ULTRA: 0.04 gradient reads as banding on pure-black — drop to 0.02
            val gearGradientAlpha = if (gearActive) {
                if (isUltraNightNow()) 0.02f else 0.04f
            } else 0f
            Box(
                Modifier.fillMaxWidth()
                    .then(pageEntrance(2, pageEntered))
                    .background(
                        Brush.verticalGradient(listOf(accent.copy(alpha = gearGradientAlpha), Surf2)),
                        RoundedCornerShape(16.dp)
                    )
                    .border(1.dp, if (gearActive) accent.copy(alpha = 0.25f) else Brd.copy(alpha = borderAlpha(0.15f)), RoundedCornerShape(16.dp))
                    // B.6: inner vignette — radial gradient from transparent center to
                    // Bg at bottom corners, pushes the gear letter forward without texture
                    .drawBehind {
                        val vignette = Brush.radialGradient(
                            listOf(Color.Transparent, Bg.copy(alpha = 0.25f)),
                            center = Offset(size.width / 2f, size.height * 0.3f),
                            radius = size.maxDimension * 0.8f
                        )
                        drawRect(vignette)
                    }
                    .padding(vertical = gearPadDp.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(
                        targetState = vs.gearDisplay,
                        transitionSpec = {
                            (slideInVertically { -it } + fadeIn()) togetherWith
                            (slideOutVertically { it } + fadeOut())
                        },
                        label = "gear"
                    ) { gear ->
                        HeroNum(
                            gear, gearSizeSp.sp, Frost,
                            modifier = Modifier.graphicsLayer {
                                scaleX = gearPopScale
                                scaleY = gearPopScale
                            }
                        )
                    }
                    // B.6: 1dp hairline between letter and label — takes mode color
                    if (isSportish && p.gearModeTint) {
                        Box(Modifier.fillMaxWidth(0.3f).height(1.dp)
                            .background(modeColor.copy(alpha = 0.5f)))
                    }
                    // B.6: GEAR label tints to mode color in SPORT/TRACK/DRIFT
                    val gearLabelTarget = if (isSportish && p.gearModeTint) modeColor.copy(alpha = 0.55f) else Dim
                    val gearLabelColor by animateColorAsState(gearLabelTarget, tween(600), label = "gearLbl")
                    MonoLabel("G E A R", 8.sp, gearLabelColor, letterSpacing = 4.sp)
                }
            }

            if (vs.launchControlActive) {
                Box(
                    Modifier.fillMaxWidth()
                        .background(Warn.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .border(1.dp, Warn.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("⚡ LAUNCH CONTROL ACTIVE", 12.sp, Warn, letterSpacing = 0.2.sp)
                }
            }

            AnimatedVisibility(
                visible = !zoomed,
                enter = fadeIn() + expandVertically(spring(stiffness = Spring.StiffnessLow)),
                exit = fadeOut() + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
            SectionLabel("INPUTS")
            val thrInt = thr.roundToInt()
            val brakeInt = brake.roundToInt()
            val thrFrac = (thr / 100.0).toFloat().coerceIn(0f, 1f)
            val brakeFrac = (brake / 100.0).toFloat().coerceIn(0f, 1f)
            // rc.2: collapsed to a single one-row gauge strip on every layout —
            // eliminates the 2×2 grid that the critique flagged as hierarchy noise.
            val fuelCritical = animFuel in 0.01f..9.99f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarCard(
                    name = "THROTTLE", value = "$thrInt%",
                    fraction = thrFrac,
                    barBrush = Brush.horizontalGradient(listOf(accent.copy(0.4f), accent)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = accent, sparklineData = thrSnap
                )
                BarCard(
                    name = "BRAKE", value = "$brakeInt%",
                    fraction = brakeFrac,
                    barBrush = Brush.horizontalGradient(listOf(Orange.copy(0.4f), Orange)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = Orange, sparklineData = brakeSnap
                )
                BarCard(
                    name = if (fuelCritical) "LOW FUEL" else "FUEL",
                    value = "${animFuel.roundToInt()}%",
                    fraction = (animFuel / 100f),
                    barBrush = Brush.horizontalGradient(listOf(Ok.copy(0.4f), Ok)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = Ok,
                    criticalPulse = fuelCritical
                )
                BarCard(
                    name = "BATTERY", value = "${"%.2f".format(animBatt)}V",
                    fraction = ((animBatt - 10f) / 6f).coerceIn(0f, 1f),
                    barBrush = Brush.horizontalGradient(listOf(Warn.copy(0.4f), Warn)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = Warn
                )
            }

            if (vs.clutchPedalPct > 0.1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataCell("CLUTCH", "${vs.clutchPedalPct.roundToInt()}%", modifier = Modifier.weight(1f))
                }
            }

            SectionLabel("AWD SPLIT")
            AwdSplitBar(vs)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataCell("LAT G",  "${"%.2f".format(vs.lateralG)}g",      modifier = Modifier.weight(1f))
                DataCell("LON G",  "${"%.2f".format(vs.longitudinalG)}g", modifier = Modifier.weight(1f))
                DataCell("TORQUE", "${vs.torqueAtTrans.roundToInt()} Nm",  modifier = Modifier.weight(1f))
            }

            // F2: fuel economy section — gated on valid readings
            if (econState.isValid) {
                var econExpanded by rememberSectionExpanded("DRIVE_ECONOMY")
                SectionLabel("ECONOMY", collapsible = true, expanded = econExpanded, onToggle = { econExpanded = !econExpanded })
                AnimatedVisibility(
                    visible = econExpanded,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessLow)),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val isIdle = vs.speedKph < 2.0 && econState.idleFuelLPerHr > 0
                            val instantLabel = if (isIdle) "IDLE" else "INSTANT"
                            val instantValue = if (isIdle) {
                                "${"%.1f".format(econState.idleFuelLPerHr)} L/hr"
                            } else if (p.speedUnit == "MPH") {
                                "${"%.1f".format(econState.instantMpg)} MPG"
                            } else {
                                "${"%.1f".format(econState.instantL100km)} L/100"
                            }
                            val avgValue = if (p.speedUnit == "MPH") {
                                "${"%.1f".format(econState.avgMpg)} MPG"
                            } else {
                                "${"%.1f".format(econState.avgL100km)} L/100"
                            }
                            val dteKm = econState.distanceToEmptyKm
                            val dteStr = if (p.speedUnit == "MPH") "${"%.0f".format(dteKm * UnitConversions.KM_TO_MI)} mi"
                                         else "${"%.0f".format(dteKm)} km"
                            val fuelStr = "${"%.1f".format(econState.fuelUsedL)} L"

                            DataCell(instantLabel, instantValue, modifier = Modifier.weight(1f))
                            DataCell("AVG", avgValue, modifier = Modifier.weight(1f))
                            DataCell("DTE", dteStr, modifier = Modifier.weight(1f))
                            DataCell("USED", fuelStr, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
                }
            }
        }
    }
}

@Composable fun AwdSplitBar(vs: VehicleState) {
    val accent = LocalThemeAccent.current
    val rearPct  = vs.rearTorquePct.coerceIn(0.0, 100.0).toFloat()
    val frontPct = (100f - rearPct).coerceIn(0.01f, 99.99f)
    val rearF    = rearPct.coerceIn(0.01f, 99.99f)
    // B.7: biased segment alpha lift when delta > 20
    val splitDelta = kotlin.math.abs(frontPct - rearF)
    val biased = splitDelta > 20f
    val frontAlpha = if (biased && rearF > frontPct) 0.5f else if (biased) 0.85f else 0.5f
    val rearAlpha  = if (biased && rearF > frontPct) 0.85f else if (biased) 0.5f else 0.5f

    val torqueDelta = kotlin.math.abs(vs.awdLeftTorque - vs.awdRightTorque).toFloat()
    val flowSpeed = (2000 - (torqueDelta * 10).toInt().coerceIn(0, 1200)).coerceIn(800, 2000)
    val flowProgress by rememberInfiniteTransition(label = "awdFlow").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(flowSpeed, easing = LinearEasing), RepeatMode.Restart),
        label = "awdFlowP"
    )

    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(12.dp))
            .border(CardBorder, Brd, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                MonoLabel("FRONT", 8.sp, Dim, letterSpacing = 0.12.sp)
                HeroNum("${(100 - rearPct).roundToInt()}%", 18.sp, accent)
            }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp).height(10.dp)) {
                Row(Modifier.matchParentSize().background(Surf3, RoundedCornerShape(5.dp))) {
                    Box(Modifier.weight(frontPct).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(accent, accent.copy(frontAlpha))),
                            RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp)))
                    Box(Modifier.weight(rearF).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Ok.copy(rearAlpha), Ok)),
                            RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)))
                }
                if (vs.totalRearTorque > 5) {
                    // V6: dot count + glow scales with torque intensity
                    val totalTorque = vs.totalRearTorque.toFloat()
                    val dotCount = when {
                        totalTorque > 800 -> 5
                        totalTorque > 400 -> 4
                        totalTorque > 100 -> 3
                        else -> 2
                    }
                    val highLoad = totalTorque > 500
                    Canvas(Modifier.matchParentSize()) {
                        val dotRadius = 2.dp.toPx()
                        val capRadius = 1.5.dp.toPx()
                        val rearDominant = rearPct > 55f
                        for (i in 0 until dotCount) {
                            val phase = (flowProgress + i.toFloat() / dotCount) % 1f
                            val x = if (rearDominant) size.width * (1f - phase) else size.width * phase
                            val dotAlpha = (0.4f * (1f - kotlin.math.abs(phase - 0.5f) * 2f)).coerceIn(0f, 0.4f)
                            val dotColor = if (rearDominant) Ok else accent
                            val center = Offset(x, size.height / 2f)
                            // V6: glow ring on high-load dots
                            if (highLoad) {
                                drawCircle(dotColor.copy(alpha = dotAlpha * 0.15f), dotRadius * 2.5f, center)
                            }
                            drawCircle(dotColor.copy(alpha = dotAlpha), dotRadius, center)
                            drawCircle(dotColor.copy(alpha = dotAlpha * 0.6f), capRadius, center.copy(y = center.y - 0.5.dp.toPx()))
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MonoLabel("REAR", 8.sp, Dim, letterSpacing = 0.12.sp)
                HeroNum("${rearPct.roundToInt()}%", 18.sp, Ok)
            }
        }
        // rc.2: dropped the center "front/rear split" line — it duplicated
        // the percentages already shown at each end of the bar. L/R torque
        // labels now get full row width and a touch more prominence.
        // B.7: L/R delta pulse when imbalance > 200 Nm
        val lrImbalance = torqueDelta > 200f
        val lrPulseAlpha by rememberInfiniteTransition(label = "lrPulse").animateFloat(
            initialValue = 1f, targetValue = 0.6f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "lrPulseA"
        )
        val lrAlpha = if (lrImbalance) lrPulseAlpha else 1f
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                MonoLabel("LEFT", 10.sp, Dim, letterSpacing = 0.15.sp)
                MonoText("${vs.awdLeftTorque.roundToInt()} Nm", 16.sp, accent.copy(alpha = lrAlpha))
            }
            Column(horizontalAlignment = Alignment.End) {
                MonoLabel("RIGHT", 10.sp, Dim, letterSpacing = 0.15.sp)
                MonoText("${vs.awdRightTorque.roundToInt()} Nm", 16.sp, Ok.copy(alpha = lrAlpha))
            }
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
