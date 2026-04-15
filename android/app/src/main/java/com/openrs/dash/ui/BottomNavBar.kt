package com.openrs.dash.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.R
import com.openrs.dash.ui.anim.pressClick
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

// ═══════════════════════════════════════════════════════════════════════════
// BOTTOM NAV BAR — 7 tabs with vector icons, frosted glass background
// ═══════════════════════════════════════════════════════════════════════════

private data class NavItem(val icon: Int, val label: String)

private val navItems = listOf(
    NavItem(R.drawable.ic_nav_dash,  "DRIVE"),
    NavItem(R.drawable.ic_nav_power, "PERF"),
    NavItem(R.drawable.ic_nav_temps, "THERMAL"),
    NavItem(R.drawable.ic_nav_map,   "TRIP"),
    NavItem(R.drawable.ic_nav_more,  "GARAGE")
)

@Composable
fun BottomNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    /** Per-tab badge flags — true draws a small Warn dot on the icon. */
    badges: List<Boolean> = emptyList(),
) {
    val accent = LocalThemeAccent.current
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
            // Stamped bevel gradient over the frost
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f  to Color.White.copy(alpha = 0.08f),
                        0.08f to Color.White.copy(alpha = 0.03f),
                        0.5f  to Color.Transparent,
                        0.92f to Color.Black.copy(alpha = 0.06f),
                        1.0f  to Color.Black.copy(alpha = 0.15f)
                    )
                )
            )
            .drawBehind {
                val w = size.width
                val h = size.height
                val px1 = 1.dp.toPx()
                // Top specular highlight
                drawLine(
                    color = Color.White.copy(alpha = 0.22f),
                    start = Offset(0f, 0f),
                    end = Offset(w, 0f),
                    strokeWidth = px1
                )
                // Soft glow below highlight
                drawLine(
                    color = Color.White.copy(alpha = 0.06f),
                    start = Offset(0f, px1 * 2),
                    end = Offset(w, px1 * 2),
                    strokeWidth = px1 * 2
                )
                // Bottom inner shadow
                drawLine(
                    color = Color.Black.copy(alpha = 0.35f),
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = px1
                )
            }
    ) {
        val tabWidth = maxWidth / tabCount

        // ── Sliding neon indicator (top edge — faces content) ────────
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selected,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "navSlide"
        )
        Box(
            Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .align(Alignment.TopStart)
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.6f)
                    .height(2.dp)
                    .background(
                        accent,
                        RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)
                    )
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.7f)
                    .height(6.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.12f), Color.Transparent)
                        ),
                        RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
                    )
            )
        }

        // ── Nav items (padded above system gesture bar) ─────────────
        Row(Modifier.fillMaxWidth().height(Tokens.NavBarHeight).padding(top = 6.dp)) {
            navItems.forEachIndexed { i, item ->
                val isActive = i == selected
                val tint = if (isActive) accent else Dim

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
                            Icon(
                                painter = painterResource(item.icon),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(20.dp)
                            )
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
                        Spacer(Modifier.height(2.dp))
                        MonoLabel(item.label, 7.sp, tint, letterSpacing = 0.08.sp)
                    }
                }
            }
        }
    }
}
