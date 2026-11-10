package com.openrs.dash

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.AppHeader
import com.openrs.dash.ui.DrivePage
import com.openrs.dash.ui.LinkUi
import com.openrs.dash.ui.MorePage
import com.openrs.dash.ui.UserPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * #289, REQ-rc8-launch-control-reference (AUDIT.md r2 §3; owner: "Show "LC" this is what the car natively shows."): the
 * production header, DRIVE and GARAGE, read by what they say on screen and to TalkBack.
 * - One small "LC", from 0x420 byte 6 bit 2 in a documented frame, only while that frame is fresh on this connection.
 * - Never "ON" or "ENGAGED", and nothing from 0x225 (MS-CAN per DigiCluster).
 * - GARAGE shows no 0x731 probe state (ARMED / STANDBY / RPM, Auto Start-Stop ACTIVE / OFF).
 * Frames are synthetic; they go through the real decoder and stamping (CanDecoder.decode + stampFresh, as LinkSession does).
 */
@RunWith(RobolectricTestRunner::class)
// DrivePage and MorePage read OpenRSDashApp.instance: start the app here, or the class passes only after another one has
@Config(qualifiers = "w411dp-h914dp-xxhdpi", application = OpenRSDashApp::class)
class LaunchControlDisplayTest {
    @Before fun reset() { CanDecoder.resetSessionState() }

    private val now get() = System.currentTimeMillis()

    /** A documented 0x420 detail: byte 6 = 0x11 (Sport / Track) with or without the LC bit, byte 7 0xCC (Sport). */
    private fun f420(lc: Boolean) = ByteArray(8).also { it[6] = (0x11 or if (lc) 0x04 else 0).toByte(); it[7] = 0xCC.toByte() }
    private fun f225(on: Boolean) = ByteArray(8).also { it[5] = (if (on) 0x08 else 0).toByte() }

    private fun feed(vs: VehicleState, id: Int, data: ByteArray, at: Long): VehicleState {
        val decoded = CanDecoder.decode(id, data, vs) ?: error("0x%03X not decoded".format(id))
        return CanDecoder.stampFresh(id, vs, decoded, at)
    }

    private val live = VehicleState(isConnected = true, escStatus = EscStatus.fromInt(2))

