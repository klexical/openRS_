package com.openrs.dash.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.driveModePending
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.can.modeChangeBreath
import com.openrs.dash.can.successHapticFor
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Tokens.PagePad
import com.openrs.dash.ui.anim.pressClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick-access drive mode dock that drops down from the header.
 * Shows 4 mode buttons (N/S/T/D) with the same confirmation flow as MorePage.
 *
 * rc.2 adds a thin bottom progress bar on the pending button, a subtle
 * mode-color shimmer sweep while busy, and a mode-specific success haptic
 * reinforcing that the car actually switched. The confirmed state flashes
 * the button fill to the mode color; the ambient breath (header underline +
 * nav indicator) carries the visual follow-through.
 */
@Composable fun DriveModeDock(
    vs: VehicleState,
    canControl: Boolean,
    firmwareApi: FirmwareCommandSender?,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val accent = LocalThemeAccent.current
    val scope = rememberCoroutineScope()
    var pendingDriveMode by remember { mutableStateOf<DriveMode?>(null) }
    var confirmedMode by remember { mutableStateOf<DriveMode?>(null) }

    // Staggered entrance — each button delays 40ms
    val modes = remember {
        listOf(DriveMode.NORMAL to "N", DriveMode.SPORT to "S",
               DriveMode.TRACK to "T", DriveMode.DRIFT to "D")
    }
    val alphas = modes.indices.map { i ->
        remember { Animatable(0f) }
    }
    val offsets = modes.indices.map { i ->
        remember { Animatable(12f) }
    }
    LaunchedEffect(Unit) {
        modes.indices.forEach { i ->
            launch {
                delay(i * 40L)
                launch { alphas[i].animateTo(1f, tween(250, easing = EaseOut)) }
                launch { offsets[i].animateTo(0f, tween(250, easing = EaseOut)) }
            }
        }
    }

    // Row-wide shimmer while any mode is pending — mode-color linear sweep.
    val shimmerTransition = rememberInfiniteTransition(label = "dockShimmer")
    val shimmerOffset by shimmerTransition.animateFloat(
        initialValue = -0.4f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "dockShimmerA"
    )

    // Offline the last frame's mode is not the car's current mode — nothing is selected (the header reads MODE —).
    val connected = vs.isConnected
    val current = if (connected) vs.driveMode else null
    val canTap = canControl && firmwareApi != null

    Column(
        Modifier.fillMaxWidth()
            .background(InstrumentFill)
            .drawBehind {
                drawRect(Brd.copy(alpha = borderAlpha(0.6f)), Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            }
            .padding(horizontal = PagePad, vertical = 10.dp)
    ) {
        // Caption: what the dock is and what it's doing right now.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InstrumentLabel("DRIVE MODE")
            Spacer(Modifier.weight(1f))
            val pending = pendingDriveMode
            val confirmed = confirmedMode
            // The command stays in flight through the confirmation dwell, so CONFIRMED takes precedence.
            when {
                confirmed != null -> MonoLabel("${confirmed.label.uppercase()} CONFIRMED", 10.sp, Frost, FontWeight.Bold, 0.3.sp)
                pending != null   -> MonoLabel("SWITCHING TO ${pending.label.uppercase()}\u2026", 10.sp, actionInk(accent), FontWeight.Bold, 0.3.sp)
                !connected        -> InstrumentMeta("OFFLINE")
                !canTap           -> InstrumentMeta("VIEW ONLY")
                current != null   -> InstrumentMeta("CURRENT \u00B7 ${current.label.uppercase()}")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            modes.forEachIndexed { index, (mode, letter) ->
                // One accent for every mode — SPORT / TRACK / DRIFT used green / amber / orange, which read as
                // status colours. The selected mode is the accent tint with dark-on-DAY text, like every chip.
                val isActive = current == mode
                val isPending = pendingDriveMode == mode && !isActive
                val isConfirmed = confirmedMode == mode

                val dimForBusy = pendingDriveMode != null && !isPending && !isConfirmed
                val buttonShape = RoundedCornerShape(10.dp)

                val bgTarget = when {
                    isConfirmed -> accent.copy(alpha = pillBgAlpha(0.20f))
                    isActive    -> accent.copy(alpha = pillBgAlpha(0.14f))
                    isPending   -> accent.copy(alpha = pillBgAlpha(0.06f))
                    else        -> Surf2
                }
                val animBg by animateColorAsState(bgTarget, tween(250), label = "dockBg$index")

                val borderTarget = when {
                    isActive || isPending || isConfirmed -> accent.copy(alpha = borderAlpha(0.7f))
                    else                                 -> Brd.copy(alpha = if (canTap) 1f else 0.6f)
                }
                val animBorder by animateColorAsState(borderTarget, tween(250), label = "dockBrd$index")

                val textTarget = when {
                    isActive || isConfirmed -> actionInk(accent)
                    isPending               -> actionInk(accent).copy(alpha = 0.7f)
                    canTap                  -> Frost
                    else                    -> Mid
                }
                val animText by animateColorAsState(textTarget, tween(250), label = "dockTxt$index")

                val labelTarget = when {
                    isActive || isConfirmed || isPending -> actionInk(accent)
                    canTap                               -> Mid
                    else                                 -> Dim
                }
                val animLabel by animateColorAsState(labelTarget, tween(250), label = "dockLbl$index")

                Column(
                    Modifier.weight(1f)
                        .heightIn(min = 64.dp)
                        .graphicsLayer {
                            alpha = alphas[index].value * (if (dimForBusy) 0.5f else 1f)
                            translationY = offsets[index].value * density
                        }
                        // Everything below stays inside the rounded button (the shimmer is a full-bounds rect).
                        .clip(buttonShape)
                        .background(animBg, buttonShape)
                        .border(
                            if (isPending) 1.5.dp else 1.dp,
                            animBorder,
                            buttonShape
                        )
                        // Shimmer sweeps the pending button while the car confirms (accent, not a mode colour).
                        .drawBehind {
                            if (isPending) {
                                val sweepX = shimmerOffset * size.width
                                val band = size.width * 0.25f
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        0.5f to accent.copy(alpha = 0.18f),
                                        1f to Color.Transparent,
                                        startX = sweepX - band,
                                        endX = sweepX + band
                                    )
                                )
                            }
                        }
                        .pressClick(enabled = canControl && firmwareApi != null && !isActive && pendingDriveMode == null) {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            pendingDriveMode = mode
                            driveModePending.value = mode
                            confirmedMode = null
                            scope.launch {
                                try {
                                    when (val r = executeDriveModeChange(firmwareApi!!, mode, vs.driveMode)) {
                                        is DriveCommandResult.Success -> {
                                            haptic.performHapticFeedback(successHapticFor(mode))
                                            confirmedMode = mode
                                            modeChangeBreath.value = mode
                                            // Dwell on the confirmed state, then clear the breath
                                            // and dismiss. Keeping both delays in the outer coroutine
                                            // ensures they share the same cancellation scope as the
                                            // finally{} cleanup below.
                                            delay(1_200)
                                            if (modeChangeBreath.value == mode) modeChangeBreath.value = null
                                            delay(300)
                                            onDismiss()
                                        }
                                        is DriveCommandResult.Busy -> {
                                            haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                            snackbarHostState.showSnackbar("Mode change in progress \u2014 please wait")
                                        }
                                        is DriveCommandResult.Failed -> {
                                            haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                            snackbarHostState.showSnackbar(r.message)
                                        }
                                        is DriveCommandResult.CorrectionFailed -> {
                                            haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                            snackbarHostState.showSnackbar("Mode correction failed \u2014 try again manually")
                                        }
                                        is DriveCommandResult.NoConfirmation -> {
                                            haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                            snackbarHostState.showSnackbar("Mode change didn\u2019t take effect \u2014 try again")
                                        }
                                    }
                                } finally {
                                    // Always release the in-flight globals so the header MODE pill
                                    // and nav indicator can't get stuck if the dock composition is
                                    // disposed (and this scope cancelled) mid-command. Only clear
                                    // modeChangeBreath if we own it — a rapid successor tap may
                                    // have already set it to a different mode.
                                    pendingDriveMode = null
                                    driveModePending.value = null
                                    if (modeChangeBreath.value == mode) modeChangeBreath.value = null
                                }
                            }
                        }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        HeroNum(letter, 22.sp, animText)
                    }
                    Spacer(Modifier.height(3.dp))
                    MonoLabel(
                        when {
                            isConfirmed -> "CONFIRMED"
                            isPending   -> "SWITCHING"
                            else        -> mode.label.uppercase()
                        },
                        10.sp,
                        animLabel,
                        if (isActive || isConfirmed || isPending) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        InstrumentMeta(
            when {
                canTap     -> "Tap a mode to change it \u2014 the car confirms in a few seconds."
                !connected -> "Connect to the car to change modes."
                else       -> "Shows the current mode \u2014 openRS_ firmware unlocks tap control."
            },
            color = Mid
        )
    }
}
