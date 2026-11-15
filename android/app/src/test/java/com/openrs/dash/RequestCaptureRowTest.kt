package com.openrs.dash

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.DiagPage
import com.openrs.dash.ui.REQUEST_CAPTURE_LABEL
import com.openrs.dash.ui.REQUEST_CAPTURE_SWITCH_TAG
import com.openrs.dash.ui.REQUEST_CAPTURE_TEXT
import com.openrs.dash.ui.RequestCaptureRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #209 Step B: GARAGE → DIAG's Request capture switch, read by what the composition says and does. The owner's name and
 * text are shown; tapping it asks the connection's switch; with no live connection it can't be used; DIAG shows it only
 * with a service to ask. Teardown as the Compose rule in `.claude/rules/android-general.md` says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi", application = OpenRSDashApp::class)
class RequestCaptureRowTest {
    private fun idleFrames(n: Int) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    /**
     * Composes [content] and hands [read] a function returning the current root semantics node (call it again after a
     * tap and [idleFrames]), then destroys and drains (the Compose teardown rule).
     */
    private fun <T> composed(content: @Composable () -> Unit, read: (() -> SemanticsNode) -> T): T {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent { Box(Modifier.fillMaxSize()) { content() } }
        idleFrames(4)
        val root = {
            val compose = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            (compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
        }
        return try { read(root) } finally { controller.pause().stop().destroy(); idleFrames(8) }
    }

    /** Taps the nearest clickable node at or above the one showing [text]. */
    private fun SemanticsNode.tap(text: String) {
        var n: SemanticsNode? = all().first { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
        while (n != null && !n.config.contains(SemanticsActions.OnClick)) n = n.parent
        n!!.config[SemanticsActions.OnClick].action!!.invoke()
        idleFrames(6)
    }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }
    private fun SemanticsNode.texts() = all().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    private fun SemanticsNode.switch() = all().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == REQUEST_CAPTURE_SWITCH_TAG }

    @Test fun `the row shows the owner's name and text, and a tap asks to turn the capture on`() {
        val asked = mutableListOf<Boolean>()
        composed({ RequestCaptureRow(on = false, enabled = true, onChange = { asked += it }) }) { r ->
            val root = r()
            assertTrue(root.texts().toString(), REQUEST_CAPTURE_LABEL in root.texts() && REQUEST_CAPTURE_TEXT in root.texts())
            val sw = root.switch()
            assertNotNull("the switch", sw)
            sw!!.config[SemanticsActions.OnClick].action!!.invoke()
        }
        assertEquals(listOf(true), asked)
    }

    @Test fun `with no live connection the switch can't be used`() {
        composed({ RequestCaptureRow(on = false, enabled = false, onChange = {}) }) { r ->
            val sw = r().switch()
            assertNotNull("the switch", sw)
            assertTrue("disabled", sw!!.config.contains(SemanticsProperties.Disabled))
        }
    }

    /** N5: a tap the switch refuses (no live link at that moment) says why, in DIAG's snackbar. */
    @Test fun `a refused tap says why in DIAG's snackbar`() {
        val vs = VehicleState(isConnected = true)
        val snack = androidx.compose.material3.SnackbarHostState()
        composed({ DiagPage(lines = emptyList(), vs = vs, onScanDtcs = null, onRequestCapture = { false }, snackbarHostState = snack) }) { r ->
            r().tap("DEVELOPER")
            r().switch()!!.config[SemanticsActions.OnClick].action!!.invoke()
            idleFrames(6)
            assertEquals(com.openrs.dash.ui.REQUEST_CAPTURE_REFUSED, snack.currentSnackbarData?.visuals?.message)
        }
    }

    /** The switch sits beside the DID prober, in DIAG's DEVELOPER section (collapsed by default, as the prober is). */
    @Test fun `DIAG shows the switch beside the DID prober with a service to ask, and not without one`() {
        val vs = VehicleState(isConnected = true)
        composed({ DiagPage(lines = emptyList(), vs = vs, onScanDtcs = null, onRequestCapture = { true }) }) { r ->
            r().tap("DEVELOPER")
            val t = r().texts()
            assertTrue("with a service: $t", REQUEST_CAPTURE_TEXT in t)
            assertTrue("after the DID prober: $t", t.indexOf("DID PROBER") in 0 until t.indexOf(REQUEST_CAPTURE_LABEL))
        }
        composed({ DiagPage(lines = emptyList(), vs = vs, onScanDtcs = null) }) { r ->
            r().tap("DEVELOPER")
            assertFalse("without one", REQUEST_CAPTURE_TEXT in r().texts())
        }
    }
}
