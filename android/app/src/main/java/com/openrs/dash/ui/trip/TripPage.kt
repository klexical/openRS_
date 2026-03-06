package com.openrs.dash.ui.trip

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.openrs.dash.R
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.PeakType
import com.openrs.dash.data.TripState
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.WeatherData
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.BarlowCond
import com.openrs.dash.ui.Brd
import com.openrs.dash.ui.DataCell
import com.openrs.dash.ui.Dim
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.JetBrainsMonoFamily
import com.openrs.dash.ui.LocalThemeAccent
import com.openrs.dash.ui.Mid
import com.openrs.dash.ui.MonoLabel
import com.openrs.dash.ui.MonoText
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.OrbitronFamily
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.Surf2
import com.openrs.dash.ui.Surf3
import com.openrs.dash.ui.UIText
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.Warn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which parameter colors the route polyline. */
private enum class ColorMode { SPEED, DRIVE_MODE }

/**
 * Full-screen trip overlay — Google Map with live telemetry HUD.
 *
 * Triggered from [AppHeader]'s TRIP button; rendered inside the outermost Box in
 * MainActivity so it covers the tab bar and header completely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripPage(
    tripState: TripState,
    vehicleState: VehicleState,
    prefs: UserPrefs,
    onStartTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current
    val scope = rememberCoroutineScope()

    // ── Permission ────────────────────────────────────────────────────────────
    var hasLocationPerm by remember {
        mutableStateOf(
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPerm = granted }

    // ── Polyline color mode toggle ─────────────────────────────────────────────
    var colorMode by remember { mutableStateOf(ColorMode.SPEED) }

    // ── Trip summary sheet ─────────────────────────────────────────────────────
    var showSummary by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Camera ────────────────────────────────────────────────────────────────
    val cameraPositionState = rememberCameraPositionState()
    val lastPoint = tripState.points.lastOrNull()
    LaunchedEffect(lastPoint) {
        lastPoint?.let {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lng), 15f)
            )
        }
    }

    // ── Polyline segments (rebuilt only when point list grows) ─────────────────
    val segments = remember(tripState.points.size, colorMode) {
        tripState.points.zipWithNext().map { (a, b) ->
            val color = when (colorMode) {
                ColorMode.SPEED -> {
                    val avg = (a.speedKph + b.speedKph) / 2.0
                    when {
                        avg < 60.0  -> Ok
                        avg < 100.0 -> Accent
                        avg < 140.0 -> Warn
                        else        -> Orange
                    }
                }
                ColorMode.DRIVE_MODE -> when (a.driveMode) {
                    DriveMode.SPORT   -> Warn
                    DriveMode.TRACK   -> Ok
                    DriveMode.DRIFT   -> Orange
                    DriveMode.CUSTOM  -> accent
                    else              -> Accent
                }
            }
            listOf(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng)) to color
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {

            // ── Header ───────────────────────────────────────────────────────
            TripHeader(
                isRecording = tripState.isRecording,
                colorMode   = colorMode,
                onColorMode = { colorMode = if (colorMode == ColorMode.SPEED) ColorMode.DRIVE_MODE else ColorMode.SPEED },
                onDismiss   = onDismiss
            )

            // ── Map ──────────────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
            ) {
                if (hasLocationPerm) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        properties = MapProperties(
                            mapStyleOptions = MapStyleOptions.loadRawResourceStyle(
                                ctx, R.raw.map_style_dark
                            )
                        ),
                        uiSettings = MapUiSettings(
                            zoomControlsEnabled  = false,
                            mapToolbarEnabled    = false,
                            myLocationButtonEnabled = false,
                            compassEnabled       = false
                        )
                    ) {
                        // Route polyline
                        key(tripState.points.size, colorMode) {
                            segments.forEach { (pts, color) ->
                                Polyline(
                                    points = pts,
                                    color  = color,
                                    width  = 10f
                                )
                            }
                        }

                        // Current position dot
                        lastPoint?.let { pt ->
                            Circle(
                                center      = LatLng(pt.lat, pt.lng),
                                radius      = 6.0,
                                fillColor   = accent,
                                strokeColor = Frost,
                                strokeWidth = 3f
                            )
                        }

                        // Peak event markers
                        tripState.peakEvents.forEach { event ->
                            val snippet = when (event.type) {
                                PeakType.RPM       -> "%.0f rpm".format(event.value)
                                PeakType.BOOST     -> "%.1f psi".format(event.value)
                                PeakType.LATERAL_G -> "%.2f g".format(event.value)
                            }
                            Marker(
                                state   = MarkerState(LatLng(event.lat, event.lng)),
                                title   = event.type.label,
                                snippet = snippet
                            )
                        }

                        // Race-Ready first-achieved marker
                        tripState.rtrAchievedPoint?.let { pt ->
                            Marker(
                                state   = MarkerState(LatLng(pt.lat, pt.lng)),
                                title   = "Race Ready",
                                snippet = "RTR achieved here"
                            )
                        }
                    }

                    // Weather overlay (top-right of map)
                    tripState.currentWeather?.let { weather ->
                        WeatherCard(
                            weather  = weather,
                            prefs    = prefs,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        )
                    }

                    // Legend strip (bottom of map)
                    ColorLegend(
                        colorMode = colorMode,
                        modifier  = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    )

                } else {
                    // Permission prompt
                    Box(
                        Modifier.fillMaxSize().background(Surf),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            MonoText(
                                "Location access required\nfor trip tracking",
                                14.sp, Mid, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    permLauncher.launch(
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accent)
                            ) {
                                MonoText("GRANT ACCESS", 12.sp, Bg, FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ── Live HUD ─────────────────────────────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
                    .background(Surf)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Row 1 — Speed · RPM · Gear · Avg RPM
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DataCell(
                        "SPD",
                        "${prefs.displaySpeed(vehicleState.speedKph)} ${prefs.speedLabel}",
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "RPM",
                        "%.0f".format(vehicleState.rpm),
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "GEAR",
                        vehicleState.gearDisplay,
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "AVG RPM",
                        "%.0f".format(tripState.avgRpm),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 2 — Coolant · Oil · Ambient · Fuel %
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DataCell(
                        "CLT",
                        "${prefs.displayTemp(vehicleState.coolantTempC)}${prefs.tempLabel}",
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "OIL",
                        "${prefs.displayTemp(vehicleState.oilTempC)}${prefs.tempLabel}",
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "AMB",
                        "${prefs.displayTemp(vehicleState.ambientTempC)}${prefs.tempLabel}",
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "FUEL",
                        "%.0f%%".format(vehicleState.fuelLevelPct),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 3 — RDU · PTU · Fuel used · Economy
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DataCell(
                        "RDU",
                        if (vehicleState.rduTempC > -90)
                            "${prefs.displayTemp(vehicleState.rduTempC)}${prefs.tempLabel}"
                        else "--",
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "PTU",
                        "${prefs.displayTemp(vehicleState.ptuTempC)}${prefs.tempLabel}",
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "USED",
                        "%.2fL".format(tripState.fuelUsedL),
                        modifier = Modifier.weight(1f)
                    )
                    val (econVal, econUnit) = if (prefs.speedUnit == "MPH")
                        "%.1f".format(tripState.avgFuelMpg) to "MPG"
                    else
                        "%.1f".format(tripState.avgFuelL100km) to "L/100"
                    DataCell(
                        "ECON",
                        "$econVal $econUnit",
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 4 — Wheel speeds
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DataCell(
                        "FL",
                        "${prefs.displaySpeed(vehicleState.wheelSpeedFL)} ${prefs.speedLabel}",
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "FR",
                        "${prefs.displaySpeed(vehicleState.wheelSpeedFR)} ${prefs.speedLabel}",
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "RL",
                        "${prefs.displaySpeed(vehicleState.wheelSpeedRL)} ${prefs.speedLabel}",
                        modifier = Modifier.weight(1f)
                    )
                    DataCell(
                        "RR",
                        "${prefs.displaySpeed(vehicleState.wheelSpeedRR)} ${prefs.speedLabel}",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.weight(1f))

                // Start / End Trip button
                Button(
                    onClick = {
                        if (tripState.isRecording) {
                            showSummary = tripState.points.isNotEmpty()
                            onEndTrip()
                        } else {
                            if (!hasLocationPerm) {
                                permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                onStartTrip()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape  = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (tripState.isRecording) Orange else accent
                    )
                ) {
                    MonoText(
                        if (tripState.isRecording) "END TRIP" else "START TRIP",
                        13.sp, Bg, FontWeight.Bold
                    )
                }
            }
        }

        // ── Trip Summary Sheet ────────────────────────────────────────────────
        if (showSummary) {
            ModalBottomSheet(
                onDismissRequest = { showSummary = false },
                sheetState       = sheetState,
                containerColor   = Surf
            ) {
                TripSummaryContent(
                    tripState = tripState,
                    prefs     = prefs,
                    onClose   = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showSummary = false
                        }
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TRIP HEADER
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun TripHeader(
    isRecording: Boolean,
    colorMode: ColorMode,
    onColorMode: () -> Unit,
    onDismiss: () -> Unit
) {
    val accent = LocalThemeAccent.current

    val infiniteTransition = rememberInfiniteTransition(label = "rec")
    val recAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.2f, label = "recDot",
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse)
    )

    Row(
        Modifier
            .fillMaxWidth()
            .background(Surf)
            .border(1.dp, Brd)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        Box(
            Modifier
                .size(28.dp)
                .background(Surf2, RoundedCornerShape(6.dp))
                .border(1.dp, Brd, RoundedCornerShape(6.dp))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            UIText("←", 14.sp, Mid)
        }

        Spacer(Modifier.width(8.dp))

        // Title + REC indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            MonoLabel("TRIP MAP", 11.sp, Frost, FontWeight.Bold, 0.2.sp)
            if (isRecording) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Orange.copy(alpha = recAlpha))
                )
                MonoLabel("REC", 9.sp, Orange, FontWeight.Bold, 0.2.sp)
            }
        }

        // Color mode toggle
        Box(
            Modifier
                .background(Surf2, RoundedCornerShape(4.dp))
                .border(1.dp, Brd, RoundedCornerShape(4.dp))
                .clickable { onColorMode() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            MonoLabel(
                if (colorMode == ColorMode.SPEED) "SPD" else "MODE",
                9.sp, accent, FontWeight.Bold, 0.15.sp
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// WEATHER CARD OVERLAY
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun WeatherCard(
    weather: WeatherData,
    prefs: UserPrefs,
    modifier: Modifier = Modifier
) {
    val tempStr = prefs.displayTemp(weather.tempC) + prefs.tempLabel
    val windStr = if (prefs.speedUnit == "MPH")
        "%.0f mph".format(weather.windMps * 2.237)
    else
        "%.0f m/s".format(weather.windMps)

    Box(
        modifier
            .background(Bg.copy(alpha = 0.88f), RoundedCornerShape(8.dp))
            .border(1.dp, Brd, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                UIText(weather.emoji, 14.sp, Frost)
                MonoText(tempStr, 14.sp, Frost, FontWeight.Bold)
            }
            MonoLabel(weather.description, 8.sp, Mid, letterSpacing = 0.1.sp)
            MonoLabel("${weather.humidity}% · $windStr", 8.sp, Dim, letterSpacing = 0.1.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// COLOR LEGEND
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ColorLegend(colorMode: ColorMode, modifier: Modifier = Modifier) {
    val accent = LocalThemeAccent.current
    val items: List<Pair<androidx.compose.ui.graphics.Color, String>> = when (colorMode) {
        ColorMode.SPEED -> listOf(
            Ok     to "<60",
            Accent to "60–100",
            Warn   to "100–140",
            Orange to "140+ km/h"
        )
        ColorMode.DRIVE_MODE -> listOf(
            Accent to "Normal",
            Warn   to "Sport",
            Ok     to "Track",
            Orange to "Drift"
        )
    }
    Row(
        modifier
            .background(Bg.copy(alpha = 0.80f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (color, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                MonoLabel(label, 7.sp, Mid, letterSpacing = 0.1.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TRIP SUMMARY SHEET CONTENT
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun TripSummaryContent(
    tripState: TripState,
    prefs: UserPrefs,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current

    val distStr = "%.2f".format(
        if (prefs.speedUnit == "MPH") tripState.cumulativeDistanceKm * 0.621371
        else tripState.cumulativeDistanceKm
    ) + " ${if (prefs.speedUnit == "MPH") "mi" else "km"}"

    val elapsedSec  = tripState.elapsedMs / 1000L
    val elapsedStr  = "%d:%02d:%02d".format(elapsedSec / 3600, (elapsedSec % 3600) / 60, elapsedSec % 60)

    val maxSpeedStr = prefs.displaySpeed(tripState.maxSpeedKph) + " " + prefs.speedLabel
    val avgSpeedStr = prefs.displaySpeed(tripState.avgSpeedKph) + " " + prefs.speedLabel

    val (econVal, econUnit) = if (prefs.speedUnit == "MPH")
        "%.1f".format(tripState.avgFuelMpg) to "MPG"
    else
        "%.1f".format(tripState.avgFuelL100km) to "L/100km"

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonoLabel("TRIP SUMMARY", 11.sp, accent, FontWeight.Bold, 0.2.sp)
            Box(
                Modifier
                    .background(Surf2, RoundedCornerShape(6.dp))
                    .border(1.dp, Brd, RoundedCornerShape(6.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                MonoLabel("CLOSE", 9.sp, Mid, letterSpacing = 0.15.sp)
            }
        }

        // Stats grid
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("DISTANCE", distStr, Modifier.weight(1f))
            SummaryCell("DURATION", elapsedStr, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("MAX SPEED", maxSpeedStr, Modifier.weight(1f))
            SummaryCell("AVG SPEED", avgSpeedStr, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("FUEL USED", "%.2f L".format(tripState.fuelUsedL), Modifier.weight(1f))
            SummaryCell(econUnit, econVal, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("PEAK RPM",   "%.0f".format(tripState.peakRpm),    Modifier.weight(1f))
            SummaryCell("PEAK BOOST", "%.1f PSI".format(tripState.peakBoostPsi), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("AVG RPM",    "%.0f".format(tripState.avgRpm),      Modifier.weight(1f))
            SummaryCell("PEAK LAT G", "%.2f g".format(tripState.peakLateralG), Modifier.weight(1f))
        }

        // Drive mode breakdown
        if (tripState.driveModeBreakdown.isNotEmpty()) {
            MonoLabel("DRIVE MODE BREAKDOWN", 9.sp, Dim, letterSpacing = 0.2.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tripState.driveModeBreakdown.entries
                    .sortedByDescending { it.value }
                    .forEach { (mode, frac) ->
                        val modeColor = when (mode) {
                            DriveMode.SPORT  -> Warn
                            DriveMode.TRACK  -> Ok
                            DriveMode.DRIFT  -> Orange
                            DriveMode.CUSTOM -> accent
                            else             -> Accent
                        }
                        Box(
                            Modifier
                                .background(modeColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .border(1.dp, modeColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            MonoLabel(
                                "${mode.label.uppercase()} ${"%.0f".format(frac * 100)}%",
                                9.sp, modeColor, letterSpacing = 0.1.sp
                            )
                        }
                    }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(8.dp))
            .border(1.dp, Brd, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.15.sp)
        Spacer(Modifier.height(3.dp))
        MonoText(value, 13.sp, Frost)
    }
}
