package com.openrs.dash.ui.anim

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a soft radial glow behind the composable — works on all API levels (no RenderEffect).
 */
fun Modifier.neonGlow(
    color: Color,
    radius: Dp = 10.dp,
    alpha: Float = 0.15f
): Modifier = this.drawBehind {
    drawCircle(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius.toPx().coerceAtLeast(size.minDimension * 0.6f)
        )
    )
}

/**
 * Draws a soft rectangular glow — useful for bars and indicators.
 */
fun Modifier.neonGlowRect(
    color: Color,
    alpha: Float = 0.20f,
    spread: Dp = 4.dp
): Modifier = this.drawBehind {
    val s = spread.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, color.copy(alpha = alpha), Color.Transparent)
        ),
        topLeft = Offset(0f, -s),
        size = androidx.compose.ui.geometry.Size(size.width, size.height + s * 2)
    )
}
