package com.openrs.dash.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.PerformanceTimer
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Tokens.CardGap
import com.openrs.dash.ui.Tokens.CardShape
import com.openrs.dash.ui.Tokens.PagePad
import com.openrs.dash.ui.anim.pressClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PERF tab — performance timer + tuning + dynamics surface.
 */
@Composable
fun PerfPage(vs: VehicleState, p: UserPrefs, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = PagePad, end = PagePad, top = PagePad, bottom = PagePad + Tokens.NavBarHeight),
        verticalArrangement = Arrangement.spacedBy(CardGap)
    ) {
        PerformanceTimerCard(vs)
        PersonalBestsCard()
        GForceSection(vs, onReset)
        PowerPageContent(vs, p)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PERSONAL BESTS — lifetime records from persisted perf runs + laps
// ═══════════════════════════════════════════════════════════════════════════

private data class PersonalBests(
    val best60Ms: Long? = null,
    val best100Ms: Long? = null,
    val bestLapMs: Long? = null,
    val totalRuns: Int = 0,
    val totalLaps: Int = 0
)

@Composable
private fun PersonalBestsCard() {
    val dao = OpenRSDashApp.instance.driveDb.driveDao()
    val bests by produceState(PersonalBests()) {
        value = withContext(Dispatchers.IO) {
            PersonalBests(
                best60Ms = dao.getPersonalBest60Ms(),
                best100Ms = dao.getPersonalBest100Ms(),
                bestLapMs = dao.getPersonalBestLapMs(),
                totalRuns = dao.getPerfRunCount(),
                totalLaps = dao.getTotalLapCount()
            )
        }
    }

    // Only show when there's at least one record
    if (bests.totalRuns == 0 && bests.totalLaps == 0) return

    val accent = LocalThemeAccent.current

    Column(
        Modifier.fillMaxWidth()
            .border(Tokens.CardBorder, Brd, CardShape)
            .background(Surf, CardShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("PERSONAL BESTS", 8.sp, Dim, letterSpacing = 0.5.sp)
            Spacer(Modifier.weight(1f))
            val count = bests.totalRuns + bests.totalLaps
            MonoLabel("$count RECORDS", 7.sp, Dim.copy(alpha = 0.6f), letterSpacing = 0.3.sp)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bests.best60Ms?.let { ms ->
                DataCell("0-60 MPH", formatTimerMs(ms), accent, modifier = Modifier.weight(1f))
            }
            bests.best100Ms?.let { ms ->
                DataCell("0-100 MPH", formatTimerMs(ms), accent, modifier = Modifier.weight(1f))
            }
            bests.bestLapMs?.let { ms ->
                DataCell("BEST LAP", formatTimerMs(ms), accent, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PERFORMANCE TIMER — 0-60 / 0-100 mph
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun PerformanceTimerCard(vs: VehicleState) {
    val timerState by PerformanceTimer.state.collectAsState()
    val elapsed by PerformanceTimer.elapsedMs.collectAsState()
    val result by PerformanceTimer.result.collectAsState()
    val best60 by PerformanceTimer.best60Ms.collectAsState()
    val best100 by PerformanceTimer.best100Ms.collectAsState()
    val speedMph by PerformanceTimer.currentSpeedMph.collectAsState()
    val accent = LocalThemeAccent.current
    val haptic = LocalHapticFeedback.current

    val borderColor by animateColorAsState(
        when (timerState) {
            PerformanceTimer.State.ARMED -> accent.copy(alpha = 0.5f)
            PerformanceTimer.State.RUNNING -> Ok.copy(alpha = 0.6f)
            PerformanceTimer.State.FINISHED -> accent.copy(alpha = 0.4f)
            else -> Brd
        },
        tween(300), label = "timerBorder"
    )

    Column(
        Modifier.fillMaxWidth()
            .border(Tokens.CardBorder, borderColor, CardShape)
            .background(Surf, CardShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Header row
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("0-60 / 0-100", 8.sp, Dim, letterSpacing = 0.5.sp)
            Spacer(Modifier.weight(1f))
            // Session best badge
            best60?.let { ms ->
                MonoLabel(
                    "BEST ${formatTimerMs(ms)}",
                    7.sp, accent.copy(alpha = 0.8f), letterSpacing = 0.3.sp
                )
            }
        }

        when (timerState) {
            PerformanceTimer.State.IDLE -> TimerIdleRow(vs.isConnected) {
                if (PerformanceTimer.arm()) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            PerformanceTimer.State.ARMED -> TimerArmedRow {
                PerformanceTimer.cancel()
            }
            PerformanceTimer.State.RUNNING -> TimerRunningRow(elapsed, speedMph) {
                PerformanceTimer.finishAt60()
                // Persist the 60-only result to Room
                val finishResult = PerformanceTimer.result.value
                if (finishResult != null) {
                    val app = com.openrs.dash.OpenRSDashApp.instance
                    val driveId = app.driveRecorder.driveState.value.let { if (it.isRecording) it.driveId else null }
                    Thread {
                        try {
                            app.driveDb.driveDao().insertPerfRun(
                                com.openrs.dash.data.PerfRunEntity(
                                    driveId = driveId,
                                    timestamp = System.currentTimeMillis(),
                                    zeroTo60Ms = finishResult.zeroTo60Ms,
                                    zeroTo100Ms = finishResult.zeroTo100Ms,
                                    peakRpm = finishResult.peakRpm,
                                    peakBoostPsi = finishResult.peakBoostPsi,
                                    launchRpm = finishResult.launchRpm,
                                    ambientTempC = vs.ambientTempC
                                )
                            )
                        } catch (_: Exception) {}
                    }.start()
                }
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            }
            PerformanceTimer.State.FINISHED -> TimerResultRow(result, best60, best100) {
                if (PerformanceTimer.arm()) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
}

@Composable
private fun TimerIdleRow(connected: Boolean, onArm: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .pressClick(enabled = connected) { onArm() }
            .background(if (connected) Surf2 else Surf, RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (connected) "ARM" else "\u2014 CONNECT TO USE \u2014",
            fontFamily = RajdhaniFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            color = if (connected) Frost else Dim
        )
    }
}

@Composable
private fun TimerArmedRow(onCancel: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "armed")
    val pulse by inf.animateFloat(
        0.6f, 1.0f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "armedPulse"
    )
    val accent = LocalThemeAccent.current

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "READY \u2014 FLOOR IT",
            fontFamily = RajdhaniFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 1.sp,
            color = accent,
            modifier = Modifier.alpha(pulse)
        )
        Box(
            Modifier.pressClick(pressedScale = 0.95f) { onCancel() }
                .background(Surf2, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            MonoLabel("CANCEL", 8.sp, Dim)
        }
    }
}

@Composable
private fun TimerRunningRow(elapsedMs: Long, speedMph: Double, onFinish60: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            AnimatedContent(
                targetState = formatTimerMs(elapsedMs),
                transitionSpec = { fadeIn(tween(60)) togetherWith fadeOut(tween(60)) },
                label = "elapsed"
            ) { time ->
                Text(
                    time,
                    fontFamily = RajdhaniFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = Ok,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                )
            }
            MonoLabel("${"%.0f".format(speedMph)} MPH", 9.sp, Dim)
        }
        Box(
            Modifier.pressClick(pressedScale = 0.95f) { onFinish60() }
                .background(Surf2, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            MonoLabel("STOP AT 60", 8.sp, Frost)
        }
    }
}

@Composable
private fun TimerResultRow(
    result: PerformanceTimer.TimerResult?,
    best60: Long?,
    best100: Long?,
    onRearm: () -> Unit
) {
    if (result == null) return
    val accent = LocalThemeAccent.current
    val isBest60 = best60 != null && result.zeroTo60Ms == best60

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Primary: 0-60 result
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                MonoLabel("0-60 MPH", 7.sp, Dim, letterSpacing = 0.3.sp)
                Text(
                    formatTimerMs(result.zeroTo60Ms),
                    fontFamily = RajdhaniFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = if (isBest60) accent else Frost,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                )
            }
            result.zeroTo100Ms?.let { ms ->
                val isBest100 = best100 != null && ms == best100
                Column(horizontalAlignment = Alignment.End) {
                    MonoLabel("0-100 MPH", 7.sp, Dim, letterSpacing = 0.3.sp)
                    Text(
                        formatTimerMs(ms),
                        fontFamily = RajdhaniFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = if (isBest100) accent else Frost,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                    )
                }
            }
        }

        // Stats row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("LAUNCH", "${"%.0f".format(result.launchRpm)} RPM", modifier = Modifier.weight(1f))
            DataCell("PEAK RPM", "${"%.0f".format(result.peakRpm)}", modifier = Modifier.weight(1f))
            DataCell("PEAK BOOST", "${"%.1f".format(result.peakBoostPsi)} PSI", modifier = Modifier.weight(1f))
        }

        // Re-arm button
        Box(
            Modifier.fillMaxWidth()
                .pressClick { onRearm() }
                .background(Surf2, RoundedCornerShape(8.dp))
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            MonoLabel("ARM AGAIN", 8.sp, Frost, letterSpacing = 0.5.sp)
        }
    }
}

private fun formatTimerMs(ms: Long): String {
    val seconds = ms / 1000
    val frac = ms % 1000
    return "%d.%03d".format(seconds, frac)
}
