package com.openrs.dash.ui.trip

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
import com.openrs.dash.ui.UnitConversions
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

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = mapProperties,
        uiSettings = uiSettings
    ) {
        // ── Route polyline (color-segmented) ─────────────────────────
        if (points.size >= 2) {
            val segments = buildColorSegments(points, colorMode)
            segments.forEach { seg ->
                Polyline(
                    points = seg.latLngs,
                    color = seg.color,
                    width = 10f,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap()
                )
            }
        }

        // ── Start / Finish flags ─────────────────────────────────────
        if (points.isNotEmpty()) {
            val first = points.first()
            Marker(
                state = remember(first.lat, first.lng) { MarkerState(position = LatLng(first.lat, first.lng)) },
                title = "Start",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                zIndex = 6f
            )

            if (points.size >= 2) {
                val last = points.last()
                Marker(
                    state = remember(last.lat, last.lng) { MarkerState(position = LatLng(last.lat, last.lng)) },
                    title = if (isRecording) "Current" else "Finish",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                    zIndex = 6f
                )
            }
        }

        // ── Pause point markers ──────────────────────────────────────
        if (points.size >= 2) {
            for (i in 1 until points.size) {
                val gap = points[i].timestamp - points[i - 1].timestamp
                if (gap > 5000) {
                    val pauseIcon = remember { createPauseMarker() }
                    Marker(
                        state = remember(points[i - 1].lat, points[i - 1].lng) {
                            MarkerState(position = LatLng(points[i - 1].lat, points[i - 1].lng))
                        },
                        title = "Paused",
                        snippet = "%.0fs pause".format(gap / 1000.0),
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
                PeakType.BOOST -> Accent to "Boost: ${boostText(peak.value, prefs)}"
                PeakType.LATERAL_G -> Orange to "Lat-G: ${"%.2f".format(peak.value)}"
                PeakType.SPEED -> Frost to "Speed: ${prefs.displaySpeed(peak.value)} ${speedUnitText(prefs)}"
            }
            Marker(
                state = MarkerState(position = LatLng(peak.lat, peak.lng)),
                title = peak.type.label,
                snippet = label,
                icon = BitmapDescriptorFactory.defaultMarker(colorToHue(color)),
                zIndex = 5f
            )
        }

        // ── RTR marker ───────────────────────────────────────────────
        rtrPoint?.let {
            Marker(
                state = MarkerState(position = LatLng(it.lat, it.lng)),
                title = "Race Ready",
                snippet = "RTR achieved",
                icon = BitmapDescriptorFactory.defaultMarker(colorToHue(Ok)),
                zIndex = 5f
            )
        }

        // ── Bookmark markers ────────────────────────────────────────
        bookmarks.forEach { bm ->
            val snippet = buildString {
                if (bm.label.isNotEmpty()) append(bm.label).append(" — ")
                append("${prefs.displaySpeed(bm.speedKph)} ${speedUnitText(prefs)}")
                if (bm.rpm > 0) append(" · ${bm.rpm} RPM")
                if (bm.boostPsi > 0) append(" · ${boostText(bm.boostPsi, prefs)}")
            }
            Marker(
                state = MarkerState(position = LatLng(bm.lat, bm.lng)),
                title = if (bm.label.isNotEmpty()) bm.label else "Bookmark",
                snippet = snippet,
                icon = BitmapDescriptorFactory.defaultMarker(colorToHue(Warn)),
                zIndex = 7f
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Polyline color segmentation
// ═══════════════════════════════════════════════════════════════════════════

private data class ColorSegment(val latLngs: List<LatLng>, val color: Color)

private fun buildColorSegments(
    points: List<DrivePointEntity>,
    mode: ColorMode
): List<ColorSegment> {
    if (points.isEmpty()) return emptyList()
    val segments = mutableListOf<ColorSegment>()
    var currentColor = pointColor(points[0], mode)
    var currentLatLngs = mutableListOf(LatLng(points[0].lat, points[0].lng))

    for (i in 1 until points.size) {
        val p = points[i]
        val prev = points[i - 1]

        // Detect pause gaps (> 5s between consecutive points)
        val gap = p.timestamp - prev.timestamp > 5000

        val color = pointColor(p, mode)
        if (color != currentColor || gap) {
            segments += ColorSegment(currentLatLngs.toList(), currentColor)
            currentColor = color
            currentLatLngs = mutableListOf(
                if (gap) LatLng(p.lat, p.lng)
                else LatLng(prev.lat, prev.lng)  // overlap for seamless join
            )
        }
        currentLatLngs += LatLng(p.lat, p.lng)
    }
    if (currentLatLngs.size >= 2) {
        segments += ColorSegment(currentLatLngs.toList(), currentColor)
    }
    return segments
}

internal fun pointColor(point: DrivePointEntity, mode: ColorMode): Color = when (mode) {
    ColorMode.SPEED -> when {
        point.speedKph < 60  -> Ok       // green — slow
        point.speedKph < 100 -> Accent   // cyan — moderate
        point.speedKph < 140 -> Warn     // yellow — fast
        else                 -> Orange   // orange — very fast
    }
    ColorMode.DRIVE_MODE -> when (point.driveMode) {
        DriveMode.SPORT.label  -> Warn     // yellow
        DriveMode.TRACK.label  -> Ok       // green
        DriveMode.DRIFT.label  -> Orange   // orange
        else                   -> Accent   // cyan — Normal
    }
    ColorMode.BOOST -> when {
        point.boostPsi < 0   -> Accent   // cyan — vacuum
        point.boostPsi < 8   -> Ok       // green — low boost
        point.boostPsi < 16  -> Warn     // yellow — mid boost
        else                 -> Orange   // orange — full boost
    }
    ColorMode.THROTTLE -> when {
        point.throttlePct < 25  -> Accent   // cyan — coasting
        point.throttlePct < 50  -> Ok       // green — light
        point.throttlePct < 75  -> Warn     // yellow — moderate
        else                    -> Orange   // orange — full send
    }
    ColorMode.LATERAL_G -> when {
        kotlin.math.abs(point.lateralG) < 0.3 -> Accent  // cyan — straight
        kotlin.math.abs(point.lateralG) < 0.6 -> Ok      // green — gentle
        kotlin.math.abs(point.lateralG) < 0.9 -> Warn    // yellow — spirited
        else                                  -> Orange   // orange — high-G
    }
    ColorMode.OIL_TEMP -> when {
        point.oilTempC < 0   -> Accent   // cyan — cold / no data
        point.oilTempC < 90  -> Ok       // green — warming
        point.oilTempC < 110 -> Warn     // yellow — operating
        else                 -> Orange   // orange — hot
    }
    ColorMode.AWD -> {
        // Rear-vs-front wheel speed delta: proxy for GKN Twinster engagement
        val frontAvg = (point.wheelSpeedFL + point.wheelSpeedFR) / 2.0
        val rearAvg = (point.wheelSpeedRL + point.wheelSpeedRR) / 2.0
        val delta = kotlin.math.abs(rearAvg - frontAvg)
        when {
            delta < 0.5 -> Accent   // cyan — minimal rear drive
            delta < 2.0 -> Ok       // green — light vectoring
            delta < 4.0 -> Warn     // yellow — active vectoring
            else        -> Orange   // orange — heavy torque transfer
        }
    }
    ColorMode.DYNAMICS -> {
        // Composite: brake zones (red/orange) vs cornering (yellow) vs cruise (green)
        val braking = point.brakePressure > 50 || point.longitudinalG < -0.2
        val cornering = kotlin.math.abs(point.lateralG) > 0.3
        when {
            braking && cornering -> Orange   // trail braking
            braking              -> Color(0xFFCC2200)  // hard braking — red-orange
            cornering && kotlin.math.abs(point.lateralG) > 0.6 -> Warn  // hard corner
            cornering            -> Ok       // moderate corner
            else                 -> Accent   // cruise / acceleration
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

/** Unit shown once after the legend's mode label ("SPD mph"); empty for unitless bands. */
internal fun legendUnit(mode: ColorMode, prefs: UserPrefs): String = when (mode) {
    ColorMode.SPEED -> speedUnitText(prefs)
    ColorMode.BOOST -> prefs.displayBoost(UnitConversions.STD_ATM_KPA).second
    ColorMode.OIL_TEMP -> prefs.tempLabel
    else -> ""
}

private fun speedUnitText(prefs: UserPrefs) = if (prefs.speedUnit == "MPH") "mph" else "km/h"

/** Gauge psi (as stored) → the user's boost unit, value only ("8.0", "0.55", "55"). */
private fun boostValue(psi: Double, prefs: UserPrefs): String =
    prefs.displayBoost(psi / UnitConversions.KPA_TO_PSI + UnitConversions.STD_ATM_KPA).first

private fun boostText(psi: Double, prefs: UserPrefs): String =
    prefs.displayBoost(psi / UnitConversions.KPA_TO_PSI + UnitConversions.STD_ATM_KPA).let { (v, u) -> "$v $u" }

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
