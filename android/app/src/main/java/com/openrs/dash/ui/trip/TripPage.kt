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
import androidx.compose.foundation.indication
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.openrs.dash.demo.TripPreview
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

/**
 * TRIP tab host — owns the Google map's lifetime.
 *
 * - Lazy: the pager composes TRIP as a neighbour (THERMAL / GARAGE) and prefetches it while scrolling
 *   towards it (from PERF). The map is created only once TRIP has been the settled page ([settled]);
 *   before that the map area is a plain placeholder.
 * - Retained: from then on the page pins itself in the pager, so leaving for DRIVE / PERF keeps this
 *   composition — and its MapView, camera, tiles and view state — instead of destroying the map and
 *   rebuilding it on return. The pin is released when the composition is disposed (activity destroy),
 *   which is also when AndroidView destroys the MapView; there is only ever one map instance.
 * - Idle while hidden: the subtree gets a lifecycle capped at CREATED while TRIP isn't on screen
 *   ([visible]), so the MapView is stopped (no rendering, no map location layer), and the live inputs
 *   are held at their last on-screen values so the page doesn't recompose. Recording and location
 *   tracking live in DriveRecorder and are unaffected.
 */
@Composable
fun TripPage(
    driveState: DriveState,
    vehicleState: VehicleState,
    prefs: UserPrefs,
    onMapTouched: (Boolean) -> Unit = {},
    settled: Boolean = true,
    visible: Boolean = true
) {
    var mapCreated by remember { mutableStateOf(false) }
    LaunchedEffect(settled) { if (settled) mapCreated = true }

    val pinnable = LocalPinnableContainer.current
    DisposableEffect(mapCreated, pinnable) {
        val handle = if (mapCreated) pinnable?.pin() else null
        onDispose { handle?.release() }
    }

    val parentOwner = LocalLifecycleOwner.current
    val owner = remember(parentOwner) { VisibilityCappedLifecycleOwner(parentOwner) }
    DisposableEffect(owner) {
        owner.attach()
        onDispose { owner.detach() }
    }
    SideEffect { owner.setVisible(visible) }

    // Debug-only sample states (TripPreview): a recording / paused DriveState that exists only in memory.
    val preview by TripPreview.mode.collectAsState()
    val previewState = remember(preview) {
        when (preview) {
            TripPreview.Mode.RECORDING -> TripPreview.driveState(paused = false)
            TripPreview.Mode.PAUSED -> TripPreview.driveState(paused = true)
            else -> null
        }
    }

    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        TripPageContent(
            driveState = previewState ?: heldWhileHidden(driveState, visible),
            vehicleState = heldWhileHidden(vehicleState, visible),
            prefs = prefs,
            onMapTouched = onMapTouched,
            mapCreated = mapCreated,
            visible = visible,
            preview = preview
        )
    }
}

/** [value] while [visible]; otherwise the last value seen while visible (plain holder — no recomposition). */
@Composable
private fun <T> heldWhileHidden(value: T, visible: Boolean): T {
    val held = remember { arrayOfNulls<Any?>(1).also { it[0] = value } }
    if (visible) held[0] = value
    @Suppress("UNCHECKED_CAST")
    return held[0] as T
}

/**
 * Follows [parent]'s lifecycle but stays at CREATED while the TRIP page is off screen. Views under it
 * (the MapView via AndroidView's ViewTree owner) see ON_PAUSE / ON_STOP when the user leaves TRIP and
 * ON_START / ON_RESUME when TRIP is back — and still follow the activity into the background.
 */
internal class VisibilityCappedLifecycleOwner(private val parent: LifecycleOwner) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private var visible = true
    private val observer = LifecycleEventObserver { _, _ -> sync() }

    fun attach() = parent.lifecycle.addObserver(observer)

    fun detach() {
        parent.lifecycle.removeObserver(observer)
        if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
    }

    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        sync()
    }

    private fun sync() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        val p = parent.lifecycle.currentState
        val cap = if (visible) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        registry.currentState = if (p == Lifecycle.State.DESTROYED) Lifecycle.State.DESTROYED else minOf(p, cap)
    }
}

