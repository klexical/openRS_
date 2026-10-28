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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.WicanApi
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.Tokens.CardGap
import com.openrs.dash.ui.Tokens.PagePad
import com.openrs.dash.ui.Tokens.SectionGap
import com.openrs.dash.ui.anim.pageEntrance
import com.openrs.dash.ui.anim.pressClick
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════
// CONTROL PAGE (formerly MorePage)
// rc.2: regrouped into Vehicle Controls + Firmware Status zones.
// VIN → DiagPage, Custom Dashboard + Sapphire → SettingsSheet.
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun MorePage(
    vs: VehicleState,
    p: UserPrefs,
    snackbarHostState: SnackbarHostState,
    firmwareApi: FirmwareCommandSender? = null,
    onOpenDock: () -> Unit = {},
) {
    val isFw   by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
    val fwLabel by OpenRSDashApp.instance.firmwareVersionLabel.collectAsState()
    val devStatus by OpenRSDashApp.instance.deviceStatus.collectAsState()
    val scope  = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val accent = LocalThemeAccent.current
    val canControl = isFw && vs.isConnected
    var pendingEsc by remember { mutableStateOf<EscStatus?>(null) }
    var fwStatusExpanded by rememberSectionExpanded("CONTROL_FW_STATUS")
    var pageEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pageEntered = true }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = PagePad, end = PagePad, top = PagePad, bottom = pageBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(SectionGap)
    ) {

        // ══════════════════════════════════════════════════════════════════
        // Zone A — Vehicle Controls
        // ══════════════════════════════════════════════════════════════════
        SectionLabel("VEHICLE CONTROLS", modifier = pageEntrance(0, pageEntered))

        if (vs.isConnected) {
            Column(modifier = pageEntrance(1, pageEntered), verticalArrangement = Arrangement.spacedBy(CardGap)) {
                // ── Drive Mode readout ───────────────────────────────────
                // Design pass: the mode is a reading (ink, as in the header) — SPORT green / TRACK amber /
                // DRIFT orange read as OK / caution / alarm. CHANGE MODE is the accent action.
                Box(
                    Modifier.fillMaxWidth()
                        .instrumentSurface()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            InstrumentLabel("DRIVE MODE")
                            Spacer(Modifier.height(2.dp))
                            HeroNum(vs.driveMode.label.uppercase(), 22.sp, Frost)
                        }
                        Box(
                            Modifier
                                .heightIn(min = 48.dp)
                                .background(
                                    if (canControl) accent.copy(alpha = pillBgAlpha(0.14f)) else Surf2,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    CardBorder,
                                    if (canControl) accent.copy(alpha = borderAlpha(0.55f)) else Brd,
                                    RoundedCornerShape(8.dp)
                                )
                                .pressClick(enabled = canControl) {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onOpenDock()
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MonoLabel(
                                "CHANGE MODE \u25BE",
                                11.sp,
                                if (canControl) actionInk(accent) else Dim,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                }

                // ── ESC ──────────────────────────────────────────────────
                InstrumentLabel("ESC")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(EscStatus.ON to "ESC ON", EscStatus.PARTIAL to "SPORT", EscStatus.OFF to "ESC OFF")
                        .forEach { (status, label) ->
                            val isActive = vs.escStatus == status
                            val isPending = pendingEsc == status && !isActive
                            // ON = normal (accent, as a selected chip); SPORT / OFF keep the header's
                            // reduced-stability colours.
                            val color = when (status) {
                                EscStatus.ON -> accent; EscStatus.PARTIAL -> Warn; else -> Orange
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
                                    .heightIn(min = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                MonoLabel(
                                    if (isPending) "..." else label,
                                    11.sp, if (isActive) textColor(if (color == accent) actionInk(accent) else color) else Mid,
                                    if (isActive) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.3.sp
                                )
                            }
                        }
                }
                if (vs.escStatus == EscStatus.LAUNCH) {
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
                InstrumentMeta(
                    if (canControl) "Tap to change · Live from CAN 0x1C0"
                    else "Current: ${vs.escStatus.label} (CAN 0x1C0). Use ESC button in car."
                )
            }
        } else {
            Box(
                pageEntrance(1, pageEntered).fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(8.dp))
                    .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("\u2014 CONNECT ADAPTER TO CONFIGURE \u2014", 11.sp, Mid, letterSpacing = 0.2.sp)
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // Zone B — Firmware Status (collapsible)
        // ══════════════════════════════════════════════════════════════════
        SectionLabel("FIRMWARE STATUS", collapsible = true, expanded = fwStatusExpanded,
            onToggle = { fwStatusExpanded = !fwStatusExpanded },
            modifier = pageEntrance(2, pageEntered))

        AnimatedVisibility(
            visible = fwStatusExpanded,
            modifier = pageEntrance(3, pageEntered),
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            data class ModuleInfo(val label: String, val state: Boolean?, val timedOut: Boolean, val subtitle: String)
            val modules = listOf(
                ModuleInfo("RDU",  vs.rduEnabled,  false, "Rear Drive Unit"),
                ModuleInfo("PDC",  vs.pdcEnabled,  false, "Pull Drift Comp"),
                ModuleInfo("FENG", vs.fengEnabled, vs.fengTimedOut, "Engine Sound")
            )
            val allModulesEmpty = modules.all { it.state == null && !it.timedOut }

            Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                if (!vs.isConnected) {
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Surf2, RoundedCornerShape(8.dp))
                            .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        MonoLabel("\u2014 CONNECT TO POPULATE \u2014", 11.sp, Mid, letterSpacing = 0.2.sp)
                    }
                } else {
                    // Design pass: one FEATURES panel (was five cards). States read neutral — STANDBY / OFF /
                    // PROBING were amber, which signals a problem; ON / ARMED take an accent dot; an engaged
                    // launch control (on / engaged) uses the paint accent, as on DRIVE.
                    data class Feature(val name: String, val code: String?, val glyph: String, val state: String, val color: Color)
                    val lcState = when {
                        vs.isConnected && vs.launchControlEngaged -> Triple("\u26A1", "ENGAGED", actionInk(LocalThemeAccent.current))
                        vs.isConnected && vs.launchControlActive -> Triple("\u26A1", "ON", actionInk(LocalThemeAccent.current))
                        vs.lcArmed == true     -> Triple("\u25CF", "ARMED", Frost)
                        vs.lcArmed == false    -> Triple("\u25CB", "STANDBY", Mid)
                        isFw && !vs.rsprotTimedOut -> Triple("\u2026", "PROBING", Dim)
                        else                   -> Triple("\u25CB", "N/A", Dim)
                    }
                    val assState = when {
                        vs.assEnabled == true  -> Triple("\u25CF", "ACTIVE", Frost)
                        vs.assEnabled == false -> Triple("\u25CB", "OFF", Mid)
                        isFw && !vs.rsprotTimedOut -> Triple("\u2026", "PROBING", Dim)
                        else                   -> Triple("\u25CB", "N/A", Dim)
                    }
                    val features = buildList {
                        add(Feature("Launch Control", if (vs.lcRpmTarget > 0) "${vs.lcRpmTarget} RPM" else null,
                            lcState.first, lcState.second, lcState.third))
                        add(Feature("Auto Start-Stop", null, assState.first, assState.second, assState.third))
                        if (!allModulesEmpty) modules.forEach { (label, state, timedOut, subtitle) ->
                            val (g, txt, col) = when {
                                state == true  -> Triple("\u25CF", "ON", Frost)
                                state == false -> Triple("\u25CB", "OFF", Mid)
                                timedOut       -> Triple("\u25CB", "N/A", Dim)
                                else           -> Triple("\u2026", "PROBING", Dim)
                            }
                            add(Feature(subtitle, label, g, txt, col))
                        }
                    }
                    Column(Modifier.fillMaxWidth().instrumentSurface().padding(horizontal = 14.dp, vertical = 4.dp)) {
                        features.forEachIndexed { i, f ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.35f))))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    UIText(f.name, 14.sp, Frost, FontWeight.SemiBold)
                                    f.code?.let { InstrumentMeta(it) }
                                }
                                // The dot carries the accent when on; the word stays readable ink / neutral
                                MonoLabel(f.glyph, 12.sp, if (f.color == Frost) accent else f.color, FontWeight.Bold)
                                Spacer(Modifier.width(6.dp))
                                MonoLabel(f.state, 11.sp, textColor(f.color), FontWeight.Bold, 0.3.sp)
                            }
                        }
                        if (allModulesEmpty) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.35f))))
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                InstrumentMeta("\u00B7\u00B7\u00B7  probing modules  \u00B7\u00B7\u00B7")
                            }
                        }
                    }

                    // Firmware banner
                    // Information, not success / alarm: neutral surface; only the check mark keeps Ok.
                    Row(
                        Modifier.fillMaxWidth()
                            .instrumentSurface()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isFw) {
                            MonoLabel("\u2713", 13.sp, Ok, FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            MonoLabel("$fwLabel detected", 11.sp, Frost, letterSpacing = 0.2.sp)
                        } else {
                            MonoLabel("Flash openrs-fw to unlock CAN write, LC, Auto Start-Stop & more.",
                                11.sp, Mid, letterSpacing = 0.2.sp)
                        }
                    }

                    // Device info from WiCAN /check_status (WiFi only)
                    devStatus?.let { ds ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Surf2, RoundedCornerShape(8.dp))
                                .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            UIText("Adapter", 14.sp, Frost, FontWeight.SemiBold)
                            @Composable fun InfoRow(label: String, value: String) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MonoLabel(label, 10.sp, Dim)
                                    MonoLabel(value, 10.sp, Frost)
                                }
                            }
                            if (ds.hardwareVersion.isNotBlank()) InfoRow("Hardware", ds.hardwareVersion)
                            if (ds.firmwareVersion.isNotBlank()) InfoRow("Firmware", "v${ds.firmwareVersion}")
                            if (ds.deviceId.isNotBlank()) InfoRow("Device ID", ds.deviceId)
                            if (ds.canDatarate.isNotBlank()) InfoRow("CAN Bus", "${ds.canDatarate} ${ds.canMode}")
                            if (ds.obdPortVoltage > 0) InfoRow("OBD Port", "%.1fV".format(ds.obdPortVoltage))
                            if (ds.bleStatus.isNotBlank()) InfoRow("Bluetooth", ds.bleStatus)

                            // Reboot adapter button
                            Spacer(Modifier.height(4.dp))
                            var showRebootConfirm by remember { mutableStateOf(false) }
                            val ctx = LocalContext.current
                            val adapterHost = AppSettings.getHost(ctx)

                            Box(
                                Modifier.fillMaxWidth()
                                    .background(Orange.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                    .border(CardBorder, Orange.copy(0.2f), RoundedCornerShape(6.dp))
                                    .clickable { showRebootConfirm = true }
                                    .heightIn(min = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                MonoLabel("REBOOT ADAPTER", 11.sp, Orange, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                            }

                            if (showRebootConfirm) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { showRebootConfirm = false },
                                    title = { UIText("Reboot Adapter?", 14.sp, Frost, FontWeight.Bold) },
                                    text = {
                                        MonoLabel(
                                            "This will reboot the WiCAN adapter. " +
                                                "The connection will drop and auto-reconnect after the device restarts (~5 s).",
                                            10.sp, Mid
                                        )
                                    },
                                    confirmButton = {
                                        MonoLabel("REBOOT", 11.sp, Orange, FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                showRebootConfirm = false
                                                scope.launch {
                                                    WicanApi.reboot(ctx, adapterHost)
                                                    snackbarHostState.showSnackbar("Adapter rebooting…")
                                                }
                                            }.padding(12.dp))
                                    },
                                    dismissButton = {
                                        MonoLabel("CANCEL", 11.sp, Dim, FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                showRebootConfirm = false
                                            }.padding(12.dp))
                                    },
                                    containerColor = Surf2,
                                    shape = RoundedCornerShape(12.dp),
                                )
                            }
                        }
                    }

                    InstrumentMeta("Polled via extended diagnostic session (60 s cycle).")
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Paint-colour theme picker. Decoupled from UserPrefsStore so SettingsSheet
 * can stage the selection in local state and only commit on SAVE — matches
 * the "hit SAVE to apply" RESET contract.
 */
