package com.openrs.dash.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Single-source-of-truth for spacing, shapes, and sizing constants.
 * Replaces hardcoded dp values scattered across page files and Components.kt.
 */
object Tokens {
    // ── Page-level spacing ──────────────────────────────────────────────
    val PagePad       = 12.dp
    val NavBarHeight    = 45.dp
    val StatusBarHeight = 34.dp
    val AnomalyStripHeight = 18.dp
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
