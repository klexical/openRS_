package com.openrs.dash.ui.trip

import com.openrs.dash.ui.units.distance
import com.openrs.dash.ui.units.distanceIn
import com.openrs.dash.ui.units.distanceUnitText
import com.openrs.dash.ui.units.speed
import com.openrs.dash.ui.units.speedIn
import com.openrs.dash.ui.units.speedUnitText
import com.openrs.dash.ui.units.boostPsi
import com.openrs.dash.ui.units.tempDeltaIn
import com.openrs.dash.ui.units.fuelVolume
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.ui.*
import kotlin.math.abs

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE COMPARISON SHEET — side-by-side comparison of two drives with deltas
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveComparisonSheet(
    driveA: DriveEntity,
    driveB: DriveEntity,
    prefs: UserPrefs,
    onDismiss: () -> Unit,
    /** Debug TRIP preview: sample drives — label the sheet. */
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
            // ── Title ──
            InstrumentLabel("DRIVE COMPARISON", color = Mid)
            Spacer(Modifier.height(10.dp))

            // ── Column headers: Drive A | vs | Drive B ──
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Drive names in ink, readable (were 9 sp paint-blue labels)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    UIText(driveA.name ?: "Drive A", 14.sp, Frost, FontWeight.SemiBold)
                }
                Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
                    InstrumentLabel("vs")
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    UIText(driveB.name ?: "Drive B", 14.sp, Frost, FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Metric rows ──

            val durationA = if (driveA.endTime > 0) driveA.endTime - driveA.startTime else 0L
            val durationB = if (driveB.endTime > 0) driveB.endTime - driveB.startTime else 0L
            ComparisonRow(
                label = "DURATION",
                valueA = formatDuration(durationA),
                valueB = formatDuration(durationB),
                direction = DeltaDirection.NEUTRAL,
                delta = formatDurationDelta(durationB - durationA)
            )

            val distA = driveA.distanceKm
            val distB = driveB.distanceKm
            ComparisonRow(
                label = "DISTANCE",
                valueA = prefs.distance(distA).text,
                valueB = prefs.distance(distB).text,
                direction = DeltaDirection.NEUTRAL,
                delta = formatNumericDelta(prefs.distanceIn(distB) - prefs.distanceIn(distA), prefs.distanceUnitText, 1)
            )

            ComparisonRow(
                label = "AVG SPEED",
                valueA = prefs.speed(driveA.avgSpeedKph).text,
                valueB = prefs.speed(driveB.avgSpeedKph).text,
                direction = DeltaDirection.NEUTRAL,
                delta = formatSpeedDelta(driveB.avgSpeedKph - driveA.avgSpeedKph, prefs)
            )

            ComparisonRow(
                label = "MAX SPEED",
                valueA = prefs.speed(driveA.maxSpeedKph).text,
                valueB = prefs.speed(driveB.maxSpeedKph).text,
                direction = DeltaDirection.HIGHER_BETTER,
                rawDelta = driveB.maxSpeedKph - driveA.maxSpeedKph,
                delta = formatSpeedDelta(driveB.maxSpeedKph - driveA.maxSpeedKph, prefs)
            )

            Spacer(Modifier.height(6.dp))

            ComparisonRow(
                label = "PEAK RPM",
                valueA = "${driveA.peakRpm}",
                valueB = "${driveB.peakRpm}",
                direction = DeltaDirection.HIGHER_BETTER,
                rawDelta = (driveB.peakRpm - driveA.peakRpm).toDouble(),
                delta = formatIntDelta(driveB.peakRpm - driveA.peakRpm)
            )

            // #207: a delta only between peaks of the same source (or two drives from before sources were stored);
            // otherwise each value is named by its source and nothing is compared. No reading = "—", never 0.
            val peakA = com.openrs.dash.data.StoredBoost.drivePeak(driveA)
            val peakB = com.openrs.dash.data.StoredBoost.drivePeak(driveB)
            val comparable = peakA.sameKind(peakB)
            fun peakText(p: com.openrs.dash.data.StoredBoost, named: Boolean) = p.psiOrNull?.let { psi ->
                formatBoost(psi, prefs) + if (named) " ${if (p is com.openrs.dash.data.StoredBoost.Legacy) "src n/r" else p.label}" else ""
            } ?: "—"
            ComparisonRow(
                label = if (comparable) "PEAK ${peakA.label}" else "PEAK BOOST / TIP",
                valueA = peakText(peakA, !comparable),
                valueB = peakText(peakB, !comparable),
                direction = if (comparable) DeltaDirection.HIGHER_BETTER else DeltaDirection.NEUTRAL,
                rawDelta = if (comparable) peakB.psiOrNull!! - peakA.psiOrNull!! else 0.0,
                delta = if (comparable) formatBoostDelta(peakB.psiOrNull!! - peakA.psiOrNull!!, prefs) else ""
            )

            ComparisonRow(
                label = "PEAK LAT G",
                valueA = "%.2f g".format(driveA.peakLateralG),
                valueB = "%.2f g".format(driveB.peakLateralG),
                direction = DeltaDirection.HIGHER_BETTER,
                rawDelta = driveB.peakLateralG - driveA.peakLateralG,
                delta = formatGDelta(driveB.peakLateralG - driveA.peakLateralG)
            )

            Spacer(Modifier.height(6.dp))

            // Oil: show start -> peak progression. #229: a drive saved without this build's 0x0F8 mapping is tagged
            // "map n/r" (values as recorded); no delta across different mappings
            val oilMapA = com.openrs.dash.data.OilPtuMapping.isCurrent(driveA.oilPtuMapping)
            val oilMapB = com.openrs.dash.data.OilPtuMapping.isCurrent(driveB.oilPtuMapping)
            fun oilTag(mapped: Boolean) = if (mapped) "" else " ${com.openrs.dash.data.OilPtuMapping.UNKNOWN_TAG}"
            val oilA = formatTempProgression(driveA.startOilTempC, driveA.peakOilTempC, prefs) + oilTag(oilMapA)
            val oilB = formatTempProgression(driveB.startOilTempC, driveB.peakOilTempC, prefs) + oilTag(oilMapB)
            ComparisonRow(
                label = "OIL",
                valueA = oilA,
                valueB = oilB,
                direction = DeltaDirection.NEUTRAL,
                delta = if (oilMapA == oilMapB) formatTempDelta(driveB.peakOilTempC, driveA.peakOilTempC, prefs) else "—"
            )

            val cltA = formatTempProgression(driveA.startCoolantTempC, driveA.peakCoolantTempC, prefs)
            val cltB = formatTempProgression(driveB.startCoolantTempC, driveB.peakCoolantTempC, prefs)
            ComparisonRow(
                label = "COOLANT",
                valueA = cltA,
                valueB = cltB,
                direction = DeltaDirection.NEUTRAL,
                delta = formatTempDelta(driveB.peakCoolantTempC, driveA.peakCoolantTempC, prefs)
            )

            Spacer(Modifier.height(6.dp))

            val fuel = fuelUsedCompare(driveA.fuelUsedL, driveB.fuelUsedL, prefs)
            ComparisonRow(
                label = "FUEL USED",
                valueA = fuel.valueA,
                valueB = fuel.valueB,
                direction = if (fuel.comparable) DeltaDirection.LOWER_BETTER else DeltaDirection.NEUTRAL,
                rawDelta = fuel.rawDelta,
                delta = fuel.delta
            )

            ComparisonRow(
                label = "SCORE",
                valueA = "${driveA.aggressionScore}",
                valueB = "${driveB.aggressionScore}",
                direction = DeltaDirection.HIGHER_BETTER,
                rawDelta = (driveB.aggressionScore - driveA.aggressionScore).toDouble(),
                delta = formatIntDelta(driveB.aggressionScore - driveA.aggressionScore)
            )

            Spacer(Modifier.height(12.dp))

            // ── Dismiss button ──
            Box(
                Modifier.fillMaxWidth()
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

// ── Delta direction enum ─────────────────────────────────────────────────

private enum class DeltaDirection { HIGHER_BETTER, LOWER_BETTER, NEUTRAL }

// ── Comparison row composable ────────────────────────────────────────────

@Composable
private fun ComparisonRow(
    label: String,
    valueA: String,
    valueB: String,
    direction: DeltaDirection,
    delta: String,
    rawDelta: Double = 0.0
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 3.dp)
            .instrumentSurface()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drive A value
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            DataNum(valueA, 15.sp, Frost)
        }

        // Center: label + delta (B − A). Neutral — the old green / orange called higher RPM, boost, score
        // and lower fuel "better"; a comparison reports the difference, it doesn't judge it.
        Column(
            Modifier.width(96.dp).padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            InstrumentLabel(label)
            if (delta.isNotBlank()) MonoLabel(delta, 10.sp, Mid, FontWeight.Medium)
        }

        // Drive B value
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            DataNum(valueB, 15.sp, Frost)
        }
    }
}

