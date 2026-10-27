package com.openrs.dash

import com.openrs.dash.data.DriveThermalRecord
import com.openrs.dash.data.DriveThermalReview
import com.openrs.dash.data.DriveThermalReview.Basis
import com.openrs.dash.data.DriveThermalReview.PeakLevel
import com.openrs.dash.data.DriveThermalSensor
import com.openrs.dash.data.DriveThermalSensor.COOLANT
import com.openrs.dash.data.DriveThermalSensor.OIL
import com.openrs.dash.data.DriveThermalSensor.PTU
import com.openrs.dash.data.DriveThermalSensor.RDU
import com.openrs.dash.data.DriveThermalTracker
import com.openrs.dash.data.ThermalSegment
import com.openrs.dash.ui.Level
import com.openrs.dash.ui.TempAvail
import com.openrs.dash.ui.ThermalCell
import com.openrs.dash.ui.ThermalLimits
import com.openrs.dash.ui.ThermalProfile
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.UserPrefsStore
import com.openrs.dash.ui.driveLimits
import com.openrs.dash.ui.lim
import com.openrs.dash.ui.sample
import com.openrs.dash.ui.tVs
import com.openrs.dash.data.VehicleState
import org.junit.After
import com.openrs.dash.ui.tempLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drive thermal record: the recorder judges peaks against the profile in effect, saves those limits
 * with the drive, and the TRIP TEMP badge and summary read them back — never the current profile.
 */
class DriveThermalRecordTest {

    private val t0 = 1_700_000_000_000L

    /** What DriveRecorder does per sample: the effective profile from prefs, then the four readings. */
    private fun DriveThermalTracker.sample(prefs: UserPrefs, s: Int, oil: Double = -99.0, coolant: Double = -99.0,
                                           ptu: Double = -99.0, rdu: Double = -99.0) {
        val limits = ThermalLimits.from(prefs)
        sample(t0 + s * 1000L, limits.preset, limits.profile.driveLimits(),
            mapOf(OIL to oil, COOLANT to coolant, PTU to ptu, RDU to rdu))
    }

    private fun recorded(prefs: UserPrefs, vararg oil: Double): String =
        DriveThermalTracker().apply { oil.forEachIndexed { i, t -> sample(prefs, i, oil = t) } }.toJson()!!

    private val street = UserPrefs(tempPreset = "street")
    private val race = UserPrefs(tempPreset = "race")

    @Test
    fun `recorder uses the effective profile limits - built-ins and Custom`() {
        for ((prefs, p) in listOf(street to ThermalProfile.STREET, UserPrefs(tempPreset = "track") to ThermalProfile.TRACK,
                                  race to ThermalProfile.RACE)) {
            val seg = DriveThermalRecord.decode(recorded(prefs, 100.0))!!.single()
            assertEquals(prefs.tempPreset, seg.profile)
            assertEquals(p.oilCritC, seg.limits.getValue(OIL).critC, 0.0)
            assertEquals(p.coolWarnC, seg.limits.getValue(COOLANT).warnC, 0.0)
            assertEquals(p.ptuCritC, seg.limits.getValue(PTU).critC, 0.0)
            assertEquals(p.rduWarnC, seg.limits.getValue(RDU).warnC, 0.0)
        }
        val custom = ThermalProfile.STREET.copy(oilWarnC = 112.0, oilCritC = 124.0, rduCritC = 97.0)
        val seg = DriveThermalRecord.decode(recorded(UserPrefs(tempPreset = "custom", customThermal = custom), 100.0))!!.single()
        assertEquals("custom", seg.profile)
        assertEquals(124.0, seg.limits.getValue(OIL).critC, 0.0)
        assertEquals(97.0, seg.limits.getValue(RDU).critC, 0.0)
        // Custom selected but never saved → the app runs STREET, and the record says so
        assertEquals("street", DriveThermalRecord.decode(recorded(UserPrefs(tempPreset = "custom"), 100.0))!!.single().profile)
    }

