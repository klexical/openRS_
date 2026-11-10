package com.openrs.dash

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.openrs.dash.data.ObserverNote
import com.openrs.dash.ui.trip.ObserverNoteForm
import org.junit.Assert.assertEquals
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
 * The observer's note form (TRIP, long-press on MARK): a preset saves in one tap, free text is bounded and saved by
 * SAVE NOTE, CANCEL saves nothing, and the form says the note goes into the drive and its exports. The production form,
 * driven through its semantics actions (no dialog window; `ObserverNoteDialog` only wraps it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi", application = OpenRSDashApp::class)
class ObserverNoteFormTest {

    private fun idle(n: Int = 4) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private class Form(val saved: MutableList<String>, val cancelled: IntArray, val root: () -> SemanticsNode)

    private fun form(block: Form.() -> Unit) {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val saved = mutableListOf<String>(); val cancelled = IntArray(1)
        activity.setContent { ObserverNoteForm(onSave = { saved += it }, onCancel = { cancelled[0]++ }) }
        idle()
        val compose = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        try {
            Form(saved, cancelled) { (compose.getChildAt(0) as ViewRootForTest).semanticsOwner.rootSemanticsNode }.block()
        } finally {
            controller.pause().stop().destroy()
            idle(8)
        }
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { all(it) }
    private fun texts(n: SemanticsNode) = all(n).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
    private fun Form.tap(label: String) {
        val node = all(root()).single { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }
        node.config[SemanticsActions.OnClick].action!!.invoke(); idle()
    }
    private fun Form.type(text: String) {
        val node = all(root()).single { it.config.getOrNull(SemanticsActions.SetText) != null }
        node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)); idle()
    }

    @Test fun `the form names itself, says where the note goes, and offers the four cluster modes`() = form {
        val t = texts(root())
        assertTrue("$t", "OBSERVER NOTE" in t && t.any { "Saved with the drive and included in its exports" in it })
        assertTrue("$t", t.containsAll(ObserverNote.PRESETS))
    }

    @Test fun `a preset saves that note in one tap`() = form {
        tap("Cluster showed SPORT")
        assertEquals(listOf("Cluster showed SPORT"), saved); assertEquals(0, cancelled[0])
    }

    @Test fun `free text is saved cleaned and bounded`() = form {
        type("  rear  felt loose\n at the hairpin " + "x".repeat(100))
        tap("SAVE NOTE")
        val s = saved.single()
        assertTrue(s, s.startsWith("rear felt loose at the hairpin x") && s.length <= ObserverNote.MAX && '\n' !in s)
    }

    @Test fun `SAVE NOTE with no text saves an unlabelled marker - CANCEL saves nothing`() = form {
        tap("SAVE NOTE"); assertEquals(listOf(""), saved)
        tap("CANCEL"); assertEquals(1, cancelled[0]); assertEquals(1, saved.size)
    }
}
