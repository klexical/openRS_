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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.BoostSweepStats
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
    onDismiss: () -> Unit,
    /** Debug preview: sample history for the TRENDS section instead of Room. */
    trendDrives: List<DriveEntity>? = null,
    /** Debug preview: label the sheet as sample data (the page's notice is hidden behind it). */
    previewNotice: Boolean = false
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
            if (previewNotice) {
                Box(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Warn.copy(alpha = pillBgAlpha(0.12f)))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { InstrumentLabel("PREVIEW · SAMPLE DATA — NOT SAVED", color = demoInk()) }
            }
            // ── Title + Duration badge ──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    InstrumentLabel("DRIVE SUMMARY", color = Mid)
                    val whenStr = remember(drive.startTime) {
                        java.text.SimpleDateFormat("EEE d MMM · h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(drive.startTime))
                    }
                    UIText(drive.name ?: whenStr, 16.sp, Frost, FontWeight.SemiBold)
                    if (drive.name != null) InstrumentMeta(whenStr)
                }
                val durationMs = if (drive.endTime > 0) drive.endTime - drive.startTime else 0L
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = pillBgAlpha(0.14f)))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    MonoLabel(formatDurationCompact(durationMs), 12.sp, actionInk(accent), FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(4.dp))
            SheetDivider()
            Spacer(Modifier.height(8.dp))

            // ── Totals + peaks: one readings panel (was eight DataCell cards) ──
            run {
                val durationMs = if (drive.endTime > 0) drive.endTime - drive.startTime else 0L
                val dist = if (prefs.speedUnit == "MPH") "%.1f mi".format(drive.distanceKm * 0.621371)
                    else "%.1f km".format(drive.distanceKm)
                val econ = if (drive.distanceKm > 0.1 && drive.fuelUsedL > 0) {
                    if (prefs.speedUnit == "MPH") "%.1f MPG".format(282.48 / (drive.fuelUsedL / drive.distanceKm * 100.0))
                    else "%.1f L/100".format(drive.fuelUsedL / drive.distanceKm * 100.0)
                } else "— ${if (prefs.speedUnit == "MPH") "MPG" else "L/100"}"
                val boost = prefs.displayBoost(drive.peakBoostPsi / UnitConversions.KPA_TO_PSI + UnitConversions.STD_ATM_KPA)
                ReadoutGrid(
                    rows = listOf(
                        listOf(Readout("DURATION", formatDurationCompact(durationMs)), Readout("DISTANCE", dist),
                            Readout("FUEL", if (drive.fuelUsedL > 0) "%.2f L".format(drive.fuelUsedL) else "— L"),
                            Readout("ECONOMY", econ)),
                        listOf(Readout("TOP SPEED", "${prefs.displaySpeed(drive.maxSpeedKph)} ${prefs.speedLabel}"),
                            Readout("PEAK RPM", "${drive.peakRpm}"),
                            Readout("PEAK BOOST", "${boost.first} ${boost.second}"),
                            Readout("PEAK G", "%.2f g".format(drive.peakLateralG))),
                    ),
                    dense = true
                )
            }

            // ── Thermal progression ──
            if (drive.startOilTempC > -90 || drive.peakOilTempC > -90 ||
                drive.startCoolantTempC > -90 || drive.peakCoolantTempC > -90) {
                Spacer(Modifier.height(6.dp))
                SheetDivider()
                Spacer(Modifier.height(6.dp))

                InstrumentLabel("THERMAL PROGRESSION", Modifier.padding(bottom = 6.dp), color = Mid)

                // Oil temp row
                if (drive.startOilTempC > -90 || drive.peakOilTempC > -90) {
                    ThermalRow(
                        label = "OIL",
                        startC = drive.startOilTempC,
                        peakC = drive.peakOilTempC,
                        endC = drive.endOilTempC,
                        prefs = prefs,
                        warnC = prefs.oilWarnC, critC = prefs.oilCritC
                    )
                }

                // Coolant temp row
                if (drive.startCoolantTempC > -90 || drive.peakCoolantTempC > -90) {
                    ThermalRow(
                        label = "COOLANT",
                        startC = drive.startCoolantTempC,
                        peakC = drive.peakCoolantTempC,
                        endC = drive.endCoolantTempC,
                        prefs = prefs,
                        warnC = prefs.coolWarnC, critC = prefs.coolCritC
                    )
                }
            }

            // ── Drive mode breakdown ──
            val modeBreakdown = parseModeBreakdown(drive.driveModeBreakdown)
            if (modeBreakdown.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SheetDivider()
                Spacer(Modifier.height(6.dp))

                InstrumentLabel("DRIVE MODE BREAKDOWN", Modifier.padding(bottom = 6.dp), color = Mid)

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
                            // The swatch was fillMaxWidth(0f) — zero wide, so the legend had no colours.
                            Box(
                                Modifier.size(10.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(modeColor(mode))
                            )
                            MonoLabel(
                                "${mode.uppercase()} ${(fraction * 100).toInt()}%",
                                10.sp, Mid
                            )
                        }
                    }
                }
            }

            // ── Trends across recent drives ──
            DriveTrendsSection(trendDrives, prefs)

            // ── Boost sweep summary (rc.4) ──
            val boostStats = remember(points) { BoostSweepStats.from(points) }
            if (boostStats != null) {
                Spacer(Modifier.height(6.dp))
                SheetDivider()
                Spacer(Modifier.height(6.dp))

                InstrumentLabel("BOOST SWEEP", Modifier.padding(bottom = 6.dp), color = Mid)
                run {
                    val avgSign = if (boostStats.avgDeltaPsi >= 0) "+" else ""
                    ReadoutGrid(
                        rows = listOf(listOf(
                            Readout("AVG Δ", "$avgSign%.1f PSI".format(boostStats.avgDeltaPsi)),
                            Readout("WORST Δ", "%.1f PSI".format(boostStats.worstDeltaPsi)),
                            Readout("@ RPM", "${boostStats.worstRpm}"),
                            // WGDC ≥ 95 % = saturation (existing rule) — the only amber here
                            Readout("WGDC MAX", "%.0f%%".format(boostStats.maxWgdc),
                                color = if (boostStats.wgdcSaturated) Warn else androidx.compose.ui.graphics.Color.Unspecified),
                        )),
                        level = if (boostStats.wgdcSaturated) Level.WARN else Level.NORMAL,
                        dense = true
                    )
                }
                if (boostStats.wgdcSaturated) {
                    Spacer(Modifier.height(2.dp))
                    MonoText("WGDC ≥ 95% — wastegate saturation, target may be out of reach",
                        10.sp, Warn)
                }
                boostStats.avgWgdcAtPeak?.let { w ->
                    Spacer(Modifier.height(4.dp))
                    ReadoutGrid(rows = listOf(listOf(
                        Readout("WGDC @ PEAK", "%.0f%%".format(w)), Readout("LOADED", "${boostStats.sampleCount}"), null, null,
                    )), dense = true)
                }
                // Boost by gear (#175) — peak is gauge vs standard atmosphere, Δ = actual − target
                if (boostStats.byGear.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    InstrumentLabel("BY GEAR · PEAK PSI / AVG Δ", Modifier.padding(bottom = 4.dp), color = Mid)
                    // Three per row so "12.3 / +0.4" fits at phone width
                    ReadoutGrid(rows = boostStats.byGear.chunked(3).map { row ->
                        row.map { g -> Readout("G${g.gear}", "%.1f / %+.1f".format(g.peakBoostPsi, g.avgDeltaPsi)) } +
                            List(3 - row.size) { null }
                    }, dense = true)
                }
            }

            // ── Time-series chart ──
            if (points.size >= 2) {
                Spacer(Modifier.height(6.dp))
                SheetDivider()
                Spacer(Modifier.height(6.dp))

                InstrumentLabel("TELEMETRY", Modifier.padding(bottom = 6.dp), color = Mid)
                DriveTimeSeries(points = points)
            }

            Spacer(Modifier.height(12.dp))
            SheetDivider()
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
                        .heightIn(min = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoText("SHARE", 13.sp, Bg, FontWeight.Bold)
                }
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surf2)
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .heightIn(min = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoText("DISMISS", 13.sp, Mid, FontWeight.Bold)
                }
            }
        }
    }
}

