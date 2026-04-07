package com.openrs.dash.ui.trip

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.rememberCameraPositionState
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE PAGE — MAP tab: live recording + drive history
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun DrivePage(
    driveState: DriveState,
    vehicleState: VehicleState,
    prefs: UserPrefs,
    onMapTouched: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val accent = LocalThemeAccent.current
    val scope = rememberCoroutineScope()
    val recorder = remember { OpenRSDashApp.instance.driveRecorder }

    var hasLocationPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPerm = granted }

    // Color mode cycling through all 6
    var colorModeIndex by remember { mutableIntStateOf(0) }
    val colorMode = ColorMode.entries[colorModeIndex]

    // Map type cycling: Normal → Satellite → Terrain
    var mapType by remember { mutableStateOf(MapType.NORMAL) }

    // Drive history
    var drives by remember { mutableStateOf<List<DriveEntity>>(emptyList()) }
    var selectedDrivePoints by remember { mutableStateOf<List<DrivePointEntity>>(emptyList()) }
    var selectedDriveId by remember { mutableStateOf<Long?>(null) }
    var selectedDrive by remember { mutableStateOf<DriveEntity?>(null) }

    // Rename dialog state
    var renameDriveId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Idle location (one-shot centering when not recording)
    var idleLocation by remember { mutableStateOf<android.location.Location?>(null) }

    // One-shot location fetch when tab opens idle
    LaunchedEffect(hasLocationPerm) {
        if (hasLocationPerm && !driveState.isRecording) {
            try {
                val loc = recorder.getLastKnownLocation()
                if (loc != null) idleLocation = loc
            } catch (_: Exception) {}
        }
    }

    // Load drive history
    LaunchedEffect(driveState.isRecording) {
        withContext(Dispatchers.IO) {
            drives = DriveDatabase.getInstance(context).driveDao()
                .getRecentDrives(AppSettings.getMaxSavedDrives(context))
        }
    }

    // Recording indicator pulse
    val recAlpha by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "recAlpha"
    )

    val isLive = vehicleState.isConnected || driveState.isRecording
    val cameraPositionState = rememberCameraPositionState()

    Column(Modifier.fillMaxSize().background(Bg).padding(bottom = com.openrs.dash.ui.Tokens.NavBarHeight)) {
        // ── Map section ──────────────────────────────────────────────
        // pointerInput tracks touch state so the parent HorizontalPager
        // can disable swiping while the user is interacting with the map.
        // Events are NOT consumed — map and floating controls work normally.
        Box(
            Modifier.weight(if (isLive) 0.55f else 0.45f).fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onMapTouched(true)
                        try {
                            do { val event = awaitPointerEvent() }
                            while (event.changes.any { it.pressed })
                        } finally { onMapTouched(false) }
                    }
                }
        ) {
            val mapPoints = if (driveState.isRecording) {
                driveState.recentPoints
            } else if (selectedDriveId != null) {
                selectedDrivePoints
            } else {
                emptyList()
            }

            val currentLat = driveState.currentLocation?.latitude ?: idleLocation?.latitude
            val currentLng = driveState.currentLocation?.longitude ?: idleLocation?.longitude

            DriveMap(
                points = mapPoints,
                colorMode = colorMode,
                peakEvents = driveState.peakEvents,
                rtrPoint = driveState.rtrAchievedPoint,
                currentLat = currentLat,
                currentLng = currentLng,
                isRecording = driveState.isRecording,
                isPaused = driveState.isPaused,
                hasLocationPermission = hasLocationPerm,
                mapType = mapType,
                cameraPositionState = cameraPositionState
            )

            // ── Floating controls (top-right stack) ─────────────────
            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Color mode toggle
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                        .clickable {
                            colorModeIndex = (colorModeIndex + 1) % ColorMode.entries.size
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    MonoText(colorMode.label, 10.sp, accent, FontWeight.Bold)
                }

                // Map type toggle
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                        .clickable {
                            mapType = when (mapType) {
                                MapType.NORMAL -> MapType.SATELLITE
                                MapType.SATELLITE -> MapType.TERRAIN
                                else -> MapType.NORMAL
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    MonoText(
                        when (mapType) {
                            MapType.SATELLITE -> "SAT"
                            MapType.TERRAIN -> "TER"
                            else -> "MAP"
                        },
                        10.sp, Mid, FontWeight.Bold
                    )
                }

                // Weather card
                driveState.currentWeather?.let { weather ->
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Surf.copy(alpha = 0.85f))
                            .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Column {
                            MonoText(
                                "${prefs.displayTemp(weather.tempC)}${prefs.tempLabel}",
                                10.sp, Frost, FontWeight.Bold
                            )
                            MonoText(weather.description, 8.sp, Dim)
                        }
                    }
                }

                // Zoom in
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                        .clickable { scope.launch { cameraPositionState.animate(CameraUpdateFactory.zoomIn()) } }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    MonoText("+", 12.sp, Frost, FontWeight.Bold)
                }

                // Zoom out
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                        .clickable { scope.launch { cameraPositionState.animate(CameraUpdateFactory.zoomOut()) } }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    MonoText("\u2212", 12.sp, Frost, FontWeight.Bold)
                }

                // Locate / recenter
                if (currentLat != null && currentLng != null) {
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Surf.copy(alpha = 0.85f))
                            .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                            .clickable {
                                scope.launch {
                                    cameraPositionState.animate(
                                        CameraUpdateFactory.newLatLngZoom(
                                            LatLng(currentLat, currentLng), 15f
                                        ), 500
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        MonoText("\u25CE", 12.sp, accent, FontWeight.Bold)
                    }
                }
            }

            // Recording indicator (top-center)
            if (driveState.isRecording && !driveState.isPaused) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier.size(8.dp)
                            .clip(CircleShape)
                            .background(Orange.copy(alpha = recAlpha))
                    )
                    MonoText("REC", 9.sp, Orange, FontWeight.Bold)
                }
            }

            // Paused indicator
            if (driveState.isPaused) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText("PAUSED", 9.sp, Warn, FontWeight.Bold)
                }
            }

            // ── Route stats overlay (bottom-start) ──────────────────
            val showStats = driveState.isRecording || selectedDriveId != null
            if (showStats) {
                val statDist: Double
                val statDuration: Long
                val statAvgSpd: Double
                if (driveState.isRecording) {
                    statDist = driveState.cumulativeDistanceKm
                    statDuration = driveState.elapsedMs
                    statAvgSpd = driveState.avgSpeedKph
                } else {
                    val d = selectedDrive
                    statDist = d?.distanceKm ?: 0.0
                    statDuration = if (d != null && d.endTime > 0) d.endTime - d.startTime else 0L
                    statAvgSpd = d?.avgSpeedKph ?: 0.0
                }

                Box(
                    Modifier.align(Alignment.BottomStart).padding(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val dist = if (prefs.speedUnit == "MPH")
                            "%.1f mi".format(statDist * 0.621371)
                        else "%.1f km".format(statDist)
                        MonoText(dist, 10.sp, Frost, FontWeight.Bold)
                        MonoText(formatDuration(statDuration), 9.sp, Mid)
                        val avgSpd = if (prefs.speedUnit == "MPH")
                            "%.0f mph".format(statAvgSpd * 0.621371)
                        else "%.0f km/h".format(statAvgSpd)
                        MonoText("AVG $avgSpd", 8.sp, Dim)
                    }
                }
            }

            // ── Color legend strip (bottom-center) ──────────────────
            if (mapPoints.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(colorMode.label, 8.sp, accent, FontWeight.Bold)
                    colorLegend(colorMode).forEach { (label, color) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape).background(color)
                            )
                            MonoLabel(label, 7.sp, Dim)
                        }
                    }
                }
            }
        }

        // ── Bottom section: HUD (live) or History (idle) ─────────────
        if (isLive) {
            LiveHud(
                vehicleState = vehicleState,
                driveState = driveState,
                prefs = prefs,
                hasLocationPerm = hasLocationPerm,
                onRequestPermission = { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                onStart = { recorder.startDrive(sessionId = 0) },
                onPause = { recorder.pauseDrive() },
                onResume = { recorder.resumeDrive() },
                onStop = {
                    recorder.stopDrive()
                    scope.launch(Dispatchers.IO) {
                        drives = DriveDatabase.getInstance(context).driveDao()
                            .getRecentDrives(AppSettings.getMaxSavedDrives(context))
                    }
                },
                modifier = Modifier.weight(0.45f).fillMaxWidth()
            )
        } else {
            DriveHistoryList(
                drives = drives,
                prefs = prefs,
                selectedId = selectedDriveId,
                onSelect = { drive ->
                    if (selectedDriveId == drive.id) {
                        selectedDriveId = null
                        selectedDrive = null
                        selectedDrivePoints = emptyList()
                    } else {
                        selectedDriveId = drive.id
                        selectedDrive = drive
                        scope.launch(Dispatchers.IO) {
                            selectedDrivePoints = DriveDatabase.getInstance(context)
                                .driveDao().getPoints(drive.id)
                        }
                    }
                },
                onExport = { drive ->
                    scope.launch(Dispatchers.IO) {
                        val pts = DriveDatabase.getInstance(context).driveDao().getPoints(drive.id)
                        DiagnosticExporter.shareDrive(context, drive, pts)
                    }
                },
                onDelete = { drive ->
                    scope.launch(Dispatchers.IO) {
                        DriveDatabase.getInstance(context).driveDao().deleteDrive(drive.id)
                        drives = DriveDatabase.getInstance(context).driveDao()
                            .getRecentDrives(AppSettings.getMaxSavedDrives(context))
                    }
                    if (selectedDriveId == drive.id) {
                        selectedDriveId = null
                        selectedDrive = null
                        selectedDrivePoints = emptyList()
                    }
                },
                onRename = { drive ->
                    renameDriveId = drive.id
                    renameText = drive.name ?: ""
                },
                modifier = Modifier.weight(0.55f).fillMaxWidth()
            )
        }
    }

    // ── Rename dialog ────────────────────────────────────────────────
    if (renameDriveId != null) {
        AlertDialog(
            onDismissRequest = { renameDriveId = null },
            title = { MonoText("Rename Drive", 14.sp, Frost, FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { MonoText("e.g. Tail of the Dragon", 12.sp, Dim) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Frost,
                        unfocusedTextColor = Mid,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Brd,
                        cursorColor = accent,
                        focusedPlaceholderColor = Dim,
                        unfocusedPlaceholderColor = Dim
                    )
                )
            },
            confirmButton = {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(accent)
                        .clickable {
                            val id = renameDriveId ?: return@clickable
                            val name = renameText.ifBlank { null }
                            scope.launch(Dispatchers.IO) {
                                DriveDatabase.getInstance(context).driveDao()
                                    .updateDriveName(id, name)
                                drives = DriveDatabase.getInstance(context).driveDao()
                                    .getRecentDrives(AppSettings.getMaxSavedDrives(context))
                            }
                            renameDriveId = null
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    MonoText("SAVE", 11.sp, Bg, FontWeight.Bold)
                }
            },
            dismissButton = {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable { renameDriveId = null }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    MonoText("CANCEL", 11.sp, Mid, FontWeight.Bold)
                }
            },
            containerColor = Surf2,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// LIVE HUD — telemetry strip + recording controls
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun LiveHud(
    vehicleState: VehicleState,
    driveState: DriveState,
    prefs: UserPrefs,
    hasLocationPerm: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalThemeAccent.current

    Column(
        modifier
            .background(Surf)
            .padding(horizontal = 8.dp)
            .padding(top = 6.dp, bottom = 6.dp)
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Row 1 — Speed · RPM · Gear · Avg RPM
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DataCell("SPD",
                    "${prefs.displaySpeed(vehicleState.speedKph)} ${prefs.speedLabel}",
                    modifier = Modifier.weight(1f))
                DataCell("RPM", "%.0f".format(vehicleState.rpm), modifier = Modifier.weight(1f))
                DataCell("GEAR", vehicleState.gearDisplay, modifier = Modifier.weight(1f))
                DataCell("AVG RPM", "%.0f".format(driveState.avgRpm), modifier = Modifier.weight(1f))
            }

            // Row 2 — Coolant · Oil · Ambient · Fuel %
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DataCell("CLT",
                    if (vehicleState.coolantTempC > -90)
                        "${prefs.displayTemp(vehicleState.coolantTempC)}${prefs.tempLabel}" else "--",
                    modifier = Modifier.weight(1f))
                DataCell("OIL",
                    if (vehicleState.oilTempC > -90)
                        "${prefs.displayTemp(vehicleState.oilTempC)}${prefs.tempLabel}" else "--",
                    modifier = Modifier.weight(1f))
                DataCell("AMB", "${prefs.displayTemp(vehicleState.ambientTempC)}${prefs.tempLabel}", modifier = Modifier.weight(1f))
                DataCell("FUEL", "%.0f%%".format(vehicleState.fuelLevelPct), modifier = Modifier.weight(1f))
            }

            // Row 3 — RDU · PTU · Fuel used · Economy
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DataCell("RDU",
                    if (vehicleState.rduTempC > -90)
                        "${prefs.displayTemp(vehicleState.rduTempC)}${prefs.tempLabel}" else "--",
                    modifier = Modifier.weight(1f))
                DataCell("PTU",
                    if (vehicleState.ptuTempC > -90)
                        "${prefs.displayTemp(vehicleState.ptuTempC)}${prefs.tempLabel}" else "--",
                    modifier = Modifier.weight(1f))
                DataCell("USED", "%.2fL".format(driveState.fuelUsedL), modifier = Modifier.weight(1f))
                val (econVal, econUnit) = if (prefs.speedUnit == "MPH")
                    "%.1f".format(driveState.avgFuelMpg) to "MPG"
                else
                    "%.1f".format(driveState.avgFuelL100km) to "L/100"
                DataCell("ECON", "$econVal $econUnit", modifier = Modifier.weight(1f))
            }

            // Row 4 — Wheel speeds
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DataCell("FL", "${prefs.displaySpeed(vehicleState.wheelSpeedFL)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                DataCell("FR", "${prefs.displaySpeed(vehicleState.wheelSpeedFR)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                DataCell("RL", "${prefs.displaySpeed(vehicleState.wheelSpeedRL)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                DataCell("RR", "${prefs.displaySpeed(vehicleState.wheelSpeedRR)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Recording controls ───────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!driveState.isRecording) {
                Button(
                    onClick = {
                        if (!hasLocationPerm) onRequestPermission()
                        else onStart()
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    MonoText("START", 13.sp, Bg, FontWeight.Bold)
                }
            } else if (driveState.isPaused) {
                Button(
                    onClick = onResume,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    MonoText("RESUME", 13.sp, Bg, FontWeight.Bold)
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(0.5f).height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange)
                ) {
                    MonoText("STOP", 13.sp, Bg, FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onPause,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Warn)
                ) {
                    MonoText("PAUSE", 13.sp, Bg, FontWeight.Bold)
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(0.5f).height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange)
                ) {
                    MonoText("STOP", 13.sp, Bg, FontWeight.Bold)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DRIVE HISTORY LIST — grouped by date, swipe to delete
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun DriveHistoryList(
    drives: List<DriveEntity>,
    prefs: UserPrefs,
    selectedId: Long?,
    onSelect: (DriveEntity) -> Unit,
    onExport: (DriveEntity) -> Unit,
    onDelete: (DriveEntity) -> Unit,
    onRename: (DriveEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalThemeAccent.current

    Column(modifier.background(Surf).padding(horizontal = 8.dp, vertical = 6.dp)) {
        SectionLabel(
            "DRIVE HISTORY",
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (drives.isEmpty()) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .border(Tokens.CardBorder, Brd, RoundedCornerShape(10.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MonoText("No drives recorded yet", 11.sp, Dim, FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    MonoLabel(
                        "Connect to your car and tap START to record your first drive",
                        9.sp, Dim
                    )
                }
            }
        } else {
            // Group by date
            val grouped = remember(drives) { groupDrivesByDate(drives) }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                grouped.forEach { (dateLabel, groupDrives) ->
                    // Date section header
                    item(key = "header_$dateLabel") {
                        MonoText(
                            dateLabel.uppercase(),
                            9.sp, Dim, FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }

                    items(groupDrives, key = { it.id }) { drive ->
                        SwipeDriveCard(
                            drive = drive,
                            prefs = prefs,
                            isSelected = selectedId == drive.id,
                            onClick = { onSelect(drive) },
                            onExport = { onExport(drive) },
                            onDelete = { onDelete(drive) },
                            onRename = { onRename(drive) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDriveCard(
    drive: DriveEntity,
    prefs: UserPrefs,
    isSelected: Boolean,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Orange.copy(alpha = 0.15f))
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                MonoText("DELETE", 11.sp, Orange, FontWeight.Bold)
            }
        }
    ) {
        DriveCard(
            drive = drive,
            prefs = prefs,
            isSelected = isSelected,
            onClick = onClick,
            onExport = onExport,
            onRename = onRename
        )
    }
}

@Composable
private fun DriveCard(
    drive: DriveEntity,
    prefs: UserPrefs,
    isSelected: Boolean,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit
) {
    val accent = LocalThemeAccent.current
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val durationMs = if (drive.endTime > 0) drive.endTime - drive.startTime else 0L
    val durationStr = formatDuration(durationMs)
    val isActive = drive.endTime == 0L

    val borderColor = if (isSelected) accent else Brd
    val bgColor = if (isSelected) Surf2 else Surf

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(Tokens.CardBorder, borderColor, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        // Header: name/time + status badge + duration
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Drive name or time
                val displayName = drive.name ?: timeFormat.format(Date(drive.startTime))
                MonoText(
                    displayName, 11.sp, Frost, FontWeight.Bold,
                    modifier = Modifier.clickable { onRename() }
                )

                // Status badge
                val (badgeText, badgeColor) = when {
                    isActive -> "ACTIVE" to Orange
                    !drive.hasGps -> "NO GPS" to Dim
                    else -> "COMPLETE" to Ok
                }
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    MonoLabel(badgeText, 7.sp, badgeColor)
                }
            }

            MonoText(durationStr, 10.sp, Mid)
        }

        Spacer(Modifier.height(6.dp))

        // Stats row — distance, peak speed, peak RPM, peak boost
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (drive.hasGps && drive.distanceKm > 0) {
                val dist = if (prefs.speedUnit == "MPH")
                    "%.1f mi".format(drive.distanceKm * 0.621371)
                else "%.1f km".format(drive.distanceKm)
                StatChip("DIST", dist)
            }
            if (drive.maxSpeedKph > 0) {
                val spd = prefs.displaySpeed(drive.maxSpeedKph)
                StatChip("MAX", "$spd ${prefs.speedLabel}")
            }
            if (drive.peakRpm > 0) StatChip("RPM", "${drive.peakRpm}")
            if (drive.peakBoostPsi > 0) StatChip("BOOST", "%.1f".format(drive.peakBoostPsi))
            if (drive.peakLateralG > 0) StatChip("G-LAT", "%.2f".format(drive.peakLateralG))
        }

        // GPS indicator (mini route preview placeholder)
        if (drive.hasGps && drive.distanceKm > 0) {
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(Ok.copy(alpha = 0.6f))
                )
                MonoLabel("GPS", 7.sp, Ok.copy(alpha = 0.6f))
            }
        }

        // Export + Rename buttons when selected
        if (isSelected) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExport,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.15f))
                ) {
                    MonoText("SHARE", 11.sp, accent, FontWeight.Bold)
                }
                Button(
                    onClick = onRename,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Surf3)
                ) {
                    MonoText("RENAME", 11.sp, Mid, FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MonoLabel(label, 7.sp, Dim)
        MonoText(value, 9.sp, Frost, FontWeight.Bold)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════════

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "Active"
    val secs = ms / 1000
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun groupDrivesByDate(drives: List<DriveEntity>): List<Pair<String, List<DriveEntity>>> {
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

    return drives.groupBy { drive ->
        val cal = Calendar.getInstance().apply { timeInMillis = drive.startTime }
        when {
            isSameDay(cal, today) -> "Today"
            isSameDay(cal, yesterday) -> "Yesterday"
            else -> dateFormat.format(Date(drive.startTime))
        }
    }.toList()
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
