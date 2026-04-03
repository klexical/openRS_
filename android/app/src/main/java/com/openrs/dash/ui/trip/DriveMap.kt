package com.openrs.dash.ui.trip

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
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.PeakEvent
import com.openrs.dash.data.PeakType
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.Ok
import com.openrs.dash.ui.Orange
import com.openrs.dash.ui.Warn
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
    OIL_TEMP("TEMP")
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
    colorMode: ColorMode = ColorMode.SPEED,
    peakEvents: List<PeakEvent> = emptyList(),
    rtrPoint: DrivePointEntity? = null,
    currentLat: Double? = null,
    currentLng: Double? = null,
    isRecording: Boolean = false,
    isPaused: Boolean = false,
    hasLocationPermission: Boolean = false,
    mapType: MapType = MapType.NORMAL,
    cameraPositionState: CameraPositionState = rememberCameraPositionState(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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

    // Apply dark style only on Normal map type (satellite/terrain have their own look)
    val effectiveStyle = if (mapType == MapType.NORMAL) mapStyleOptions else null

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
                state = MarkerState(position = LatLng(first.lat, first.lng)),
                title = "Start",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                zIndex = 6f
            )

            if (points.size >= 2) {
                val last = points.last()
                Marker(
                    state = MarkerState(position = LatLng(last.lat, last.lng)),
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
                        state = MarkerState(position = LatLng(points[i - 1].lat, points[i - 1].lng)),
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
                state = MarkerState(position = LatLng(currentLat, currentLng)),
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
                PeakType.BOOST -> Accent to "Boost: ${"%.1f".format(peak.value)} PSI"
                PeakType.LATERAL_G -> Orange to "Lat-G: ${"%.2f".format(peak.value)}"
                PeakType.SPEED -> Frost to "Speed: ${"%.0f".format(peak.value)} km/h"
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
}

/** Legend entries for each color mode — label + color pairs. */
internal fun colorLegend(mode: ColorMode): List<Pair<String, Color>> = when (mode) {
    ColorMode.SPEED -> listOf("<60" to Ok, "60-100" to Accent, "100-140" to Warn, "140+" to Orange)
    ColorMode.DRIVE_MODE -> listOf("Normal" to Accent, "Sport" to Warn, "Track" to Ok, "Drift" to Orange)
    ColorMode.BOOST -> listOf("Vac" to Accent, "<8" to Ok, "8-16" to Warn, "16+" to Orange)
    ColorMode.THROTTLE -> listOf("<25%" to Accent, "25-50" to Ok, "50-75" to Warn, "75+" to Orange)
    ColorMode.LATERAL_G -> listOf("<0.3g" to Accent, "0.3-0.6" to Ok, "0.6-0.9" to Warn, "0.9+" to Orange)
    ColorMode.OIL_TEMP -> listOf("<90°" to Ok, "90-110" to Warn, "110+" to Orange)
}

// ═══════════════════════════════════════════════════════════════════════════
// Marker helpers
// ═══════════════════════════════════════════════════════════════════════════

private fun createPositionDot(): BitmapDescriptor {
    val size = 36
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
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
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
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
