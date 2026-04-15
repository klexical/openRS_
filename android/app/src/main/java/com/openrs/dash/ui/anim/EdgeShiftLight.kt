package com.openrs.dash.ui.anim

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import com.openrs.dash.ui.effectiveThemeIsDay

/**
 * Full-screen shift cue overlay.
 *
 * v3.0 simplified: a single top-edge bar fills green→yellow→red from 70% of
 * shift RPM to redline, plus a full-screen red pulse at ≥95%. The prior
 * three-phase breathing/progressive/flash system was over-engineered and
 * washed out in daylight.
 *
 * Disabled entirely in DAY theme (soft halos smear on light backgrounds).
 * Non-interactive: does not intercept touch events.
 *
 * [colorMode] is kept for back-compat but only "white" + "accent" affect the
 * bar hue now — the green→yellow→red ramp always runs when style is default.
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
    if (effectiveThemeIsDay()) return

    val rampStart = shiftRpm * 0.70f
    val flashStart = shiftRpm * 0.95f
    if (rpm < rampStart) return

    val accent = LocalThemeAccent.current
    val ramp = ((rpm - rampStart) / (shiftRpm - rampStart)).coerceIn(0f, 1f)

    // Bar color: green (0-33%) → yellow (33-66%) → red (66-100%)
    val barColor = when {
        colorMode == "white" -> Frost
        colorMode == "accent" -> lerpColor(accent, Orange, ramp)
        ramp < 0.33f -> Ok
        ramp < 0.66f -> lerpColor(Ok, Warn, (ramp - 0.33f) / 0.33f)
        else -> lerpColor(Warn, Orange, (ramp - 0.66f) / 0.34f)
    }

    // Flash: red pulse at ≥95% redline. Transition is hoisted so it doesn't
    // re-allocate when rpm crosses the threshold. We only READ the pulse value
    // while flashing, so recompositions at the animation rate are gated.
    val pulseTransition = rememberInfiniteTransition(label = "shiftFlash")
    val pulseState = pulseTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            tween(200, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "flashA"
    )
    val isFlashing = rpm >= flashStart
    val flashAlpha = if (isFlashing) pulseState.value * intensity else 0f

    val barHeight = 6.dp
    val barAlpha = (0.4f + ramp * 0.5f) * intensity

    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val h = barHeight.toPx()
                // Top bar — alpha scales with rpm, spans full width
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(barColor.copy(alpha = barAlpha), Color.Transparent),
                        startY = 0f,
                        endY = h * 2f
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, h * 2f)
                )
                // Flash clamped to top 40% of screen — still unmistakable but
                // leaves hero numerics readable at redline.
                if (flashAlpha > 0f) {
                    drawRect(
                        color = Orange.copy(alpha = flashAlpha),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height * 0.4f)
                    )
                }
            }
    )
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
