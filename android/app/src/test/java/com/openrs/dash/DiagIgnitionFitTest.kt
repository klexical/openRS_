package com.openrs.dash

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.DiagVehiclePanel
import com.openrs.dash.ui.Tokens
import com.openrs.dash.ui.ignitionStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #327 — DIAG's IGNITION readout names the power mode with the list's names ("Key Recently Out", "Post Accessory", …).
 * In the VEHICLE panel's three columns at 360 dp the longest wrapped to two lines (three at font 1.3), so the panel changed
 * height at every key-off (7 → 3 → 2). IGNITION has its own full-width row: every name is one line, at font 1.0 and 1.3.
 * The production [DiagVehiclePanel] inside the page's margins, 360 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class DiagIgnitionFitTest {

    /** Height of the IGNITION value and of the one-line E-BRAKE value ("OFF"), in px. */
    private fun heights(code: Int, fontScale: Float): Pair<Float, Float> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val value = ignitionStatusLabel(code)
        controller.get().setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = Tokens.PagePad)) {
                    DiagVehiclePanel(VehicleState(isConnected = true, ignitionStatus = code, engineState360 = 0xB2))
                }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val compose = (controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as android.view.ViewGroup
        var ignition = 0f; var oneLine = 0f
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.let { t ->
                when (t.joinToString("") { it.text }) { value -> ignition = n.boundsInRoot.height; "OFF" -> oneLine = n.boundsInRoot.height }
            }
            n.children.forEach { walk(it) }
        }
        walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        controller.pause().stop().destroy()
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        return ignition to oneLine
    }

    private fun wrapped(fontScale: Float): List<String> = listOf(0, 1, 2, 3, 4, 5, 6, 7, 9).mapNotNull { code ->
        val (h, line) = heights(code, fontScale)
        check(h > 0f && line > 0f) { "code $code: the values were not found" }
        if (h > line) "${ignitionStatusLabel(code)} (${h.toInt()} px, one line ${line.toInt()} px)" else null
    }

    @Test fun `every ignition name is one line at 360 dp, font 1-0`() {
        assertEquals(emptyList<String>(), wrapped(1.0f))
    }

    @Test fun `every ignition name is one line at 360 dp, font 1-3`() {
        assertEquals(emptyList<String>(), wrapped(1.3f))
    }
}
