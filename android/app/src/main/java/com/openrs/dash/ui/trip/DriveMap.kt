package com.openrs.dash.ui.trip

import com.openrs.dash.ui.units.speed
import com.openrs.dash.ui.units.speedUnitText
import com.openrs.dash.ui.units.boostUnitText
import com.openrs.dash.ui.units.boostPsi
import androidx.core.graphics.createBitmap
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.openrs.dash.R
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.DriveBookmarkEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.PeakEvent
import com.openrs.dash.data.PeakType
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.Warn
import com.openrs.dash.ui.effectiveThemeIsDay
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.UserPrefsStore
import androidx.compose.ui.geometry.Offset

// ═══════════════════════════════════════════════════════════════════════════
// DriveMap — Google Maps Compose wrapper with telemetry polyline overlay
// ═══════════════════════════════════════════════════════════════════════════

enum class ColorMode(val label: String) {
    SPEED("SPD"),
    DRIVE_MODE("MODE"),
    BOOST("BOOST"),
    THROTTLE("THRTL"),
    LATERAL_G("G-LAT"),
    OIL_TEMP("TEMP"),
    AWD("AWD"),
    DYNAMICS("BRAKE")
}

/**
 * Google Maps composable for the MAP tab.
 *
 * @param points     Drive points to render as a polyline (live or historic)
 * @param colorMode  Polyline coloring strategy
 * @param peakEvents Peak markers to place on the map
 * @param rtrPoint   Race-ready achievement point (optional marker)
 * @param currentLat Current live latitude (for position dot, null if no location)
 * @param currentLng Current live longitude
 * @param isRecording Whether a drive is actively recording
 * @param isPaused   Whether recording is paused
 * @param hasLocationPermission Whether fine location permission is granted
 * @param mapType    Google Maps map type (Normal, Satellite, Terrain)
 */
