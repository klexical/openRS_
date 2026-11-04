package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.BoostRule
import com.openrs.dash.data.BoostSession
import com.openrs.dash.data.BoostSource
import com.openrs.dash.data.DashCell
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.PerformanceTimer
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.defaultCellFor
import com.openrs.dash.data.resolveValue
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import com.openrs.dash.ui.GaugeName
import com.openrs.dash.ui.LiveBoostLabel
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.dashCellEditorLabel
import com.openrs.dash.ui.dashCellLabel
import com.openrs.dash.ui.dashCellQualifier
import com.openrs.dash.ui.liveGaugeName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * #207 — the live gauge's name (BOOST · THROTTLE INLET) on the consumer paths, with the reading itself unchanged: CAN
 * frames through [LinkSession.onCanFrame], TIP through the PCM parser and [ObdMerge], then what DRIVE / TRIP / HUD /
 * Custom Dashboard read ([BoostSession.gauge], [resolveValue], the HUD format). Payloads as in BoostSourceTest: TIP
 * 0x033E raw 4F1A = 158.20 kPa at baro 96.5 kPa (0x090 byte 2 = C1) → 61.7 kPa ≈ 8.9 psi.
 */
class LiveBoostLabelTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val state = MutableStateFlow(VehicleState(isConnected = true))
    private val own = LinkOwnership()
    private lateinit var link: LinkSession
    private val prefs = UserPrefs()                                      // PSI
    private val live = GaugeName(LiveBoostLabel.TITLE, LiveBoostLabel.QUALIFIER)

    private class NoRecorder : LinkSession.Recorder {
        override val isRecording = false; override val isPaused = false; override val activeDriveId: Long? = null
        override fun pausedDurationMs() = 0L
        override fun resumeDrive() = Unit; override fun stopDrive() = Unit; override fun startDrive(sessionId: Long) = Unit
    }

    @Before fun fresh() {
        CanDecoder.resetSessionState(); FuelEconomy.reset(); PerformanceTimer.resetSession()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        BoostSession.reset(); BoostSession.log = {}
        link = LinkSession(own, own.begin(), state, NoRecorder(), onFrameCounted = {}, perfRunSink = { _: PerfRunEntity -> })
    }

    @After fun clean() { BoostSession.reset(); BoostSession.log = {} }

    private fun rpmFrame(rpm: String = "617D") = link.onCanFrame(0x090, hex("0000C1FF${rpm}0000"), 50.0)
    private fun boostFrame(byte5: Int) = link.onCanFrame(0x0F8, hex("0042000000%02X1052".format(byte5)), 50.0)
    private fun tip(raw: Int) {
        Thread.sleep(3)
        ObdResponseParser.parsePcmResponse(hex("0562033E%04X".format(raw)), state.value) { obd -> state.update { ObdMerge.merge(it, obd) } }
    }
    private fun now() = System.currentTimeMillis()
    private fun gauge(at: Long = now()) = BoostSession.gauge(state.value, at)
    private fun shown(at: Long = now()) = gauge(at).gaugeKpa?.let { prefs.displayGauge(it).first }

    @Test fun `normal, stale and recovered — the caption stays BOOST · THROTTLE INLET, the value follows TIP alone`() {
        rpmFrame(); boostFrame(0); tip(0x4F1A)
        assertEquals(live, liveGaugeName(gauge()))
        assertEquals(158.203 - 96.5, gauge().gaugeKpa!!, 0.01)
        assertEquals("8.9", shown())
        val gap = now() + BoostRule.TIP_FRESH_MS + 100                   // TIP poll gap, CAN 0x0F8 still arriving
        rpmFrame(); boostFrame(0)
        assertNull("stale → \"—\", never CAN 0x0F8 in its place", shown(gap))
        assertEquals("the caption doesn't flip with freshness", live, liveGaugeName(gauge(gap)))
        rpmFrame(); tip(0x4F1A)                                          // recovered
        assertEquals("8.9", shown())
        assertEquals(live, liveGaugeName(gauge()))
    }

    @Test fun `a CAN 0x0F8 reading never gets the throttle-inlet name — provenance keeps TIP`() {
        rpmFrame(); boostFrame(0); tip(0x4F1A)
        val tipRead = BoostSession.read(state.value, now())
        assertEquals(BoostSource.TIP_033E, tipRead.source)
        assertEquals("recorded / exported name unchanged", "TIP", tipRead.source.label)
        rpmFrame(rpm = "0000")                                           // engine off: the recorder reads CAN 0x0F8
        val canRead = BoostSession.read(state.value, now())
        assertEquals(BoostSource.CAN_0F8, canRead.source)
        assertEquals(0.0, canRead.gaugeKpa!!, 1e-9)                      // a genuine CAN 0 is still a reading
        assertEquals(GaugeName("BOOST", null), liveGaugeName(canRead))
        assertNull("the live gauge shows no CAN value", gauge().gaugeKpa)
    }

    @Test fun `Custom Dashboard — saved TIP, older BOOST and new cells all read as the live gauge, values unchanged`() {
        rpmFrame(); tip(0x4F1A)
        val saved = listOf(DashCell("boostKpa", "hero", "TIP"), DashCell("boostKpa", "number", "BOOST"),
            defaultCellFor("boostKpa")!!)
        for (cell in saved) {
            assertEquals(LiveBoostLabel.TITLE, dashCellLabel(cell))
            assertEquals(LiveBoostLabel.QUALIFIER, dashCellQualifier(cell))
            assertEquals("BOOST · THROTTLE INLET", dashCellEditorLabel(cell))
            assertEquals("8.9", resolveValue(state.value, prefs, cell, now()).first)
        }
        assertEquals("— —", resolveValue(state.value, prefs, saved[0], now() + BoostRule.TIP_FRESH_MS + 100).first)
        val tipActual = DashCell("tipActualKpa", "number", "TIP ACTUAL")    // technical cell keeps its name
        assertEquals("TIP ACTUAL", dashCellLabel(tipActual)); assertNull(dashCellQualifier(tipActual))
        assertEquals("TIP ACTUAL", dashCellEditorLabel(tipActual))
    }

    @Test fun `a genuine 0 at the throttle inlet stays 0, not unavailable`() {
        rpmFrame(); tip(0x3040)                                          // 96.5 kPa abs = measured baro
        assertEquals(0.0, gauge().gaugeKpa!!, 1e-9)
        assertEquals(prefs.displayGauge(0.0).first, resolveValue(state.value, prefs, DashCell("boostKpa", "hero", "TIP"), now()).first)
        assertEquals(live, liveGaugeName(gauge()))
    }

    @Test fun `HUD line reads BOOST with the gauge's value and unit`() {
        val res = File("src/main/res/values/strings.xml").readText()
        val fmt = Regex("""<string name="hud_boost">([^<]*)</string>""").find(res)!!.groupValues[1]
        assertEquals("BOOST: %1\$s %2\$s", fmt)
        rpmFrame(); tip(0x4F1A)
        val (v, u) = prefs.displayGauge(gauge().gaugeKpa!!)
        assertEquals("BOOST: 8.9 PSI", String.format(fmt, v, u))
    }
}
