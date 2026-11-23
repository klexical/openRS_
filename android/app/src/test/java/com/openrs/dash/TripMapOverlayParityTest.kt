package com.openrs.dash

import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.LatLng
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.ui.trip.ColorMode
import com.openrs.dash.ui.trip.RoutePalette
import com.openrs.dash.ui.trip.buildRouteOverlay
import com.openrs.dash.ui.trip.pointColor
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * #520: [buildRouteOverlay] draws what DriveMap drew before it, checked against a verbatim copy of the old
 * `buildColorSegments` and pause-marker loop (staging 7a8c5e4) over 4,000 random drives (0 to 59 points; steps of
 * 0 / 1,000 / 5,000 / 5,001 ms and long gaps; mixed boost sources) in every colour mode. Drawn polylines (2 or more points:
 * points and colour, in order) and pause marks (position and snippet) are equal, and every key is unique. The one
 * difference, a one-point first segment that draws nothing, is filtered out on both sides. Synthetic points only; reads
 * the theme colours, writes no Compose state.
 */
class TripMapOverlayParityTest {

    private fun oldSegments(points: List<DrivePointEntity>, mode: ColorMode): List<Pair<List<LatLng>, Color>> {
        if (points.size < 2) return emptyList()   // old DriveMap: `if (points.size >= 2)`
        val segments = mutableListOf<Pair<List<LatLng>, Color>>()
        val boostSource = if (mode == ColorMode.BOOST) com.openrs.dash.data.StoredBoost.primarySource(points) else null
        var currentColor = pointColor(points[0], mode, boostSource)
        var currentLatLngs = mutableListOf(LatLng(points[0].lat, points[0].lng))
        for (i in 1 until points.size) {
            val p = points[i]
            val prev = points[i - 1]
            val gap = p.timestamp - prev.timestamp > 5000
            val color = pointColor(p, mode, boostSource)
            if (color != currentColor || gap) {
                segments += currentLatLngs.toList() to currentColor
                currentColor = color
                currentLatLngs = mutableListOf(if (gap) LatLng(p.lat, p.lng) else LatLng(prev.lat, prev.lng))
            }
            currentLatLngs += LatLng(p.lat, p.lng)
        }
        if (currentLatLngs.size >= 2) segments += currentLatLngs.toList() to currentColor
        return segments
    }

    private fun oldPauses(points: List<DrivePointEntity>): List<Pair<LatLng, String>> {
        val out = mutableListOf<Pair<LatLng, String>>()
        if (points.size >= 2) for (i in 1 until points.size) {
            val gap = points[i].timestamp - points[i - 1].timestamp
            if (gap > 5000) out += LatLng(points[i - 1].lat, points[i - 1].lng) to "%.0fs pause".format(gap / 1000.0)
        }
        return out
    }

    private val sources = listOf(null, "TIP_033E", "CAN_0F8", "NONE")

    private fun randomDrive(r: Random, n: Int): List<DrivePointEntity> {
        var ts = r.nextLong(0, 10_000)
        return (0 until n).map { i ->
            ts += when (r.nextInt(10)) { 0 -> 0L; 1 -> 5_000L; 2 -> 5_001L; 3 -> r.nextLong(6_000, 600_000); else -> 1_000L }
            DrivePointEntity(driveId = 1, timestamp = ts, lat = i * 1e-5, lng = i * 2e-5,
                speedKph = listOf(30.0, 80.0, 120.0, 160.0)[r.nextInt(if (r.nextInt(3) == 0) 4 else 1)],
                throttlePct = r.nextDouble(0.0, 100.0), lateralG = r.nextDouble(-1.2, 1.2),
                driveMode = listOf("Normal", "Sport", "Track", "Drift")[r.nextInt(4)],
                oilTempC = listOf(-99.0, 50.0, 100.0, 120.0)[r.nextInt(4)],
                boostPsi = r.nextDouble(-10.0, 25.0), boostSource = sources[r.nextInt(sources.size)],
                brakePressure = r.nextDouble(0.0, 100.0), longitudinalG = r.nextDouble(-1.0, 1.0),
                wheelSpeedRL = r.nextDouble(0.0, 5.0))
        }
    }

    @Test fun `drawn polylines and pause marks equal staging's on random drives`() {
        val r = Random(520)
        repeat(4_000) { k ->
            val pts = randomDrive(r, r.nextInt(0, 60))
            for (mode in ColorMode.entries) {
                val drawnOld = oldSegments(pts, mode).filter { it.first.size >= 2 }
                val o = buildRouteOverlay(pts, mode, RoutePalette.current())
                val drawnNew = o.segments.map { it.latLngs.toList() to it.color }.filter { it.first.size >= 2 }
                assertEquals("drive $k mode $mode", drawnOld, drawnNew)
                assertEquals("drive $k mode $mode pauses", oldPauses(pts), o.pauses.map { it.position to it.snippet })
                assertEquals(o.segments.size, o.segments.map { it.key }.toSet().size)
                assertEquals(o.pauses.size, o.pauses.map { it.key }.toSet().size)
            }
        }
    }
}