// ── Formatting helpers ───────────────────────────────────────────────────

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val secs = ms / 1000
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatDurationDelta(deltaMs: Long): String {
    if (deltaMs == 0L) return ""
    val prefix = if (deltaMs > 0) "+" else "-"
    val absMs = abs(deltaMs)
    val secs = absMs / 1000
    val m = secs / 60
    val s = secs % 60
    return if (m > 0) "$prefix%d:%02d".format(m, s) else "$prefix%ds".format(s)
}

private fun formatNumericDelta(delta: Double, unit: String, decimals: Int): String {
    if (abs(delta) < 0.05) return ""
    val prefix = if (delta > 0) "+" else ""
    return "$prefix%.${decimals}f $unit".format(delta)
}

private fun formatSpeedDelta(deltaKph: Double, prefs: UserPrefs): String {
    val delta = prefs.speedIn(deltaKph)
    if (abs(delta) < 0.5) return ""
    val prefix = if (delta > 0) "+" else ""
    return "$prefix%.0f %s".format(delta, prefs.speedUnitText)
}

private fun formatIntDelta(delta: Int): String {
    if (delta == 0) return ""
    val prefix = if (delta > 0) "+" else ""
    return "$prefix$delta"
}

private fun formatBoost(psi: Double, prefs: UserPrefs): String {
    return prefs.boostPsi(psi).text
}

