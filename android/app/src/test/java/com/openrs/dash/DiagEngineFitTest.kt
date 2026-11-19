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
import com.openrs.dash.ui.engineStatusLabel
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
 * #328 — DIAG's ENGINE reads 0x360 byte 7: "Off", "Running", or any other value raw as "0xNN · unidentified". In the VEHICLE
 * panel's two columns at 360 dp the unidentified form wrapped to two lines at font 1.3, so the panel changed height with
 * the engine state, which #327 removed for IGNITION. ENGINE has its own full-width row: every form is one line, at font
 * 1.0 and 1.3. The production [DiagVehiclePanel] inside the page's margins, 360 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class DiagEngineFitTest {

    /** Height of the ENGINE value for byte 7 = [code] and of the one-line E-BRAKE value ("OFF"), in px. */
    private fun heights(code: Int, fontScale: Float): Pair<Float, Float> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val value = engineStatusLabel(code)
        controller.get().setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = Tokens.PagePad)) {
                    DiagVehiclePanel(VehicleState(isConnected = true, ignitionStatus = 6, engineState360 = code))   // IGNITION "Ignition On": no value shared with ENGINE
                }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val compose = (controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as android.view.ViewGroup
        var engine = 0f; var oneLine = 0f
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.let { t ->
                when (t.joinToString("") { it.text }) { value -> engine = n.boundsInRoot.height; "OFF" -> oneLine = n.boundsInRoot.height }
            }
            n.children.forEach { walk(it) }
        }
        walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        controller.pause().stop().destroy()
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        return engine to oneLine
    }

    private fun wrapped(fontScale: Float): List<String> = listOf(-1, 0x02, 0xB2, 0x00, 0x7F).mapNotNull { code ->
        val (h, line) = heights(code, fontScale)
        check(h > 0f && line > 0f) { "code $code: the values were not found" }
        if (h > line) "${engineStatusLabel(code)} (${h.toInt()} px, one line ${line.toInt()} px)" else null
    }

    @Test fun `every engine state is one line at 360 dp, font 1-0`() {
        assertEquals(emptyList<String>(), wrapped(1.0f))
    }

    @Test fun `every engine state is one line at 360 dp, font 1-3`() {
        assertEquals(emptyList<String>(), wrapped(1.3f))
    }
}
