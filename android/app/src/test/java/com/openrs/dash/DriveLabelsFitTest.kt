package com.openrs.dash

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.openrs.dash.data.SessionMarks
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.BottomNavBar
import com.openrs.dash.ui.DrivePage
import com.openrs.dash.ui.LinkUi
import com.openrs.dash.ui.UserPrefs
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.junit.After
import org.junit.Assert.assertTrue
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
 * #293 and the DRIVE part of #508: DRIVE's labels at large system font sizes. The production [DrivePage] under the
 * production [BottomNavBar], at 360 and 411 dp, font 1.0 / 1.3 / 2.0, in every speed unit and three boost units:
 * - the hero captions, their units, the PEAK lines, the pedal names and the tab names never break inside a word and are
 *   never clipped or cut (no visual overflow);
 * - a hero's caption and unit on one line keep at least 4 dp between them (#508: "ENGINERPM", "SPEEDkm/h");
 * - the tab names, in a bar of fixed height, follow the system font only up to 1.3;
 * - at font 1.0 the caption and unit stay on one line (the default layout is unchanged).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// DrivePage reads OpenRSDashApp.instance: start the app here, or the class passes only after another one has
@Config(qualifiers = "w360dp-h720dp-xxhdpi", application = OpenRSDashApp::class)
class DriveLabelsFitTest {

    private val tabs = setOf("DRIVE", "PERF", "THERMAL", "TRIP", "GARAGE")

    private class Label(val text: String, val bounds: Rect, val layout: TextLayoutResult?)

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    /** A pull in progress, with session peaks, so every PEAK line holds a value. */
    private val live = VehicleState(
        isConnected = true, rpm = 4339.0, speedKph = 99.8,
        session = SessionMarks(peakRpm = 6875.0, peakSpeedKph = 187.4),
    )

    /**
     * Why [layout] is cut, or null: an ellipsis, a line wider or a last line lower than the box the text was given, or a box
     * outside the screen (a tab name pushed below the bar's bottom edge).
     */
    private fun cut(layout: TextLayoutResult, bounds: Rect, screen: Rect): String? = when {
        (0 until layout.lineCount).any { layout.isLineEllipsized(it) } -> "is ellipsized"
        (0 until layout.lineCount).any { layout.getLineRight(it) > layout.size.width + 1f } -> "is wider than its box"
        layout.getLineBottom(layout.lineCount - 1) > layout.size.height + 1f -> "is taller than its box"
        bounds.bottom > screen.bottom + 0.5f || bounds.right > screen.right + 0.5f || bounds.left < screen.left - 0.5f ->
            "runs off the screen ($bounds in $screen)"
        else -> null
    }

    private fun render(widthDp: Int, fontScale: Float, p: UserPrefs): Triple<Float, Rect, List<Label>> {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h720dp-xxhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            val haze = remember { HazeState() }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().hazeSource(haze)) { DrivePage(vs = live, p = p, link = LinkUi.LIVE) }
                BottomNavBar(selected = 0, onSelect = {}, hazeState = haze, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
        repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val compose = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val out = mutableListOf<Label>()
        fun walk(n: SemanticsNode) {
            if (!n.layoutInfo.isPlaced) return
            n.config.getOrNull(SemanticsProperties.Text)?.let { t ->
                val layouts = mutableListOf<TextLayoutResult>()
                n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                out += Label(t.joinToString("") { it.text }, n.boundsInRoot, layouts.firstOrNull())
            }
            n.children.forEach { walk(it) }
        }
        val root = (compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
        val screen = root.boundsInRoot
        walk(root)
        val density = activity.resources.displayMetrics.density
        controller.pause().stop().destroy()
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        return Triple(density, screen, out)
    }

    /** Line breaks that fall inside a word ("ENGIN|E", "THROT|TLE"). */
    private fun breaksInsideWord(text: String, layout: TextLayoutResult): List<Int> =
        (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }
            .filter { end -> end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace() }

    @Test fun `hero captions, units, peaks, pedal and tab names stay whole and unclipped at every font size and unit`() {
        val units = listOf(
            UserPrefs(speedUnit = "MPH", boostUnit = "PSI"),
            UserPrefs(speedUnit = "KPH", boostUnit = "KPA"),
            UserPrefs(speedUnit = "KPH", boostUnit = "BAR"),
        )
        val failures = mutableListOf<String>()
        for (w in listOf(360, 411)) for (fs in listOf(1.0f, 1.3f, 2.0f)) for (p in units) {
            val case = "$w dp font $fs ${p.speedLabel}/${p.displayGauge(0.0).second}"
            val (density, screen, labels) = render(w, fs, p)
            val boostUnit = p.displayGauge(0.0).second
            val heroes = listOf("ENGINE" to "RPM", "BOOST" to boostUnit, "SPEED" to p.speedLabel)
            val watched = heroes.flatMap { listOf(it.first, it.second) }.toSet() +
                setOf("BRAKE", "THROTTLE") + tabs
            val checked = labels.filter { it.text in watched || it.text.startsWith("PEAK ") }
            for (name in watched) if (checked.none { it.text == name }) failures += "[$case] '$name' not on screen"
            if (checked.count { it.text.startsWith("PEAK ") } != 3) failures += "[$case] PEAK lines: ${checked.filter { it.text.startsWith("PEAK") }.map { it.text }}"
            for (l in checked) {
                val layout = l.layout
                if (layout == null) { failures += "[$case] '${l.text}' has no text layout"; continue }
                breaksInsideWord(l.text, layout).takeIf { it.isNotEmpty() }?.let { failures += "[$case] '${l.text}' breaks inside a word at $it" }
                cut(layout, l.bounds, screen)?.let { failures += "[$case] '${l.text}' $it" }
            }
            // The tab bar's height is fixed: a tab name follows the system font up to 1.3 and no further
            for (l in checked.filter { it.text in tabs }) {
                val input = l.layout?.layoutInput ?: continue
                val dp = input.style.fontSize.value * input.density.fontScale
                if (dp > 9f * 1.3f + 0.01f) failures += "[$case] tab '${l.text}' at %.1f sp-equivalent, above 9 × 1.3".format(dp)
            }
            for ((caption, unit) in heroes) {
                val c = checked.firstOrNull { it.text == caption } ?: continue
                val u = checked.firstOrNull { it.text == unit && it.bounds.top < c.bounds.bottom + 60 * density && it.bounds.left >= c.bounds.left - 1 } ?: continue
                // on one line: they share at least half the smaller height (a stacked unit sits under the caption)
                val overlap = minOf(u.bounds.bottom, c.bounds.bottom) - maxOf(u.bounds.top, c.bounds.top)
                val sameLine = overlap > minOf(u.bounds.height, c.bounds.height) / 2
                if (fs == 1.0f && !sameLine) failures += "[$case] $caption / $unit: not on one line at the default font"
                val gapDp = (u.bounds.left - c.bounds.right) / density
                if (sameLine && gapDp < 4f) failures += "[$case] $caption / $unit touch: gap %.1f dp".format(gapDp)
            }
            println("#293 [$case] ok=${failures.none { it.startsWith("[$case]") }}")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
