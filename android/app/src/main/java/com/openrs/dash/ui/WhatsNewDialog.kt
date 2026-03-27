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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openrs.dash.BuildConfig

/**
 * Version highlights for the "What's New" dialog.
 * Key = versionName (e.g. "2.2.5"), Value = list of user-facing highlights.
 */
private val versionHighlights: Map<String, List<String>> = mapOf(
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
    val version = BuildConfig.VERSION_NAME
    val highlights = versionHighlights[version] ?: return onDismiss()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .background(Bg, RoundedCornerShape(12.dp))
                .border(1.dp, Brd, RoundedCornerShape(12.dp))
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
                highlights.forEach { highlight ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Surf2, RoundedCornerShape(8.dp))
                            .border(1.dp, Brd, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("●", fontSize = 8.sp, color = accent,
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
