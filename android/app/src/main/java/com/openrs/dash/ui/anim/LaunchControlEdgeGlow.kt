package com.openrs.dash.ui.anim

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.unit.dp
import com.openrs.dash.ui.Warn

/**
 * Peripheral glow triggered while launch control is engaged. Distinct rhythm
 * from [EdgeShiftLight] (slower breath, ~700ms) so the two cues never collide.
 * Draws a yellow frame around the viewport — unmissable in-cabin, yet keeps
 * hero numerics readable.
 */
@Composable
fun LaunchControlEdgeGlow(
    engaged: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = engaged,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(240))
    ) {
        val breath by rememberInfiniteTransition(label = "lcBreath").animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(700, easing = EaseInOut),
                RepeatMode.Reverse
            ),
            label = "lcBreathA"
        )
        val frame = 10.dp
        Box(
            modifier
                .fillMaxSize()
                .drawBehind {
                    val t = frame.toPx()
                    val topBrush = Brush.verticalGradient(
                        listOf(Warn.copy(alpha = 0.55f * breath), Color.Transparent),
                        startY = 0f, endY = t * 2.5f
                    )
                    val bottomBrush = Brush.verticalGradient(
                        listOf(Color.Transparent, Warn.copy(alpha = 0.45f * breath)),
                        startY = size.height - t * 2.5f, endY = size.height
                    )
                    val leftBrush = Brush.horizontalGradient(
                        listOf(Warn.copy(alpha = 0.30f * breath), Color.Transparent),
                        startX = 0f, endX = t * 2f
                    )
                    val rightBrush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Warn.copy(alpha = 0.30f * breath)),
                        startX = size.width - t * 2f, endX = size.width
                    )
                    drawRect(topBrush, Offset.Zero, Size(size.width, t * 2.5f))
                    drawRect(
                        bottomBrush,
                        Offset(0f, size.height - t * 2.5f),
                        Size(size.width, t * 2.5f)
                    )
                    drawRect(leftBrush, Offset.Zero, Size(t * 2f, size.height))
                    drawRect(
                        rightBrush,
                        Offset(size.width - t * 2f, 0f),
                        Size(t * 2f, size.height)
                    )
                }
        )
    }
}
