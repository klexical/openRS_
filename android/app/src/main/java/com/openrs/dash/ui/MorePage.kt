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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════════════
// MORE PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun MorePage(
    vs: VehicleState,
    p: UserPrefs,
    snackbarHostState: SnackbarHostState,
    onSettings: () -> Unit
) {
    val isFw   by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
    val scope  = rememberCoroutineScope()
    val ctx    = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    val accent = LocalThemeAccent.current

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
                            DriveMode.DRIFT -> Red
                            else            -> accent
                        }
                        Column(
                            Modifier.weight(1f)
                                .background(if (isActive) modeAccent.copy(0.1f) else Surf2, RoundedCornerShape(10.dp))
                                .border(1.dp, if (isActive) modeAccent else Brd, RoundedCornerShape(10.dp))
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            HeroNum(letter, 20.sp, if (isActive) modeAccent else Frost)
                            Spacer(Modifier.height(2.dp))
                            MonoLabel(mode.label.uppercase(), 8.sp, if (isActive) modeAccent else Dim, letterSpacing = 0.1.sp)
                        }
                    }
            }
            Spacer(Modifier.height(6.dp))
            MonoLabel("Read-only mirror of CAN 0x1B0. Use steering wheel MODE button.", 9.sp, Dim)
        }

        HorizontalDivider(color = Brd)

        // ── ESC ──────────────────────────────────────────────────────────
        MoreSection("ELECTRONIC STABILITY CONTROL") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(EscStatus.ON to "ESC ON", EscStatus.PARTIAL to "SPORT", EscStatus.OFF to "ESC OFF")
                    .forEach { (status, label) ->
                        val isActive = vs.escStatus == status
                        val color = when (status) {
                            EscStatus.ON -> Ok; EscStatus.PARTIAL -> Warn; else -> Red
                        }
                        Box(
                            Modifier.weight(1f)
                                .background(if (isActive) color.copy(0.1f) else Surf2, RoundedCornerShape(10.dp))
                                .border(1.dp, if (isActive) color else Brd, RoundedCornerShape(10.dp))
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MonoLabel(label, 10.sp, if (isActive) color else Dim, letterSpacing = 0.08.sp)
                        }
                    }
            }
            Spacer(Modifier.height(6.dp))
            MonoLabel("Current: ${vs.escStatus.label} (CAN 0x1C0). Use ESC button in car.", 9.sp, Dim)
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
                    val lcText = when {
                        vs.lcArmed == true  -> "● ARMED"
                        vs.lcArmed == false -> "○ STANDBY"
                        isFw                -> "… PROBING"
                        else                -> "○ N/A"
                    }
                    val lcColor = when {
                        vs.lcArmed == true -> Ok
                        isFw               -> Warn
                        else               -> Dim
                    }
                    MonoText(lcText, 10.sp, lcColor)
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
                        isFw                   -> "… PROBING"
                        else                   -> "○ N/A"
                    }
                    val assColor = when {
                        vs.assEnabled == true -> Ok
                        isFw                  -> Warn
                        else                  -> Dim
                    }
                    MonoText(assText, 10.sp, assColor)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth()
                    .background(if (isFw) Ok.copy(alpha = 0.06f) else Red.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                    .border(1.dp, if (isFw) Ok.copy(0.2f) else Red.copy(0.2f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonoLabel(
                    if (isFw) "✓  openRS_ firmware detected — features active."
                    else "⚡  Flash openrs-fw to unlock CAN write, LC, Auto Start-Stop & more.",
                    9.sp, if (isFw) Ok else Red, letterSpacing = 0.05.sp
                )
            }
        }

        HorizontalDivider(color = Brd)

        // ── Module Status ────────────────────────────────────────────────
        MoreSection("MODULE STATUS") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple("RDU",  vs.rduEnabled,  "Rear Drive Unit"),
                    Triple("PDC",  vs.pdcEnabled,  "Pull Drift Comp"),
                    Triple("FENG", vs.fengEnabled, "Engine Sound")
                ).forEach { (label, state, subtitle) ->
                    Column(
                        Modifier.weight(1f)
                            .background(Surf2, RoundedCornerShape(10.dp))
                            .border(1.dp, Brd, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        UIText(label, 12.sp, Frost, FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val (dot, col) = when (state) {
                            true  -> "● ON"  to Ok
                            false -> "○ OFF" to Dim
                            null  -> "…"     to Warn
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

        // ── RS Theme ─────────────────────────────────────────────────────
        MoreSection("THEME — RS PAINT COLOUR") {
            ThemePicker(p)
        }

        HorizontalDivider(color = Brd)

        // ── Connection & Diagnostics ─────────────────────────────────────
        MoreSection("CONNECTION & DIAGNOSTICS") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DataCell("STATUS", if (vs.isConnected) "LIVE" else "OFFLINE",
                    valueColor = if (vs.isConnected) Ok else Red, modifier = Modifier.weight(1f))
                DataCell("MODE", vs.dataMode, modifier = Modifier.weight(1f))
                DataCell("FPS",  "${vs.framesPerSecond.toInt()}", modifier = Modifier.weight(1f))
                DataCell("SESSION", DiagnosticLogger.formatDuration(remember(vs.framesPerSecond) { DiagnosticLogger.sessionDurationMs }),
                    modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(accent.copy(0.1f), accent.copy(0.05f))), RoundedCornerShape(10.dp))
                    .border(1.dp, accent.copy(0.3f), RoundedCornerShape(10.dp))
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
                    12.sp, accent, letterSpacing = 0.1.sp
                )
            }
        }

        HorizontalDivider(color = Brd)

        // ── Display Settings ─────────────────────────────────────────────
        MoreSection("DISPLAY SETTINGS") {
            Box(
                Modifier.fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .border(1.dp, Brd, RoundedCornerShape(10.dp))
                    .clickable { onSettings() }
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        MonoLabel("Units, TPMS threshold, connection settings", 9.sp, Dim)
                        Spacer(Modifier.height(2.dp))
                        UIText("${p.speedLabel} · ${p.tempLabel} · ${p.boostUnit}", 12.sp, Frost)
                    }
                    MonoLabel("⚙ OPEN", 10.sp, accent, letterSpacing = 0.1.sp)
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

/** Returns the accent Color for a theme ID without allocating a full UserPrefs instance. */
private fun themeAccentColor(id: String): androidx.compose.ui.graphics.Color = when (id) {
    "red"    -> androidx.compose.ui.graphics.Color(0xFFFF2233)
    "orange" -> androidx.compose.ui.graphics.Color(0xFFFF6600)
    "green"  -> androidx.compose.ui.graphics.Color(0xFF00FF88)
    "purple" -> androidx.compose.ui.graphics.Color(0xFF8C7AFF)
    "silver" -> androidx.compose.ui.graphics.Color(0xFFAAC4DD)
    else     -> androidx.compose.ui.graphics.Color(0xFF00D2FF)
}

@Composable fun ThemePicker(p: UserPrefs) {
    val ctx = LocalContext.current
    val themes = listOf(
        "cyan"   to "Nitrous Blue",
        "red"    to "Race Red",
        "orange" to "Tangerine",
        "green"  to "Mean Green",
        "purple" to "Stealth",
        "silver" to "Silver"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.take(3).forEach { (id, name) ->
                ThemeChip(id, name, themeAccentColor(id), p.themeId == id, ctx, p, Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.drop(3).forEach { (id, name) ->
                ThemeChip(id, name, themeAccentColor(id), p.themeId == id, ctx, p, Modifier.weight(1f))
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
