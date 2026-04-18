package com.openrs.dash.ui.anim

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openrs.dash.ui.isDayModeNow

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
): Modifier = this.drawWithCache {
    // Day mode: halos over light bg become muddy smears. Dim to ~25%.
    val scale = if (isDayModeNow()) 0.25f else 1f
    val effective = intensity * scale
    val r = radius.toPx().coerceAtLeast(size.minDimension * 0.6f)
    val outer = Brush.radialGradient(
        listOf(color.copy(alpha = effective * 0.5f), Color.Transparent),
        center = Offset(size.width / 2f, size.height / 2f),
        radius = r * 1.2f
    )
    val inner = Brush.radialGradient(
        listOf(color.copy(alpha = effective), Color.Transparent),
        center = Offset(size.width / 2f, size.height / 2f),
        radius = r * 0.7f
    )
    onDrawBehind {
        drawCircle(brush = outer)
        drawCircle(brush = inner)
    }
}

/**
 * Ambient card glow — subtle outer shadow + top specular highlight.
 * Provides depth separation against textured backgrounds.
 *
 * @param color  glow tint (White for neutral, accent for hero cards)
 * @param intensity  0.0–1.0, scales the outer shadow alpha (default 0.06)
 * @param cornerRadius  matches the card's corner radius
 * @param tint  optional edge tint for OEM polish. When specified:
 *              Night/Ultra: replaces white specular with tint-colored edge.
 *              Day: overlays a top-gradient sheen ("machined aluminum").
 *              Default [Color.Unspecified] = no tint (current behavior).
 */
fun Modifier.cardGlow(
    color: Color = Color.White,
    intensity: Float = 0.06f,
    cornerRadius: Dp = 12.dp,
    tint: Color = Color.Unspecified
): Modifier = this.drawWithCache {
    val day = isDayModeNow()
    val cr = CornerRadius(cornerRadius.toPx())
    val cornerPx = cornerRadius.toPx()
    val hasTint = tint != Color.Unspecified

    if (day) {
        // DAY: soft directional drop-shadow (2dp down, 4dp blur) using two
        // offset layers for depth. When tint is provided, add a top-gradient
        // sheen ("machined aluminum" specular) over the top 12dp.
        val offset1 = 2.dp.toPx()
        val spread1 = 4.dp.toPx()
        val offset2 = 4.dp.toPx()
        val spread2 = 8.dp.toPx()
        val sheenHeight = 12.dp.toPx()
        val sheenBrush = if (hasTint) Brush.verticalGradient(
            listOf(tint.copy(alpha = 0.06f), Color.Transparent),
            startY = 0f, endY = sheenHeight
        ) else null

        onDrawBehind {
            drawRoundRect(
                color = Color.Black.copy(alpha = intensity * 0.8f),
                topLeft = Offset(-spread2, offset2),
                size = Size(size.width + spread2 * 2, size.height + spread2),
                cornerRadius = cr
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = intensity * 1.4f),
                topLeft = Offset(-spread1, offset1),
                size = Size(size.width + spread1 * 2, size.height + spread1),
                cornerRadius = cr
            )
            if (sheenBrush != null) {
                drawRoundRect(
                    brush = sheenBrush,
                    size = Size(size.width, sheenHeight),
                    cornerRadius = cr
                )
            }
        }
    } else {
        // NIGHT/ULTRA: ambient halo + top specular + bottom shadow.
        // v2: specular bumped 0.07→0.10, shadow bumped 0.10→0.14 for
        // stronger OLED materiality. When tint is provided, the specular
        // uses the tint color instead of white (prevents double-stroke).
        val spread = 6.dp.toPx()
        val specularOffset = 1.dp.toPx()
        val specularAlpha = 0.10f
        val shadowAlpha = 0.14f
        val specularColor = if (hasTint) tint.copy(alpha = specularAlpha)
                            else Color.White.copy(alpha = specularAlpha)
        val glowBrush = Brush.radialGradient(
            listOf(
                color.copy(alpha = intensity * 0.5f),
                color.copy(alpha = intensity * 0.2f),
                Color.Transparent
            ),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.maxDimension * 0.7f
        )
        val glowTopLeft = Offset(-spread, -spread + specularOffset)
        val glowSize = Size(size.width + spread * 2, size.height + spread * 2)

        onDrawBehind {
            drawRoundRect(
                brush = glowBrush,
                topLeft = glowTopLeft,
                size = glowSize,
                cornerRadius = cr
            )
            drawLine(
                color = specularColor,
                start = Offset(cornerPx, 0f),
                end = Offset(size.width - cornerPx, 0f),
                strokeWidth = 1f
            )
            drawLine(
                color = Color.Black.copy(alpha = shadowAlpha),
                start = Offset(cornerPx, size.height),
                end = Offset(size.width - cornerPx, size.height),
                strokeWidth = 1f
            )
        }
    }
}
