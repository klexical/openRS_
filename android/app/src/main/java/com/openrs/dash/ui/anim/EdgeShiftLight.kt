package com.openrs.dash.ui.anim

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.LocalThemeAccent
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.Warn

/**
 * Full-screen peripheral shift light overlay.
 *
 * Three animation phases based on RPM relative to [shiftRpm]:
 * - **Breathing** (70–81%): subtle pulse on top edge only
 * - **Progressive fill** (81–95.5%): side edges fill bottom-to-top, top intensifies
 * - **Flash** (≥95.5%): all edges flash at 120 ms (matches [ShiftLightBar])
 *
 * Draws soft gradient halos that fade inward — no hard borders.
 * Non-interactive: does not intercept touch events.
 */
@Composable
fun EdgeShiftLight(
    rpm: Float,
    shiftRpm: Float = 6800f,
    enabled: Boolean,
    colorMode: String,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    val breathingStart = shiftRpm * 0.70f
    val progressiveStart = shiftRpm * 0.81f
    val flashStart = shiftRpm * 0.955f

    // ── Phase detection ─────────────────────────────────────────────────
    val isFlashing = rpm >= flashStart
    val isProgressive = rpm >= progressiveStart && !isFlashing
    val isBreathing = rpm >= breathingStart && !isProgressive && !isFlashing
    val isActive = rpm >= breathingStart

    if (!isActive) return

    // ── Resolve glow color ──────────────────────────────────────────────
    val accent = LocalThemeAccent.current
    val targetColor = when {
        colorMode == "white" -> Frost
        colorMode == "progressive" -> when {
            rpm >= shiftRpm * 0.81f -> if (rpm >= shiftRpm * 0.90f) Orange else Warn
            else -> Ok
        }
        else -> accent  // "accent" — follows RS paint theme
    }
    val glowColor by animateColorAsState(targetColor, tween(400), label = "edgeColor")

    // ── Breathing animation (top edge pulse) ────────────────────────────
    val breathingAlpha = if (isBreathing) {
        val t = rememberInfiniteTransition(label = "edgeBreathe")
        val a by t.animateFloat(
            initialValue = 0.05f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                tween(800, easing = EaseInOut),
                RepeatMode.Reverse
            ),
            label = "breatheAlpha"
        )
        a * intensity
    } else 0f

    // ── Progressive fill (side gauge + top intensify) ───────────────────
    val fillProgress by animateFloatAsState(
        targetValue = if (isProgressive || isFlashing) {
            ((rpm - progressiveStart) / (flashStart - progressiveStart)).coerceIn(0f, 1f)
        } else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "fillProgress"
    )

    // ── Flash animation ─────────────────────────────────────────────────
    val flashAlpha = if (isFlashing) {
        val t = rememberInfiniteTransition(label = "edgeFlash")
        val a by t.animateFloat(
            initialValue = 1f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                tween(120, easing = LinearEasing),
                RepeatMode.Reverse
            ),
            label = "flashAlpha"
        )
        a * intensity
    } else 0f

    val minWidthPx = 4.dp
    val maxWidthPx = 12.dp

    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val minW = minWidthPx.toPx()
                val maxW = maxWidthPx.toPx()

                when {
                    isFlashing -> drawFlash(glowColor, flashAlpha, maxW)
                    isProgressive -> drawProgressive(glowColor, fillProgress, intensity, minW, maxW)
                    isBreathing -> drawTopEdge(glowColor, breathingAlpha, minW * 1.5f)
                }
            }
    )
}

// ── Drawing helpers ─────────────────────────────────────────────────────────

/**
 * Flash phase: all four edges glow simultaneously with pulsing alpha.
 * Layered: outer halo (low alpha, wider) + inner core (higher alpha, tighter).
 */
private fun DrawScope.drawFlash(color: Color, alpha: Float, width: Float) {
    val haloWidth = width * 1.6f
    val haloAlpha = alpha * 0.25f

    // Outer halo
    drawTopEdge(color, haloAlpha, haloWidth)
    drawBottomEdge(color, haloAlpha, haloWidth)
    drawLeftEdge(color, haloAlpha, haloWidth, 1f)
    drawRightEdge(color, haloAlpha, haloWidth, 1f)

    // Inner core
    drawTopEdge(color, alpha, width)
    drawBottomEdge(color, alpha, width)
    drawLeftEdge(color, alpha, width, 1f)
    drawRightEdge(color, alpha, width, 1f)
}

/**
 * Progressive phase: top edge intensifies, side edges fill bottom-to-top.
 */
private fun DrawScope.drawProgressive(
    color: Color,
    fillProgress: Float,
    intensity: Float,
    minWidth: Float,
    maxWidth: Float
) {
    val width = lerp(minWidth, maxWidth, fillProgress)
    val topAlpha = lerp(0.2f, 0.7f, fillProgress) * intensity

    // Top edge — always full width, alpha grows with progress
    drawTopEdge(color, topAlpha, width)

    // Outer halo on top
    if (fillProgress > 0.3f) {
        drawTopEdge(color, topAlpha * 0.3f, width * 1.5f)
    }

    // Side edges — fill from bottom up
    if (fillProgress > 0f) {
        val sideAlpha = lerp(0.15f, 0.5f, fillProgress) * intensity
        drawLeftEdge(color, sideAlpha, width, fillProgress)
        drawRightEdge(color, sideAlpha, width, fillProgress)
    }

    // Bottom edge fades in at high progress
    if (fillProgress > 0.7f) {
        val bottomAlpha = lerp(0f, 0.3f, (fillProgress - 0.7f) / 0.3f) * intensity
        drawBottomEdge(color, bottomAlpha, width * 0.8f)
    }
}

// ── Edge primitives (soft gradient halos) ───────────────────────────────────

private fun DrawScope.drawTopEdge(color: Color, alpha: Float, width: Float) {
    if (alpha <= 0f) return
    drawRect(
        brush = Brush.verticalGradient(
            listOf(color.copy(alpha = alpha), Color.Transparent),
            startY = 0f,
            endY = width
        ),
        topLeft = Offset.Zero,
        size = Size(size.width, width)
    )
}

private fun DrawScope.drawBottomEdge(color: Color, alpha: Float, width: Float) {
    if (alpha <= 0f) return
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, color.copy(alpha = alpha)),
            startY = size.height - width,
            endY = size.height
        ),
        topLeft = Offset(0f, size.height - width),
        size = Size(size.width, width)
    )
}

private fun DrawScope.drawLeftEdge(color: Color, alpha: Float, width: Float, fillFraction: Float) {
    if (alpha <= 0f || fillFraction <= 0f) return
    val fillHeight = size.height * fillFraction
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(color.copy(alpha = alpha), Color.Transparent),
            startX = 0f,
            endX = width
        ),
        topLeft = Offset(0f, size.height - fillHeight),
        size = Size(width, fillHeight)
    )
}

private fun DrawScope.drawRightEdge(color: Color, alpha: Float, width: Float, fillFraction: Float) {
    if (alpha <= 0f || fillFraction <= 0f) return
    val fillHeight = size.height * fillFraction
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, color.copy(alpha = alpha)),
            startX = size.width - width,
            endX = size.width
        ),
        topLeft = Offset(size.width - width, size.height - fillHeight),
        size = Size(width, fillHeight)
    )
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
