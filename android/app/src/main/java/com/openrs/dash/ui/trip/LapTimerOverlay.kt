package com.openrs.dash.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.LapTimer
import com.openrs.dash.data.LapTimerState
import com.openrs.dash.ui.Brd
import com.openrs.dash.ui.DataNum
import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.Label
import com.openrs.dash.ui.LocalThemeAccent
import com.openrs.dash.ui.MonoLabel
import com.openrs.dash.ui.MonoText
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.Tokens
import com.openrs.dash.ui.UserPrefs
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════════
// LAP TIMER OVERLAY — live lap counter + lap list for the TRIP tab
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun LapTimerOverlay(
    lapTimer: LapTimer,
    prefs: UserPrefs,
    modifier: Modifier = Modifier
) {
    // Tick counter drives recomposition for the live elapsed display
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(lapTimer.state, lapTimer.currentLapStartMs) {
        while (lapTimer.state == LapTimerState.TIMING) {
            elapsedMs = lapTimer.currentLapElapsedMs()
            delay(100L)
        }
        elapsedMs = 0L
    }

    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surf.copy(alpha = 0.88f))
            .border(Tokens.CardBorder, Brd, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Current lap timer ────────────────────────────────────────
        val timerColor = when (lapTimer.state) {
            LapTimerState.TIMING -> Frost
            else -> Dim
        }
        val displayMs = if (lapTimer.state == LapTimerState.TIMING) elapsedMs else 0L
        DataNum(formatLapTime(displayMs), 28.sp, timerColor)

        // ── Delta vs best ────────────────────────────────────────────
        val delta = lapTimer.deltaVsBestMs()
        if (delta != null) {
            val deltaSign = if (delta >= 0) "+" else ""
            val deltaColor = if (delta <= 0) Ok else Orange
            val deltaSec = delta / 1000.0
            MonoLabel(
                "${deltaSign}${"%.1f".format(deltaSec)}s",
                11.sp, deltaColor, FontWeight.Bold
            )
        }

        // ── State label (armed / idle) ───────────────────────────────
        if (lapTimer.state == LapTimerState.ARMED) {
            Spacer(Modifier.height(2.dp))
            Label("ARMED", 9.sp, Dim)
        }

        // ── Lap list ─────────────────────────────────────────────────
        if (lapTimer.laps.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                lapTimer.laps.forEach { lap ->
                    val isBest = lap.lapTimeMs == lapTimer.bestLapMs
                    val lapColor = if (isBest) Ok else Dim
                    MonoLabel(
                        "L${lap.lapNumber}  ${formatLapTime(lap.lapTimeMs)}  " +
                                "RPM:${lap.peakRpm}  B:${"%.1f".format(lap.peakBoostPsi)}",
                        8.sp, lapColor
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SET S/F BUTTON — pill control matching the map floating controls
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun SetStartFinishButton(
    isSet: Boolean,
    onSet: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalThemeAccent.current
    val label = if (isSet) "CLEAR S/F" else "SET S/F"
    val color = if (isSet) Orange else accent

    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Surf.copy(alpha = 0.85f))
            .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
            .clickable { if (isSet) onClear() else onSet() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        MonoText(label, 10.sp, color, FontWeight.Bold)
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────

/** Format milliseconds as M:SS.s (one decimal). */
private fun formatLapTime(ms: Long): String {
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    val tenths = (ms % 1000) / 100
    return "$minutes:${"%02d".format(seconds)}.${tenths}"
}