private fun formatBoostDelta(deltaPsi: Double, prefs: UserPrefs): String {
    if (abs(deltaPsi) < 0.05) return ""
    val prefix = if (deltaPsi > 0) "+" else ""
    return prefix + prefs.boostPsi(deltaPsi).text
}

private fun formatGDelta(delta: Double): String {
    if (abs(delta) < 0.005) return ""
    val prefix = if (delta > 0) "+" else ""
    return "$prefix%.2f g".format(delta)
}

private fun formatTempProgression(startC: Double, peakC: Double, prefs: UserPrefs): String {
    val startStr = if (startC > -90) "${prefs.displayTemp(startC)}${prefs.tempLabel}" else "\u2014"
    val peakStr = if (peakC > -90) "${prefs.displayTemp(peakC)}${prefs.tempLabel}" else "\u2014"
    return "$startStr \u2192 $peakStr"
}

private fun formatTempDelta(peakB: Double, peakA: Double, prefs: UserPrefs): String {
    if (peakA <= -90 || peakB <= -90) return ""
    val deltaC = peakB - peakA
    if (abs(deltaC) < 0.5) return ""
    // Convert the delta itself to display units
    val deltaDisplay = prefs.tempDeltaIn(deltaC)
    val prefix = if (deltaDisplay > 0) "+" else ""
    return "$prefix%.0f%s".format(deltaDisplay, prefs.tempLabel)
}

/** The FUEL USED row's texts (#336). */
internal data class FuelCompare(val valueA: String, val valueB: String, val delta: String, val rawDelta: Double, val comparable: Boolean)

/**
 * The FUEL USED row for two drives (#336): a stored 0.0 is no figure (the gauge never dropped by enough steps to be fuel),
 * shown "—", and a pair where either drive has none shows no difference, as Sapphire's Compare does.
 */
internal fun fuelUsedCompare(aL: Double, bL: Double, prefs: UserPrefs): FuelCompare {
    fun text(l: Double) = if (l > 0) prefs.fuelVolume(l).text else "—"
    val comparable = aL > 0 && bL > 0
    return FuelCompare(text(aL), text(bL), if (comparable) formatFuelDelta(bL - aL, prefs) else "", if (comparable) bL - aL else 0.0, comparable)
}

/** The difference in the fuel unit; none when it rounds to zero at the unit's decimals. */
private fun formatFuelDelta(deltaL: Double, prefs: UserPrefs): String {
    val shown = prefs.fuelVolume(abs(deltaL))
    if (shown.value.all { it == '0' || it == '.' || it == ',' }) return ""
    return (if (deltaL > 0) "+" else "-") + shown.text
}