    @Test
    fun `95 percent of critical boundary, and levels at warn and crit`() {
        // STREET oil: warn 120, crit 130 → flag at 123.5 °C
        fun review(peak: Double) = DriveThermalReview.from(recorded(street, 90.0, peak)).sensor(OIL)!!
        assertFalse(review(123.49).flagged)
        assertTrue(review(123.5).flagged)
        assertEquals(PeakLevel.NORMAL, review(119.9).peakLevel)
        assertEquals(PeakLevel.WARN, review(120.0).peakLevel)
        assertEquals(PeakLevel.WARN, review(129.9).peakLevel)
        assertEquals(PeakLevel.CRIT, review(130.0).peakLevel)
        assertEquals(123.5, review(123.5).flagReadingC!!, 0.0)
        assertEquals(130.0, review(123.5).flagCritC!!, 0.0)
    }

    @Test
    fun `peak is the highest recorded sample - sentinels ignored`() {
        val r = DriveThermalReview.from(DriveThermalTracker().apply {
            sample(street, 0, oil = 95.0, coolant = -99.0)
            sample(street, 1, oil = 121.0, coolant = 96.0)
            sample(street, 2, oil = -99.0, coolant = 99.0)      // sentinel: ignored, not a 0 °C reading
            sample(street, 3, oil = 110.0, coolant = 97.0)
        }.toJson())
        assertEquals(121.0, r.sensor(OIL)!!.peakC, 0.0)
        assertEquals(99.0, r.sensor(COOLANT)!!.peakC, 0.0)
        assertNull(r.sensor(RDU))                              // never reported → no row, no flag
    }

    @Test
    fun `custom values flag at their own limit, not the built-in one`() {
        val custom = UserPrefs(tempPreset = "custom", customThermal = ThermalProfile.STREET.copy(oilWarnC = 112.0, oilCritC = 124.0))
        val json = recorded(custom, 118.0)                     // 95 % of 124 = 117.8
        assertTrue(DriveThermalReview.from(json).flagged)
        assertEquals(PeakLevel.WARN, DriveThermalReview.from(json).sensor(OIL)!!.peakLevel)
        assertFalse(DriveThermalReview.from(recorded(street, 118.0)).flagged)   // 118 under STREET: normal, no flag
    }

    @Test
    fun `Celsius and Fahrenheit entry give the same record, and the 95 percent rule stays in Celsius`() {
        // Custom oil limits typed in °F (248 / 266 °F) and in °C (120 / 130 °C) save the same °C values
        val warnF = ThermalCell.of(0.0, "F").copy(text = "248").resolvedC!!
        val critF = ThermalCell.of(0.0, "F").copy(text = "266").resolvedC!!
        val typedF = UserPrefs(tempPreset = "custom", customThermal = ThermalProfile.STREET.copy(oilWarnC = warnF, oilCritC = critF))
        val typedC = UserPrefs(tempPreset = "custom", customThermal = ThermalProfile.STREET.copy(oilWarnC = 120.0, oilCritC = 130.0))
        assertEquals(recorded(typedC, 123.0), recorded(typedF, 123.0))
        // 123 °C = 253.4 °F: ≥ 95 % of 266 °F (252.7 °F) but below 95 % of 130 °C (123.5 °C). The review has no
        // display unit, so a °F user sees the same verdict as a °C user — evaluated in °C.
        assertFalse(DriveThermalReview.from(recorded(typedF, 123.0)).flagged)
        assertTrue(DriveThermalReview.from(recorded(typedF, 123.5)).flagged)
    }

