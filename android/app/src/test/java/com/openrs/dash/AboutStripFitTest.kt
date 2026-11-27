package com.openrs.dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.openrs.dash.ui.AboutStrip
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.SettingsDialog
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.UserPrefsStore
import com.openrs.dash.update.BuildInfo
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
 * #594: at 360 dp Settings' ABOUT strip gave the version label the row and left UPDATES a column one character wide,
 * so it stacked U / P / D / A / T / E / S down the strip's edge (debug build, every font size). At 360 dp, font 1.0 /
 * 1.3 / 2.0: the production [SettingsDialog] (this build's debug label), and [AboutStrip] at the width the dialog gives
 * it with the longest debug and release labels (a nightly's name). UPDATES is one line and ends inside the strip, the
 * label ends at least [GAP_DP] before it, and the strip's touch target is at least 48 dp tall.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi", application = OpenRSDashApp::class)
class AboutStripFitTest {

    private companion object {
        const val GAP_DP = 4f
        val SCALES = listOf(1.0f, 1.3f, 2.0f)
        const val NIGHTLY = "2.2.7-nightly.20261123"
        val LABELS = listOf(
            "debug" to BuildInfo.settingsLabel(NIGHTLY, 99, "debug", "1549b7e", dirty = true),
            "release" to BuildInfo.settingsLabel(NIGHTLY, 99, "release", "1549b7e", dirty = false),
        )
    }

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        UserPrefsStore.resetForTest()
        idle(8)
    }

    private fun idle(frames: Int) = repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap { nodes(it) }

    private fun textOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun windows(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        return (wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<View>).toList()
    }

    private fun composeRoot(v: View): ViewRootForTest? = v as? ViewRootForTest
        ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { composeRoot(g.getChildAt(it)) } }

    /** The topmost window that holds UPDATES (Settings is a dialog window of its own). */
    private fun stripNodes(): List<SemanticsNode> {
        repeat(100) {
            windows().asReversed().filter { it.isAttachedToWindow }.mapNotNull { composeRoot(it) }
                .map { nodes(it.semanticsOwner.unmergedRootSemanticsNode).filter { n -> n.layoutInfo.isPlaced } }
                .firstOrNull { all -> all.any { textOf(it) == "UPDATES" } }
                ?.let { return it }
            idle(5)
        }
        error("UPDATES not shown")
    }

    private fun lines(n: SemanticsNode): Int? {
        val layout = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        return layout.firstOrNull()?.lineCount
    }

    /** Composes [content], checks the strip; returns its problems and the strip's width in dp. */
    private fun check(case: String, fontScale: Float, content: @androidx.compose.runtime.Composable () -> Unit): Pair<List<String>, Float> {
        RuntimeEnvironment.setFontScale(fontScale)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) { content() }
        }
        try {
            idle(60)
            val density = controller.get().resources.displayMetrics.density
            val all = stripNodes()
            val out = mutableListOf<String>()
            val updates = all.first { textOf(it) == "UPDATES" }
            val label = all.firstOrNull { textOf(it)?.startsWith("openRS_ v") == true }
            val row = all.filter { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show updates" }
                .minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
            if (lines(updates) != 1) out += "[$case] UPDATES takes ${lines(updates)} lines"
            if (row == null) out += "[$case] strip toggle not found"
            if (row != null) {
                val r = row.boundsInRoot
                if (r.height / density < 47.5f) out += "[$case] strip target ${r.height / density} dp tall"
                if (updates.boundsInRoot.right > r.right + 0.5f) out += "[$case] UPDATES ends past the strip"
            }
            if (label == null) out += "[$case] version label not found"
            label?.let {
                val gap = (updates.boundsInRoot.left - it.boundsInRoot.right) / density
                if (gap < GAP_DP) out += "[$case] label ends ${"%.1f".format(gap)} dp from UPDATES"
            }
            println("#594 [$case] updates=${updates.boundsInRoot} label=${label?.boundsInRoot} row=${row?.boundsInRoot}")
            return out to ((row?.boundsInRoot?.width ?: 0f) / density)
        } finally {
            controller.pause().stop().destroy()
            idle(8)
        }
    }

    @Test fun `ABOUT strip keeps UPDATES one line - 360 dp, font 1_0 1_3 2_0, debug and release labels`() {
        val out = SCALES.flatMap { fs ->
            val (dialog, width) = check("font $fs SettingsDialog", fs) { SettingsDialog(onDismiss = {}) }
            dialog + LABELS.flatMap { (kind, version) ->
                check("font $fs $kind", fs) {
                    Box(Modifier.width(width.dp)) {
                        AboutStrip(expanded = false, onToggle = {}, updateChannel = "beta", onChannelChange = {},
                            versionLabel = version)
                    }
                }.first
            }
        }
        assertEquals(emptyList<String>(), out)
    }
}
