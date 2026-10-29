package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.BoostReading
import com.openrs.dash.data.BoostRule
import com.openrs.dash.data.BoostSession
import com.openrs.dash.data.BoostSource
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.PerformanceTimer
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * #207 — the boost source rule through the production paths: CAN frames through [LinkSession.onCanFrame] (decoder,
 * freshness stamps, peaks / evidence), TIP through the PCM parser and [ObdMerge] (what CanDataService.mergeObdState
 * does), the drive-peak rule the recorder uses, and the export builders. Payloads from the 2026-10-28 drives: 0x0F8
 * byte 5 = 0 while TIP 0x033E read 158.20 kPa (raw 4F1A) at baro 96.5 kPa (0x090 byte 2 = C1).
 *
 * Time: stamps are written with the real clock by the production code; the rule is then evaluated at explicit
 * later instants (`now + n`) — what a stalled link looks like to the 1 s UI clock.
 */
class BoostSourceTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val state = MutableStateFlow(VehicleState(isConnected = true))
    private val own = LinkOwnership()
    private lateinit var link: LinkSession
    private val events = mutableListOf<String>()

    private class NoRecorder : LinkSession.Recorder {
        override val isRecording = false; override val isPaused = false; override val activeDriveId: Long? = null
        override fun pausedDurationMs() = 0L
        override fun resumeDrive() = Unit; override fun stopDrive() = Unit; override fun startDrive(sessionId: Long) = Unit
    }

    @Before fun fresh() {
        CanDecoder.resetSessionState(); FuelEconomy.reset(); PerformanceTimer.resetSession()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        BoostSession.reset(); events.clear(); BoostSession.log = { events += it }
        link = LinkSession(own, own.begin(), state, NoRecorder(), onFrameCounted = {}, perfRunSink = { _: PerfRunEntity -> })
    }

    /** BoostSession is process-wide: leave it clean for the next test class (LinkSession tests feed it too). */
    @org.junit.After fun clean() { BoostSession.reset(); BoostSession.log = {} }

    /** 0x090: baro byte (C1 = 96.5 kPa), rpm bytes 4-5 (617D ≈ 3,100 rpm; 0000 = engine off). */
    private fun rpmFrame(baro: String = "C1", rpm: String = "617D") = link.onCanFrame(0x090, hex("0000${baro}FF${rpm}0000"), 50.0)
    private fun boostFrame(byte5: Int) = link.onCanFrame(0x0F8, hex("0042000000%02X1052".format(byte5)), 50.0)
    private fun throttle(pct: Int) = state.update { it.copy(throttlePct = pct.toDouble(), throttleHasSource = true) }
    /** A TIP decode merged like CanDataService.mergeObdState; the next CAN frame lets the session see it. */
    private fun tip(raw: Int) {
        Thread.sleep(3)                                                  // one decode per stamp (real ones are ~1.2 s apart)
        ObdResponseParser.parsePcmResponse(hex("0562033E%04X".format(raw)), state.value) { obd -> state.update { ObdMerge.merge(it, obd) } }
    }
    private fun now() = System.currentTimeMillis()
    private fun read(atMs: Long = now()) = BoostSession.read(state.value, atMs)
    private fun waitForStamp() = Thread.sleep(CanDecoder.CAN_STAMP_THROTTLE_MS + 20)

    @Test fun `never received — baro alone gives no boost reading and no peak`() {
        rpmFrame()                                                       // baro 96.5 arrives, 0x0F8 never does
        assertEquals(BoostReading.NONE, read())                          // was +0.70 psi (default 101.325 − 96.5)
        assertTrue(BoostSession.peaks.value.isEmpty())
    }

    @Test fun `CAN byte 5 is gauge — independent of when baro arrives or changes`() {
        boostFrame(21)                                                   // before any 0x090: no baro yet
        assertEquals(21.0, read().gaugeKpa!!, 1e-9)                      // was 21 + 101.325 − 96.5 once baro came
        rpmFrame(baro = "C1")
        assertEquals(21.0, read().gaugeKpa!!, 1e-9)
        rpmFrame(baro = "B4")                                            // baro 90 kPa later (altitude)
        assertEquals(BoostSource.CAN_0F8, read().source)
        assertEquals(21.0, read().gaugeKpa!!, 1e-9)
    }

    @Test fun `stale CAN — no reading once 0x0F8 stops, recovers on the next frame`() {
        rpmFrame(); boostFrame(5)
        val t0 = now()
        assertEquals(BoostSource.CAN_0F8, read(t0).source)
        assertEquals(BoostReading.NONE, read(t0 + BoostRule.CAN_FRESH_MS + 1_000))
        waitForStamp(); boostFrame(6)
        assertEquals(6.0, read().gaugeKpa!!, 1e-9)
    }

    @Test fun `stalled traffic while connected — TIP expires on the wall clock, not lastUpdate`() {
        rpmFrame(); tip(0x4F1A)                                          // no 0x0F8 on this link: TIP is the source
        val t0 = now()
        assertEquals(BoostSource.TIP_033E, read(t0).source)
        assertEquals(158.203 - 96.5, read(t0).gaugeKpa!!, 0.01)
        assertTrue(state.value.isConnected)
        // Nothing arrives for 3 s (adapter stalled, socket still open): before, lastUpdate froze with the TIP stamp
        // and TIP stayed "fresh" indefinitely
        assertEquals(BoostReading.NONE, read(t0 + 3_000))
    }

    @Test fun `TIP needs measured baro, a running engine and a fresh decode`() {
        link.onCanFrame(0x090, hex("000000FF617D0000"), 50.0)          // baro byte 0 → not measured
        tip(0x4F1A)
        assertEquals("no invented reference", BoostReading.NONE, read())
        rpmFrame(baro = "C1"); tip(0x4F1A)
        assertEquals(BoostSource.TIP_033E, read().source)
        rpmFrame(rpm = "0000")                                           // engine off, TIP still within 2.5 s
        assertEquals(BoostReading.NONE, read())
        rpmFrame(); tip(0x4F1A)                                          // engine back, fresh decode → recovered
        assertEquals(BoostSource.TIP_033E, read().source)
    }

    @Test fun `engine off with CAN tracking — CAN stays the source, TIP never takes over`() {
        rpmFrame(); boostFrame(0); tip(0x4F1A)
        assertEquals(BoostSource.CAN_0F8, read().source)                 // CAN fresh and not shown wrong: CAN
        rpmFrame(rpm = "0000")
        assertEquals(BoostSource.CAN_0F8, read().source)
        assertEquals(0.0, read().gaugeKpa!!, 1e-9)
    }

    @Test fun `conflict at wide-open throttle — CAN 0 while TIP shows boost — switches to TIP, labelled, logged`() {
        rpmFrame(); boostFrame(0); throttle(95)
        tip(0x4F1A); rpmFrame()                                          // vote 1 (CAN 0, TIP +61.7 kPa)
        assertEquals(BoostSource.CAN_0F8, read().source)
        assertTrue(BoostSession.canTracking)
        tip(0x4F00); rpmFrame()                                          // vote 2
        assertFalse(BoostSession.canTracking)
        assertEquals(BoostSource.TIP_033E, read().source)
        assertEquals("TIP", read().source.label)
        assertEquals(1, events.size)
        assertTrue(events.single(), events.single().contains("not tracking"))
    }

    @Test fun `CAN that follows TIP under load keeps CAN, and part throttle is no evidence`() {
        rpmFrame(); throttle(30); boostFrame(0)
        repeat(3) { tip(0x4F1A + it); rpmFrame() }                       // TIP high at part throttle: upstream of the plate
        assertTrue(BoostSession.canTracking)
        waitForStamp(); boostFrame(55); throttle(95)
        repeat(3) { tip(0x4F2A + it); rpmFrame() }                       // WOT, CAN tracks
        assertTrue(BoostSession.canTracking)
        assertEquals(BoostSource.CAN_0F8, read().source)
    }

    @Test fun `peaks are per source — a caption never shows another source's peak`() {
        rpmFrame(); boostFrame(12)                                       // CAN peak 12 kPa
        throttle(95)
        waitForStamp(); boostFrame(0)
        tip(0x4F1A); rpmFrame(); tip(0x4F00); rpmFrame()                 // → TIP source
        val peaks = BoostSession.peaks.value
        assertEquals(12.0, peaks[BoostSource.CAN_0F8]!!, 1e-9)
        assertEquals(158.203 - 96.5, peaks[BoostSource.TIP_033E]!!, 0.01)
        val prefs = com.openrs.dash.ui.UserPrefs(boostUnit = "BAR")
        assertEquals("PEAK ${"%.2f".format((158.203 - 96.5) * 0.14503773 * 0.0689476)}", com.openrs.dash.ui.boostPeakCaption(read(), prefs))
        assertNull(com.openrs.dash.ui.boostPeakCaption(BoostReading.NONE, prefs))
    }

    @Test fun `drive peak keeps the source of the reading that set it — NONE never sets one`() {
        val can = BoostReading(BoostSource.CAN_0F8, 10.0); val tipR = BoostReading(BoostSource.TIP_033E, 60.0)
        assertNull(BoostRule.nextDrivePeak(0.0, null, BoostReading.NONE))
        val p1 = BoostRule.nextDrivePeak(0.0, null, can)!!
        assertEquals(BoostSource.CAN_0F8, p1.second)
        val p2 = BoostRule.nextDrivePeak(p1.first, p1.second, tipR)!!
        assertEquals(BoostSource.TIP_033E, p2.second)
        assertNull(BoostRule.nextDrivePeak(p2.first, p2.second, can))
    }

    // ── Exports: provenance only where recorded ──

    private fun drive(policy: String?, peakSrc: String?) = DriveEntity(id = 1, startTime = 0, endTime = 60_000,
        peakBoostPsi = 8.9, peakBoostSource = peakSrc, boostPolicy = policy)
    private fun pt(t: Long, psi: Double, src: String?, baro: Double? = null) =
        DrivePointEntity(driveId = 1, timestamp = t, lat = 0.0, lng = 0.0, boostPsi = psi, boostSource = src, baroKpa = baro)

    @Test fun `an old drive's export claims no source or policy, values as recorded`() {
        val old = drive(null, null); val pts = listOf(pt(0, -0.63, null), pt(1_000, 0.0, null))
        val summary = DriveExportBuilder.buildSummary(old, pts)
        assertTrue(summary, summary.contains("Boost source: not recorded"))
        assertFalse(summary.contains("CAN 0x0F8 while fresh"))
        val json = JSONObject(DriveExportBuilder.buildProfileJson(old, pts))
        assertFalse(json.getJSONObject("drive").has("peakBoostSource"))
        assertFalse(json.getJSONObject("drive").has("boostPolicy"))
        assertFalse(json.has("boostSources"))
        val row = DriveExportBuilder.buildCsv(pts).lines()[2].split(",")
        assertEquals("-0.630", row[6])                                    // as recorded, never rewritten
        assertEquals("", row[row.size - 2]); assertEquals("", row.last())
    }

    @Test fun `a new drive with mixed sources exports each point's source, gaps for NONE`() {
        val d = drive(BoostRule.POLICY, "TIP_033E")
        val pts = listOf(pt(0, 0.0, "CAN_0F8", 96.5), pt(1_000, 0.0, "NONE", 96.5), pt(2_000, 8.9, "TIP_033E", 96.5))
        val csv = DriveExportBuilder.buildCsv(pts).lines()
        assertTrue(csv[1].endsWith("boost_source,baro_kpa"))
        assertEquals(listOf("CAN_0F8", "NONE", "TIP_033E"), csv.subList(2, 5).map { it.split(",").let { c -> c[c.size - 2] } })
        assertEquals("", csv[3].split(",")[6])                            // no source → no boost value
        val json = JSONObject(DriveExportBuilder.buildProfileJson(d, pts))
        assertEquals("TIP_033E", json.getJSONObject("drive").getString("peakBoostSource"))
        assertEquals(BoostRule.POLICY, json.getJSONObject("drive").getString("boostPolicy"))
        assertEquals(1, json.getJSONObject("boostSources").getInt("NONE"))
        assertTrue(DriveExportBuilder.buildSummary(d, pts).contains("(TIP 0x033E − baro)"))
    }

    @Test fun `by-gear TIP peak uses the recorded baro, else says standard atmosphere`() {
        fun loaded(baro: Double?) = DrivePointEntity(driveId = 1, timestamp = 0, lat = 0.0, lng = 0.0, gear = "3",
            tipActualKpa = 160.0, boostTargetKpa = 165.0, wgdcPct = 80.0, baroKpa = baro)
        val withBaro = com.openrs.dash.data.BoostSweepStats.from(listOf(loaded(96.5)))!!.byGear.single()
        assertTrue(withBaro.baroMeasured)
        assertEquals((160.0 - 96.5) * 0.14503773, withBaro.peakBoostPsi, 1e-6)
        val old = com.openrs.dash.data.BoostSweepStats.from(listOf(loaded(null)))!!.byGear.single()
        assertFalse(old.baroMeasured)
        assertEquals((160.0 - 101.325) * 0.14503773, old.peakBoostPsi, 1e-6)
    }

    @Test fun `Custom Dashboard TIP cells use the TIP gauge's reference, absolute only when labelled`() {
        rpmFrame(); tip(0x4F1A)
        val prefs = com.openrs.dash.ui.UserPrefs()
        val cell = com.openrs.dash.data.DashCell("tipActualKpa", "number", "TIP ACTUAL")
        assertEquals("8.9 PSI", com.openrs.dash.data.resolveValue(state.value, prefs, cell).first)   // was "158"
        state.update { it.copy(barometricPressure = 0.0) }
        assertEquals("158 kPa abs", com.openrs.dash.data.resolveValue(state.value, prefs, cell).first)
    }

    @Suppress("unused") private val keep = AtomicInteger()
}
