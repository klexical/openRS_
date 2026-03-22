package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.R

// ═══════════════════════════════════════════════════════════════════════════
// SHARED UI COMPONENTS — used across two or more tab pages
// ═══════════════════════════════════════════════════════════════════════════

/** Section label: small text with extending horizontal rule */
@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MonoLabel(text, 9.sp, Dim, letterSpacing = 0.2.sp)
        Box(Modifier.weight(1f).height(1.dp).background(Brd))
    }
}

/** Data cell — JetBrains Mono label + value */
@Composable fun DataCell(
    label: String,
    value: String,
    valueColor: Color = Frost,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.15.sp)
        Spacer(Modifier.height(3.dp))
        MonoText(value, 14.sp, valueColor)
    }
}

/** Hero card — large Orbitron number for BOOST / SPEED / RPM */
@Composable fun HeroCard(
    unit: String,
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    borderAccent: Color? = null,
    peak: String = ""
) {
    val accent = LocalThemeAccent.current
    val brd = borderAccent ?: Brd
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(16.dp))
            .border(1.dp, brd, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(unit, 8.sp, Dim, letterSpacing = 0.18.sp)
        Spacer(Modifier.height(4.dp))
        HeroNum(value, 26.sp, valueColor, Modifier.fillMaxWidth())
        if (peak.isNotEmpty()) {
            MonoText(peak, 9.sp, accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(12.dp))
            .border(1.dp, Brd, RoundedCornerShape(12.dp))
            .padding(12.dp)
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
            )
        }
    }
}

/** AFR/lambda numeric card — used on Power page */
@Composable fun AfrCard(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier) {
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(12.dp))
            .border(1.dp, Brd, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(4.dp))
        HeroNum(value, 22.sp, valueColor, Modifier.fillMaxWidth())
        MonoLabel(unit, 9.sp, Dim)
    }
}

/** Wheel speed cell — front/rear accent colour */
@Composable fun WheelCell(label: String, speed: String, front: Boolean) {
    val accent = LocalThemeAccent.current
    val borderColor = if (front) accent.copy(alpha = 0.35f) else Ok.copy(alpha = 0.3f)
    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(8.dp),
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
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(3.dp))
        MonoText(value, 18.sp, Frost, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (peak.isNotEmpty()) {
            MonoText(peak, 9.sp, accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Tire pressure card with optional temperature — colour-coded by pressure/temp range */
@Composable fun TireCard(
    label: String, psi: Double, p: UserPrefs, lowThreshold: Double,
    tempC: Double = -99.0
) {
    val isLow     = psi in 0.0..(lowThreshold - 0.001)
    val isMissing = psi < 0
    val tireColor = when {
        isMissing  -> Dim
        isLow      -> Red
        psi > 40.0 -> Warn
        else       -> Ok
    }
    val hasTemp = tempC > -90
    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, if (isLow) Red.copy(0.5f) else Brd, RoundedCornerShape(10.dp))
            .padding(8.dp, 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 9.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(4.dp))
        HeroNum(if (isMissing) "—" else p.displayTire(psi), 18.sp, tireColor)
        MonoLabel(p.tireLabel, 8.sp, Dim, letterSpacing = 0.1.sp)
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

@Deprecated("Use FocusRsOutline", replaceWith = ReplaceWith("FocusRsOutline(compact)"))
@Composable fun CarOutlinePlaceholder(compact: Boolean = false) = FocusRsOutline(compact)
