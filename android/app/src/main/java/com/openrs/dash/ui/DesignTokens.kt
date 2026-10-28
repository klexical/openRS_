package com.openrs.dash.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-source-of-truth for spacing, shapes, and sizing constants.
 * Replaces hardcoded dp values scattered across page files and Components.kt.
 */
object Tokens {
    // ── Page-level spacing ──────────────────────────────────────────────
    val PagePad       = 12.dp
    val NavBarHeight    = 48.dp   // tab row = 48 dp touch targets (was 45)
    val StatusBarHeight = 44.dp   // header: MODE pill / settings tap targets span the full height (was 34)
    val AnomalyStripHeight = 20.dp
    val CardGap         = 10.dp
    val SectionGap    = 14.dp

    // ── Card internal padding ───────────────────────────────────────────
    val InnerH        = 12.dp
    val InnerV        = 10.dp
    val HeroInnerH    = 10.dp
    val HeroInnerV    = 14.dp

    // ── Shapes ──────────────────────────────────────────────────────────
    val CardShape     = RoundedCornerShape(12.dp)
    val HeroShape     = RoundedCornerShape(14.dp)
    val ChipShape     = RoundedCornerShape(6.dp)
    val PillShape     = RoundedCornerShape(20.dp)
    val BarShape      = RoundedCornerShape(2.dp)

    // ── Border ──────────────────────────────────────────────────────────
    val CardBorder    = 0.5.dp

    // ── Specific component radii ─────────────────────────────────────────
    val CardRadius    = 12.dp
    val HeroRadius    = 14.dp
    val GfRadius      = 10.dp
}

/**
 * Bottom padding for a scrolling page: page margin + bottom nav + the system navigation / gesture inset.
 * The nav overlay (BottomNavBar) is NavBarHeight **plus** that inset tall, and the Scaffold lets content
 * run behind both — padding only NavBarHeight left the last ~24 dp (DRIVE ECONOMY) under the nav.
 */
@Composable fun pageBottomPadding(): Dp =
    Tokens.PagePad + navBarClearance()

/** Height the bottom nav overlay covers: its rows + the system navigation / gesture inset. */
@Composable fun navBarClearance(): Dp =
    Tokens.NavBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
