package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.DtcCodeEntity
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcSeverity
import com.openrs.dash.data.DtcStatus
import com.openrs.dash.ui.Tokens.CardBorder

// ═══════════════════════════════════════════════════════════════════════════
// DTC DETAIL SHEET — expanded DTC info bottom sheet
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcDetailSheet(
    dtc: DtcResult,
    history: List<DtcCodeEntity>? = null,
    knownIssueNote: String? = null,
    onDismiss: () -> Unit
) {
    val accent = LocalThemeAccent.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val statusColor = when (dtc.status) {
        DtcStatus.ACTIVE    -> Orange
        DtcStatus.PENDING   -> Warn
        DtcStatus.PERMANENT -> Orange
        DtcStatus.UNKNOWN   -> Dim
    }

    val severity = DtcSeverity.fromCode(dtc.code)
    val severityColor = when (severity) {
        DtcSeverity.HIGH   -> Orange
        DtcSeverity.MEDIUM -> Warn
        DtcSeverity.LOW    -> Dim
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
            // ── Code + Status badge ──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoText(dtc.code, 22.sp, statusColor, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Status badge
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(statusColor.copy(0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        MonoLabel(dtc.status.label.uppercase(), 9.sp, statusColor)
                    }
                    // Severity badge
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(severityColor.copy(0.10f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        MonoLabel(severity.label.uppercase(), 9.sp, severityColor)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            MonoLabel("Module: ${dtc.module}", 10.sp, Dim)

            // ── Description ──
            if (dtc.description.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                MonoText(dtc.description, 12.sp, Frost)
            }

            // ── Status explanation ──
            Spacer(Modifier.height(12.dp))
            val statusExplanation = when (dtc.status) {
                DtcStatus.ACTIVE    -> "The fault condition is currently present. The ECU is actively detecting this failure."
                DtcStatus.PENDING   -> "Detected once but not yet confirmed. May clear on the next driving cycle if the condition does not recur."
                DtcStatus.PERMANENT -> "Stored fault confirmed across multiple driving cycles. Will not clear until the fault condition is resolved."
                DtcStatus.UNKNOWN   -> "The ECU returned this code with no recognized status bits set."
            }
            Box(
                Modifier.fillMaxWidth()
                    .background(statusColor.copy(0.06f), RoundedCornerShape(8.dp))
                    .border(CardBorder, statusColor.copy(0.2f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                MonoText(statusExplanation, 9.sp, Mid)
            }

            // ── Known RS Issue ──
            if (knownIssueNote != null) {
                Spacer(Modifier.height(12.dp))
                MonoLabel("FOCUS RS NOTES", 9.sp, accent, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(accent.copy(0.06f), RoundedCornerShape(8.dp))
                        .border(CardBorder, accent.copy(0.2f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    MonoText(knownIssueNote, 9.sp, Mid)
                }
            }

            // ── Freeze Frame Data ──
            val ff = dtc.freezeFrame
            if (ff != null && ff.entries.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                MonoLabel("FREEZE FRAME — Snapshot #${ff.recordNumber}", 9.sp, accent, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(Surf2, RoundedCornerShape(8.dp))
                        .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (entry in ff.entries) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MonoLabel(entry.label, 9.sp, Dim, modifier = Modifier.weight(1f))
                            MonoLabel(entry.value, 9.sp, Frost)
                        }
                    }
                }
            }

            // ── Occurrence History ──
            if (history != null && history.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                MonoLabel("HISTORY — ${history.size} occurrence(s)", 9.sp, accent, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(Surf2, RoundedCornerShape(8.dp))
                        .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val dateFmt = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                    // Show up to 10 most recent occurrences
                    for (entry in history.take(10)) {
                        val statusLabel = try { DtcStatus.valueOf(entry.status).label } catch (_: Exception) { entry.status }
                        val sc = when (entry.status) {
                            "ACTIVE" -> Orange; "PENDING" -> Warn; "PERMANENT" -> Orange; else -> Dim
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MonoLabel(statusLabel, 9.sp, sc)
                            MonoLabel(entry.module, 8.sp, Dim)
                        }
                    }
                    if (history.size > 10) {
                        MonoLabel("+ ${history.size - 10} more", 8.sp, Dim)
                    }
                }
            }
        }
    }
}