    @Test
    fun `editing the profile after the drive changes nothing about it`() {
        val custom = ThermalProfile.STREET.copy(oilWarnC = 112.0, oilCritC = 124.0)
        val saved = recorded(UserPrefs(tempPreset = "custom", customThermal = custom), 119.0)
        assertTrue(DriveThermalReview.from(saved).flagged)
        // Later: Custom loosened to 135 °C, or the user goes back to STREET. Judged by the current profile
        // (what the summary used to do) the drive would now read normal…
        for (later in listOf(UserPrefs(tempPreset = "custom", customThermal = custom.copy(oilWarnC = 125.0, oilCritC = 135.0)), street)) {
            assertEquals(Level.NORMAL, tempLevel(119.0, later.oilWarnC, later.oilCritC, TempAvail.AVAILABLE))
            assertFalse(119.0 >= later.oilCritC * 0.95)
        }
        // …but the review reads only the saved record, so the drive keeps its WARN level and TEMP flag
        val after = DriveThermalReview.from(saved)
        assertEquals(PeakLevel.WARN, after.sensor(OIL)!!.peakLevel)
        assertTrue(after.flagged)
        assertEquals(124.0, after.sensor(OIL)!!.flagCritC!!, 0.0)
        assertEquals(listOf("custom"), after.profiles)
    }

    @Test
    fun `a profile change during recording starts a segment - each part judged by its own profile`() {
        val tracker = DriveThermalTracker().apply {
            sample(street, 0, oil = 100.0)
            sample(street, 1, oil = 118.0)                    // STREET: normal, no flag (95 % = 123.5)
            sample(race, 2, oil = 104.0)                      // RACE from here: warn 100, flag at 104.5
            sample(race, 3, oil = 106.0)
        }
        val segs = tracker.segmentList
        assertEquals(listOf("street", "race"), segs.map { it.profile })
        assertEquals(118.0, segs[0].peaks.getValue(OIL), 0.0)
        assertEquals(106.0, segs[1].peaks.getValue(OIL), 0.0)
        val r = DriveThermalReview.from(tracker.toJson())
        assertTrue(r.profileChanged)
        assertEquals(118.0, r.sensor(OIL)!!.peakC, 0.0)       // drive peak (shown in the summary)
        assertEquals(PeakLevel.NORMAL, r.sensor(OIL)!!.peakLevel)   // 118 under its own STREET limits — not RACE's
        assertTrue(r.sensor(OIL)!!.flagged)
        assertEquals("race", r.sensor(OIL)!!.flagProfile)
        assertEquals(106.0, r.sensor(OIL)!!.flagReadingC!!, 0.0)
        // The earlier 118 °C is never re-judged against RACE (that would be CRIT); RACE → STREET can't hide the flag
        val back = DriveThermalTracker().apply { sample(race, 0, oil = 106.0); sample(street, 1, oil = 107.0) }
        assertTrue(DriveThermalReview.from(back.toJson()).flagged)
        // A saved Custom edit mid-drive is a change too (same id, new limits)
        val c1 = UserPrefs(tempPreset = "custom", customThermal = ThermalProfile.STREET.copy(oilCritC = 124.0))
        val c2 = UserPrefs(tempPreset = "custom", customThermal = ThermalProfile.STREET.copy(oilCritC = 135.0))
        val edited = DriveThermalTracker().apply { sample(c1, 0, oil = 100.0); sample(c2, 1, oil = 125.0); sample(c2, 2, oil = 126.0) }
        assertEquals(listOf("custom", "custom"), edited.segmentList.map { it.profile })
        assertFalse(DriveThermalReview.from(edited.toJson()).flagged)   // 126 < 95 % of 135 = 128.25
    }

    @Test
    fun `legacy records keep their original rule and claim no profile`() {
        // Exact shape of the pre-profile recorder's snapshot (fixed 140 / 115 / 120 / 120 °C, drive-end reading)
        val legacy = """[{"sensor":"Oil","peakC":134.0,"climbRateCPerMin":0.4,"critThresholdC":140.0,"timeToCriticalMin":15.0},""" +
            """{"sensor":"RDU","peakC":110.0,"climbRateCPerMin":0.0,"critThresholdC":120.0}]"""
        val r = DriveThermalReview.from(legacy)
        assertEquals(Basis.LEGACY, r.basis)
        assertTrue(r.profiles.isEmpty())
        assertTrue(r.sensor(OIL)!!.flagged)                    // 134 ≥ 133 (95 % of the stored 140)
        assertFalse(r.sensor(RDU)!!.flagged)                   // 110 < 114
        assertNull(r.sensor(OIL)!!.peakLevel)                      // limits unknown → no colours
        assertEquals(140.0, r.sensor(OIL)!!.flagCritC!!, 0.0)
        assertFalse(DriveThermalReview.from("""[{"sensor":"Oil","peakC":132.9,"critThresholdC":140.0}]""").flagged)

        for (none in listOf(null, "", "not json", """{"v":99}""")) {
            val n = DriveThermalReview.from(none)
            assertEquals(Basis.NONE, n.basis)
            assertFalse(n.flagged)
        }
    }

