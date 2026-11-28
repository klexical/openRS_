package com.openrs.dash

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.trip.DELETE_TRIP_CANCEL
import com.openrs.dash.ui.trip.DELETE_TRIP_CONFIRM
import com.openrs.dash.ui.trip.DELETE_TRIP_TEXT
import com.openrs.dash.ui.trip.DELETE_TRIP_TITLE
import com.openrs.dash.ui.trip.SwipeDriveCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #631: a swipe left on a drive in TRIP's history used to delete it at once — the drive, its points and its laps,
 * with no undo. The production [SwipeDriveCard], swiped with real touch events: the swipe only asks ("Delete this
 * trip?"); only the dialog's DELETE deletes, and CANCEL keeps the drive. Teardown as the Compose rule in
 * `.claude/rules/android-general.md` says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi", application = OpenRSDashApp::class)
class TripDeleteConfirmTest {

    private fun idle(frames: Int) = repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { nodes(it) }
    private fun texts(n: SemanticsNode) = nodes(n).flatMap { m -> m.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun windows(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        return (wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<View>).toList()
    }

    /** The delete dialog's own Compose root (a dialog is a window of its own), or null when none is showing. */
    private fun dialogRoot(): SemanticsNode? {
        fun find(v: View): ViewRootForTest? = v as? ViewRootForTest
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return windows().asReversed().filter { it.isAttachedToWindow }.mapNotNull { find(it) }
            .map { it.semanticsOwner.unmergedRootSemanticsNode }
            .firstOrNull { DELETE_TRIP_TITLE in texts(it) }
    }

    /** Taps the nearest clickable node at or above the one showing [text]. */
    private fun SemanticsNode.tap(text: String) {
        var n: SemanticsNode? = nodes(this).first { m -> m.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
        while (n != null && !n.config.contains(SemanticsActions.OnClick)) n = n.parent
        n!!.config[SemanticsActions.OnClick].action!!.invoke()
        idle(10)
    }

    /**
     * Composes one drive card, swipes it right to left with touch events, and hands [after] the card's view; [deleted]
     * counts the card's onDelete calls. Destroys and drains at the end.
     */
    private fun swiped(after: (deleted: () -> Int) -> Unit) {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        var deletes = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            Box(Modifier.fillMaxWidth()) {
                SwipeDriveCard(
                    drive = DriveEntity(id = 7, startTime = 1_000_000L, endTime = 2_000_000L, name = "Test drive"),
                    prefs = UserPrefs(),
                    isSelected = false,
                    onClick = {},
                    onSummary = {},
                    onExport = {},
                    onDelete = { deletes++ },
                    onRename = {}
                )
            }
        }
        idle(6)
        try {
            val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val loc = IntArray(2).also { content.getLocationInWindow(it) }
            val root = activity.window.decorView
            val d = activity.resources.displayMetrics.density
            val y = loc[1] + 30 * d
            val startX = loc[0] + content.width * 0.9f
            val endX = loc[0] + content.width * 0.05f
            val t0 = SystemClock.uptimeMillis()
            root.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, startX, y, 0))
            val steps = 10
            for (i in 1..steps) {
                val x = startX + (endX - startX) * i / steps
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8))
                root.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + i * 8L, MotionEvent.ACTION_MOVE, x, y, 0))
            }
            root.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + steps * 8L + 8, MotionEvent.ACTION_UP, endX, y, 0))
            idle(60)                                   // the settle animation runs to the end
            after { deletes }
        } finally {
            controller.pause().stop().destroy()
            idle(8)
        }
    }

    @Test fun `a swipe asks first and deletes nothing`() = swiped { deleted ->
        assertEquals("a swipe alone must not delete the drive", 0, deleted())
        val dialog = dialogRoot()
        assertNotNull("the swipe shows the delete confirmation", dialog)
        val shown = texts(dialog!!)
        assertEquals(listOf(true, true, true), listOf(DELETE_TRIP_TEXT in shown, DELETE_TRIP_CONFIRM in shown, DELETE_TRIP_CANCEL in shown))
    }

    @Test fun `DELETE in the dialog deletes the drive once`() = swiped { deleted ->
        dialogRoot()!!.tap(DELETE_TRIP_CONFIRM)
        assertEquals(1, deleted())
        assertNull("the dialog closes", dialogRoot())
    }

    @Test fun `CANCEL keeps the drive`() = swiped { deleted ->
        dialogRoot()!!.tap(DELETE_TRIP_CANCEL)
        assertEquals(0, deleted())
        assertNull("the dialog closes", dialogRoot())
    }
}
