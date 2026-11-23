package com.openrs.dash

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.ui.Tokens
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.trip.DriveReplayView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #301: TRIP's replay is a column of header, notice, map, readouts, slider and transport row. At font 1.3 or larger the
 * fixed parts outgrew the screen: the map (which took the remaining height) fell to zero and the transport row slid under
 * the bottom navigation. The production [DriveReplayView] (with the TRIP preview's notice, the tallest form) at 320 and
 * 360 dp wide, fonts 1.3 and 2.0: the map keeps at least [MIN_MAP_DP], and the four transport buttons sit whole inside the
 * window above the bottom navigation (the view's own `navBarClearance()`, 48 dp in Robolectric) and answer a tap. Above the
 * view the test keeps [TOP_DP] for the status bar and a two-line app header, the most the app puts there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriveReplayFitTest {

    private class Node(val text: String?, val description: String?, val bounds: Rect, val node: SemanticsNode)

    private class Screen(val density: Float, val windowH: Float, val nodes: List<Node>) {
        fun byDescription(d: String) = nodes.firstOrNull { it.description == d }
        fun byText(t: String) = nodes.firstOrNull { it.text == t }
    }

    private fun points(): List<DrivePointEntity> = List(600) { i ->
        DrivePointEntity(
            driveId = 1, timestamp = i * 1_000L, lat = 10.0 + i * 1e-5, lng = 20.0 + i * 1e-5,
            speedKph = 98.0, rpm = 4_500, gear = "3", boostPsi = 14.2, coolantTempC = 92.0, oilTempC = 105.0,
            lateralG = 0.85, driveMode = "Sport",
        )
    }

    private val drive = DriveEntity(id = 1, startTime = 0L, endTime = 600_000L, name = "Sunday run")

    /** Composes the replay at [qualifiers] and [fontScale], runs [act] on the placed semantics, then tears down. */
    private fun <T> withReplay(qualifiers: String, fontScale: Float, act: (Screen, () -> Screen) -> T): T {
        RuntimeEnvironment.setQualifiers(qualifiers)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val pts = points()
        controller.get().setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.fillMaxWidth().height(TOP_DP.dp))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        DriveReplayView(drive, pts, UserPrefs(), onDismiss = {}, previewNotice = true)
                    }
                }
            }
        }
        fun idle() = repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        idle()
        val content = controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)
        val compose = content.getChildAt(0) as android.view.ViewGroup
        val density = controller.get().resources.displayMetrics.density
        fun read(): Screen {
            idle()
            val out = mutableListOf<Node>()
            fun walk(n: SemanticsNode) {
                if (!n.layoutInfo.isPlaced) return
                out += Node(
                    n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text },
                    n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(""),
                    n.boundsInRoot, n,
                )
                n.children.forEach { walk(it) }
            }
            walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            return Screen(density, content.height.toFloat(), out)
        }
        try {
            return act(read(), ::read)
        } finally {
            controller.pause().stop().destroy()
            repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        }
    }

    /** Top of the controls: the slider's bounds, less the controls' 4 dp top padding. */
    private fun controlsTop(s: Screen) =
        s.nodes.first { it.node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }.bounds.top - 4 * s.density

    /**
     * The map's height on screen: from under the header (the close button's 48 dp target plus the header's 12 dp padding,
     * then the notice when shown) to the top of the readings panel (its 8 dp margin + 10 dp padding above the first label)
     * or the top of the controls, whichever comes first.
     */
    private fun mapHeightDp(s: Screen): Float {
        // the notice's label, then its 6 dp padding and 10 dp margin
        val notice = s.nodes.filter { it.text?.startsWith("PREVIEW") == true }.maxOfOrNull { it.bounds.bottom + 16 * s.density }
        val close = s.nodes.first { it.node.config.getOrNull(SemanticsActions.OnClick)?.label == "Close replay" }.bounds.bottom
        val top = maxOf(close + 12 * s.density, notice ?: 0f)
        val speed = s.byText("SPEED")!!.bounds.let { if (it.height > 0f) it.top - (8 + 10) * s.density else Float.MAX_VALUE }
        return (minOf(speed, controlsTop(s)) - top) / s.density
    }

    private val readings = listOf("SPEED", "RPM", "GEAR", "BOOST", "OIL", "COOLANT", "LAT G", "MODE")

    private val buttons = listOf("Back 10 seconds", "Play", "Forward 10 seconds", "Playback speed 1x")

    private fun check(qualifiers: String, fontScale: Float, failures: MutableList<String>) =
        withReplay(qualifiers, fontScale) { s, reread ->
            val case = "$qualifiers font $fontScale"
            val map = mapHeightDp(s)
            println("#301 [$case] map=${"%.0f".format(map)} dp window=${s.windowH / s.density} dp")
            if (map < MIN_MAP_DP) failures += "[$case] map ${"%.0f".format(map)} dp < $MIN_MAP_DP dp"
            val top = TOP_DP * s.density
            val navTop = s.windowH - Tokens.NavBarHeight.value * s.density
            for (name in buttons) {
                val b = s.byDescription(name)
                if (b == null) { failures += "[$case] '$name' not placed"; continue }
                val r = b.bounds
                println("#301 [$case] '$name' ${r.top / s.density}..${r.bottom / s.density} dp")
                if (r.height < 48 * s.density - 0.5f) failures += "[$case] '$name' ${r.height / s.density} dp tall (< 48)"
                if (r.top < top - 0.5f || r.bottom > navTop + 0.5f)
                    failures += "[$case] '$name' ${r.top / s.density}..${r.bottom / s.density} dp is outside " +
                        "$TOP_DP..${navTop / s.density} dp (under the header or the bottom navigation)"
                if (r.left < -0.5f || r.right > s.nodes.first().bounds.right + 0.5f) failures += "[$case] '$name' cut at the side"
            }
            // Every reading can be brought on screen above the controls: at the end of the page's scroll range (none at
            // the default font) each reading's unclipped bounds sit between the header and the controls
            val maxScroll = s.nodes.mapNotNull { it.node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue() }
                .maxOrNull() ?: 0f
            for (label in readings) {
                val n = s.byText(label)?.node
                if (n == null) { failures += "[$case] reading '$label' not composed"; continue }
                val y = n.positionInRoot.y - maxScroll
                if (y < top - 0.5f || y + n.size.height > controlsTop(s) + 0.5f)
                    failures += "[$case] reading '$label' can't be brought on screen: ${y / s.density}..${(y + n.size.height) / s.density} dp"
            }
            // A tap on +10s reaches the playhead: the elapsed label reads 0:10
            s.byDescription("Forward 10 seconds")?.node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
            if (reread().byText("0:10") == null) failures += "[$case] +10s tap did not move the playhead"
        }

    @Test fun `at fonts 1_3 and 2_0, 320 and 360 dp, the map shows and the four transport buttons are whole and tappable`() {
        val failures = mutableListOf<String>()
        for (q in listOf("w320dp-h640dp-xhdpi", "w360dp-h720dp-xxhdpi")) for (fs in listOf(1.3f, 2.0f)) check(q, fs, failures)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `at the default font the replay fits as before - map, readings and controls with nothing to scroll`() {
        val failures = mutableListOf<String>()
        check("w360dp-h720dp-xxhdpi", 1.0f, failures)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        withReplay("w360dp-h720dp-xxhdpi", 1.0f) { s, _ ->
            // nothing on the page scrolls at the default font: no scroll range reaches past zero
            val scrollable = s.nodes.mapNotNull { it.node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
                .filter { it.maxValue() > 0f }
            assertEquals("scroll ranges at font 1.0", 0, scrollable.size)
        }
    }

    private companion object {
        /** Status bar (24 dp) + the app header on two lines (2 × 44 dp). */
        const val TOP_DP = 112
        /** The least map height the replay keeps (DriveReplayView's floor). */
        const val MIN_MAP_DP = 160
    }
}
