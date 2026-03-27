package com.openrs.dash.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcStatus
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
@Composable fun DiagPage(
    lines: List<String>,
    vs: VehicleState,
    onScanDtcs: (suspend () -> List<DtcResult>)?,
    onClearDtcs: (suspend () -> Map<String, Boolean>)? = null,
    onSendRawQuery: (suspend (responseId: Int, frame: String, timeoutMs: Long) -> ByteArray?)? = null
) {
    val ctx    = LocalContext.current
    val scope  = rememberCoroutineScope()
    val accent = LocalThemeAccent.current
    var exporting by remember { mutableStateOf(false) }

    // DTC scan state
    var dtcScanning  by remember { mutableStateOf(false) }
    var dtcClearing  by remember { mutableStateOf(false) }
    var dtcResults   by remember { mutableStateOf<List<DtcResult>?>(null) }
    var dtcError     by remember { mutableStateOf<String?>(null) }
    var dtcClearStatus by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // P-4: snapshot once so the size/values are consistent within one composition
    val inv = remember(vs.framesPerSecond) { DiagnosticLogger.frameInventorySnapshot }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {

        // ── DTC Scanner ───────────────────────────────────────────────────────
        SectionLabel("DTC SCANNER")
        Spacer(Modifier.height(4.dp))

        val dtcBusy = dtcScanning || dtcClearing
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Scan button
            Box(
                Modifier.weight(1f)
                    .background(
                        if (!dtcBusy && vs.isConnected && onScanDtcs != null)
                            Brush.horizontalGradient(listOf(accent.copy(0.12f), accent.copy(0.06f)))
                        else Brush.horizontalGradient(listOf(Dim.copy(0.1f), Dim.copy(0.05f))),
                        RoundedCornerShape(10.dp)
                    )
                    .border(
                        1.dp,
                        if (!dtcBusy && vs.isConnected && onScanDtcs != null) accent.copy(0.35f) else Dim.copy(0.2f),
                        RoundedCornerShape(10.dp)
                    )
                    .clickable(enabled = !dtcBusy && vs.isConnected && onScanDtcs != null) {
                        dtcScanning = true
                        dtcError = null
                        dtcClearStatus = null
                        scope.launch(Dispatchers.IO) {
                            val result = try {
                                onScanDtcs?.invoke()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) { null }
                            withContext(Dispatchers.Main) {
                                dtcScanning = false
                                if (result != null) {
                                    dtcResults = result
                                } else {
                                    dtcError = "Scan failed — check adapter connection"
                                }
                            }
                        }
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel(
                    when {
                        dtcScanning -> "SCANNING..."
                        !vs.isConnected -> "CONNECT TO SCAN"
                        else -> "⟳  SCAN ALL MODULES"
                    },
                    11.sp,
                    if (!dtcBusy && vs.isConnected && onScanDtcs != null) accent else Dim,
                    letterSpacing = 0.08.sp
                )
            }

            // Dismiss button — clears results from the display
            if (dtcResults != null) {
                Box(
                    Modifier.width(72.dp)
                        .background(Color(0xFF1A0A0A), RoundedCornerShape(10.dp))
                        .border(1.dp, Dim.copy(0.3f), RoundedCornerShape(10.dp))
                        .clickable(enabled = !dtcBusy) { dtcResults = null; dtcError = null; dtcClearStatus = null }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("DISMISS", 9.sp, Dim)
                }
            }
        }

        // Clear Fault Codes button — shown when there are stored faults and adapter is connected
        val hasFaults = dtcResults?.isNotEmpty() == true
        if (hasFaults || dtcClearing) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        if (!dtcBusy && vs.isConnected && onClearDtcs != null)
                            Color(0xFF1A0606)
                        else Color(0xFF120404),
                        RoundedCornerShape(10.dp)
                    )
                    .border(
                        1.dp,
                        if (!dtcBusy && vs.isConnected && onClearDtcs != null) Orange.copy(0.45f) else Orange.copy(0.15f),
                        RoundedCornerShape(10.dp)
                    )
                    .clickable(enabled = !dtcBusy && vs.isConnected && onClearDtcs != null) {
                        showClearConfirm = true
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel(
                    if (dtcClearing) "CLEARING..." else "⚠  CLEAR FAULT CODES (0x14)",
                    11.sp,
                    if (!dtcBusy && vs.isConnected && onClearDtcs != null) Orange.copy(0.9f) else Orange.copy(0.35f),
                    letterSpacing = 0.06.sp
                )
            }
        }

        // ── Clear DTC confirmation dialog ────────────────────────────────────
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                containerColor = SurfUp,
                titleContentColor = Frost,
                textContentColor = Dim,
                title = { Text("Clear All Fault Codes?", fontFamily = ShareTechMono, fontSize = 14.sp) },
                text = {
                    Text(
                        "This sends UDS Service 0x14 to all ECU modules (PCM, BCM, ABS, AWD, PSCM) " +
                        "to clear stored, pending, and permanent DTCs.\n\n" +
                        "Cleared codes may return if the underlying condition persists.",
                        fontFamily = ShareTechMono, fontSize = 11.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirm = false
                        dtcClearing = true
                        dtcError = null
                        dtcClearStatus = null
                        scope.launch(Dispatchers.IO) {
                            val ack = try {
                                onClearDtcs?.invoke()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) { null }
                            withContext(Dispatchers.Main) {
                                dtcClearing = false
                                when {
                                    ack == null || ack.isEmpty() -> {
                                        dtcError = "Clear failed — no response from ECUs"
                                    }
                                    else -> {
                                        val ok  = ack.count { it.value }
                                        val all = ack.size
                                        dtcClearStatus = if (ok == all)
                                            "Cleared: ${ack.keys.joinToString(", ")}  ($ok/$all)"
                                        else
                                            "Partial: ${ack.entries.joinToString(", ") { "${it.key}:${if (it.value) "✓" else "✗"}" }}"
                                        dtcResults = null
                                    }
                                }
                            }
                        }
                    }) {
                        Text("CLEAR", fontFamily = ShareTechMono, color = Orange, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text("CANCEL", fontFamily = ShareTechMono, color = Dim, fontSize = 12.sp)
                    }
                }
            )
        }

        Spacer(Modifier.height(4.dp))
        if (dtcClearStatus == null) {
            MonoLabel(
                when {
                    dtcScanning -> "Querying PCM, BCM, ABS, AWD, PSCM..."
                    dtcClearing -> "Sending UDS 0x14 to all modules — do not disconnect..."
                    else        -> "Reads active, pending, and permanent fault codes from all modules."
                },
                9.sp, Dim, modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // Clear status confirmation
        if (dtcClearStatus != null) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF060E06), RoundedCornerShape(8.dp))
                    .border(1.dp, Ok.copy(0.3f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                MonoLabel("✓  ${dtcClearStatus}", 10.sp, Ok)
            }
            Spacer(Modifier.height(6.dp))
        }

        // Error
        if (dtcError != null) {
            MonoLabel("⚠ ${dtcError}", 10.sp, Warn, modifier = Modifier.padding(bottom = 6.dp))
        }

        // Results
        val results = dtcResults
        // Fix B: hold the last non-null snapshot so the exit animation doesn't go blank
        // when dtcResults is set to null by DISMISS before AnimatedVisibility finishes.
        var lastResults by remember { mutableStateOf<List<DtcResult>?>(null) }
        if (results != null) lastResults = results
        AnimatedVisibility(visible = results != null) {
            lastResults?.let { r ->
                Column(Modifier.fillMaxWidth()) {
                    if (r.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth()
                                .background(Color(0xFF060E0A), RoundedCornerShape(10.dp))
                                .border(1.dp, Ok.copy(0.25f), RoundedCornerShape(10.dp))
                                .padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MonoLabel("✓  NO FAULT CODES — all modules clean", 11.sp, Ok)
                        }
                    } else {
                        // Group by module
                        val grouped = r.groupBy { it.module }
                        val moduleOrder = listOf("PCM", "BCM", "ABS", "AWD", "PSCM")
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Surf2, RoundedCornerShape(10.dp))
                                .border(1.dp, Brd, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (moduleName in moduleOrder) {
                                val moduleDtcs = grouped[moduleName] ?: continue
                                MonoLabel(moduleName, 9.sp, accent, letterSpacing = 1.sp)
                                moduleDtcs.forEach { dtc ->
                                    DtcRow(dtc)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    MonoLabel(
                        "${r.size} fault code(s) found across ${r.map { it.module }.distinct().size} module(s).",
                        9.sp, Dim, modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = Brd)
        Spacer(Modifier.height(10.dp))

        // ── Session stats + export ────────────────────────────────────────────
        SectionLabel("DIAGNOSTICS")
        Spacer(Modifier.height(4.dp))

        val sessionMs  = remember(vs.framesPerSecond) { DiagnosticLogger.sessionDurationMs }
        val frameCount = inv.values.sumOf { it.totalReceived }
        val issueCount = inv.values.sumOf { it.validationIssues.size }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataCell("FPS",    "${vs.framesPerSecond.roundToInt()}", modifier = Modifier.weight(1f))
            DataCell("STATUS", if (vs.isConnected) "LIVE" else "OFF",
                valueColor = if (vs.isConnected) Ok else Orange, modifier = Modifier.weight(1f))
            DataCell("MODE",   vs.dataMode, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataCell("SESSION", DiagnosticLogger.formatDuration(sessionMs), modifier = Modifier.weight(1f))
            DataCell("FRAMES",  "$frameCount",                              modifier = Modifier.weight(1f))
            DataCell("IDs",     "${inv.size}",                              modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataCell("ENGINE", engineStatusLabel(vs.engineStatus), modifier = Modifier.weight(1f))
            DataCell("IGNITION", ignitionStatusLabel(vs.ignitionStatus), modifier = Modifier.weight(1f))
            DataCell("E-BRAKE", if (vs.eBrake) "ON" else "OFF",
                valueColor = if (vs.eBrake) Warn else Dim, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataCell("BATT V", "${"%.1f".format(vs.batteryVoltage)}V", modifier = Modifier.weight(1f))
            DataCell("BATT SoC", if (vs.batterySoc >= 0) "${vs.batterySoc.roundToInt()}%" else "—",
                valueColor = when {
                    vs.batterySoc < 0   -> Dim
                    vs.batterySoc < 50  -> Orange
                    vs.batterySoc < 70  -> Warn
                    else                -> Ok
                }, modifier = Modifier.weight(1f))
            DataCell("BATT TEMP",
                if (vs.batteryTempC > -90) "${vs.batteryTempC.roundToInt()}°C" else "—",
                modifier = Modifier.weight(1f))
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
        CrashHistorySection()
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Brd)
        Spacer(Modifier.height(10.dp))
        DidProberSection(vs, onSendRawQuery)
        Spacer(Modifier.height(14.dp))
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

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Brd)
        Spacer(Modifier.height(10.dp))
        PidBrowserSection()
    }
}

internal fun engineStatusLabel(v: Int): String = when (v) {
    -1  -> "—"
    0   -> "Idle"
    2   -> "Off"
    183 -> "Running"
    186 -> "Kill"
    191 -> "Start"
    196 -> "Warmup"
    else -> "0x%02X".format(v)
}

internal fun ignitionStatusLabel(v: Int): String = when (v) {
    -1  -> "—"
    0   -> "Key Out"
    1   -> "Key In"
    4   -> "Acc"
    7   -> "Run"
    9   -> "Crank"
    else -> "$v"
}

// ── DTC result row composable ─────────────────────────────────────────────

@Composable
private fun DtcRow(dtc: DtcResult) {
    val statusColor = when (dtc.status) {
        DtcStatus.ACTIVE    -> Orange
        DtcStatus.PENDING   -> Warn
        DtcStatus.PERMANENT -> Color(0xFFFF8C00)
        DtcStatus.UNKNOWN   -> Dim
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MonoLabel(dtc.code, 10.sp, statusColor, modifier = Modifier.width(54.dp))
        Column(Modifier.weight(1f)) {
            if (dtc.description.isNotEmpty()) {
                MonoText(dtc.description, 9.sp, Mid)
            }
            MonoLabel(dtc.status.label, 8.sp, statusColor.copy(0.7f))
        }
    }
}

// ── Crash History Section ──────────────────────────────────────────────────

@Composable
private fun CrashHistorySection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateFmt = remember { java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault()) }

    var crashFiles by remember { mutableStateOf(DiagnosticExporter.crashFiles(ctx)) }

    SectionLabel("CRASH HISTORY")
    Spacer(Modifier.height(4.dp))

    if (crashFiles.isEmpty()) {
        Box(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(10.dp))
                .border(1.dp, Brd, RoundedCornerShape(10.dp))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            MonoLabel("No crash reports", 10.sp, Dim)
        }
    } else {
        Column(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(10.dp))
                .border(1.dp, Brd, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MonoLabel("${crashFiles.size} crash report(s)", 10.sp, Orange, letterSpacing = 0.1.sp)
            Spacer(Modifier.height(2.dp))
            crashFiles.take(20).forEach { file ->
                val ts = dateFmt.format(java.util.Date(file.lastModified()))
                Row(
                    Modifier.fillMaxWidth()
                        .background(Surf, RoundedCornerShape(6.dp))
                        .border(1.dp, Brd, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoLabel(ts, 9.sp, Frost)
                    MonoLabel(file.name.removePrefix("crash_telemetry_").removeSuffix(".json"), 8.sp, Dim)
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(Orange.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .border(1.dp, Orange.copy(0.3f), RoundedCornerShape(8.dp))
                    .clickable {
                        scope.launch(Dispatchers.IO) {
                            DiagnosticExporter.clearCrashHistory(ctx)
                            withContext(Dispatchers.Main) {
                                crashFiles = emptyList()
                            }
                        }
                    }
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("CLEAR CRASH HISTORY", 10.sp, Orange, letterSpacing = 0.1.sp)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    MonoLabel("Crash reports are auto-included in diagnostic ZIP exports.", 9.sp, Dim)
}
