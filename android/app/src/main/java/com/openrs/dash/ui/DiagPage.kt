package com.openrs.dash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.anim.pageEntrance
import com.openrs.dash.ui.anim.pressClick
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
    onSendRawQuery: (suspend (responseId: Int, frame: String, timeoutMs: Long) -> ByteArray?)? = null,
    onResetSession: () -> Unit = {},
    snackbarHostState: androidx.compose.material3.SnackbarHostState? = null,
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
    var dtcScanJob   by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var dtcScanStartMs by remember { mutableStateOf(0L) }
    var dtcScanElapsed by remember { mutableStateOf(0) }
    LaunchedEffect(dtcScanning) {
        if (dtcScanning) {
            dtcScanStartMs = System.currentTimeMillis()
            dtcScanElapsed = 0
            while (dtcScanning) {
                dtcScanElapsed = ((System.currentTimeMillis() - dtcScanStartMs) / 1000L).toInt()
                kotlinx.coroutines.delay(250)
            }
        } else {
            dtcScanElapsed = 0
        }
    }

    // P-4: snapshot once so the size/values are consistent within one composition
    val inv = remember(vs.framesPerSecond) { DiagnosticLogger.frameInventorySnapshot }

    // Collapsible section states (persisted in SharedPreferences)
    var diagExpanded        by rememberSectionExpanded("DIAG_DIAGNOSTICS")
    var dtcExpanded         by rememberSectionExpanded("DIAG_DTC_SCANNER")
    var developerExpanded   by rememberSectionExpanded("DIAG_DEVELOPER", default = false)
    var crashExpanded       by rememberSectionExpanded("DIAG_CRASH_HISTORY", default = false)
    var didProberExpanded   by rememberSectionExpanded("DIAG_DID_PROBER", default = false)
    var canOutputExpanded   by rememberSectionExpanded("DIAG_CAN_OUTPUT", default = false)
    var frameInvExpanded    by rememberSectionExpanded("DIAG_FRAME_INV", default = false)
    var pidBrowserExpanded  by rememberSectionExpanded("DIAG_PID_BROWSER", default = false)
    var showAllFrames       by remember { mutableStateOf(false) }

    var pageEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pageEntered = true }

    var scanComplete by remember { mutableStateOf(false) }
    LaunchedEffect(dtcResults) { if (!dtcResults.isNullOrEmpty()) scanComplete = true }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = Tokens.PagePad, end = Tokens.PagePad, top = Tokens.PagePad, bottom = Tokens.PagePad + Tokens.NavBarHeight)) {

        // ── VIN (passive CAN 0x40A) ──────────────────────────────────
        if (vs.vin.isNotEmpty()) {
            Row(
                pageEntrance(0, pageEntered).fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(8.dp))
                    .border(Tokens.CardBorder, Brd, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoLabel("VIN", 9.sp, Dim, letterSpacing = 0.1.sp)
                Spacer(Modifier.weight(1f))
                MonoText(vs.vin, 12.sp, Frost)
            }
            Spacer(Modifier.height(Tokens.SectionGap))
        }

        val frameCount = inv.values.sumOf { it.totalReceived }
        val dtcCount = dtcResults?.size ?: 0
        val dtcBusy = dtcScanning || dtcClearing
        val sessionMs  = remember(vs.framesPerSecond) { DiagnosticLogger.sessionDurationMs }
        val issueCount = inv.values.sumOf { it.validationIssues.size }

        // ── Diagnostics (collapsible, expanded by default) ────────────────
        SectionLabel("DIAGNOSTICS", collapsible = true, expanded = diagExpanded,
            onToggle = { diagExpanded = !diagExpanded },
            modifier = pageEntrance(1, pageEntered))
        AnimatedVisibility(
            visible = diagExpanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column {
                // rc.2 hierarchy: split the former 4×4 equal-weight grid into
                // three labeled sub-groups. The reader can now skim:
                //   HEALTH       — is telemetry alive and how much?
                //   VEHICLE      — what's the car doing right now?
                //   BATTERY      — the long-tail owner concern
                Spacer(Modifier.height(4.dp))
                MonoLabel("HEALTH", 8.sp, Dim.copy(alpha = textMutedAlpha(0.7f)), letterSpacing = 0.3.sp)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DataCell("STATUS", if (vs.isConnected) "LIVE" else "— —",
                        valueColor = if (vs.isConnected) Ok else Dim, modifier = Modifier.weight(1f))
                    DataCell("FPS",    "${vs.framesPerSecond.roundToInt()}", modifier = Modifier.weight(1f))
                    DataCell("DTCs", if (dtcResults != null) "$dtcCount" else "—",
                        valueColor = if (dtcCount > 0) Orange else if (dtcResults != null) Ok else Dim,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DataCell("SESSION", DiagnosticLogger.formatDuration(sessionMs), modifier = Modifier.weight(1f))
                    DataCell("FRAMES",  "$frameCount",                              modifier = Modifier.weight(1f))
                    DataCell("IDs",     "${inv.size}",                              modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                if (!vs.isConnected) {
                    // rc.2: when disconnected, the VEHICLE + BATTERY + buttons
                    // rows are all em-dashes (>50% of the section). Replace with
                    // a single CTA so the screen reads as deliberate, not broken.
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Surf2, RoundedCornerShape(10.dp))
                            .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                            .padding(vertical = 20.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MonoLabel("VEHICLE \u00B7 BATTERY \u00B7 BUTTONS", 8.sp,
                                Dim.copy(alpha = textMutedAlpha(0.6f)), letterSpacing = 0.3.sp)
                            Spacer(Modifier.height(6.dp))
                            MonoLabel("Connect to view live data", 11.sp, Dim, letterSpacing = 0.1.sp)
                        }
                    }
                } else {
                MonoLabel("VEHICLE", 8.sp, Dim.copy(alpha = textMutedAlpha(0.7f)), letterSpacing = 0.3.sp)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DataCell("ENGINE", engineStatusLabel(vs.engineStatus), modifier = Modifier.weight(1f))
                    DataCell("IGNITION", ignitionStatusLabel(vs.ignitionStatus), modifier = Modifier.weight(1f))
                    DataCell("E-BRAKE", if (vs.eBrake) "ON" else "OFF",
                        valueColor = if (vs.eBrake) Warn else Dim, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                MonoLabel("BATTERY", 8.sp, Dim.copy(alpha = textMutedAlpha(0.7f)), letterSpacing = 0.3.sp)
                Spacer(Modifier.height(4.dp))
                // Availability-aware placeholders so startup/stale states read cleanly
                // rather than leaking the raw -1 sentinel as "-1.00V".
                fun availSub(a: TempAvail) = when (a) {
                    TempAvail.WARMING     -> "WARMING"
                    TempAvail.STALE       -> "STALE"
                    TempAvail.UNAVAILABLE -> "N/A"
                    TempAvail.AVAILABLE   -> ""
                }
                val battVAvail    = scalarAvailFor(vs.batteryVoltage >= 0, vs.fieldLastUpdateMs["batteryVoltage"])
                val battAAvail    = scalarAvailFor(vs.batteryCurrentA > -900, vs.fieldLastUpdateMs["batteryCurrentA"])
                val battSocAvail  = scalarAvailFor(vs.batterySoc >= 0, vs.fieldLastUpdateMs["batterySoc"])
                val battTempAvail = scalarAvailFor(vs.batteryTempC > -90, vs.fieldLastUpdateMs["batteryTempC"])
                val chgTgtAvail   = scalarAvailFor(vs.batteryChargingVoltageDesired >= 0, vs.fieldLastUpdateMs["batteryChargingVoltageDesired"])
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DataCell("BATT V",
                        if (battVAvail == TempAvail.AVAILABLE || battVAvail == TempAvail.STALE)
                            "${"%.2f".format(vs.batteryVoltage)}V" else "—",
                        sub = availSub(battVAvail),
                        valueColor = if (battVAvail == TempAvail.AVAILABLE) Frost else Dim,
                        modifier = Modifier.weight(1f))
                    DataCell("BATT A",
                        if (battAAvail == TempAvail.AVAILABLE || battAAvail == TempAvail.STALE)
                            "${"%+.1f".format(vs.batteryCurrentA)} A" else "—",
                        sub = availSub(battAAvail),
                        valueColor = when {
                            battAAvail != TempAvail.AVAILABLE -> Dim
                            vs.batteryCurrentA >  0.5  -> Ok       // charging
                            vs.batteryCurrentA < -0.5  -> Frost    // discharging
                            else                       -> Mid      // resting
                        }, modifier = Modifier.weight(1f))
                    DataCell("BATT SoC",
                        if (battSocAvail == TempAvail.AVAILABLE || battSocAvail == TempAvail.STALE)
                            "${vs.batterySoc.roundToInt()}%" else "—",
                        sub = availSub(battSocAvail),
                        valueColor = when {
                            battSocAvail != TempAvail.AVAILABLE -> Dim
                            vs.batterySoc < 50  -> Orange
                            vs.batterySoc < 70  -> Warn
                            else                -> Ok
                        }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DataCell("BATT TEMP",
                        if (battTempAvail == TempAvail.AVAILABLE || battTempAvail == TempAvail.STALE)
                            "${vs.batteryTempC.roundToInt()}°C" else "—",
                        sub = availSub(battTempAvail),
                        valueColor = if (battTempAvail == TempAvail.AVAILABLE) Frost else Dim,
                        modifier = Modifier.weight(1f))
                    DataCell("CHG TGT",
                        if (chgTgtAvail == TempAvail.AVAILABLE || chgTgtAvail == TempAvail.STALE)
                            "${"%.1f".format(vs.batteryChargingVoltageDesired)}V" else "—",
                        sub = availSub(chgTgtAvail),
                        valueColor = if (chgTgtAvail == TempAvail.AVAILABLE) Frost else Dim,
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }

                // ── Live button inputs (0x070, 0x260, 0x305) ─────────────────
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DataCell("MODE BTN", if (vs.driveModeButtonPressed) "HELD" else "—",
                        valueColor = if (vs.driveModeButtonPressed) accent else Dim,
                        modifier = Modifier.weight(1f))
                    DataCell("SUSP BTN", if (vs.suspensionButtonPressed) "HELD" else "—",
                        valueColor = if (vs.suspensionButtonPressed) accent else Dim,
                        modifier = Modifier.weight(1f))
                    DataCell("ASS BTN", if (vs.autoStartStopButtonPressed) "HELD" else "—",
                        valueColor = if (vs.autoStartStopButtonPressed) accent else Dim,
                        modifier = Modifier.weight(1f))
                    DataCell("ESC BTN", if (vs.escOffButtonPressed) "HELD" else "—",
                        valueColor = if (vs.escOffButtonPressed) Orange else Dim,
                        modifier = Modifier.weight(1f))
                }
                }  // end else (connected)

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
                        .border(CardBorder, if (!exporting) accent.copy(0.3f) else Dim.copy(0.3f), RoundedCornerShape(10.dp))
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

                // ── Reset Session ────────────────────────────────────────────────
                if (vs.isConnected) {
                    var confirmReset by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoLabel("SESSION", 9.sp, Dim, letterSpacing = 0.2.sp)
                        Spacer(Modifier.weight(1f))
                        if (confirmReset) {
                            MonoLabel("Reset all data?", 10.sp, Orange)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .background(Orange.copy(0.15f), RoundedCornerShape(6.dp))
                                    .border(CardBorder, Orange.copy(0.4f), RoundedCornerShape(6.dp))
                                    .clickable {
                                        onResetSession()
                                        confirmReset = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                MonoLabel("CONFIRM", 10.sp, Orange, FontWeight.Bold, 0.1.sp)
                            }
                            Box(
                                Modifier
                                    .background(Surf2, RoundedCornerShape(6.dp))
                                    .border(CardBorder, Brd, RoundedCornerShape(6.dp))
                                    .clickable { confirmReset = false }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                MonoLabel("CANCEL", 10.sp, Dim, FontWeight.Bold, 0.1.sp)
                            }
                        } else {
                            Box(
                                Modifier
                                    .background(Surf2, RoundedCornerShape(6.dp))
                                    .border(CardBorder, Brd, RoundedCornerShape(6.dp))
                                    .clickable { confirmReset = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                MonoLabel("RESET SESSION", 10.sp, Frost, FontWeight.Bold, 0.1.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Tokens.SectionGap))

        // ── DTC Scanner (collapsible, expanded by default) ────────────────
        SectionLabel("DTC SCANNER", collapsible = true, expanded = dtcExpanded,
            onToggle = { dtcExpanded = !dtcExpanded },
            modifier = pageEntrance(2, pageEntered))
        AnimatedVisibility(
            visible = dtcExpanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column {
                Spacer(Modifier.height(4.dp))
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
                            .pressClick(
                                enabled = (dtcScanning)
                                    || (!dtcBusy && vs.isConnected && onScanDtcs != null)
                            ) {
                                if (dtcScanning) {
                                    dtcScanJob?.cancel()
                                    dtcScanJob = null
                                    dtcScanning = false
                                    dtcError = "Scan aborted"
                                } else {
                                    dtcScanning = true
                                    dtcError = null
                                    dtcClearStatus = null
                                    scanComplete = false
                                    dtcScanJob = scope.launch(Dispatchers.IO) {
                                        val result = try {
                                            onScanDtcs?.invoke()
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (_: Exception) { null }
                                        withContext(Dispatchers.Main) {
                                            if (dtcScanning) {
                                                dtcScanning = false
                                                if (result != null) {
                                                    dtcResults = result
                                                    // Feed the GARAGE-tab badge with the active-code count.
                                                    com.openrs.dash.OpenRSDashApp.instance.activeDtcCount.value =
                                                        result.count { it.status == com.openrs.dash.data.DtcStatus.ACTIVE }
                                                } else {
                                                    dtcError = "Scan failed — check adapter connection"
                                                }
                                            }
                                            dtcScanJob = null
                                        }
                                    }
                                }
                            }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        MonoLabel(
                            when {
                                dtcScanning     -> "\u25A0  ABORT  (${dtcScanElapsed}s)"
                                !vs.isConnected -> "CONNECT TO SCAN"
                                else            -> "\u27F3  SCAN ALL MODULES"
                            },
                            11.sp,
                            when {
                                dtcScanning -> Warn
                                !dtcBusy && vs.isConnected && onScanDtcs != null -> accent
                                else -> Dim
                            },
                            letterSpacing = 0.08.sp
                        )
                    }

                    // Dismiss button — clears results from the display
                    if (dtcResults != null) {
                        Box(
                            Modifier.width(72.dp)
                                .background(Surf3, RoundedCornerShape(10.dp))
                                .border(CardBorder, Dim.copy(0.3f), RoundedCornerShape(10.dp))
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
                                    Orange.copy(alpha = 0.10f)
                                else Orange.copy(alpha = 0.04f),
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
                            .background(Ok.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .border(CardBorder, Ok.copy(0.3f), RoundedCornerShape(8.dp))
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
                var lastResults by remember { mutableStateOf<List<DtcResult>?>(null) }
                if (results != null) lastResults = results
                AnimatedVisibility(visible = results != null) {
                    lastResults?.let { r ->
                        Column(Modifier.fillMaxWidth()) {
                            if (r.isEmpty()) {
                                Box(
                                    Modifier.fillMaxWidth()
                                        .background(Ok.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                        .border(CardBorder, Ok.copy(0.25f), RoundedCornerShape(10.dp))
                                        .padding(14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    MonoLabel("✓  NO FAULT CODES — all modules clean", 11.sp, Ok)
                                }
                            } else {
                                val grouped = r.groupBy { it.module }
                                val moduleOrder = listOf("PCM", "BCM", "ABS", "AWD", "PSCM")
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(Surf2, RoundedCornerShape(10.dp))
                                        .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    var dtcIdx = 0
                                    for (moduleName in moduleOrder) {
                                        val moduleDtcs = grouped[moduleName] ?: continue
                                        MonoLabel(moduleName, 9.sp, accent, letterSpacing = 1.sp)
                                        moduleDtcs.forEach { dtc ->
                                            Box(pageEntrance(dtcIdx, scanComplete, staggerDelayMs = 60)) {
                                                DtcRow(dtc)
                                            }
                                            dtcIdx++
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
            }
        }

        // ── Clear DTC confirmation dialog (outside collapsible so it always works) ──
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
                                        com.openrs.dash.OpenRSDashApp.instance.activeDtcCount.value = 0
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

        Spacer(Modifier.height(Tokens.SectionGap))

        // ── Developer (collapsed by default; wraps expert-tier tools) ─────────
        SectionLabel("DEVELOPER", collapsible = true, expanded = developerExpanded,
            onToggle = { developerExpanded = !developerExpanded },
            modifier = pageEntrance(3, pageEntered))
        AnimatedVisibility(
            visible = developerExpanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column {

        Spacer(Modifier.height(Tokens.SectionGap))

        // ── Crash History (collapsed by default) ─────────────────────────────
        SectionLabel("CRASH HISTORY", collapsible = true, expanded = crashExpanded,
            onToggle = { crashExpanded = !crashExpanded })
        AnimatedVisibility(
            visible = crashExpanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column { CrashHistorySection() }
        }

        Spacer(Modifier.height(Tokens.SectionGap))

        // ── DID Prober (collapsed by default) ────────────────────────────────
        SectionLabel("DID PROBER", collapsible = true, expanded = didProberExpanded,
            onToggle = { didProberExpanded = !didProberExpanded })
        AnimatedVisibility(
            visible = didProberExpanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column { DidProberSection(vs, onSendRawQuery) }
        }

        Spacer(Modifier.height(Tokens.SectionGap))

        // ── Live CAN Output (collapsed by default) ───────────────────────────
        SectionLabel("LIVE CAN OUTPUT", collapsible = true, expanded = canOutputExpanded,
            onToggle = { canOutputExpanded = !canOutputExpanded })
        AnimatedVisibility(
            visible = canOutputExpanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column {
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(Surf3, Tokens.CardShape)
                        .border(Tokens.CardBorder, Brd, Tokens.CardShape)
                        .padding(Tokens.InnerV)
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
            }
        }

        if (inv.isNotEmpty()) {
            Spacer(Modifier.height(Tokens.SectionGap))

            // ── Frame Inventory (collapsed by default) ───────────────────────
            SectionLabel("FRAME INVENTORY (${inv.size} IDs)", collapsible = true,
                expanded = frameInvExpanded, onToggle = { frameInvExpanded = !frameInvExpanded })
            AnimatedVisibility(
                visible = frameInvExpanded,
                enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    val sortedEntries = inv.entries.sortedBy { it.key }
                    val visibleEntries = if (showAllFrames) sortedEntries else sortedEntries.take(15)
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Surf2, Tokens.CardShape)
                            .border(Tokens.CardBorder, Brd, Tokens.CardShape)
                            .padding(Tokens.InnerV),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        visibleEntries.forEach { (id, info) ->
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
                        if (!showAllFrames && sortedEntries.size > 15) {
                            Spacer(Modifier.height(4.dp))
                            MonoLabel(
                                "Show all ${sortedEntries.size} IDs ▸",
                                10.sp, accent,
                                modifier = Modifier.clickable { showAllFrames = true }.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Tokens.SectionGap))

        // ── PID Browser (collapsed by default) ───────────────────────────────
        SectionLabel("PID BROWSER", collapsible = true, expanded = pidBrowserExpanded,
            onToggle = { pidBrowserExpanded = !pidBrowserExpanded })
        AnimatedVisibility(
            visible = pidBrowserExpanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column { PidBrowserSection(
                onCopyNotify = { msg ->
                    if (snackbarHostState != null) {
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                }
            ) }
        }

            } // DEVELOPER column
        } // DEVELOPER AnimatedVisibility
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
        DtcStatus.PERMANENT -> Orange
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

    if (crashFiles.isEmpty()) {
        Box(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(10.dp))
                .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            MonoLabel("No crash reports", 10.sp, Dim)
        }
    } else {
        Column(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(10.dp))
                .border(CardBorder, Brd, RoundedCornerShape(10.dp))
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
                        .border(CardBorder, Brd, RoundedCornerShape(6.dp))
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
                    .border(CardBorder, Orange.copy(0.3f), RoundedCornerShape(8.dp))
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