    @Test
    fun `record round-trips exactly`() {
        val tracker = DriveThermalTracker().apply {
            sample(street, 0, oil = 101.5, coolant = 92.25, ptu = 70.0, rdu = 61.0)
            sample(race, 1, oil = 103.0)
        }
        val json = tracker.toJson(mapOf(OIL to 0.8))!!
        assertEquals(tracker.segmentList, DriveThermalRecord.decode(json))
        assertNull(DriveThermalTracker().toJson())             // no sample → no record
        assertEquals(DriveThermalSensor.entries.map { it.key }, listOf("Oil", "Coolant", "PTU", "RDU"))
    }

    // ── Round 26 follow-ups ────────────────────────────────────────────────

    @After fun resetPrefs() = UserPrefsStore.resetForTest()

    /** A connected frame with each reading stamped at the given second (null = never stamped). */
    private fun frame(oil: Double = -99.0, oilAt: Int? = null, rdu: Double = -99.0, rduAt: Int? = null) = VehicleState(
        isConnected = true, oilTempC = oil, rduTempC = rdu,
        fieldLastUpdateMs = buildMap {
            oilAt?.let { put("oilTempC", t0 + it * 1000L) }
            rduAt?.let { put("rduTempC", t0 + it * 1000L) }
        })

    @Test
    fun `flag threshold is 95 percent of the saved critical in Celsius, then converted for display`() {
        val r = DriveThermalReview.from(DriveThermalTracker().apply { sample(race, 0, oil = 105.0) }.toJson()).sensor(OIL)!!
        assertEquals(104.5, r.flagThresholdC!!, 1e-9)                 // 110 °C × 0.95
        val f = UserPrefs(tempUnit = "F")
        assertEquals("221°F", f.tVs(r.flagReadingC!!, r.flagThresholdC!!))
        assertEquals("220.1°F", f.lim(r.flagThresholdC!!))            // 104.5 °C — not 95 % of 230 °F (218.5 °F)
        assertEquals("104.5°C", UserPrefs(tempUnit = "C").lim(r.flagThresholdC!!))
        // A reading exactly on the threshold never reads as below it after rounding
        assertEquals("220.1°F", f.tVs(104.5, 104.5))
        // Legacy: the old fixed 140 °C → 133 °C threshold
        val legacy = DriveThermalReview.from("""[{"sensor":"Oil","peakC":134.0,"critThresholdC":140.0}]""").sensor(OIL)!!
        assertEquals("271.4°F", f.lim(legacy.flagThresholdC!!))
        assertEquals("273°F", f.tVs(legacy.flagReadingC!!, legacy.flagThresholdC!!))
    }

    @Test
    fun `a stale reading is not carried into a new, stricter segment`() {
        // STREET RDU: warn 90, crit 100 (flag 95). RACE RDU: crit 85 (flag 80.75). RDU goes stale after 90 s.
        val tracker = DriveThermalTracker().apply {
            sample(frame(rdu = 92.0, rduAt = 0), street, t0)
            sample(frame(rdu = 92.0, rduAt = 0), street, t0 + 60_000)
            sample(frame(rdu = 92.0, rduAt = 0), race, t0 + 100_000)     // RDU polls stopped: 100 s old → stale
            sample(frame(rdu = 92.0, rduAt = 0), race, t0 + 130_000)
        }
        val segs = tracker.segmentList
        assertEquals(listOf("street", "race"), segs.map { it.profile })
        assertEquals(92.0, segs[0].peaks.getValue(RDU), 0.0)           // observed while fresh: kept
        assertNull(segs[1].peaks[RDU])                                 // never measured under RACE
        val r = DriveThermalReview.from(tracker.toJson())
        assertFalse(r.flagged)                                         // no flag from a held value
        assertEquals(92.0, r.sensor(RDU)!!.peakC, 0.0)
        assertEquals(PeakLevel.WARN, r.sensor(RDU)!!.peakLevel)
    }

