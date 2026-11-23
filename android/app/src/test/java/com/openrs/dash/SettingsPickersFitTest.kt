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
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.SettingsDialog
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.UserPrefsStore
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
 * #477 at 360 dp: Settings' pickers outside UNITS (Theme mode, Classic fonts, the edge glow's Color and Intensity, the
 * thermal profile, the adapter, the connection method and the update channel) since every Settings picker option became a
 * 48 dp target. The production [SettingsDialog] under the app's MaterialTheme (its text inherits Material's letter
 * spacing), each category opened as a user opens it, at font scale 1.0 and 1.3: each row's label is one line, each
 * option is one line (the adapter names may wrap: SegmentedPicker keeps their model code that way) and a 48 dp target.
 * Any wrap and any target under 48 dp fails (#507 fixed the five rows that wrapped before), and the dialog's CANCEL /
 * RESET / SAVE actions keep one line too (#508's CANCEL). [UnitsFitTest] holds Settings → UNITS.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class SettingsPickersFitTest {

    private class Shown(val text: String, val lines: Int, val clickable: Boolean, val wDp: Float, val hDp: Float)

    /** The pickers outside UNITS: (category, row label, options). */
    private val rows = listOf(
        Triple("DISPLAY", "Theme mode", listOf("NIGHT", "DAY", "AUTO", "ULTRA")),
        Triple("DISPLAY", "Classic fonts", listOf("MODERN", "CLASSIC")),
        Triple("DISPLAY", "Color", listOf("Accent", "White", "Progressive")),
        Triple("DISPLAY", "Intensity", listOf("Low", "Med", "High")),
        Triple("THERMAL", "Active profile", listOf("STREET", "TRACK", "RACE")),
        Triple("CONNECTION", "Hardware", listOf("MeatPi USB (C3)", "MeatPi Pro (S3)")),
        Triple("CONNECTION", "Connection method", listOf("WiFi", "Bluetooth")),
        Triple("ABOUT", "Update channel", listOf("stable", "beta")),
    )
    private val mayWrap = setOf("MeatPi USB (C3)", "MeatPi Pro (S3)")

    /** The dialog's bottom actions, always shown under every category (#508: CANCEL broke onto two lines at font 1.3). */
    private val actions = listOf("CANCEL", "RESET", "SAVE")

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        UserPrefsStore.resetForTest()
        RuntimeEnvironment.getApplication().getSharedPreferences("openrs_settings", 0).edit().clear().commit()
        idle(8)
    }

    private fun idle(frames: Int) = repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    /** The Settings dialog's own Compose root (a dialog is a window of its own, not part of the activity's tree). */
    private fun dialogView(): ViewRootForTest {
        repeat(100) { findDialog()?.let { return it }; idle(5) }
        return findDialog() ?: error("Settings dialog not shown")
    }

    private fun windows(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        return (wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<View>).toList()
    }

    /** Windows other test classes left behind can still be listed: the attached one that holds Settings. */
    private fun findDialog(): ViewRootForTest? {
        fun find(v: View): ViewRootForTest? = v as? ViewRootForTest
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return windows().asReversed().filter { it.isAttachedToWindow }.mapNotNull { find(it) }.firstOrNull { root ->
            nodes(root.semanticsOwner.unmergedRootSemanticsNode).any { textOf(it) == "Search settings…" }
        }
    }

    /** Lays the dialog out again: a click recomposes it, but the dialog window gets no traversal of its own here. */
    private fun relayout() {
        val v = dialogView() as View
        v.requestLayout()
        v.rootView.measure(View.MeasureSpec.makeMeasureSpec(v.rootView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(v.rootView.height, View.MeasureSpec.EXACTLY))
        v.rootView.layout(v.rootView.left, v.rootView.top, v.rootView.right, v.rootView.bottom)
    }

    private fun dialogRoot(): SemanticsNode = dialogView().semanticsOwner.unmergedRootSemanticsNode

    private fun textOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        ?: n.children.firstNotNullOfOrNull { textOf(it) }

    private fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap { nodes(it) }

    private fun click(match: (SemanticsNode) -> Boolean) {
        val n = nodes(dialogRoot()).first { it.config.getOrNull(SemanticsActions.OnClick) != null && match(it) }
        n.config[SemanticsActions.OnClick].action!!.invoke()
        idle(40); relayout(); idle(4)
    }

    private fun shown(density: Float): List<Shown> = nodes(dialogRoot()).mapNotNull { n ->
        val clickable = n.config.getOrNull(SemanticsActions.OnClick) != null
        val text = if (clickable) textOf(n) else n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        if (text == null) return@mapNotNull null
        val layouts = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        Shown(text, layouts.firstOrNull()?.lineCount ?: 0, clickable, n.size.width / density, n.size.height / density)
    }

    private fun problems(fontScale: Float): List<String> {
        val ctx = RuntimeEnvironment.getApplication()
        // The phone's font size, as Settings → Display sets it: a dialog is a window of its own and takes its density
        // from the configuration, not from a LocalDensity provided around it
        RuntimeEnvironment.setFontScale(fontScale)
        UserPrefsStore.update(ctx) { it.copy(edgeShiftLight = true) }   // the glow's Color and Intensity rows show
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                SettingsDialog(onDismiss = {})
            }
        }
        idle(20)
        val density = controller.get().resources.displayMetrics.density
        val out = mutableListOf<String>()
        val first = shown(density)
        for (a in actions) {
            val t = first.filter { !it.clickable && it.text == a }
            if (t.isEmpty()) out += "$a: not found" else if (t.any { it.lines != 1 }) out += "$a: ${t.maxOf { it.lines }} lines"
        }
        for (category in rows.map { it.first }.distinct()) {
            click { textOf(it)?.startsWith(category) == true }
            if (category == "ABOUT") {
                click { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show updates" }
            }
            val all = shown(density)
            for ((_, label, options) in rows.filter { it.first == category }) {
                val l = all.filter { !it.clickable && it.text == label }
                if (l.isEmpty()) { out += "$label: not found"; continue }
                if (l.any { it.lines != 1 }) out += "$label: ${l.maxOf { it.lines }} lines"
                for (o in options) {
                    val text = all.filter { !it.clickable && it.text == o }
                    val target = all.filter { it.clickable && it.text == o }
                    if (text.isEmpty() || target.isEmpty()) { out += "$label / $o: not found"; continue }
                    if (text.any { it.lines < 1 || (o !in mayWrap && it.lines > 1) }) out += "$label / $o: ${text.maxOf { it.lines }} lines"
                    target.filter { it.wDp < 47.5f || it.hDp < 47.5f }
                        .forEach { out += "$label / $o: ${it.wDp} × ${it.hDp} dp" }
                }
            }
            click { it.config.getOrNull(SemanticsActions.OnClick)?.label == "All settings" }
        }
        controller.pause().stop().destroy()
        idle(8)
        return out.distinct()
    }

    @Test fun `Settings pickers outside UNITS - one-line labels and options, 48 dp targets at 360 dp, font 1-0`() {
        assertEquals(emptyList<String>(), problems(1.0f))
    }

    @Test fun `Settings pickers outside UNITS - one-line labels and options, 48 dp targets at 360 dp, font 1-3`() {
        assertEquals(emptyList<String>(), problems(1.3f))
    }
}
