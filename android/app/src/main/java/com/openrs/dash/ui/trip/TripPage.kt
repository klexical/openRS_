package com.openrs.dash.ui.trip

import android.Manifest
import android.content.Context
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openrs.dash.BuildConfig
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.PeakType
import com.openrs.dash.data.TripState
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.WeatherData
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.BarlowCond
import com.openrs.dash.ui.Bg
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
import com.openrs.dash.ui.UIText
import com.openrs.dash.ui.UserPrefs
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices
import com.openrs.dash.ui.Warn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.config.Configuration
import kotlin.coroutines.resume
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** CartoDB Dark Matter tiles — dark-themed OSM tiles, free for personal use. */
private fun cartoDarkTiles() = XYTileSource(
    "CartoDB.DarkMatter", 1, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/",
        "https://d.basemaps.cartocdn.com/dark_all/"
    ),
    "© OpenStreetMap contributors © CARTO"
)

/** Programmatic cyan position dot for the current GPS location marker. */
private fun createPositionDot(ctx: Context): android.graphics.drawable.Drawable {
    val size = 32
    val bmp  = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    paint.style = android.graphics.Paint.Style.FILL
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1f, paint)
    paint.color = android.graphics.Color.argb(255, 0, 210, 255)  // #00D2FF
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 5f, paint)
    return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
}

/** Which parameter drives the route polyline color. */
private enum class ColorMode { SPEED, DRIVE_MODE }

