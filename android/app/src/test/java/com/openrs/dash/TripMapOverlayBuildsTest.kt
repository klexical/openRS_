package com.openrs.dash

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.ui.ThemeMode
import com.openrs.dash.ui.setThemeMode
import com.openrs.dash.ui.trip.ColorMode
import com.openrs.dash.ui.trip.RouteOverlay
import com.openrs.dash.ui.trip.RoutePalette
import com.openrs.dash.ui.trip.buildRouteOverlay
import com.openrs.dash.ui.trip.rememberRouteOverlay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * #520: TRIP drew most of its frames over 32 ms. DriveMap recomposes on every fix and dot tick (and the replay on every
 * cursor step), and each composition rebuilt every colour segment (a LatLng per point) and walked every point for pause
 * markers. [rememberRouteOverlay] is what DriveMap draws from: with a 3,600-point drive (the live window's cap) it must
 * build once per point update and never on a recomposition that leaves the points, colour mode and theme as they were.
 * Synthetic points only (times from 0, positions near 0° / 0°).
 */
@RunWith(RobolectricTestRunner::class)
class TripMapOverlayBuildsTest {

    // Every activity built here is destroyed after the test and the main looper drained (android-general rule:
    // a composition left alive stops later Compose tests in the same JVM from recomposing)
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<ComponentActivity>>()

    @After fun closeActivities() {
        setThemeMode(ThemeMode.NIGHT)
        activities.forEach { it.pause().stop().destroy() }
        activities.clear()
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    private fun idleFrame() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))

    /** One point a second; speed swings across the SPD bands every 40 s, so the route has many segments. */
    private fun drive(n: Int, from: Int = 0) = (from until from + n).map { i ->
        DrivePointEntity(driveId = 1, timestamp = i * 1_000L, lat = i * 1e-5, lng = i * 2e-5,
            speedKph = if ((i / 20) % 2 == 0) 40.0 else 120.0)
    }

    private class Host {
        var points by mutableStateOf<List<DrivePointEntity>>(emptyList())
        var mode by mutableStateOf(ColorMode.SPEED)
        var tick by mutableIntStateOf(0)
        var builds = 0
        var overlay: RouteOverlay? = null
    }

    private fun compose(host: Host) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { activities += it }.get()
        activity.setContent {
            // The tick stands in for what recomposes the map without new points: the dot's 1 s clock, a fix, a peak
            @Suppress("UNUSED_VARIABLE") val t = host.tick
            host.overlay = rememberRouteOverlay(host.points, host.mode) { p, m, c ->
                host.builds++
                buildRouteOverlay(p, m, c)
            }
        }
        idleFrame()
    }

    private fun recomposeFrames(host: Host, frames: Int) = repeat(frames) { host.tick++; idleFrame() }

    @Test fun `a 3600-point drive builds once, not once per recomposition`() {
        val host = Host().apply { points = drive(3_600) }
        compose(host)
        assertEquals(1, host.builds)

        recomposeFrames(host, 120)
        assertEquals("120 recompositions with the same points rebuilt the overlay", 1, host.builds)
        assertTrue(host.overlay!!.segments.size > 100)
    }

    @Test fun `each point update builds once, the frames between it none`() {
        val host = Host().apply { points = drive(3_600) }
        compose(host)
        // The recorder's publish: the window drops its oldest point and adds one, then toList() (DriveRecorder)
        for (step in 1..5) {
            host.points = drive(3_600, from = step)
            idleFrame()
            recomposeFrames(host, 30)
            assertEquals(1 + step, host.builds)
        }
    }

    @Test fun `colour mode and theme changes rebuild, with the new colours`() {
        val host = Host().apply { points = drive(200) }
        compose(host)
        host.mode = ColorMode.THROTTLE
        idleFrame()
        assertEquals(2, host.builds)

        val night = host.overlay!!.segments.map { it.color }.toSet()
        setThemeMode(ThemeMode.DAY)
        idleFrame()
        assertEquals("a theme change must rebuild: the colours are read once per build", 3, host.builds)
        val day = RoutePalette.current()
        assertEquals(setOf(day.accent), host.overlay!!.segments.map { it.color }.toSet())   // throttle 0 → coasting
        assertTrue(night != setOf(day.accent))
        recomposeFrames(host, 10)
        assertEquals(3, host.builds)
    }
}
