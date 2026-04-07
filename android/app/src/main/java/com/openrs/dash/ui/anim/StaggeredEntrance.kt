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
