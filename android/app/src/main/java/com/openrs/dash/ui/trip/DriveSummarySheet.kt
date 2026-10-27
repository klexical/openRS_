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
import com.openrs.dash.data.DriveThermalReview
import com.openrs.dash.data.DriveThermalSensor
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
            if (previewNotice) PreviewSheetNotice()
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

            // ── Thermal progression — levels and TEMP FLAG lines come from the limits saved with the drive
            //    (DriveThermalReview, shared with the history card's TEMP badge), never the current profile ──
            val review = remember(drive.thermalPeaksJson) { DriveThermalReview.from(drive.thermalPeaksJson) }
            val profileBasis = review.basis == DriveThermalReview.Basis.PROFILE
            // The peak's level under its own segment's limits; the flag lines explain any later, stricter event
            fun levelOf(s: DriveThermalSensor): Level? = when (review.sensor(s)?.peakLevel) {
                DriveThermalReview.PeakLevel.CRIT -> Level.CRIT
                DriveThermalReview.PeakLevel.WARN -> Level.WARN
                DriveThermalReview.PeakLevel.NORMAL -> Level.NORMAL
                null -> null
            }
            // The judged peak (fresh samples — the value the levels and flag use); legacy records stored a drive-end reading
            fun peakOf(s: DriveThermalSensor, stored: Double) = review.peakFor(s, stored)
            val awdEnds = remember(points) {
                fun ends(f: (DrivePointEntity) -> Double) =
                    (points.firstOrNull { f(it) > -90 }?.let(f) ?: -99.0) to (points.lastOrNull { f(it) > -90 }?.let(f) ?: -99.0)
                mapOf(DriveThermalSensor.PTU to ends { it.ptuTempC }, DriveThermalSensor.RDU to ends { it.rduTempC })
            }
            val showOil = drive.startOilTempC > -90 || drive.peakOilTempC > -90
            val showCoolant = drive.startCoolantTempC > -90 || drive.peakCoolantTempC > -90
            val awdRows = if (profileBasis) listOf(DriveThermalSensor.PTU, DriveThermalSensor.RDU).filter { review.sensor(it) != null } else emptyList()
            if (showOil || showCoolant || awdRows.isNotEmpty() || review.flagged) {
                Spacer(Modifier.height(6.dp))
                SheetDivider()
                Spacer(Modifier.height(6.dp))

                InstrumentLabel("THERMAL PROGRESSION", Modifier.padding(bottom = 6.dp), color = Mid)

                if (showOil) ThermalRow("OIL", drive.startOilTempC, peakOf(DriveThermalSensor.OIL, drive.peakOilTempC),
                    drive.endOilTempC, prefs, levelOf(DriveThermalSensor.OIL))
                if (showCoolant) ThermalRow("COOLANT", drive.startCoolantTempC, peakOf(DriveThermalSensor.COOLANT, drive.peakCoolantTempC),
                    drive.endCoolantTempC, prefs, levelOf(DriveThermalSensor.COOLANT))
                for (s in awdRows) {
                    val (start, end) = awdEnds.getValue(s)
                    ThermalRow(s.key.uppercase(), start, review.sensor(s)!!.peakC, end, prefs, levelOf(s))
                }
                ThermalLimitsNote(review, prefs)
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
                // Pressures in the user's unit (stats are psi) — they were always printed as PSI
                fun gaugeIn(psi: Double): String = when (prefs.boostUnit) {
                    "BAR" -> "%.2f".format(psi * UnitConversions.PSI_TO_BAR)
                    "KPA" -> "%.0f".format(psi / UnitConversions.KPA_TO_PSI)
                    else  -> "%.1f".format(psi)
                }
                val pUnit = prefs.displayBoost(UnitConversions.STD_ATM_KPA).second
                run {
                    val avgSign = if (boostStats.avgDeltaPsi >= 0) "+" else ""
                    ReadoutGrid(
                        rows = listOf(listOf(
                            Readout("AVG Δ", "$avgSign${gaugeIn(boostStats.avgDeltaPsi)} $pUnit"),
                            Readout("WORST Δ", "${gaugeIn(boostStats.worstDeltaPsi)} $pUnit"),
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
                    // Two columns — at 360 dp "WGDC @ PEAK" ran into "LOADED" in a four-column row
                    ReadoutGrid(rows = listOf(listOf(
                        Readout("WGDC @ PEAK", "%.0f%%".format(w)), Readout("LOADED SAMPLES", "${boostStats.sampleCount}"),
                    )), dense = true)
                }
                // Boost by gear (#175) — peak is gauge vs standard atmosphere, Δ = actual − target
                if (boostStats.byGear.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    InstrumentLabel("BY GEAR · PEAK / AVG Δ (${pUnit})", Modifier.padding(bottom = 4.dp), color = Mid)
                    // Three per row so "12.3 / +0.4" fits at phone width
                    ReadoutGrid(rows = boostStats.byGear.chunked(3).map { row ->
                        row.map { g ->
                            val d = gaugeIn(g.avgDeltaPsi).let { if (g.avgDeltaPsi >= 0) "+$it" else it }
                            Readout("G${g.gear}", "${gaugeIn(g.peakBoostPsi)} / $d")
                        } +
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

/** Debug TRIP preview: a sheet covers the page's notice, so sample sheets carry their own. */
@Composable
internal fun PreviewSheetNotice() {
    Box(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Warn.copy(alpha = pillBgAlpha(0.12f)))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { InstrumentLabel("PREVIEW · SAMPLE DATA — NOT SAVED", color = demoInk()) }
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
    /** From the drive's saved limits; null = limits unknown (legacy / not recorded) → no colour. */
    level: Level?,
) {
    val peakLevel = if (peakC > -90) level ?: Level.NONE else Level.NONE
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

/**
 * Which limits the rows were judged against, and the reading behind each TEMP flag (the history card's badge):
 * "OIL 221°F · flag threshold 220.1°F · RACE". The threshold is 95 % of the saved critical in °C, then converted —
 * the same value the check uses ([DriveThermalReview.SensorReview.flagThresholdC]). The flagging reading can be
 * lower than the drive's peak when a stricter profile applied later.
 */
@Composable
private fun ThermalLimitsNote(review: DriveThermalReview, prefs: UserPrefs) {
    val names = review.profiles.mapIndexed { i, id ->
        val n = thermalProfileName(id)
        if (i > 0 && review.profiles[i - 1] == id) "$n (edited)" else n
    }
    val context = when (review.basis) {
        DriveThermalReview.Basis.PROFILE ->
            if (review.profileChanged) "Limits: ${names.joinToString(" → ")} — each part judged by the profile in effect then"
            else "Limits: ${names.first()} profile, saved with the drive"
        DriveThermalReview.Basis.LEGACY -> "Recorded before limits were saved with drives — no limit colours"
        DriveThermalReview.Basis.NONE -> "No thermal limits recorded with this drive — no limit colours"
    }
    MonoText(context, 10.sp, Dim)
    for (r in review.sensors.filter { it.flagged }) {
        val reading = r.flagReadingC ?: continue
        val threshold = r.flagThresholdC ?: continue
        val label = r.sensor.key.uppercase()
        val line = when (review.basis) {
            DriveThermalReview.Basis.LEGACY ->
                "TEMP FLAG  $label ${prefs.tVs(reading, threshold)} at drive end · flag threshold ${prefs.lim(threshold)} · old fixed limit"
            else ->
                "TEMP FLAG  $label ${prefs.tVs(reading, threshold)} · flag threshold ${prefs.lim(threshold)} · ${thermalProfileName(r.flagProfile ?: "")}"
        }
        Spacer(Modifier.height(2.dp))
        MonoText(line, 10.sp, WarnInk)
    }
}

private fun thermalProfileName(id: String) = when (id) {
    "track" -> "TRACK"; "race" -> "RACE"; "custom" -> "CUSTOM"; else -> "STREET"
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
