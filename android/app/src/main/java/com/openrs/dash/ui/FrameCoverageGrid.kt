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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.ui.anim.pressClick

// ═══════════════════════════════════════════════════════════════════════════
// FRAME COVERAGE GRID — colour-coded CAN ID heatmap
// ═══════════════════════════════════════════════════════════════════════════

private enum class FrameFreshness { LIVE, STALE, MISSING, UNKNOWN }

private fun FrameFreshness.color(): Color = when (this) {
    FrameFreshness.LIVE    -> Ok
    FrameFreshness.STALE   -> Warn
    FrameFreshness.MISSING -> Orange
    FrameFreshness.UNKNOWN -> Dim
}

private fun FrameFreshness.label(): String = when (this) {
    FrameFreshness.LIVE    -> "LIVE"
    FrameFreshness.STALE   -> "STALE"
    FrameFreshness.MISSING -> "MISSING"
    FrameFreshness.UNKNOWN -> "UNKNOWN"
}

/** Short human-readable label for a known CAN ID. */
private fun idLabel(id: Int): String = when (id) {
    0x010 -> "STEER"
    0x070 -> "TORQUE"
    0x076 -> "THRTL"
    0x080 -> "PEDAL"
    0x090 -> "RPM"
    0x0C8 -> "GAUGE"
    0x0F8 -> "TEMPS"
    0x130 -> "SPEED"
    0x138 -> "CLUTCH"
    0x160 -> "LNG-G"
    0x180 -> "LAT-G"
    0x190 -> "WHEELS"
    0x1A4 -> "AMBI"
    0x1B0 -> "MODE"
    0x1C0 -> "ESC"
    0x1E0 -> "WHLROT"
    0x225 -> "LAUNCH"
    0x230 -> "GEAR"
    0x252 -> "BRAKE"
    0x260 -> "BTNS"
    0x2C0 -> "AWD"
    0x2F0 -> "COOL"
    0x305 -> "MDEBTN"
    0x340 -> "PCMAMB"
    0x360 -> "ODO"
    0x380 -> "FUEL"
    0x40A -> "VIN"
    0x420 -> "MDEEXT"
    else  -> ""
}

private data class TileData(
    val canId: Int,
    val freshness: FrameFreshness,
    val frameCount: Long,
    val info: DiagnosticLogger.FrameInfo?
)

