package com.openrs.dash

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.trip.TripPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #629: the TRIP page stays composed off screen (recorder-trip rule "TRIP map: created on TRIP, then retained"), so
 * anything in it that ticks on its own re-runs TRIP's composition every second on the main thread, whatever tab is
 * shown. Two 1 s `wallClockMs()` boost tickers (the route-stats overlay and `LiveHud`) were not gated on `visible`.
 *
 * The production [TripPage], connected (idle, then recording), with only it in the window: while hidden, nothing in it writes
 * snapshot state (so nothing ticks or recomposes) over [HIDDEN_S] seconds. The visible
 * run first shows the count sees TRIP's own ticks (one clock while connected, two while recording; the rec pulse adds
 * a write per frame).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h720dp-xxhdpi", application = OpenRSDashApp::class)
class TripHiddenRecompositionTest {

    private companion object {
        const val HIDDEN_S = 3
    }

    private fun idleMs(ms: Long) = repeat((ms / 16).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    /** [ms] of real time with the main looper kept turning: `wallClockMs()`'s `delay` runs on kotlinx's real-time
     *  executor (the UI dispatcher has no delay of its own), so a looper-only idle never reaches its tick. */
    private fun runFor(ms: Long) = repeat((ms / 100).toInt()) { Thread.sleep(100); idleMs(96) }

    /** Snapshot state written outside a snapshot: a clock tick, an animation frame, a collected flow — every one of them
     *  invalidates the composition that reads it (only TRIP is in the window). */
    private var writes = 0L

    /** A connected car mid-drive: every live readout of the page is on. */
    private val vs = VehicleState(isConnected = true, speedKph = 100.0, rpm = 4340.0, coolantTempC = 93.0, oilTempC = 101.0)

    @Test
    fun `hidden TRIP does not tick while connected`() = assertHiddenStill(DriveState(), minSeen = HIDDEN_S - 1L)

    @Test
    fun `hidden TRIP does not tick while connected and recording`() =
        assertHiddenStill(DriveState(phase = DrivePhase.RECORDING, startTime = 1L), minSeen = 2L * (HIDDEN_S - 1))

    /** Visible: at least [minSeen] writes over [HIDDEN_S] s (TRIP's own clocks, so the count sees them); hidden: none. */
    private fun assertHiddenStill(drive: DriveState, minSeen: Long) {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        var visible by mutableStateOf(true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                TripPage(drive, vs, UserPrefs(), settled = false, visible = visible)
            }
        }
        val observer = Snapshot.registerGlobalWriteObserver { writes++ }
        try {
            runFor(1_000)
            val seenStart = writes
            runFor(HIDDEN_S * 1_000L)
            val seen = writes - seenStart
            assertTrue("visible TRIP should write at least $minSeen times in $HIDDEN_S s (its own clocks; one tick of slack for a loaded runner), got $seen", seen >= minSeen)

            visible = false
            idleMs(500)          // the switch itself recomposes once
            val hiddenStart = writes
            runFor(HIDDEN_S * 1_000L)
            assertEquals("state writes (ticks) in the hidden TRIP page over $HIDDEN_S s", 0L, writes - hiddenStart)
        } finally {
            observer.dispose()
            // TripPage loads its drive history off the main thread: let those loads land before and after the destroy
            drain()
            controller.pause().stop().destroy()
            drain()
        }
    }

    private fun drain() {
        repeat(10) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        idleMs(128)
    }
}
