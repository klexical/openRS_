package com.openrs.dash

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.DrivePage
import com.openrs.dash.ui.LinkUi
import com.openrs.dash.ui.UserPrefs
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
 * #307 item 2: DRIVE's tyre alerts (the status line, the VEHICLE summary and its chips) judge a wheel only by a current
 * reading — the rule THERMAL's TYRES panel uses (`tyreView`, 60 s per wheel). A held pressure, its own stamp older than
 * 60 s while the link is up, raises no live LOW / PRESSURE / HIGH alert; a current wheel beside it still does. Read from
 * the production page (`DrivePage`) by what it says on screen. Pressures arrive through the real BCM reply path
 * (parser → ObdMerge), then the stamp is aged; the values are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
// DrivePage reads OpenRSDashApp.instance: start the app here, or the class passes only after another one has
@Config(qualifiers = "w411dp-h914dp-xxhdpi", application = OpenRSDashApp::class)
class DriveTyreAlertFreshnessTest {

    private val now get() = System.currentTimeMillis()

    /** BCM 0x2813 (LF) / 0x2814 (RF) / 0x2816 (LR) / 0x2815 (RR) reply for [psi]: psi = ((raw / 3) + 22 / 3) × 0.145. */
    private fun tpms(did: Int, psi: Double): ByteArray {
        val raw = Math.round((psi / 0.145 - 22.0 / 3.0) * 3.0).toInt()
        return byteArrayOf(0x05, 0x62, (did shr 8).toByte(), did.toByte(), (raw shr 8).toByte(), raw.toByte(), 0, 0)
    }

    private fun reply(current: VehicleState, did: Int, psi: Double): VehicleState {
        var merged = current
        ObdResponseParser.parseBcmResponse(tpms(did, psi), current) { merged = ObdMerge.merge(current, it) }
        return merged
    }

    /** Four replies through the parser; then FL's stamp is moved [lfAgeMs] into the past and the others' [othersAgeMs]. */
    private fun car(lf: Double, rf: Double = 38.0, lr: Double = 38.0, rr: Double = 38.0,
                    lfAgeMs: Long = 0L, othersAgeMs: Long = 0L, connected: Boolean = true): VehicleState {
        var vs = VehicleState(isConnected = true)
        vs = reply(vs, 0x2813, lf); vs = reply(vs, 0x2814, rf); vs = reply(vs, 0x2816, lr); vs = reply(vs, 0x2815, rr)
        assertTrue("the four pressures decoded: $vs", listOf(vs.tirePressLF, vs.tirePressRF, vs.tirePressLR, vs.tirePressRR).all { it > 0 })
        val t = now
        val stamps = vs.fieldLastUpdateMs + mapOf(
            "tirePressLF" to t - lfAgeMs, "tirePressRF" to t - othersAgeMs,
            "tirePressLR" to t - othersAgeMs, "tirePressRR" to t - othersAgeMs)
        return vs.copy(fieldLastUpdateMs = stamps, tpmsLastUpdate = t - minOf(lfAgeMs, othersAgeMs), isConnected = connected,
            lastUpdate = t)
    }

    private fun idleFrames(n: Int) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    /** Every text DRIVE shows for [state] (unmerged tree). The state is rebuilt just before the last frames. */
    private fun said(prefs: UserPrefs = UserPrefs(), link: LinkUi = LinkUi.LIVE, state: () -> VehicleState): List<String> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val vs = androidx.compose.runtime.mutableStateOf(state())
        activity.setContent { Box(Modifier.fillMaxSize()) { DrivePage(vs = vs.value, p = prefs, link = link) } }
        idleFrames(4)
        vs.value = state()
        idleFrames(4)
        val compose = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.children.forEach { walk(it) }
        }
        walk((compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        controller.pause().stop().destroy()
        idleFrames(8)
        return out
    }

    /** Tyre alert wording: the title ("FL TYRE LOW", also the VEHICLE summary, maybe with "+N") and the chip ("FL 25.0 psi LOW"). */
    private fun tyreAlerts(said: List<String>) = said.filter { it.contains("TYRE") || Regex("^(FL|FR|RL|RR) ").containsMatchIn(it) }

    @Test fun `control - a current low pressure leads DRIVE as a tyre alert`() {
        val s = tyreAlerts(said { car(lf = 25.0) })
        assertTrue("$s", s.any { it.startsWith("FL TYRE LOW") })
    }

    @Test fun `a stale low wheel beside three current ones raises no tyre alert`() {
        val s = said { car(lf = 25.0, lfAgeMs = 3 * 60_000L + 5_000L) }
        assertEquals("a held pressure is not a live alarm on DRIVE: $s", emptyList<String>(), tyreAlerts(s))
    }

    @Test fun `a stale low wheel does not hide a current low one`() {
        val s = tyreAlerts(said { car(lf = 25.0, rr = 27.0, lfAgeMs = 3 * 60_000L) })
        assertTrue("the current low wheel is the alert: $s", s.any { it.startsWith("RR TYRE LOW") })
        assertTrue("the held one is not: $s", s.none { it.startsWith("FL") })
    }

    @Test fun `a stale wheel under Warn raises no caution`() {
        val s = said { car(lf = 32.0, lfAgeMs = 61_000L) }
        assertEquals("$s", emptyList<String>(), tyreAlerts(s))
    }

    @Test fun `a stale wheel over High raises no TYRE HIGH`() {
        val s = said { car(lf = 52.0, lfAgeMs = 2 * 60_000L) }
        assertEquals("$s", emptyList<String>(), tyreAlerts(s))
    }

    @Test fun `a new reply makes the wheel current again`() {
        val held = car(lf = 25.0, lfAgeMs = 5 * 60_000L)
        val s = tyreAlerts(said { reply(held, 0x2813, 24.0) })
        assertTrue("$s", s.any { it.startsWith("FL TYRE LOW") })
    }

    @Test fun `BAR - the same rule in the other unit`() {
        val bar = UserPrefs(tireUnit = "BAR")
        assertTrue(tyreAlerts(said(bar) { car(lf = 25.0) }).any { it.startsWith("FL TYRE LOW") })
        assertEquals(emptyList<String>(), tyreAlerts(said(bar) { car(lf = 25.0, lfAgeMs = 3 * 60_000L) }))
    }
}