    @Test
    fun `a still-fresh reading counts in the new segment at once, and earlier flags survive staleness`() {
        // Switch to RACE 60 s after the last RDU poll: still inside its 90 s window — it is current, no extra poll needed
        val fresh = DriveThermalTracker().apply {
            sample(frame(rdu = 92.0, rduAt = 0), street, t0)
            sample(frame(rdu = 92.0, rduAt = 0), race, t0 + 60_000)
        }
        val r = DriveThermalReview.from(fresh.toJson())
        assertTrue(r.flagged)
        assertEquals("race", r.sensor(RDU)!!.flagProfile)
        // Oil flagged under STREET, then CAN oil goes stale (60 s) and the profile changes: the flag stays
        val kept = DriveThermalTracker().apply {
            sample(frame(oil = 125.0, oilAt = 0), street, t0)                  // ≥ 123.5: flag
            sample(frame(oil = 125.0, oilAt = 0), UserPrefs(tempPreset = "track"), t0 + 70_000)   // stale → not counted
        }
        val k = DriveThermalReview.from(kept.toJson()).sensor(OIL)!!
        assertTrue(k.flagged)
        assertEquals("street", k.flagProfile)
        assertNull(kept.segmentList[1].peaks[OIL])
        // A reading never stamped is not current either
        assertTrue(DriveThermalTracker().apply { sample(frame(oil = 130.0), street, t0) }.segmentList.single().peaks.isEmpty())
    }

    @Test
    fun `highest temperature and flag-causing reading can differ`() {
        // 122 °C under STREET (WARN, below its 123.5 flag), later 106 °C under RACE (≥ 104.5: flag)
        val tracker = DriveThermalTracker().apply {
            sample(frame(oil = 122.0, oilAt = 0), street, t0)
            sample(frame(oil = 106.0, oilAt = 30), race, t0 + 30_000)
        }
        val oil = DriveThermalReview.from(tracker.toJson()).sensor(OIL)!!
        assertEquals(122.0, oil.peakC, 0.0)                            // summary keeps the true peak
        assertEquals(PeakLevel.WARN, oil.peakLevel)                    // 122 by STREET — RACE would call it CRIT
        assertTrue(oil.flagged)
        assertEquals(106.0, oil.flagReadingC!!, 0.0)                   // the flag names the RACE reading…
        assertEquals(110.0, oil.flagCritC!!, 0.0)                      // …and RACE's saved critical
        assertEquals("race", oil.flagProfile)
        // Same value peaking in both segments: the worse of its own levels
        val both = DriveThermalTracker().apply {
            sample(frame(oil = 111.0, oilAt = 0), street, t0); sample(frame(oil = 111.0, oilAt = 5), race, t0 + 5_000)
        }
        assertEquals(PeakLevel.CRIT, DriveThermalReview.from(both.toJson()).sensor(OIL)!!.peakLevel)
    }

    @Test
    fun `service-only start loads the saved profile before anything is recorded`() {
        val custom = ThermalProfile.STREET.copy(oilWarnC = 112.0, oilCritC = 124.0)
        val saved = UserPrefs(tempPreset = "custom", customThermal = custom)
        UserPrefsStore.resetForTest()
        // Process recreated by a sticky service restart, no activity: the store still holds defaults
        assertEquals("street", ThermalLimits.from(UserPrefsStore.prefs.value).preset)
        assertTrue(UserPrefsStore.ensureLoaded { saved })              // what CanDataService.onCreate does first
        val seg = DriveThermalTracker().apply { sample(frame(oil = 118.0, oilAt = 0), UserPrefsStore.prefs.value, t0) }
            .segmentList.single()
        assertEquals("custom", seg.profile)
        assertEquals(124.0, seg.limits.getValue(OIL).critC, 0.0)
        // Already loaded (activity first, or a second service start): never replaced by a re-read
        assertFalse(UserPrefsStore.ensureLoaded { UserPrefs() })
        assertEquals(saved, UserPrefsStore.prefs.value)
    }

