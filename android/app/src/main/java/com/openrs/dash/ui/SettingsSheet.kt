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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.openrs.dash.BuildConfig
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
    var screenOn        by remember { mutableStateOf(current.screenOn) }
    var autoReconnect   by remember { mutableStateOf(current.autoReconnect) }
    var reconnectSec    by remember { mutableStateOf(current.reconnectIntervalSec.toString()) }
    var maxDiagZips     by remember { mutableStateOf(current.maxDiagZips.toString()) }
    var adapterType     by remember { mutableStateOf(current.adapterType) }
    var meatPiMicroSd   by remember { mutableStateOf(current.meatPiMicroSdLog) }
    var error           by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .background(Surf, RoundedCornerShape(12.dp))
                .border(1.dp, Brd, RoundedCornerShape(12.dp))
        ) {
            // ── Title bar ────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(SurfUp, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
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
                    SettingsRow("Low Pressure Warning") {
                        OutlinedTextField(
                            value = tireLowPsi,
                            onValueChange = { tireLowPsi = it; error = null },
                        label = { Text("PSI threshold", fontFamily = ShareTechMono, fontSize = 10.sp) },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = outlinedFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost
                        )
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Tires below this value show red. Default: ${AppSettings.DEFAULT_TIRE_LOW_PSI} PSI",
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

                // ── Adapter section ───────────────────────────────────────────
                SettingsSection("ADAPTER") {
                    SettingsRow("Hardware") {
                        SegmentedPicker(
                            options  = listOf("WiCAN", "MeatPi Pro"),
                            selected = if (adapterType == "MEATPI") "MeatPi Pro" else "WiCAN",
                            onSelect = { selected ->
                                val newType = if (selected == "MeatPi Pro") "MEATPI" else "WICAN"
                                if (newType != adapterType) {
                                    // Auto-populate connection fields with the correct defaults when
                                    // switching adapters — but only if the current values still match
                                    // the OTHER adapter's defaults (preserves any custom IP/port).
                                    if (newType == "MEATPI" &&
                                        host == AppSettings.DEFAULT_HOST &&
                                        port == AppSettings.DEFAULT_PORT.toString()) {
                                        host = AppSettings.DEFAULT_HOST_MEATPI
                                        port = AppSettings.DEFAULT_PORT_MEATPI.toString()
                                    } else if (newType == "WICAN" &&
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
                    if (adapterType == "MEATPI") {
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

                // ── Connection section ────────────────────────────────────────
                val isMeatPi = adapterType == "MEATPI"
                val defaultHost = if (isMeatPi) AppSettings.DEFAULT_HOST_MEATPI else AppSettings.DEFAULT_HOST
                val defaultPort = if (isMeatPi) AppSettings.DEFAULT_PORT_MEATPI else AppSettings.DEFAULT_PORT
                SettingsSection(if (isMeatPi) "MEATPI PRO CONNECTION" else "WICAN CONNECTION") {
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
                        if (isMeatPi)
                            "Default: $defaultHost:$defaultPort  (TCP SLCAN — configure port in WiCAN Pro web UI)"
                        else
                            "Default: $defaultHost:$defaultPort",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono
                    )
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

                // Error
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = Red, fontFamily = ShareTechMono)
                }
            }

            // ── Action buttons ───────────────────────────────────────────────
            HorizontalDivider(color = Brd)
            Text(
                "openRS_ v${BuildConfig.VERSION_NAME}",
                fontSize = 10.sp,
                color = Dim,
                fontFamily = ShareTechMono,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center
            )
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
                Button(
                    onClick = {
                        val p = port.toIntOrNull()
                        val threshold = tireLowPsi.toFloatOrNull()
                        // M-10 fix: only validate the retry interval field when auto-reconnect
                        // is enabled. When disabled the field is hidden and may hold a stale
                        // string; fall back to the default so SAVE never gets permanently stuck.
                        val retryInt = if (autoReconnect) reconnectSec.toIntOrNull()
                                       else reconnectSec.toIntOrNull() ?: AppSettings.DEFAULT_RECONNECT_INTERVAL
                        val maxZips = maxDiagZips.toIntOrNull()
                        when {
                            host.isBlank() -> error = "Host cannot be empty"
                            p == null || p !in 1..65535 -> error = "Port must be 1–65535"
                            threshold == null || threshold <= 0 -> error = "Tire threshold must be > 0"
                            autoReconnect && (retryInt == null || retryInt < 1) -> error = "Retry interval must be ≥ 1 s"
                            maxZips == null || maxZips < 1 -> error = "Max ZIPs must be ≥ 1"
                            else -> {
                                AppSettings.save(ctx, host, p)
                                UserPrefsStore.update(ctx) { it.copy(
                                    speedUnit            = speedUnit,
                                    tempUnit             = tempUnit,
                                    boostUnit            = boostUnit,
                                    tireUnit             = tireUnit,
                                    tireLowPsi           = threshold,
                                    screenOn             = screenOn,
                                    autoReconnect        = autoReconnect,
                                    reconnectIntervalSec = retryInt ?: AppSettings.DEFAULT_RECONNECT_INTERVAL,
                                    maxDiagZips          = maxZips,
                                    adapterType          = adapterType,
                                    meatPiMicroSdLog     = meatPiMicroSd
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
    Column(
        Modifier.fillMaxWidth()
            .background(SurfUp, RoundedCornerShape(8.dp))
            .border(1.dp, Brd, RoundedCornerShape(8.dp))
            .padding(14.dp)
    ) {
        Text(title, fontSize = 9.sp, color = Dim, letterSpacing = 1.5.sp, fontFamily = ShareTechMono)
        Spacer(Modifier.height(12.dp))
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
    Row(
        Modifier
            .background(Brd, RoundedCornerShape(6.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .background(
                        if (isSelected) pickerAccent else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onSelect(option) }
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
