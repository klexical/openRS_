package com.openrs.dash

import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.LatLng
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.ui.trip.ColorMode
import com.openrs.dash.ui.trip.ColorSegment
import com.openrs.dash.ui.trip.RoutePalette
import com.openrs.dash.ui.trip.buildRouteOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #520: DriveMap draws each segment as a polyline keyed by [ColorSegment.key], and maps-compose re-sends a polyline's
 * points only when they differ from the last ones it set. So a point update must leave every segment but the ends with
 * the same key and the same points; a positional (unkeyed) list re-sent every segment once the live window dropped a
 * segment off its front. Also pins the drawn route itself: segments and pause marks as DriveMap drew them before.
 * Plain JVM, no composition and no Compose state written (android-general rule). Synthetic points only.
 */
class TripMapOverlayKeysTest {

    private val palette = RoutePalette(accent = Color(0xFF0091EA), ok = Color(0xFF00FF88), warn = Color(0xFFFFCC00),
        orange = Color(0xFFFF4D00), dim = Color(0xFF7A9AB8))

    private fun point(i: Int, ts: Long = i * 1_000L, speed: Double = 40.0) =
        DrivePointEntity(driveId = 1, timestamp = ts, lat = i * 1e-5, lng = i * 2e-5, speedKph = speed)

    /** Speed crosses the SPD bands every 7 s: about 500 segments in the 3,600-point live window. */
    private fun drive(n: Int, from: Int = 0) = (from until from + n).map { i ->
        point(i, speed = if ((i / 7) % 2 == 0) 40.0 else 120.0)
    }

    /** Polylines whose points or colour maps-compose would set again: matched by key, as DriveMap draws them. */
    private fun resent(old: List<ColorSegment>, new: List<ColorSegment>): Int {
        val before = old.associateBy { it.key }
        return new.count { s -> before[s.key]?.let { it.latLngs != s.latLngs || it.color != s.color } ?: true }
    }

    @Test fun `a full window sliding by one point re-sends at most the first and last polylines`() {
        var old = buildRouteOverlay(drive(3_600), ColorMode.SPEED, palette).segments
        assertTrue(old.size > 400)
        for (step in 1..30) {
            val new = buildRouteOverlay(drive(3_600, from = step), ColorMode.SPEED, palette).segments
            assertTrue("step $step re-sent ${resent(old, new)} of ${new.size} polylines", resent(old, new) <= 2)
            assertEquals(new.size, new.map { it.key }.toSet().size)
            old = new
        }
    }

    @Test fun `a growing drive re-sends only its last polyline, or adds one`() {
        var old = buildRouteOverlay(drive(1_000), ColorMode.SPEED, palette).segments
        for (n in 1_001..1_030) {
            val new = buildRouteOverlay(drive(n), ColorMode.SPEED, palette).segments
            assertTrue(resent(old, new) <= 2)
            old = new
        }
    }

    @Test fun `segments and pause marks are drawn as before`() {
        // 0-2 slow, 3-4 fast, a 9 s gap, 5-6 fast
        val pts = listOf(point(0), point(1), point(2), point(3, speed = 120.0), point(4, speed = 120.0),
            point(5, ts = 13_000L, speed = 120.0), point(6, ts = 14_000L, speed = 120.0))
        val o = buildRouteOverlay(pts, ColorMode.SPEED, palette)
        fun ll(i: Int) = LatLng(i * 1e-5, i * 2e-5)
        assertEquals(listOf(
            ColorSegment(0L, listOf(ll(0), ll(1), ll(2)), palette.ok),
            ColorSegment(2_000L, listOf(ll(2), ll(3), ll(4)), palette.warn),   // overlaps the last slow point
            ColorSegment(13_000L, listOf(ll(5), ll(5), ll(6)), palette.warn), // a gap starts afresh (first point twice, as before)
        ), o.segments)
        assertEquals(1, o.pauses.size)
        assertEquals(ll(4), o.pauses[0].position)
        assertEquals(4_000L, o.pauses[0].key)
        assertEquals("%.0fs pause".format(9.0), o.pauses[0].snippet)
    }

    @Test fun `equal timestamps still give unique keys`() {
        val pts = listOf(point(0, ts = 0), point(1, ts = 0, speed = 120.0), point(2, ts = 0, speed = 40.0),
            point(3, ts = 9_000, speed = 40.0), point(4, ts = 18_000, speed = 40.0))
        val o = buildRouteOverlay(pts, ColorMode.SPEED, palette)
        assertEquals(o.segments.size, o.segments.map { it.key }.toSet().size)
        assertEquals(o.pauses.size, o.pauses.map { it.key }.toSet().size)
        assertEquals(2, o.pauses.size)
    }

    @Test fun `a colour change one point into a segment leaves no one-point segment`() {
        // 0 slow, 1 fast, 2 slow: the first segment would hold point 0 alone
        val pts = listOf(point(0), point(1, speed = 120.0), point(2))
        val o = buildRouteOverlay(pts, ColorMode.SPEED, palette)
        assertTrue(o.segments.all { it.latLngs.size >= 2 })
        assertEquals(listOf(0L, 1_000L), o.segments.map { it.key })
    }

    @Test fun `fewer than two points draw nothing`() {
        assertTrue(buildRouteOverlay(emptyList(), ColorMode.SPEED, palette).segments.isEmpty())
        val one = buildRouteOverlay(listOf(point(0)), ColorMode.SPEED, palette)
        assertTrue(one.segments.isEmpty() && one.pauses.isEmpty())
    }
}
