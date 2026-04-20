package com.openrs.dash.ui.trip

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import com.openrs.dash.data.DriveBookmarkEntity
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.LapTimer
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.ExportProgress
import com.openrs.dash.ui.*
import com.openrs.dash.ui.anim.pageEntrance
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════════════════════════════════
// TRIP PAGE — MAP tab: live recording + drive history
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun TripPage(
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

    // Map type cycling: Satellite → Terrain → Normal
    var mapType by remember { mutableStateOf(MapType.SATELLITE) }

    // Drive history
    var drives by remember { mutableStateOf<List<DriveEntity>>(emptyList()) }
    var selectedDrivePoints by remember { mutableStateOf<List<DrivePointEntity>>(emptyList()) }
    var selectedDriveId by remember { mutableStateOf<Long?>(null) }
    var selectedDrive by remember { mutableStateOf<DriveEntity?>(null) }

    // Bookmarks for the displayed drive
    var mapBookmarks by remember { mutableStateOf<List<DriveBookmarkEntity>>(emptyList()) }

    // Rename dialog state
    var renameDriveId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Post-drive summary sheet
    var summaryDrive by remember { mutableStateOf<DriveEntity?>(null) }
    var summaryPoints by remember { mutableStateOf<List<DrivePointEntity>>(emptyList()) }

    // Drive comparison
    var compareA by remember { mutableStateOf<DriveEntity?>(null) }
    var compareB by remember { mutableStateOf<DriveEntity?>(null) }

    // Session replay
    var replayDrive by remember { mutableStateOf<DriveEntity?>(null) }
    var replayPoints by remember { mutableStateOf<List<DrivePointEntity>>(emptyList()) }

    // Export options sheet
    var exportDrive by remember { mutableStateOf<DriveEntity?>(null) }

    // Lap timer — shared with DriveRecorder for persistence on drive stop
    val lapTimer = remember { LapTimer() }
    LaunchedEffect(recorder) { recorder.lapTimer = lapTimer }

    // Collect drive completion events
    LaunchedEffect(recorder) {
        recorder.driveCompleted.collect { drive ->
            summaryDrive = drive
            // Load points for time-series chart in summary
            withContext(Dispatchers.IO) {
                summaryPoints = DriveDatabase.getInstance(context).driveDao().getPoints(drive.id)
            }
        }
    }

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

    // Export progress
    val exportProgress by DiagnosticExporter.exportProgress.collectAsState()

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
                bookmarks = mapBookmarks,
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
            // rc.2: absorb all pointer events so taps that start on a control
            // and drag slightly don't reach the underlying Google Maps
            // AndroidView and pan the map.
            var controlsVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { controlsVisible = true }
            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Color mode toggle
                Box(
                    pageEntrance(0, controlsVisible, 50)
                        .clip(RoundedCornerShape(6.dp))
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
                    pageEntrance(1, controlsVisible, 50)
                        .clip(RoundedCornerShape(6.dp))
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
                        pageEntrance(2, controlsVisible, 50)
                            .clip(RoundedCornerShape(6.dp))
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
                    pageEntrance(3, controlsVisible, 50)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                        .clickable { scope.launch { cameraPositionState.animate(CameraUpdateFactory.zoomIn()) } }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    MonoText("+", 12.sp, Frost, FontWeight.Bold)
                }

                // Zoom out
                Box(
                    pageEntrance(4, controlsVisible, 50)
                        .clip(RoundedCornerShape(6.dp))
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
                        pageEntrance(5, controlsVisible, 50)
                            .clip(RoundedCornerShape(6.dp))
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

                // SET S/F button (lap timer)
                if (driveState.isRecording) {
                    SetStartFinishButton(
                        isSet = lapTimer.startFinish != null,
                        onSet = {
                            val loc = driveState.currentLocation
                            if (loc != null) {
                                lapTimer.setStartFinish(loc.latitude, loc.longitude, loc.bearing)
                            }
                        },
                        onClear = { lapTimer.clearStartFinish() },
                        modifier = pageEntrance(6, controlsVisible, 50)
                    )

                    // Bookmark button
                    Box(
                        pageEntrance(7, controlsVisible, 50)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Surf.copy(alpha = 0.85f))
                            .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                            .clickable { recorder.addBookmark() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        MonoText("\u2691 MARK", 10.sp, Warn, FontWeight.Bold)
                    }
                }
            }

            // ── Lap timer overlay (top-start, during recording) ────
            if (driveState.isRecording && lapTimer.startFinish != null) {
                LapTimerOverlay(
                    lapTimer = lapTimer,
                    prefs = prefs,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                )
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

                        // 1.1 — DTE + boost (live recording only)
                        if (driveState.isRecording) {
                            val boostPsi = vehicleState.boostPsi
                            MonoText(
                                "BOOST ${"%.1f".format(boostPsi)} PSI",
                                8.sp, if (boostPsi > 16) Orange else if (boostPsi > 8) Warn else Dim
                            )
                            val dte = driveState.distanceToEmptyKm
                            if (dte > 0) {
                                val dteStr = if (prefs.speedUnit == "MPH")
                                    "%.0f mi".format(dte * 0.621371)
                                else "%.0f km".format(dte)
                                MonoText("DTE $dteStr", 8.sp, if (dte < 30) Warn else Dim)
                            }
                        }

                        // 1.4 — Altitude + heading (live only)
                        if (driveState.isRecording) {
                            val loc = driveState.currentLocation
                            if (loc != null) {
                                if (loc.hasAltitude()) {
                                    val altM = loc.altitude.toInt()
                                    val altStr = if (prefs.speedUnit == "MPH")
                                        "${(altM * 3.28084).toInt()} ft"
                                    else "$altM m"
                                    MonoText("ALT $altStr", 8.sp, Dim)
                                }
                                if (loc.hasBearing() && vehicleState.speedKph > 5) {
                                    MonoText("HDG ${loc.bearing.toInt()}°", 8.sp, Dim)
                                }
                                // GPS accuracy dot
                                val accColor = when {
                                    loc.accuracy < 5f  -> Ok
                                    loc.accuracy < 15f -> Warn
                                    else               -> Orange
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(accColor))
                                    MonoLabel("±${"%.0f".format(loc.accuracy)}m", 7.sp, Dim)
                                }
                            }
                        }
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
                        mapBookmarks = emptyList()
                    } else {
                        selectedDriveId = drive.id
                        selectedDrive = drive
                        scope.launch(Dispatchers.IO) {
                            val dao = DriveDatabase.getInstance(context).driveDao()
                            selectedDrivePoints = dao.getPoints(drive.id)
                            mapBookmarks = dao.getBookmarks(drive.id)
                        }
                    }
                },
                onSummary = { drive ->
                    summaryDrive = drive
                    scope.launch(Dispatchers.IO) {
                        summaryPoints = DriveDatabase.getInstance(context).driveDao().getPoints(drive.id)
                    }
                },
                onExport = { drive -> exportDrive = drive },
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
                onTagsChanged = { drive, tags ->
                    scope.launch(Dispatchers.IO) {
                        DriveDatabase.getInstance(context).driveDao().updateDriveTags(drive.id, tags)
                        drives = DriveDatabase.getInstance(context).driveDao()
                            .getRecentDrives(AppSettings.getMaxSavedDrives(context))
                    }
                },
                onReplay = { drive ->
                    replayDrive = drive
                    scope.launch(Dispatchers.IO) {
                        replayPoints = DriveDatabase.getInstance(context).driveDao().getPoints(drive.id)
                    }
                },
                onCompare = { drive ->
                    if (compareA == null) {
                        compareA = drive
                    } else if (compareA!!.id != drive.id) {
                        compareB = drive
                    }
                },
                onBatchExport = { selectedDrives ->
                    scope.launch(Dispatchers.IO) {
                        val dao = DriveDatabase.getInstance(context).driveDao()
                        val drivesWithPoints = selectedDrives.map { d ->
                            d to dao.getPoints(d.id)
                        }
                        DiagnosticExporter.shareDrives(context, drivesWithPoints)
                    }
                },
                modifier = Modifier.weight(0.55f).fillMaxWidth()
            )
        }
    }

    // ── Drive summary sheet ─────────────────────────────────────────
    summaryDrive?.let { drive ->
        DriveSummarySheet(
            drive = drive,
            points = summaryPoints,
            prefs = prefs,
            onShare = {
                scope.launch(Dispatchers.IO) {
                    val pts = summaryPoints.ifEmpty {
                        DriveDatabase.getInstance(context).driveDao().getPoints(drive.id)
                    }
                    DiagnosticExporter.shareDrive(context, drive, pts)
                }
                summaryDrive = null
                summaryPoints = emptyList()
            },
            onDismiss = {
                summaryDrive = null
                summaryPoints = emptyList()
            }
        )
    }

    // ── Drive comparison sheet ───────────────────────────────────────
    // When compareA is set, the next tap on COMPARE picks compareB and shows the sheet
    val compA = compareA
    val compB = compareB
    if (compA != null && compB != null) {
        DriveComparisonSheet(
            driveA = compA,
            driveB = compB,
            prefs = prefs,
            onDismiss = {
                compareA = null
                compareB = null
            }
        )
    }

    // ── Export options sheet ───────────────────────────────────────────
    exportDrive?.let { drive ->
        ExportOptionsSheet(
            drive = drive,
            onExport = { components ->
                exportDrive = null
                scope.launch(Dispatchers.IO) {
                    val dao = DriveDatabase.getInstance(context).driveDao()
                    val pts = dao.getPoints(drive.id)
                    DiagnosticExporter.shareDrive(
                        context, drive, pts, components = components
                    )
                }
            },
            onDismiss = { exportDrive = null }
        )
    }

    // ── Session replay view ──────────────────────────────────────────
    replayDrive?.let { drive ->
        if (replayPoints.isNotEmpty()) {
            DriveReplayView(
                drive = drive,
                points = replayPoints,
                prefs = prefs,
                onDismiss = {
                    replayDrive = null
                    replayPoints = emptyList()
                }
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

    // ── Export progress indicator ────────────────────────────────────
    AnimatedVisibility(
        visible = exportProgress !is ExportProgress.Idle,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val label = when (exportProgress) {
            is ExportProgress.Packaging -> "PACKAGING..."
            is ExportProgress.Sharing -> "SHARING..."
            is ExportProgress.Done -> "DONE"
            is ExportProgress.Error -> "EXPORT FAILED"
            else -> ""
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Surf2)
                .border(Tokens.CardBorder, Brd, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            MonoText(label, 11.sp, if (exportProgress is ExportProgress.Error) Orange else accent, FontWeight.Bold)
        }
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
            // Row 1 — Speed · RPM · Gear · Boost
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DataCell("SPD",
                    "${prefs.displaySpeed(vehicleState.speedKph)} ${prefs.speedLabel}",
                    modifier = Modifier.weight(1f))
                DataCell("RPM", "%.0f".format(vehicleState.rpm), modifier = Modifier.weight(1f))
                DataCell("GEAR", vehicleState.gearDisplay, modifier = Modifier.weight(1f))
                val boostPsi = vehicleState.boostPsi
                DataCell("BOOST", "%.1f".format(boostPsi),
                    valueColor = when {
                        boostPsi > 16 -> Orange
                        boostPsi > 8  -> Warn
                        boostPsi > 0  -> Ok
                        else          -> Frost
                    },
                    sub = "\u2588".repeat((boostPsi.coerceIn(0.0, 22.0) / 22.0 * 5).toInt()),
                    modifier = Modifier.weight(1f))
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

            // Row 4 — Wheel speeds / slip delta (tap to toggle)
            var showSlipDelta by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable { showSlipDelta = !showSlipDelta },
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showSlipDelta) {
                    val vs = vehicleState
                    val frontAvg = (vs.wheelSpeedFL + vs.wheelSpeedFR) / 2.0
                    val rearAvg = (vs.wheelSpeedRL + vs.wheelSpeedRR) / 2.0
                    val leftAvg = (vs.wheelSpeedFL + vs.wheelSpeedRL) / 2.0
                    val rightAvg = (vs.wheelSpeedFR + vs.wheelSpeedRR) / 2.0
                    val frDelta = kotlin.math.abs(frontAvg - rearAvg)
                    val lrDelta = kotlin.math.abs(leftAvg - rightAvg)
                    val flrlDelta = kotlin.math.abs(vs.wheelSpeedFL - vs.wheelSpeedRL)
                    val frrDelta = kotlin.math.abs(vs.wheelSpeedFR - vs.wheelSpeedRR)
                    fun slipColor(d: Double) = when {
                        d > 5 -> Orange; d > 2 -> Warn; else -> Ok
                    }
                    DataCell("F-R", "%.1f".format(frDelta), valueColor = slipColor(frDelta), modifier = Modifier.weight(1f))
                    DataCell("L-R", "%.1f".format(lrDelta), valueColor = slipColor(lrDelta), modifier = Modifier.weight(1f))
                    DataCell("FL-RL", "%.1f".format(flrlDelta), valueColor = slipColor(flrlDelta), modifier = Modifier.weight(1f))
                    DataCell("FR-RR", "%.1f".format(frrDelta), valueColor = slipColor(frrDelta), modifier = Modifier.weight(1f))
                } else {
                    DataCell("FL", "${prefs.displaySpeed(vehicleState.wheelSpeedFL)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                    DataCell("FR", "${prefs.displaySpeed(vehicleState.wheelSpeedFR)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                    DataCell("RL", "${prefs.displaySpeed(vehicleState.wheelSpeedRL)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                    DataCell("RR", "${prefs.displaySpeed(vehicleState.wheelSpeedRR)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                }
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
    onSummary: (DriveEntity) -> Unit,
    onExport: (DriveEntity) -> Unit,
    onDelete: (DriveEntity) -> Unit,
    onRename: (DriveEntity) -> Unit,
    onTagsChanged: (DriveEntity, String) -> Unit = { _, _ -> },
    onReplay: (DriveEntity) -> Unit = {},
    onCompare: (DriveEntity) -> Unit = {},
    onBatchExport: (List<DriveEntity>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accent = LocalThemeAccent.current

    // Multi-select mode
    var multiSelectMode by remember { mutableStateOf(false) }
    val multiSelected = remember { mutableStateListOf<Long>() }

    Column(modifier.background(Surf).padding(horizontal = 8.dp, vertical = 6.dp)) {
        // Header with optional batch export controls
        if (multiSelectMode) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoText("${multiSelected.size} SELECTED", 11.sp, accent, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(accent)
                            .clickable {
                                val selected = drives.filter { it.id in multiSelected }
                                if (selected.isNotEmpty()) onBatchExport(selected)
                                multiSelectMode = false
                                multiSelected.clear()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        MonoText("EXPORT", 10.sp, Bg, FontWeight.Bold)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Surf2)
                            .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                            .clickable {
                                multiSelectMode = false
                                multiSelected.clear()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        MonoText("CANCEL", 10.sp, Mid, FontWeight.Bold)
                    }
                }
            }
        } else {
            SectionLabel(
                "DRIVE HISTORY",
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (drives.isEmpty()) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val autoRecOn = remember { com.openrs.dash.ui.AppSettings.getAutoRecordDrives(ctx) }
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
                        if (autoRecOn)
                            "Connect to your car and tap START — or just drive, auto-record is on"
                        else
                            "Connect and tap START, or enable Auto-record in Settings",
                        9.sp, Dim
                    )
                }
            }
        } else {
            // Group by date
            val grouped = remember(drives) { groupDrivesByDate(drives) }

            var historyVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { historyVisible = true }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                var globalIndex = 0
                grouped.forEach { (dateLabel, groupDrives) ->
                    // Date section header
                    item(key = "header_$dateLabel") {
                        MonoText(
                            dateLabel.uppercase(),
                            9.sp, Dim, FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }

                    groupDrives.forEach { drive ->
                        val index = globalIndex++
                        item(key = drive.id) {
                            val delay = (index * 50).coerceAtMost(400)
                            val itemAlpha by animateFloatAsState(
                                targetValue = if (historyVisible) 1f else 0f,
                                animationSpec = tween(300, delayMillis = delay, easing = EaseOut),
                                label = "driveA$index"
                            )
                            val itemOffsetY by animateDpAsState(
                                targetValue = if (historyVisible) 0.dp else 16.dp,
                                animationSpec = tween(300, delayMillis = delay, easing = EaseOut),
                                label = "driveY$index"
                            )
                            Box(Modifier.graphicsLayer { alpha = itemAlpha; translationY = itemOffsetY.toPx() }) {
                                if (multiSelectMode) {
                                    // Multi-select: checkbox overlay on card
                                    val isChecked = drive.id in multiSelected
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isChecked) accent.copy(alpha = 0.08f) else Surf)
                                            .border(Tokens.CardBorder, if (isChecked) accent else Brd, RoundedCornerShape(10.dp))
                                            .clickable {
                                                if (isChecked) multiSelected.remove(drive.id)
                                                else multiSelected.add(drive.id)
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            Modifier.size(18.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isChecked) accent else Surf2)
                                                .border(Tokens.CardBorder, if (isChecked) accent else Brd, RoundedCornerShape(4.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isChecked) MonoText("\u2713", 11.sp, Bg, FontWeight.Bold)
                                        }
                                        Column(Modifier.weight(1f)) {
                                            val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
                                            val displayName = drive.name ?: timeFormat.format(Date(drive.startTime))
                                            MonoText(displayName, 11.sp, Frost, FontWeight.Bold)
                                            val dist = if (prefs.speedUnit == "MPH")
                                                "%.1f mi".format(drive.distanceKm * 0.621371)
                                            else "%.1f km".format(drive.distanceKm)
                                            MonoLabel(dist, 9.sp, Dim)
                                        }
                                    }
                                } else {
                                    SwipeDriveCard(
                                        drive = drive,
                                        prefs = prefs,
                                        isSelected = selectedId == drive.id,
                                        onClick = { onSelect(drive) },
                                        onLongClick = {
                                            multiSelectMode = true
                                            multiSelected.clear()
                                            multiSelected.add(drive.id)
                                        },
                                        onSummary = { onSummary(drive) },
                                        onExport = { onExport(drive) },
                                        onDelete = { onDelete(drive) },
                                        onRename = { onRename(drive) },
                                        onTagsChanged = { tags -> onTagsChanged(drive, tags) },
                                        onReplay = { onReplay(drive) },
                                        onCompare = { onCompare(drive) }
                                    )
                                }
                            }
                        }
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
    onLongClick: () -> Unit = {},
    onSummary: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onTagsChanged: (String) -> Unit = {},
    onReplay: () -> Unit = {},
    onCompare: () -> Unit = {}
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
            onLongClick = onLongClick,
            onSummary = onSummary,
            onExport = onExport,
            onRename = onRename,
            onTagsChanged = onTagsChanged,
            onReplay = onReplay,
            onCompare = onCompare
        )
    }
}

@Composable
private fun DriveCard(
    drive: DriveEntity,
    prefs: UserPrefs,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onSummary: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onTagsChanged: (String) -> Unit = {},
    onReplay: () -> Unit = {},
    onCompare: () -> Unit = {}
) {
    val context = LocalContext.current
    val accent = LocalThemeAccent.current
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val durationMs = if (drive.endTime > 0) drive.endTime - drive.startTime else 0L
    val durationStr = formatDuration(durationMs)
    val isActive = drive.endTime == 0L

    // Animate selection transitions so the card visually "lifts" into focus
    // instead of snapping — matches the premium feel of the rest of the app.
    val borderColor by animateColorAsState(
        if (isSelected) accent else Brd,
        tween(250), label = "driveCardBrd"
    )
    val bgColor by animateColorAsState(
        if (isSelected) Surf2 else Surf,
        tween(250), label = "driveCardBg"
    )

    @OptIn(ExperimentalFoundationApi::class)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(Tokens.CardBorder, borderColor, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = { onClick() }, onLongClick = { onLongClick() })
            .animateContentSize(tween(250, easing = EaseOut))
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

                // Aggression score badge
                if (drive.aggressionScore > 0) {
                    val scoreColor = when {
                        drive.aggressionScore > 60 -> Orange
                        drive.aggressionScore > 30 -> Warn
                        else -> Ok
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(scoreColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        MonoLabel("${drive.aggressionScore}", 7.sp, scoreColor)
                    }
                }

                // Thermal alert badge — flag drives where a sensor exceeded threshold
                if (hasThermalAnomaly(drive)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Warn.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        MonoLabel("TEMP", 7.sp, Warn)
                    }
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

        // Route thumbnail (or GPS dot fallback)
        if (drive.hasGps && drive.distanceKm > 0) {
            Spacer(Modifier.height(4.dp))
            val thumbFile = remember(drive.id) {
                RouteThumbnail.getCached(context, drive.id)
            }
            if (thumbFile != null) {
                val bitmap = remember(thumbFile) {
                    android.graphics.BitmapFactory.decodeFile(thumbFile.absolutePath)
                }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Route preview",
                        modifier = Modifier.height(40.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(Tokens.CardBorder, Brd, RoundedCornerShape(4.dp))
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Ok.copy(alpha = 0.6f)))
                    MonoLabel("GPS", 7.sp, Ok.copy(alpha = 0.6f))
                }
            }
        }

        // Export + Rename buttons when selected — animate in/out with the card
        // expand so the transition reads as a smooth reveal, not a hard pop.
        AnimatedVisibility(
            visible = isSelected,
            enter = fadeIn(tween(200)) + expandVertically(tween(250, easing = EaseOut)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200, easing = EaseIn))
        ) {
            Column {
                // Tag chips
                Spacer(Modifier.height(6.dp))
                val activeTags = remember(drive.tags) {
                    drive.tags.split(",").filter { it.isNotBlank() }.toMutableStateList()
                }
                val allTags = listOf("TRACK", "COMMUTE", "CRUISE", "SPIRITED", "RAIN")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    allTags.forEach { tag ->
                        val active = tag in activeTags
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (active) accent.copy(alpha = 0.2f)
                                    else Surf3.copy(alpha = 0.5f)
                                )
                                .border(
                                    Tokens.CardBorder,
                                    if (active) accent.copy(alpha = 0.5f) else Brd,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    if (active) activeTags.remove(tag) else activeTags.add(tag)
                                    onTagsChanged(activeTags.joinToString(","))
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            MonoLabel(
                                tag, 7.sp,
                                if (active) accent else Dim
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (drive.endTime > 0) {
                        Button(
                            onClick = onSummary,
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.15f))
                        ) {
                            MonoText("SUMMARY", 11.sp, accent, FontWeight.Bold)
                        }
                    }
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

                // Row 2: REPLAY + COMPARE
                if (drive.endTime > 0 && drive.hasGps) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onReplay,
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Warn.copy(alpha = 0.15f))
                        ) {
                            MonoText("REPLAY", 11.sp, Warn, FontWeight.Bold)
                        }
                        Button(
                            onClick = onCompare,
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Surf3)
                        ) {
                            MonoText("COMPARE", 11.sp, Mid, FontWeight.Bold)
                        }
                    }
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

/**
 * Check if a drive had any sensor exceed its critical threshold.
 * Parses thermalPeaksJson: [{"sensor":"Oil","peakC":140,...}, ...]
 */
private fun hasThermalAnomaly(drive: DriveEntity): Boolean {
    val json = drive.thermalPeaksJson ?: return false
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).any { i ->
            val obj = arr.getJSONObject(i)
            val peakC = obj.getDouble("peakC")
            val critC = obj.getDouble("critThresholdC")
            peakC >= critC * 0.95 // flag at 95% of critical
        }
    } catch (_: Exception) { false }
}

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
