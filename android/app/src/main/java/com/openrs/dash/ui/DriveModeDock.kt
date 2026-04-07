package com.openrs.dash.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.openrs.dash.ui.Tokens.CardBorder
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Tokens.PagePad
import com.openrs.dash.ui.anim.pressClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick-access drive mode dock that drops down from the header.
 * Shows 4 mode buttons (N/S/T/D) with the same confirmation flow as MorePage.
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

    Column(
        Modifier.fillMaxWidth()
            .background(Surf2)
            .border(width = 1.dp, color = Brd.copy(alpha = 0.5f))
            .padding(horizontal = PagePad, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            modes.forEachIndexed { index, (mode, letter) ->
                val isActive = vs.driveMode == mode
                val modeAccent = when (mode) {
                    DriveMode.SPORT -> Ok
                    DriveMode.TRACK -> Warn
                    DriveMode.DRIFT -> Orange
                    else            -> accent
                }
                val isPending = pendingDriveMode == mode && !isActive

                Column(
                    Modifier.weight(1f)
                        .graphicsLayer {
                            alpha = alphas[index].value
                            translationY = offsets[index].value * density
                        }
                        .then(
                            if (isActive) Modifier.border(CardBorder, modeAccent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            else Modifier
                        )
                        .background(
                            when {
                                isActive  -> modeAccent.copy(0.1f)
                                isPending -> modeAccent.copy(0.05f)
                                else      -> Surf3
                            },
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            if (isPending) 1.5.dp else 1.dp,
                            when {
                                isActive || isPending -> modeAccent
                                canControl            -> Brd
                                else                  -> Brd.copy(alpha = 0.5f)
                            },
                            RoundedCornerShape(10.dp)
                        )
                        .pressClick(enabled = canControl && firmwareApi != null && !isActive && pendingDriveMode == null) {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            pendingDriveMode = mode
                            scope.launch {
                                when (val r = executeDriveModeChange(firmwareApi!!, mode, vs.driveMode)) {
                                    is DriveCommandResult.Success -> {
                                        pendingDriveMode = null
                                        delay(400)
                                        onDismiss()
                                        return@launch
                                    }
                                    is DriveCommandResult.Busy ->
                                        snackbarHostState.showSnackbar("Mode change in progress \u2014 please wait")
                                    is DriveCommandResult.Failed ->
                                        snackbarHostState.showSnackbar(r.message)
                                    is DriveCommandResult.CorrectionFailed ->
                                        snackbarHostState.showSnackbar("Mode correction failed \u2014 try again manually")
                                    is DriveCommandResult.NoConfirmation ->
                                        snackbarHostState.showSnackbar("Mode change didn\u2019t take effect \u2014 try again")
                                }
                                pendingDriveMode = null
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HeroNum(letter, 20.sp,
                        when {
                            isActive  -> modeAccent
                            isPending -> modeAccent.copy(0.6f)
                            canControl -> Frost
                            else      -> Dim
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                    MonoLabel(
                        if (isPending) "..." else mode.label.uppercase(),
                        8.sp,
                        if (isActive) modeAccent else Dim,
                        letterSpacing = 0.1.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        MonoLabel(
            if (canControl) "Tap to change \u00B7 Quick Mode Dock"
            else "Displays current Drive Mode \u2014 openRS_ firmware unlocks tap control",
            9.sp, Dim
        )
    }
}