/**
 * Full-screen trip overlay — OpenStreetMap (CartoDB Dark Matter) with live telemetry HUD.
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
    val ctx    = LocalContext.current
    val accent = LocalThemeAccent.current
    val scope  = rememberCoroutineScope()

    // ── Location permission ───────────────────────────────────────────────────
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
    val sheetState  = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Recording dot animation (was in TripHeader) ────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "rec")
    val recAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.2f, label = "recDot",
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse)
    )

    // ── Polyline segments — rebuilt only when point list grows or mode changes ──
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
                    DriveMode.SPORT  -> Warn
                    DriveMode.TRACK  -> Ok
                    DriveMode.DRIFT  -> Orange
                    DriveMode.CUSTOM -> accent
                    else             -> Accent
                }
            }
            listOf(GeoPoint(a.lat, a.lng), GeoPoint(b.lat, b.lng)) to color
        }
    }

    // Read window insets ONCE from Android's View layer (stable, not affected by
    // permission-dialog dismiss animations that briefly set Compose LocalWindowInsets to 0).
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topPad = remember(view) {
        val insets = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        if (insets != null) with(density) { insets.top.toDp() } else 0.dp
    }
    val bottomPad = remember(view) {
        val insets = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        if (insets != null) with(density) { insets.bottom.toDp() } else 0.dp
    }
    // Edge-to-edge: no top padding on outer Box — map fills behind status bar,
    // floating controls are positioned below the status bar using topPad.
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {

            // ── Map (edge-to-edge, floating controls) ────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
            ) {
                if (hasLocationPerm) {
                    OsmTripMap(
                        tripState = tripState,
                        segments  = segments,
                        modifier  = Modifier.fillMaxSize()
                    )

                    // ── Floating back arrow (top-left, below status bar) ──────
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(top = topPad + 8.dp, start = 12.dp)
                            .size(32.dp)
                            .background(Bg.copy(alpha = 0.82f), RoundedCornerShape(8.dp))
                            .border(1.dp, Brd, RoundedCornerShape(8.dp))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) { UIText("←", 14.sp, Mid) }

                    // ── Floating SPD/MODE toggle (top-right, below status bar) ─
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = topPad + 8.dp, end = 12.dp)
                            .background(Bg.copy(alpha = 0.82f), RoundedCornerShape(4.dp))
                            .border(1.dp, Brd, RoundedCornerShape(4.dp))
                            .clickable {
                                colorMode = if (colorMode == ColorMode.SPEED) ColorMode.DRIVE_MODE
                                            else ColorMode.SPEED
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        MonoLabel(
                            if (colorMode == ColorMode.SPEED) "SPD" else "MODE",
                            9.sp, accent, FontWeight.Bold, 0.15.sp
                        )
                    }

                    // ── Floating REC indicator (top-center, only when recording) ─
                    if (tripState.isRecording) {
                        Row(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = topPad + 10.dp)
                                .background(Bg.copy(alpha = 0.82f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Orange.copy(alpha = recAlpha)))
                            MonoLabel("REC", 9.sp, Orange, FontWeight.Bold, 0.2.sp)
                        }
                    }

                    // ── Weather overlay (top-right corner, below SPD toggle) ──
                    tripState.currentWeather?.let { weather ->
                        WeatherCard(
                            weather  = weather,
                            prefs    = prefs,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = topPad + 46.dp, end = 8.dp)
                        )
                    }

                    // ── Color legend strip (bottom-left of map) ───────────────
                    ColorLegend(
                        colorMode = colorMode,
                        modifier  = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    )

                } else {
                    // Permission prompt — push content below status bar
                    Box(
                        Modifier.fillMaxSize().background(Surf).padding(top = topPad),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            // Back arrow floated even on permission screen
                            Box(
                                Modifier
                                    .align(Alignment.Start)
                                    .size(32.dp)
                                    .background(Surf2, RoundedCornerShape(8.dp))
                                    .border(1.dp, Brd, RoundedCornerShape(8.dp))
                                    .clickable { onDismiss() },
                                contentAlignment = Alignment.Center
                            ) { UIText("←", 14.sp, Mid) }
                            MonoText(
                                "Location access required\nfor trip tracking",
                                14.sp, Mid, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
                    DataCell("SPD",
                        "${prefs.displaySpeed(vehicleState.speedKph)} ${prefs.speedLabel}",
                        modifier = Modifier.weight(1f))
                    DataCell("RPM",     "%.0f".format(vehicleState.rpm),       modifier = Modifier.weight(1f))
                    DataCell("GEAR",    vehicleState.gearDisplay,               modifier = Modifier.weight(1f))
                    DataCell("AVG RPM", "%.0f".format(tripState.avgRpm),        modifier = Modifier.weight(1f))
                }

                // Row 2 — Coolant · Oil · Ambient · Fuel %
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DataCell("CLT",  "${prefs.displayTemp(vehicleState.coolantTempC)}${prefs.tempLabel}", modifier = Modifier.weight(1f))
                    DataCell("OIL",  "${prefs.displayTemp(vehicleState.oilTempC)}${prefs.tempLabel}",     modifier = Modifier.weight(1f))
                    DataCell("AMB",  "${prefs.displayTemp(vehicleState.ambientTempC)}${prefs.tempLabel}", modifier = Modifier.weight(1f))
                    DataCell("FUEL", "%.0f%%".format(vehicleState.fuelLevelPct),                          modifier = Modifier.weight(1f))
                }

                // Row 3 — RDU · PTU · Fuel used · Economy
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DataCell("RDU",
                        if (vehicleState.rduTempC > -90)
                            "${prefs.displayTemp(vehicleState.rduTempC)}${prefs.tempLabel}" else "--",
                        modifier = Modifier.weight(1f))
                    DataCell("PTU",  "${prefs.displayTemp(vehicleState.ptuTempC)}${prefs.tempLabel}", modifier = Modifier.weight(1f))
                    DataCell("USED", "%.2fL".format(tripState.fuelUsedL),                             modifier = Modifier.weight(1f))
                    val (econVal, econUnit) = if (prefs.speedUnit == "MPH")
                        "%.1f".format(tripState.avgFuelMpg)      to "MPG"
                    else
                        "%.1f".format(tripState.avgFuelL100km) to "L/100"
                    DataCell("ECON", "$econVal $econUnit",                                             modifier = Modifier.weight(1f))
                }

                // Row 4 — Wheel speeds
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DataCell("FL", "${prefs.displaySpeed(vehicleState.wheelSpeedFL)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                    DataCell("FR", "${prefs.displaySpeed(vehicleState.wheelSpeedFR)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                    DataCell("RL", "${prefs.displaySpeed(vehicleState.wheelSpeedRL)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                    DataCell("RR", "${prefs.displaySpeed(vehicleState.wheelSpeedRR)} ${prefs.speedLabel}", modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.weight(1f))

                // Start / End Trip button
                Button(
                    onClick = {
                        if (tripState.isRecording) {
                            showSummary = tripState.points.isNotEmpty()
                            onEndTrip()
                        } else {
                            if (!hasLocationPerm)
                                permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            else
                                onStartTrip()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(bottom = bottomPad),
                    shape    = RoundedCornerShape(8.dp),
                    colors   = ButtonDefaults.buttonColors(
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
// OSM MAP
// ═══════════════════════════════════════════════════════════════════════════

/**
 * OpenStreetMap map using CartoDB Dark Matter tiles.
 *
 * Overlay management is incremental — polylines are added as new segments arrive
 * rather than cleared and rebuilt every second, which eliminates the flash.
 * The position marker is moved in-place rather than removed and re-added.
 * Camera only animates when a trip first starts, then lets the user pan freely.
 */
