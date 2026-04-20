package com.openrs.dash.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.diagnostics.ExportComponent
import com.openrs.dash.ui.*

// ═══════════════════════════════════════════════════════════════════════════
// EXPORT OPTIONS SHEET — selective export toggles before sharing (#2C)
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsSheet(
    drive: DriveEntity,
    onExport: (Set<ExportComponent>) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = LocalThemeAccent.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Default: core formats on, interop formats off
    val toggles = remember {
        mutableStateMapOf<ExportComponent, Boolean>().apply {
            ExportComponent.entries.forEach { comp ->
                put(comp, comp != ExportComponent.RACECHRONO_CSV &&
                          comp != ExportComponent.TRACKADDICT_CSV)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surf,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(
                Modifier.padding(vertical = 10.dp)
                    .height(4.dp)
                    .fillMaxWidth(0.12f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brd)
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            MonoText("EXPORT OPTIONS", 13.sp, Frost, FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            NeonDivider()
            Spacer(Modifier.height(8.dp))

            // Drive info
            val durationMs = if (drive.endTime > 0) drive.endTime - drive.startTime else 0L
            MonoLabel(
                "${"%.1f".format(drive.distanceKm)} km · ${formatDurationForExport(durationMs)}",
                9.sp, Dim
            )
            Spacer(Modifier.height(8.dp))

            // Toggle rows
            ExportComponent.entries.forEach { comp ->
                val enabled = toggles[comp] == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(Tokens.CardShape)
                        .background(if (enabled) Surf2 else Surf)
                        .border(Tokens.CardBorder, Brd, Tokens.CardShape)
                        .clickable { toggles[comp] = !enabled }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        MonoText(comp.label, 11.sp, if (enabled) Frost else Dim, FontWeight.Bold)
                        MonoLabel(componentHint(comp), 8.sp, Dim)
                    }
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (enabled) accent else Surf2)
                            .border(Tokens.CardBorder, if (enabled) accent else Brd, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (enabled) {
                            MonoText("\u2713", 11.sp, Bg, FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            NeonDivider()
            Spacer(Modifier.height(12.dp))

            // Action buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent)
                        .clickable {
                            val selected = toggles.filter { it.value }.keys
                            if (selected.isNotEmpty()) onExport(selected)
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val count = toggles.count { it.value }
                    MonoText("EXPORT ($count)", 12.sp, Bg, FontWeight.Bold)
                }
                Box(
                    Modifier.weight(0.5f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surf2)
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoText("CANCEL", 12.sp, Mid, FontWeight.Bold)
                }
            }
        }
    }
}

private fun componentHint(comp: ExportComponent): String = when (comp) {
    ExportComponent.DRIVE_CSV      -> "32-column telemetry at 1 Hz"
    ExportComponent.DRIVE_GPX      -> "GPS track for mapping tools"
    ExportComponent.SUMMARY        -> "Human-readable drive stats"
    ExportComponent.PROFILE_JSON   -> "Sapphire web dashboard import"
    ExportComponent.DIAGNOSTICS    -> "Session frames + decode log"
    ExportComponent.SLCAN_LOG      -> "Raw CAN frames (can be large)"
    ExportComponent.DTC_REPORT     -> "Diagnostic trouble codes"
    ExportComponent.RACECHRONO_CSV -> "RaceChrono-compatible format"
    ExportComponent.TRACKADDICT_CSV -> "TrackAddict-compatible format"
}

private fun formatDurationForExport(ms: Long): String {
    if (ms <= 0) return "0:00"
    val secs = ms / 1000
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
