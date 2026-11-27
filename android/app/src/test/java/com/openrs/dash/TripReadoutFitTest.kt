package com.openrs.dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.trip.TripPage
import com.openrs.dash.ui.units.UnitIds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #593: at 360 dp and font 1.3 the label of TRIP's live readout grid ran into its neighbour ("FUEL USEDECONOMY"). The
 * production [TripPage] (idle, no map, as the screenshot test draws it) under the app's MaterialTheme at 360 dp, font
 * 1.0 and 1.3, in every fuel volume and economy unit: each label of the readout's three rows is one line and ends at
 * least [GAP_DP] before the next column's label starts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi", application = OpenRSDashApp::class)
class TripReadoutFitTest {

    private companion object {
        const val GAP_DP = 4f
        val SCALES = listOf(1.0f, 1.3f)
        val FUEL = listOf(UnitIds.L, UnitIds.US_GAL, UnitIds.UK_GAL)
        val ECONOMY = listOf(UnitIds.L100KM, UnitIds.KML, UnitIds.MPG_US, UnitIds.MPG_UK)
        /** The first label of each row; the rest of a row is every text placed level with it. */
        val ROW_STARTS = listOf("SPEED", "COOLANT", "RDU")
    }

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        idle(8)
    }

    private fun idle(frames: Int) = repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap { nodes(it) }

    private fun textOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun composeRoot(v: View): ViewRootForTest? = v as? ViewRootForTest
        ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { composeRoot(g.getChildAt(it)) } }

    private fun lines(n: SemanticsNode): Int? {
        val layout = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        return layout.firstOrNull()?.lineCount
    }

    /** A connected car with every readout present (US-style mid-drive values; the labels don't depend on them). */
    private val vs = VehicleState(
        isConnected = true, speedKph = 100.0, rpm = 4340.0, coolantTempC = 93.0, oilTempC = 101.0, ambientTempC = 21.0,
        fuelLevelPct = 61.0, rduTempC = 53.0, ptuTempC = 70.0,
    )

    private fun problems(fontScale: Float, fuel: String, economy: String): List<String> {
        RuntimeEnvironment.setFontScale(fontScale)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val p = UserPrefs(fuelUnit = fuel, economyUnit = economy)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                TripPage(DriveState(), vs, p, settled = false)
            }
        }
        try {
            idle(60)
            val case = "font $fontScale $fuel $economy"
            val density = controller.get().resources.displayMetrics.density
            val root = composeRoot(controller.get().window.decorView)!!.semanticsOwner.unmergedRootSemanticsNode
            val texts = nodes(root).filter { it.layoutInfo.isPlaced && textOf(it) != null }
            val out = mutableListOf<String>()
            for (start in ROW_STARTS) {
                val first = texts.firstOrNull { textOf(it) == start }
                if (first == null) { out += "[$case] '$start' not found"; continue }
                val top = first.boundsInRoot.top
                val row = texts.filter { kotlin.math.abs(it.boundsInRoot.top - top) < 0.5f }.sortedBy { it.boundsInRoot.left }
                if (row.size != 4) out += "[$case] row '$start' has ${row.size} labels: ${row.map { textOf(it) }}"
                row.forEach { if (lines(it) != 1) out += "[$case] '${textOf(it)}' takes ${lines(it)} lines" }
                row.zipWithNext().forEach { (a, b) ->
                    // the text as drawn, not its node's box: a one-line label's box is its text's width
                    val gap = (b.boundsInRoot.left - a.boundsInRoot.right) / density
                    if (gap < GAP_DP) out += "[$case] '${textOf(a)}' ends ${"%.1f".format(gap)} dp from '${textOf(b)}'"
                }
                println("#593 [$case] ${row.map { textOf(it) to it.boundsInRoot.width / density }}")
            }
            return out
        } finally {
            controller.pause().stop().destroy()
            idle(8)
        }
    }

    @Test fun `TRIP readout labels keep a gap - 360 dp, font 1_0 1_3, every fuel and economy unit`() {
        val out = SCALES.flatMap { fs -> FUEL.flatMap { f -> ECONOMY.flatMap { e -> problems(fs, f, e) } } }
        assertEquals(emptyList<String>(), out)
    }
}