@Composable
private fun OsmTripMap(
    tripState: TripState,
    segments: List<Pair<List<GeoPoint>, Color>>,
    modifier: Modifier = Modifier
) {
    val ctx            = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // mapRef uses mutableStateOf so the factory write triggers a recomposition,
    // ensuring AndroidView.update runs AFTER Android lays out the MapView (non-zero size).
    // All other refs are plain arrays — they don't need to drive recomposition.
    val mapRef            = remember { mutableStateOf<MapView?>(null) }
    val posMarkerRef      = remember { arrayOfNulls<Marker>(1) }
    val renderedSegCount  = remember { intArrayOf(0) }
    val prevSegmentsHash  = remember { intArrayOf(-1) }
    val peakMarkerCount   = remember { intArrayOf(0) }
    val centeredOnStart   = remember { booleanArrayOf(false) }

    // Center map on user's last known GPS location once, so we don't show dark ocean at (0,0)
    LaunchedEffect(Unit) {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(ctx)
            val location = suspendCancellableCoroutine { cont ->
                fusedClient.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
            val map = mapRef.value
            if (location != null && map != null) {
                map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            } else {
                // No location yet — zoom out so dark ocean isn't the entire view
                mapRef.value?.controller?.setZoom(3.0)
            }
        } catch (_: SecurityException) {
        }
    }

    AndroidView(
        factory = { context ->
            Configuration.getInstance().apply {
                load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                userAgentValue = "openRS_/${BuildConfig.VERSION_NAME}"
            }
            MapView(context).apply {
                setTileSource(cartoDarkTiles())
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                controller.setZoom(15.0)
                zoomController.setVisibility(
                    org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
                )
                onResume()  // enable tile downloading before first layout
            }.also {
                mapRef.value = it   // mutableStateOf write → triggers recomposition → update runs after layout
                it.post { it.invalidate() }  // redraw after first layout frame with correct dimensions
            }
        },
        update = { mapView ->
            // ── Polylines — incremental ───────────────────────────────────────
            val newHash = System.identityHashCode(segments)
            if (newHash != prevSegmentsHash[0]) {
                val fullRebuild = segments.size <= renderedSegCount[0]
                if (fullRebuild) {
                    // Color mode changed or trip reset — remove existing polylines
                    mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Polyline }
                    renderedSegCount[0] = 0
                }
                // Add only segments we haven't drawn yet
                segments.drop(renderedSegCount[0]).forEach { (pts, color) ->
                    if (pts.size >= 2) {
                        org.osmdroid.views.overlay.Polyline(mapView).apply {
                            setPoints(pts)
                            outlinePaint.color       = color.toArgb()
                            outlinePaint.strokeWidth = 10f
                            outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
                            outlinePaint.isAntiAlias = true
                        }.also { mapView.overlays.add(it) }
                    }
                }
                renderedSegCount[0] = segments.size
                prevSegmentsHash[0] = newHash
            }

            // ── Position marker — move in place, never remove/re-add ──────────
            tripState.points.lastOrNull()?.let { pt ->
                val gp       = GeoPoint(pt.lat, pt.lng)
                val existing = posMarkerRef[0]
                if (existing != null && mapView.overlays.contains(existing)) {
                    existing.position = gp
                } else {
                    val m = Marker(mapView).apply {
                        position = gp
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon  = createPositionDot(ctx)
                        title = null
                    }
                    mapView.overlays.add(m)
                    posMarkerRef[0] = m
                }
            }

            // ── Peak / RTR markers — rebuild only when count grows ────────────
            val targetPeaks = tripState.peakEvents.size +
                if (tripState.rtrAchievedPoint != null) 1 else 0
            if (targetPeaks != peakMarkerCount[0]) {
                mapView.overlays.removeAll { it is Marker && it !== posMarkerRef[0] }
                tripState.peakEvents.forEach { event ->
                    Marker(mapView).apply {
                        position = GeoPoint(event.lat, event.lng)
                        title    = event.type.label
                        snippet  = when (event.type) {
                            PeakType.RPM       -> "%.0f rpm".format(event.value)
                            PeakType.BOOST     -> "%.1f psi".format(event.value)
                            PeakType.LATERAL_G -> "%.2f g".format(event.value)
                        }
                    }.also { mapView.overlays.add(it) }
                }
                tripState.rtrAchievedPoint?.let { pt ->
                    Marker(mapView).apply {
                        position = GeoPoint(pt.lat, pt.lng)
                        title    = "Race Ready"
                        snippet  = "RTR achieved here"
                    }.also { mapView.overlays.add(it) }
                }
                peakMarkerCount[0] = targetPeaks
            }

            // ── Camera — center once when trip starts, then hands-free ────────
            if (tripState.isRecording && tripState.points.size == 1 && !centeredOnStart[0]) {
                tripState.points.first().let { pt ->
                    mapView.controller.animateTo(GeoPoint(pt.lat, pt.lng))
                }
                centeredOnStart[0] = true
            }
            if (!tripState.isRecording) {
                centeredOnStart[0] = false  // reset so next trip re-centres
            }

            mapView.invalidate()
        },
        modifier = modifier
    )

    // OSMDroid requires explicit lifecycle callbacks to load tiles and release resources
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapRef.value?.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapRef.value?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapRef.value?.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapRef.value?.onPause()
        }
    }
}

