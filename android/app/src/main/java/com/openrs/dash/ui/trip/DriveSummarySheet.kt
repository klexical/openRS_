package com.openrs.dash.ui.trip

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.ui.*
import com.openrs.dash.ui.anim.Sparkline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE SUMMARY SHEET — post-drive stats bottom sheet (#177)
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveSummarySheet(
    drive: DriveEntity,
    points: List<DrivePointEntity> = emptyList(),
    prefs: UserPrefs,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val accent = LocalThemeAccent.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surf,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(
                Modifier.padding(vertical = 10.dp)
                    .height(4.dp)
                    .fillMaxWidth(0.12f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brd)
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // ── Title + Duration badge ──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoText("DRIVE SUMMARY", 13.sp, Frost, FontWeight.Bold)
                val durationMs = if (drive.endTime > 0) drive.endTime - drive.startTime else 0L
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    MonoLabel(formatDurationCompact(durationMs), 9.sp, accent)
                }
            }

            Spacer(Modifier.height(4.dp))
            NeonDivider()
            Spacer(Modifier.height(8.dp))

            // ── Row 1: Duration · Distance · Fuel Used · Economy ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val durationMs = if (drive.endTime > 0) drive.endTime - drive.startTime else 0L
                DataCell("DURATION", formatDurationCompact(durationMs), modifier = Modifier.weight(1f))

                val dist = if (prefs.speedUnit == "MPH")
                    "%.1f mi".format(drive.distanceKm * 0.621371)
                else "%.1f km".format(drive.distanceKm)
                DataCell("DISTANCE", dist, modifier = Modifier.weight(1f))

                DataCell("FUEL", "%.2f L".format(drive.fuelUsedL), modifier = Modifier.weight(1f))

                val econ = if (drive.distanceKm > 0.1 && drive.fuelUsedL > 0) {
                    if (prefs.speedUnit == "MPH") {
                        val mpg = 282.48 / (drive.fuelUsedL / drive.distanceKm * 100.0)
                        "%.1f MPG".format(mpg)
                    } else {
                        "%.1f L/100".format(drive.fuelUsedL / drive.distanceKm * 100.0)
                    }
                } else "—"
                DataCell("ECONOMY", econ, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(6.dp))
            NeonDivider()
            Spacer(Modifier.height(6.dp))

            // ── Row 2: Peak Speed · Peak RPM · Peak Boost · Peak G ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val spd = "${prefs.displaySpeed(drive.maxSpeedKph)} ${prefs.speedLabel}"
                DataCell("PEAK SPEED", spd, modifier = Modifier.weight(1f))
                DataCell("PEAK RPM", "${drive.peakRpm}", modifier = Modifier.weight(1f))
                DataCell("PEAK BOOST", "%.1f PSI".format(drive.peakBoostPsi), modifier = Modifier.weight(1f))
                DataCell("PEAK G", "%.2f".format(drive.peakLateralG), modifier = Modifier.weight(1f))
            }

            // ── Thermal progression ──
            if (drive.startOilTempC > -90 || drive.peakOilTempC > -90 ||
                drive.startCoolantTempC > -90 || drive.peakCoolantTempC > -90) {
                Spacer(Modifier.height(6.dp))
                NeonDivider()
                Spacer(Modifier.height(6.dp))

                MonoLabel("THERMAL PROGRESSION", 9.sp, Dim,
                    modifier = Modifier.padding(bottom = 4.dp))

                // Oil temp row
                if (drive.startOilTempC > -90 || drive.peakOilTempC > -90) {
                    ThermalRow(
                        label = "OIL",
                        startC = drive.startOilTempC,
                        peakC = drive.peakOilTempC,
                        endC = drive.endOilTempC,
                        prefs = prefs
                    )
                }

                // Coolant temp row
                if (drive.startCoolantTempC > -90 || drive.peakCoolantTempC > -90) {
                    ThermalRow(
                        label = "CLT",
                        startC = drive.startCoolantTempC,
                        peakC = drive.peakCoolantTempC,
                        endC = drive.endCoolantTempC,
                        prefs = prefs
                    )
                }
            }

            // ── Drive mode breakdown ──
            val modeBreakdown = parseModeBreakdown(drive.driveModeBreakdown)
            if (modeBreakdown.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                NeonDivider()
                Spacer(Modifier.height(6.dp))

                MonoLabel("DRIVE MODE BREAKDOWN", 9.sp, Dim,
                    modifier = Modifier.padding(bottom = 4.dp))

                // Segmented bar
                Row(
                    Modifier.fillMaxWidth().height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(4.dp))
                ) {
                    modeBreakdown.forEach { (mode, fraction) ->
                        Box(
                            Modifier.weight(fraction.coerceAtLeast(0.01f))
                                .height(16.dp)
                                .background(modeColor(mode))
                        )
                    }
                }

                // Legend
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    modeBreakdown.forEach { (mode, fraction) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.height(8.dp).fillMaxWidth(0f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(modeColor(mode))
                                    .padding(horizontal = 4.dp)
                            )
                            MonoLabel(
                                "$mode ${(fraction * 100).toInt()}%",
                                8.sp, Mid
                            )
                        }
                    }
                }
            }

            // ── Trends across recent drives ──
            DriveTrendsSection()

            // ── Time-series chart ──
            if (points.size >= 2) {
                Spacer(Modifier.height(6.dp))
                NeonDivider()
                Spacer(Modifier.height(6.dp))

                MonoLabel("TELEMETRY", 9.sp, Dim,
                    modifier = Modifier.padding(bottom = 4.dp))
                DriveTimeSeries(points = points)
            }

            Spacer(Modifier.height(12.dp))
            NeonDivider()
            Spacer(Modifier.height(12.dp))

            // ── Action buttons ──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent)
                        .clickable { onShare() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoText("SHARE", 12.sp, Bg, FontWeight.Bold)
                }
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surf2)
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoText("DISMISS", 12.sp, Mid, FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ThermalRow(
    label: String,
    startC: Double,
    peakC: Double,
    endC: Double,
    prefs: UserPrefs
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Surf2, Tokens.CardShape)
            .border(Tokens.CardBorder, Brd, Tokens.CardShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonoText(label, 10.sp, Frost, FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TempStage("START", startC, prefs)
            MonoLabel("\u2192", 10.sp, Dim)
            TempStage("PEAK", peakC, prefs, isPeak = true)
            MonoLabel("\u2192", 10.sp, Dim)
            TempStage("END", endC, prefs)
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TempStage(
    label: String,
    tempC: Double,
    prefs: UserPrefs,
    isPeak: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MonoLabel(label, 7.sp, Dim)
        val text = if (tempC > -90) "${prefs.displayTemp(tempC)}${prefs.tempLabel}" else "—"
        MonoText(text, 10.sp, if (isPeak) Warn else Mid, FontWeight.Bold)
    }
}

private fun parseModeBreakdown(json: String): List<Pair<String, Float>> {
    if (json.isBlank() || json == "{}") return emptyList()
    return try {
        val obj = JSONObject(json)
        obj.keys().asSequence().map { key ->
            key to obj.getDouble(key).toFloat()
        }.sortedByDescending { it.second }.toList()
    } catch (_: Exception) { emptyList() }
}

private fun modeColor(mode: String) = when (mode.uppercase()) {
    "NORMAL"  -> Ok
    "SPORT"   -> Warn
    "TRACK"   -> Orange
    "DRIFT"   -> Orange
    else      -> Mid
}

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE TRENDS — sparklines across recent drives
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun DriveTrendsSection() {
    val recentDrives by produceState<List<DriveEntity>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            OpenRSDashApp.instance.driveDb.driveDao().getRecentDrives(20)
                .filter { it.endTime > 0 }
                .reversed() // oldest first for sparkline
        }
    }
    if (recentDrives.size < 3) return

    Spacer(Modifier.height(6.dp))
    NeonDivider()
    Spacer(Modifier.height(6.dp))

    MonoLabel("TRENDS (LAST ${recentDrives.size} DRIVES)", 9.sp, Dim,
        modifier = Modifier.padding(bottom = 4.dp))

    val accent = LocalThemeAccent.current

    // Build trend data
    val oilPeaks = recentDrives.map { if (it.peakOilTempC > -90) it.peakOilTempC.toFloat() else 0f }
    val boostPeaks = recentDrives.map { it.peakBoostPsi.toFloat() }
    val aggrScores = recentDrives.map { it.aggressionScore.toFloat() }
    val fuelEcon = recentDrives.map { it.avgFuelL100km.toFloat() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (oilPeaks.any { it > 0f }) {
            TrendRow("PEAK OIL", oilPeaks, Orange)
        }
        if (boostPeaks.any { it > 0f }) {
            TrendRow("PEAK BOOST", boostPeaks, accent)
        }
        if (aggrScores.any { it > 0f }) {
            TrendRow("AGGRESSION", aggrScores, Warn)
        }
        if (fuelEcon.any { it > 0f }) {
            TrendRow("FUEL L/100", fuelEcon, Ok)
        }
    }
}

@Composable
private fun TrendRow(
    label: String,
    data: List<Float>,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Surf2, Tokens.CardShape)
            .border(Tokens.CardBorder, Brd, Tokens.CardShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MonoLabel(label, 8.sp, Dim, modifier = Modifier.weight(0.35f))
        Sparkline(
            data = data,
            lineColor = color,
            modifier = Modifier.weight(0.5f).height(24.dp),
            strokeWidth = 1.dp,
            fillAlpha = 0.15f
        )
        // Latest value
        MonoText(
            "%.1f".format(data.lastOrNull() ?: 0f),
            9.sp, Mid, FontWeight.Bold,
            modifier = Modifier.weight(0.15f)
        )
    }
}

private fun formatDurationCompact(ms: Long): String {
    if (ms <= 0) return "0:00"
    val secs = ms / 1000
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
