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

    private fun escDescription(vs: VehicleState): String {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
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
}
