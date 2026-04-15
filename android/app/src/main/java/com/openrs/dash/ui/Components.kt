package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.openrs.dash.ui.anim.Sparkline
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.R
import com.openrs.dash.data.BandPattern
import com.openrs.dash.data.ThermalBand
import com.openrs.dash.data.thermalBandForTire
import com.openrs.dash.ui.anim.cardGlow
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.Tokens.CardShape
import com.openrs.dash.ui.Tokens.HeroShape
import com.openrs.dash.ui.Tokens.InnerH
import com.openrs.dash.ui.Tokens.InnerV
import com.openrs.dash.ui.Tokens.HeroInnerH
import com.openrs.dash.ui.Tokens.HeroInnerV

// ═══════════════════════════════════════════════════════════════════════════
// SHARED UI COMPONENTS — used across two or more tab pages
// ═══════════════════════════════════════════════════════════════════════════

/** Neon accent divider — replaces solid Brd horizontal rules. */
@Composable fun NeonDivider(modifier: Modifier = Modifier) {
    val accent = LocalThemeAccent.current
    Box(modifier.fillMaxWidth().height(1.dp).background(
        Brush.horizontalGradient(listOf(
            Color.Transparent, accent.copy(alpha = 0.3f), accent.copy(alpha = 0.15f), Color.Transparent
        ))
    ))
}

/** Section label: small text with extending neon horizontal rule. Optionally collapsible. */
@Composable fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null
) {
    Row(
        modifier
            .padding(bottom = 8.dp)
            .then(if (collapsible && onToggle != null)
                Modifier.clickable { onToggle() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (collapsible) {
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 0f else -90f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium), label = "chevRot"
            )
            MonoLabel("\u25BE", 9.sp, Dim, modifier = Modifier.graphicsLayer { rotationZ = rotation })
        }
        MonoLabel(text, 9.sp, Dim, letterSpacing = 0.3.sp)
        NeonDivider(Modifier.weight(1f))
    }
}

/** Data cell — JetBrains Mono label + value.
 *  Optional [indicator] renders a color-blind-safe glyph when a metric is in
 *  a critical band (so state is encoded by color **and** shape). */
@Composable fun DataCell(
    label: String,
    value: String,
    valueColor: Color = Frost,
    modifier: Modifier = Modifier,
    indicator: BandPattern = BandPattern.NONE,
    sub: String = "",
) {
    val isPlaceholder = value == "— —"
    val displayColor = if (isPlaceholder) {
        val alpha by rememberInfiniteTransition(label = "ph").animateFloat(
            initialValue = 0.3f, targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "phA"
        )
        Dim.copy(alpha = textMutedAlpha(alpha))
    } else valueColor

    Column(
        modifier
            .cardGlow(cornerRadius = Tokens.CardRadius)
            .background(Surf2, CardShape)
            .border(CardBorder, Brd.copy(alpha = borderAlpha(0.15f)), CardShape)
            .padding(horizontal = InnerH, vertical = InnerV)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(label, 8.sp, Dim)
            when (indicator) {
                BandPattern.CHEVRON_UP   -> MonoLabel("▲", 9.sp, valueColor)
                BandPattern.CHEVRON_DOWN -> MonoLabel("▼", 9.sp, valueColor)
                BandPattern.NONE -> {}
            }
        }
        Spacer(Modifier.height(3.dp))
        AggressiveNum(value, 14.sp, displayColor)
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(1.dp))
            MonoLabel(sub, 8.sp, Dim.copy(alpha = textMutedAlpha(0.6f)), letterSpacing = 0.2.sp)
        }
    }
}

/** Hero card — large Orbitron number for BOOST / SPEED / RPM.
 *  @param valueFraction 0.0–1.0 drives glow intensity (0=idle, 1=max). */
