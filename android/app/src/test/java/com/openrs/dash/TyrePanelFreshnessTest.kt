package com.openrs.dash

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.UnifiedChassisSection
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
 * REQ-rc8-app-capture-improvements, "THERMAL tyre illustration": the TYRES panel judges a wheel only by a current reading.
 * A held pressure (its own stamp older than 60 s, or the link down) is last-known: it raises no live LOW chip and takes no
 * part in the imbalance check; a held low pressure stays on screen as "WAS LOW" with its age. Read from the production
 * panel (`UnifiedChassisSection`) by what it says on screen. Pressures arrive through the real BCM reply path
 * (parser → ObdMerge), then the stamp is aged; the values are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi", application = OpenRSDashApp::class)
class TyrePanelFreshnessTest {

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

    /** Four replies through the parser; then each wheel's stamp is moved [ageMs] into the past (0 = just now). */
    private fun car(lf: Double, rf: Double = 38.0, lr: Double = 38.0, rr: Double = 38.0,
                    lfAgeMs: Long = 0L, othersAgeMs: Long = 0L, connected: Boolean = true): VehicleState {
        var vs = VehicleState(isConnected = true)
        vs = reply(vs, 0x2813, lf); vs = reply(vs, 0x2814, rf); vs = reply(vs, 0x2816, lr); vs = reply(vs, 0x2815, rr)
        assertTrue("the four pressures decoded: $vs", listOf(vs.tirePressLF, vs.tirePressRF, vs.tirePressLR, vs.tirePressRR).all { it > 0 })
        val t = now
        val stamps = vs.fieldLastUpdateMs + mapOf(
            "tirePressLF" to t - lfAgeMs, "tirePressRF" to t - othersAgeMs,
            "tirePressLR" to t - othersAgeMs, "tirePressRR" to t - othersAgeMs)
        return vs.copy(fieldLastUpdateMs = stamps, tpmsLastUpdate = t - minOf(lfAgeMs, othersAgeMs), isConnected = connected)
    }

    private fun idleFrames(n: Int) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    /** Every text the TYRES + AWD section shows for [state] (unmerged tree). */
    private fun said(prefs: UserPrefs = UserPrefs(), state: () -> VehicleState): List<String> {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val vs = androidx.compose.runtime.mutableStateOf(state())
        activity.setContent { Column(Modifier.fillMaxSize()) { UnifiedChassisSection(vs.value, prefs) } }
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

    private fun liveLow(said: List<String>) = said.filter { it.endsWith(" LOW") && !it.contains("WAS LOW") }
    private fun imbalance(said: List<String>) = said.filter { it.startsWith("PRESSURE IMBALANCE") }

    @Test fun `control - a current low pressure raises the live LOW chip`() {
        val s = said { car(lf = 25.0) }
        assertEquals(listOf("FRONT LEFT LOW"), liveLow(s))
        assertTrue("no stale wording for a current reading: $s", s.none { it.startsWith("STALE") || it.contains("WAS LOW") })
    }

    @Test fun `one stale low wheel beside three current ones - no live LOW chip, no imbalance, WAS LOW with its age`() {
        val s = said { car(lf = 25.0, lfAgeMs = 3 * 60_000L + 5_000L) }
        assertEquals("a held pressure is not a live alarm: $s", emptyList<String>(), liveLow(s))
        assertEquals("a held pressure is not compared with current ones: $s", emptyList<String>(), imbalance(s))
        assertTrue("the card keeps the fact with its age: $s", "WAS LOW 3m" in s)
        assertTrue("the panel keeps the fact with its age: $s", "FRONT LEFT WAS LOW · 3m AGO" in s)
        assertTrue("the last-known value stays on the card: $s", "25.0" in s)
    }

    @Test fun `one stale in-range wheel reads STALE with its age and raises nothing`() {
        val s = said { car(lf = 38.0, lfAgeMs = 2 * 60_000L + 1_000L) }
        assertTrue("$s", "STALE 2m" in s)
        assertEquals(emptyList<String>(), liveLow(s) + imbalance(s) + s.filter { it.contains("WAS LOW") })
    }

    @Test fun `whole-link loss - every wheel is last-known at once, a low one is WAS LOW, nothing is a live alarm`() {
        val s = said { car(lf = 25.0, lfAgeMs = 20_000L, othersAgeMs = 20_000L, connected = false) }
        assertEquals("$s", emptyList<String>(), liveLow(s) + imbalance(s))
        assertTrue("$s", "WAS LOW 20s" in s)
        assertEquals("the three in-range wheels read STALE with their age: $s", 3, s.count { it == "STALE 20s" })
        assertTrue("$s", "FRONT LEFT WAS LOW · 20s AGO" in s)
    }

    @Test fun `reconnect - a new reply makes the wheel current again`() {
        val held = car(lf = 25.0, lfAgeMs = 5 * 60_000L, othersAgeMs = 5 * 60_000L, connected = false)
        val back = reply(held.copy(isConnected = true), 0x2813, 24.0)        // the low wheel answers again, still low
        val s = said { back }
        assertEquals("$s", listOf("FRONT LEFT LOW"), liveLow(s))
        assertTrue("its card is live again: $s", s.none { it.startsWith("WAS LOW") })
        assertEquals("the three not yet re-read stay last-known: $s", 3, s.count { it == "STALE 5m" })
        val healed = reply(held.copy(isConnected = true), 0x2813, 38.0)      // or it answers in range: the old fact is gone
        val s2 = said { healed }
        assertEquals("$s2", emptyList<String>(), liveLow(s2) + s2.filter { it.contains("WAS LOW") })
    }

    @Test fun `BAR - the last-known value and the wording are the same rule in the other unit`() {
        val s = said(UserPrefs(tireUnit = "BAR")) { car(lf = 25.0, lfAgeMs = 3 * 60_000L + 5_000L) }
        assertTrue("25.0 psi shown as 1.72 bar: $s", "1.72" in s)
        assertTrue("$s", "WAS LOW 3m" in s && "FRONT LEFT WAS LOW · 3m AGO" in s)
        assertEquals("$s", emptyList<String>(), liveLow(s) + imbalance(s))
    }
}