    /**
     * Every text, content description and state description the composition exposes (unmerged tree). [state] is built again
     * just before the last frames, so a frame stamped "now" is a few frames old when read, however long the activity took to
     * set up (the LC window is 1.5 s of wall clock; a cold Robolectric render can take longer).
     */
    private fun said(state: () -> VehicleState, content: @Composable (VehicleState) -> Unit): List<String> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val vs = androidx.compose.runtime.mutableStateOf(state())
        activity.setContent { Box(Modifier.fillMaxSize()) { content(vs.value) } }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        vs.value = state()
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val compose = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            n.config.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
            n.children.forEach { walk(it) }
        }
        walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        controller.pause().stop().destroy()
        return out
    }

    private fun header(state: () -> VehicleState) = said(state) { vs ->
        AppHeader(vs = vs, prefs = UserPrefs(), onSettings = {}, onConnect = {}, onDisconnect = {}, onReconnect = {}, link = LinkUi.LIVE)
    }
    private fun drive(state: () -> VehicleState) = said(state) { vs -> DrivePage(vs = vs, p = UserPrefs(), link = LinkUi.LIVE) }
    private fun garage(state: () -> VehicleState) = said(state) { vs -> MorePage(vs = vs, p = UserPrefs(), snackbarHostState = SnackbarHostState()) }

    /** Any launch-control wording on screen or for TalkBack. */
    private fun lcWords(said: List<String>) = said.filter {
        it == "LC" || it.startsWith("LC ") || it.contains("LAUNCH CONTROL", ignoreCase = true) || it.contains("ENGAGED", ignoreCase = true)
    }
    /** What must never appear: a state word beside LC, ENGAGED, or the app's own caption. */
    private fun forbidden(said: List<String>) = said.filter {
        it.startsWith("LC ") || it.contains("ENGAGED", ignoreCase = true) || it.contains("LAUNCH CONTROL ·") ||
            it.contains("Launch control on", ignoreCase = true) || it.contains("Launch control engaged", ignoreCase = true)
    }

    @Test fun `positive - a fresh documented 0x420 with bit 2 shows LC, in the header and on DRIVE, and no state word`() {
        val vs = { feed(live, CanDecoder.ID_DRIVE_MODE_EXT, f420(lc = true), now) }
        val h = header(vs); val d = drive(vs)
        assertTrue("header shows LC: $h", "LC" in h)
        assertTrue("DRIVE shows LC: ${lcWords(d)}", "LC" in d)
        assertEquals(emptyList<String>(), forbidden(h) + forbidden(d))
    }

    @Test fun `negative - bit 2 clear shows no LC`() {
        val vs = { feed(live, CanDecoder.ID_DRIVE_MODE_EXT, f420(lc = false), now) }
        assertEquals(emptyList<String>(), lcWords(header(vs)) + lcWords(drive(vs)))
    }

    @Test fun `stale - the last 0x420 is older than 1_5 s with the link up - no LC`() {
        val vs = { feed(live, CanDecoder.ID_DRIVE_MODE_EXT, f420(lc = true), now - 2_000L) }
        assertEquals(emptyList<String>(), lcWords(header(vs)) + lcWords(drive(vs)))
        assertGarageRowWithoutLc(garage(vs))
    }

    @Test fun `missing - a held flag with no 0x420 received shows no LC`() {
        val vs = { live.copy(launchControlActive = true) }
        assertEquals(emptyList<String>(), lcWords(header(vs)) + lcWords(drive(vs)))
        assertGarageRowWithoutLc(garage(vs))
    }

    /** GARAGE (QA F2): the FEATURES panel is on screen (link up) and its Launch Control row doesn't read "LC". */
    private fun assertGarageRowWithoutLc(g: List<String>) {
        assertTrue("GARAGE's Launch Control row is on screen: $g", "Launch Control" in g)
        assertTrue("GARAGE shows no LC: $g", "LC" !in g)
    }

    @Test fun `0x225 bit 3 set never shows LC or ENGAGED`() {
        val vs = { feed(live, CanDecoder.ID_LAUNCH_CTRL, f225(on = true), now) }
        assertEquals(emptyList<String>(), lcWords(header(vs)) + lcWords(drive(vs)))
    }

    @Test fun `ESC raw 3 alone shows no LC`() {
        val vs = { live.copy(escStatus = EscStatus.fromInt(3)) }
        assertEquals(emptyList<String>(), lcWords(header(vs)) + lcWords(drive(vs)))
    }

    @Test fun `GARAGE shows no 0x731 probe state - no ARMED, STANDBY, RPM setpoint or Auto Start-Stop ACTIVE`() {
        val vs = { live.copy(lcArmed = true, lcRpmTarget = 4500, assEnabled = true) }
        val g = garage(vs)
        assertTrue("the FEATURES rows are on screen: $g", "Launch Control" in g && "Auto Start-Stop" in g)
        val probe = g.filter { it == "ARMED" || it == "STANDBY" || it.endsWith(" RPM") || it == "ACTIVE" }
        assertEquals(emptyList<String>(), probe)
        assertEquals(emptyList<String>(), lcWords(g).filter { it != "LAUNCH CONTROL" && it.contains("ENGAGED") })
    }

    @Test fun `GARAGE's Launch Control row reads LC only from a fresh 0x420, never from the probe`() {
        val armedOnly = garage { live.copy(lcArmed = true) }
        assertTrue("no LC from the probe alone: $armedOnly", "LC" !in armedOnly)
        val fresh = garage { feed(live, CanDecoder.ID_DRIVE_MODE_EXT, f420(lc = true), now) }
        assertTrue("LC from a fresh 0x420: $fresh", "LC" in fresh)
        assertEquals(emptyList<String>(), forbidden(fresh))
    }
}