@Composable fun HeroCard(
    unit: String,
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    borderAccent: Color? = null,
    peak: String = "",
    sparklineData: List<Float>? = null,
    valueFraction: Float = 0f,
    peakHoldValue: String? = null,
) {
    val accent = LocalThemeAccent.current
    val animFrac by animateFloatAsState(valueFraction.coerceIn(0f, 1f),
        spring(stiffness = Spring.StiffnessMediumLow), label = "heroFrac")
    val hasData = animFrac > 0.01f
    val glowAlpha = animFrac * 0.3f
    // Long-press peak-hold: flip to session peak for 3s, then revert.
    var peakHoldActive by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(peakHoldActive) {
        if (peakHoldActive) {
            kotlinx.coroutines.delay(3000)
            peakHoldActive = false
        }
    }
    val peakModifier = if (peakHoldValue != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    peakHoldActive = true
                }
            )
        }
    } else Modifier
    val shownValue = if (peakHoldActive && peakHoldValue != null) peakHoldValue else value
    Column(
        modifier
            .then(peakModifier)
            .cardGlow(color = borderAccent ?: accent, cornerRadius = Tokens.HeroRadius)
            .background(Surf2, HeroShape)
            .border(CardBorder, Brd.copy(alpha = borderAlpha(0.15f)), HeroShape)
            .padding(horizontal = HeroInnerH, vertical = HeroInnerV),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(if (peakHoldActive) "PEAK · $unit" else unit, 8.sp,
            if (peakHoldActive) accent else Dim, letterSpacing = 0.18.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().then(if (hasData) Modifier.drawBehind {
                // Outer diffuse glow
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(valueColor.copy(alpha = glowAlpha * 0.5f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 1.2f
                    )
                )
                // Inner bright core
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(valueColor.copy(alpha = glowAlpha), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.7f
                    )
                )
            } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            HeroNum(shownValue, 26.sp, valueColor, Modifier.fillMaxWidth())
        }
        if (peak.isNotEmpty()) {
            MonoText(peak, 9.sp, accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }
        if (sparklineData != null && sparklineData.size >= 2) {
            Spacer(Modifier.height(4.dp))
            Sparkline(sparklineData, valueColor, Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 4.dp))
        }
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.15.sp)
    }
}

/** Bar card — label + value + gradient progress bar */
@Composable fun BarCard(
    name: String,
    value: String,
    fraction: Float,
    barBrush: Brush,
    modifier: Modifier = Modifier,
    barGlowColor: Color? = null,
    sparklineData: List<Float>? = null,
    criticalPulse: Boolean = false
) {
    val hasData = fraction > 0f
    // Slow 1.4s Warn-tinted pulse on the card background when critical (e.g., fuel <10%).
    val pulseAlpha = if (criticalPulse) {
        val t = rememberInfiniteTransition(label = "critPulse")
        t.animateFloat(
            initialValue = 0f, targetValue = 0.18f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "critA"
        ).value
    } else 0f
    Column(
        modifier
            .cardGlow(cornerRadius = Tokens.CardRadius)
            .background(Surf2, CardShape)
            .then(
                if (criticalPulse)
                    Modifier.background(Warn.copy(alpha = pulseAlpha), CardShape)
                else Modifier
            )
            .border(CardBorder, Brd.copy(alpha = borderAlpha(0.15f)), CardShape)
            .padding(InnerH)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(name, 9.sp, if (criticalPulse) Warn else Dim)
            AggressiveNum(value, 13.sp, if (criticalPulse) Warn else Frost)
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp)
                .background(Surf3, RoundedCornerShape(2.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(barBrush, RoundedCornerShape(2.dp))
            )
        }
        if (sparklineData != null && sparklineData.size >= 2) {
            Spacer(Modifier.height(6.dp))
            Sparkline(sparklineData, barGlowColor ?: Accent, Modifier.fillMaxWidth().height(28.dp))
        }
    }
}

/** AFR/lambda numeric card — used on Power page */
@Composable fun AfrCard(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier) {
    val isPlaceholder = value == "— —"
    val displayColor = if (isPlaceholder) {
        val alpha by rememberInfiniteTransition(label = "ph").animateFloat(
            initialValue = 0.3f, targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "phA"
        )
        Dim.copy(alpha = textMutedAlpha(alpha))
    } else valueColor

    Column(
        modifier
            .cardGlow(cornerRadius = Tokens.CardRadius)
            .background(Surf2, CardShape)
            .border(CardBorder, Brd.copy(alpha = borderAlpha(0.15f)), CardShape)
            .padding(InnerH),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(4.dp))
        HeroNum(value, 22.sp, displayColor, Modifier.fillMaxWidth())
        MonoLabel(unit, 9.sp, Dim)
    }
}

