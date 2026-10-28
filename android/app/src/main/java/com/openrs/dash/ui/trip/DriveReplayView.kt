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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
    onDismiss: () -> Unit,
    /** Debug TRIP preview: a sample drive — label the replay. */
    previewNotice: Boolean = false
) {
    if (points.isEmpty()) {
        onDismiss()
        return
    }

    val accent = LocalThemeAccent.current
    // Open on the route's start (DriveMap's auto-centre ran before the map was ready, leaving a world view)
    val cameraPositionState = rememberCameraPositionState {
        position = com.google.android.gms.maps.model.CameraPosition.fromLatLngZoom(
            LatLng(points.first().lat, points.first().lng), 15f)
    }
    // Back closes the replay (it fell through to the activity)
    androidx.activity.compose.BackHandler(onBack = onDismiss)

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
            // The page sits under the bottom nav overlay — the transport row was hidden behind it.
            .padding(bottom = navBarClearance())
    ) {
        // ── Header (drive name + close button) ───────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.PagePad, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                InstrumentLabel("REPLAY", color = Mid)
                UIText(drive.name ?: formatTimestamp(drive.startTime), 16.sp, Frost, FontWeight.SemiBold)
            }
            // 48 dp close target around a compact chip
            Box(
                Modifier.size(48.dp).clickable(onClickLabel = "Close replay") { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surf2)
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) { MonoText("\u2715", 14.sp, Mid, FontWeight.Bold) }
            }
        }

        if (previewNotice) Box(Modifier.padding(horizontal = Tokens.PagePad)) { PreviewSheetNotice() }

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

        // ── HUD: one readings panel (was eight DataCell cards); boost in the user's unit ──
        Box(Modifier.fillMaxWidth().padding(horizontal = Tokens.PagePad, vertical = 8.dp)) {
            val oilText = if (currentPoint.oilTempC > -90)
                "${prefs.displayTemp(currentPoint.oilTempC)}${prefs.tempLabel}" else "— —"
            val cltText = if (currentPoint.coolantTempC > -90)
                "${prefs.displayTemp(currentPoint.coolantTempC)}${prefs.tempLabel}" else "— —"
            val boost = prefs.displayBoost(currentPoint.boostPsi / UnitConversions.KPA_TO_PSI + UnitConversions.STD_ATM_KPA)
            ReadoutGrid(
                rows = listOf(
                    listOf(Readout("SPEED", "${prefs.displaySpeed(currentPoint.speedKph)} ${prefs.speedLabel}"),
                        Readout("RPM", "${currentPoint.rpm}"),
                        Readout("GEAR", currentPoint.gear.ifEmpty { "— —" }),
                        Readout("BOOST", "${boost.first} ${boost.second}")),
                    listOf(Readout("OIL", oilText), Readout("COOLANT", cltText),
                        Readout("LAT G", "%.2f g".format(currentPoint.lateralG)),
                        Readout("MODE", currentPoint.driveMode.uppercase())),
                ),
                dense = true
            )
        }

        // ── Controls ─────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.PagePad, vertical = 4.dp)
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
                MonoLabel(formatDuration(currentPoint.timestamp - startTime), 11.sp, Frost, FontWeight.Medium)
                MonoLabel(formatDuration(endTime - startTime), 11.sp, Dim)
            }

            Spacer(Modifier.height(8.dp))

            // Transport buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rewind 10 samples (~10 s) — labelled; "◀" read as "back"
                TransportButton("\u221210s") {
                    cursorIndex = seekIndex(points, cursorIndex, -10_000L)
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

                // Forward 10 samples (~10 s) — "▶" duplicated the play glyph
                TransportButton("+10s") {
                    cursorIndex = seekIndex(points, cursorIndex, 10_000L)
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
            .sizeIn(minWidth = 64.dp, minHeight = 48.dp)
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

/**
 * Index of the point nearest [deltaMs] from [from] by timestamp. The ±10s buttons stepped 10 samples, which is
 * 10 s only at the recorder's 1 Hz — on sparser data (resumed drives, gaps) the label was wrong.
 */
internal fun seekIndex(points: List<DrivePointEntity>, from: Int, deltaMs: Long): Int {
    if (points.isEmpty()) return 0
    val target = points[from].timestamp + deltaMs
    var i = from
    if (deltaMs > 0) { while (i < points.lastIndex && points[i + 1].timestamp <= target) i++ }
    else { while (i > 0 && points[i - 1].timestamp >= target) i-- }
    // Always move at least one sample so the button never does nothing
    return when {
        i == from && deltaMs > 0 -> (from + 1).coerceAtMost(points.lastIndex)
        i == from && deltaMs < 0 -> (from - 1).coerceAtLeast(0)
        else -> i
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