/** Hairline between summary sections (was NeonDivider — no decorative glow). */
@Composable
private fun SheetDivider() =
    Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.4f))))

/**
 * Start → peak → end for one sensor. The peak takes the THERMAL preset's warn / crit colour only when it
 * crossed one (it used to be amber whatever the value).
 */
@Composable
private fun ThermalRow(
    label: String,
    startC: Double,
    peakC: Double,
    endC: Double,
    prefs: UserPrefs,
    warnC: Double,
    critC: Double,
) {
    val peakLevel = if (peakC > -90) tempLevel(peakC, warnC, critC, TempAvail.AVAILABLE) else Level.NONE
    Row(
        Modifier.fillMaxWidth()
            .instrumentSurface(if (peakLevel.isAlert) peakLevel else Level.NORMAL)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        InstrumentLabel(label, color = Mid)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TempStage("START", startC, prefs, Frost)
            MonoLabel("\u2192", 12.sp, Dim)
            TempStage("PEAK", peakC, prefs, if (peakLevel.isAlert) levelColor(peakLevel) else Frost)
            MonoLabel("\u2192", 12.sp, Dim)
            TempStage("END", endC, prefs, Frost)
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun TempStage(label: String, tempC: Double, prefs: UserPrefs, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        InstrumentLabel(label)
        val text = if (tempC > -90) "${prefs.displayTemp(tempC)}${prefs.tempLabel}" else "—"
        DataNum(text, 15.sp, if (tempC > -90) color else Dim)
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

/**
 * Drive-mode shares on a neutral → paint scale. Green / amber / orange read as OK / caution / limit
 * everywhere else in the app, and the header shows the mode in ink.
 */
@Composable
private fun modeColor(mode: String) = when (mode.uppercase()) {
    "NORMAL"  -> Dim
    "SPORT"   -> accentMid()
    "TRACK"   -> LocalThemeAccent.current
    "DRIFT"   -> Frost
    else      -> Mid
}

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE TRENDS — sparklines across recent drives
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun DriveTrendsSection(sample: List<DriveEntity>? = null, prefs: UserPrefs) {
    val recentDrives by produceState<List<DriveEntity>>(emptyList(), sample) {
        value = sample?.filter { it.endTime > 0 }?.reversed() ?: withContext(Dispatchers.IO) {
            OpenRSDashApp.instance.driveDb.driveDao().getRecentDrives(20)
                .filter { it.endTime > 0 }
                .reversed() // oldest first for sparkline
        }
    }
    if (recentDrives.size < 3) return

    Spacer(Modifier.height(6.dp))
    SheetDivider()
    Spacer(Modifier.height(6.dp))

    InstrumentLabel("TRENDS (LAST ${recentDrives.size} DRIVES)", Modifier.padding(bottom = 6.dp), color = Mid)

    val accent = LocalThemeAccent.current

    // Trend data in the user's units (stored: °C, gauge psi, L/100 km) — the values were shown raw and
    // unlabelled next to a °F / MPG summary. Sparklines plot the converted values.
    val fahrenheit = prefs.tempUnit != "C"
    val oilPeaks = recentDrives.map { d ->
        if (d.peakOilTempC > -90) (if (fahrenheit) d.peakOilTempC * 9 / 5 + 32 else d.peakOilTempC).toFloat() else 0f
    }
    val boostUnit = prefs.displayBoost(UnitConversions.STD_ATM_KPA).second
    fun boostIn(psi: Double): Double = when (prefs.boostUnit) {
        "BAR" -> psi * UnitConversions.PSI_TO_BAR
        "KPA" -> psi / UnitConversions.KPA_TO_PSI
        else  -> psi
    }
    val boostPeaks = recentDrives.map { boostIn(it.peakBoostPsi).toFloat() }
    val aggrScores = recentDrives.map { it.aggressionScore.toFloat() }
    val mpg = prefs.speedUnit == "MPH"
    val fuelEcon = recentDrives.map { d ->
        val l100 = d.avgFuelL100km
        (if (l100 <= 0.0) 0.0 else if (mpg) 282.48 / l100 else l100).toFloat()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (oilPeaks.any { it > 0f }) {
            TrendRow("PEAK OIL", oilPeaks, accentMid(), "%.0f${prefs.tempLabel}".format(oilPeaks.last()))
        }
        if (boostPeaks.any { it > 0f }) {
            val fmt = when (prefs.boostUnit) { "BAR" -> "%.2f"; "KPA" -> "%.0f"; else -> "%.1f" }
            TrendRow("PEAK BOOST", boostPeaks, accentMid(), "${fmt.format(boostPeaks.last())} $boostUnit")
        }
        if (aggrScores.any { it > 0f }) {
            TrendRow("SCORE", aggrScores, accentMid(), "%.0f".format(aggrScores.last()))
        }
        if (fuelEcon.any { it > 0f }) {
            TrendRow("ECONOMY", fuelEcon, accentMid(),
                if (fuelEcon.last() > 0f) "%.1f ${if (mpg) "MPG" else "L/100"}".format(fuelEcon.last())
                else "— ${if (mpg) "MPG" else "L/100"}")
        }
    }
}

@Composable
private fun TrendRow(
    label: String,
    data: List<Float>,
    color: androidx.compose.ui.graphics.Color,
    latest: String,
) {
    Row(
        Modifier.fillMaxWidth()
            .instrumentSurface()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InstrumentLabel(label, Modifier.weight(0.35f))
        Sparkline(
            data = data,
            lineColor = color,
            modifier = Modifier.weight(0.4f).height(24.dp),
            strokeWidth = 1.dp,
            fillAlpha = 0.15f
        )
        // Latest value, with its unit
        DataNum(latest, 14.sp, Frost, modifier = Modifier.weight(0.25f))
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