/** Wheel speed cell — front/rear accent colour */
@Composable fun WheelCell(label: String, speed: String, front: Boolean) {
    val accent = LocalThemeAccent.current
    val isPlaceholder = speed == "— —" || speed == "0"
    Column(
        Modifier.fillMaxWidth()
            .cardGlow(cornerRadius = Tokens.CardRadius)
            .background(Surf2, Tokens.CardShape)
            .border(CardBorder, Brd.copy(alpha = borderAlpha(0.15f)), Tokens.CardShape)
            .padding(InnerV),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 9.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(2.dp))
        AggressiveNum(speed, 15.sp, Frost)
    }
}

/** G-force / dynamics numeric card */
@Composable fun GfCard(label: String, value: String, peak: String, modifier: Modifier) {
    val accent = LocalThemeAccent.current
    val isPlaceholder = value == "— —" || value == "0.00"
    Column(
        modifier
            .cardGlow(cornerRadius = Tokens.GfRadius)
            .background(Surf2, RoundedCornerShape(Tokens.GfRadius))
            .border(CardBorder, Brd.copy(alpha = borderAlpha(0.15f)), RoundedCornerShape(Tokens.GfRadius))
            .padding(InnerV),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier.fillMaxWidth().then(if (!isPlaceholder) Modifier.drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.12f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.8f
                    )
                )
            } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            MonoText(value, 18.sp, Frost, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (peak.isNotEmpty()) {
            MonoText(peak, 9.sp, accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Tire pressure card with optional temperature and delta trend — colour-coded by pressure/temp range */
@Composable fun TireCard(
    label: String, psi: Double, p: UserPrefs, lowThreshold: Double,
    tempC: Double = -99.0,
    deltaText: String = ""
) {
    val isMissing = psi < 0
    val warnThreshold = p.tireWarnPsi.toDouble()
    val highThreshold = p.tireHighPsi.toDouble()
    val isLow     = psi in 0.0..(lowThreshold - 0.001)
    val isWarn    = psi in lowThreshold..(warnThreshold - 0.001)
    val tireColor = when {
        isMissing       -> Dim
        isLow           -> Orange        // critically under-inflated
        isWarn          -> Warn          // getting low
        psi > highThreshold -> Orange   // over-inflated
        else            -> Ok            // optimal range
    }
    val hasTemp = tempC > -90
    val tireBorderColor = when {
        isMissing -> Brd.copy(alpha = 0.3f)
        isLow     -> Orange.copy(alpha = 0.5f)
        isWarn    -> Warn.copy(alpha = 0.3f)
        psi > highThreshold -> Orange.copy(alpha = 0.3f)
        else      -> Ok.copy(alpha = 0.15f)
    }
    Column(
        Modifier.fillMaxWidth()
            .cardGlow(color = tireColor, intensity = 0.04f, cornerRadius = Tokens.CardRadius)
            .background(Surf2, CardShape)
            .border(CardBorder, tireBorderColor, CardShape)
            .padding(InnerV, 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 9.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(4.dp))
        HeroNum(if (isMissing) "—" else p.displayTire(psi), 18.sp, tireColor)
        MonoLabel(p.tireLabel, 8.sp, Dim, letterSpacing = 0.1.sp)
        if (deltaText.isNotEmpty()) {
            val deltaColor = if (deltaText.startsWith("\u25B2")) Ok else Orange
            MonoText(deltaText, 8.sp, deltaColor)
        }
        if (hasTemp) {
            Box(Modifier.fillMaxWidth(0.7f).height(1.dp).padding(vertical = 0.dp)
                .background(Brd))
            Spacer(Modifier.height(3.dp))
            MonoText(
                p.displayTemp(tempC) + p.tempLabel,
                10.sp,
                tireTempColor(tempC)
            )
        }
    }
}

fun tireTempColor(tempC: Double): Color = bandColor(thermalBandForTire(tempC))

/** Maps a ThermalBand to its UI color in the current theme. Call from a
 *  composable scope — semantic colours (Ok/Warn/Orange) are mode-aware getters. */
fun bandColor(band: ThermalBand): Color = when (band) {
    ThermalBand.COLD -> Mid
    ThermalBand.NOMINAL -> Ok
    ThermalBand.HOT -> Warn
    ThermalBand.CRITICAL -> Orange
}

/** Focus RS MK3 top-down wireframe — tinted to the active theme accent color */
@Composable fun FocusRsOutline(compact: Boolean = false) {
    val accent = LocalThemeAccent.current
    val w = if (compact) 52.dp else 72.dp
    val h = if (compact) 110.dp else 150.dp
    Box(Modifier.width(w).height(h), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.focus_rs_wireframe),
            contentDescription = "Focus RS",
            colorFilter = ColorFilter.tint(accent, BlendMode.SrcIn),
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// v3.0 CARD TIERS
// ═══════════════════════════════════════════════════════════════════════════
// Three explicit visual weights. Pages should pick a tier per-field rather
// than rendering everything with equal prominence. Use sparingly — a screen
// with ten Heroes is a screen with none.
//
//   HeroTier      — 96-140 dp tall, one value at 44-96 sp.  ~3-5 per screen.
//   PrimaryTier   — 72-88  dp tall, value 24-32 sp + label. 2-col grid default.
//   SecondaryTier — 44-56  dp tall, value 14-18 sp inline.   3-col grid or row.

/** Hero-tier card: the largest numeric weight. Reserve for the stars. */
@Composable
fun HeroTier(
    label: String,
    value: String,
    unit: String? = null,
    peak: String? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 56.sp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .cardGlow(cornerRadius = 14.dp)
            .background(Surf, HeroShape)
            .border(CardBorder, Brd, HeroShape)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DriveNum(value, fontSize, Frost)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(label, 10.sp, Dim, letterSpacing = 1.8.sp)
            if (unit != null) {
                Spacer(Modifier.width(6.dp))
                Label(unit, 10.sp, LocalThemeAccent.current, letterSpacing = 1.sp)
            }
        }
        if (peak != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(
                    "▲", fontSize = 8.sp, color = LocalThemeAccent.current,
                )
                Spacer(Modifier.width(4.dp))
                DataNum(peak, 12.sp, Dim)
            }
        }
    }
}

/** Primary-tier card: label + value. The default for most data. */
@Composable
fun PrimaryTier(
    label: String,
    value: String,
    unit: String? = null,
    valueColor: Color = Frost,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Surf, CardShape)
            .border(CardBorder, Brd, CardShape)
            .padding(horizontal = InnerH, vertical = InnerV),
    ) {
        Label(label, 9.sp, Dim, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            DataNum(value, 26.sp, valueColor)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Label(unit, 10.sp, Dim,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/** Secondary-tier card: compact, for supporting context. */
@Composable
fun SecondaryTier(
    label: String,
    value: String,
    unit: String? = null,
    valueColor: Color = Frost,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Surf, CardShape)
            .border(CardBorder, Brd, CardShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Label(label, 8.sp, Dim, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            DataNum(value, 17.sp, valueColor)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Label(unit, 9.sp, Dim,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// AVAILABILITY-AWARE CELL — renders a FieldState<Double> with distinct states
// ═══════════════════════════════════════════════════════════════════════════
/**
 * DataCell equivalent that honours [com.openrs.dash.data.FieldState].
 *
 * State rendering:
 *  * NotPolled / Warming → dim placeholder, subtle pulse
 *  * Available           → normal value in [valueColor]
 *  * Stale               → value at 60% opacity + "· Ns" age badge
 *  * Unavailable         → card absent (returns early)
 */
@Composable
fun AvailCell(
    label: String,
    state: com.openrs.dash.data.FieldState<Double>,
    format: (Double) -> String,
    valueColor: Color = Frost,
    modifier: Modifier = Modifier,
) {
    if (state is com.openrs.dash.data.FieldState.Unavailable) return

    val warming = state is com.openrs.dash.data.FieldState.NotPolled ||
                  state is com.openrs.dash.data.FieldState.Warming
    val pulse by rememberInfiniteTransition(label = "availPulse").animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "availPulseAlpha"
    )

    Column(
        modifier
            .background(Surf2, CardShape)
            .border(CardBorder, Brd, CardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(label, 9.sp, Dim, letterSpacing = 1.2.sp)
            if (state is com.openrs.dash.data.FieldState.Stale) {
                Spacer(Modifier.width(6.dp))
                Label("· ${state.ageSeconds}s", 8.sp, Warn.copy(alpha = 0.75f))
            }
        }
        Spacer(Modifier.height(4.dp))
        when (state) {
            is com.openrs.dash.data.FieldState.Available ->
                DataNum(format(state.value), 18.sp, valueColor)
            is com.openrs.dash.data.FieldState.Stale ->
                DataNum(format(state.value), 18.sp, valueColor.copy(alpha = 0.6f))
            else ->
                DataNum("— —", 18.sp, Dim.copy(alpha = if (warming) pulse else 0.5f))
        }
    }
}
