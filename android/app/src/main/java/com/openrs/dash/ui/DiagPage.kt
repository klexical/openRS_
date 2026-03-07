package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// DIAG PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun DiagPage(lines: List<String>, vs: VehicleState) {
    val ctx    = LocalContext.current
    val scope  = rememberCoroutineScope()
    val accent = LocalThemeAccent.current
    var exporting by remember { mutableStateOf(false) }

    // P-4: snapshot once so the size/values are consistent within one composition
    val inv = remember(vs.framesPerSecond) { DiagnosticLogger.frameInventorySnapshot }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionLabel("DIAGNOSTICS")
        Spacer(Modifier.height(4.dp))

        val sessionMs  = remember(vs.framesPerSecond) { DiagnosticLogger.sessionDurationMs }
        val frameCount = inv.values.sumOf { it.totalReceived }
        val issueCount = inv.values.sumOf { it.validationIssues.size }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataCell("FPS",    "${vs.framesPerSecond.roundToInt()}", modifier = Modifier.weight(1f))
            DataCell("STATUS", if (vs.isConnected) "LIVE" else "OFF",
                valueColor = if (vs.isConnected) Ok else Red, modifier = Modifier.weight(1f))
            DataCell("MODE",   vs.dataMode, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataCell("SESSION", DiagnosticLogger.formatDuration(sessionMs), modifier = Modifier.weight(1f))
            DataCell("FRAMES",  "$frameCount",                              modifier = Modifier.weight(1f))
            DataCell("IDs",     "${inv.size}",                              modifier = Modifier.weight(1f))
        }

        if (issueCount > 0) {
            Spacer(Modifier.height(6.dp))
            MonoLabel("⚠ $issueCount validation issue(s) — capture snapshot to review", 9.sp, Warn)
        }

        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(
                    if (!exporting) Brush.horizontalGradient(listOf(accent.copy(0.1f), accent.copy(0.05f)))
                    else Brush.horizontalGradient(listOf(Dim.copy(0.1f), Dim.copy(0.05f))),
                    RoundedCornerShape(10.dp)
                )
                .border(1.dp, if (!exporting) accent.copy(0.3f) else Dim.copy(0.3f), RoundedCornerShape(10.dp))
                .clickable(enabled = !exporting) {
                    exporting = true
                    scope.launch(Dispatchers.IO) {
                        DiagnosticExporter.share(ctx)
                        withContext(Dispatchers.Main) { exporting = false }
                    }
                }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            MonoLabel(
                if (exporting) "BUILDING..." else "↑  CAPTURE & SHARE SNAPSHOT",
                12.sp, if (!exporting) accent else Dim, letterSpacing = 0.1.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        MonoLabel("Exports ZIP (summary + raw log + JSON) via share sheet.", 9.sp, Dim,
            modifier = Modifier.padding(bottom = 12.dp))

        HorizontalDivider(color = Brd)
        Spacer(Modifier.height(10.dp))
        SectionLabel("LIVE CAN OUTPUT")
        Spacer(Modifier.height(4.dp))

        Column(
            Modifier.fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFF060810), RoundedCornerShape(10.dp))
                .border(1.dp, Brd, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            val displayLines = lines.takeLast(20)
            if (displayLines.isEmpty() && vs.isConnected) {
                MonoLabel("Connected — waiting for first CAN frame...", 10.sp, Warn)
            } else if (displayLines.isEmpty()) {
                MonoLabel("Connect to WiCAN to see raw output.", 10.sp, Dim)
            } else {
                displayLines.forEach { line ->
                    val parts = line.trim().split(" ", limit = 2)
                    Row(Modifier.padding(vertical = 1.dp)) {
                        if (parts.size >= 2) {
                            MonoLabel(parts[0], 10.sp, Warn, letterSpacing = 0.05.sp)
                            Spacer(Modifier.width(12.dp))
                            MonoText(parts[1], 10.sp, Mid)
                        } else {
                            MonoText(line, 10.sp, Mid)
                        }
                    }
                }
            }
        }

        if (inv.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("FRAME INVENTORY (${inv.size} IDs)")
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .border(1.dp, Brd, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                inv.entries.sortedBy { it.key }.forEach { (id, info) ->
                    val decoded  = if (info.lastDecoded.isEmpty()) "(no decoder)" else info.lastDecoded
                    val issColor = if (info.validationIssues.isNotEmpty()) Warn else Mid
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MonoText("0x%03X".format(id), 9.sp, accent)
                        MonoText("×${info.totalReceived}", 9.sp, Dim)
                        MonoText(decoded.take(32), 9.sp, issColor, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                    info.validationIssues.forEach { issue ->
                        MonoLabel("  ⚠ $issue", 8.sp, Warn)
                    }
                }
            }
        }
    }
}