    // ── Round 28: START → PEAK → END (summary rows and export curves share this rule) ──

    @Test
    fun `progression - new records use the saved first and last fresh readings`() {
        val json = DriveThermalTracker().apply {
            sample(frame(oil = 60.0, oilAt = 0), street, t0)
            sample(frame(oil = 118.0, oilAt = 1), street, t0 + 1_000)
            sample(frame(oil = 104.0, oilAt = 2), street, t0 + 2_000)
        }.toJson()
        // The stored readings at start / stop (−99 / 125 after a pause) are not used when the record has endpoints
        val p = DriveThermalReview.from(json).progression(OIL, -99.0, 118.0, 125.0)
        assertEquals(DriveThermalReview.Progression(60.0, 118.0, 104.0), p)
        assertTrue(p.complete)
    }

    @Test
    fun `progression - older records fall back, and an END above the PEAK becomes a dash, never a higher peak`() {
        // Early v2 record: segments only, no saved endpoints → stored start / stop readings
        val early = DriveThermalRecord.encode(listOf(ThermalSegment("street", t0, ThermalProfile.STREET.driveLimits(),
            mapOf(OIL to 100.0, COOLANT to 90.0))))
        val r = DriveThermalReview.from(early)
        val oil = r.progression(OIL, 40.0, 100.0, 110.0)        // read at stop after a pause: 110 > saved peak 100
        assertEquals(40.0, oil.startC!!, 0.0)
        assertEquals(100.0, oil.peakC!!, 0.0)                    // saved peak unchanged — not raised to 110, not hidden
        assertNull(oil.endC)
        assertFalse(oil.complete)                                // the export leaves this curve out; the summary shows "—"
        val missing = r.progression(OIL, -99.0, 100.0, 95.0)     // no reading when recording started
        assertNull(missing.startC); assertEquals(100.0, missing.peakC!!, 0.0); assertEquals(95.0, missing.endC!!, 0.0)
        assertEquals(DriveThermalReview.Progression(45.0, 90.0, 88.0), r.progression(COOLANT, 45.0, -99.0, 88.0))
        assertEquals(90.0, r.progression(COOLANT, 45.0, 140.0, 88.0).peakC!!, 0.0)   // record peak, not the stored one
        // A start above the peak (stale pre-drive RDU in the points) is dropped the same way
        val rdu = DriveThermalReview.from(DriveThermalRecord.encode(listOf(ThermalSegment("street", t0,
            ThermalProfile.STREET.driveLimits(), mapOf(RDU to 70.0))))).progression(RDU, 74.0, -99.0, 68.0)
        assertNull(rdu.startC); assertEquals(70.0, rdu.peakC!!, 0.0); assertEquals(68.0, rdu.endC!!, 0.0)
    }

    @Test
    fun `progression - legacy and no-record drives use their stored peak, endpoints checked against it`() {
        val legacy = DriveThermalReview.from("""[{"sensor":"Oil","peakC":134.0,"critThresholdC":140.0}]""")
        assertEquals(DriveThermalReview.Progression(35.0, 136.0, 134.0), legacy.progression(OIL, 35.0, 136.0, 134.0))
        assertNull(legacy.progression(RDU, 50.0, -99.0, 60.0).peakC)   // a legacy drive-end RDU value is no peak
        val none = DriveThermalReview.from(null)
        assertNull(none.progression(OIL, 40.0, 112.0, 115.0).endC)
        // No peak at all: nothing to check endpoints against → no row (summary) / no curve (export)
        val empty = none.progression(OIL, 40.0, -99.0, 60.0)
        assertNull(empty.peakC); assertNull(empty.startC); assertNull(empty.endC)
    }
}