@Composable fun ThemePicker(activeId: String, onSelect: (String) -> Unit) {
    val themes = RsPaints.map { it.id to it.name }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.take(3).forEach { (id, name) ->
                ThemeChip(id, name, rsPaintAccent(id), activeId == id, onSelect, Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.drop(3).forEach { (id, name) ->
                ThemeChip(id, name, rsPaintAccent(id), activeId == id, onSelect, Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun ThemeChip(
    id: String, name: String, color: androidx.compose.ui.graphics.Color,
    isActive: Boolean, onSelect: (String) -> Unit, modifier: Modifier
) {
    // Dot shows the real paint; selection border/label use the mode-legible tone so
    // Frozen White (DAY) / Shadow Black (NIGHT) don't vanish when selected.
    val ink = legibleAccent(color, isDayModeNow())
    Column(
        modifier
            .background(if (isActive) ink.copy(alpha = 0.12f) else Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, if (isActive) ink else Brd, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelect(id) }
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(color)
                .border(if (isActive) 2.dp else 0.dp, Frost.copy(0.6f), CircleShape)
        )
        Spacer(Modifier.height(5.dp))
        MonoLabel(name, 10.sp, if (isActive) actionInk(ink) else Mid, letterSpacing = 0.1.sp,
            modifier = Modifier.fillMaxWidth(), fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
    }
}

