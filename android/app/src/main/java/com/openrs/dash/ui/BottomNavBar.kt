package com.openrs.dash.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.openrs.dash.R
import com.openrs.dash.can.modeChangeBreath
import com.openrs.dash.data.DriveMode
import com.openrs.dash.ui.anim.pressClick
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.annotation.DrawableRes
import androidx.compose.ui.draw.alpha

// ═══════════════════════════════════════════════════════════════════════════
// BOTTOM NAV BAR — 5 tabs with vector icons, frosted glass background
// ═══════════════════════════════════════════════════════════════════════════

/**
 * One "Precision" icon family for all five tabs (openRS_icons_round03): three alpha layers cut from the
 * supplied artwork — [whole] glyph, its white [base] strokes and its blue [accent] details — on one 104 px canvas
 * at the source's common scale. Inactive = [whole] in the muted colour; selected = the design's two-tone.
 */
private data class NavItem(@DrawableRes val whole: Int, @DrawableRes val base: Int, @DrawableRes val accent: Int, val label: String)

private val navItems = listOf(
    NavItem(R.drawable.nav_drive_whole,   R.drawable.nav_drive_base,   R.drawable.nav_drive_accent,   "DRIVE"),
    NavItem(R.drawable.nav_perf_whole,    R.drawable.nav_perf_base,    R.drawable.nav_perf_accent,    "PERF"),
    NavItem(R.drawable.nav_thermal_whole, R.drawable.nav_thermal_base, R.drawable.nav_thermal_accent, "THERMAL"),
    NavItem(R.drawable.nav_trip_whole,    R.drawable.nav_trip_base,    R.drawable.nav_trip_accent,    "TRIP"),
    NavItem(R.drawable.nav_garage_whole,  R.drawable.nav_garage_base,  R.drawable.nav_garage_accent,  "GARAGE")
)

@Composable
fun BottomNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    /** Per-tab badge flags — true draws a small Warn dot on the icon. */
    badges: List<Boolean> = emptyList(),
    /** When true, each tab uses a unique identity color instead of uniform accent. */
    navTabIdentity: Boolean = false,
) {
    val accent = LocalThemeAccent.current
    // rc.2: persistent chrome (nav bar is always visible) uses the mid-tier accent
    // to reduce OLED burn-in risk and reserve full-sat paint for ephemeral highlights
    // (changing values, warnings, CTAs). The visible softening is ~8-12%, matching
    // the "Desaturate for persistent chrome" directive from the Daylight critique.
    val baseAccentChrome = accentMid()
    val breathMode = modeChangeBreath.value
    // Per-tab identity color (only when pref is on and no breath is active)
    val identityColor = if (navTabIdentity) tabIdentityColor(selected) else baseAccentChrome
    val breathTarget = when (breathMode) {
        DriveMode.SPORT -> Ok
        DriveMode.TRACK -> Warn
        DriveMode.DRIFT -> Orange
        DriveMode.NORMAL -> accent
        null -> identityColor
        else -> identityColor
    }
    val accentChrome by animateColorAsState(
        targetValue = breathTarget,
        animationSpec = tween(if (breathMode != null) 180 else 900),
        label = "navBreath"
    )
    val haptic = LocalHapticFeedback.current
    val tabCount = navItems.size
    val sysNavPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            // Total height = nav items + system gesture inset (frost fills to screen edge)
            .height(Tokens.NavBarHeight + sysNavPad)
            // ── Frosted glass via Haze backdrop blur ────────────────
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = Bg,
                    tint = HazeTint(
                        color = Bg.copy(alpha = 0.0f)
                    ),
                    blurRadius = 24.dp
                )
            )
            // One hairline where the frost meets the content (was a stamped bevel gradient, a white
            // specular line and an inner shadow — decoration that read as a bright seam on NIGHT and
            // vanished on DAY).
            .drawBehind {
                drawLine(
                    color = Brd.copy(alpha = borderAlpha(0.7f)),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        val tabWidth = maxWidth / tabCount

        // ── Sliding indicator (top edge — faces content) ────────────
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selected,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "navSlide"
        )
        Box(
            Modifier
                .offset { IntOffset(indicatorOffset.roundToPx(), 0) }   // layout-phase read, no recomposition per frame
                .width(tabWidth)
                .align(Alignment.TopStart)
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.6f)
                    .height(3.dp)
                    .background(
                        accentChrome,
                        RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)
                    )
            )
        }

        // ── Nav items (padded above system gesture bar) ─────────────
        Row(Modifier.fillMaxWidth().height(Tokens.NavBarHeight).padding(top = 4.dp)) {
            navItems.forEachIndexed { i, item ->
                val isActive = i == selected
                // Per-tab identity: active tab uses its own color; breath overrides all
                val tabColor = if (navTabIdentity && breathMode == null)
                    tabIdentityColor(i) else accentChrome
                // DAY: the mid-tier chrome blue is pale cyan on white — the selected icon + label use the readable
                // darker DAY blue (actionInk, as the symbol family's SELECTED tone). The indicator bar keeps the chrome.
                val usesChromeBlue = breathMode == null && (!navTabIdentity || i == 0)   // i.e. tabColor is accentMid()
                val selectedInk = if (isDayModeNow() && usesChromeBlue) actionInk(accent) else textColor(tabColor)
                val tint by animateColorAsState(
                    targetValue = if (isActive) selectedInk else Dim,
                    animationSpec = tween(300),
                    label = "navTint$i"
                )

                val iconScale by animateFloatAsState(
                    targetValue = if (isActive) 1.06f else 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "iconScale$i"
                )
                val showBadge = badges.getOrNull(i) == true
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            role = Role.Tab
                            this.selected = isActive
                            contentDescription = if (showBadge) "${item.label} tab, alert" else "${item.label} tab"
                        }
                        .pressClick {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSelect(i)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box {
                            // 24 dp canvas → the glyphs read ~19 dp tall (the canvas keeps the family's common scale)
                            val iconMod = Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                            val sel by animateFloatAsState(if (isActive) 1f else 0f, tween(300), label = "navSel$i")
                            Box(iconMod) {
                                if (sel < 1f) Icon(painterResource(item.whole), null, Modifier.matchParentSize().alpha(1f - sel), tint = Dim)
                                if (sel > 0f) {
                                    Icon(painterResource(item.base), null, Modifier.matchParentSize().alpha(sel), tint = Frost)
                                    Icon(painterResource(item.accent), null, Modifier.matchParentSize().alpha(sel), tint = tint)
                                }
                            }
                            if (showBadge) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .size(6.dp)
                                        .offset(x = 2.dp, y = (-1).dp)
                                        .background(Warn, RoundedCornerShape(3.dp))
                                )
                            }
                        }
                        Spacer(Modifier.height(1.dp))
                        // 9 sp (was 7): the tab names must read at arm's length on a mounted phone.
                        MonoLabel(item.label, 9.sp, tint, if (isActive) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.2.sp)
                    }
                }
            }
        }
    }
}
