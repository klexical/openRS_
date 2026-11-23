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
import com.openrs.dash.ui.Accent
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.Surf
import com.openrs.dash.ui.WhatsNewDialog
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
 * #508 (What's New part): at 360 dp and font 1.3 the title ("What's New — v…") ran up to the ✕ close button. The
 * production [WhatsNewDialog] under the app's MaterialTheme at 360 dp, font 1.0 / 1.3 / 2.0, for a stable, an RC and a
 * nightly release name (the longest shipped): the close button keeps its full 48 × 48 dp target inside the dialog, and
 * every piece of the title ends at least [GAP_DP] before that target starts and stays inside the dialog.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class WhatsNewTitleFitTest {

    private companion object {
        const val GAP_DP = 4f
        val NAMES = listOf("2.2.7", "2.2.7-rc.8", "2.2.7-nightly.20261123")
        val SCALES = listOf(1.0f, 1.3f, 2.0f)
    }

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        idle(8)
    }

    private fun idle(frames: Int) = repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private fun windows(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        return (wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<View>).toList()
    }

    private fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap { nodes(it) }

    private fun textOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    /** The dialog's own Compose root (a dialog is a window of its own, not part of the activity's tree). */
    private fun dialogRoot(): SemanticsNode {
        fun find(v: View): ViewRootForTest? = v as? ViewRootForTest
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        repeat(100) {
            windows().asReversed().filter { it.isAttachedToWindow }.mapNotNull { find(it) }
                .map { it.semanticsOwner.unmergedRootSemanticsNode }
                .firstOrNull { root -> nodes(root).any { textOf(it) == "What's New" } }
                ?.let { return it }
            idle(5)
        }
        error("What's New dialog not shown")
    }

    private fun problems(fontScale: Float, version: String): List<String> {
        // The phone's font size: a dialog window takes its density from the configuration, not from a LocalDensity
        RuntimeEnvironment.setFontScale(fontScale)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                WhatsNewDialog(onDismiss = {}, version = version)
            }
        }
        idle(30)
        val density = controller.get().resources.displayMetrics.density
        val case = "font $fontScale v$version"
        val all = nodes(dialogRoot()).filter { it.layoutInfo.isPlaced }
        val out = mutableListOf<String>()
        val close = all.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Close" }
        // The dialog's panel: the widest placed node (the root of a dialog window is the panel itself or its wrapper)
        val panel = all.maxBy { it.boundsInRoot.width }.boundsInRoot
        val title = all.filter { n -> textOf(n).let { it == "What's New" || it == " — v$version" } }
        if (close == null) out += "[$case] close button not found"
        if (title.size != 2) out += "[$case] title pieces found: ${title.map { textOf(it) }}"
        if (close != null) {
            val c = close.boundsInRoot
            if (c.width / density < 47.5f || c.height / density < 47.5f)
                out += "[$case] close target ${c.width / density} × ${c.height / density} dp"
            if (c.right > panel.right + 0.5f) out += "[$case] close target past the dialog edge"
            for (t in title) {
                val b = t.boundsInRoot
                val gap = (c.left - b.right) / density
                // only a piece beside the button can meet it; one wrapped below the button's row cannot
                val besideButton = b.top < c.bottom && b.bottom > c.top
                if (besideButton && gap < GAP_DP) out += "[$case] '${textOf(t)}' ends ${"%.1f".format(gap)} dp from the close target"
                if (b.left < panel.left - 0.5f || b.right > panel.right + 0.5f) out += "[$case] '${textOf(t)}' outside the dialog"
            }
        }
        println("#508W [$case] close=${close?.boundsInRoot} title=${title.map { textOf(it) to it.boundsInRoot }}")
        controller.pause().stop().destroy()
        idle(8)
        return out
    }

    @Test fun `What's New title keeps clear of the close button - 360 dp, font 1_0 1_3 2_0, every release name`() {
        val out = SCALES.flatMap { fs -> NAMES.flatMap { problems(fs, it) } }
        assertEquals(emptyList<String>(), out)
    }
}
