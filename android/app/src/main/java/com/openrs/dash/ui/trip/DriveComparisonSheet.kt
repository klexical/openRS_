package com.openrs.dash.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
            // ── Title ──
            MonoText(
                "DRIVE COMPARISON", 13.sp, Frost, FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))
            NeonDivider()
            Spacer(Modifier.height(6.dp))

            // ── Column headers: Drive A | vs | Drive B ──
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    MonoLabel(
                        driveA.name ?: "DRIVE A", 9.sp, accent,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    MonoLabel("vs", 8.sp, Dim)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    MonoLabel(
                        driveB.name ?: "DRIVE B", 9.sp, accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            NeonDivider()
            Spacer(Modifier.height(8.dp))

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
            val distUnit = if (prefs.speedUnit == "MPH") "mi" else "km"
            val distFactor = if (prefs.speedUnit == "MPH") UnitConversions.KM_TO_MI else 1.0
            ComparisonRow(
                label = "DISTANCE",
                valueA = "%.1f %s".format(distA * distFactor, distUnit),
                valueB = "%.1f %s".format(distB * distFactor, distUnit),
                direction = DeltaDirection.NEUTRAL,
                delta = formatNumericDelta(distB * distFactor - distA * distFactor, distUnit, 1)
            )

            ComparisonRow(
                label = "AVG SPEED",
                valueA = "%s %s".format(prefs.displaySpeed(driveA.avgSpeedKph), prefs.speedLabel),
                valueB = "%s %s".format(prefs.displaySpeed(driveB.avgSpeedKph), prefs.speedLabel),
                direction = DeltaDirection.NEUTRAL,
                delta = formatSpeedDelta(driveB.avgSpeedKph - driveA.avgSpeedKph, prefs)
            )

            ComparisonRow(
                label = "MAX SPEED",
                valueA = "%s %s".format(prefs.displaySpeed(driveA.maxSpeedKph), prefs.speedLabel),
                valueB = "%s %s".format(prefs.displaySpeed(driveB.maxSpeedKph), prefs.speedLabel),
                direction = DeltaDirection.HIGHER_BETTER,
                rawDelta = driveB.maxSpeedKph - driveA.maxSpeedKph,
                delta = formatSpeedDelta(driveB.maxSpeedKph - driveA.maxSpeedKph, prefs)
            )

            NeonDivider()
            Spacer(Modifier.height(8.dp))

            ComparisonRow(
                label = "PEAK RPM",
                valueA = "${driveA.peakRpm}",
                valueB = "${driveB.peakRpm}",
                direction = DeltaDirection.HIGHER_BETTER,
                rawDelta = (driveB.peakRpm - driveA.peakRpm).toDouble(),
                delta = formatIntDelta(driveB.peakRpm - driveA.peakRpm)
            )

            ComparisonRow(
                label = "PEAK BOOST",
                valueA = formatBoost(driveA.peakBoostPsi, prefs),
                valueB = formatBoost(driveB.peakBoostPsi, prefs),
                direction = DeltaDirection.HIGHER_BETTER,
                rawDelta = driveB.peakBoostPsi - driveA.peakBoostPsi,
                delta = formatBoostDelta(driveB.peakBoostPsi - driveA.peakBoostPsi, prefs)
            )

            ComparisonRow(
                label = "PEAK LAT G",
                valueA = "%.2f G".format(driveA.peakLateralG),
                valueB = "%.2f G".format(driveB.peakLateralG),
                direction = DeltaDirection.HIGHER_BETTER,
                rawDelta = driveB.peakLateralG - driveA.peakLateralG,
                delta = formatGDelta(driveB.peakLateralG - driveA.peakLateralG)
            )

            NeonDivider()
            Spacer(Modifier.height(8.dp))

            // Oil: show start -> peak progression
            val oilA = formatTempProgression(driveA.startOilTempC, driveA.peakOilTempC, prefs)
            val oilB = formatTempProgression(driveB.startOilTempC, driveB.peakOilTempC, prefs)
            ComparisonRow(
                label = "OIL TEMP",
                valueA = oilA,
                valueB = oilB,
                direction = DeltaDirection.NEUTRAL,
                delta = formatTempDelta(driveB.peakOilTempC, driveA.peakOilTempC, prefs)
            )

            val cltA = formatTempProgression(driveA.startCoolantTempC, driveA.peakCoolantTempC, prefs)
            val cltB = formatTempProgression(driveB.startCoolantTempC, driveB.peakCoolantTempC, prefs)
            ComparisonRow(
                label = "CLT TEMP",
                valueA = cltA,
                valueB = cltB,
                direction = DeltaDirection.NEUTRAL,
                delta = formatTempDelta(driveB.peakCoolantTempC, driveA.peakCoolantTempC, prefs)
            )

            NeonDivider()
            Spacer(Modifier.height(8.dp))

            ComparisonRow(
                label = "FUEL USED",
                valueA = "%.2f L".format(driveA.fuelUsedL),
                valueB = "%.2f L".format(driveB.fuelUsedL),
                direction = DeltaDirection.LOWER_BETTER,
                rawDelta = driveB.fuelUsedL - driveA.fuelUsedL,
                delta = formatFuelDelta(driveB.fuelUsedL - driveA.fuelUsedL)
            )

            ComparisonRow(
                label = "AGGRESSION",
                valueA = "${driveA.aggressionScore}",
                valueB = "${driveB.aggressionScore}",
                direction = DeltaDirection.HIGHER_BETTER,
                rawDelta = (driveB.aggressionScore - driveA.aggressionScore).toDouble(),
                delta = formatIntDelta(driveB.aggressionScore - driveA.aggressionScore)
            )

            Spacer(Modifier.height(12.dp))
            NeonDivider()
            Spacer(Modifier.height(12.dp))

            // ── Dismiss button ──
            Box(
                Modifier.fillMaxWidth()
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
            .background(Surf2, Tokens.CardShape)
            .border(Tokens.CardBorder, Brd, Tokens.CardShape)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drive A value
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            MonoText(valueA, 11.sp, Frost, FontWeight.Bold, textAlign = TextAlign.End)
        }

        // Center: label + delta
        Column(
            Modifier.width(80.dp).padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MonoLabel(label, 8.sp, Dim, fontWeight = FontWeight.Bold)
            if (delta.isNotBlank()) {
                val deltaColor = when (direction) {
                    DeltaDirection.NEUTRAL -> Mid
                    DeltaDirection.HIGHER_BETTER ->
                        if (rawDelta > 0) Ok else if (rawDelta < 0) Orange else Mid
                    DeltaDirection.LOWER_BETTER ->
                        if (rawDelta < 0) Ok else if (rawDelta > 0) Orange else Mid
                }
                MonoLabel(delta, 8.sp, deltaColor)
            }
        }

        // Drive B value
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            MonoText(valueB, 11.sp, Frost, FontWeight.Bold, textAlign = TextAlign.Start)
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
    val factor = if (prefs.speedUnit == "MPH") UnitConversions.KM_TO_MI else 1.0
    val delta = deltaKph * factor
    if (abs(delta) < 0.5) return ""
    val prefix = if (delta > 0) "+" else ""
    return "$prefix%.0f %s".format(delta, prefs.speedLabel)
}

private fun formatIntDelta(delta: Int): String {
    if (delta == 0) return ""
    val prefix = if (delta > 0) "+" else ""
    return "$prefix$delta"
}

private fun formatBoost(psi: Double, prefs: UserPrefs): String {
    return when (prefs.boostUnit) {
        "BAR" -> "%.2f BAR".format(psi * UnitConversions.PSI_TO_BAR)
        "KPA" -> "%.0f kPa".format(psi / UnitConversions.KPA_TO_PSI)
        else -> "%.1f PSI".format(psi)
    }
}

private fun formatBoostDelta(deltaPsi: Double, prefs: UserPrefs): String {
    if (abs(deltaPsi) < 0.05) return ""
    val prefix = if (deltaPsi > 0) "+" else ""
    return when (prefs.boostUnit) {
        "BAR" -> "$prefix%.2f BAR".format(deltaPsi * UnitConversions.PSI_TO_BAR)
        "KPA" -> "$prefix%.0f kPa".format(deltaPsi / UnitConversions.KPA_TO_PSI)
        else -> "$prefix%.1f PSI".format(deltaPsi)
    }
}

private fun formatGDelta(delta: Double): String {
    if (abs(delta) < 0.005) return ""
    val prefix = if (delta > 0) "+" else ""
    return "$prefix%.2f G".format(delta)
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
    val deltaDisplay = if (prefs.tempUnit == "C") deltaC else deltaC * 9.0 / 5.0
    val prefix = if (deltaDisplay > 0) "+" else ""
    return "$prefix%.0f%s".format(deltaDisplay, prefs.tempLabel)
}

private fun formatFuelDelta(deltaL: Double): String {
    if (abs(deltaL) < 0.005) return ""
    val prefix = if (deltaL > 0) "+" else ""
    return "$prefix%.2f L".format(deltaL)
}
