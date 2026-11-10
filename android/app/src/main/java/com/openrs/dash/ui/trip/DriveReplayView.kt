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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
    var playbackSpeed by remember { mutableStateOf(1f) }
    // #285: one time base — the playhead is elapsed drive time; the sample shown is the last one recorded at or before it
    val timeline = remember(points) { ReplayTimeline(points.map { it.timestamp }) }
    var position by remember(points) { mutableLongStateOf(0L) }
    val cursorIndex by remember(points) { derivedStateOf { timeline.indexAt(position) } }
    val gap by remember(points) { derivedStateOf { timeline.gapAt(position) } }

    val currentPoint = points[cursorIndex]

    // ── Playback tick: the playhead advances by real time × speed, so a gap plays as long as it lasted ──
    LaunchedEffect(replayState, playbackSpeed) {
        if (replayState != ReplayState.PLAYING) return@LaunchedEffect
        var last = System.nanoTime()
        while (position < timeline.durationMs) {
            delay(100)
            val dtMs = (System.nanoTime() - last) / 1_000_000
            last += dtMs * 1_000_000
            position = timeline.advance(position, dtMs, playbackSpeed)
        }
        // Auto-pause at end
        replayState = ReplayState.PAUSED
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
            // #285: inside a stretch with no stored sample — say so (no cause: a stored drive doesn't record one) and
            // offer to jump to the next sample. Top of the map: the bottom carries the Google logo.
            gap?.let { g ->
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surf2)
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Skip gap") { position = g.toMs }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoText(
                        "NO DATA ${ReplayTimeline.formatClock(g.toMs - g.fromMs)} \u00B7 SKIP GAP",
                        12.sp, Frost, FontWeight.Bold
                    )
                }
            }
        }

        // ── HUD: one readings panel (was eight DataCell cards); boost in the user's unit ──
        // In a gap no reading is current: every value reads "— —" (the marker stays at the last sample).
        Box(Modifier.fillMaxWidth().padding(horizontal = Tokens.PagePad, vertical = 8.dp)) {
            val inGap = gap != null
            fun v(text: String) = if (inGap) "\u2014 \u2014" else text
            val oilText = if (currentPoint.oilTempC > -90)
                "${prefs.displayTemp(currentPoint.oilTempC)}${prefs.tempLabel}" else "\u2014 \u2014"
            val cltText = if (currentPoint.coolantTempC > -90)
                "${prefs.displayTemp(currentPoint.coolantTempC)}${prefs.tempLabel}" else "\u2014 \u2014"
            val boost = prefs.displayBoost(currentPoint.boostPsi / UnitConversions.KPA_TO_PSI + UnitConversions.STD_ATM_KPA)
            ReadoutGrid(
                rows = listOf(
                    listOf(Readout("SPEED", v("${prefs.displaySpeed(currentPoint.speedKph)} ${prefs.speedLabel}")),
                        Readout("RPM", v("${currentPoint.rpm}")),
                        Readout("GEAR", v(currentPoint.gear.ifEmpty { "\u2014 \u2014" })),
                        // #207: the point's own source when recorded (TIP / BOOST); no reading → "— —"; points from
                        // before sources were stored: as recorded, "source not recorded"
                        com.openrs.dash.data.StoredBoost.point(currentPoint).let { b ->
                            Readout(b.label, v(if (b == com.openrs.dash.data.StoredBoost.Unavailable) "\u2014 \u2014" else "${boost.first} ${boost.second}"),
                                sub = if (b is com.openrs.dash.data.StoredBoost.Legacy) "src n/r" else null)
                        }),
                    // #229: a drive saved without this build's 0x0F8 mapping — oil as recorded, said so
                    listOf(Readout("OIL", v(oilText),
                            sub = if (com.openrs.dash.data.OilPtuMapping.isCurrent(drive.oilPtuMapping)) null
                                  else com.openrs.dash.data.OilPtuMapping.UNKNOWN_TAG),
                        Readout("COOLANT", v(cltText)),
                        Readout("LAT G", v("%.2f g".format(currentPoint.lateralG))),
                        Readout("MODE", v(currentPoint.driveMode.uppercase()))),
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
            ReplayScrubber(
                timeline = timeline,
                position = { position },
                accent = accent,
                onScrub = {
                    replayState = ReplayState.PAUSED
                    position = it
                }
            )

            Spacer(Modifier.height(8.dp))

            // Transport buttons: equal shares of the row, so they fit at 320 dp (four 64 dp buttons + spacers did not)
            val seekLabel = if (timeline.hasTimeBase) "10s" else "10"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Row(
                    Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TransportButton(
                        "\u2212$seekLabel",
                        if (timeline.hasTimeBase) "Back 10 seconds" else "Back 10 samples",
                        Modifier.weight(1f)
                    ) { position = timeline.seek(position, -10_000L) }

                    TransportButton(
                        if (replayState == ReplayState.PLAYING) "\u275A\u275A" else "\u25B6",
                        if (replayState == ReplayState.PLAYING) "Pause" else "Play",
                        Modifier.weight(1f),
                        isPrimary = true
                    ) {
                        replayState = when (replayState) {
                            ReplayState.PLAYING -> ReplayState.PAUSED
                            else -> {
                                // If at end, restart from beginning
                                if (position >= timeline.durationMs) position = 0L
                                ReplayState.PLAYING
                            }
                        }
                    }

                    TransportButton(
                        "+$seekLabel",
                        if (timeline.hasTimeBase) "Forward 10 seconds" else "Forward 10 samples",
                        Modifier.weight(1f)
                    ) { position = timeline.seek(position, 10_000L) }

                    TransportButton(
                        "${playbackSpeed.toInt()}x",
                        "Playback speed ${playbackSpeed.toInt()}x",
                        Modifier.weight(1f)
                    ) {
                        playbackSpeed = when (playbackSpeed) {
                            1f -> 2f
                            2f -> 4f
                            else -> 1f
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Slider + elapsed / total labels; reads the playhead through [position] so only this part recomposes per tick. */
@Composable
private fun ReplayScrubber(
    timeline: ReplayTimeline,
    position: () -> Long,
    accent: androidx.compose.ui.graphics.Color,
    onScrub: (Long) -> Unit,
) {
    val pos = position()
    Slider(
        value = pos.toFloat(),
        onValueChange = { onScrub(it.toLong().coerceIn(0L, timeline.durationMs)) },
        valueRange = 0f..maxOf(timeline.durationMs, 1L).toFloat(),
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = Brd
        ),
        modifier = Modifier.fillMaxWidth()
    )

    // Playhead time / drive length (or the sample number when the samples carry no time)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MonoLabel(timeline.positionText(pos), 11.sp, Frost, FontWeight.Medium)
        MonoLabel(timeline.durationText(), 11.sp, Dim)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun TransportButton(
    label: String,
    /** What TalkBack reads (the glyphs mean nothing spoken). */
    name: String,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    val accent = LocalThemeAccent.current
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPrimary) accent else Surf3)
            .border(Tokens.CardBorder, if (isPrimary) accent else Brd, RoundedCornerShape(8.dp))
            .clickable(onClickLabel = name) { onClick() }
            .semantics(mergeDescendants = true) {
                contentDescription = name
                role = Role.Button
            }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        MonoText(
            label, 13.sp,
            if (isPrimary) Bg else Frost,
            FontWeight.Bold
        )
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val fmt = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
    return fmt.format(java.util.Date(epochMs))
}