@Composable
fun FrameCoverageGrid(
    inventory: Map<Int, DiagnosticLogger.FrameInfo>,
    knownIds: Set<Int>,
    sessionStartMs: Long
) {
    val accent = LocalThemeAccent.current
    val now = System.currentTimeMillis()
    val sessionSec = ((now - sessionStartMs).coerceAtLeast(1000L)) / 1000.0

    // Build tile list: known IDs (including missing ones) + unknown IDs
    val knownTiles = knownIds.sorted().map { id ->
        val info = inventory[id]
        val freshness = when {
            info == null -> FrameFreshness.MISSING
            info.lastSeenMs > 0 && now - info.lastSeenMs < 5_000 -> FrameFreshness.LIVE
            info.lastSeenMs > 0 -> FrameFreshness.STALE
            else -> FrameFreshness.MISSING
        }
        TileData(id, freshness, info?.totalReceived ?: 0, info)
    }

    val unknownTiles = inventory.entries
        .filter { it.key !in knownIds }
        .sortedBy { it.key }
        .map { (id, info) ->
            TileData(id, FrameFreshness.UNKNOWN, info.totalReceived, info)
        }

    var expandedId by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth()) {
        // ── Legend ──
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FrameFreshness.entries.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .background(f.color().copy(alpha = 0.3f), Tokens.CardShape)
                            .border(Tokens.CardBorder, f.color().copy(alpha = 0.6f), Tokens.CardShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        MonoLabel(f.label(), 8.sp, f.color())
                    }
                }
            }
        }

        // ── Known IDs grid ──
        val liveCount = knownTiles.count { it.freshness == FrameFreshness.LIVE }
        val totalKnown = knownTiles.size
        MonoLabel("DECODED ($liveCount/$totalKnown active)", 9.sp, Dim,
            modifier = Modifier.padding(bottom = 4.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .height((((knownTiles.size + 3) / 4) * 68).dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(knownTiles, key = { it.canId }) { tile ->
                FrameTile(tile, sessionSec, tile.canId == expandedId, accent) {
                    expandedId = if (expandedId == tile.canId) null else tile.canId
                }
            }
        }

        // ── Expanded detail ──
        val expandedTile = (knownTiles + unknownTiles).find { it.canId == expandedId }
        AnimatedVisibility(
            visible = expandedTile != null,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            expandedTile?.let { tile ->
                FrameDetail(tile, sessionSec, accent)
            }
        }

        // ── Unknown IDs ──
        if (unknownTiles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            MonoLabel("UNKNOWN (${unknownTiles.size} IDs)", 9.sp, Dim,
                modifier = Modifier.padding(bottom = 4.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .height((((unknownTiles.size + 3) / 4) * 68).dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(unknownTiles, key = { it.canId }) { tile ->
                    FrameTile(tile, sessionSec, tile.canId == expandedId, accent) {
                        expandedId = if (expandedId == tile.canId) null else tile.canId
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameTile(
    tile: TileData,
    sessionSec: Double,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val fc = tile.freshness.color()
    val borderColor = if (selected) accent else fc.copy(alpha = 0.4f)
    val bgColor = if (selected) Surf3 else Surf2
    val fps = if (sessionSec > 1.0) tile.frameCount / sessionSec else 0.0
    val label = idLabel(tile.canId)

    Column(
        Modifier
            .background(bgColor, Tokens.CardShape)
            .border(Tokens.CardBorder, borderColor, Tokens.CardShape)
            .pressClick(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        MonoLabel("0x%03X".format(tile.canId), 10.sp, fc)
        if (label.isNotEmpty()) {
            MonoLabel(label, 7.sp, Dim)
        }
        if (tile.freshness == FrameFreshness.MISSING) {
            MonoLabel("—", 9.sp, Dim)
        } else {
            MonoLabel("×${formatCount(tile.frameCount)}", 8.sp, Mid)
            if (fps >= 0.5) MonoLabel("${"%.0f".format(fps)} fps", 7.sp, Dim)
        }
    }
}

@Composable
private fun FrameDetail(tile: TileData, sessionSec: Double, accent: Color) {
    val info = tile.info ?: return
    val fps = if (sessionSec > 1.0) tile.frameCount / sessionSec else 0.0
    val label = idLabel(tile.canId)
    val title = if (label.isNotEmpty()) "0x%03X — $label".format(tile.canId)
                else "0x%03X".format(tile.canId)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(Surf2, Tokens.CardShape)
            .border(Tokens.CardBorder, Brd, Tokens.CardShape)
            .padding(Tokens.InnerV)
    ) {
        MonoLabel(title, 10.sp, accent)
        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoLabel("COUNT", 8.sp, Dim)
            MonoLabel("${tile.frameCount}", 9.sp, Frost)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoLabel("FPS", 8.sp, Dim)
            MonoLabel("%.1f".format(fps), 9.sp, Frost)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoLabel("STATUS", 8.sp, Dim)
            MonoLabel(tile.freshness.label(), 9.sp, tile.freshness.color())
        }

        if (info.firstRawHex.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            MonoLabel("FIRST", 8.sp, Dim)
            MonoLabel(info.firstRawHex, 8.sp, Mid)
        }
        if (info.lastRawHex.isNotEmpty() && info.hasChanged) {
            MonoLabel("LAST", 8.sp, Dim)
            MonoLabel(info.lastRawHex, 8.sp, Mid)
        }
        if (info.lastDecoded.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            MonoLabel("DECODED", 8.sp, Dim)
            MonoLabel(info.lastDecoded, 8.sp, Mid)
        }
        if (info.validationIssues.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            info.validationIssues.forEach { issue ->
                MonoLabel("⚠ $issue", 8.sp, Warn)
            }
        }
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
    n >= 10_000    -> "${"%.0f".format(n / 1_000.0)}K"
    n >= 1_000     -> "${"%.1f".format(n / 1_000.0)}K"
    else           -> "$n"
}
