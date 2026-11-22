package com.openrs.dash

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openrs.dash.ui.Readout
import com.openrs.dash.ui.ReadoutGrid
import com.openrs.dash.ui.Tokens
import com.openrs.dash.ui.UNIT_ROWS
import com.openrs.dash.ui.UnitsSection
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.baroText
import com.openrs.dash.ui.units.Region
import com.openrs.dash.ui.units.UnitIds
import com.openrs.dash.ui.units.withUnits
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
 * #464 at 360 dp (owner): Settings → UNITS keeps every label and option on one line, the short region names included,
 * and every option is a 48 dp touch target; POWER's BARO keeps one line in each air-pressure unit. The production
 * [UnitsSection] in the Settings body's width (the dialog is 95 % of the screen, its body padded 14 dp a side), and the
 * production [ReadoutGrid] inside the page margins, at font scale 1.0 and 1.3.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The section scrolls in Settings, so the test composes it in a vertical scroll too and reads each node's own size (not its
// on-screen bounds): the height of the window does not matter. A tall window ran the release suite out of heap on CI
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class UnitsFitTest {

    private class Shown(val text: String, val lines: Int, val overflow: Boolean, val clickable: Boolean,
                        val wDp: Float, val hDp: Float)

    /** Every text (with its line count) and every clickable node's size, composing [content] at [fontScale]. */
    private fun compose(fontScale: Float, content: @Composable () -> Unit): List<Shown> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) { content() }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val density = controller.get().resources.displayMetrics.density
        val root = (controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as android.view.ViewGroup
        val out = mutableListOf<Shown>()
        fun textOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            ?: n.children.firstNotNullOfOrNull { textOf(it) }
        fun walk(n: SemanticsNode) {
            val clickable = n.config.getOrNull(SemanticsActions.OnClick) != null
            val text = if (clickable) textOf(n) else n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val layouts = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            if (text != null || clickable) {
                val l = layouts.firstOrNull()
                out += Shown(text ?: "", l?.lineCount ?: 0, l?.hasVisualOverflow ?: false, clickable,
                    n.size.width / density, n.size.height / density)
            }
            n.children.forEach { walk(it) }
        }
        walk((root.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        controller.pause().stop().destroy()
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        return out
    }

    /** The Settings body at 360 dp: 95 % of the width, less 14 dp each side. */
    private val settingsBody = (360 * 0.95f - 28).dp

    private fun units(fontScale: Float, region: Region): List<Shown> = compose(fontScale) {
        Box(Modifier.verticalScroll(rememberScrollState()).width(settingsBody)) { UnitsSection(region.units!!) {} }
    }

    private fun unitsProblems(fontScale: Float): List<String> = Region.entries.mapNotNull { it.units }.flatMap { r ->
        val shown = units(fontScale, Region.of(r))
        val texts = shown.filter { it.text.isNotEmpty() && it.lines > 0 && !it.clickable }
        val expected = Region.entries.map { it.label } + UNIT_ROWS.flatMap { row -> listOf(row.label) + row.options.map { it.second } }
        val missing = expected.filter { e -> texts.none { it.text == e } }
        check(missing.isEmpty()) { "not found: $missing" }
        val targets = shown.filter { it.clickable && it.text in expected }.map { it.text }
        check(targets.containsAll(expected - UNIT_ROWS.map { it.label }.toSet())) { "options without a click target" }
        texts.filter { !it.clickable && it.text in expected && it.lines > 1 }.map { "${it.text}: ${it.lines} lines" } +
            shown.filter { it.clickable && it.text in expected && (it.hDp < 47.5f || it.wDp < 47.5f) }
                .map { "${it.text}: ${it.wDp} × ${it.hDp} dp" }
    }.distinct()

    @Test fun `Settings UNITS - every label and option is one line and a 48 dp target at 360 dp, font 1-0`() {
        assertEquals(emptyList<String>(), unitsProblems(1.0f))
    }

    @Test fun `Settings UNITS - every label and option is one line and a 48 dp target at 360 dp, font 1-3`() {
        assertEquals(emptyList<String>(), unitsProblems(1.3f))
    }

    @Test fun `the region picker shows the short names`() {
        assertEquals(listOf("EU", "UK", "US", "CA", "AU / NZ", "Custom"), Region.entries.map { it.label })
    }

    private fun baroLines(fontScale: Float, airUnit: String): Pair<String, Int> {
        val p = UserPrefs().withUnits(Region.US.units!!).copy(airUnit = airUnit)
        val baro = baroText(p, 101.325)
        val shown = compose(fontScale) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Tokens.PagePad)) {
                // POWER's ENGINE MANAGEMENT first row: SHORT FT, LONG FT, BARO
                ReadoutGrid(listOf(listOf(Readout("SHORT FT", "-12.5%"), Readout("LONG FT", "-12.5%"), Readout(com.openrs.dash.ui.BARO_LABEL, baro))))
            }
        }
        return baro to shown.first { it.text == baro && !it.clickable }.lines
    }

    @Test fun `POWER BARO is one line in every air unit at 360 dp`() {
        for (scale in listOf(1.0f, 1.3f)) for (air in listOf(UnitIds.INHG, UnitIds.HPA, UnitIds.KPA)) {
            val (text, lines) = baroLines(scale, air)
            assertEquals("BARO \"$text\" at font $scale", 1, lines)
        }
    }
}
