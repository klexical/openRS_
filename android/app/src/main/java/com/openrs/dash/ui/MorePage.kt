package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import android.content.Intent
import android.net.Uri
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.Tokens.PagePad
import com.openrs.dash.ui.anim.pressClick
import com.openrs.dash.data.PerformanceTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SAPPHIRE_URL = "https://klexical.github.io/openRS_/"

// ═══════════════════════════════════════════════════════════════════════════
// MORE PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun MorePage(
    vs: VehicleState,
    p: UserPrefs,
    snackbarHostState: SnackbarHostState,
    onSettings: () -> Unit,
    onCustomDash: () -> Unit = {},
    firmwareApi: FirmwareCommandSender? = null
) {
    val isFw   by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
    val fwLabel by OpenRSDashApp.instance.firmwareVersionLabel.collectAsState()
    val scope  = rememberCoroutineScope()
    val ctx    = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val accent = LocalThemeAccent.current
    val canControl = isFw && vs.isConnected
    var pendingDriveMode by remember { mutableStateOf<DriveMode?>(null) }
    var pendingEsc       by remember { mutableStateOf<EscStatus?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = PagePad, end = PagePad, top = PagePad, bottom = PagePad + Tokens.NavBarHeight),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Drive Mode ───────────────────────────────────────────────────
        MoreSection("DRIVE MODE") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DriveMode.NORMAL to "N", DriveMode.SPORT to "S",
                       DriveMode.TRACK to "T", DriveMode.DRIFT to "D")
                    .forEach { (mode, letter) ->
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
                                .background(
                                    if (isActive) modeAccent.copy(0.1f)
                                    else if (isPending) modeAccent.copy(0.05f)
                                    else Surf2,
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    if (isPending) 1.5.dp else 1.dp,
                                    if (isActive || isPending) modeAccent else Brd,
                                    RoundedCornerShape(10.dp)
                                )
                                .pressClick(enabled = canControl && firmwareApi != null && !isActive && pendingDriveMode == null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    pendingDriveMode = mode
                                    scope.launch {
                                        when (val r = executeDriveModeChange(firmwareApi!!, mode, vs.driveMode)) {
                                            is DriveCommandResult.Success -> { /* CAN state already updated */ }
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
                            HeroNum(letter, 20.sp, if (isActive) modeAccent else if (isPending) modeAccent.copy(0.6f) else Frost)
                            Spacer(Modifier.height(2.dp))
                            MonoLabel(
                                if (isPending) "..." else mode.label.uppercase(),
                                8.sp, if (isActive) modeAccent else Dim, letterSpacing = 0.1.sp
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

        HorizontalDivider(color = Brd)

        // ── ESC ──────────────────────────────────────────────────────────
        MoreSection("ELECTRONIC STABILITY CONTROL") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(EscStatus.ON to "ESC ON", EscStatus.PARTIAL to "SPORT", EscStatus.OFF to "ESC OFF")
                    .forEach { (status, label) ->
                        val isActive = vs.escStatus == status
                        val isPending = pendingEsc == status && !isActive
                        val color = when (status) {
                            EscStatus.ON -> Ok; EscStatus.PARTIAL -> Warn; else -> Orange
                        }
                        Box(
                            Modifier.weight(1f)
                                .background(
                                    if (isActive) color.copy(0.1f)
                                    else if (isPending) color.copy(0.05f)
                                    else Surf2,
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    if (isPending) 1.5.dp else 1.dp,
                                    if (isActive || isPending) color else Brd,
                                    RoundedCornerShape(10.dp)
                                )
                                .pressClick(enabled = canControl && firmwareApi != null && !isActive) {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    pendingEsc = status
                                    scope.launch {
                                        DiagnosticLogger.event("ESC_CMD",
                                            "Sending escMode=${status.toFirmwareInt()} (${status.label})")
                                        val result = firmwareApi!!.setEscMode(status.toFirmwareInt())
                                        if (result.isFailure) {
                                            DiagnosticLogger.event("ESC_CMD",
                                                "FAILED: ${result.exceptionOrNull()?.message}")
                                            snackbarHostState.showSnackbar("ESC command failed")
                                        } else {
                                            DiagnosticLogger.event("ESC_CMD", "OK")
                                        }
                                        pendingEsc = null
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MonoLabel(
                                if (isPending) "..." else label,
                                10.sp, if (isActive) color else Dim, letterSpacing = 0.08.sp
                            )
                        }
                    }
            }
            if (vs.escStatus == EscStatus.LAUNCH) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(Warn.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .border(CardBorder, Warn.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("⚡ ESC LAUNCH MODE", 10.sp, Warn, letterSpacing = 0.1.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            MonoLabel(
                if (canControl) "Tap to change \u00B7 Live from CAN 0x1C0"
                else "Current: ${vs.escStatus.label} (CAN 0x1C0). Use ESC button in car.",
                9.sp, Dim
            )
        }

        HorizontalDivider(color = Brd)

        // ── OpenRS-FW Features ───────────────────────────────────────────
        MoreSection(if (isFw) "OPENRS-FW ACTIVE" else "FEATURES — REQUIRES openrs-fw v1.0") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(
                    Modifier.weight(1f)
                        .background(Surf2, RoundedCornerShape(10.dp))
                        .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    UIText("Launch Control", 12.sp, Frost, FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    if (vs.launchControlActive) {
                        MonoText("⚡ ACTIVE", 10.sp, Warn)
                    } else {
                        val lcText = when {
                            vs.lcArmed == true  -> "● ARMED"
                            vs.lcArmed == false -> "○ STANDBY"
                            isFw && !vs.rsprotTimedOut -> "… PROBING"
                            else                -> "○ N/A"
                        }
                        val lcColor = when {
                            vs.lcArmed == true            -> Ok
                            isFw && !vs.rsprotTimedOut    -> Warn
                            else                          -> Dim
                        }
                        MonoText(lcText, 10.sp, lcColor)
                    }
                    if (vs.lcRpmTarget > 0) {
                        Spacer(Modifier.height(2.dp))
                        MonoLabel("${vs.lcRpmTarget} RPM", 9.sp, Dim)
                    }
                }
                Column(
                    Modifier.weight(1f)
                        .background(Surf2, RoundedCornerShape(10.dp))
                        .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    UIText("Auto Start-Stop", 12.sp, Frost, FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    val assText = when {
                        vs.assEnabled == true  -> "● ACTIVE"
                        vs.assEnabled == false -> "○ OFF"
                        isFw && !vs.rsprotTimedOut -> "… PROBING"
                        else                   -> "○ N/A"
                    }
                    val assColor = when {
                        vs.assEnabled == true            -> Ok
                        isFw && !vs.rsprotTimedOut       -> Warn
                        else                             -> Dim
                    }
                    MonoText(assText, 10.sp, assColor)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth()
                    .background(if (isFw) Ok.copy(alpha = 0.06f) else Orange.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                    .border(CardBorder, if (isFw) Ok.copy(0.2f) else Orange.copy(0.2f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column {
                    MonoLabel(
                        if (isFw) "✓  $fwLabel detected"
                        else "⚡  Flash openrs-fw to unlock CAN write, LC, Auto Start-Stop & more.",
                        9.sp, if (isFw) Ok else Orange, letterSpacing = 0.05.sp
                    )
                }
            }
        }

        HorizontalDivider(color = Brd)

        // ── Module Status ────────────────────────────────────────────────
        MoreSection("MODULE STATUS") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                data class ModuleInfo(val label: String, val state: Boolean?, val timedOut: Boolean, val subtitle: String)
                listOf(
                    ModuleInfo("RDU",  vs.rduEnabled,  false, "Rear Drive Unit"),
                    ModuleInfo("PDC",  vs.pdcEnabled,  false, "Pull Drift Comp"),
                    ModuleInfo("FENG", vs.fengEnabled, vs.fengTimedOut, "Engine Sound")
                ).forEach { (label, state, timedOut, subtitle) ->
                    Column(
                        Modifier.weight(1f)
                            .background(Surf2, RoundedCornerShape(10.dp))
                            .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        UIText(label, 12.sp, Frost, FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val (dot, col) = when {
                            state == true  -> "● ON"  to Ok
                            state == false -> "○ OFF" to Dim
                            timedOut       -> "○ N/A" to Dim
                            else           -> "…"     to Warn
                        }
                        MonoText(dot, 10.sp, col)
                        Spacer(Modifier.height(2.dp))
                        MonoLabel(subtitle, 8.sp, Dim, letterSpacing = 0.08.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            MonoLabel("Polled via extended diagnostic session (60 s cycle).", 9.sp, Dim)
        }

        HorizontalDivider(color = Brd)

        // ── VIN (passive CAN 0x40A) ──────────────────────────────────────
        if (vs.vin.isNotEmpty()) {
            MoreSection("VEHICLE IDENTIFICATION") {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Surf2, RoundedCornerShape(8.dp))
                        .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoLabel("VIN", 9.sp, Dim, letterSpacing = 0.1.sp)
                    Spacer(Modifier.weight(1f))
                    MonoText(vs.vin, 12.sp, Frost)
                }
            }

            HorizontalDivider(color = Brd)
        }

        // ── Custom Dashboard ──────────────────────────────────────────────
        MoreSection("CUSTOM DASHBOARD") {
            val savedLayout = remember { AppSettings.loadCustomDash(ctx) }
            val gaugeCount = savedLayout?.cells?.size ?: 0
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(listOf(accent.copy(0.1f), accent.copy(0.05f))),
                        RoundedCornerShape(10.dp)
                    )
                    .border(CardBorder, accent.copy(0.3f), RoundedCornerShape(10.dp))
                    .clickable { onCustomDash() }
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        UIText("Open Custom Dashboard", 12.sp, Frost, FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        MonoLabel(
                            if (gaugeCount > 0) "$gaugeCount gauges configured"
                            else "Build a custom gauge layout",
                            9.sp, Dim
                        )
                    }
                    MonoLabel("\u25B6 OPEN", 10.sp, accent, letterSpacing = 0.1.sp)
                }
            }
        }

        HorizontalDivider(color = Brd)

        // ── Performance Timer ────────────────────────────────────────────
        val timerState by PerformanceTimer.state.collectAsState()
        SideEffect {
            if (timerState.state == PerformanceTimer.State.ARMED ||
                timerState.state == PerformanceTimer.State.RUNNING) {
                PerformanceTimer.onSpeedUpdate(vs.speedKph, vs.rpm, vs.boostPsi)
            }
        }
        PerformanceTimerSection(timerState, accent)

        HorizontalDivider(color = Brd)

        // ── Sapphire Web Dashboard ───────────────────────────────────────
        MoreSection("WEB DASHBOARD") {
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(listOf(accent.copy(0.08f), accent.copy(0.03f))),
                        RoundedCornerShape(10.dp)
                    )
                    .border(CardBorder, accent.copy(0.2f), RoundedCornerShape(10.dp))
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SAPPHIRE_URL))
                        ctx.startActivity(intent)
                    }
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoLabel("SAPPHIRE", 11.sp, accent, fontWeight = FontWeight.Bold, letterSpacing = 0.15.sp)
                        Spacer(Modifier.weight(1f))
                        MonoLabel("↗ OPEN", 10.sp, accent, letterSpacing = 0.1.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    MonoLabel(
                        "Analyse trip & diagnostic data in your browser. Drop an export ZIP to explore charts, maps, and CAN data.",
                        9.sp, Dim
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable fun MoreSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        MonoLabel(title, 9.sp, Dim, letterSpacing = 0.2.sp, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

@Composable fun ThemePicker(p: UserPrefs) {
    val ctx = LocalContext.current
    val themes = RsPaints.map { it.id to it.name }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.take(3).forEach { (id, name) ->
                ThemeChip(id, name, rsPaintAccent(id), p.themeId == id, ctx, p, Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.drop(3).forEach { (id, name) ->
                ThemeChip(id, name, rsPaintAccent(id), p.themeId == id, ctx, p, Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun ThemeChip(
    id: String, name: String, color: androidx.compose.ui.graphics.Color,
    isActive: Boolean, ctx: android.content.Context, p: UserPrefs, modifier: Modifier
) {
    Column(
        modifier
            .background(if (isActive) color.copy(alpha = 0.12f) else Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, if (isActive) color else Brd, RoundedCornerShape(10.dp))
            .clickable { UserPrefsStore.update(ctx) { it.copy(themeId = id) } }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(color)
                .border(if (isActive) 2.dp else 0.dp, Frost.copy(0.6f), CircleShape)
        )
        Spacer(Modifier.height(5.dp))
        MonoLabel(name, 8.sp, if (isActive) color else Dim, letterSpacing = 0.1.sp,
            modifier = Modifier.fillMaxWidth(), fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PERFORMANCE TIMER SECTION
// ═══════════════════════════════════════════════════════════════════════════

@Composable private fun PerformanceTimerSection(
    ts: PerformanceTimer.TimerState,
    accent: androidx.compose.ui.graphics.Color
) {
    MoreSection("PERFORMANCE TIMER") {
        Column(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(12.dp))
                .border(CardBorder, when (ts.state) {
                    PerformanceTimer.State.RUNNING  -> accent.copy(0.6f)
                    PerformanceTimer.State.ARMED    -> Warn.copy(0.4f)
                    PerformanceTimer.State.FINISHED -> Ok.copy(0.4f)
                    else -> Brd
                }, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header row: target toggle
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .background(Surf, RoundedCornerShape(6.dp))
                        .border(CardBorder, Brd, RoundedCornerShape(6.dp))
                        .clickable {
                            val next = if (ts.target == PerformanceTimer.Target.ZERO_TO_60)
                                PerformanceTimer.Target.ZERO_TO_100
                            else PerformanceTimer.Target.ZERO_TO_60
                            if (ts.state == PerformanceTimer.State.IDLE) PerformanceTimer.arm(next)
                            else if (ts.state == PerformanceTimer.State.FINISHED) {
                                PerformanceTimer.reset()
                                PerformanceTimer.arm(next)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    MonoLabel(ts.target.label, 9.sp, Frost, letterSpacing = 0.1.sp)
                }
            }

            // Timer display
            val timeStr = when (ts.state) {
                PerformanceTimer.State.IDLE     -> "—.——"
                PerformanceTimer.State.ARMED    -> "0.00"
                PerformanceTimer.State.RUNNING  -> "%.2f".format(ts.elapsedMs / 1000.0)
                PerformanceTimer.State.FINISHED -> "%.2f".format(ts.resultMs / 1000.0)
            }
            val timeColor = when (ts.state) {
                PerformanceTimer.State.RUNNING  -> accent
                PerformanceTimer.State.FINISHED -> Ok
                PerformanceTimer.State.ARMED    -> Warn
                else -> Frost
            }
            HeroNum(timeStr, 42.sp, timeColor)
            MonoLabel("seconds", 8.sp, Dim)

            // Status / details row
            when (ts.state) {
                PerformanceTimer.State.IDLE -> {
                    Box(
                        Modifier
                            .background(accent.copy(0.12f), RoundedCornerShape(6.dp))
                            .border(CardBorder, accent.copy(0.3f), RoundedCornerShape(6.dp))
                            .pressClick { PerformanceTimer.arm(ts.target) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        MonoLabel("ARM TIMER", 10.sp, accent, FontWeight.Bold)
                    }
                }
                PerformanceTimer.State.ARMED -> {
                    MonoLabel("Waiting for launch…", 10.sp, Warn)
                }
                PerformanceTimer.State.RUNNING -> {
                    MonoLabel("Launch RPM: ${ts.launchRpm.roundToInt()}", 9.sp, Dim)
                }
                PerformanceTimer.State.FINISHED -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DataCell("LAUNCH", "${ts.launchRpm.roundToInt()} RPM", modifier = Modifier.weight(1f))
                        DataCell("BOOST", "${"%.1f".format(ts.peakBoostPsi)} PSI", modifier = Modifier.weight(1f))
                        if (ts.bestResultMs > 0) {
                            DataCell("BEST", "${"%.2f".format(ts.bestResultMs / 1000.0)}s", modifier = Modifier.weight(1f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .background(accent.copy(0.12f), RoundedCornerShape(6.dp))
                                .border(CardBorder, accent.copy(0.3f), RoundedCornerShape(6.dp))
                                .pressClick { PerformanceTimer.reset(); PerformanceTimer.arm(ts.target) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            MonoLabel("GO AGAIN", 10.sp, accent, FontWeight.Bold)
                        }
                        Box(
                            Modifier
                                .background(Surf, RoundedCornerShape(6.dp))
                                .border(CardBorder, Brd, RoundedCornerShape(6.dp))
                                .clickable { PerformanceTimer.reset() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            MonoLabel("RESET", 10.sp, Dim)
                        }
                    }
                }
            }
        }
    }
}

