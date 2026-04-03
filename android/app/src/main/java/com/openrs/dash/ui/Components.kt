package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.openrs.dash.ui.anim.Sparkline
import com.openrs.dash.ui.anim.neonBorder
import com.openrs.dash.ui.anim.neonGlowRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.R
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
        MonoLabel(text, 9.sp, Dim, letterSpacing = 0.2.sp)
        NeonDivider(Modifier.weight(1f))
    }
}

/** Data cell — JetBrains Mono label + value */
@Composable fun DataCell(
    label: String,
    value: String,
    valueColor: Color = Frost,
    modifier: Modifier = Modifier
) {
    val isPlaceholder = value == "— —"
    val displayColor = if (isPlaceholder) {
        val alpha by rememberInfiniteTransition(label = "ph").animateFloat(
            initialValue = 0.3f, targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "phA"
        )
        Dim.copy(alpha = alpha)
    } else valueColor

    val glowColor = if (!isPlaceholder && valueColor != Frost) valueColor.copy(alpha = 0.2f) else Brd.copy(alpha = 0.4f)
    Column(
        modifier
            .background(Surf2, CardShape)
            .neonBorder(glowColor, Tokens.CardRadius)
            .padding(horizontal = InnerH, vertical = InnerV)
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.15.sp)
        Spacer(Modifier.height(3.dp))
        MonoText(value, 14.sp, displayColor)
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
    valueFraction: Float = 0f
) {
    val accent = LocalThemeAccent.current
    val animFrac by animateFloatAsState(valueFraction.coerceIn(0f, 1f),
        spring(stiffness = Spring.StiffnessMediumLow), label = "heroFrac")
    val glowAlpha = 0.08f + animFrac * 0.25f
    val borderAlpha = 0.15f + animFrac * 0.45f
    val glowCol = borderAccent ?: accent.copy(alpha = borderAlpha)
    Column(
        modifier
            .background(Surf2, HeroShape)
            .neonBorder(glowCol, Tokens.HeroRadius, alpha = borderAlpha, animated = animFrac > 0.5f)
            .padding(horizontal = HeroInnerH, vertical = HeroInnerV),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(unit, 8.sp, Dim, letterSpacing = 0.18.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().drawBehind {
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
            },
            contentAlignment = Alignment.Center
        ) {
            HeroNum(value, 26.sp, valueColor, Modifier.fillMaxWidth())
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
    sparklineData: List<Float>? = null
) {
    Column(
        modifier
            .background(Surf2, CardShape)
            .neonBorder(barGlowColor?.copy(alpha = 0.2f) ?: Brd.copy(alpha = 0.3f), Tokens.CardRadius)
            .padding(InnerH)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(name, 9.sp, Dim, letterSpacing = 0.15.sp)
            MonoText(value, 13.sp, Frost)
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp)
                .background(Surf3, RoundedCornerShape(2.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(barBrush, RoundedCornerShape(2.dp))
                    .then(if (barGlowColor != null) Modifier.neonGlowRect(barGlowColor) else Modifier)
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
        Dim.copy(alpha = alpha)
    } else valueColor

    Column(
        modifier
            .background(Surf2, CardShape)
            .neonBorder(Brd.copy(alpha = 0.3f), Tokens.CardRadius)
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
    val borderColor = if (front) accent.copy(alpha = 0.35f) else Ok.copy(alpha = 0.3f)
    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, Tokens.CardShape)
            .neonBorder(borderColor, Tokens.CardRadius, alpha = 0.3f)
            .padding(InnerV),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 9.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(2.dp))
        MonoText(speed, 16.sp, Frost)
    }
}

/** G-force / dynamics numeric card */
@Composable fun GfCard(label: String, value: String, peak: String, modifier: Modifier) {
    val accent = LocalThemeAccent.current
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(Tokens.GfRadius))
            .neonBorder(accent.copy(alpha = 0.15f), Tokens.GfRadius)
            .padding(InnerV),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier.fillMaxWidth().drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.12f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.8f
                    )
                )
            },
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
            .background(Surf2, CardShape)
            .neonBorder(tireBorderColor, Tokens.CardRadius)
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

fun tireTempColor(tempC: Double): Color = when {
    tempC < 15.0 -> Mid
    tempC <= 27.0 -> Ok
    tempC <= 40.0 -> Warn
    else -> Orange
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

