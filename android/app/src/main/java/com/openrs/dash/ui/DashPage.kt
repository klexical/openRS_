package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.anim.ShiftLightBar
import com.openrs.dash.ui.anim.SparklineData
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// DASH PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun DashPage(vs: VehicleState, p: UserPrefs) {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current
    val (boostVal, boostLbl) = p.displayBoost(vs.boostKpa)
    val brakeStr = "%.0f".format(vs.brakePressure.coerceIn(0.0, 100.0))

    // ── Animated values ──────────────────────────────────────────────────
    val animRpm by animateFloatAsState(vs.rpm.toFloat(), spring(stiffness = Spring.StiffnessHigh), label = "rpm")
    val animSpeed by animateFloatAsState(vs.speedKph.toFloat(), spring(stiffness = Spring.StiffnessHigh), label = "spd")
    val animBoost by animateFloatAsState(vs.boostKpa.toFloat(), spring(stiffness = Spring.StiffnessMediumLow), label = "bst")
    val thr = if (vs.throttleHasSource) vs.throttlePct else vs.accelPedalPct
    val animThr by animateFloatAsState(thr.toFloat(), spring(stiffness = Spring.StiffnessHigh), label = "thr")
    val animBrake by animateFloatAsState(vs.brakePressure.toFloat().coerceIn(0f, 100f), spring(stiffness = Spring.StiffnessHigh), label = "brk")
    val animFuel by animateFloatAsState(vs.fuelLevelPct.toFloat(), tween(800), label = "fuel")
    val animBatt by animateFloatAsState(vs.batteryVoltage.toFloat(), tween(800), label = "batt")

    // ── Sparkline data (sampled at ~4 Hz) ────────────────────────────────
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
            brakeSpark.push(vs.brakePressure.toFloat().coerceIn(0f, 100f))
        }
    }

    // ── Formatted animated values ────────────────────────────────────────
    val (animBoostVal, _) = p.displayBoost(animBoost.toDouble())
    val animSpeedStr = p.displaySpeed(animSpeed.toDouble())
    val animRpmStr = "${animRpm.toInt()}"

    val scrollState = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Hero Row: BOOST | RPM | SPEED (with ▲ session peaks) ─────────
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroCard(
                unit = boostLbl, value = animBoostVal, label = "BOOST",
                valueColor = Warn,
                borderAccent = Warn.copy(alpha = 0.25f),
                peak = "▲ ${"%.1f".format(vs.peakBoostPsi)}",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                sparklineData = boostSpark.snapshot()
            )
            HeroCard(
                unit = "RPM", value = animRpmStr, label = "ENGINE",
                valueColor = Orange,
                borderAccent = Orange.copy(alpha = 0.2f),
                peak = "▲ ${vs.peakRpm.toInt()}",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                sparklineData = rpmSpark.snapshot()
            )
            HeroCard(
                unit = p.speedLabel, value = animSpeedStr, label = "SPEED",
                valueColor = accent,
                borderAccent = accent.copy(alpha = 0.25f),
                peak = "▲ ${p.displaySpeed(vs.peakSpeedKph)}",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                sparklineData = speedSpark.snapshot()
            )
        }

        // ── Shift Light Bar ──────────────────────────────────────────────
        ShiftLightBar(rpm = animRpm, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))

        // ── Gear — rally-style full-width hero ──────────────────────────
        Box(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(accent.copy(alpha = 0.04f), Surf2)),
                    RoundedCornerShape(16.dp)
                )
                .border(2.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .padding(vertical = 18.dp),
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
                    HeroNum(gear, 72.sp, Frost)
                }
                MonoLabel("G E A R", 8.sp, Dim, letterSpacing = 4.sp)
            }
        }

        // ── Launch Control indicator (CAN 0x420 — any drive mode) ─────────
        if (vs.launchControlActive) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Warn.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .border(1.5.dp, Warn.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("⚡ LAUNCH CONTROL ACTIVE", 12.sp, Warn, letterSpacing = 0.2.sp)
            }
        }

        // ── Warning Lamps (populated once IPC DIDs are discovered) ─────────
        val activeWarnings = listOfNotNull(
            if (vs.warnMil == true) "CEL" else null,
            if (vs.warnAbs == true) "ABS" else null,
            if (vs.warnBrake == true) "BRK" else null,
            if (vs.warnCharge == true) "CHRG" else null,
            if (vs.warnOilPressure == true) "OIL" else null,
            if (vs.warnTempHigh == true) "TEMP" else null
        )
        if (activeWarnings.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Orange.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .border(1.5.dp, Orange.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel(
                    "⚠ ${activeWarnings.joinToString(" · ")}",
                    12.sp, Orange, letterSpacing = 0.2.sp
                )
            }
        }

        // ── Inputs & Resources bar grid ─────────────────────────────────────
        SectionLabel("INPUTS & RESOURCES")
        val wide = isWideLayout()
        if (wide) {
            // Landscape / wide: single row of 4 BarCards
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarCard(
                    name = if (vs.throttleHasSource) "THROTTLE" else "PEDAL",
                    value = "${animThr.roundToInt()}%",
                    fraction = (animThr / 100f),
                    barBrush = Brush.horizontalGradient(listOf(accent.copy(0.4f), accent)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = accent,
                    sparklineData = thrSpark.snapshot()
                )
                BarCard(
                    name = "BRAKE", value = "${animBrake.roundToInt()}%",
                    fraction = (animBrake / 100f).coerceIn(0f, 1f),
                    barBrush = Brush.horizontalGradient(listOf(Orange.copy(0.4f), Orange)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = Orange,
                    sparklineData = brakeSpark.snapshot()
                )
                BarCard(
                    name = "FUEL", value = "${animFuel.roundToInt()}%",
                    fraction = (animFuel / 100f),
                    barBrush = Brush.horizontalGradient(listOf(Ok.copy(0.4f), Ok)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = Ok
                )
                BarCard(
                    name = "BATTERY", value = "${"%.1f".format(animBatt)}V",
                    fraction = ((animBatt - 10f) / 6f).coerceIn(0f, 1f),
                    barBrush = Brush.horizontalGradient(listOf(Warn.copy(0.4f), Warn)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = Warn
                )
            }
        } else {
            // Portrait: two rows of 2 BarCards
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarCard(
                    name = if (vs.throttleHasSource) "THROTTLE" else "PEDAL",
                    value = "${animThr.roundToInt()}%",
                    fraction = (animThr / 100f),
                    barBrush = Brush.horizontalGradient(listOf(accent.copy(0.4f), accent)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = accent,
                    sparklineData = thrSpark.snapshot()
                )
                BarCard(
                    name = "BRAKE", value = "${animBrake.roundToInt()}%",
                    fraction = (animBrake / 100f).coerceIn(0f, 1f),
                    barBrush = Brush.horizontalGradient(listOf(Orange.copy(0.4f), Orange)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = Orange,
                    sparklineData = brakeSpark.snapshot()
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarCard(
                    name = "FUEL", value = "${animFuel.roundToInt()}%",
                    fraction = (animFuel / 100f),
                    barBrush = Brush.horizontalGradient(listOf(Ok.copy(0.4f), Ok)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = Ok
                )
                BarCard(
                    name = "BATTERY", value = "${"%.1f".format(animBatt)}V",
                    fraction = ((animBatt - 10f) / 6f).coerceIn(0f, 1f),
                    barBrush = Brush.horizontalGradient(listOf(Warn.copy(0.4f), Warn)),
                    modifier = Modifier.weight(1f),
                    barGlowColor = Warn
                )
            }
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

        // ── Odometer toggle (persisted to SharedPreferences) ────────────────
        val odomInMiles = p.odomInMiles
        val odomLabel = if (odomInMiles) "ODO (mi)" else "ODO (km)"
        val odomValue = when {
            vs.odometerKm < 0 -> "—"
            odomInMiles       -> "${"%.0f".format(vs.odometerKm * UnitConversions.KM_TO_MI)} mi"
            else              -> "${"%.0f".format(vs.odometerKm.toDouble())} km"
        }
        Box(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(12.dp))
                .border(1.dp, Brd, RoundedCornerShape(12.dp))
                .clickable(enabled = vs.odometerKm >= 0) {
                    UserPrefsStore.update(ctx) { it.copy(odomInMiles = !it.odomInMiles) }
                }
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

    // ── Pinned Metric Strip (visible when hero row scrolls out of view) ──
    AnimatedVisibility(
        visible = scrollState.value > 220,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        MiniMetricStrip(animBoostVal, boostLbl, animRpmStr, animSpeedStr, p.speedLabel)
    }
    } // end outer Box
}

/** Compact sticky row: BOOST / RPM / SPEED — shown when the hero row scrolls off-screen. */
@Composable private fun MiniMetricStrip(
    boostVal: String, boostLbl: String,
    rpmStr: String, speedStr: String, speedLbl: String
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Surf)
            .border(width = 1.dp, color = Brd,
                shape = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonoLabel("BOOST:", 9.sp, Dim)
        MonoLabel("$boostVal $boostLbl", 9.sp, Frost)
        MonoLabel("  |  ", 9.sp, Dim)
        MonoLabel("RPM:", 9.sp, Dim)
        MonoLabel(rpmStr, 9.sp, Frost)
        MonoLabel("  |  ", 9.sp, Dim)
        MonoLabel("SPD:", 9.sp, Dim)
        MonoLabel("$speedStr $speedLbl", 9.sp, Frost)
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
    c >= critC  -> Orange
    c >= warnC  -> Warn
    c >= warnC * 0.6 -> Ok
    else        -> Frost
}
