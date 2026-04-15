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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.anim.ShiftLightBar
import com.openrs.dash.ui.anim.SparklineData
import com.openrs.dash.ui.Tokens.PagePad
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.Tokens.CardGap
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE PAGE — what's happening right now, curated for glance-readability
// Polled fields (temps, fuel economy, odometer, warnings) live on other tabs.
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun DrivePage(vs: VehicleState, p: UserPrefs) {
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

    val (boostVal, _) = p.displayBoost(vs.boostKpa)
    val speedStr = p.displaySpeed(vs.speedKph)
    val rpmStr = "${vs.rpm.toInt()}"

    // B2: when CAN is disconnected, dim hero numerics and show a slim banner.
    // Keeps the last-known values visible (common ask) but signals they are stale.
    val stale = !vs.isConnected
    val heroAlpha by animateFloatAsState(
        if (stale) 0.4f else 1f,
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
    val gearSizeSp by animateFloatAsState(
        if (zoomed) 140f else 72f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "gearSize"
    )
    val gearPadDp by animateFloatAsState(
        if (zoomed) 42f else 18f,
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

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(start = PagePad, end = PagePad, top = PagePad, bottom = PagePad + Tokens.NavBarHeight),
            verticalArrangement = Arrangement.spacedBy(CardGap)
        ) {
            // C2: hero border alpha scales with valueFraction so the card
            // gains visual weight under active input and stays quiet at rest.
            val boostFrac = (vs.boostPsi.toFloat() / 30f).coerceIn(0f, 1f)
            val rpmFrac = (vs.rpm.toFloat() / 6800f).coerceIn(0f, 1f)
            val speedFrac = (vs.speedKph.toFloat() / 250f).coerceIn(0f, 1f)
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max).alpha(heroAlpha),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeroCard(
                    unit = boostLbl, value = boostVal, label = "BOOST",
                    valueColor = Warn,
                    borderAccent = Warn.copy(alpha = 0.15f + 0.2f * boostFrac),
                    peak = "▲ ${"%.1f".format(vs.peakBoostPsi)}",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    sparklineData = boostSnap,
                    valueFraction = boostFrac,
                    peakHoldValue = "%.1f".format(vs.peakBoostPsi)
                )
                HeroCard(
                    unit = "RPM", value = rpmStr, label = "ENGINE",
                    valueColor = Orange,
                    borderAccent = Orange.copy(alpha = 0.15f + 0.2f * rpmFrac),
                    peak = "▲ ${vs.peakRpm.toInt()}",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    sparklineData = rpmSnap,
                    valueFraction = rpmFrac,
                    peakHoldValue = vs.peakRpm.toInt().toString()
                )
                HeroCard(
                    unit = p.speedLabel, value = speedStr, label = "SPEED",
                    valueColor = accent,
                    borderAccent = accent.copy(alpha = 0.15f + 0.2f * speedFrac),
                    peak = "▲ ${p.displaySpeed(vs.peakSpeedKph)}",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    sparklineData = speedSnap,
                    valueFraction = speedFrac,
                    peakHoldValue = p.displaySpeed(vs.peakSpeedKph)
                )
            }

            AnimatedVisibility(
                visible = stale,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .background(Warn.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .border(1.dp, Warn.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel(
                        if (vs.isIdle) "⚠ CAN DISCONNECTED — tap status bar to reconnect"
                        else "⚠ CAN DISCONNECTED — values shown are stale",
                        11.sp, Warn, letterSpacing = 0.2.sp
                    )
                }
            }

            ShiftLightBar(rpm = vs.rpm.toFloat(), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))

            val gearActive = vs.isConnected && (vs.rpm > 0 || vs.speedKph > 0)
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(accent.copy(alpha = if (gearActive) 0.04f else 0f), Surf2)),
                        RoundedCornerShape(16.dp)
                    )
                    .border(1.dp, if (gearActive) accent.copy(alpha = 0.25f) else Brd.copy(alpha = borderAlpha(0.15f)), RoundedCornerShape(16.dp))
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
                    MonoLabel("G E A R", 8.sp, Dim, letterSpacing = 4.sp)
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
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
            SectionLabel("INPUTS")
            val wide = isWideLayout()
            val thrInt = thr.roundToInt()
            val brakeInt = brake.roundToInt()
            val thrFrac = (thr / 100.0).toFloat().coerceIn(0f, 1f)
            val brakeFrac = (brake / 100.0).toFloat().coerceIn(0f, 1f)
            if (wide) {
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
                    val fuelCritical = animFuel in 0.01f..9.99f
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
            } else {
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
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fuelCriticalNarrow = animFuel in 0.01f..9.99f
                    BarCard(
                        name = if (fuelCriticalNarrow) "LOW FUEL" else "FUEL",
                        value = "${animFuel.roundToInt()}%",
                        fraction = (animFuel / 100f),
                        barBrush = Brush.horizontalGradient(listOf(Ok.copy(0.4f), Ok)),
                        modifier = Modifier.weight(1f),
                        barGlowColor = Ok,
                        criticalPulse = fuelCriticalNarrow
                    )
                    BarCard(
                        name = "BATTERY", value = "${"%.2f".format(animBatt)}V",
                        fraction = ((animBatt - 10f) / 6f).coerceIn(0f, 1f),
                        barBrush = Brush.horizontalGradient(listOf(Warn.copy(0.4f), Warn)),
                        modifier = Modifier.weight(1f),
                        barGlowColor = Warn
                    )
                }
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
                        .background(Brush.horizontalGradient(listOf(accent, accent.copy(0.5f))),
                            RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp)))
                    Box(Modifier.weight(rearF).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Ok.copy(0.5f), Ok)),
                            RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)))
                }
                if (vs.totalRearTorque > 5) {
                    Canvas(Modifier.matchParentSize()) {
                        val dotRadius = 2.dp.toPx()
                        val dotCount = 3
                        val rearDominant = rearPct > 55f
                        for (i in 0 until dotCount) {
                            val phase = (flowProgress + i.toFloat() / dotCount) % 1f
                            val x = if (rearDominant) size.width * (1f - phase) else size.width * phase
                            val dotAlpha = (0.4f * (1f - kotlin.math.abs(phase - 0.5f) * 2f)).coerceIn(0f, 0.4f)
                            val dotColor = if (rearDominant) Ok else accent
                            drawCircle(dotColor.copy(alpha = dotAlpha), dotRadius, Offset(x, size.height / 2f))
                        }
                    }
                }
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
    c >= critC  -> Orange
    c >= warnC  -> Warn
    c >= warnC * 0.6 -> Ok
    else        -> Frost
}
