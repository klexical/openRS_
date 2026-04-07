package com.openrs.dash.ui.anim

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════════════
// GLOW MODIFIER LIBRARY
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Intense double-layer radial bloom behind active hero values.
 * Outer diffuse ring + inner bright core.
 *
 * @param intensity 0.0–1.0, scales both layers.
 */
fun Modifier.bloomGlow(
    color: Color,
    radius: Dp = 40.dp,
    intensity: Float = 0.3f
): Modifier = this.drawBehind {
    val r = radius.toPx().coerceAtLeast(size.minDimension * 0.6f)
    // Outer diffuse layer
    drawCircle(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = intensity * 0.5f), Color.Transparent),
            center = center,
            radius = r * 1.2f
        )
    )
    // Inner bright core
    drawCircle(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = intensity), Color.Transparent),
            center = center,
            radius = r * 0.7f
        )
    )
}

/**
 * Ambient card glow — subtle outer shadow + top specular highlight.
 * Provides depth separation against textured backgrounds.
 *
 * @param color  glow tint (White for neutral, accent for hero cards)
 * @param intensity  0.0–1.0, scales the outer shadow alpha (default 0.06)
 * @param cornerRadius  matches the card's corner radius
 */
fun Modifier.cardGlow(
    color: Color = Color.White,
    intensity: Float = 0.06f,
    cornerRadius: Dp = 12.dp
): Modifier = this.drawBehind {
    val cr = CornerRadius(cornerRadius.toPx())
    val spread = 6.dp.toPx()

    // Outer diffuse shadow — expanded rounded rect behind the card
    drawRoundRect(
        brush = Brush.radialGradient(
            listOf(
                color.copy(alpha = intensity * 0.5f),
                color.copy(alpha = intensity * 0.2f),
                Color.Transparent
            ),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.maxDimension * 0.7f
        ),
        topLeft = Offset(-spread, -spread + 1.dp.toPx()),
        size = Size(size.width + spread * 2, size.height + spread * 2),
        cornerRadius = cr
    )

    // Top specular highlight — 1px stamped bevel
    drawLine(
        color = Color.White.copy(alpha = 0.06f),
        start = Offset(cornerRadius.toPx(), 0f),
        end = Offset(size.width - cornerRadius.toPx(), 0f),
        strokeWidth = 1f
    )
}
