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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                    // Ford's names (Normal / Sport / Off); the row is already labelled ESC
                    listOf(EscStatus.ON to "NORMAL", EscStatus.PARTIAL to "SPORT", EscStatus.OFF to "OFF")
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
                                    .pressClick(enabled = escChipEnabled(vs.escStatus, status, canControl, firmwareApi != null)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                        pendingEsc = status
                                        scope.launch {
                                            DiagnosticLogger.event("ESC_CMD",
                                                "Sending escMode=${status.firmwareMode} (${status.diagnostic})")
                                            // Acceptance by the adapter isn't success — wait for CAN 0x1C0 (#125)
                                            val outcome = com.openrs.dash.can.EscCommand.execute(firmwareApi, status,
                                                { com.openrs.dash.OpenRSDashApp.instance.vehicleState.value })
                                            DiagnosticLogger.event("ESC_CMD", com.openrs.dash.can.EscCommand.describe(status, outcome))
                                            pendingEsc = null                     // before the message, which suspends until it ends
                                            when (outcome) {
                                                is com.openrs.dash.can.EscCommand.Outcome.Confirmed -> Unit
                                                is com.openrs.dash.can.EscCommand.Outcome.NotConfirmed -> snackbarHostState.showSnackbar(
                                                    if (outcome.observed == status) "Adapter accepted ESC ${status.label}. The car reports it, but not on two frames since the request"
                                                    else "Adapter accepted ESC ${status.label}, but the car reports ESC ${outcome.observed.label}")
                                                com.openrs.dash.can.EscCommand.Outcome.Refused -> snackbarHostState.showSnackbar("ESC state unknown — nothing sent")
                                                com.openrs.dash.can.EscCommand.Outcome.Busy -> snackbarHostState.showSnackbar("An ESC change is already in progress — try again")
                                                com.openrs.dash.can.EscCommand.Outcome.ConnectionLost -> snackbarHostState.showSnackbar("Connection lost — ESC not confirmed")
                                                else -> snackbarHostState.showSnackbar("ESC command failed")
                                            }
                                        }
                                    }
                                    .heightIn(min = 48.dp)
                                    // What TalkBack says names the setting, as the header does: "Stability control: Sport"
                                    .semantics { contentDescription = "Stability control: ${status.label}"; selected = isActive },
                                contentAlignment = Alignment.Center
                            ) {
                                MonoLabel(
                                    if (isPending) "..." else label,
                                    11.sp, if (isActive) textColor(if (color == accent) actionInk(accent) else color) else Mid,
                                    if (isActive) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.3.sp,
                                    modifier = Modifier.clearAndSetSemantics { }
                                )
                            }
                        }
                }
                InstrumentMeta(
                    when {
                        // Owner decision: no ESC request while the car's ESC state is unknown (no 0x1C0 yet, or raw 3)
                        !vs.escStatus.isKnown -> "Waiting for the car's ESC report (CAN 0x1C0) — buttons off until it arrives"
                        canControl            -> "Tap to change · Live from CAN 0x1C0"
                        else                  -> "Current: ESC ${vs.escStatus.label} (CAN 0x1C0). Use ESC button in car."
                    }
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
                    // Design pass: one FEATURES panel (was five cards). States read neutral — OFF / PROBING were amber, which
                    // signals a problem; ON takes an accent dot.
                    // #289: Launch Control shows only the cluster's "LC" (fresh 0x420 bit 2, LaunchControl) or "—". The 0x731 probe
                    // DIDs (DE00 armed, DE01 setpoint, DE02 Auto Start-Stop) are undocumented, at the keyless module's address:
                    // diagnostics only, so Auto Start-Stop reads "—" (no other source) while the probe still runs.
                    data class Feature(val name: String, val code: String?, val glyph: String, val state: String, val color: Color)
                    val lcShown = com.openrs.dash.data.LaunchControl.shown(vs, com.openrs.dash.ui.wallClockMs(500L))
                    val lcState = if (lcShown) Triple("\u25CF", "LC", actionInk(LocalThemeAccent.current))
                                  else Triple("", "\u2014", Dim)
                    val features = buildList {
                        add(Feature("Launch Control", null, lcState.first, lcState.second, lcState.third))
                        add(Feature("Auto Start-Stop", null, "", "\u2014", Dim))
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

/**
 * An ESC chip can be tapped only to change a state the car reported: never while the current state is unknown (no 0x1C0
 * frame yet, or raw 3 — owner decision, REQ-rc8-esc-owner-wording), never for the active state, and only with a command path.
 */
internal fun escChipEnabled(current: EscStatus, chip: EscStatus, canControl: Boolean, hasCommandPath: Boolean): Boolean =
    canControl && hasCommandPath && current.isKnown && chip.isKnown && current != chip
