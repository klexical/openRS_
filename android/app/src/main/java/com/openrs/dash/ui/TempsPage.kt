package com.openrs.dash.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.VehicleState

// ═══════════════════════════════════════════════════════════════════════════
// TEMPS PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun TempsPage(vs: VehicleState, p: UserPrefs) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RtrBanner(vs, p)
        TempPresetBadge(p)

        SectionLabel("TEMPERATURES")
        val tempItems = listOf(
            TempSpec("ENGINE OIL",
                if (vs.oilTempC > -90) p.displayTemp(vs.oilTempC) else "— —", p.tempLabel,
                vs.oilTempC.takeIf { it > -90 } ?: 0.0,
                p.oilWarnC, p.oilCritC, if (vs.oilTempC <= -90) "WARMING" else "INFERRED"),
            TempSpec("COOLANT",
                if (vs.coolantTempC > -90) p.displayTemp(vs.coolantTempC) else "— —", p.tempLabel,
                vs.coolantTempC.takeIf { it > -90 } ?: 0.0,
                p.coolWarnC, p.coolCritC, if (vs.coolantTempC <= -90) "WARMING" else ""),
            TempSpec("INTAKE AIR",    p.displayTemp(vs.intakeTempC),  p.tempLabel, vs.intakeTempC,
                p.intakeWarnC, p.intakeCritC, ""),
            TempSpec("AMBIENT",       p.displayTemp(vs.ambientTempC), p.tempLabel, vs.ambientTempC,
                40.0, 50.0, ""),
            TempSpec("RDU (REAR)",
                if (vs.rduTempC > -90) p.displayTemp(vs.rduTempC) else "— —", p.tempLabel,
                vs.rduTempC.takeIf { it > -90 } ?: 0.0,
                p.rduWarnC, p.rduCritC, if (vs.rduTempC <= -90) "POLLING" else ""),
            TempSpec("PTU (TRANSFER)",
                if (vs.ptuTempC > -90) p.displayTemp(vs.ptuTempC) else "— —", p.tempLabel,
                vs.ptuTempC.takeIf { it > -90 } ?: 0.0,
                p.ptuWarnC, p.ptuCritC, if (vs.ptuTempC <= -90) "POLLING" else ""),
            TempSpec("CHARGE AIR",
                if (vs.chargeAirTempC > -90) p.displayTemp(vs.chargeAirTempC) else "— —", p.tempLabel,
                vs.chargeAirTempC, 60.0, 80.0, if (vs.chargeAirTempC <= -90) "POLLING" else ""),
            TempSpec("MANIFOLD",
                if (vs.manifoldChargeTempC > -90) p.displayTemp(vs.manifoldChargeTempC) else "— —", p.tempLabel,
                vs.manifoldChargeTempC, 60.0, 90.0, if (vs.manifoldChargeTempC <= -90) "POLLING" else ""),
            TempSpec("CATALYTIC",
                if (vs.catalyticTempC > -90) p.displayTemp(vs.catalyticTempC) else "— —", p.tempLabel,
                vs.catalyticTempC, 700.0, 800.0, if (vs.catalyticTempC <= -90) "POLLING" else ""),
            TempSpec("CABIN",
                if (vs.cabinTempC > -90) p.displayTemp(vs.cabinTempC) else "— —", p.tempLabel,
                vs.cabinTempC.takeIf { it > -90 } ?: 0.0, 35.0, 45.0, "BCM"),
            TempSpec("BATT TEMP",
                if (vs.batteryTempC > -90) p.displayTemp(vs.batteryTempC) else "— —", p.tempLabel,
                vs.batteryTempC.takeIf { it > -90 } ?: 0.0, 40.0, 60.0, "BCM"),
        )
        tempItems.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { spec -> TempCard(spec, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

data class TempSpec(
    val label: String, val value: String, val unit: String,
    val tempC: Double, val warnC: Double, val critC: Double, val sub: String
)

@Composable fun RtrBanner(vs: VehicleState, p: UserPrefs) {
    val warmupDetail = vs.rtrStatus
    val isReady = warmupDetail == null
    val dotColor = if (isReady) Ok else Warn
    val infiniteTransition = rememberInfiniteTransition(label = "rtr")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = if (isReady) 1f else 0.3f, label = "rtrDot",
        animationSpec = infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse)
    )
    val bannerBrush = if (isReady)
        Brush.horizontalGradient(listOf(Ok.copy(alpha = 0.08f), Ok.copy(alpha = 0.04f)))
    else
        Brush.horizontalGradient(listOf(Warn.copy(alpha = 0.08f), Warn.copy(alpha = 0.04f)))
    val bannerBorder = if (isReady) Ok.copy(alpha = 0.2f) else Warn.copy(alpha = 0.2f)

    Row(
        Modifier.fillMaxWidth()
            .background(bannerBrush, RoundedCornerShape(12.dp))
            .border(1.dp, bannerBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor.copy(alpha = dotAlpha)))
        Spacer(Modifier.width(12.dp))
        Column {
            UIText(
                if (isReady) "RACE READY" else "WARMING UP — NOT RACE READY",
                13.sp, Frost, FontWeight.SemiBold, 0.5.sp
            )
            if (!isReady && warmupDetail != null) {
                MonoLabel(warmupDetail, 9.sp, Warn, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable fun TempPresetBadge(p: UserPrefs) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp))
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
                val color    = when (id) { "race" -> Red; "track" -> Warn; else -> Ok }
                Box(
                    Modifier
                        .background(if (isActive) color.copy(alpha = 0.15f) else Surf3, RoundedCornerShape(6.dp))
                        .border(1.dp, if (isActive) color.copy(alpha = 0.5f) else Brd, RoundedCornerShape(6.dp))
                        .clickable { UserPrefsStore.update(ctx) { it.copy(tempPreset = id) } }
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
        else                    -> Red
    }
    val isPlaceholder = spec.value == "— —"

    Box(
        modifier
            .background(Surf2, RoundedCornerShape(14.dp))
            .border(1.dp, Brd, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(spec.label, 8.sp, Dim, letterSpacing = 0.12.sp)
                if (spec.sub.isNotEmpty()) MonoLabel(spec.sub, 7.sp, Dim.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(6.dp))
            if (isPlaceholder) {
                MonoText("— —", 24.sp, Dim)
            } else {
                HeroNum(spec.value, 24.sp, tempColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(spec.unit, 10.sp, Dim)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Surf3, RoundedCornerShape(2.dp))) {
                if (!isPlaceholder && barPct > 0) {
                    Box(Modifier.fillMaxWidth(barPct).height(3.dp).background(barColor, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}
