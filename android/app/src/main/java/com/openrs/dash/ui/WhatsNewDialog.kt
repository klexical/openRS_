package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.openrs.dash.ui.Tokens.CardBorder
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openrs.dash.BuildConfig
import com.openrs.dash.ui.anim.pageEntrance

/**
 * Version highlights for the "What's New" dialog.
 * Key = versionName (e.g. "2.2.5"), Value = list of user-facing highlights.
 */
private val versionHighlights: Map<String, List<String>> = mapOf(
    "2.2.7" to listOf(
        "\"Daylight\" UI overhaul — 5 tabs (DRIVE/PERF/THERMAL/TRIP/GARAGE) and a true light/dark theme",
        "Theme picker — NIGHT / DAY / AUTO + ULTRA pure-black AMOLED option (replaces the brightness slider)",
        "Rajdhani typography with tabular numerals — calmer, no-shimmy hero values",
        "Field availability sub-labels — WARMING / STALE / N/A surfaced on temps, battery, and TPMS",
        "Settings overhauled — categorized navigator with fuzzy search and per-setting help",
        "Drive-mode change haptics + status-bar MODE pill pulses while a command is in flight",
        "Long-press hero cards on DRIVE — flips BOOST/RPM/SPEED to session peak for 3s",
        "DTC badge on the GARAGE tab — see active codes from any page",
        "Drag-to-reorder on Custom Dashboard — long-press the arrow column to drag",
        "TripPage SATELLITE default + select-card animates to reveal SHARE/RENAME",
        "Adaptive zoom on DRIVE — gear inflates and the page auto-zooms above 16 kph",
        "Fixed: Auto-record no longer starts when only WiFi is connected — gates on RPM > 400 with seamless pause/resume",
        "Fixed: BLE scan timeout extended + filterless fallback to find stubborn adapters",
        "Fixed: Battery voltage now shows 2 decimals everywhere (14.74 vs 14.7)",
        "Fixed: PTU/RDU no longer visually stack on the chassis diagram",
        "Performance: SLCAN parser, glow modifiers, ShiftLightBar, transports — significant allocation churn removed in the hot paths"
    ),
    "2.2.6" to listOf(
        "Fuel trim drift tracker — short/long-term fuel trims (STFT/LTFT) on POWER tab FUEL section",
        "AWD clutch hydraulics — left/right actuator current and hydraulic pressure on CHASSIS tab",
        "Battery current monitoring — live charging/discharging amps on DIAG tab",
        "Live button input feedback — drive mode, suspension, ASS, and ESC defeat buttons on DIAG tab",
        "Critical fix — several Mode 22 PIDs were silently dropped before reaching the UI; now resolved",
        "Bottom nav bar — frosted glass with vector icons, spring-animated indicator, edge-to-edge",
        "Compact status bar — MODE/ESC pills, connection dot, settings gear in a single row",
        "MAP tab with Google Maps — live color-coded routes (6 modes), drive history, peak markers",
        "BLE transport — connect over Bluetooth Low Energy, freeing WiFi for internet",
        "Brightness control — Night/Day/Sun presets + slider for any lighting condition",
        "G-Force Plot redesign — rectangular grid, auto-scaling axes, 120-dot trail, peak labels",
        "Quick Mode Dock — tap MODE pill to change drive modes from any tab",
        "In-app updates — check and install new versions directly from the app",
        "0-60 / 0-100 performance timer — arm from MORE tab, times via CAN speed at ~100 Hz",
        "Real-time fuel economy — instant/average MPG or L/100km plus distance to empty",
        "Configurable TPMS thresholds — low/warn/high pressure with 4-zone color coding",
        "Passive VIN decode — 17-char VIN assembled from CAN 0x40A, shown on MORE tab"
    ),
    "2.2.5" to listOf(
        "FORScan PID catalog — 1,149 PIDs across 8 ECU modules, browsable on DIAG tab",
        "DID Prober — interactive Mode 22 scanner for any ECU + DID range",
        "Drive mode & ESC control — tap to change via openRS-FW firmware",
        "Session history — view past sessions with peak metrics on MORE tab",
        "Custom dashboard builder — create your own gauge layout",
        "Neon Connect chassis layout — tire cards with AWD torque visualization",
        "Per-cylinder knock correction — KR for all 4 cylinders on POWER tab",
        "AWD expansion — clutch temps, requested torque, pump current",
        "Trip recorder with GPX, CSV, and Mission Control HTML export",
        "Crash history on DIAG tab — view and clear crash reports",
        "DID probe results included in diagnostic ZIP exports",
        "Drive mode cold-start reliability improvements",
        "Settings panel visual overhaul — blue-tinted cockpit aesthetic",
        "Sapphire web dashboard — analyse trip & diagnostic data in your browser"
    )
)

@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    val accent = LocalThemeAccent.current
    val accentM = accentMid()
    val version = BuildConfig.VERSION_NAME
    val highlights = versionHighlights[version]
        ?: versionHighlights.values.lastOrNull()
        ?: return onDismiss()

    var dialogEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { dialogEntered = true }
    val dialogAlpha by animateFloatAsState(if (dialogEntered) 1f else 0f, tween(200), label = "dlgA")
    val dialogScale by animateFloatAsState(if (dialogEntered) 1f else 0.95f, tween(200), label = "dlgS")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .graphicsLayer { alpha = dialogAlpha; scaleX = dialogScale; scaleY = dialogScale }
                .fillMaxWidth(0.92f)
                .background(Bg, RoundedCornerShape(12.dp))
                .border(CardBorder, Brd, RoundedCornerShape(12.dp))
        ) {
            // Title bar
            Row(
                Modifier.fillMaxWidth()
                    .background(Surf3, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("What's New", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        fontFamily = ShareTechMono, color = Frost)
                    Text(" — v$version", fontSize = 14.sp, fontFamily = ShareTechMono, color = Dim)
                }
                Text("✕", fontSize = 18.sp, color = Dim, modifier = Modifier.clickable { onDismiss() })
            }

            // Highlights list
            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                highlights.forEachIndexed { index, highlight ->
                    Row(
                        Modifier.fillMaxWidth()
                            .then(pageEntrance(index, dialogEntered, staggerDelayMs = 60))
                            .background(Surf2, RoundedCornerShape(8.dp))
                            .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("●", fontSize = 8.sp, color = accentM,
                            modifier = Modifier.padding(top = 3.dp))
                        Text(highlight, fontSize = 11.sp, color = Frost,
                            fontFamily = ShareTechMono, lineHeight = 16.sp)
                    }
                }
            }

            // Dismiss button
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = OnAccent)
                ) {
                    Text("GOT IT", fontFamily = ShareTechMono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
