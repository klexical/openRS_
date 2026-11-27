package com.openrs.dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
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
import com.openrs.dash.ui.Readout
import com.openrs.dash.ui.ReadoutGrid
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.Tokens
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
 * #593: at 360 dp and font 1.3 the label of TRIP's live readout grid ran into its neighbour ("FUEL USEDECONOMY").
 *
 * The production [TripPage] (idle, no map, as the screenshot test draws it) under the app's MaterialTheme at 360 dp, in
 * every fuel volume and economy unit, at font 1.0 and 1.3: each label of the readout's three rows is one line, is not
 * cut (no line dropped past its maximum and none wider than the label's box: a clipped label still counts one line) and ends at least [GAP_DP]
 * before the next column's label starts.
 *
 * Every [ReadoutGrid] (PERF, DIAG, TRIP's replay and summary) draws its captions the same way, so the grid itself is
 * also checked with the longest captions its callers use, in one to four columns at 360 dp, font 1.0 / 1.3 / 2.0: no
 * caption cut, none broken inside a word (a caption too wide for its column even at the smallest size wraps between
 * words), and each [GAP_DP] clear of the next.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi", application = OpenRSDashApp::class)
class TripReadoutFitTest {

    private companion object {
        const val GAP_DP = 4f
        val SCALES = listOf(1.0f, 1.3f, 2.0f)
        /** TRIP's page: at font 2.0 the panel is squeezed under the map and cut by START (a page layout of its own). */
        val TRIP_SCALES = listOf(1.0f, 1.3f)
        /** The longest captions [ReadoutGrid]'s callers pass (DIAG, PERF, TRIP's summary and replay). */
        val LONG_CAPTIONS = listOf("LOADED SAMPLES", "CHARGE TARGET", "FUEL USED", "TOP SPEED", "DURATION", "IGNITION",
            "DISTANCE", "SHORT FT")
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

    private fun layout(n: SemanticsNode): TextLayoutResult? {
        val layout = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        return layout.firstOrNull()
    }

    private fun lines(n: SemanticsNode): Int? = layout(n)?.lineCount

    /** A label cut short (clipped, so the fit test's line count alone can't see it) or broken inside a word. */
    private fun cutOrBroken(case: String, n: SemanticsNode): List<String> {
        val l = layout(n) ?: return listOf("[$case] '${textOf(n)}' has no text layout")
        val out = mutableListOf<String>()
        // Cut: lines dropped past maxLines, or a line wider than the label's own box (clipped). The layout's own
        // hasVisualOverflow isn't used: the semantics action's layout reports the column's width, not the text's.
        if (l.multiParagraph.didExceedMaxLines) out += "[$case] '${textOf(n)}' is cut (lines past its maximum)"
        val box = n.boundsInRoot.width
        for (line in 0 until l.lineCount) {
            val w = l.getLineRight(line) - l.getLineLeft(line)
            if (w > box + 0.5f) out += "[$case] '${textOf(n)}' is cut (line $line ${"%.0f".format(w)} px in a $box px box)"
        }
        val text = textOf(n).orEmpty()
        for (line in 0 until l.lineCount - 1) {
            val end = l.getLineEnd(line)
            if (end in 1 until text.length && text[end - 1] != ' ' && text[end] != ' ')
                out += "[$case] '$text' breaks inside a word after '${text.substring(0, end)}'"
        }
        return out
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
                row.forEach { out += cutOrBroken(case, it) }
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

    @Test fun `TRIP readout labels keep a gap and are never cut - 360 dp, font 1_0 1_3, every fuel and economy unit`() {
        val out = TRIP_SCALES.flatMap { fs -> FUEL.flatMap { f -> ECONOMY.flatMap { e -> problems(fs, f, e) } } }
        assertEquals(emptyList<String>(), out)
    }

    private fun gridProblems(fontScale: Float, columns: Int): List<String> {
        RuntimeEnvironment.setFontScale(fontScale)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val rows = LONG_CAPTIONS.chunked(columns).map { r -> r.map { Readout(it, "12.3") } }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                // Scrolls, as every page holding a grid does: the window's height doesn't squeeze the grid at font 2.0
                Box(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Tokens.PagePad)) { ReadoutGrid(rows) }
            }
        }
        try {
            idle(30)
            val case = "grid font $fontScale × $columns"
            val density = controller.get().resources.displayMetrics.density
            val root = composeRoot(controller.get().window.decorView)!!.semanticsOwner.unmergedRootSemanticsNode
            val texts = nodes(root).filter { it.layoutInfo.isPlaced && textOf(it) in LONG_CAPTIONS }
            val out = mutableListOf<String>()
            if (texts.size != LONG_CAPTIONS.size) out += "[$case] captions found: ${texts.map { textOf(it) }}"
            texts.forEach { out += cutOrBroken(case, it) }
            texts.groupBy { it.boundsInRoot.top }.values.forEach { row ->
                row.sortedBy { it.boundsInRoot.left }.zipWithNext().forEach { (a, b) ->
                    val gap = (b.boundsInRoot.left - a.boundsInRoot.right) / density
                    if (gap < GAP_DP) out += "[$case] '${textOf(a)}' ends ${"%.1f".format(gap)} dp from '${textOf(b)}'"
                }
            }
            return out
        } finally {
            controller.pause().stop().destroy()
            idle(8)
        }
    }

    @Test fun `readout grid captions are never cut - 360 dp, font 1_0 1_3 2_0, one to four columns`() {
        val out = SCALES.flatMap { fs -> (1..4).flatMap { gridProblems(fs, it) } }
        assertEquals(emptyList<String>(), out)
    }
}
