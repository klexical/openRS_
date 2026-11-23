package com.openrs.dash

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import com.openrs.dash.data.ForscanPid
import com.openrs.dash.ui.PidRow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #368: a long press on a PID row in GARAGE → DIAG → PID BROWSER copies its DID. From Android 13 (API 33) Android shows
 * its own clipboard confirmation, so the app shows no message of its own there; on 12L (API 32) and earlier it keeps
 * "Copied <DID>". The production row, long-pressed through its semantics, at each SDK level. Teardown as the Compose rule
 * in `.claude/rules/android-general.md` says.
 */
@RunWith(RobolectricTestRunner::class)
class PidCopyMessageTest {
    private fun idleFrames(n: Int) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    /** Long-presses the row of a PID with DID 0x0304; returns the app's messages and the clipboard's text. */
    private fun longPressCopy(): Pair<List<String>, String?> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val messages = mutableListOf<String>()
        val pid = ForscanPid(name = "Battery charging target", description = "", unit = "V", status = "monitored", did = "0x0304")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setContent { PidRow(pid, Color(0xFF0091EA), onCopyNotify = { messages += it }) }
            idleFrames(4)
            val compose = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val root = (compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            val row = root.all().first { it.config.contains(SemanticsActions.OnLongClick) }
            row.config[SemanticsActions.OnLongClick].action!!.invoke()
            idleFrames(4)
            val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            return messages to clip?.getItemAt(0)?.text?.toString()
        } finally {
            controller.pause().stop().destroy(); idleFrames(8)
        }
    }

    @Config(sdk = [33])
    @Test fun `on Android 13 the DID is copied and the app shows no message of its own`() {
        val (messages, clip) = longPressCopy()
        assertEquals("0x0304", clip)
        assertEquals(emptyList<String>(), messages)
    }

    @Config(sdk = [35])
    @Test fun `on Android 15 the DID is copied and the app shows no message of its own`() {
        val (messages, clip) = longPressCopy()
        assertEquals("0x0304", clip)
        assertEquals(emptyList<String>(), messages)
    }

    @Config(sdk = [32])
    @Test fun `on Android 12L the DID is copied and the app says so`() {
        val (messages, clip) = longPressCopy()
        assertEquals("0x0304", clip)
        assertEquals(listOf("Copied 0x0304"), messages)
    }
}
