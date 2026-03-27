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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.openrs.dash.can.BusyException
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.FirmwareApi
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.SessionDatabase
import com.openrs.dash.data.SessionEntity
import com.openrs.dash.data.SnapshotEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SAPPHIRE_URL = "https://klexical.github.io/openRS_/"

// ═══════════════════════════════════════════════════════════════════════════
// MORE PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun MorePage(
    vs: VehicleState,
    p: UserPrefs,
    snackbarHostState: SnackbarHostState,
    onSettings: () -> Unit,
    onCustomDash: () -> Unit = {}
) {
    val isFw   by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
    val fwLabel by OpenRSDashApp.instance.firmwareVersionLabel.collectAsState()
    val scope  = rememberCoroutineScope()
    val ctx    = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val accent = LocalThemeAccent.current
    val canControl = isFw && vs.isConnected
    val prefs by UserPrefsStore.prefs.collectAsState()
    val host = remember(prefs) { AppSettings.getHost(ctx) }
    var pendingDriveMode by remember { mutableStateOf<DriveMode?>(null) }
    var pendingEsc       by remember { mutableStateOf<EscStatus?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
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
                                .clickable(enabled = canControl && !isActive && pendingDriveMode == null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    pendingDriveMode = mode
                                    scope.launch {
                                        DiagnosticLogger.event("DM_CMD",
                                            "Sending driveMode=${mode.toFirmwareInt()} (${mode.label}) to $host")
                                        val result = FirmwareApi.setDriveMode(ctx, host, mode.toFirmwareInt())
                                        if (result.isFailure) {
                                            val ex = result.exceptionOrNull()
                                            DiagnosticLogger.event("DM_CMD",
                                                "FAILED: ${ex?.message}")
                                            val msg = if (ex is BusyException)
                                                "Mode change in progress — please wait"
                                            else "Drive mode command failed"
                                            snackbarHostState.showSnackbar(msg)
                                        } else {
                                            DiagnosticLogger.event("DM_CMD", "OK (HTTP 200)")
                                            // Settling delay: firmware needs time to press the
                                            // mode button and ECU needs time to broadcast the
                                            // new mode on 0x420 (~600 ms interval). SLCAN data
                                            // (2026-03-26) showed 4s firmware delay on cold start.
                                            delay(2_000)
                                            // Watch CAN for confirmation (up to 15s after settling).
                                            // Read live state each iteration — vs is an immutable snapshot.
                                            var confirmed = false
                                            for (i in 0 until 150) {
                                                delay(100)
                                                val live = OpenRSDashApp.instance.vehicleState.value
                                                if (live.driveMode == mode) {
                                                    confirmed = true
                                                    break
                                                }
                                            }
                                            if (!confirmed) {
                                                val live = OpenRSDashApp.instance.vehicleState.value
                                                DiagnosticLogger.event("DM_CMD",
                                                    "No CAN confirmation after 17s (current=${live.driveMode}, target=$mode, modeDetail420=0x${CanDecoder.modeDetail420Hex})")
                                                snackbarHostState.showSnackbar(
                                                    "Mode change didn't take effect — try again")
                                            }
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
                if (canControl) "Tap to change \u00B7 Live from CAN 0x1B0"
                else "Read-only mirror of CAN 0x1B0. Use steering wheel MODE button.",
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
                                .clickable(enabled = canControl && !isActive) {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    pendingEsc = status
                                    scope.launch {
                                        DiagnosticLogger.event("ESC_CMD",
                                            "Sending escMode=${status.toFirmwareInt()} (${status.label}) to $host")
                                        val result = FirmwareApi.setEscMode(ctx, host, status.toFirmwareInt())
                                        if (result.isFailure) {
                                            DiagnosticLogger.event("ESC_CMD",
                                                "FAILED: ${result.exceptionOrNull()?.message}")
                                            snackbarHostState.showSnackbar("ESC command failed")
                                        } else {
                                            DiagnosticLogger.event("ESC_CMD", "OK (HTTP 200)")
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
                        .border(1.dp, Warn.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
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
                        .border(1.dp, Brd, RoundedCornerShape(10.dp))
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
                        .border(1.dp, Brd, RoundedCornerShape(10.dp))
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
                    .border(1.dp, if (isFw) Ok.copy(0.2f) else Orange.copy(0.2f), RoundedCornerShape(8.dp))
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
                            .border(1.dp, Brd, RoundedCornerShape(10.dp))
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
                    .border(1.dp, accent.copy(0.3f), RoundedCornerShape(10.dp))
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

        // ── Sapphire Web Dashboard ───────────────────────────────────────
        MoreSection("WEB DASHBOARD") {
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(listOf(accent.copy(0.08f), accent.copy(0.03f))),
                        RoundedCornerShape(10.dp)
                    )
                    .border(1.dp, accent.copy(0.2f), RoundedCornerShape(10.dp))
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

        HorizontalDivider(color = Brd)

        // ── Session History ────────────────────────────────────────────────
        SessionHistorySection()

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
            .border(2.dp, if (isActive) color else Brd, RoundedCornerShape(10.dp))
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
// SESSION HISTORY
// ═══════════════════════════════════════════════════════════════════════════

private val sessionDateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

@Composable fun SessionHistorySection() {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current
    val prefs by UserPrefsStore.prefs.collectAsState()
    var sessions by remember { mutableStateOf<List<SessionEntity>>(emptyList()) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var snapshots by remember { mutableStateOf<List<SnapshotEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sessions = SessionDatabase.getInstance(ctx).sessionDao().getRecentSessions(10)
        }
    }

    MoreSection("SESSION HISTORY") {
        if (sessions.isEmpty()) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .border(1.dp, Brd, RoundedCornerShape(10.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("No sessions recorded yet", 10.sp, Dim)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sessions.forEach { session ->
                    val isExpanded = expandedId == session.id
                    SessionCard(
                        session = session,
                        prefs = prefs,
                        isExpanded = isExpanded,
                        snapshots = if (isExpanded) snapshots else emptyList(),
                        onToggle = {
                            if (isExpanded) {
                                expandedId = null
                                snapshots = emptyList()
                            } else {
                                expandedId = session.id
                                scope.launch(Dispatchers.IO) {
                                    val loaded = SessionDatabase.getInstance(ctx)
                                        .sessionDao().getSnapshots(session.id)
                                    withContext(Dispatchers.Main) { snapshots = loaded }
                                }
                            }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        MonoLabel("Last 10 sessions. Auto-pruned after 30 days.", 9.sp, Dim)
    }
}

@Composable private fun SessionCard(
    session: SessionEntity,
    prefs: UserPrefs,
    isExpanded: Boolean,
    snapshots: List<SnapshotEntity>,
    onToggle: () -> Unit
) {
    val accent = LocalThemeAccent.current
    val dateStr = sessionDateFormat.format(Date(session.startTime))
    val durationMs = if (session.endTime > 0) session.endTime - session.startTime else 0L
    val durationStr = formatSessionDuration(durationMs)
    val isActive = session.endTime == 0L

    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, if (isActive) accent.copy(0.4f) else Brd, RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(12.dp)
    ) {
        // Header row: date + duration
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoLabel(dateStr, 10.sp, Frost, FontWeight.SemiBold)
                if (isActive) {
                    MonoLabel("LIVE", 8.sp, Ok, FontWeight.Bold, letterSpacing = 0.1.sp)
                }
            }
            MonoLabel(
                if (isActive) "active" else durationStr,
                9.sp,
                if (isActive) accent else Dim
            )
        }
        Spacer(Modifier.height(8.dp))

        // Peak metrics row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SessionMetric(
                label = "RPM",
                value = if (session.peakRpm > 0) "${session.peakRpm.toInt()}" else "--",
                modifier = Modifier.weight(1f)
            )
            SessionMetric(
                label = "BOOST",
                value = if (session.peakBoostPsi > 0) String.format("%.1f", session.peakBoostPsi)
                        else "--",
                unit = "PSI",
                modifier = Modifier.weight(1f)
            )
            SessionMetric(
                label = "SPEED",
                value = if (session.peakSpeedKph > 0) {
                    val speed = if (prefs.speedUnit == "MPH")
                        session.peakSpeedKph * 0.621371 else session.peakSpeedKph
                    "${speed.toInt()}"
                } else "--",
                unit = prefs.speedLabel,
                modifier = Modifier.weight(1f)
            )
            SessionMetric(
                label = "OIL",
                value = if (session.peakOilTempC > -90) {
                    prefs.displayTemp(session.peakOilTempC)
                } else "--",
                unit = prefs.tempLabel,
                modifier = Modifier.weight(1f)
            )
        }

        // Expanded: snapshot summary
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                if (snapshots.isEmpty()) {
                    MonoLabel("No snapshots in this session", 9.sp, Dim)
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(1.dp).background(Brd)
                    )
                    Spacer(Modifier.height(8.dp))

                    // Summary stats from snapshots
                    val avgRpm = snapshots.map { it.rpm }.average()
                    val maxRpm = snapshots.maxOf { it.rpm }
                    val avgSpeed = snapshots.map { it.speedKph }.average()
                    val maxThrottle = snapshots.maxOf { it.throttlePct }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SessionMetric("AVG RPM", "${avgRpm.toInt()}", modifier = Modifier.weight(1f))
                        SessionMetric("MAX RPM", "${maxRpm.toInt()}", modifier = Modifier.weight(1f))
                        SessionMetric(
                            "AVG SPEED",
                            if (prefs.speedUnit == "MPH") "${(avgSpeed * 0.621371).toInt()}"
                            else "${avgSpeed.toInt()}",
                            unit = prefs.speedLabel,
                            modifier = Modifier.weight(1f)
                        )
                        SessionMetric("THROTTLE", "${maxThrottle.toInt()}%", modifier = Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(6.dp))
                    MonoLabel(
                        "${snapshots.size} snapshots \u00B7 ${session.totalFrames} CAN frames",
                        8.sp, Dim
                    )
                }
            }
        }
    }
}

@Composable private fun SessionMetric(
    label: String,
    value: String,
    unit: String = "",
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Surf, RoundedCornerShape(6.dp))
            .border(1.dp, Brd, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 7.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(2.dp))
        MonoText(value, 12.sp, Frost)
        if (unit.isNotEmpty()) {
            MonoLabel(unit, 7.sp, Dim)
        }
    }
}

private fun formatSessionDuration(ms: Long): String {
    if (ms <= 0) return "--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%dh %02dm", h, m)
           else String.format("%dm %02ds", m, s)
}
