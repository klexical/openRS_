package com.openrs.dash.ui.trip

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.rememberCameraPositionState
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.ui.*
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE REPLAY VIEW — full-screen drive playback with synchronized telemetry
// ═══════════════════════════════════════════════════════════════════════════

private enum class ReplayState { IDLE, PLAYING, PAUSED }

@Composable
fun DriveReplayView(
    drive: DriveEntity,
    points: List<DrivePointEntity>,
    prefs: UserPrefs,
    onDismiss: () -> Unit
) {
    if (points.isEmpty()) {
        onDismiss()
        return
    }

    val accent = LocalThemeAccent.current
    val cameraPositionState = rememberCameraPositionState()

    var replayState by remember { mutableStateOf(ReplayState.IDLE) }
    var cursorIndex by remember { mutableIntStateOf(0) }
    var playbackSpeed by remember { mutableStateOf(1f) }

    val currentPoint = points[cursorIndex]
    val startTime = points.first().timestamp
    val endTime = points.last().timestamp

    // ── Playback tick ────────────────────────────────────────────────────
    LaunchedEffect(replayState, playbackSpeed) {
        if (replayState == ReplayState.PLAYING) {
            while (cursorIndex < points.size - 1) {
                delay((1000L / playbackSpeed).toLong())
                cursorIndex++
            }
            // Auto-pause at end
            replayState = ReplayState.PAUSED
        }
    }

    // ── Camera follow during playback ────────────────────────────────────
    LaunchedEffect(cursorIndex) {
        if (replayState == ReplayState.PLAYING) {
            val pt = points[cursorIndex]
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLng(LatLng(pt.lat, pt.lng)),
                300
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        // ── Header (drive name + close button) ───────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.PagePad, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                MonoLabel("REPLAY", 9.sp, Dim)
                MonoText(
                    drive.name ?: formatTimestamp(drive.startTime),
                    13.sp, Frost, FontWeight.Bold
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surf2)
                    .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoText("X", 12.sp, Mid, FontWeight.Bold)
            }
        }

        // ── Map area ─────────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            DriveMap(
                points = points.subList(0, (cursorIndex + 1).coerceAtMost(points.size)),
                colorMode = ColorMode.SPEED,
                currentLat = currentPoint.lat,
                currentLng = currentPoint.lng,
                isRecording = true,  // enables position marker + camera follow
                cameraPositionState = cameraPositionState
            )
        }

        // ── HUD strip ────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(Surf)
                .padding(horizontal = Tokens.PagePad, vertical = 8.dp)
        ) {
            // Row 1: SPD, RPM, GEAR, BOOST
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DataCell(
                    "SPD",
                    "${prefs.displaySpeed(currentPoint.speedKph)} ${prefs.speedLabel}",
                    modifier = Modifier.weight(1f)
                )
                DataCell(
                    "RPM",
                    "${currentPoint.rpm}",
                    modifier = Modifier.weight(1f)
                )
                DataCell(
                    "GEAR",
                    currentPoint.gear.ifEmpty { "-" },
                    modifier = Modifier.weight(1f)
                )
                DataCell(
                    "BOOST",
                    "%.1f PSI".format(currentPoint.boostPsi),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(4.dp))

            // Row 2: OIL, CLT, G-LAT, MODE
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val oilText = if (currentPoint.oilTempC > -90)
                    "${prefs.displayTemp(currentPoint.oilTempC)}${prefs.tempLabel}" else "-"
                DataCell("OIL", oilText, modifier = Modifier.weight(1f))

                val cltText = if (currentPoint.coolantTempC > -90)
                    "${prefs.displayTemp(currentPoint.coolantTempC)}${prefs.tempLabel}" else "-"
                DataCell("CLT", cltText, modifier = Modifier.weight(1f))

                DataCell(
                    "G-LAT",
                    "%.2f".format(currentPoint.lateralG),
                    modifier = Modifier.weight(1f)
                )
                DataCell(
                    "MODE",
                    currentPoint.driveMode,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── Controls ─────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(Surf2)
                .padding(horizontal = Tokens.PagePad, vertical = 8.dp)
        ) {
            // Slider
            Slider(
                value = cursorIndex.toFloat(),
                onValueChange = {
                    replayState = ReplayState.PAUSED
                    cursorIndex = it.toInt().coerceIn(0, points.size - 1)
                },
                valueRange = 0f..(points.size - 1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = Brd
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Elapsed / total time labels
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MonoLabel(
                    formatDuration(currentPoint.timestamp - startTime),
                    9.sp, Mid
                )
                MonoLabel(
                    formatDuration(endTime - startTime),
                    9.sp, Dim
                )
            }

            Spacer(Modifier.height(8.dp))

            // Transport buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rewind 10s
                TransportButton("\u25C0") {
                    cursorIndex = (cursorIndex - 10).coerceAtLeast(0)
                }

                Spacer(Modifier.width(16.dp))

                // Play / Pause
                TransportButton(
                    if (replayState == ReplayState.PLAYING) "\u275A\u275A" else "\u25B6",
                    isPrimary = true
                ) {
                    replayState = when (replayState) {
                        ReplayState.PLAYING -> ReplayState.PAUSED
                        else -> {
                            // If at end, restart from beginning
                            if (cursorIndex >= points.size - 1) cursorIndex = 0
                            ReplayState.PLAYING
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Forward 10s
                TransportButton("\u25B6") {
                    cursorIndex = (cursorIndex + 10).coerceAtMost(points.size - 1)
                }

                Spacer(Modifier.width(24.dp))

                // Speed cycle
                TransportButton("${playbackSpeed.toInt()}x") {
                    playbackSpeed = when (playbackSpeed) {
                        1f -> 2f
                        2f -> 4f
                        else -> 1f
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun TransportButton(
    label: String,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    val accent = LocalThemeAccent.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPrimary) accent else Surf3)
            .border(Tokens.CardBorder, if (isPrimary) accent else Brd, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        MonoText(
            label, 13.sp,
            if (isPrimary) Bg else Frost,
            FontWeight.Bold
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatTimestamp(epochMs: Long): String {
    val fmt = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
    return fmt.format(java.util.Date(epochMs))
}
