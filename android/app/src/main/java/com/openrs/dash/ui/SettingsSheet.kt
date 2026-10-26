package com.openrs.dash.ui

import androidx.core.net.toUri
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import android.content.Intent
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
    THERMAL("THERMAL", "Profiles · warm-up · limits"),
    CONNECTION("CONNECTION", "Adapter · WiFi · Bluetooth"),
    DRIVES("DRIVES", "Auto-record · storage"),
    DIAGNOSTICS("DIAGNOSTICS", "Exports · logging"),
    ABOUT("ABOUT", "Version · credits"),
    DEVELOPER("DEVELOPER", "Demo mode · simulated data"),   // debug builds only
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
fun SettingsDialog(
    onDismiss: () -> Unit,
    onCustomDash: () -> Unit = {},
    onStartDemo: (com.openrs.dash.demo.DemoScenario) -> Unit = {},
    onExitDemo: () -> Unit = {},
) {
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
    // Thermal profile — active profile + Custom values as editor cells (exact °C kept until a cell is edited)
    var thermalPreset   by remember { mutableStateOf(if (current.tempPreset == "custom" && current.customThermal == null) "street" else current.tempPreset) }
    var customBase      by remember { mutableStateOf(current.customThermalBase) }
    var customCells     by remember { mutableStateOf(current.customThermal?.let { thermalCells(it, current.tempUnit) }) }
    LaunchedEffect(tempUnit) { customCells = customCells?.mapValues { it.value.withUnit(tempUnit) } }
    val thermalErrors = customCells?.let { cells -> validateThermalProfile(cells.mapValues { it.value.resolvedC }, tempUnit) }.orEmpty()
    // Live-preview prefs (theme mode, classic fonts, paint
    // colour) apply visual side effects on toggle for instant preview, but
    // do NOT commit to UserPrefsStore until SAVE. Cancel reverts the side
    // effects to the committed state. RESET stages defaults in local vars.
    var navTabIdentity  by remember { mutableStateOf(current.navTabIdentity) }
    var livePillQuiet   by remember { mutableStateOf(current.livePillQuiet) }
    var gearModeTint    by remember { mutableStateOf(current.gearModeTint) }
    var themeModeLocal     by remember { mutableStateOf(current.themeMode) }
    var classicFontsLocal  by remember { mutableStateOf(current.classicFonts) }
    var themeIdLocal       by remember { mutableStateOf(current.themeId) }
    var error           by remember { mutableStateOf<String?>(null) }
    var resetConfirm    by remember { mutableStateOf(false) }

    // Navigation / search state — session-local
    var searchQuery      by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var aboutExpanded    by remember { mutableStateOf(false) }
    var showBlePicker    by remember { mutableStateOf(false) }

    // Every exit except SAVE discards: revert the live-preview side effects (theme mode, fonts, paint) to the
    // committed prefs. Back, tapping outside and ✕ used to dismiss without this, leaving an unsaved preview on.
    val cancel: () -> Unit = {
        setThemeMode(runCatching { ThemeMode.valueOf(current.themeMode) }.getOrDefault(ThemeMode.NIGHT))
        setClassicFonts(current.classicFonts)
        setThemeAccentPreview(null)
        onDismiss()
    }

    // ═══ Build NavSection list (bodies capture state vars by closure) ══════
    val demoStatus by com.openrs.dash.demo.DemoMode.status.collectAsState()
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
                Spacer(Modifier.height(4.dp))
                SettingsRow(
                    label = "Theme mode",
                    stacked = true,
                    help = "AUTO follows the device clock (06:00–19:00 → DAY). ULTRA = pure-black AMOLED variant for night driving on OLED displays.",
                ) {
                    // Live-preview: toggling applies immediately so the user
                    // can see the palette swap. Local state is the source of
                    // truth so RESET can stage a change without committing.
                    SettingsPicker(
                        options = listOf("NIGHT", "DAY", "AUTO", "ULTRA"),
                        fill = true,
                        selected = themeModeLocal,
                        onSelect = { sel ->
                            themeModeLocal = sel
                            setThemeMode(runCatching { ThemeMode.valueOf(sel) }
                                .getOrDefault(ThemeMode.NIGHT))
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                SettingsRow(
                    label = "Classic fonts",
                    help = "CLASSIC revives Orbitron for hero numerics.",
                ) {
                    SettingsPicker(
                        options = listOf("MODERN", "CLASSIC"),
                        selected = if (classicFontsLocal) "CLASSIC" else "MODERN",
                        onSelect = { sel ->
                            val on = sel == "CLASSIC"
                            classicFontsLocal = on
                            setClassicFonts(on)
                        },
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
                    Spacer(Modifier.height(4.dp))
                    SettingsRow(
                        label = "Color",
                    stacked = true,
                        help = "PROGRESSIVE fades green→yellow→red as you approach the shift point.",
                    ) {
                        SettingsPicker(
                            options = listOf("Accent", "White", "Progressive"),
                        fill = true,
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
                    Spacer(Modifier.height(4.dp))
                    SettingsRow(
                        label = "Intensity",
                        help = "Glow opacity at peak. HIGH is most visible in daylight.",
                    ) {
                        SettingsPicker(
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
                    Spacer(Modifier.height(4.dp))
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
                Spacer(Modifier.height(4.dp))
                SettingsSwitchRow(
                    label = "Gear mode tint",
                    help = "Tints the GEAR label to match drive mode colour in Sport / Track / Drift.",
                    checked = gearModeTint, onCheckedChange = { gearModeTint = it },
                )
                Spacer(Modifier.height(4.dp))
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
                    SettingsNote("Overlay permission required to display the floating HUD over other apps.")
                    Spacer(Modifier.height(8.dp))
                    SettingsAction("GRANT OVERLAY PERMISSION", Modifier.fillMaxWidth()) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${ctx.packageName}".toUri())
                        )
                    }
                } else {
                    SettingsNote("Compact boost / RPM / oil overlay on top of other apps. " +
                        "Useful on track days with a nav app.")
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsAction("START HUD", Modifier.weight(1f)) {
                            ctx.startService(Intent(ctx, HudOverlayService::class.java))
                        }
                        SettingsAction("STOP HUD", Modifier.weight(1f), tone = ActionTone.DESTRUCTIVE) {
                            ctx.stopService(Intent(ctx, HudOverlayService::class.java))
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
                    SettingsPicker(
                        options = listOf("MPH", "KPH"),
                        selected = speedUnit,
                        onSelect = { speedUnit = it },
                    )
                }
                Spacer(Modifier.height(4.dp))
                SettingsRow("Temperature") {
                    SettingsPicker(
                        options = listOf("°F", "°C"),
                        selected = if (tempUnit == "F") "°F" else "°C",
                        onSelect = { tempUnit = if (it == "°F") "F" else "C" },
                    )
                }
                Spacer(Modifier.height(4.dp))
                SettingsRow("Boost Pressure") {
                    SettingsPicker(
                        options = listOf("PSI", "BAR", "kPa"),
                        selected = when (boostUnit) { "BAR" -> "BAR"; "KPA" -> "kPa"; else -> "PSI" },
                        onSelect = { boostUnit = when (it) { "BAR" -> "BAR"; "kPa" -> "KPA"; else -> "PSI" } },
                    )
                }
                Spacer(Modifier.height(4.dp))
                SettingsRow("Tire Pressure") {
                    SettingsPicker(
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
                Spacer(Modifier.height(6.dp))
                SettingsNote(
                    "Below Low = critical · below Warn = caution · above High = over-inflated. " +
                        "Defaults ${AppSettings.DEFAULT_TIRE_LOW_PSI} / ${AppSettings.DEFAULT_TIRE_WARN_PSI} / " +
                        "${AppSettings.DEFAULT_TIRE_HIGH_PSI} psi."
                )
            }
        },
        NavSection(
            SettingsCategory.THERMAL,
            "THERMAL PROFILE",
            listOf("thermal", "profile", "street", "track", "race", "custom", "warm-up", "warning", "critical",
                "oil", "coolant", "ptu", "rdu", "clutch", "intake", "limit", "threshold", "readiness"),
        ) {
            val unitLabel = if (tempUnit == "C") "°C" else "°F"
            val options = listOf("STREET", "TRACK", "RACE") + (if (customCells != null) listOf("CUSTOM") else emptyList())
            // Sections are laid out in a Box — keep this page's blocks in one Column
            Column {
            SettingsSection("THERMAL PROFILE") {
                SettingsRow("Active profile", stacked = options.size > 3) {
                    SettingsPicker(options = options, selected = thermalPreset.uppercase(),
                        onSelect = { thermalPreset = it.lowercase() }, fill = options.size > 3)
                }
                Spacer(Modifier.height(6.dp))
                SettingsNote(
                    "These values control openRS_ advisories — warnings, readiness and gauge colours. " +
                        "They don't change the car's own protections. STREET, TRACK and RACE are existing " +
                        "openRS_ defaults, not manufacturer limits."
                )
            }
            Spacer(Modifier.height(8.dp))
            val editing = thermalPreset == "custom" && customCells != null
            val title = if (editing) "CUSTOM · copied from ${customBase.uppercase()}" else "${thermalPreset.uppercase()} · built-in, read only"
            SettingsSection("VALUES · $unitLabel") {
                InstrumentMeta(title)
                Spacer(Modifier.height(6.dp))
                val cells = if (editing) customCells!! else thermalCells(ThermalProfile.builtIn(thermalPreset), tempUnit)
                ThermalProfileTable(cells, editable = editing, errors = if (editing) thermalErrors else emptyMap(),
                    onEdit = { key, text -> customCells = customCells?.let { it + (key to it.getValue(key).copy(text = text)) }; error = null })
                Spacer(Modifier.height(10.dp))
                if (editing) {
                    SettingsAction("RESET CUSTOM TO ${customBase.uppercase()} DEFAULTS", Modifier.fillMaxWidth(), tone = ActionTone.NEUTRAL) {
                        customCells = thermalCells(ThermalProfile.builtIn(customBase), tempUnit); error = null
                    }
                } else {
                    SettingsAction(
                        (if (customCells != null) "REPLACE CUSTOM WITH " else "COPY TO CUSTOM: ") + thermalPreset.uppercase(),
                        Modifier.fillMaxWidth(),
                    ) {
                        customBase = thermalPreset
                        customCells = thermalCells(ThermalProfile.builtIn(thermalPreset), tempUnit)
                        thermalPreset = "custom"; error = null
                    }
                }
                Spacer(Modifier.height(6.dp))
                SettingsNote(
                    "Order: warm-up < warning < critical. Critical must be more than " +
                        "${if (tempUnit == "C") "2 °C" else "3.6 °F"} above warning and warning more than " +
                        "${if (tempUnit == "C") "3 °C" else "5.4 °F"} above warm-up, so the existing recovery " +
                        "margins still work. Freshness, hold times and forecasts don't change. SAVE applies, CANCEL discards."
                )
            }
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
                    stacked = true,
                    help = "MeatPi USB (C3) speaks WebSocket SLCAN; MeatPi Pro (S3) speaks raw TCP.",
                ) {
                    SettingsPicker(
                        options = listOf("MeatPi USB (C3)", "MeatPi Pro (S3)"),
                        fill = true,
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
                    Spacer(Modifier.height(4.dp))
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
                    stacked = true,
                    help = "Both adapters support both transports. WiFi keeps LTE off; Bluetooth lets the phone " +
                        "stay on normal WiFi for internet.",
                ) {
                    SettingsPicker(
                        options = listOf("WiFi", "Bluetooth"),
                        fill = true,
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
                                InstrumentLabel("PAIRED DEVICE")
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
                            Text("No device paired", fontSize = 12.sp, color = Mid,
                                fontFamily = ShareTechMono)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsAction("SCAN FOR DEVICES", Modifier.weight(1f)) { showBlePicker = true }
                        if (bleAddr.value != null) {
                            SettingsAction("FORGET", Modifier.weight(1f), tone = ActionTone.DESTRUCTIVE) {
                                AppSettings.clearBleDevice(ctx)
                                bleAddr.value = null; bleName.value = null
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SettingsNote(
                        "For best results, forget the adapter's WiFi network in your phone's WiFi settings " +
                            "to keep internet available while connected via Bluetooth."
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
                    SettingsNote(
                        if (isPro)
                            "Default: $defaultHost:$defaultPort  (TCP SLCAN — configure port in WiCAN Pro web UI)"
                        else
                            "Default: $defaultHost:$defaultPort  (WebSocket SLCAN)"
                    )

                    // ── mDNS network scan ────────────────────────────────
                    Spacer(Modifier.height(12.dp))
                    var scanning by remember { mutableStateOf(false) }
                    var discovered by remember { mutableStateOf<List<WicanDiscovery.DiscoveredDevice>>(emptyList()) }
                    var scanDone by remember { mutableStateOf(false) }
                    val scanScope = rememberCoroutineScope()

                    SettingsAction(
                        if (scanning) "SCANNING…" else "SCAN NETWORK",
                        Modifier.fillMaxWidth(),
                        enabled = !scanning,
                    ) {
                        scanning = true; scanDone = false; discovered = emptyList()
                        scanScope.launch {
                            discovered = WicanDiscovery.scan(ctx)
                            scanning = false; scanDone = true
                        }
                    }

                    if (scanDone && discovered.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        SettingsNote("No WiCAN adapters found on the network.")
                    }

                    discovered.forEach { dev ->
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(Surf2, RoundedCornerShape(8.dp))
                                .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    host = dev.host
                                    port = defaultPort.toString()
                                    error = null
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(dev.name, fontSize = 11.sp, color = Frost,
                                    fontFamily = ShareTechMono, fontWeight = FontWeight.SemiBold)
                                Text(dev.host, fontSize = 10.sp, color = Dim,
                                    fontFamily = ShareTechMono)
                            }
                            Text("USE", fontSize = 11.sp, color = actionInk(accent),
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
                    Spacer(Modifier.height(4.dp))
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
                SettingsSwitchRow(
                    label = "Auto-record drives",
                    help = "Automatically start recording when connected to your car.",
                    checked = autoRecordDrives,
                    onCheckedChange = { autoRecordDrives = it; error = null },
                )
                Spacer(Modifier.height(4.dp))
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
                SettingsSwitchRow(
                    label = "Auto-scan on connect",
                    help = "Automatically scan for fault codes when the adapter connects. " +
                        "Results appear as a badge on the GARAGE tab.",
                    checked = autoScanDtcs,
                    onCheckedChange = { autoScanDtcs = it; error = null },
                )
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
            SettingsAction("WHAT'S NEW IN v${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth()) {
                showWhatsNewLocal = true
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
            SettingsLinkRow(
                title = "Custom Dashboard",
                detail = if (gaugeCount > 0) "$gaugeCount gauges configured" else "Build a custom gauge layout",
                action = "OPEN ›",
            ) { onCustomDash(); cancel() }
        },
        NavSection(
            SettingsCategory.ABOUT,
            "SAPPHIRE WEB DASHBOARD",
            listOf("sapphire", "web", "browser", "analyse", "analyze"),
        ) {
            SettingsLinkRow(
                title = "Sapphire",
                detail = "Analyze trip & diagnostic data in your browser.",
                action = "\u2197 OPEN",
            ) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://klexical.github.io/openRS_/".toUri()))
            }
        },
    ) + if (!com.openrs.dash.demo.DemoMode.AVAILABLE) emptyList() else listOf(
        NavSection(
            SettingsCategory.DEVELOPER,
            "DEMO MODE",
            listOf("demo", "simulate", "fake", "test", "emulator", "developer"),
        ) {
            SettingsSection("DEMO MODE") {
                Text(
                    "Feeds simulated telemetry through the real dashboard. Adapter traffic stops " +
                        "first; drive-mode, ESC and DTC commands only change simulated state; " +
                        "nothing is recorded to trip history. Exiting leaves the app disconnected " +
                        "until you tap connect. Applies immediately — not a saved setting.",
                    fontSize = 11.sp, color = Mid, fontFamily = ShareTechMono, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(12.dp))
                com.openrs.dash.demo.DemoScenario.entries.forEach { sc ->
                    val current = demoStatus.active && demoStatus.scenario == sc
                    Box(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(min = 56.dp)
                            .background(if (current) Warn.copy(alpha = pillBgAlpha(0.14f)) else Surf2, RoundedCornerShape(8.dp))
                            .border(CardBorder, Warn.copy(alpha = borderAlpha(if (current) 0.6f else 0.25f)), RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onStartDemo(sc); cancel() }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column(Modifier.padding(end = 56.dp)) {
                            Text(sc.label.uppercase(), fontSize = 11.sp, color = if (current) demoInk() else Frost,
                                fontFamily = ShareTechMono, fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp)
                            Text(sc.blurb, fontSize = 10.sp, color = Mid, fontFamily = ShareTechMono,
                                lineHeight = 13.sp)
                        }
                        Text(if (current) "RUNNING" else "\u25B6 START", fontSize = 10.sp, color = demoInk(),
                            fontFamily = ShareTechMono, letterSpacing = 0.1.sp,
                            modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
                if (demoStatus.active) {
                    Spacer(Modifier.height(4.dp))
                    SettingsAction("EXIT DEMO MODE", Modifier.fillMaxWidth(), tone = ActionTone.DESTRUCTIVE) {
                        onExitDemo(); cancel()
                    }
                }
            }
        },
    )

    Dialog(
        onDismissRequest = cancel,
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
                Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("open", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = ShareTechMono, color = Frost)
                    Text("RS", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = ShareTechMono, color = accent)
                    Text("_ Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = ShareTechMono, color = Frost)
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Close without saving") { cancel() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", fontSize = 18.sp, color = Mid)
                }
            }
            HorizontalDivider(color = Brd)

            // ── Scrollable body ──────────────────────────────────────────────
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            InstrumentMeta(
                                "${matched.size} match${if (matched.size == 1) "" else "es"}",
                                Modifier.padding(bottom = 2.dp),
                            )
                            matched.forEach { section ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    InstrumentLabel(section.category.label)
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
            // The version lives in the strip at the top of the body — the footer is only the actions,
            // which leaves the settings more room on short screens.
            HorizontalDivider(color = Brd)
            // A blocked SAVE says why right above the buttons (it used to sit at the end of the scrolled page,
            // out of sight when the problem was further up)
            if (error != null) {
                Text(error!!, fontSize = 12.sp, color = textColor(Orange), fontFamily = ShareTechMono,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
                    textAlign = TextAlign.Center)
            }
            if (resetConfirm) {
                Text(
                    "Defaults staged — tap SAVE to apply, CANCEL to keep your settings",
                    fontSize = 10.sp, color = Mid, fontFamily = ShareTechMono,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = cancel,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Brd),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Mid),
                ) {
                    Text("CANCEL", fontFamily = ShareTechMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        // themeIdLocal, so the UI will
                        // reflect the staged defaults immediately while the
                        // actual applied theme stays at its current value
                        // until SAVE.
                        themeModeLocal     = AppSettings.DEFAULT_THEME_MODE
                        classicFontsLocal  = AppSettings.DEFAULT_CLASSIC_FONTS
                        themeIdLocal       = AppSettings.DEFAULT_THEME_ID

                        error         = null
                        resetConfirm  = true
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Mid),
                ) {
                    Text("RESET", fontFamily = ShareTechMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                            thermalErrors.isNotEmpty() -> error = "THERMAL: fix the highlighted custom values"
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
                                    themeId              = themeIdLocal,
                                    tempPreset           = thermalPreset,
                                    customThermal        = customCells?.let { thermalProfileFrom(it) } ?: it.customThermal,
                                    customThermalBase    = if (customCells != null) customBase else it.customThermalBase,
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
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
    Column(Modifier.fillMaxWidth().instrumentSurface(radius = 10.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = if (expanded) "Hide updates" else "Show updates") { onToggle() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 11.sp, color = Frost, fontFamily = ShareTechMono,
                letterSpacing = 0.06.sp)
            Text(
                if (expanded) "HIDE" else "UPDATES",
                fontSize = 11.sp, color = actionInk(accent),
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
            .heightIn(min = 48.dp)
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(CardBorder, Brd, RoundedCornerShape(10.dp))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", fontSize = 14.sp, color = Mid, fontFamily = ShareTechMono,
            modifier = Modifier.padding(end = 10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(accent),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Frost, fontFamily = ShareTechMono, fontSize = 13.sp,
            ),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("Search settings…", fontSize = 13.sp, color = Dim,
                        fontFamily = ShareTechMono)
                }
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Clear search") { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", fontSize = 14.sp, color = Mid, fontFamily = ShareTechMono)
            }
        } else Spacer(Modifier.width(14.dp))
    }
}

@Composable
private fun CategoryGrid(onSelect: (SettingsCategory) -> Unit) {
    // rc.2: single-column list (owner's-manual feel) replaces the 6-tile grid that
    // the Daylight critique flagged as "Material Design DNA, generic" hierarchy.
    val entries = SettingsCategory.entries.filter {
        it != SettingsCategory.DEVELOPER || com.openrs.dash.demo.DemoMode.AVAILABLE
    }
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

/** One category: a flat instrument panel — name in ink, contents in Dim, a chevron. */
@Composable
private fun CategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .instrumentSurface(radius = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MonoLabel(category.label, 12.sp, Frost, FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(category.blurb, fontSize = 11.sp, color = Dim, fontFamily = ShareTechMono)
        }
        Text("›", fontSize = 22.sp, color = Mid, fontFamily = ShareTechMono,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Breadcrumb(category: SettingsCategory, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "All settings", onClick = onBack)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", fontSize = 22.sp, color = Mid, fontFamily = ShareTechMono,
            modifier = Modifier.padding(end = 10.dp))
        Text("Settings", fontSize = 12.sp, color = Mid, fontFamily = ShareTechMono,
            letterSpacing = 0.08.sp)
        Text("  ›  ", fontSize = 12.sp, color = Dim, fontFamily = ShareTechMono)
        Text(category.label, fontSize = 12.sp, color = Frost, fontFamily = ShareTechMono,
            fontWeight = FontWeight.Bold, letterSpacing = 0.08.sp)
    }
}

// ── Reusable settings components ─────────────────────────────────────────────

/** A group of settings: the shared instrument panel with a caption (was a gradient card with an accent bar). */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .instrumentSurface(radius = 10.dp)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        InstrumentLabel(title, color = Mid)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** "?" toggle for a row's help text — 48 dp touch area around a 20 dp mark. */
@Composable
private fun HelpToggle(open: Boolean, onToggle: () -> Unit) {
    val accent = LocalThemeAccent.current
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(onClickLabel = if (open) "Hide help" else "Show help", onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(20.dp)
                .background(if (open) accent.copy(alpha = pillBgAlpha(0.14f)) else Color.Transparent, CircleShape)
                .border(CardBorder, if (open) accent.copy(alpha = borderAlpha(0.6f)) else Mid, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", fontSize = 11.sp, color = if (open) actionInk(accent) else Mid,
                fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HelpText(help: String, open: Boolean) {
    AnimatedVisibility(
        visible = open,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Text(
            help, fontSize = 11.sp, color = Mid, fontFamily = ShareTechMono, lineHeight = 15.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

/**
 * Label (+ help) with its control. [stacked] puts the control on its own full-width line — used for
 * pickers whose options don't fit beside the label on a 360 dp phone.
 */
@Composable
private fun SettingsRow(
    label: String,
    help: String? = null,
    stacked: Boolean = false,
    content: @Composable () -> Unit,
) {
    var helpOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono,
                    modifier = Modifier.weight(1f, fill = false))
                if (help != null) HelpToggle(helpOpen) { helpOpen = !helpOpen }
            }
            if (!stacked) content()
        }
        if (help != null) HelpText(help, helpOpen)
        if (stacked) content()
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
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono,
                    modifier = Modifier.weight(1f, fill = false))
                if (help != null) HelpToggle(helpOpen) { helpOpen = !helpOpen }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor    = OnAccent,
                    checkedTrackColor    = LocalThemeAccent.current,
                    checkedBorderColor   = LocalThemeAccent.current,
                    uncheckedThumbColor  = Mid,
                    uncheckedTrackColor  = Surf3,
                    uncheckedBorderColor = Mid.copy(alpha = 0.6f),
                ),
            )
        }
        if (help != null) HelpText(help, helpOpen)
    }
}

/**
 * Option picker (Settings, PERF knock scope): the selected option is an accent tint with its label in [actionInk] (dark on
 * DAY) — the chip style used across the app — instead of a solid fill. Every option is a 44 dp chip in
 * a 48 dp control. [fill] spreads the options across the row (stacked rows).
 */
@Composable
fun SegmentedPicker(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    fill: Boolean = false,
) {
    val accent = LocalThemeAccent.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .background(Surf2, RoundedCornerShape(8.dp))
            .border(CardBorder, Brd, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) accent.copy(alpha = pillBgAlpha(0.16f)) else Color.Transparent,
                animationSpec = tween(250), label = "segBg",
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) accent.copy(alpha = borderAlpha(0.6f)) else Color.Transparent,
                animationSpec = tween(250), label = "segBrd",
            )
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgColor, RoundedCornerShape(6.dp))
                    .border(CardBorder, borderColor, RoundedCornerShape(6.dp))
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onSelect(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Wraps (centred) rather than clipping — "MeatPi USB (C3)" must keep its model code.
                Text(
                    option,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = ShareTechMono,
                    color = if (isSelected) actionInk(accent) else Mid,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

/** Settings' picker — the shared [SegmentedPicker]. */
@Composable
private fun SettingsPicker(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    fill: Boolean = false,
) = SegmentedPicker(options, selected, onSelect, fill)


/** Explanatory text under a setting — one size and colour for every note (were 9–10 sp). */
@Composable
private fun SettingsNote(text: String) =
    Text(text, fontSize = 11.sp, color = Dim, fontFamily = ShareTechMono, lineHeight = 15.sp)

private enum class ActionTone { ACCENT, NEUTRAL, DESTRUCTIVE }

/**
 * Full-width settings action: 48 dp, label in [actionInk] on the accent tint. DESTRUCTIVE (forget, stop,
 * exit demo) keeps the orange outline TRIP uses for STOP; NEUTRAL is for unavailable / in-progress states.
 * Status colours (green / orange) no longer stand in for "go" — start / install / grant read as actions.
 */
@Composable
private fun SettingsAction(
    text: String,
    modifier: Modifier = Modifier,
    tone: ActionTone = ActionTone.ACCENT,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = LocalThemeAccent.current
    val shape = RoundedCornerShape(10.dp)
    val (bg, edge, ink) = when {
        !enabled -> Triple(Surf2, Brd, Dim)
        tone == ActionTone.DESTRUCTIVE -> Triple(Orange.copy(alpha = pillBgAlpha(0.08f)), Orange.copy(alpha = borderAlpha(0.45f)), Orange)
        tone == ActionTone.NEUTRAL -> Triple(Surf2, Brd, Mid)
        else -> Triple(accent.copy(alpha = pillBgAlpha(0.12f)), accent.copy(alpha = borderAlpha(0.45f)), actionInk(accent))
    }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(bg, shape)
            .border(CardBorder, edge, shape)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.sp, color = ink, fontFamily = ShareTechMono,
            fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp, textAlign = TextAlign.Center)
    }
}

/** A row that opens something (custom dashboard, Sapphire): title, one line of detail, the action on the right. */
@Composable
private fun SettingsLinkRow(title: String, detail: String, action: String, onClick: () -> Unit) {
    val accent = LocalThemeAccent.current
    Row(
        Modifier.fillMaxWidth()
            .instrumentSurface(radius = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
            Text(detail, fontSize = 11.sp, color = Dim, fontFamily = ShareTechMono)
        }
        Text(action, fontSize = 11.sp, color = actionInk(accent), fontFamily = ShareTechMono,
            fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp, modifier = Modifier.padding(start = 8.dp))
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
            SettingsPicker(
                options = listOf("stable", "beta"),
                selected = updateChannel,
                onSelect = onChannelChange,
            )
        }

        // ── Check for updates button ────────────────────────────────────
        SettingsAction("CHECK FOR UPDATES", Modifier.fillMaxWidth(), enabled = updateState !is UpdateState.Checking) {
            scope.launch { UpdateManager.checkForUpdate(ctx, updateChannel) }
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
                    Text("Checking for updates…", fontSize = 11.sp, color = Mid, fontFamily = ShareTechMono)
                }
            }
            is UpdateState.Available -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "v${state.version.displayName} available" +
                            if (state.isPrerelease) "  (pre-release)" else "",
                        fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.fileSizeBytes > 0) {
                        SettingsNote("%.1f MB".format(state.fileSizeBytes / 1_048_576.0))
                    }
                    if (state.releaseNotes.isNotEmpty()) {
                        Text(
                            state.releaseNotes.take(300) +
                                if (state.releaseNotes.length > 300) "..." else "",
                            fontSize = 11.sp, color = Mid, fontFamily = ShareTechMono,
                            lineHeight = 15.sp,
                        )
                    }

                    val canInstall = ctx.packageManager.canRequestPackageInstalls()
                    if (!canInstall) {
                        // A step to take, not a fault — the note is neutral and the button is the action.
                        SettingsNote("Allow app installs from openRS_ in system settings to continue")
                        SettingsAction("OPEN INSTALL SETTINGS", Modifier.fillMaxWidth()) {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    "package:${ctx.packageName}".toUri(),
                                ),
                            )
                        }
                    } else {
                        SettingsAction("DOWNLOAD", Modifier.fillMaxWidth()) {
                            scope.launch { UpdateManager.downloadUpdate(ctx, state.downloadUrl) }
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
                            fontSize = 11.sp, color = Mid, fontFamily = ShareTechMono,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = accent, trackColor = Brd,
                        )
                        Text(
                            "Downloading... %.1f MB".format(state.bytesDownloaded / 1_048_576.0),
                            fontSize = 11.sp, color = Mid, fontFamily = ShareTechMono,
                        )
                    }
                }
            }
            is UpdateState.ReadyToInstall -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Download complete", fontSize = 12.sp, color = Frost, fontFamily = ShareTechMono,
                        fontWeight = FontWeight.Bold)
                    SettingsAction("INSTALL", Modifier.fillMaxWidth()) { UpdateManager.installApk(ctx, state.apkFile) }
                }
            }
            is UpdateState.Error -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.message, fontSize = 11.sp, color = Orange, fontFamily = ShareTechMono)
                    SettingsNote("Tap CHECK FOR UPDATES to retry")
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


// ── Thermal profile editor ──────────────────────────────────────────────────

private fun thermalCells(p: ThermalProfile, unit: String): Map<String, ThermalCell> =
    ThermalProfile.FIELDS.associate { it.key to ThermalCell.of(it.get(p), unit) }

/** Builds the profile from validated cells (callers check [validateThermalProfile] first). */
private fun thermalProfileFrom(cells: Map<String, ThermalCell>): ThermalProfile =
    ThermalProfile.FIELDS.fold(ThermalProfile.STREET) { p, f -> f.set(p, cells.getValue(f.key).resolvedC ?: f.get(p)) }

/** One block per sensor: name, then warm-up / warning / critical side by side (read-only for built-ins). */
@Composable
private fun ThermalProfileTable(
    cells: Map<String, ThermalCell>, editable: Boolean, errors: Map<String, String>, onEdit: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ThermalRow.entries.forEach { row ->
            val fields = ThermalProfile.FIELDS.filter { it.row == row }
            Column {
                Text(row.label, fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThermalKind.entries.forEach { kind ->
                        val f = fields.firstOrNull { it.kind == kind }
                        Column(Modifier.weight(1f)) {
                            if (f == null) return@Column
                            val cell = cells.getValue(f.key)
                            InstrumentMeta(kind.label)
                            if (editable) {
                                OutlinedTextField(
                                    value = cell.text, onValueChange = { onEdit(f.key, it) },
                                    singleLine = true, isError = f.key in errors,
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${row.label} ${kind.label}" },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = outlinedFieldColors(),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = ShareTechMono, fontSize = 14.sp, color = Frost),
                                )
                            } else {
                                Text(cell.text, fontSize = 15.sp, color = Frost, fontFamily = ShareTechMono,
                                    modifier = Modifier.heightIn(min = 32.dp).padding(top = 6.dp))
                            }
                        }
                    }
                }
                fields.mapNotNull { errors[it.key]?.let { e -> "${it.kind.label}: $e" } }.forEach {
                    Text(it, fontSize = 11.sp, color = textColor(Orange), fontFamily = ShareTechMono, lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}
