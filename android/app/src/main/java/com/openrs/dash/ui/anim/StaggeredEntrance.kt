package com.openrs.dash.ui.anim

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.EaseOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Column that staggers child entrance animations with a fade + slide-up effect.
 * Each child appears with a delay of [staggerDelayMs] × its index.
 *
 * Apply at the section level (hero row, inputs section, etc.), not per-DataCell.
 */
@Composable
fun StaggeredColumn(
    itemCount: Int,
    modifier: Modifier = Modifier,
    staggerDelayMs: Int = 40,
    spacing: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable (index: Int, entranceModifier: Modifier) -> Unit
) {
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    Column(modifier, verticalArrangement = spacing) {
        repeat(itemCount) { index ->
            val delay = index * staggerDelayMs
            val alpha by animateFloatAsState(
                targetValue = if (visibleState.targetState) 1f else 0f,
                animationSpec = tween(durationMillis = 300, delayMillis = delay, easing = EaseOut),
                label = "stgA$index"
            )
            val offsetY by animateDpAsState(
                targetValue = if (visibleState.targetState) 0.dp else 16.dp,
                animationSpec = tween(durationMillis = 300, delayMillis = delay, easing = EaseOut),
                label = "stgY$index"
            )
            content(index, Modifier.graphicsLayer {
                this.alpha = alpha
                translationY = offsetY.toPx()
            })
        }
    }
}

/**
 * Returns a fade + slide-up modifier for page-level entrance animation.
 * Each [index] adds [staggerDelayMs] to the delay, creating a cascade.
 * Use when [StaggeredColumn] doesn't fit (conditional items, nested structures).
 *
 * @param visible flip to true on first composition via `LaunchedEffect(Unit) { visible = true }`
 */
@Composable
fun pageEntrance(index: Int, visible: Boolean, staggerDelayMs: Int = 40): Modifier {
    val delay = index * staggerDelayMs
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300, delayMillis = delay, easing = EaseOut),
        label = "pgEntA$index"
    )
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 16.dp,
        animationSpec = tween(300, delayMillis = delay, easing = EaseOut),
        label = "pgEntY$index"
    )
    return Modifier.graphicsLayer {
        this.alpha = alpha
        translationY = offsetY.toPx()
    }
}

/**
 * Returns a modifier that briefly dips alpha to 0.85 then back to 1.0
 * when [connected] transitions from false→true ("wake-up" pulse).
 * No-op while disconnected or after the pulse finishes.
 */
@Composable
fun connectionPulse(connected: Boolean): Modifier {
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(connected) {
        if (connected) {
            pulse = true
            delay(200)
            pulse = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (pulse) 0.85f else 1f,
        animationSpec = tween(200, easing = EaseOut),
        label = "connPulse"
    )
    return Modifier.graphicsLayer { this.alpha = alpha }
}