// TripHeader removed — back arrow, SPD toggle, and REC indicator are now
// floating overlays directly on the map. See TripPage composable.

// ═══════════════════════════════════════════════════════════════════════════
// WEATHER CARD
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun WeatherCard(weather: WeatherData, prefs: UserPrefs, modifier: Modifier = Modifier) {
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
    val items: List<Pair<Color, String>> = when (colorMode) {
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
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                MonoLabel(label, 7.sp, Mid, letterSpacing = 0.1.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TRIP SUMMARY SHEET
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun TripSummaryContent(tripState: TripState, prefs: UserPrefs, onClose: () -> Unit) {
    val accent = LocalThemeAccent.current

    val distStr = "%.2f %s".format(
        if (prefs.speedUnit == "MPH") tripState.cumulativeDistanceKm * 0.621371
        else tripState.cumulativeDistanceKm,
        if (prefs.speedUnit == "MPH") "mi" else "km"
    )
    val elapsedSec = tripState.elapsedMs / 1000L
    val elapsedStr = "%d:%02d:%02d".format(elapsedSec / 3600, (elapsedSec % 3600) / 60, elapsedSec % 60)
    val (econVal, econUnit) = if (prefs.speedUnit == "MPH")
        "%.1f".format(tripState.avgFuelMpg)      to "MPG"
    else
        "%.1f".format(tripState.avgFuelL100km) to "L/100km"

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            MonoLabel("TRIP SUMMARY", 11.sp, accent, FontWeight.Bold, 0.2.sp)
            Box(
                Modifier
                    .background(Surf2, RoundedCornerShape(6.dp))
                    .border(1.dp, Brd, RoundedCornerShape(6.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) { MonoLabel("CLOSE", 9.sp, Mid, letterSpacing = 0.15.sp) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("DISTANCE", distStr,    Modifier.weight(1f))
            SummaryCell("DURATION", elapsedStr, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("MAX SPEED", prefs.displaySpeed(tripState.maxSpeedKph) + " " + prefs.speedLabel, Modifier.weight(1f))
            SummaryCell("AVG SPEED", prefs.displaySpeed(tripState.avgSpeedKph) + " " + prefs.speedLabel, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("FUEL USED", "%.2f L".format(tripState.fuelUsedL), Modifier.weight(1f))
            SummaryCell(econUnit, econVal,                                  Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("PEAK RPM",   "%.0f".format(tripState.peakRpm),         Modifier.weight(1f))
            SummaryCell("PEAK BOOST", "%.1f PSI".format(tripState.peakBoostPsi), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("AVG RPM",    "%.0f".format(tripState.avgRpm),           Modifier.weight(1f))
            SummaryCell("PEAK LAT G", "%.2f g".format(tripState.peakLateralG),   Modifier.weight(1f))
        }

        if (tripState.driveModeBreakdown.isNotEmpty()) {
            MonoLabel("DRIVE MODE BREAKDOWN", 9.sp, Dim, letterSpacing = 0.2.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tripState.driveModeBreakdown.entries.sortedByDescending { it.value }.forEach { (mode, frac) ->
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
