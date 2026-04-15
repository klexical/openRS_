package com.openrs.dash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.getValue
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
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.anim.StaggeredColumn

// ═══════════════════════════════════════════════════════════════════════════
// TEMPS PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun TempsPage(vs: VehicleState, p: UserPrefs) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = PagePad, end = PagePad, top = PagePad, bottom = PagePad + Tokens.NavBarHeight),
        verticalArrangement = Arrangement.spacedBy(CardGap)
    ) {
        RtrBanner(vs, p)
        TempPresetBadge(p)

        // ── Tires + AWD (migrated from CHASSIS) ─────────────────────────
        UnifiedChassisSection(vs, p)

        fun peakStr(peakC: Double) = if (peakC > -90) "\u25B2 ${p.displayTemp(peakC)}${p.tempLabel}" else ""
        fun avail(valueC: Double, tag: String) =
            tempAvailFor(valueC, vs.fieldLastUpdateMs[tag])
        fun subFor(a: TempAvail, fallback: String) = when (a) {
            TempAvail.WARMING     -> "WARMING"
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
        val powertrainItems = listOf(
            TempSpec("ENGINE OIL",
                if (vs.oilTempC > -90) p.displayTemp(vs.oilTempC) else "— —", p.tempLabel,
                vs.oilTempC.takeIf { it > -90 } ?: 0.0,
                p.oilWarnC, p.oilCritC, subFor(oilA, "INFERRED"),
                peakStr(vs.peakOilTempC), oilA),
            TempSpec("COOLANT",
                if (vs.coolantTempC > -90) p.displayTemp(vs.coolantTempC) else "— —", p.tempLabel,
                vs.coolantTempC.takeIf { it > -90 } ?: 0.0,
                p.coolWarnC, p.coolCritC, subFor(coolA, ""),
                peakStr(vs.peakCoolantTempC), coolA),
            TempSpec("INTAKE AIR",
                if (vs.intakeTempC > -90) p.displayTemp(vs.intakeTempC) else "— —", p.tempLabel,
                vs.intakeTempC.takeIf { it > -90 } ?: 0.0,
                p.intakeWarnC, p.intakeCritC, subFor(intakeA, ""),
                avail = intakeA),
            TempSpec("PTU (TRANSFER)",
                if (vs.ptuTempC > -90) p.displayTemp(vs.ptuTempC) else "— —", p.tempLabel,
                vs.ptuTempC.takeIf { it > -90 } ?: 0.0,
                p.ptuWarnC, p.ptuCritC, subFor(ptuA, ""),
                peakStr(vs.peakPtuTempC), ptuA),
            TempSpec("RDU (REAR)",
                if (vs.rduTempC > -90) p.displayTemp(vs.rduTempC) else "— —", p.tempLabel,
                vs.rduTempC.takeIf { it > -90 } ?: 0.0,
                p.rduWarnC, p.rduCritC, subFor(rduA, ""),
                peakStr(vs.peakRduTempC), rduA),
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
        AnimatedVisibility(visible = detailExpanded, enter = expandVertically(), exit = shrinkVertically()) {
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
)

@Composable fun RtrBanner(vs: VehicleState, p: UserPrefs) {
    val warmupDetail = vs.rtrStatus
    val isReady = warmupDetail == null
    val dotColor = if (isReady) Ok else Warn
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
    val bannerBrush = if (isReady)
        Brush.horizontalGradient(listOf(Ok.copy(alpha = 0.08f), Ok.copy(alpha = 0.04f)))
    else
        Brush.horizontalGradient(listOf(Warn.copy(alpha = 0.08f), Warn.copy(alpha = 0.04f)))
    val bannerBorder = if (isReady) Ok.copy(alpha = 0.2f) else Warn.copy(alpha = 0.2f)

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
                if (isReady) "RACE READY" else "WARMING UP — NOT RACE READY",
                13.sp, Frost, FontWeight.SemiBold, 0.5.sp
            )
            if (!isReady) {
                MonoLabel(warmupDetail, 9.sp, Warn, modifier = Modifier.padding(top = 2.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(spec.unit, 10.sp, Dim)
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
