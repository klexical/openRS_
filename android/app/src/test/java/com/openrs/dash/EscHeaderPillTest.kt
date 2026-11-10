package com.openrs.dash

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.AppHeader
import com.openrs.dash.ui.LinkUi
import com.openrs.dash.ui.UserPrefs
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * REQ-rc8-esc-owner-wording: the production header's ESC pill, read by what it says to TalkBack (the ESC symbol's
 * content description). Before any 0x1C0 frame, and for raw 3, it says unknown — never "ON" or "Launch"; a known state
 * uses Ford's word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class EscHeaderPillTest {

    // Every activity built here is destroyed after the test and the main looper drained: a composition left alive or with
    // work queued at exit stops later Compose tests in the same JVM from recomposing (LaunchControlDisplayTest, QA F3)
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<ComponentActivity>>()

    @After fun closeActivities() {
        activities.forEach { it.pause().stop().destroy() }
        activities.clear()
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    private fun escDescription(vs: VehicleState): String {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { activities += it }.get()
        activity.setContent {
            Box(Modifier.fillMaxWidth()) {
                AppHeader(vs = vs, prefs = UserPrefs(), onSettings = {}, onConnect = {}, onDisconnect = {}, onReconnect = {},
                    link = LinkUi.LIVE)
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val compose = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val found = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            if (!n.layoutInfo.isPlaced) return   // what is on screen: SubcomposeLayout keeps measured-only copies composed
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.filter { it.startsWith("Stability control") }?.let { found += it }
            n.children.forEach { walk(it) }
        }
        walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        return found.single()
    }

    @Test fun `no 0x1C0 frame yet - the ESC pill says unknown, not ON`() =
        assertEquals("Stability control: unknown", escDescription(VehicleState(isConnected = true)))

    @Test fun `raw 3 - the ESC pill says unknown, not Launch`() =
        assertEquals("Stability control: unknown", escDescription(VehicleState(isConnected = true, escStatus = EscStatus.fromInt(3))))

    @Test fun `known states use Ford's words`() {
        assertEquals("Stability control: Normal", escDescription(VehicleState(isConnected = true, escStatus = EscStatus.fromInt(0))))
        assertEquals("Stability control: Sport", escDescription(VehicleState(isConnected = true, escStatus = EscStatus.fromInt(2))))
        assertEquals("Stability control: Off", escDescription(VehicleState(isConnected = true, escStatus = EscStatus.fromInt(1))))
    }

    /** Everything TalkBack can announce: the MERGED semantics tree's descriptions and texts, placed nodes only. */
    private fun announcements(vs: VehicleState): List<String> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { activities += it }.get()
        activity.setContent {
            Box(Modifier.fillMaxWidth()) {
                AppHeader(vs = vs, prefs = UserPrefs(), onSettings = {}, onConnect = {}, onDisconnect = {}, onReconnect = {},
                    link = LinkUi.LIVE)
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val compose = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            if (!n.layoutInfo.isPlaced) return
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            n.config.getOrNull(SemanticsProperties.Text)?.let { out += it.map { s -> s.text } }
            n.children.forEach { walk(it) }
        }
        walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.rootSemanticsNode)
        return out
    }

    @Test fun `the ESC pill is announced once - its visible word is not read again (merged tree, QA A2)`() {
        // Drive mode NORMAL + ESC Sport (a synthetic pair): only the ESC pill can say "SPORT"
        val said = announcements(VehicleState(isConnected = true, driveMode = com.openrs.dash.data.DriveMode.NORMAL,
            escStatus = EscStatus.fromInt(2)))
        assertEquals(said.toString(), 1, said.count { it == "Stability control: Sport" })
        assertEquals("the visible ESC word read separately: $said", 0, said.count { it == "SPORT" })
    }
}
