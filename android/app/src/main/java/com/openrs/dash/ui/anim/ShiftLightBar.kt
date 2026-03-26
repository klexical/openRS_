package com.openrs.dash.ui.anim

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.Surf3
import com.openrs.dash.ui.Warn

/**
 * Horizontal LED-style shift indicator bar.
 * Fills green -> yellow -> red as RPM climbs. Flashes at redline.
 */
@Composable
fun ShiftLightBar(
    rpm: Float,
    redlineRpm: Float = 6800f,
    modifier: Modifier = Modifier,
    segmentCount: Int = 18
) {
    val isRedline = rpm >= redlineRpm * 0.955f  // ~6500 RPM
    val flashAlpha = if (isRedline) {
        val flashTransition = rememberInfiniteTransition(label = "shift")
        val alpha by flashTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                tween(120, easing = LinearEasing),
                RepeatMode.Reverse
            ),
            label = "shiftFlash"
        )
        alpha
    } else 1f

    Row(
        modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        for (i in 0 until segmentCount) {
            val segThreshold = (i.toFloat() / segmentCount) * redlineRpm
            val isLit = rpm >= segThreshold
            val baseColor = when {
                segThreshold >= redlineRpm * 0.81f -> Orange   // ~5500+
                segThreshold >= redlineRpm * 0.59f -> Warn     // ~4000+
                else -> Ok
            }
            val displayColor = when {
                !isLit -> Surf3
                isRedline -> baseColor.copy(alpha = flashAlpha)
                else -> baseColor
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .padding(horizontal = 1.dp)
                    .background(displayColor, RoundedCornerShape(3.dp))
                    .then(
                        if (isLit && !isRedline) Modifier.drawBehind {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color.Transparent, baseColor.copy(alpha = 0.2f), Color.Transparent)
                                )
                            )
                        } else Modifier
                    )
            )
        }
    }
}
