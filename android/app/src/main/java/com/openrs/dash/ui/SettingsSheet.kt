package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.Brush
import com.openrs.dash.BuildConfig
import com.openrs.dash.can.WicanDiscovery
import com.openrs.dash.service.HudOverlayService
import com.openrs.dash.update.UpdateManager
import com.openrs.dash.update.UpdateState
import kotlinx.coroutines.launch
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.anim.pageEntrance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ═══════════════════════════════════════════════════════════════════════════
// SETTINGS — v2.2.7 overhaul
//
// Categorized navigation (root grid → sub-page) with live search across all
// settings. Replaces the flat 14-section scroll. The cog in AppHeader and
// GARAGE's SETTINGS entry both open this same dialog.
// ═══════════════════════════════════════════════════════════════════════════

private enum class SettingsCategory(val label: String, val blurb: String) {
    DISPLAY("DISPLAY", "Theme · shift light · overlay"),
    UNITS("UNITS", "Speed · temp · pressure"),
    CONNECTION("CONNECTION", "Adapter · WiFi · Bluetooth"),
    DRIVES("DRIVES", "Auto-record · storage"),
    DIAGNOSTICS("DIAGNOSTICS", "Exports · logging"),
    ABOUT("ABOUT", "Version · credits"),
}

private class NavSection(
    val category: SettingsCategory,
    val title: String,
    val keywords: List<String>,
    val body: @Composable () -> Unit,
)

/** Case-insensitive substring; falls back to subsequence fuzzy match. */
private fun fuzzyMatch(query: String, text: String): Boolean {
    val q = query.lowercase().trim()
    if (q.isEmpty()) return true
    val t = text.lowercase()
    if (t.contains(q)) return true
    var idx = 0
    for (c in t) {
        if (idx < q.length && c == q[idx]) idx++
        if (idx == q.length) return true
    }
    return false
}

/**
 * Rank a single text field against a query:
 *   0 = exact match, 1 = prefix, 2 = substring, 3 = subsequence, 4 = no match.
 * Lower is better. Used to order search results so substring beats subsequence.
 */