@Composable
fun DriveMap(
    points: List<DrivePointEntity>,
    modifier: Modifier = Modifier,
    colorMode: ColorMode = ColorMode.SPEED,
    peakEvents: List<PeakEvent> = emptyList(),
    bookmarks: List<DriveBookmarkEntity> = emptyList(),
    rtrPoint: DrivePointEntity? = null,
    currentLat: Double? = null,
    currentLng: Double? = null,
    isRecording: Boolean = false,
    isPaused: Boolean = false,
    hasLocationPermission: Boolean = false,
    mapType: MapType = MapType.NORMAL,
    cameraPositionState: CameraPositionState = rememberCameraPositionState(),
    prefs: UserPrefs = UserPrefsStore.prefs.value
) {
    val context = LocalContext.current
    val isDay = effectiveThemeIsDay()
    val mapStyleOptions = remember {
        MapStyleOptions.loadRawResourceStyle(context, R.raw.google_map_style_dark)
    }

    // Auto-center on first point or current location (idle)
    LaunchedEffect(currentLat, currentLng, points.firstOrNull()) {
        val lat = currentLat ?: points.firstOrNull()?.lat ?: return@LaunchedEffect
        val lng = currentLng ?: points.firstOrNull()?.lng ?: return@LaunchedEffect
        if (cameraPositionState.position.target.latitude == 0.0) {
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 15f)
            )
        }
    }

    // Follow current position when recording
    LaunchedEffect(currentLat, currentLng, isRecording) {
        if (isRecording && currentLat != null && currentLng != null) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLng(LatLng(currentLat, currentLng)),
                300
            )
        }
    }

    // Zoom to fit entire route when loading a historic drive (not recording)
    LaunchedEffect(points.size, isRecording) {
        if (!isRecording && points.size >= 2) {
            val bounds = LatLngBounds.builder().apply {
                points.forEach { include(LatLng(it.lat, it.lng)) }
            }.build()
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngBounds(bounds, 80),
                500
            )
        }
    }

    // Apply dark style only on Normal map type in NIGHT mode.
    // DAY mode uses Google's default light basemap; satellite/terrain have their own look.
    val effectiveStyle = if (mapType == MapType.NORMAL && !isDay) mapStyleOptions else null

    val mapProperties = remember(effectiveStyle, mapType, hasLocationPermission) {
        MapProperties(
            mapStyleOptions = effectiveStyle,
            mapType = mapType,
            isMyLocationEnabled = hasLocationPermission
        )
    }

    val uiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = true
        )
    }

    // The route's segments and pause marks: built once per point update, never per composition (#520). The map
    // recomposes on every fix and dot tick; rebuilding allocated a LatLng per point and walked every point each time.
    val overlay = rememberRouteOverlay(points, colorMode)
    val roundCap = remember { RoundCap() }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = mapProperties,
        uiSettings = uiSettings
    ) {
        // ── Route polyline (color-segmented) ─────────────────────────
        // Keyed by the segment's first point (#520): a point added, or one dropped from the front of the live window,
        // leaves every other segment's polyline as it was (maps-compose sets a polyline's points only when they differ)
        overlay.segments.forEach { seg ->
            key(seg.key) {
                Polyline(
                    points = seg.latLngs,
                    color = seg.color,
                    width = 10f,
                    jointType = JointType.ROUND,
                    startCap = roundCap,
                    endCap = roundCap
                )
            }
        }

        // ── Start / Finish flags ─────────────────────────────────────
        // Icons are remembered: maps-compose calls setIcon whenever the descriptor differs, and a new one never equals
        // the last (#520)
        if (points.isNotEmpty()) {
            val first = points.first()
            Marker(
                state = remember(first.lat, first.lng) { MarkerState(position = LatLng(first.lat, first.lng)) },
                title = "Start",
                icon = remember { BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN) },
                zIndex = 6f
            )

            if (points.size >= 2) {
                val last = points.last()
                Marker(
                    state = remember(last.lat, last.lng) { MarkerState(position = LatLng(last.lat, last.lng)) },
                    title = if (isRecording) "Current" else "Finish",
                    icon = remember { BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED) },
                    zIndex = 6f
                )
            }
        }

        // ── Pause point markers ──────────────────────────────────────
        if (overlay.pauses.isNotEmpty()) {
            val pauseIcon = remember { createPauseMarker() }
            overlay.pauses.forEach { pause ->
                key(pause.key) {
                    Marker(
                        state = remember(pause.position) { MarkerState(position = pause.position) },
                        title = "Paused",
                        snippet = pause.snippet,
                        icon = pauseIcon,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 4f
                    )
                }
            }
        }

        // ── Current position marker (only when no blue dot / during recording) ──
        if (currentLat != null && currentLng != null && isRecording) {
            val posIcon = remember { createPositionDot() }
            Marker(
                state = remember(currentLat, currentLng) { MarkerState(position = LatLng(currentLat, currentLng)) },
                icon = posIcon,
                anchor = Offset(0.5f, 0.5f),
                flat = true,
                zIndex = 10f
            )
        }

        // ── Peak markers ─────────────────────────────────────────────
        peakEvents.forEach { peak ->
            val (color, label) = when (peak.type) {
                PeakType.RPM -> Warn to "RPM: ${peak.value.toInt()}"
                PeakType.BOOST -> Accent to "${if (peak.boostSource == com.openrs.dash.data.BoostSource.TIP_033E) "TIP" else "Boost"}: ${boostText(peak.value, prefs)}"
                PeakType.LATERAL_G -> Orange to "Lat-G: ${"%.2f".format(peak.value)}"
                PeakType.SPEED -> Frost to "Speed: ${prefs.speed(peak.value).text}"
            }
            val hue = colorToHue(color)
            Marker(
                state = remember(peak.lat, peak.lng) { MarkerState(position = LatLng(peak.lat, peak.lng)) },
                title = peak.type.label,
                snippet = label,
                icon = remember(hue) { BitmapDescriptorFactory.defaultMarker(hue) },
                zIndex = 5f
            )
        }

        // ── RTR marker ───────────────────────────────────────────────
        rtrPoint?.let {
            val hue = colorToHue(Ok)
            Marker(
                state = remember(it.lat, it.lng) { MarkerState(position = LatLng(it.lat, it.lng)) },
                // Where the thermal verdict first read TEMPS READY: temps inside the openRS_ advisory limits, not a
                // statement that the car is ready for the track
                title = "Temps ready",
                snippet = "Thermal verdict: TEMPS READY",
                icon = remember(hue) { BitmapDescriptorFactory.defaultMarker(hue) },
                zIndex = 5f
            )
        }

        // ── Bookmark markers ────────────────────────────────────────
        bookmarks.forEach { bm ->
            val snippet = buildString {
                if (bm.label.isNotEmpty()) append(bm.label).append(" — ")
                append(prefs.speed(bm.speedKph).text)
                if (bm.rpm > 0) append(" · ${bm.rpm} RPM")
                // no boost: a bookmark stores no source (#207), so its value couldn't be named
            }
            val hue = colorToHue(Warn)
            Marker(
                state = remember(bm.lat, bm.lng) { MarkerState(position = LatLng(bm.lat, bm.lng)) },
                title = if (bm.label.isNotEmpty()) "Observer note: ${bm.label}" else "Observer note (no text)",
                snippet = snippet,
                icon = remember(hue) { BitmapDescriptorFactory.defaultMarker(hue) },
                zIndex = 7f
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Polyline color segmentation
// ═══════════════════════════════════════════════════════════════════════════

/**
 * The five colours a route is drawn in, read once per build (#520): each theme getter re-reads the theme mode, and in
 * AUTO the clock, so reading them per point cost one clock read per point.
 */
internal data class RoutePalette(val accent: Color, val ok: Color, val warn: Color, val orange: Color, val dim: Color) {
    companion object {
        fun current() = RoutePalette(Accent, Ok, Warn, Orange, com.openrs.dash.ui.Dim)
    }
}

/** One polyline: [key] is its first point's time (made unique), so a polyline keeps its node while its points stay. */
internal data class ColorSegment(val key: Long, val latLngs: List<LatLng>, val color: Color)

/** A pause marker at the last point before a gap of more than 5 s; [key] is that point's time (made unique). */
internal data class PauseMark(val key: Long, val position: LatLng, val snippet: String)

/** What the map draws from the points, built in one pass by [buildRouteOverlay]. */
internal class RouteOverlay(val segments: List<ColorSegment>, val pauses: List<PauseMark>)

/** The longest step between two points that is not a pause: the recorder stores one point a second. */
private const val PAUSE_GAP_MS = 5000L

/**
 * The route overlay for [points] in [mode], rebuilt only when the points, the mode or the theme's colours change
 * (#520). DriveMap recomposes on every fix and every dot tick; the live points list is republished once per recorded
 * point, so the overlay is built once per point update. [build] is the test seam that counts builds.
 */
@Composable
internal fun rememberRouteOverlay(
    points: List<DrivePointEntity>,
    mode: ColorMode,
    build: (List<DrivePointEntity>, ColorMode, RoutePalette) -> RouteOverlay = ::buildRouteOverlay,
): RouteOverlay {
    val palette = RoutePalette.current()
    return remember(points, mode, palette) { build(points, mode, palette) }
}

/** Segments (a new one at each colour change or pause gap) and pause marks, from the points in order. */
internal fun buildRouteOverlay(points: List<DrivePointEntity>, mode: ColorMode, palette: RoutePalette): RouteOverlay {
    if (points.size < 2) return RouteOverlay(emptyList(), emptyList())
    val segments = mutableListOf<ColorSegment>()
    val pauses = mutableListOf<PauseMark>()
    // BOOST colours one measurement only — the drive peak's source (#207); other sources' points are drawn Dim
    val boostSource = if (mode == ColorMode.BOOST) com.openrs.dash.data.StoredBoost.primarySource(points) else null
    var lastSegKey = Long.MIN_VALUE
    var lastPauseKey = Long.MIN_VALUE
    fun segKey(ts: Long): Long = (if (ts > lastSegKey) ts else lastSegKey + 1).also { lastSegKey = it }

    var currentColor = pointColor(points[0], mode, boostSource, palette)
    var currentKey = segKey(points[0].timestamp)
    var currentLatLngs = mutableListOf(LatLng(points[0].lat, points[0].lng))

    for (i in 1 until points.size) {
        val p = points[i]
        val prev = points[i - 1]

        // Detect pause gaps (> 5s between consecutive points)
        val gapMs = p.timestamp - prev.timestamp
        val gap = gapMs > PAUSE_GAP_MS
        if (gap) {
            val key = (if (prev.timestamp > lastPauseKey) prev.timestamp else lastPauseKey + 1).also { lastPauseKey = it }
            pauses += PauseMark(key, LatLng(prev.lat, prev.lng), "%.0fs pause".format(gapMs / 1000.0))
        }

        val color = pointColor(p, mode, boostSource, palette)
        if (color != currentColor && !gap && currentLatLngs.size == 1) {
            // A colour change one point into a segment: that point is the next segment's start anyway, so the segment
            // takes the new colour instead of leaving a one-point polyline (which draws nothing) whose key the next
            // segment would collide with — that re-sent every later polyline when the live window slid by a point
            currentColor = color
        } else if (color != currentColor || gap) {
            segments += ColorSegment(currentKey, currentLatLngs, currentColor)
            currentColor = color
            val start = if (gap) p else prev   // overlap for seamless join
            currentKey = segKey(start.timestamp)
            currentLatLngs = mutableListOf(LatLng(start.lat, start.lng))
        }
        currentLatLngs += LatLng(p.lat, p.lng)
    }
    if (currentLatLngs.size >= 2) {
        segments += ColorSegment(currentKey, currentLatLngs, currentColor)
    }
    return RouteOverlay(segments, pauses)
}

internal fun pointColor(point: DrivePointEntity, mode: ColorMode, boostSource: com.openrs.dash.data.BoostSource? = null): Color =
    pointColor(point, mode, boostSource, RoutePalette.current())

private fun pointColor(point: DrivePointEntity, mode: ColorMode, boostSource: com.openrs.dash.data.BoostSource?, c: RoutePalette): Color = when (mode) {
    ColorMode.SPEED -> when {
        point.speedKph < 60  -> c.ok       // green — slow
        point.speedKph < 100 -> c.accent   // cyan — moderate
        point.speedKph < 140 -> c.warn     // yellow — fast
        else                 -> c.orange   // orange — very fast
    }
    ColorMode.DRIVE_MODE -> when (point.driveMode) {
        DriveMode.SPORT.label  -> c.warn     // yellow
        DriveMode.TRACK.label  -> c.ok       // green
        DriveMode.DRIFT.label  -> c.orange   // orange
        else                   -> c.accent   // cyan — Normal
    }
    // #207: no reading, or a reading from another source than the one coloured → Dim; legacy points as recorded
    ColorMode.BOOST -> when (val b = com.openrs.dash.data.StoredBoost.point(point)) {
        com.openrs.dash.data.StoredBoost.Unavailable -> c.dim
        is com.openrs.dash.data.StoredBoost.Reading -> if (b.source != boostSource) c.dim else boostBand(b.psi, c)
        is com.openrs.dash.data.StoredBoost.Legacy -> boostBand(b.psi, c)
    }
    ColorMode.THROTTLE -> when {
        point.throttlePct < 25  -> c.accent   // cyan — coasting
        point.throttlePct < 50  -> c.ok       // green — light
        point.throttlePct < 75  -> c.warn     // yellow — moderate
        else                    -> c.orange   // orange — full send
    }
    ColorMode.LATERAL_G -> when {
        kotlin.math.abs(point.lateralG) < 0.3 -> c.accent  // cyan — straight
        kotlin.math.abs(point.lateralG) < 0.6 -> c.ok      // green — gentle
        kotlin.math.abs(point.lateralG) < 0.9 -> c.warn    // yellow — spirited
        else                                  -> c.orange   // orange — high-G
    }
    ColorMode.OIL_TEMP -> when {
        point.oilTempC < 0   -> c.accent   // cyan — cold / no data
        point.oilTempC < 90  -> c.ok       // green — warming
        point.oilTempC < 110 -> c.warn     // yellow — operating
        else                 -> c.orange   // orange — hot
    }
    ColorMode.AWD -> {
        // Rear-vs-front wheel speed delta: proxy for GKN Twinster engagement
        val frontAvg = (point.wheelSpeedFL + point.wheelSpeedFR) / 2.0
        val rearAvg = (point.wheelSpeedRL + point.wheelSpeedRR) / 2.0
        val delta = kotlin.math.abs(rearAvg - frontAvg)
        when {
            delta < 0.5 -> c.accent   // cyan — minimal rear drive
            delta < 2.0 -> c.ok       // green — light vectoring
            delta < 4.0 -> c.warn     // yellow — active vectoring
            else        -> c.orange   // orange — heavy torque transfer
        }
    }
    ColorMode.DYNAMICS -> {
        // Composite: brake zones (red/orange) vs cornering (yellow) vs cruise (green)
        val braking = point.brakePressure > 50 || point.longitudinalG < -0.2
        val cornering = kotlin.math.abs(point.lateralG) > 0.3
        when {
            braking && cornering -> c.orange   // trail braking
            braking              -> Color(0xFFCC2200)  // hard braking — red-orange
            cornering && kotlin.math.abs(point.lateralG) > 0.6 -> c.warn  // hard corner
            cornering            -> c.ok       // moderate corner
            else                 -> c.accent   // cruise / acceleration
        }
    }
}

/** Legend entries for each color mode — label + color pairs. */
/**
 * Legend bands in the user's units. [pointColor]'s thresholds are stored units — speed km/h (60 / 100 / 140),
 * boost psi (0 / 8 / 16), oil °C (90 / 110) — and stay as they are; only the printed boundaries convert, so
 * MPH reads <37 / 37–62 / 62–87 / 87+. [legendUnit] is shown once after the mode label.
 */
internal fun colorLegend(mode: ColorMode, prefs: UserPrefs): List<Pair<String, Color>> = when (mode) {
    ColorMode.SPEED -> {
        val (a, b, c) = listOf(60.0, 100.0, 140.0).map { prefs.displaySpeed(it) }
        listOf("<$a" to Ok, "$a–$b" to Accent, "$b–$c" to Warn, "$c+" to Orange)
    }
    ColorMode.DRIVE_MODE -> listOf("Normal" to Accent, "Sport" to Warn, "Track" to Ok, "Drift" to Orange)
    ColorMode.BOOST -> {
        val (a, b) = listOf(8.0, 16.0).map { boostValue(it, prefs) }
        listOf("Vac" to Accent, "<$a" to Ok, "$a–$b" to Warn, "$b+" to Orange)
    }
    ColorMode.THROTTLE -> listOf("<25%" to Accent, "25-50" to Ok, "50-75" to Warn, "75+" to Orange)
    ColorMode.LATERAL_G -> listOf("<0.3g" to Accent, "0.3-0.6" to Ok, "0.6-0.9" to Warn, "0.9+" to Orange)
    ColorMode.OIL_TEMP -> {
        val (a, b) = listOf(90.0, 110.0).map { prefs.displayTemp(it) }
        listOf("<$a" to Ok, "$a–$b" to Warn, "$b+" to Orange)
    }
    ColorMode.AWD -> listOf("<0.5" to Accent, "0.5-2" to Ok, "2-4" to Warn, "4+" to Orange)
    ColorMode.DYNAMICS -> listOf("Cruise" to Accent, "Corner" to Ok, "Hard" to Warn, "Brake" to Orange)
}

private fun boostBand(psi: Double, c: RoutePalette): Color = when {
    psi < 0  -> c.accent   // cyan — vacuum
    psi < 8  -> c.ok       // green — low boost
    psi < 16 -> c.warn     // yellow — mid boost
    else     -> c.orange   // orange — full boost
}

/**
 * The legend's mode label: BOOST names the measurement coloured (#207) — TIP, BOOST (CAN 0x0F8) or legacy. TEMP (oil)
 * says "MAP N/R" unless the points' drive was saved with this build's 0x0F8 mapping ([oilPtuMapping], #229).
 */
internal fun legendLabel(mode: ColorMode, points: List<DrivePointEntity>, oilPtuMapping: String? = null): String {
    if (mode == ColorMode.OIL_TEMP && !com.openrs.dash.data.OilPtuMapping.isCurrent(oilPtuMapping))
        return "${mode.label} · MAP N/R"
    if (mode != ColorMode.BOOST) return mode.label
    com.openrs.dash.data.StoredBoost.primarySource(points)?.let { return it.label }
    return if (points.any { it.boostSource == null }) "BOOST · SRC N/R" else mode.label
}

/** Unit shown once after the legend's mode label ("SPD mph"); empty for unitless bands. */
internal fun legendUnit(mode: ColorMode, prefs: UserPrefs): String = when (mode) {
    ColorMode.SPEED -> prefs.speedUnitText
    ColorMode.BOOST -> prefs.boostUnitText
    ColorMode.OIL_TEMP -> prefs.tempLabel
    else -> ""
}

/** Gauge psi (as stored) → the user's boost unit, value only ("8.0", "0.55", "55"). */
private fun boostValue(psi: Double, prefs: UserPrefs): String = prefs.boostPsi(psi).value

private fun boostText(psi: Double, prefs: UserPrefs): String = prefs.boostPsi(psi).text

// ═══════════════════════════════════════════════════════════════════════════
// Marker helpers
// ═══════════════════════════════════════════════════════════════════════════

private fun createPositionDot(): BitmapDescriptor {
    val size = 36
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    // White outer ring
    canvas.drawCircle(cx, cy, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    })
    // Cyan inner circle (Nitrous Blue accent)
    canvas.drawCircle(cx, cy, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0091EA.toInt()
        style = Paint.Style.FILL
    })
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** Small orange pause marker. */
private fun createPauseMarker(): BitmapDescriptor {
    val size = 24
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    canvas.drawCircle(cx, cy, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFCC00.toInt()
        style = Paint.Style.FILL
    })
    // Pause bars
    canvas.drawRect(cx - 4f, cy - 4f, cx - 1.5f, cy + 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF05070A.toInt()
        style = Paint.Style.FILL
    })
    canvas.drawRect(cx + 1.5f, cy - 4f, cx + 4f, cy + 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF05070A.toInt()
        style = Paint.Style.FILL
    })
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** Map a Compose Color to a Google Maps marker hue (0-360). */
private fun colorToHue(color: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv[0]
}
