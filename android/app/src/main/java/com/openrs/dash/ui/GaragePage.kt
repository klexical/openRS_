package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.data.DtcProgressCallback
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.Tokens.PagePad

// ═══════════════════════════════════════════════════════════════════════════
// GARAGE PAGE — combines MORE (control) + DIAG (diagnostics) under one tab
// ═══════════════════════════════════════════════════════════════════════════
//
// v3.0 "Daylight" Phase 4: reduce top-level nav by folding the two non-driving
// tabs into one. Internal segmented picker preserves both surfaces with their
// existing internal scrolls — no composable content rewritten.

private enum class GaragePanel(val label: String) {
    CONTROL("CONTROL"),   // drive mode, firmware, custom dash, settings
    DIAGNOSTICS("DIAG"),  // DTCs, PID browser, DID prober, export
}

@Composable
fun GaragePage(
    debugLines: List<String>,
    vs: VehicleState,
    p: UserPrefs,
    snackbarHostState: SnackbarHostState,
    firmwareApi: FirmwareCommandSender? = null,
    onScanDtcs: (suspend (DtcProgressCallback?) -> DtcScanResult)? = null,
    onClearDtcs: (suspend () -> Map<String, Boolean>)? = null,
    onRetryScanModule: (suspend (String) -> Pair<List<DtcResult>, ModuleScanStatus>)? = null,
    onFetchFreezeFrames: (suspend (List<DtcResult>) -> List<DtcResult>)? = null,
    onSendRawQuery: (suspend (Int, String, Long) -> ByteArray?)? = null,
    onResetSession: () -> Unit = {},
    onOpenDock: () -> Unit = {},
) {
    var panel by remember { mutableStateOf(GaragePanel.CONTROL) }

    Column(Modifier.fillMaxSize()) {
        GaragePanelPicker(panel) { panel = it }
        when (panel) {
            GaragePanel.CONTROL -> MorePage(
                vs = vs,
                p = p,
                snackbarHostState = snackbarHostState,
                firmwareApi = firmwareApi,
                onOpenDock = onOpenDock,
            )
            GaragePanel.DIAGNOSTICS -> DiagPage(
                lines = debugLines,
                vs = vs,
                onScanDtcs = onScanDtcs,
                onClearDtcs = onClearDtcs,
                onRetryScanModule = onRetryScanModule,
                onFetchFreezeFrames = onFetchFreezeFrames,
                onSendRawQuery = onSendRawQuery,
                onResetSession = onResetSession,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

@Composable
private fun GaragePanelPicker(
    selected: GaragePanel,
    onSelect: (GaragePanel) -> Unit,
) {
    val accent = LocalThemeAccent.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = PagePad, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GaragePanel.entries.forEach { entry ->
            val active = entry == selected
            Box(
                Modifier.weight(1f)
                    .background(
                        if (active) accent.copy(alpha = pillBgAlpha(0.15f)) else Surf2,
                        RoundedCornerShape(8.dp),
                    )
                    .border(
                        CardBorder,
                        if (active) accent.copy(alpha = borderAlpha(0.5f)) else Brd,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSelect(entry)
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                MonoLabel(
                    entry.label,
                    11.sp,
                    if (active) accent else Dim,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 0.15.sp,
                )
            }
        }
    }
}