@Composable
private fun TripPageContent(
    driveState: DriveState,
    vehicleState: VehicleState,
    prefs: UserPrefs,
    onMapTouched: (Boolean) -> Unit,
    mapCreated: Boolean,
    visible: Boolean,
    preview: TripPreview.Mode? = null
) {
    // Room ids are positive; TripPreview's sample drives are negative. Reads for a negative id come from
    // TripPreview and every write path returns early for one, so preview data never reaches the database.
    fun samplePoints(id: Long) = TripPreview.points(id)
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
    // Android 12+ requires COARSE alongside FINE in one request; drive recording needs FINE.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasLocationPerm = result[Manifest.permission.ACCESS_FINE_LOCATION] == true }

    // Map view state is saveable as well: before the first visit and after process death the page is
    // rebuilt, and colour mode, map type, the selected drive and (rememberCameraPositionState) the camera
    // come back.
    // Color mode cycling through all 6
    var colorModeIndex by rememberSaveable { mutableIntStateOf(0) }
    val colorMode = ColorMode.entries[colorModeIndex]

    // Map type cycling: Satellite → Terrain → Normal
    var mapType by rememberSaveable { mutableStateOf(MapType.SATELLITE) }

    // Drive history
    var drives by remember { mutableStateOf<List<DriveEntity>>(emptyList()) }
    var selectedDrivePoints by remember { mutableStateOf<List<DrivePointEntity>>(emptyList()) }
    var selectedDriveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedDrive by remember { mutableStateOf<DriveEntity?>(null) }

    // Bookmarks for the displayed drive
    var mapBookmarks by remember { mutableStateOf<List<DriveBookmarkEntity>>(emptyList()) }

    // Restored selection (page rebuilt from saved state): reload the drive and its track.
    LaunchedEffect(Unit) {
        val id = selectedDriveId ?: return@LaunchedEffect
        if (selectedDrive != null) return@LaunchedEffect
        if (id < 0) { selectedDriveId = null; return@LaunchedEffect }   // a preview selection isn't restored
        withContext(Dispatchers.IO) {
            val dao = DriveDatabase.getInstance(context).driveDao()
            val d = dao.getDrive(id)
            if (d == null) { selectedDriveId = null; return@withContext }
            selectedDrive = d
            selectedDrivePoints = dao.getPoints(id)
            mapBookmarks = dao.getBookmarks(id)
        }
    }

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

    // Post-drive summary: queued app-wide by DriveRecorder until closed here. A summary opened from a
    // history row (summaryDrive) takes precedence; the queued one follows when that closes.
    val pendingSummary by recorder.pendingSummary.drive.collectAsState()
    val shownSummary = summaryDrive ?: pendingSummary
    LaunchedEffect(shownSummary?.id) {
        val id = shownSummary?.id ?: return@LaunchedEffect
        // Points for the time-series chart in the summary
        summaryPoints = if (id < 0) samplePoints(id)
            else withContext(Dispatchers.IO) { DriveDatabase.getInstance(context).driveDao().getPoints(id) }
    }
    fun closeSummary(drive: DriveEntity) {
        if (summaryDrive?.id == drive.id) summaryDrive = null else recorder.pendingSummary.consume(drive.id)
        summaryPoints = emptyList()
        if (drive.id < 0 && preview == TripPreview.Mode.SUMMARY) TripPreview.set(TripPreview.Mode.HISTORY)
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

    // Load drive history (preview: the sample list)
    LaunchedEffect(driveState.isRecording, preview) {
        if (preview != null) { drives = TripPreview.drives(); return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            drives = DriveDatabase.getInstance(context).driveDao()
                .getRecentDrives(AppSettings.getMaxSavedDrives(context))
        }
    }

    // Preview states that need a selection or the summary open; leaving preview drops sample state.
    LaunchedEffect(preview) {
        when (preview) {
            TripPreview.Mode.SELECTED -> TripPreview.drives().first().let { d ->
                selectedDriveId = d.id; selectedDrive = d; selectedDrivePoints = samplePoints(d.id); mapBookmarks = emptyList()
            }
            TripPreview.Mode.SUMMARY -> summaryDrive = TripPreview.drives().first()
            TripPreview.Mode.COMPARE -> TripPreview.drives().let { compareA = it[0]; compareB = it[2] }
            TripPreview.Mode.REPLAY -> TripPreview.drives().first().let { replayDrive = it; replayPoints = samplePoints(it.id) }
            TripPreview.Mode.EXPORT -> exportDrive = TripPreview.drives().first()
            null -> {
                if ((selectedDriveId ?: 0L) < 0) { selectedDriveId = null; selectedDrive = null; selectedDrivePoints = emptyList() }
                if ((summaryDrive?.id ?: 0L) < 0) summaryDrive = null
                if ((compareA?.id ?: 0L) < 0 || (compareB?.id ?: 0L) < 0) { compareA = null; compareB = null }
                if ((replayDrive?.id ?: 0L) < 0) { replayDrive = null; replayPoints = emptyList() }
                if ((exportDrive?.id ?: 0L) < 0) exportDrive = null
            }
            else -> {}
        }
    }

    // Recording indicator pulse — only runs while the REC badge is on screen (an infinite transition
    // asks for a frame every vsync for as long as it is composed, and this page now stays composed).
    val recAlpha = if (visible && driveState.isRecording) {
        val a by rememberInfiniteTransition(label = "rec").animateFloat(
            initialValue = 1f, targetValue = 0.2f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "recAlpha"
        )
        a
    } else 1f

    // Export progress
    val exportProgress by DiagnosticExporter.exportProgress.collectAsState()

    val historyPreview = preview != null && preview != TripPreview.Mode.RECORDING && preview != TripPreview.Mode.PAUSED
    val isLive = !historyPreview && (vehicleState.isConnected || driveState.isRecording)
    val cameraPositionState = rememberCameraPositionState()

    Column(Modifier.fillMaxSize().background(Bg).padding(bottom = com.openrs.dash.ui.navBarClearance())) {
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

            if (mapCreated) DriveMap(
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
                cameraPositionState = cameraPositionState,
                prefs = prefs
            ) else Box(Modifier.fillMaxSize().background(Surf2))

            // ── Floating controls (top-right stack) ─────────────────
            // rc.2: absorb all pointer events so taps that start on a control
            // and drag slightly don't reach the underlying Google Maps
            // AndroidView and pan the map.
            var controlsVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { controlsVisible = true }
            // Design pass: each map control is a 48 × 48 dp touch area around a compact visible chip
            // (MapControl); touch areas sit edge to edge, so they never overlap and the chips show an 8 dp gap.
            // Short map areas (offline on a 360 × 720 dp phone, ~245 dp) pair zoom +/− in one row so the stack
            // — up to the locate button — still fits.
            BoxWithConstraints(Modifier.matchParentSize()) {
            val compactMap = maxHeight < 300.dp
            val chipSize = if (compactMap) 36.dp else 40.dp
            val ink = actionInk(accent)   // DAY: darker paint for text on the light chips; NIGHT: the paint
            Column(
                Modifier.align(Alignment.TopEnd).padding(if (compactMap) 4.dp else 8.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
                horizontalAlignment = Alignment.End
            ) {
                // Color mode toggle
                MapControl(chipSize, pageEntrance(0, controlsVisible, 50), "Route colour: ${colorMode.label}", onClick = {
                    colorModeIndex = (colorModeIndex + 1) % ColorMode.entries.size
                }) { MonoText(colorMode.label, 11.sp, ink, FontWeight.Bold) }

                // Map type toggle
                val typeLabel = when (mapType) {
                    MapType.SATELLITE -> "SAT"
                    MapType.TERRAIN -> "TER"
                    else -> "MAP"
                }
                MapControl(chipSize, pageEntrance(1, controlsVisible, 50), "Map type: $typeLabel", onClick = {
                    mapType = when (mapType) {
                        MapType.NORMAL -> MapType.SATELLITE
                        MapType.SATELLITE -> MapType.TERRAIN
                        else -> MapType.NORMAL
                    }
                }) { MonoText(typeLabel, 11.sp, Frost, FontWeight.Bold) }

                // Weather card (not interactive)
                driveState.currentWeather?.let { weather ->
                    Box(
                        pageEntrance(2, controlsVisible, 50)
                            .padding(vertical = 4.dp)
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
                            MonoText(weather.description, 10.sp, Dim)
                        }
                    }
                }

                // Zoom in / out — stacked, or side by side on a short map
                val zoomIn: @Composable () -> Unit = {
                    MapControl(chipSize, pageEntrance(3, controlsVisible, 50), "Zoom in", onClick = {
                        scope.launch { cameraPositionState.animate(CameraUpdateFactory.zoomIn()) }
                    }) { MonoText("+", 16.sp, Frost, FontWeight.Bold) }
                }
                val zoomOut: @Composable () -> Unit = {
                    MapControl(chipSize, pageEntrance(4, controlsVisible, 50), "Zoom out", onClick = {
                        scope.launch { cameraPositionState.animate(CameraUpdateFactory.zoomOut()) }
                    }) { MonoText("\u2212", 16.sp, Frost, FontWeight.Bold) }
                }
                if (compactMap) Row { zoomOut(); zoomIn() } else { zoomIn(); zoomOut() }

                // Locate / recenter
                if (currentLat != null && currentLng != null) {
                    MapControl(chipSize, pageEntrance(5, controlsVisible, 50), "Recentre on my location", onClick = {
                        scope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(LatLng(currentLat, currentLng), 15f), 500
                            )
                        }
                    }) { MonoText("\u25CE", 16.sp, ink, FontWeight.Bold) }
                }

                // SET S/F button (lap timer)
                if (driveState.isRecording) {
                    val sfSet = lapTimer.startFinish != null
                    MapControl(chipSize, pageEntrance(6, controlsVisible, 50),
                        if (sfSet) "Clear start/finish line" else "Set start/finish line", onClick = {
                        if (preview != null) return@MapControl
                        if (sfSet) lapTimer.clearStartFinish()
                        else driveState.currentLocation?.let { loc ->
                            lapTimer.setStartFinish(loc.latitude, loc.longitude, loc.bearing)
                        }
                    }) { MonoText(if (sfSet) "CLEAR S/F" else "SET S/F", 11.sp, if (sfSet) Orange else ink, FontWeight.Bold) }

                    // Bookmark button — an action, not a warning: ink, not amber.
                    MapControl(chipSize, pageEntrance(7, controlsVisible, 50), "Bookmark this spot", onClick = {
                        if (preview == null) recorder.addBookmark()
                    }) { MonoText("\u2691 MARK", 11.sp, Frost, FontWeight.Bold) }
                }
            }

            }

            // ── Lap timer overlay (top-start, during recording) ────
            if (visible && driveState.isRecording && lapTimer.startFinish != null) {
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
                    MonoText("REC", 10.sp, Orange, FontWeight.Bold)
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
                    // Paused is a state, not a warning — ink (was amber).
                    MonoText("\u2759\u2759 PAUSED", 10.sp, Frost, FontWeight.Bold)
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
                    // Above the colour legend when it shows — on a 360 dp phone the bottom-centre legend ran
                    // over this box ("AVG 43 mph" was cut off).
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (mapPoints.isNotEmpty()) 44.dp else 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surf.copy(alpha = 0.85f))
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val dist = if (prefs.speedUnit == "MPH")
                            "%.1f mi".format(statDist * 0.621371)
                        else "%.1f km".format(statDist)
                        MonoText(dist, 12.sp, Frost, FontWeight.Bold)
                        MonoText(formatDuration(statDuration), 10.sp, Mid)
                        val avgSpd = if (prefs.speedUnit == "MPH")
                            "%.0f mph".format(statAvgSpd * 0.621371)
                        else "%.0f km/h".format(statAvgSpd)
                        MonoText("AVG $avgSpd", 10.sp, Dim)

                        // 1.1 — DTE + boost (live recording only)
                        if (driveState.isRecording) {
                            val boostPsi = vehicleState.boostPsi
                            // Boost bands (8 / 16 psi) weren't limits — read it in ink like the live panel.
                            MonoText("BOOST ${prefs.displayBoost(vehicleState.boostKpa).let { (v, u) -> "$v $u" }}", 10.sp, Dim)
                            val dte = driveState.distanceToEmptyKm
                            if (dte > 0) {
                                val dteStr = if (prefs.speedUnit == "MPH")
                                    "%.0f mi".format(dte * 0.621371)
                                else "%.0f km".format(dte)
                                MonoText("DTE $dteStr", 10.sp, if (dte < 30) Warn else Dim)
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
                                    MonoText("ALT $altStr", 10.sp, Dim)
                                }
                                if (loc.hasBearing() && vehicleState.speedKph > 5) {
                                    MonoText("HDG ${loc.bearing.toInt()}°", 10.sp, Dim)
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
                                    MonoLabel("±${"%.0f".format(loc.accuracy)}m", 9.sp, Dim)
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
                    val unit = legendUnit(colorMode, prefs)
                    MonoText(if (unit.isEmpty()) colorMode.label else "${colorMode.label} $unit", 10.sp, actionInk(accent), FontWeight.Bold)
                    colorLegend(colorMode, prefs).forEach { (label, color) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape).background(color)
                            )
                            MonoLabel(label, 9.sp, Dim)
                        }
                    }
                }
            }
        }

        // Debug preview: say plainly that this is sample data and nothing is saved.
        if (preview != null) {
            Box(
                Modifier.fillMaxWidth().background(Warn.copy(alpha = pillBgAlpha(0.12f)))
                    .padding(horizontal = Tokens.PagePad, vertical = 4.dp)
            ) { InstrumentLabel("PREVIEW · SAMPLE DATA — NOT SAVED · ${preview.name}", color = demoInk()) }
        }

        // ── Bottom section: HUD (live) or History (idle) ─────────────
        if (isLive) {
            LiveHud(
                vehicleState = vehicleState,
                driveState = driveState,
                prefs = prefs,
                hasLocationPerm = hasLocationPerm,
                onRequestPermission = {
                    permLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ))
                },
                // Preview: the buttons only step through the sample states — the recorder is never called.
                onStart = { if (preview != null) TripPreview.set(TripPreview.Mode.RECORDING) else recorder.startDrive(sessionId = 0) },
                onPause = { if (preview != null) TripPreview.set(TripPreview.Mode.PAUSED) else recorder.pauseDrive() },
                onResume = { if (preview != null) TripPreview.set(TripPreview.Mode.RECORDING) else recorder.resumeDrive() },
                onStop = {
                    if (preview != null) { TripPreview.set(TripPreview.Mode.SUMMARY); return@LiveHud }
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
                        if (drive.id < 0) { selectedDrivePoints = samplePoints(drive.id); mapBookmarks = emptyList() }
                        else scope.launch(Dispatchers.IO) {
                            val dao = DriveDatabase.getInstance(context).driveDao()
                            selectedDrivePoints = dao.getPoints(drive.id)
                            mapBookmarks = dao.getBookmarks(drive.id)
                        }
                    }
                },
                onSummary = { drive -> summaryDrive = drive },   // points load with the sheet (shownSummary)
                onExport = { drive -> exportDrive = drive },   // the sheet's EXPORT no-ops for a sample drive
                onDelete = { drive ->
                    if (drive.id < 0) return@DriveHistoryList
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
                    if (drive.id < 0) return@DriveHistoryList
                    renameDriveId = drive.id
                    renameText = drive.name ?: ""
                },
                onTagsChanged = { drive, tags ->
                    if (drive.id < 0) return@DriveHistoryList
                    scope.launch(Dispatchers.IO) {
                        DriveDatabase.getInstance(context).driveDao().updateDriveTags(drive.id, tags)
                        drives = DriveDatabase.getInstance(context).driveDao()
                            .getRecentDrives(AppSettings.getMaxSavedDrives(context))
                    }
                },
                onReplay = { drive ->
                    replayDrive = drive
                    if (drive.id < 0) { replayPoints = samplePoints(drive.id); return@DriveHistoryList }
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
                    if (selectedDrives.any { it.id < 0 }) return@DriveHistoryList
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
    // Modal sheet = its own window: only present it on TRIP, or a drive ending while DRIVE is open would
    // pop TRIP's summary over it. A summary that arrives off screen waits for the next TRIP visit, and
    // closing it consumes it — it is shown once, not on every return to TRIP.
    shownSummary?.takeIf { visible }?.let { drive ->
        DriveSummarySheet(
            drive = drive,
            points = summaryPoints,
            prefs = prefs,
            onShare = {
                if (drive.id < 0) { closeSummary(drive); return@DriveSummarySheet }   // preview: nothing exported
                scope.launch(Dispatchers.IO) {
                    val pts = summaryPoints.ifEmpty {
                        DriveDatabase.getInstance(context).driveDao().getPoints(drive.id)
                    }
                    DiagnosticExporter.shareDrive(context, drive, pts)
                }
                closeSummary(drive)
            },
            onDismiss = { closeSummary(drive) },
            trendDrives = if (drive.id < 0) TripPreview.drives() else null,
            previewNotice = drive.id < 0
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
            },
            previewNotice = compA.id < 0 || compB.id < 0
        )
    }

    // ── Export options sheet ───────────────────────────────────────────
    exportDrive?.let { drive ->
        ExportOptionsSheet(
            drive = drive,
            onExport = { components ->
                exportDrive = null
                if (drive.id < 0) return@ExportOptionsSheet   // preview: nothing is exported
                scope.launch(Dispatchers.IO) {
                    val dao = DriveDatabase.getInstance(context).driveDao()
                    val pts = dao.getPoints(drive.id)
                    DiagnosticExporter.shareDrive(
                        context, drive, pts, components = components
                    )
                }
            },
            onDismiss = { exportDrive = null },
            prefs = prefs,
            previewNotice = drive.id < 0
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
                },
                previewNotice = drive.id < 0
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
            .background(Bg)
            .padding(horizontal = Tokens.PagePad)
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Design pass: readings in ink, BOOST in the paint accent (as on DRIVE), amber / orange only past
            // an existing limit — THERMAL preset temps, fuel < 10 %. The old boost colours (green > 0, amber
            // > 8, orange > 16 psi) weren't limits. Recording can continue through an adapter drop; the last
            // frame is held in VehicleState: per-frame values go to placeholders, slow values stay, STALE.
            val live = vehicleState.isConnected
            val ph = "— —"
            fun temp(label: String, valueC: Double, tag: String, warnC: Double? = null, critC: Double? = null): Pair<Readout, Level> {
                if (valueC <= -90) return Readout(label, ph) to Level.NONE
                val avail = tempAvailFor(valueC, vehicleState.fieldLastUpdateMs[tag]).whenDisconnected(live)
                val lvl = when {
                    avail == TempAvail.STALE -> Level.STALE
                    warnC != null && critC != null -> tempLevel(valueC, warnC, critC, avail)
                    else -> Level.NORMAL
                }
                return Readout(label, "${prefs.displayTemp(valueC)}${prefs.tempLabel}",
                    color = if (lvl.isAlert) levelColor(lvl) else Color.Unspecified,
                    sub = if (lvl == Level.STALE) "STALE" else null) to lvl
            }
            val (clt, cltL) = temp("COOLANT", vehicleState.coolantTempC, "coolantTempC", prefs.coolWarnC, prefs.coolCritC)
            val (oil, oilL) = temp("OIL", vehicleState.oilTempC, "oilTempC", prefs.oilWarnC, prefs.oilCritC)
            val (amb, _) = temp("AMBIENT", vehicleState.ambientTempC, "ambientTempC")
            val (rdu, rduL) = temp("RDU", vehicleState.rduTempC, "rduTempC", prefs.rduWarnC, prefs.rduCritC)
            val (ptu, ptuL) = temp("PTU", vehicleState.ptuTempC, "ptuTempC", prefs.ptuWarnC, prefs.ptuCritC)
            val hasFuel = vehicleState.fuelLevelPct > 0
            val fuelLow = hasFuel && vehicleState.fuelLevelPct < 10.0          // DRIVE "LOW FUEL" rule
            val fuel = Readout("FUEL", if (hasFuel) "%.0f%%".format(vehicleState.fuelLevelPct) else ph,
                color = if (fuelLow && live) Warn else Color.Unspecified,
                sub = if (hasFuel && !live) "STALE" else null)
            // Unavailable → "—" with its unit (same rule as DRIVE ECONOMY), not a computed 0.
            val used = Readout("FUEL USED", if (driveState.fuelUsedL > 0) "%.2f L".format(driveState.fuelUsedL) else "— L")
            val econ = if (prefs.speedUnit == "MPH")
                Readout("ECONOMY", if (driveState.avgFuelMpg > 0) "%.1f MPG".format(driveState.avgFuelMpg) else "— MPG")
            else
                Readout("ECONOMY", if (driveState.avgFuelL100km > 0) "%.1f L/100".format(driveState.avgFuelL100km) else "— L/100")
            val panelLevel = worstLevel(listOf(cltL, oilL, rduL, ptuL) + if (fuelLow && live) listOf(Level.WARN) else emptyList())

            // Wheel speeds / slip Δ — same tap-to-toggle as before, now with a visible switch; one panel with
            // the readings above so everything fits under the map without scrolling.
            var showSlipDelta by remember { mutableStateOf(false) }
            ReadoutGrid(
                rows = listOf(
                    listOf(
                        Readout("SPEED", if (live) "${prefs.displaySpeed(vehicleState.speedKph)} ${prefs.speedLabel}" else ph),
                        Readout("RPM", if (live) "%.0f".format(vehicleState.rpm) else ph),
                        Readout("GEAR", if (live) vehicleState.gearDisplay else ph),
                        // Value + the user's pressure unit; DAY reads the darker paint (actionInk) like the map labels.
                        Readout("BOOST", if (live) prefs.displayBoost(vehicleState.boostKpa).let { (v, u) -> "$v $u" } else ph,
                            color = actionInk(accent)),
                    ),
                    listOf(clt, oil, amb, fuel),
                    listOf(rdu, ptu, used, econ),
                ),
                level = panelLevel,
                dense = true,
                footer = {
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(onClickLabel = if (showSlipDelta) "Show wheel speeds" else "Show slip deltas") {
                                showSlipDelta = !showSlipDelta
                            },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.4f))))
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            InstrumentLabel(if (showSlipDelta) "SLIP Δ (${prefs.speedLabel})" else "WHEEL SPEEDS (${prefs.speedLabel})", color = Mid)
                            Spacer(Modifier.weight(1f))
                            InstrumentMeta(if (showSlipDelta) "SHOW SPEEDS ›" else "SHOW SLIP Δ ›")
                        }
                        Row(Modifier.fillMaxWidth()) {
                            val vs = vehicleState
                            val cells: List<Pair<String, String>> = when {
                                !live -> listOf("FL", "FR", "RL", "RR").map { it to ph }
                                showSlipDelta -> {
                                    val frontAvg = (vs.wheelSpeedFL + vs.wheelSpeedFR) / 2.0
                                    val rearAvg = (vs.wheelSpeedRL + vs.wheelSpeedRR) / 2.0
                                    val leftAvg = (vs.wheelSpeedFL + vs.wheelSpeedRL) / 2.0
                                    val rightAvg = (vs.wheelSpeedFR + vs.wheelSpeedRR) / 2.0
                                    listOf(
                                        "F-R" to "%.1f".format(kotlin.math.abs(frontAvg - rearAvg)),
                                        "L-R" to "%.1f".format(kotlin.math.abs(leftAvg - rightAvg)),
                                        "FL-RL" to "%.1f".format(kotlin.math.abs(vs.wheelSpeedFL - vs.wheelSpeedRL)),
                                        "FR-RR" to "%.1f".format(kotlin.math.abs(vs.wheelSpeedFR - vs.wheelSpeedRR)),
                                    )
                                }
                                else -> listOf(
                                    "FL" to prefs.displaySpeed(vs.wheelSpeedFL), "FR" to prefs.displaySpeed(vs.wheelSpeedFR),
                                    "RL" to prefs.displaySpeed(vs.wheelSpeedRL), "RR" to prefs.displaySpeed(vs.wheelSpeedRR),
                                )
                            }
                            cells.forEach { (label, value) ->
                                // Slip keeps its existing bands (> 2 amber, > 5 orange); in range reads in ink, not green.
                                val d = if (showSlipDelta && live) value.toDoubleOrNull() ?: 0.0 else 0.0
                                val c = when { d > 5 -> Orange; d > 2 -> Warn; else -> Frost }
                                Column(Modifier.weight(1f)) {
                                    InstrumentLabel(label)
                                    Spacer(Modifier.height(2.dp))
                                    DataNum(value, 16.sp, if (value.startsWith("—")) Dim else c)
                                }
                            }
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Recording controls — same actions; START / RESUME are the accent action, PAUSE neutral,
        // STOP an orange outline (ends the drive) rather than a second solid colour block.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val demo by com.openrs.dash.demo.DemoMode.status.collectAsState()
            if (!driveState.isRecording && demo.active) {
                // Simulated telemetry is never written to trip history.
                Box(
                    Modifier.weight(1f).height(48.dp).instrumentSurface(),
                    contentAlignment = Alignment.Center
                ) { InstrumentLabel("DEMO — NOT RECORDED", color = Dim) }
            } else if (!driveState.isRecording) {
                RecButton("START", accent, filled = true, Modifier.weight(1f)) {
                    if (!hasLocationPerm) onRequestPermission() else onStart()
                }
            } else if (driveState.isPaused) {
                RecButton("RESUME", accent, filled = true, Modifier.weight(1f), onResume)
                RecButton("STOP", Orange, filled = false, Modifier.weight(0.5f), onStop)
            } else {
                RecButton("PAUSE", Mid, filled = false, Modifier.weight(1f), onPause)
                RecButton("STOP", Orange, filled = false, Modifier.weight(0.5f), onStop)
            }
        }
    }
}