private fun matchRank(query: String, text: String): Int {
    val q = query.lowercase().trim()
    if (q.isEmpty()) return 0
    val t = text.lowercase()
    if (t == q) return 0
    if (t.startsWith(q)) return 1
    if (t.contains(q)) return 2
    var idx = 0
    for (c in t) {
        if (idx < q.length && c == q[idx]) idx++
        if (idx == q.length) return 3
    }
    return 4
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit, onCustomDash: () -> Unit = {}) {
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
    var autoScanDtcs    by remember { mutableStateOf(current.autoScanDtcs) }
    var maxSavedDrives  by remember { mutableStateOf(current.maxSavedDrives.toString()) }
    var adapterType     by remember { mutableStateOf(current.adapterType) }
    var connectionMethod by remember { mutableStateOf(current.connectionMethod) }
    var meatPiMicroSd   by remember { mutableStateOf(current.meatPiMicroSdLog) }
    var edgeShiftLight  by remember { mutableStateOf(current.edgeShiftLight) }
    var edgeShiftColor  by remember { mutableStateOf(current.edgeShiftColor) }
    var edgeShiftIntensity by remember { mutableStateOf(current.edgeShiftIntensity) }
    var edgeShiftRpm    by remember { mutableStateOf(current.edgeShiftRpm.toString()) }
    var updateChannel   by remember { mutableStateOf(current.updateChannel) }
    // Live-preview prefs (theme mode, classic fonts, drive auto-zoom, paint
    // colour) apply visual side effects on toggle for instant preview, but
    // do NOT commit to UserPrefsStore until SAVE. Cancel reverts the side
    // effects to the committed state. RESET stages defaults in local vars.
    var navTabIdentity  by remember { mutableStateOf(current.navTabIdentity) }
    var livePillQuiet   by remember { mutableStateOf(current.livePillQuiet) }
    var gearModeTint    by remember { mutableStateOf(current.gearModeTint) }
    var themeModeLocal     by remember { mutableStateOf(current.themeMode) }
    var classicFontsLocal  by remember { mutableStateOf(current.classicFonts) }
    var driveAutoZoomLocal by remember { mutableStateOf(current.driveAutoZoom) }
    var themeIdLocal       by remember { mutableStateOf(current.themeId) }
    var error           by remember { mutableStateOf<String?>(null) }
    var resetConfirm    by remember { mutableStateOf(false) }

    // Navigation / search state — session-local
    var searchQuery      by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var aboutExpanded    by remember { mutableStateOf(false) }
    var showBlePicker    by remember { mutableStateOf(false) }

    // ═══ Build NavSection list (bodies capture state vars by closure) ══════
    val sections: List<NavSection> = listOf(
        NavSection(
            SettingsCategory.DISPLAY,
            "APPEARANCE",
            listOf("theme", "night", "day", "auto", "font", "orbitron", "classic",
                "drive", "zoom", "screen", "keep", "wake", "display"),
        ) {
            SettingsSection("APPEARANCE") {
                SettingsSwitchRow(
                    label = "Keep screen on while connected",
                    help = "Prevents the phone from sleeping while CAN is live. Drains battery.",
                    checked = screenOn, onCheckedChange = { screenOn = it },
                )
                Spacer(Modifier.height(12.dp))
                SettingsRow(
                    label = "Theme mode",
                    help = "AUTO follows the device clock (06:00–19:00 → DAY). ULTRA = pure-black AMOLED variant for night driving on OLED displays.",
                ) {
                    // Live-preview: toggling applies immediately so the user
                    // can see the palette swap. Local state is the source of
                    // truth so RESET can stage a change without committing.
                    SegmentedPicker(
                        options = listOf("NIGHT", "DAY", "AUTO", "ULTRA"),
                        selected = themeModeLocal,
                        onSelect = { sel ->
                            themeModeLocal = sel
                            setThemeMode(runCatching { ThemeMode.valueOf(sel) }
                                .getOrDefault(ThemeMode.NIGHT))
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsRow(
                    label = "Classic fonts",
                    help = "CLASSIC revives Orbitron for hero numerics.",
                ) {
                    SegmentedPicker(
                        options = listOf("MODERN", "CLASSIC"),
                        selected = if (classicFontsLocal) "CLASSIC" else "MODERN",
                        onSelect = { sel ->
                            val on = sel == "CLASSIC"
                            classicFontsLocal = on
                            setClassicFonts(on)
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsRow(
                    label = "DRIVE auto-zoom",
                    help = "Collapses inputs and inflates the gear digit once rolling above ~10 mph.",
                ) {
                    Switch(
                        checked = driveAutoZoomLocal,
                        onCheckedChange = { on ->
                            driveAutoZoomLocal = on
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnAccent,
                            checkedTrackColor = accent,
                            uncheckedThumbColor = Dim,
                            uncheckedTrackColor = Brd,
                        ),
                    )
                }
            }
        },
        NavSection(
            SettingsCategory.DISPLAY,
            "THEME — RS PAINT COLOUR",
            listOf("paint", "color", "accent", "nitrous", "blue", "red", "grey", "black"),
        ) {
            SettingsSection("THEME — RS PAINT COLOUR") {
                ThemePicker(
                    activeId = themeIdLocal,
                    onSelect = { id ->
                        themeIdLocal = id
                        setThemeAccentPreview(rsPaintAccent(id))
                    },
                )
            }
        },
        NavSection(
            SettingsCategory.DISPLAY,
            "SHIFT LIGHT",
            listOf("shift", "rpm", "redline", "glow", "edge", "peripheral"),
        ) {
            SettingsSection("SHIFT LIGHT") {
                SettingsSwitchRow(
                    label = "Peripheral edge glow",
                    help = "Screen edges glow as RPM approaches shift point.",
                    checked = edgeShiftLight, onCheckedChange = { edgeShiftLight = it },
                )
                if (edgeShiftLight) {
                    Spacer(Modifier.height(12.dp))
                    SettingsRow(
                        label = "Color",
                        help = "PROGRESSIVE fades green→yellow→red as you approach the shift point.",
                    ) {
                        SegmentedPicker(
                            options = listOf("Accent", "White", "Progressive"),
                            selected = when (edgeShiftColor) {
                                "white" -> "White"; "progressive" -> "Progressive"; else -> "Accent"
                            },
                            onSelect = {
                                edgeShiftColor = when (it) {
                                    "White" -> "white"; "Progressive" -> "progressive"; else -> "accent"
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SettingsRow(
                        label = "Intensity",
                        help = "Glow opacity at peak. HIGH is most visible in daylight.",
                    ) {
                        SegmentedPicker(
                            options = listOf("Low", "Med", "High"),
                            selected = when (edgeShiftIntensity) {
                                "low" -> "Low"; "med" -> "Med"; else -> "High"
                            },
                            onSelect = {
                                edgeShiftIntensity = when (it) {
                                    "Low" -> "low"; "Med" -> "med"; else -> "high"
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SettingsRow(
                        label = "Shift RPM",
                        help = "RPM where the flash peaks. Breathing starts at 70% of this value.",
                    ) {
                        OutlinedTextField(
                            value = edgeShiftRpm,
                            onValueChange = { edgeShiftRpm = it; error = null },
                            label = { Text("RPM", fontFamily = ShareTechMono, fontSize = 10.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(90.dp),
                            colors = outlinedFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = ShareTechMono, fontSize = 13.sp, color = Frost,
                            ),
                        )
                    }
                }
            }
        },
        NavSection(
            SettingsCategory.DISPLAY,
            "VISUAL POLISH",
            listOf("polish", "quiet", "pill", "tab", "identity", "gear", "tint", "nav"),
        ) {
            SettingsSection("VISUAL POLISH") {
                SettingsSwitchRow(
                    label = "Quiet connected pill",
                    help = "Dims the LIVE pill after 2 s of stable connection so it doesn't compete with telemetry.",
                    checked = livePillQuiet, onCheckedChange = { livePillQuiet = it },
                )
                Spacer(Modifier.height(12.dp))
                SettingsSwitchRow(
                    label = "Gear mode tint",
                    help = "Tints the GEAR label to match drive mode colour in Sport / Track / Drift.",
                    checked = gearModeTint, onCheckedChange = { gearModeTint = it },
                )
                Spacer(Modifier.height(12.dp))
                SettingsSwitchRow(
                    label = "Nav tab identity colours",
                    help = "Each bottom-nav tab uses a unique accent colour instead of the global paint accent.",
                    checked = navTabIdentity, onCheckedChange = { navTabIdentity = it },
                )
            }
        },
        NavSection(
            SettingsCategory.DISPLAY,
            "FLOATING HUD",
            listOf("hud", "overlay", "floating", "window", "boost", "rpm", "picture"),
        ) {
            SettingsSection("FLOATING HUD") {
                val hasOverlayPerm = Settings.canDrawOverlays(ctx)
                if (!hasOverlayPerm) {
                    Text(
                        "Overlay permission required to display the floating HUD over other apps.",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .border(CardBorder, accent.copy(0.3f), RoundedCornerShape(8.dp))
                            .clickable {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${ctx.packageName}"))
                                )
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("GRANT OVERLAY PERMISSION", fontSize = 11.sp, color = accent,
                            fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        "Compact boost / RPM / oil overlay on top of other apps. " +
                            "Useful on track days with a nav app.",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
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
                            contentAlignment = Alignment.Center,
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
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("STOP HUD", fontSize = 11.sp, color = Orange,
                                fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        NavSection(
            SettingsCategory.UNITS,
            "UNITS",
            listOf("mph", "kph", "celsius", "fahrenheit", "psi", "bar", "kpa",
                "speed", "temp", "temperature", "boost", "tire", "pressure"),
        ) {
            SettingsSection("UNITS") {
                SettingsRow("Speed") {
                    SegmentedPicker(
                        options = listOf("MPH", "KPH"),
                        selected = speedUnit,
                        onSelect = { speedUnit = it },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsRow("Temperature") {
                    SegmentedPicker(
                        options = listOf("°F", "°C"),
                        selected = if (tempUnit == "F") "°F" else "°C",
                        onSelect = { tempUnit = if (it == "°F") "F" else "C" },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsRow("Boost Pressure") {
                    SegmentedPicker(
                        options = listOf("PSI", "BAR", "kPa"),
                        selected = when (boostUnit) { "BAR" -> "BAR"; "KPA" -> "kPa"; else -> "PSI" },
                        onSelect = { boostUnit = when (it) { "BAR" -> "BAR"; "kPa" -> "KPA"; else -> "PSI" } },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsRow("Tire Pressure") {
                    SegmentedPicker(
                        options = listOf("PSI", "BAR"),
                        selected = tireUnit,
                        onSelect = { tireUnit = it },
                    )
                }
            }
        },
        NavSection(
            SettingsCategory.UNITS,
            "TPMS THRESHOLDS",
            listOf("tpms", "tire", "pressure", "low", "warn", "high", "psi"),
        ) {
            SettingsSection("TPMS THRESHOLDS") {
                SettingsRow("Low (critical)") {
                    OutlinedTextField(
                        value = tireLowPsi, onValueChange = { tireLowPsi = it; error = null },
                        label = { Text("PSI", fontFamily = ShareTechMono, fontSize = 10.sp) },
                        singleLine = true, modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = outlinedFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost),
                    )
                }
                SettingsRow("Warn (getting low)") {
                    OutlinedTextField(
                        value = tireWarnPsi, onValueChange = { tireWarnPsi = it; error = null },
                        label = { Text("PSI", fontFamily = ShareTechMono, fontSize = 10.sp) },
                        singleLine = true, modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = outlinedFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost),
                    )
                }
                SettingsRow("High (over-inflated)") {
                    OutlinedTextField(
                        value = tireHighPsi, onValueChange = { tireHighPsi = it; error = null },
                        label = { Text("PSI", fontFamily = ShareTechMono, fontSize = 10.sp) },
                        singleLine = true, modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = outlinedFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Red < ${AppSettings.DEFAULT_TIRE_LOW_PSI} | Gold < ${AppSettings.DEFAULT_TIRE_WARN_PSI} | " +
                        "Green | Red > ${AppSettings.DEFAULT_TIRE_HIGH_PSI} PSI",
                    fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                )
            }
        },
        NavSection(
            SettingsCategory.CONNECTION,
            "ADAPTER",
            listOf("meatpi", "wican", "usb", "pro", "c3", "s3", "hardware", "sd", "log"),
        ) {
            SettingsSection("ADAPTER") {
                SettingsRow(
                    label = "Hardware",
                    help = "MeatPi USB (C3) speaks WebSocket SLCAN; MeatPi Pro (S3) speaks raw TCP.",
                ) {
                    SegmentedPicker(
                        options = listOf("MeatPi USB (C3)", "MeatPi Pro (S3)"),
                        selected = if (adapterType == "MEATPI_PRO") "MeatPi Pro (S3)" else "MeatPi USB (C3)",
                        onSelect = { selected ->
                            val newType = if (selected == "MeatPi Pro (S3)") "MEATPI_PRO" else "MEATPI_USB"
                            if (newType != adapterType) {
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
                        },
                    )
                }
                if (adapterType == "MEATPI_PRO") {
                    Spacer(Modifier.height(12.dp))
                    SettingsSwitchRow(
                        label = "MicroSD logging reminder",
                        help = "SD logging is configured in the WiCAN Pro web UI at http://192.168.0.10/. " +
                            "This toggle is a local reminder only.",
                        checked = meatPiMicroSd, onCheckedChange = { meatPiMicroSd = it },
                    )
                }
            }
        },
        NavSection(
            SettingsCategory.CONNECTION,
            "METHOD",
            listOf("wifi", "bluetooth", "ble", "method", "transport"),
        ) {
            SettingsSection("METHOD") {
                SettingsRow(
                    label = "Connection method",
                    help = "Both adapters support both transports. WiFi keeps LTE off; Bluetooth lets the phone " +
                        "stay on normal WiFi for internet.",
                ) {
                    SegmentedPicker(
                        options = listOf("WiFi", "Bluetooth"),
                        selected = if (connectionMethod == "BLUETOOTH") "Bluetooth" else "WiFi",
                        onSelect = { selected ->
                            connectionMethod = if (selected == "Bluetooth") "BLUETOOTH" else "WIFI"
                        },
                    )
                }
            }
        },
        NavSection(
            SettingsCategory.CONNECTION,
            if (connectionMethod == "BLUETOOTH") "BLUETOOTH DEVICE" else "WIFI",
            listOf("ble", "bluetooth", "pair", "scan", "wifi", "host", "ip", "port", "address"),
        ) {
            if (connectionMethod == "BLUETOOTH") {
                val bleAddr = remember { mutableStateOf(AppSettings.getBleDeviceAddress(ctx)) }
                val bleName = remember { mutableStateOf(AppSettings.getBleDeviceName(ctx)) }
                SettingsSection("BLUETOOTH DEVICE") {
                    if (bleAddr.value != null) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(Surf2, RoundedCornerShape(10.dp))
                                .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
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
                            verticalAlignment = Alignment.CenterVertically,
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
                            contentAlignment = Alignment.Center,
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
                                        bleAddr.value = null; bleName.value = null
                                    }
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("FORGET", fontSize = 10.sp, color = Orange,
                                    fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.1.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "For best results, forget the adapter's WiFi network in your phone's WiFi settings " +
                            "to keep internet available while connected via Bluetooth.",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                    )
                }
            } else {
                val isPro = adapterType == "MEATPI_PRO"
                val defaultHost = if (isPro) AppSettings.DEFAULT_HOST_MEATPI else AppSettings.DEFAULT_HOST
                val defaultPort = if (isPro) AppSettings.DEFAULT_PORT_MEATPI else AppSettings.DEFAULT_PORT
                val adapterLabel = if (isPro) "MEATPI PRO" else "MEATPI USB"
                SettingsSection("$adapterLabel — WIFI") {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it; error = null },
                        label = { Text("Host / IP Address", fontFamily = ShareTechMono, fontSize = 11.sp) },
                        placeholder = { Text(defaultHost, fontFamily = ShareTechMono, fontSize = 12.sp, color = Dim) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = outlinedFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = port, onValueChange = { port = it; error = null },
                        label = { Text("Port", fontFamily = ShareTechMono, fontSize = 11.sp) },
                        placeholder = { Text(defaultPort.toString(), fontFamily = ShareTechMono, fontSize = 12.sp, color = Dim) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = outlinedFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (isPro)
                            "Default: $defaultHost:$defaultPort  (TCP SLCAN — configure port in WiCAN Pro web UI)"
                        else
                            "Default: $defaultHost:$defaultPort  (WebSocket SLCAN)",
                        fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                    )

                    // ── mDNS network scan ────────────────────────────────
                    Spacer(Modifier.height(12.dp))
                    var scanning by remember { mutableStateOf(false) }
                    var discovered by remember { mutableStateOf<List<WicanDiscovery.DiscoveredDevice>>(emptyList()) }
                    var scanDone by remember { mutableStateOf(false) }
                    val scanScope = rememberCoroutineScope()

                    Box(
                        Modifier.fillMaxWidth()
                            .background(accent.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .border(CardBorder, accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .then(if (scanning) Modifier else Modifier.clickable {
                                scanning = true; scanDone = false; discovered = emptyList()
                                scanScope.launch {
                                    discovered = WicanDiscovery.scan(ctx)
                                    scanning = false; scanDone = true
                                }
                            })
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (scanning) "SCANNING…" else "SCAN NETWORK",
                            fontSize = 10.sp, color = if (scanning) Dim else accent,
                            fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.1.sp,
                        )
                    }

                    if (scanDone && discovered.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("No WiCAN adapters found on the network.",
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono)
                    }

                    discovered.forEach { dev ->
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .background(Surf2, RoundedCornerShape(8.dp))
                                .border(CardBorder, Ok.copy(0.3f), RoundedCornerShape(8.dp))
                                .clickable {
                                    host = dev.host
                                    port = defaultPort.toString()
                                    error = null
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(dev.name, fontSize = 11.sp, color = Frost,
                                    fontFamily = ShareTechMono, fontWeight = FontWeight.SemiBold)
                                Text(dev.host, fontSize = 10.sp, color = Dim,
                                    fontFamily = ShareTechMono)
                            }
                            Text("USE", fontSize = 9.sp, color = Ok,
                                fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        NavSection(
            SettingsCategory.CONNECTION,
            "AUTO-RECONNECT",
            listOf("auto", "reconnect", "retry", "interval"),
        ) {
            SettingsSection("AUTO-RECONNECT") {
                SettingsSwitchRow(
                    label = "Auto-reconnect on disconnect",
                    help = "Automatically retry the CAN adapter after a drop. Required for seamless BLE reconnection when the car comes back in range.",
                    checked = autoReconnect, onCheckedChange = { autoReconnect = it },
                )
                if (autoReconnect) {
                    Spacer(Modifier.height(12.dp))
                    SettingsRow(
                        label = "Retry interval",
                        help = "How long to wait between connection attempts. " +
                            "Default: ${AppSettings.DEFAULT_RECONNECT_INTERVAL}s",
                    ) {
                        OutlinedTextField(
                            value = reconnectSec, onValueChange = { reconnectSec = it; error = null },
                            label = { Text("seconds", fontFamily = ShareTechMono, fontSize = 10.sp) },
                            singleLine = true, modifier = Modifier.width(100.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = outlinedFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost),
                        )
                    }
                }
            }
        },
        NavSection(
            SettingsCategory.DRIVES,
            "DRIVES",
            listOf("drive", "record", "auto", "save", "history", "storage", "max"),
        ) {
            SettingsSection("DRIVES") {
                SettingsRow(
                    label = "Auto-record drives",
                    help = "Automatically start recording when connected to your car.",
                ) {
                    Switch(
                        checked = autoRecordDrives,
                        onCheckedChange = { autoRecordDrives = it; error = null },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = accent,
                            checkedTrackColor = accent.copy(alpha = 0.3f),
                        ),
                    )
                }
                Spacer(Modifier.height(10.dp))
                SettingsRow(
                    label = "Max saved drives",
                    help = "Oldest drives are removed when this limit is exceeded. " +
                        "Default: ${AppSettings.DEFAULT_MAX_SAVED_DRIVES}",
                ) {
                    OutlinedTextField(
                        value = maxSavedDrives, onValueChange = { maxSavedDrives = it; error = null },
                        label = { Text("count", fontFamily = ShareTechMono, fontSize = 10.sp) },
                        singleLine = true, modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = outlinedFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost),
                    )
                }
            }
        },
        NavSection(
            SettingsCategory.DIAGNOSTICS,
            "DTC SCANNER",
            listOf("dtc", "scan", "auto", "fault", "code"),
        ) {
            SettingsSection("DTC SCANNER") {
                SettingsRow(
                    label = "Auto-scan on connect",
                    help = "Automatically scan for fault codes when the adapter connects. " +
                        "Results appear as a badge on the GARAGE tab.",
                ) {
                    Switch(
                        checked = autoScanDtcs,
                        onCheckedChange = { autoScanDtcs = it; error = null },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = accent,
                            checkedTrackColor = accent.copy(alpha = 0.3f),
                        ),
                    )
                }
            }
        },
        NavSection(
            SettingsCategory.DIAGNOSTICS,
            "EXPORTS",
            listOf("zip", "diagnostic", "export", "log", "max"),
        ) {
            SettingsSection("EXPORTS") {
                SettingsRow(
                    label = "Max saved ZIP exports",
                    help = "Oldest ZIPs are removed when this limit is exceeded. " +
                        "Default: ${AppSettings.DEFAULT_MAX_DIAG_ZIPS}",
                ) {
                    OutlinedTextField(
                        value = maxDiagZips, onValueChange = { maxDiagZips = it; error = null },
                        label = { Text("count", fontFamily = ShareTechMono, fontSize = 10.sp) },
                        singleLine = true, modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = outlinedFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost),
                    )
                }
            }
        },
        NavSection(
            SettingsCategory.ABOUT,
            "WHAT'S NEW",
            listOf("changelog", "whats", "new", "version", "release"),
        ) {
            var showWhatsNewLocal by remember { mutableStateOf(false) }
            Box(
                Modifier.fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(8.dp))
                    .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                    .clickable { showWhatsNewLocal = true }
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("WHAT'S NEW IN v${BuildConfig.VERSION_NAME}", fontSize = 11.sp,
                    color = accent, fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.sp)
            }
            if (showWhatsNewLocal) {
                WhatsNewDialog(onDismiss = { showWhatsNewLocal = false })
            }

        },
        NavSection(
            SettingsCategory.ABOUT,
            "CUSTOM DASHBOARD",
            listOf("custom", "dashboard", "gauge", "layout"),
        ) {
            val savedLayout = remember { AppSettings.loadCustomDash(ctx) }
            val gaugeCount = savedLayout?.cells?.size ?: 0
            Box(
                Modifier.fillMaxWidth()
                    .background(accent.copy(0.08f), RoundedCornerShape(8.dp))
                    .border(CardBorder, accent.copy(0.25f), RoundedCornerShape(8.dp))
                    .clickable { onCustomDash(); onDismiss() }
                    .padding(14.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Custom Dashboard", fontSize = 12.sp, color = Frost,
                            fontFamily = ShareTechMono, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (gaugeCount > 0) "$gaugeCount gauges configured"
                            else "Build a custom gauge layout",
                            fontSize = 9.sp, color = Dim, fontFamily = ShareTechMono
                        )
                    }
                    Text("\u25B6 OPEN", fontSize = 10.sp, color = accent,
                        fontFamily = ShareTechMono, letterSpacing = 0.1.sp)
                }
            }
        },
        NavSection(
            SettingsCategory.ABOUT,
            "SAPPHIRE WEB DASHBOARD",
            listOf("sapphire", "web", "browser", "analyse", "analyze"),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .background(accent.copy(0.06f), RoundedCornerShape(8.dp))
                    .border(CardBorder, accent.copy(0.2f), RoundedCornerShape(8.dp))
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://klexical.github.io/openRS_/"))
                        ctx.startActivity(intent)
                    }
                    .padding(14.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SAPPHIRE", fontSize = 11.sp, color = accent,
                            fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.15.sp)
                        Spacer(Modifier.weight(1f))
                        Text("\u2197 OPEN", fontSize = 10.sp, color = accent,
                            fontFamily = ShareTechMono, letterSpacing = 0.1.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Analyze trip & diagnostic data in your browser.",
                        fontSize = 9.sp, color = Dim, fontFamily = ShareTechMono)
                }
            }
        },
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .background(Bg, RoundedCornerShape(12.dp))
                .border(CardBorder, Brd, RoundedCornerShape(12.dp)),
        ) {
            // ── Title bar ────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(Surf3, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
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
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Compact about strip (always visible at top)
                AboutStrip(
                    expanded = aboutExpanded,
                    onToggle = { aboutExpanded = !aboutExpanded },
                    updateChannel = updateChannel,
                    onChannelChange = { updateChannel = it },
                )

                // Search bar
                SearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                )

                val isSearching = searchQuery.isNotBlank()
                when {
                    isSearching -> {
                        // Rank each section: best rank across title / category / keywords.
                        // Substring beats subsequence; title beats keywords at equal rank.
                        val scored = sections.mapNotNull { section ->
                            val tr = matchRank(searchQuery, section.title)
                            val cr = matchRank(searchQuery, section.category.label)
                            val kr = section.keywords.minOfOrNull { matchRank(searchQuery, it) } ?: 4
                            val best = minOf(tr, cr, kr)
                            if (best >= 4) null else Triple(section, best, tr)
                        }.sortedWith(compareBy({ it.second }, { it.third }))
                        val matched = scored.map { it.first }
                        if (matched.isEmpty()) {
                            Text(
                                "No settings match \"$searchQuery\".",
                                fontSize = 12.sp, color = Dim, fontFamily = ShareTechMono,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Text(
                                "${matched.size} match${if (matched.size == 1) "" else "es"}",
                                fontSize = 9.sp, color = Dim,
                                fontFamily = ShareTechMono, letterSpacing = 1.2.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            matched.forEach { section ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        section.category.label,
                                        fontSize = 9.sp, color = Dim,
                                        fontFamily = ShareTechMono, letterSpacing = 1.2.sp,
                                    )
                                    section.body()
                                }
                            }
                        }
                    }
                    selectedCategory == null -> {
                        // Category grid
                        CategoryGrid(onSelect = { selectedCategory = it })
                    }
                    else -> {
                        Breadcrumb(
                            category = selectedCategory!!,
                            onBack = { selectedCategory = null },
                        )
                        var catEntered by remember(selectedCategory) { mutableStateOf(false) }
                        LaunchedEffect(selectedCategory) { catEntered = true }
                        val activeCategory = selectedCategory!!
                        sections.filter { it.category == activeCategory }.forEachIndexed { idx, section ->
                            Box(pageEntrance(idx, catEntered, staggerDelayMs = 30)) {
                                section.body()
                            }
                        }
                    }
                }

                // Error (inline above buttons)
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = Orange, fontFamily = ShareTechMono)
                }
            }

            if (showBlePicker) {
                BleDevicePickerDialog(
                    onDeviceSelected = { address, name ->
                        AppSettings.saveBleDevice(ctx, address, name)
                        showBlePicker = false
                    },
                    onDismiss = { showBlePicker = false },
                )
            }

            // ── Action buttons ───────────────────────────────────────────────
            HorizontalDivider(color = Brd)
            val versionLabel = buildString {
                append("openRS_ v${BuildConfig.VERSION_NAME}")
                if (BuildConfig.RC_SUFFIX.isNotEmpty()) append("-${BuildConfig.RC_SUFFIX}")
            }
            Text(
                versionLabel,
                fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )
            if (resetConfirm) {
                Text(
                    "Defaults restored — tap SAVE to apply",
                    fontSize = 10.sp, color = Ok, fontFamily = ShareTechMono,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        // Revert live-preview side effects to committed state
                        setThemeMode(runCatching { ThemeMode.valueOf(current.themeMode) }
                            .getOrDefault(ThemeMode.NIGHT))
                        setClassicFonts(current.classicFonts)
                        setThemeAccentPreview(null)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Dim),
                ) {
                    Text("CANCEL", fontFamily = ShareTechMono, fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        // RESET stages defaults in local state only. Nothing
                        // is written to UserPrefsStore here — the "hit SAVE to
                        // apply" hint below the button is literal. This means
                        // Cancel after RESET is a true no-op: the user's
                        // previous settings remain intact.
                        host          = AppSettings.DEFAULT_HOST
                        port          = AppSettings.DEFAULT_PORT.toString()
                        speedUnit     = AppSettings.DEFAULT_SPEED_UNIT
                        tempUnit      = AppSettings.DEFAULT_TEMP_UNIT
                        boostUnit     = AppSettings.DEFAULT_BOOST_UNIT
                        tireUnit      = AppSettings.DEFAULT_TIRE_UNIT
                        tireLowPsi    = AppSettings.DEFAULT_TIRE_LOW_PSI.toString()
                        tireWarnPsi   = AppSettings.DEFAULT_TIRE_WARN_PSI.toString()
                        tireHighPsi   = AppSettings.DEFAULT_TIRE_HIGH_PSI.toString()
                        screenOn      = AppSettings.DEFAULT_SCREEN_ON
                        autoReconnect = AppSettings.DEFAULT_AUTO_RECONNECT
                        reconnectSec  = AppSettings.DEFAULT_RECONNECT_INTERVAL.toString()
                        maxDiagZips   = AppSettings.DEFAULT_MAX_DIAG_ZIPS.toString()
                        autoRecordDrives = AppSettings.DEFAULT_AUTO_RECORD_DRIVES
                        autoScanDtcs = AppSettings.DEFAULT_AUTO_SCAN_DTCS
                        maxSavedDrives = AppSettings.DEFAULT_MAX_SAVED_DRIVES.toString()
                        adapterType   = AppSettings.DEFAULT_ADAPTER_TYPE
                        connectionMethod = AppSettings.DEFAULT_CONNECTION_METHOD
                        meatPiMicroSd = AppSettings.DEFAULT_MEATPI_MICROSD
                        edgeShiftLight    = AppSettings.DEFAULT_EDGE_SHIFT_LIGHT
                        edgeShiftColor    = AppSettings.DEFAULT_EDGE_SHIFT_COLOR
                        edgeShiftIntensity = AppSettings.DEFAULT_EDGE_SHIFT_INTENSITY
                        edgeShiftRpm      = AppSettings.DEFAULT_EDGE_SHIFT_RPM.toString()
                        updateChannel = AppSettings.DEFAULT_UPDATE_CHANNEL
                        navTabIdentity = AppSettings.DEFAULT_NAV_TAB_IDENTITY
                        livePillQuiet  = AppSettings.DEFAULT_LIVE_PILL_QUIET
                        gearModeTint   = AppSettings.DEFAULT_GEAR_MODE_TINT
                        // Live-preview prefs also stage-only on RESET. Their
                        // pickers read themeModeLocal / classicFontsLocal /
                        // driveAutoZoomLocal / themeIdLocal, so the UI will
                        // reflect the staged defaults immediately while the
                        // actual applied theme stays at its current value
                        // until SAVE.
                        themeModeLocal     = AppSettings.DEFAULT_THEME_MODE
                        classicFontsLocal  = AppSettings.DEFAULT_CLASSIC_FONTS
                        driveAutoZoomLocal = AppSettings.DEFAULT_DRIVE_AUTO_ZOOM
                        themeIdLocal       = AppSettings.DEFAULT_THEME_ID

                        error         = null
                        resetConfirm  = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(contentColor = Dim),
                ) {
                    Text("RESET", fontFamily = ShareTechMono, fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val p = port.toIntOrNull()
                        val threshold = tireLowPsi.toFloatOrNull()
                        val warnThr   = tireWarnPsi.toFloatOrNull()
                        val highThr   = tireHighPsi.toFloatOrNull()
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
                                    autoScanDtcs         = autoScanDtcs,
                                    maxSavedDrives       = maxDrives ?: AppSettings.DEFAULT_MAX_SAVED_DRIVES,
                                    updateChannel        = updateChannel,
                                    navTabIdentity       = navTabIdentity,
                                    livePillQuiet        = livePillQuiet,
                                    gearModeTint         = gearModeTint,
                                    // Live-preview prefs — toggling only updated local
                                    // state + visual side effects; committing here persists.
                                    themeMode            = themeModeLocal,
                                    classicFonts         = classicFontsLocal,
                                    driveAutoZoom        = driveAutoZoomLocal,
                                    themeId              = themeIdLocal,
                                )}
                                // Ensure the runtime theme reflects whatever
                                // the local state ended up at (covers the
                                // RESET→SAVE path where the toggle handler
                                // never ran).
                                setThemeMode(runCatching { ThemeMode.valueOf(themeModeLocal) }
                                    .getOrDefault(ThemeMode.NIGHT))
                                setClassicFonts(classicFontsLocal)
                                setThemeAccentPreview(null)
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = OnAccent),
                ) {
                    Text("SAVE", fontFamily = ShareTechMono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Navigation affordances ───────────────────────────────────────────────────

@Composable
private fun AboutStrip(
    expanded: Boolean,
    onToggle: () -> Unit,
    updateChannel: String,
    onChannelChange: (String) -> Unit,
) {
    val accent = LocalThemeAccent.current
    val label = buildString {
        append("openRS_ v").append(BuildConfig.VERSION_NAME)
        if (BuildConfig.RC_SUFFIX.isNotEmpty()) append("-").append(BuildConfig.RC_SUFFIX)
        append(" · ").append(updateChannel)
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(CardBorder, Brd, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 11.sp, color = Frost, fontFamily = ShareTechMono,
                letterSpacing = 0.06.sp)
            Text(
                if (expanded) "HIDE" else "CHECK",
                fontSize = 10.sp, color = accent,
                fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.sp,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
                AppUpdatesSection(
                    updateChannel = updateChannel,
                    onChannelChange = onChannelChange,
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val accent = LocalThemeAccent.current
    Row(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(CardBorder, Brd, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", fontSize = 14.sp, color = Dim, fontFamily = ShareTechMono,
            modifier = Modifier.padding(end = 10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(accent),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Frost, fontFamily = ShareTechMono, fontSize = 13.sp,
            ),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("Search settings…", fontSize = 13.sp, color = Dim,
                        fontFamily = ShareTechMono)
                }
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Text("✕", fontSize = 14.sp, color = Dim, fontFamily = ShareTechMono,
                modifier = Modifier.clickable { onValueChange("") }.padding(start = 8.dp))
        }
    }
}

@Composable
private fun CategoryGrid(onSelect: (SettingsCategory) -> Unit) {
    // rc.2: single-column list (owner's-manual feel) replaces the 6-tile grid that
    // the Daylight critique flagged as "Material Design DNA, generic" hierarchy.
    val entries = SettingsCategory.entries
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEachIndexed { idx, cat ->
            Box(pageEntrance(idx, entered, staggerDelayMs = 30)) {
                CategoryRow(cat, onClick = { onSelect(cat) })
            }
        }
    }
}

@Composable
private fun CategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    val accentM = accentMid()
    val accentD = accentDim()
    Row(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Surf2, Surf.copy(alpha = 0.5f))),
                RoundedCornerShape(10.dp),
            )
            .border(CardBorder, Brd, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).height(22.dp)
                .background(accentM, RoundedCornerShape(1.5.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MonoLabel(category.label, 11.sp, accentM, letterSpacing = 1.4.sp)
            Text(category.blurb, fontSize = 11.sp, color = Dim, fontFamily = ShareTechMono)
        }
        Text("›", fontSize = 22.sp, color = accentD, fontFamily = ShareTechMono,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Breadcrumb(category: SettingsCategory, onBack: () -> Unit) {
    val accentD = accentDim()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onBack).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", fontSize = 20.sp, color = accentD, fontFamily = ShareTechMono,
            modifier = Modifier.padding(end = 10.dp))
        Text("Settings", fontSize = 11.sp, color = Dim, fontFamily = ShareTechMono,
            letterSpacing = 0.08.sp)
        Text(" › ", fontSize = 11.sp, color = Dim, fontFamily = ShareTechMono)
        Text(category.label, fontSize = 11.sp, color = Frost, fontFamily = ShareTechMono,
            fontWeight = FontWeight.Bold, letterSpacing = 0.08.sp)
    }
}

// ── Reusable settings components ─────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val accentM = accentMid()
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Surf2, Surf.copy(alpha = 0.5f))),
                RoundedCornerShape(10.dp),
            )
            .border(Tokens.CardBorder, Brd, RoundedCornerShape(10.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(3.dp).height(14.dp)
                    .background(accentM, RoundedCornerShape(1.5.dp)),
            )
            Spacer(Modifier.width(8.dp))
            MonoLabel(title, 9.sp, accentM, letterSpacing = 1.5.sp)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun SettingsRow(
    label: String,
    help: String? = null,
    content: @Composable () -> Unit,
) {
    var helpOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono)
                if (help != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(16.dp)
                            .border(CardBorder, Dim, CircleShape)
                            .clickable { helpOpen = !helpOpen },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("?", fontSize = 9.sp, color = Dim, fontFamily = ShareTechMono,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
            content()
        }
        if (help != null) {
            AnimatedVisibility(
                visible = helpOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    help, fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: String? = null,
) {
    var helpOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono)
                if (help != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(16.dp)
                            .border(CardBorder, Dim, CircleShape)
                            .clickable { helpOpen = !helpOpen },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("?", fontSize = 9.sp, color = Dim, fontFamily = ShareTechMono,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor  = OnAccent,
                    checkedTrackColor  = LocalThemeAccent.current,
                    uncheckedThumbColor = Dim,
                    uncheckedTrackColor = Brd,
                ),
            )
        }
        if (help != null) {
            AnimatedVisibility(
                visible = helpOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    help, fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
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
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) pickerAccent else Color.Transparent,
                animationSpec = tween(250), label = "segBg",
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) pickerAccent.copy(alpha = 0.6f) else Color.Transparent,
                animationSpec = tween(250), label = "segBrd",
            )
            Box(
                Modifier
                    .background(bgColor, RoundedCornerShape(4.dp))
                    .border(CardBorder, borderColor, RoundedCornerShape(4.dp))
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = ShareTechMono,
                    color = if (isSelected) OnAccent else Dim,
                )
            }
        }
    }
}

// ── App Updates section ──────────────────────────────────────────────────────

@Composable
private fun AppUpdatesSection(
    updateChannel: String,
    onChannelChange: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current
    val scope = rememberCoroutineScope()
    val updateState by UpdateManager.state.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── Channel picker ──────────────────────────────────────────────
        SettingsRow(
            label = "Update channel",
            help = if (updateChannel == "beta") "Includes pre-release (RC) builds from GitHub"
                   else "Only stable releases from GitHub",
        ) {
            SegmentedPicker(
                options = listOf("stable", "beta"),
                selected = updateChannel,
                onSelect = onChannelChange,
            )
        }

        // ── Check for updates button ────────────────────────────────────
        Box(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(8.dp))
                .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                .clickable {
                    scope.launch { UpdateManager.checkForUpdate(ctx, updateChannel) }
                }
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("CHECK FOR UPDATES", fontSize = 11.sp,
                color = accent, fontFamily = ShareTechMono, fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.sp)
        }

        // ── Status display ──────────────────────────────────────────────
        when (val state = updateState) {
            is UpdateState.Idle -> {}
            is UpdateState.Checking -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = accent, strokeWidth = 2.dp,
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
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.fileSizeBytes > 0) {
                        Text(
                            "%.1f MB".format(state.fileSizeBytes / 1_048_576.0),
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                        )
                    }
                    if (state.releaseNotes.isNotEmpty()) {
                        Text(
                            state.releaseNotes.take(300) +
                                if (state.releaseNotes.length > 300) "..." else "",
                            fontSize = 10.sp, color = Mid, fontFamily = ShareTechMono,
                            lineHeight = 14.sp,
                        )
                    }

                    val canInstall = ctx.packageManager.canRequestPackageInstalls()
                    if (!canInstall) {
                        Text(
                            "Allow app installs from openRS_ in system settings to continue",
                            fontSize = 10.sp, color = Orange, fontFamily = ShareTechMono,
                        )
                        Box(
                            Modifier.fillMaxWidth()
                                .background(Orange.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .border(CardBorder, Orange.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable {
                                    ctx.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:${ctx.packageName}"),
                                        ),
                                    )
                                }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
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
                            contentAlignment = Alignment.Center,
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
                            color = accent, trackColor = Brd,
                        )
                        Text(
                            "Downloading... ${(state.progress * 100).toInt()}%  " +
                                "(%.1f / %.1f MB)".format(
                                    state.bytesDownloaded / 1_048_576.0,
                                    state.totalBytes / 1_048_576.0,
                                ),
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = accent, trackColor = Brd,
                        )
                        Text(
                            "Downloading... %.1f MB".format(state.bytesDownloaded / 1_048_576.0),
                            fontSize = 10.sp, color = Dim, fontFamily = ShareTechMono,
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
                        contentAlignment = Alignment.Center,
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
