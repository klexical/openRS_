package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import android.content.pm.PackageManager
import com.openrs.dash.BuildConfig
import com.openrs.dash.service.HudOverlayService
import com.openrs.dash.update.UpdateManager
import com.openrs.dash.update.UpdateState
import kotlinx.coroutines.launch
import com.openrs.dash.ui.Tokens.CardBorder
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current
    val current by UserPrefsStore.prefs.collectAsState()

    // Local mutable state — only committed on SAVE
    var host            by remember { mutableStateOf(AppSettings.getHost(ctx)) }
    var port            by remember { mutableStateOf(AppSettings.getPort(ctx).toString()) }
    var speedUnit       by remember { mutableStateOf(current.speedUnit) }
    var tempUnit        by remember { mutableStateOf(current.tempUnit) }
    var boostUnit       by remember { mutableStateOf(current.boostUnit) }
    var tireUnit        by remember { mutableStateOf(current.tireUnit) }
    var tireLowPsi      by remember { mutableStateOf(current.tireLowPsi.toString()) }
    var tireWarnPsi     by remember { mutableStateOf(current.tireWarnPsi.toString()) }
    var tireHighPsi     by remember { mutableStateOf(current.tireHighPsi.toString()) }
    var screenOn        by remember { mutableStateOf(current.screenOn) }
    var autoReconnect   by remember { mutableStateOf(current.autoReconnect) }
    var reconnectSec    by remember { mutableStateOf(current.reconnectIntervalSec.toString()) }
    var maxDiagZips     by remember { mutableStateOf(current.maxDiagZips.toString()) }
    var autoRecordDrives by remember { mutableStateOf(current.autoRecordDrives) }
    var maxSavedDrives  by remember { mutableStateOf(current.maxSavedDrives.toString()) }
    var adapterType     by remember { mutableStateOf(current.adapterType) }
    var connectionMethod by remember { mutableStateOf(current.connectionMethod) }
    var meatPiMicroSd   by remember { mutableStateOf(current.meatPiMicroSdLog) }
    var edgeShiftLight  by remember { mutableStateOf(current.edgeShiftLight) }
    var edgeShiftColor  by remember { mutableStateOf(current.edgeShiftColor) }
    var edgeShiftIntensity by remember { mutableStateOf(current.edgeShiftIntensity) }
    var edgeShiftRpm    by remember { mutableStateOf(current.edgeShiftRpm.toString()) }
    var updateChannel   by remember { mutableStateOf(current.updateChannel) }
    var error           by remember { mutableStateOf<String?>(null) }
    var resetConfirm    by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .background(Bg, RoundedCornerShape(12.dp))
                .border(CardBorder, Brd, RoundedCornerShape(12.dp))
        ) {
            // ── Title bar ────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(Surf3, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("open", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = ShareTechMono, color = Frost)
                    Text("RS", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = ShareTechMono, color = accent)
                    Text("_ Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = ShareTechMono, color = Frost)
                }
                Text("✕", fontSize = 18.sp, color = Dim, modifier = Modifier.clickable { onDismiss() })
            }

            // ── Scrollable body ──────────────────────────────────────────────
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {

                // ── Units section ─────────────────────────────────────────────
                SettingsSection("UNITS") {
                    SettingsRow("Speed") {
                        SegmentedPicker(
                            options = listOf("MPH", "KPH"),
                            selected = speedUnit,
                            onSelect = { speedUnit = it }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SettingsRow("Temperature") {
                        SegmentedPicker(
                            options = listOf("°F", "°C"),
                            selected = if (tempUnit == "F") "°F" else "°C",
                            onSelect = { tempUnit = if (it == "°F") "F" else "C" }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SettingsRow("Boost Pressure") {
                        SegmentedPicker(
                            options = listOf("PSI", "BAR", "kPa"),
                            selected = when (boostUnit) { "BAR" -> "BAR"; "KPA" -> "kPa"; else -> "PSI" },
                            onSelect = { boostUnit = when (it) { "BAR" -> "BAR"; "kPa" -> "KPA"; else -> "PSI" } }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SettingsRow("Tire Pressure") {
                        SegmentedPicker(
                            options = listOf("PSI", "BAR"),
                            selected = tireUnit,
                            onSelect = { tireUnit = it }
                        )
                    }
                }

                // ── TPMS section ──────────────────────────────────────────────
                SettingsSection("TPMS") {
                    SettingsRow("Low (critical)") {
                        OutlinedTextField(
                            value = tireLowPsi,
                            onValueChange = { tireLowPsi = it; error = null },
                            label = { Text("PSI", fontFamily = ShareTechMono, fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(90.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = outlinedFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost
                            )
                        )
                    }
                    SettingsRow("Warn (getting low)") {
                        OutlinedTextField(
                            value = tireWarnPsi,
                            onValueChange = { tireWarnPsi = it; error = null },
                            label = { Text("PSI", fontFamily = ShareTechMono, fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(90.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = outlinedFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost
                            )
                        )
                    }
                    SettingsRow("High (over-inflated)") {
                        OutlinedTextField(
                            value = tireHighPsi,
                            onValueChange = { tireHighPsi = it; error = null },
                            label = { Text("PSI", fontFamily = ShareTechMono, fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(90.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = outlinedFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost
                            )
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Red < ${AppSettings.DEFAULT_TIRE_LOW_PSI} | Gold < ${AppSettings.DEFAULT_TIRE_WARN_PSI} | Green | Red > ${AppSettings.DEFAULT_TIRE_HIGH_PSI} PSI",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono)
                }

                // ── Display section ───────────────────────────────────────────
                SettingsSection("DISPLAY") {
                    SettingsSwitchRow(
                        label = "Keep screen on while connected",
                        checked = screenOn,
                        onCheckedChange = { screenOn = it }
                    )
                }

                // ── Shift light section ──────────────────────────────────────
                SettingsSection("SHIFT LIGHT") {
                    SettingsSwitchRow(
                        label = "Peripheral edge glow",
                        checked = edgeShiftLight,
                        onCheckedChange = { edgeShiftLight = it }
                    )
                    if (edgeShiftLight) {
                        Spacer(Modifier.height(12.dp))
                        SettingsRow("Color") {
                            SegmentedPicker(
                                options = listOf("Accent", "White", "Progressive"),
                                selected = when (edgeShiftColor) {
                                    "white" -> "White"
                                    "progressive" -> "Progressive"
                                    else -> "Accent"
                                },
                                onSelect = {
                                    edgeShiftColor = when (it) {
                                        "White" -> "white"
                                        "Progressive" -> "progressive"
                                        else -> "accent"
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingsRow("Intensity") {
                            SegmentedPicker(
                                options = listOf("Low", "Med", "High"),
                                selected = when (edgeShiftIntensity) {
                                    "low" -> "Low"
                                    "med" -> "Med"
                                    else -> "High"
                                },
                                onSelect = {
                                    edgeShiftIntensity = when (it) {
                                        "Low" -> "low"
                                        "Med" -> "med"
                                        else -> "high"
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingsRow("Shift RPM") {
                            OutlinedTextField(
                                value = edgeShiftRpm,
                                onValueChange = { edgeShiftRpm = it; error = null },
                                label = { Text("RPM", fontFamily = ShareTechMono, fontSize = 10.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(90.dp),
                                colors = outlinedFieldColors(),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = ShareTechMono, fontSize = 13.sp, color = Frost
                                )
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Screen edges glow as RPM approaches shift point.",
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                        )
                    }
                }

                // ── Theme section ────────────────────────────────────────────
                SettingsSection("THEME — RS PAINT COLOUR") {
                    ThemePicker(current)
                }

                // ── Brightness / Visibility section ─────────────────────────
                SettingsSection("VISIBILITY") {
                    val presetName = when {
                        current.brightness <= 0.01f -> "NIGHT"
                        (current.brightness - 0.5f).let { it > -0.01f && it < 0.01f } -> "DAY"
                        current.brightness >= 0.99f -> "SUN"
                        else -> ""
                    }
                    SettingsRow("Preset") {
                        SegmentedPicker(
                            options = listOf("NIGHT", "DAY", "SUN"),
                            selected = presetName,
                            onSelect = { sel ->
                                val v = when (sel) {
                                    "DAY" -> 0.5f
                                    "SUN" -> 1.0f
                                    else  -> 0f
                                }
                                UserPrefsStore.update(ctx) { it.copy(brightness = v) }
                                setBrightness(v)
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Fine-tune",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = current.brightness,
                        onValueChange = { v ->
                            UserPrefsStore.update(ctx) { it.copy(brightness = v) }
                            setBrightness(v)
                        },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = LocalThemeAccent.current,
                            activeTrackColor = LocalThemeAccent.current,
                            inactiveTrackColor = Brd
                        )
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Dark", fontSize = 9.sp, color = Dim, fontFamily = ShareTechMono)
                        Text("Bright", fontSize = 9.sp, color = Dim, fontFamily = ShareTechMono)
                    }
                }

                // ── Floating HUD section ─────────────────────────────────────
                SettingsSection("FLOATING HUD") {
                    val hasOverlayPerm = Settings.canDrawOverlays(ctx)
                    if (!hasOverlayPerm) {
                        Text(
                            "Overlay permission required to display the floating HUD over other apps.",
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.fillMaxWidth()
                                .background(LocalThemeAccent.current.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .border(CardBorder, LocalThemeAccent.current.copy(0.3f), RoundedCornerShape(8.dp))
                                .clickable {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${ctx.packageName}"))
                                    )
                                }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("GRANT OVERLAY PERMISSION", fontSize = 11.sp, color = LocalThemeAccent.current,
                                fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            "Show a compact boost / RPM / oil overlay on top of other apps. Useful for track days with a nav app.",
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.weight(1f)
                                    .background(Ok.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                    .border(CardBorder, Ok.copy(0.3f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        ctx.startService(Intent(ctx, HudOverlayService::class.java))
                                    }
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("START HUD", fontSize = 11.sp, color = Ok,
                                    fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                Modifier.weight(1f)
                                    .background(Orange.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                    .border(CardBorder, Orange.copy(0.3f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        ctx.stopService(Intent(ctx, HudOverlayService::class.java))
                                    }
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("STOP HUD", fontSize = 11.sp, color = Orange,
                                    fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // ── Adapter section ───────────────────────────────────────────
                var showBlePicker by remember { mutableStateOf(false) }
                SettingsSection("ADAPTER") {
                    SettingsRow("Hardware") {
                        SegmentedPicker(
                            options  = listOf("MeatPi USB (C3)", "MeatPi Pro (S3)"),
                            selected = if (adapterType == "MEATPI_PRO") "MeatPi Pro (S3)" else "MeatPi USB (C3)",
                            onSelect = { selected ->
                                val newType = if (selected == "MeatPi Pro (S3)") "MEATPI_PRO" else "MEATPI_USB"
                                if (newType != adapterType) {
                                    // Auto-populate connection fields with the correct defaults when
                                    // switching adapters (preserves any custom IP/port).
                                    if (newType == "MEATPI_PRO" &&
                                        host == AppSettings.DEFAULT_HOST &&
                                        port == AppSettings.DEFAULT_PORT.toString()) {
                                        host = AppSettings.DEFAULT_HOST_MEATPI
                                        port = AppSettings.DEFAULT_PORT_MEATPI.toString()
                                    } else if (newType == "MEATPI_USB" &&
                                        host == AppSettings.DEFAULT_HOST_MEATPI &&
                                        port == AppSettings.DEFAULT_PORT_MEATPI.toString()) {
                                        host = AppSettings.DEFAULT_HOST
                                        port = AppSettings.DEFAULT_PORT.toString()
                                    }
                                    adapterType = newType
                                }
                            }
                        )
                    }
                    if (adapterType == "MEATPI_PRO") {
                        Spacer(Modifier.height(12.dp))
                        SettingsSwitchRow(
                            label          = "MicroSD logging reminder",
                            checked        = meatPiMicroSd,
                            onCheckedChange = { meatPiMicroSd = it }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "SD logging is configured in the WiCAN Pro web UI at http://192.168.0.10/ — " +
                            "enable it there under the SD card section. This toggle is a local reminder only.",
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                        )
                    }
                }

                // ── Connection method section ────────────────────────────────────
                SettingsSection("CONNECTION") {
                    SettingsRow("Method") {
                        SegmentedPicker(
                            options  = listOf("WiFi", "Bluetooth"),
                            selected = if (connectionMethod == "BLUETOOTH") "Bluetooth" else "WiFi",
                            onSelect = { selected ->
                                connectionMethod = if (selected == "Bluetooth") "BLUETOOTH" else "WIFI"
                            }
                        )
                    }
                }

                if (connectionMethod == "BLUETOOTH") {
                    // ── Bluetooth device section ─────────────────────────────────
                    val bleAddr = remember { mutableStateOf(AppSettings.getBleDeviceAddress(ctx)) }
                    val bleName = remember { mutableStateOf(AppSettings.getBleDeviceName(ctx)) }

                    SettingsSection("BLUETOOTH DEVICE") {
                        if (bleAddr.value != null) {
                            // Device saved — show info
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(Surf2, RoundedCornerShape(10.dp))
                                    .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Device", fontSize = 9.sp, color = Dim,
                                        fontFamily = ShareTechMono, letterSpacing = 0.1.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(bleName.value ?: "WiCAN", fontSize = 13.sp, color = Frost,
                                        fontFamily = ShareTechMono, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(2.dp))
                                    Text(bleAddr.value ?: "", fontSize = 10.sp, color = Dim,
                                        fontFamily = ShareTechMono, letterSpacing = 0.05.sp)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        } else {
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(Surf2, RoundedCornerShape(10.dp))
                                    .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("No device paired", fontSize = 11.sp, color = Dim,
                                    fontFamily = ShareTechMono)
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.weight(1f)
                                    .background(accent.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                    .border(CardBorder, accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .clickable { showBlePicker = true }
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("SCAN FOR DEVICES", fontSize = 10.sp, color = accent,
                                    fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.1.sp)
                            }
                            if (bleAddr.value != null) {
                                Box(
                                    Modifier.weight(1f)
                                        .background(Orange.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                        .border(CardBorder, Orange.copy(0.3f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            AppSettings.clearBleDevice(ctx)
                                            bleAddr.value = null
                                            bleName.value = null
                                        }
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("FORGET", fontSize = 10.sp, color = Orange,
                                        fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.1.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "For best results, forget the adapter's WiFi network in your phone's WiFi " +
                            "settings to keep internet available while connected via Bluetooth.",
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                        )
                    }

                    if (showBlePicker) {
                        BleDevicePickerDialog(
                            onDeviceSelected = { address, name ->
                                AppSettings.saveBleDevice(ctx, address, name)
                                bleAddr.value = address
                                bleName.value = name
                                showBlePicker = false
                            },
                            onDismiss = { showBlePicker = false }
                        )
                    }
                } else {
                    // ── WiFi Connection section ──────────────────────────────────
                    val isPro = adapterType == "MEATPI_PRO"
                    val defaultHost = if (isPro) AppSettings.DEFAULT_HOST_MEATPI else AppSettings.DEFAULT_HOST
                    val defaultPort = if (isPro) AppSettings.DEFAULT_PORT_MEATPI else AppSettings.DEFAULT_PORT
                    val adapterLabel = if (isPro) "MEATPI PRO" else "MEATPI USB"
                    SettingsSection("$adapterLabel — WIFI") {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it; error = null },
                            label = { Text("Host / IP Address", fontFamily = ShareTechMono, fontSize = 11.sp) },
                            placeholder = { Text(defaultHost, fontFamily = ShareTechMono, fontSize = 12.sp, color = Dim) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = outlinedFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it; error = null },
                            label = { Text("Port", fontFamily = ShareTechMono, fontSize = 11.sp) },
                            placeholder = { Text(defaultPort.toString(), fontFamily = ShareTechMono, fontSize = 12.sp, color = Dim) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = outlinedFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isPro)
                                "Default: $defaultHost:$defaultPort  (TCP SLCAN — configure port in WiCAN Pro web UI)"
                            else
                                "Default: $defaultHost:$defaultPort  (WebSocket SLCAN)",
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                        )
                    }
                }

                // ── Auto-reconnect section ────────────────────────────────────
                SettingsSection("AUTO-RECONNECT") {
                    SettingsSwitchRow(
                        label = "Auto-reconnect on disconnect",
                        checked = autoReconnect,
                        onCheckedChange = { autoReconnect = it }
                    )
                    if (autoReconnect) {
                        Spacer(Modifier.height(12.dp))
                        SettingsRow("Retry interval") {
                            OutlinedTextField(
                                value = reconnectSec,
                                onValueChange = { reconnectSec = it; error = null },
                                label = { Text("seconds", fontFamily = ShareTechMono, fontSize = 10.sp) },
                                singleLine = true,
                                modifier = Modifier.width(100.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = outlinedFieldColors(),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost
                                )
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("How long to wait between connection attempts. Default: ${AppSettings.DEFAULT_RECONNECT_INTERVAL}s",
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono)
                    }
                }

                // ── Drives section ────────────────────────────────────────────
                SettingsSection("DRIVES") {
                    SettingsRow("Auto-record drives") {
                        Switch(
                            checked = autoRecordDrives,
                            onCheckedChange = { autoRecordDrives = it; error = null },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accent,
                                checkedTrackColor = accent.copy(alpha = 0.3f)
                            )
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("Automatically start recording when connected to your car",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono)

                    Spacer(Modifier.height(10.dp))
                    SettingsRow("Max saved drives") {
                        OutlinedTextField(
                            value = maxSavedDrives,
                            onValueChange = { maxSavedDrives = it; error = null },
                            label = { Text("count", fontFamily = ShareTechMono, fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(90.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = outlinedFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost
                            )
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Oldest drives are removed when this limit is exceeded. Default: ${AppSettings.DEFAULT_MAX_SAVED_DRIVES}",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono)
                }

                // ── Diagnostics section ───────────────────────────────────────
                SettingsSection("DIAGNOSTICS") {
                    SettingsRow("Max saved ZIP exports") {
                        OutlinedTextField(
                            value = maxDiagZips,
                            onValueChange = { maxDiagZips = it; error = null },
                            label = { Text("count", fontFamily = ShareTechMono, fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(90.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = outlinedFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost
                            )
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Oldest ZIPs are removed when this limit is exceeded. Default: ${AppSettings.DEFAULT_MAX_DIAG_ZIPS}",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono)
                }

                // ── App Updates section ───────────────────────────────────────
                AppUpdatesSection(
                    updateChannel = updateChannel,
                    onChannelChange = { updateChannel = it }
                )

                // ── What's New ────────────────────────────────────────────────
                var showWhatsNewLocal by remember { mutableStateOf(false) }
                Box(
                    Modifier.fillMaxWidth()
                        .background(Surf2, RoundedCornerShape(8.dp))
                        .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                        .clickable { showWhatsNewLocal = true }
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("WHAT'S NEW IN v${BuildConfig.VERSION_NAME}", fontSize = 11.sp,
                        color = accent, fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.1.sp)
                }
                if (showWhatsNewLocal) {
                    WhatsNewDialog(onDismiss = { showWhatsNewLocal = false })
                }

                // Error
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = Orange, fontFamily = ShareTechMono)
                }
            }

            // ── Action buttons ───────────────────────────────────────────────
            HorizontalDivider(color = Brd)
            val versionLabel = buildString {
                append("openRS_ v${BuildConfig.VERSION_NAME}")
                if (BuildConfig.RC_SUFFIX.isNotEmpty()) append("-${BuildConfig.RC_SUFFIX}")
            }
            Text(
                versionLabel,
                fontSize = 10.sp,
                color = Dim,
                fontFamily = ShareTechMono,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center
            )
            if (resetConfirm) {
                Text(
                    "Defaults restored — tap SAVE to apply",
                    fontSize = 10.sp,
                    color = Ok,
                    fontFamily = ShareTechMono,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    textAlign = TextAlign.Center
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Dim)
                ) {
                    Text("CANCEL", fontFamily = ShareTechMono, fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        host          = AppSettings.DEFAULT_HOST
                        port          = AppSettings.DEFAULT_PORT.toString()
                        speedUnit     = AppSettings.DEFAULT_SPEED_UNIT
                        tempUnit      = AppSettings.DEFAULT_TEMP_UNIT
                        boostUnit     = AppSettings.DEFAULT_BOOST_UNIT
                        tireUnit      = AppSettings.DEFAULT_TIRE_UNIT
                        tireLowPsi    = AppSettings.DEFAULT_TIRE_LOW_PSI.toString()
                        screenOn      = AppSettings.DEFAULT_SCREEN_ON
                        autoReconnect = AppSettings.DEFAULT_AUTO_RECONNECT
                        reconnectSec  = AppSettings.DEFAULT_RECONNECT_INTERVAL.toString()
                        maxDiagZips   = AppSettings.DEFAULT_MAX_DIAG_ZIPS.toString()
                        autoRecordDrives = AppSettings.DEFAULT_AUTO_RECORD_DRIVES
                        maxSavedDrives = AppSettings.DEFAULT_MAX_SAVED_DRIVES.toString()
                        adapterType   = AppSettings.DEFAULT_ADAPTER_TYPE
                        connectionMethod = AppSettings.DEFAULT_CONNECTION_METHOD
                        meatPiMicroSd = AppSettings.DEFAULT_MEATPI_MICROSD
                        edgeShiftLight    = AppSettings.DEFAULT_EDGE_SHIFT_LIGHT
                        edgeShiftColor    = AppSettings.DEFAULT_EDGE_SHIFT_COLOR
                        edgeShiftIntensity = AppSettings.DEFAULT_EDGE_SHIFT_INTENSITY
                        edgeShiftRpm      = AppSettings.DEFAULT_EDGE_SHIFT_RPM.toString()
                        updateChannel = AppSettings.DEFAULT_UPDATE_CHANNEL
                        error         = null
                        resetConfirm  = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(contentColor = Dim)
                ) {
                    Text("RESET", fontFamily = ShareTechMono, fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val p = port.toIntOrNull()
                        val threshold = tireLowPsi.toFloatOrNull()
                        val warnThr   = tireWarnPsi.toFloatOrNull()
                        val highThr   = tireHighPsi.toFloatOrNull()
                        // M-10 fix: only validate the retry interval field when auto-reconnect
                        // is enabled. When disabled the field is hidden and may hold a stale
                        // string; fall back to the default so SAVE never gets permanently stuck.
                        val retryInt = if (autoReconnect) reconnectSec.toIntOrNull()
                                       else reconnectSec.toIntOrNull() ?: AppSettings.DEFAULT_RECONNECT_INTERVAL
                        val maxZips = maxDiagZips.toIntOrNull()
                        val maxDrives = maxSavedDrives.toIntOrNull()
                        val shiftRpm = edgeShiftRpm.toIntOrNull()
                        when {
                            host.isBlank() -> error = "Host cannot be empty"
                            p == null || p !in 1..65535 -> error = "Port must be 1–65535"
                            threshold == null || threshold <= 0 -> error = "Low threshold must be > 0"
                            warnThr == null || warnThr <= threshold -> error = "Warn must be > Low"
                            highThr == null || highThr <= warnThr -> error = "High must be > Warn"
                            autoReconnect && (retryInt == null || retryInt < 1) -> error = "Retry interval must be ≥ 1 s"
                            maxZips == null || maxZips < 1 -> error = "Max ZIPs must be ≥ 1"
                            maxDrives == null || maxDrives < 1 -> error = "Max drives must be ≥ 1"
                            edgeShiftLight && (shiftRpm == null || shiftRpm !in 1000..9000) -> error = "Shift RPM must be 1000–9000"
                            else -> {
                                AppSettings.save(ctx, host, p)
                                UserPrefsStore.update(ctx) { it.copy(
                                    speedUnit            = speedUnit,
                                    tempUnit             = tempUnit,
                                    boostUnit            = boostUnit,
                                    tireUnit             = tireUnit,
                                    tireLowPsi           = threshold,
                                    tireWarnPsi          = warnThr,
                                    tireHighPsi          = highThr,
                                    screenOn             = screenOn,
                                    autoReconnect        = autoReconnect,
                                    reconnectIntervalSec = retryInt ?: AppSettings.DEFAULT_RECONNECT_INTERVAL,
                                    maxDiagZips          = maxZips,
                                    adapterType          = adapterType,
                                    connectionMethod     = connectionMethod,
                                    meatPiMicroSdLog     = meatPiMicroSd,
                                    edgeShiftLight       = edgeShiftLight,
                                    edgeShiftColor       = edgeShiftColor,
                                    edgeShiftIntensity   = edgeShiftIntensity,
                                    edgeShiftRpm         = shiftRpm ?: AppSettings.DEFAULT_EDGE_SHIFT_RPM,
                                    autoRecordDrives     = autoRecordDrives,
                                    maxSavedDrives       = maxDrives ?: AppSettings.DEFAULT_MAX_SAVED_DRIVES,
                                    updateChannel        = updateChannel
                                )}
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = OnAccent)
                ) {
                    Text("SAVE", fontFamily = ShareTechMono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Reusable settings components ─────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val accent = LocalThemeAccent.current
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Surf2, Surf.copy(alpha = 0.5f))),
                RoundedCornerShape(10.dp)
            )
            .border(Tokens.CardBorder, Brd, RoundedCornerShape(10.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(3.dp).height(14.dp)
                    .background(accent, RoundedCornerShape(1.5.dp))
            )
            Spacer(Modifier.width(8.dp))
            MonoLabel(title, 9.sp, accent, letterSpacing = 1.5.sp)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor  = OnAccent,
                checkedTrackColor  = LocalThemeAccent.current,
                uncheckedThumbColor = Dim,
                uncheckedTrackColor = Brd
            )
        )
    }
}

@Composable
fun SegmentedPicker(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val pickerAccent = LocalThemeAccent.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .background(Brd, RoundedCornerShape(6.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) pickerAccent else Color.Transparent,
                animationSpec = tween(250), label = "segBg"
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) pickerAccent.copy(alpha = 0.6f) else Color.Transparent,
                animationSpec = tween(250), label = "segBrd"
            )
            Box(
                Modifier
                    .background(bgColor, RoundedCornerShape(4.dp))
                    .border(CardBorder, borderColor, RoundedCornerShape(4.dp))
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = ShareTechMono,
                    color = if (isSelected) OnAccent else Dim
                )
            }
        }
    }
}

// ── App Updates section ──────────────────────────────────────────────────────

@Composable
private fun AppUpdatesSection(
    updateChannel: String,
    onChannelChange: (String) -> Unit
) {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current
    val scope = rememberCoroutineScope()
    val updateState by UpdateManager.state.collectAsState()

    SettingsSection("APP UPDATES") {
        // ── Channel picker ──────────────────────────────────────────────
        SettingsRow("Update channel") {
            SegmentedPicker(
                options = listOf("stable", "beta"),
                selected = updateChannel,
                onSelect = onChannelChange
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            if (updateChannel == "beta") "Includes pre-release (RC) builds from GitHub"
            else "Only stable releases from GitHub",
            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
        )

        Spacer(Modifier.height(14.dp))

        // ── Check for updates button ────────────────────────────────────
        Box(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(8.dp))
                .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                .clickable {
                    scope.launch {
                        UpdateManager.checkForUpdate(ctx, updateChannel)
                    }
                }
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("CHECK FOR UPDATES", fontSize = 11.sp,
                color = accent, fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.sp)
        }

        Spacer(Modifier.height(10.dp))

        // ── Status display ──────────────────────────────────────────────
        when (val state = updateState) {
            is UpdateState.Idle -> {
                // Nothing to show
            }

            is UpdateState.Checking -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = accent,
                        strokeWidth = 2.dp
                    )
                    Text("Checking for updates...", fontSize = 11.sp, color = Dim, fontFamily = ShareTechMono)
                }
            }

            is UpdateState.Available -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "v${state.version.displayName} available" +
                            if (state.isPrerelease) "  (pre-release)" else "",
                        fontSize = 12.sp, color = Ok, fontFamily = ShareTechMono,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.fileSizeBytes > 0) {
                        Text(
                            "%.1f MB".format(state.fileSizeBytes / 1_048_576.0),
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                        )
                    }
                    if (state.releaseNotes.isNotEmpty()) {
                        Text(
                            state.releaseNotes.take(300) +
                                if (state.releaseNotes.length > 300) "..." else "",
                            fontSize = 10.sp, color = Mid, fontFamily = ShareTechMono,
                            lineHeight = 14.sp
                        )
                    }

                    // Check install permission before showing download button
                    val canInstall = ctx.packageManager.canRequestPackageInstalls()
                    if (!canInstall) {
                        Text(
                            "Allow app installs from openRS_ in system settings to continue",
                            fontSize = 10.sp, color = Orange, fontFamily = ShareTechMono
                        )
                        Box(
                            Modifier.fillMaxWidth()
                                .background(Orange.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .border(CardBorder, Orange.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${ctx.packageName}")
                                    )
                                    ctx.startActivity(intent)
                                }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("OPEN INSTALL SETTINGS", fontSize = 11.sp,
                                color = Orange, fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth()
                                .background(accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .border(CardBorder, accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        UpdateManager.downloadUpdate(ctx, state.downloadUrl)
                                    }
                                }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("DOWNLOAD", fontSize = 11.sp,
                                color = accent, fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            is UpdateState.Downloading -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.progress >= 0) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = accent,
                            trackColor = Brd
                        )
                        Text(
                            "Downloading... ${(state.progress * 100).toInt()}%  " +
                                "(%.1f / %.1f MB)".format(
                                    state.bytesDownloaded / 1_048_576.0,
                                    state.totalBytes / 1_048_576.0
                                ),
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = accent,
                            trackColor = Brd
                        )
                        Text(
                            "Downloading... %.1f MB".format(state.bytesDownloaded / 1_048_576.0),
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                        )
                    }
                }
            }

            is UpdateState.ReadyToInstall -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Download complete", fontSize = 11.sp, color = Ok, fontFamily = ShareTechMono)
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Ok.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .border(CardBorder, Ok.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .clickable { UpdateManager.installApk(ctx, state.apkFile) }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("INSTALL", fontSize = 11.sp,
                            color = Ok, fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                    }
                }
            }

            is UpdateState.Error -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.message, fontSize = 11.sp, color = Orange, fontFamily = ShareTechMono)
                    Text("Tap 'Check for updates' to retry",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono)
                }
            }
        }
    }
}

@Composable
private fun outlinedFieldColors(): TextFieldColors {
    val a = LocalThemeAccent.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor    = a,
        unfocusedBorderColor  = Brd,
        focusedLabelColor     = a,
        unfocusedLabelColor   = Dim,
        cursorColor           = a,
        focusedTextColor      = Frost,
        unfocusedTextColor    = Frost,
    )
}