/**
 * Map overlay control: a compact visible chip ([chipSize]) centred in a 48 × 48 dp touch area. The press
 * ripple is drawn on the chip only; wide labels (SET S/F, MARK) widen both.
 */
@Composable
private fun MapControl(
    chipSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.sizeIn(minWidth = chipSize, minHeight = chipSize)
                .clip(shape)
                .background(Surf.copy(alpha = 0.85f))
                .border(Tokens.CardBorder, Brd, shape)
                .indication(interaction, androidx.compose.material3.ripple())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

/** 48 dp recording action: filled (primary) or outlined in [color]. */
@Composable
private fun RecButton(label: String, color: Color, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(48.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(color, shape)
                  else Modifier.background(InstrumentFill, shape).border(1.dp, color.copy(alpha = borderAlpha(0.7f)), shape))
            .clickable(onClickLabel = label.lowercase()) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        MonoText(label, 13.sp, if (filled) Bg else textColor(color), FontWeight.Bold)
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
    modifier: Modifier = Modifier,
    onTagsChanged: (DriveEntity, String) -> Unit = { _, _ -> },
    onReplay: (DriveEntity) -> Unit = {},
    onCompare: (DriveEntity) -> Unit = {},
    onBatchExport: (List<DriveEntity>) -> Unit = {}
) {
    val accent = LocalThemeAccent.current

    // Multi-select mode
    var multiSelectMode by remember { mutableStateOf(false) }
    val multiSelected = remember { mutableStateListOf<Long>() }

    Column(modifier.background(Bg).padding(horizontal = Tokens.PagePad, vertical = 8.dp)) {
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
                    .instrumentSurface()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UIText("No drives recorded yet", 15.sp, Frost, FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    InstrumentMeta(
                        if (autoRecOn)
                            "Connect to your car and tap START — or just drive, auto-record is on"
                        else
                            "Connect and tap START, or enable Auto-record in Settings"
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
    // Design pass: the instrument surface like every other panel; selection = an accent edge.
    val borderColor by animateColorAsState(
        if (isSelected) accent else Color.Transparent,
        tween(250), label = "driveCardBrd"
    )

    @OptIn(ExperimentalFoundationApi::class)
    Column(
        Modifier
            .fillMaxWidth()
            .instrumentSurface()
            .border(1.5.dp, borderColor, RoundedCornerShape(Tokens.CardRadius))
            .clip(RoundedCornerShape(Tokens.CardRadius))
            .combinedClickable(onClick = { onClick() }, onLongClick = { onLongClick() })
            .animateContentSize(tween(250, easing = EaseOut))
            .padding(horizontal = 14.dp, vertical = 12.dp)
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
                UIText(
                    displayName, 14.sp, Frost, FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false).clickable { onRename() }
                )

                // Badges only for states worth noticing — every finished drive used to say COMPLETE
                // (green) and the intensity score was coloured on bands that aren't limits.
                if (isActive) CardBadge("ACTIVE", Orange)
                if (!drive.hasGps) CardBadge("NO GPS", Dim)
                if (hasThermalAnomaly(drive)) CardBadge("TEMP", Warn)   // ≥ 95 % of a critical limit
                if (drive.aggressionScore > 0) CardBadge("SCORE ${drive.aggressionScore}", Mid)
            }

            MonoText(durationStr, 12.sp, Mid, FontWeight.Medium)
        }

        Spacer(Modifier.height(10.dp))

        // Peaks — one readout row with units (was 7 sp captions over unlabelled 9 sp numbers)
        val cells = buildList {
            if (drive.hasGps && drive.distanceKm > 0) add("DIST" to if (prefs.speedUnit == "MPH")
                "%.1f mi".format(drive.distanceKm * 0.621371) else "%.1f km".format(drive.distanceKm))
            if (drive.maxSpeedKph > 0) add("TOP" to "${prefs.displaySpeed(drive.maxSpeedKph)} ${prefs.speedLabel.lowercase()}")
            if (drive.peakRpm > 0) add("RPM" to "${drive.peakRpm}")
            if (drive.peakBoostPsi > 0) add("BOOST" to prefs.displayBoost(drive.peakBoostPsi / UnitConversions.KPA_TO_PSI +
                UnitConversions.STD_ATM_KPA).let { (v, u) -> "$v ${u.lowercase()}" })
            if (drive.peakLateralG > 0) add("LAT G" to "%.2f g".format(drive.peakLateralG))
        }
        Row(Modifier.fillMaxWidth()) {
            cells.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    InstrumentLabel(label)
                    Spacer(Modifier.height(2.dp))
                    DataNum(value, 14.sp, Frost)
                }
            }
            repeat(5 - cells.size) { Spacer(Modifier.weight(1f)) }
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
                Spacer(Modifier.height(10.dp))
                val activeTags = remember(drive.tags) {
                    drive.tags.split(",").filter { it.isNotBlank() }.toMutableStateList()
                }
                val allTags = listOf("TRACK", "COMMUTE", "CRUISE", "SPIRITED", "RAIN")
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                .heightIn(min = 32.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MonoLabel(
                                tag, 10.sp,
                                if (active) actionInk(accent) else Dim
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                // Actions: SUMMARY / REPLAY (look at the drive) take the accent; SHARE / RENAME / COMPARE are
                // neutral. REPLAY used to be amber, which reads as a warning. 44 dp tall (were 36).
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (drive.endTime > 0) CardAction("SUMMARY", primary = true, Modifier.weight(1f), onSummary)
                    CardAction("SHARE", primary = false, Modifier.weight(1f), onExport)
                    CardAction("RENAME", primary = false, Modifier.weight(1f), onRename)
                }
                if (drive.endTime > 0 && drive.hasGps) {
                    Spacer(Modifier.height(4.dp))   // 48 dp touch rows 4 dp apart = 8 dp between the 44 dp buttons
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CardAction("REPLAY", primary = true, Modifier.weight(1f), onReplay)
                        CardAction("COMPARE", primary = false, Modifier.weight(1f), onCompare)
                    }
                }
            }
        }
    }
}

/** Small status chip on a drive card (tinted surface, 10 sp). */
@Composable
private fun CardBadge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = pillBgAlpha(0.12f)))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) { MonoLabel(text, 10.sp, color, FontWeight.Bold) }
}

/**
 * Drive card action: a 44 dp visible button (accent-tinted primary or neutral) centred in a 48 dp-tall touch
 * area that spans the button's full width. Rows sit 4 dp apart, so touch areas never overlap and the visible
 * buttons keep an 8 dp gap.
 */
@Composable
private fun CardAction(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalThemeAccent.current
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label.lowercase()) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .clip(shape)
                .background(if (primary) accent.copy(alpha = pillBgAlpha(0.14f)) else Surf3.copy(alpha = 0.6f), shape)
                .border(Tokens.CardBorder, if (primary) accent.copy(alpha = borderAlpha(0.5f)) else Brd.copy(alpha = borderAlpha(0.6f)), shape)
                .indication(interaction, androidx.compose.material3.ripple()),
            contentAlignment = Alignment.Center
        ) { MonoText(label, 12.sp, if (primary) actionInk(accent) else Mid, FontWeight.Bold) }
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
