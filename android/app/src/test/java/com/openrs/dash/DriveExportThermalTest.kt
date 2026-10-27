package com.openrs.dash

import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveThermalRecord
import com.openrs.dash.data.DriveThermalSensor
import com.openrs.dash.data.DriveThermalTracker
import com.openrs.dash.data.DriveThermalSensor.COOLANT
import com.openrs.dash.data.DriveThermalSensor.OIL
import com.openrs.dash.data.DriveThermalSensor.PTU
import com.openrs.dash.data.DriveThermalSensor.RDU
import com.openrs.dash.data.ThermalSegment
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.ui.ThermalProfile
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.UserPrefsStore
import com.openrs.dash.ui.driveLimits
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The profile export's thermal section reads the drive's saved record through DriveThermalReview — the parser
 * TRIP's TEMP badge and the summary use — so it carries the saved limits, true peaks and flag readings, and
 * says plainly when a drive is legacy or has no record. In-memory fixtures only.
 */
class DriveExportThermalTest {

    private val t0 = 1_700_000_000_000L

    @After fun resetPrefs() = UserPrefsStore.resetForTest()

    /** A recorded segment: the fresh readings in order, under one profile (fed through the recorder's tracker). */
    private data class Seg(val profile: String, val p: ThermalProfile, val readings: List<Map<DriveThermalSensor, Double>>)

    /** The record the recorder would save — peaks and first / last fresh readings from the same samples. */
    private fun record(vararg segs: Seg): String = DriveThermalTracker().apply {
        segs.forEachIndexed { i, seg ->
            seg.readings.forEachIndexed { k, r -> sample(t0 + i * 600_000L + k * 1_000L, seg.profile, seg.p.driveLimits(), r) }
        }
    }.toJson()!!

    /** An early v2 record (segments only, no first / last readings) — as written before endpoints were saved. */
    private fun earlyRecord(vararg segs: Triple<String, ThermalProfile, Map<DriveThermalSensor, Double>>) =
        DriveThermalRecord.encode(segs.mapIndexed { i, (id, p, peaks) -> ThermalSegment(id, t0 + i * 600_000L, p.driveLimits(), peaks) })

    private fun drive(thermal: String?, oil: Triple<Double, Double, Double>, coolant: Triple<Double, Double, Double>) = DriveEntity(
        id = 1, startTime = t0, endTime = t0 + 1_800_000L, hasGps = true,
        startOilTempC = oil.first, peakOilTempC = oil.second, endOilTempC = oil.third,
        startCoolantTempC = coolant.first, peakCoolantTempC = coolant.second, endCoolantTempC = coolant.third,
        thermalPeaksJson = thermal)

    /** First / last points carry PTU 60 → 80 °C and RDU 50 → 85 °C. */
    private fun points(withAwd: Boolean = true) = listOf(
        DrivePointEntity(driveId = 1, timestamp = t0 + 1_000, lat = 51.5074, lng = -0.1278, speedKph = 30.0, rpm = 1800,
            oilTempC = 90.0, coolantTempC = 85.0,
            ptuTempC = if (withAwd) 60.0 else -99.0, rduTempC = if (withAwd) 50.0 else -99.0),
        DrivePointEntity(driveId = 1, timestamp = t0 + 1_799_000, lat = 51.5124, lng = -0.1210, speedKph = 60.0, rpm = 3200,
            oilTempC = 88.0, coolantTempC = 84.0,
            ptuTempC = if (withAwd) 80.0 else -99.0, rduTempC = if (withAwd) 85.0 else -99.0))

    private fun export(name: String, d: DriveEntity, pts: List<DrivePointEntity> = points()): JSONObject {
        val profile = DriveExportBuilder.buildProfileJson(d, pts)
        val json = JSONObject(profile)
        // Complete per-drive export file sets, named as DiagnosticExporter writes them (Sapphire compatibility checks):
        // OPENRS_EXPORT_SAMPLES=<dir> ./gradlew test --tests '*DriveExportThermalTest'
        System.getenv("OPENRS_EXPORT_SAMPLES")?.let { dir ->
            val ts = "20261026_120000"
            val out = File(dir, name).apply { mkdirs() }
            val files = linkedMapOf(
                "drive_$ts.gpx" to DriveExportBuilder.buildGpx(d, pts, ts),
                "drive_$ts.csv" to DriveExportBuilder.buildCsv(pts),
                "drive_summary_$ts.txt" to DriveExportBuilder.buildSummary(d, pts),
                "drive_profile_$ts.json" to profile)
            files.forEach { (f, text) -> File(out, f).writeText(text) }
            // Same shape as DiagnosticExporter.buildManifest / inferType
            val types = mapOf("gpx" to "drive_gpx", "csv" to "drive_csv", "txt" to "drive_summary", "json" to "drive_profile")
            File(out, "manifest.json").writeText(JSONObject().apply {
                put("version", 1); put("app", "openRS_"); put("exportedAt", t0)
                put("files", org.json.JSONArray().apply {
                    files.keys.forEach { f -> put(JSONObject().put("name", f).put("type", types.getValue(f.substringAfterLast('.')))) }
                })
            }.toString(2))
        }
        // Every exported progression is coherent: valid readings, neither endpoint above the peak
        val prog = json.getJSONObject("thermalProgression")
        for (k in prog.keys()) {
            val c = prog.getJSONObject(k)
            val (a, pk, e) = Triple(c.getDouble("startC"), c.getDouble("peakC"), c.getDouble("endC"))
            assertTrue("$name/$k: $a → $pk → $e", a > -90 && e > -90 && a <= pk && e <= pk)
        }
        // Review excerpts: OPENRS_EXPORT_EXCERPTS=<dir> ./gradlew test --tests '*DriveExportThermalTest'
        System.getenv("OPENRS_EXPORT_EXCERPTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.json").writeText(JSONObject().apply {
                put("thermalProgression", json.getJSONObject("thermalProgression"))
                put("thermalRecord", json.getJSONObject("thermalRecord"))
            }.toString(2))
        }
        return json
    }

    private fun JSONObject.curve(key: String) = getJSONObject("thermalProgression").optJSONObject(key)
    private fun JSONObject.rec() = getJSONObject("thermalRecord")
    private fun JSONObject.sensor(key: String) = rec().getJSONObject("sensors").getJSONObject(key)

    @Test
    fun `built-in profile - saved limits, true peaks, flags and PTU RDU curves`() {
        val j = export("1-track", drive(record(Seg("track", ThermalProfile.TRACK, listOf(
            mapOf(OIL to 62.0, COOLANT to 58.0, PTU to 60.0, RDU to 50.0),
            mapOf(OIL to 131.0, COOLANT to 104.0, PTU to 88.0, RDU to 92.0),
            mapOf(OIL to 112.0, COOLANT to 96.0, PTU to 80.0, RDU to 85.0)))),
            oil = Triple(62.0, 131.0, 112.0), coolant = Triple(58.0, 104.0, 96.0)))
        assertEquals("profile", j.rec().getString("basis"))
        assertEquals("°C", j.rec().getString("unit"))
        assertTrue(j.rec().getBoolean("flagged"))
        val seg = j.rec().getJSONArray("segments").getJSONObject(0)
        assertEquals("track", seg.getString("profile"))
        assertEquals(0L, seg.getLong("startOffsetMs"))
        val oilLim = seg.getJSONObject("limits").getJSONObject("oil")
        assertEquals(110.0, oilLim.getDouble("warnC"), 0.0)
        assertEquals(120.0, oilLim.getDouble("critC"), 0.0)
        assertEquals(114.0, oilLim.getDouble("flagThresholdC"), 1e-9)
        assertEquals("CRIT", j.sensor("oil").getString("peakLevel"))
        assertTrue(j.sensor("rdu").getBoolean("flagged"))               // 92 ≥ 90.25 (95 % of 95)
        assertFalse(j.sensor("coolant").getBoolean("flagged"))          // 104 < 104.5
        assertFalse(j.sensor("coolant").has("flag"))
        // RDU / PTU curves were never exported before (T5D looked for keys the recorder never wrote)
        assertEquals(50.0, j.curve("rdu")!!.getDouble("startC"), 0.0)
        assertEquals(92.0, j.curve("rdu")!!.getDouble("peakC"), 0.0)
        assertEquals(85.0, j.curve("rdu")!!.getDouble("endC"), 0.0)
        assertEquals(88.0, j.curve("ptu")!!.getDouble("peakC"), 0.0)
        assertEquals(131.0, j.curve("oil")!!.getDouble("peakC"), 0.0)
        assertEquals(112.0, j.curve("oil")!!.getDouble("endC"), 0.0)
    }

    @Test
    fun `Custom - its own saved limits, not the built-in or today's profile`() {
        UserPrefsStore.ensureLoaded { UserPrefs(tempPreset = "race") }  // what is selected now must not matter
        val custom = ThermalProfile.STREET.copy(oilWarnC = 112.0, oilCritC = 124.0)
        val j = export("2-custom", drive(record(Seg("custom", custom, listOf(
            mapOf(OIL to 55.0, COOLANT to 60.0), mapOf(OIL to 118.0, COOLANT to 98.0), mapOf(OIL to 104.0, COOLANT to 92.0)))),
            oil = Triple(55.0, 118.0, 104.0), coolant = Triple(60.0, 98.0, 92.0)))
        val oilLim = j.rec().getJSONArray("segments").getJSONObject(0).getJSONObject("limits").getJSONObject("oil")
        assertEquals(124.0, oilLim.getDouble("critC"), 0.0)
        assertEquals(117.8, oilLim.getDouble("flagThresholdC"), 1e-9)
        val flag = j.sensor("oil").getJSONObject("flag")
        assertEquals("custom", flag.getString("profile"))
        assertEquals(118.0, flag.getDouble("readingC"), 0.0)
        assertEquals("WARN", j.sensor("oil").getString("peakLevel"))
    }

    @Test
    fun `mixed profiles - a higher STREET peak and a lower RACE flag stay distinct`() {
        // Ends at 104 °C: the last fresh reading, inside the final RACE segment (whose peak is 106 °C)
        val j = export("3-street-to-race", drive(record(
            Seg("street", ThermalProfile.STREET, listOf(mapOf(OIL to 70.0, COOLANT to 65.0), mapOf(OIL to 122.0, COOLANT to 99.0))),
            Seg("race", ThermalProfile.RACE, listOf(mapOf(OIL to 106.0, COOLANT to 95.0), mapOf(OIL to 104.0, COOLANT to 93.0)))),
            oil = Triple(70.0, 122.0, 104.0), coolant = Triple(65.0, 99.0, 93.0)))
        val segs = j.rec().getJSONArray("segments")
        assertEquals(2, segs.length())
        assertEquals("race", segs.getJSONObject(1).getString("profile"))
        assertEquals(600_000L, segs.getJSONObject(1).getLong("startOffsetMs"))
        assertEquals(106.0, segs.getJSONObject(1).getJSONObject("peaksC").getDouble("oil"), 0.0)
        val oil = j.sensor("oil")
        assertEquals(122.0, oil.getDouble("peakC"), 0.0)                // the true peak…
        assertEquals("street", oil.getString("peakProfile"))
        assertEquals("WARN", oil.getString("peakLevel"))                // …by STREET (RACE would say CRIT)
        val flag = oil.getJSONObject("flag")                            // …and the reading that raised the flag
        assertEquals(106.0, flag.getDouble("readingC"), 0.0)
        assertEquals("race", flag.getString("profile"))
        assertEquals(110.0, flag.getDouble("critC"), 0.0)
        assertEquals(104.5, flag.getDouble("flagThresholdC"), 1e-9)
        assertEquals(122.0, j.curve("oil")!!.getDouble("peakC"), 0.0)
        assertEquals(104.0, j.curve("oil")!!.getDouble("endC"), 0.0)
        assertNull(j.curve("rdu"))                                      // no RDU in the record → no curve
    }

    @Test
    fun `legacy - drive-end readings against the old fixed limits, never peaks or a profile`() {
        val legacy = """[{"sensor":"Oil","peakC":134.0,"climbRateCPerMin":0.4,"critThresholdC":140.0},""" +
            """{"sensor":"RDU","peakC":110.0,"climbRateCPerMin":0.0,"critThresholdC":120.0}]"""
        // Legacy driveEndC was the last recorded sample; endOilTempC the reading at stop — the same moment ±1 s here
        val j = export("4-legacy", drive(legacy, oil = Triple(40.0, 136.0, 134.0), coolant = Triple(45.0, 104.0, 95.0)))
        assertEquals("legacy", j.rec().getString("basis"))
        assertTrue(j.rec().getString("note").contains("not peaks"))
        assertFalse(j.rec().has("segments"))
        val oil = j.sensor("oil")
        assertEquals(134.0, oil.getDouble("driveEndC"), 0.0)
        assertEquals(140.0, oil.getDouble("fixedLimitC"), 0.0)
        assertEquals(133.0, oil.getDouble("flagThresholdC"), 1e-9)
        assertTrue(oil.getBoolean("flagged"))
        assertFalse(oil.has("peakC")); assertFalse(oil.has("peakProfile"))
        assertFalse(j.sensor("rdu").getBoolean("flagged"))
        assertEquals(120.0, j.sensor("rdu").getDouble("fixedLimitC"), 0.0)
        // Peaks stay the drive's own peaks; the drive-end RDU reading is not turned into a curve
        assertEquals(136.0, j.curve("oil")!!.getDouble("peakC"), 0.0)
        assertEquals(134.0, j.curve("oil")!!.getDouble("endC"), 0.0)
        assertNull(j.curve("rdu")); assertNull(j.curve("ptu"))
    }

    @Test
    fun `no record - says the limits are unknown, nothing substituted`() {
        UserPrefsStore.ensureLoaded { UserPrefs(tempPreset = "track") }
        val j = export("5-no-record", drive(null, oil = Triple(40.0, 112.0, 110.0), coolant = Triple(45.0, 104.0, 95.0)))
        assertEquals("none", j.rec().getString("basis"))
        assertEquals("absent", j.rec().getString("reason"))
        assertFalse(j.rec().getBoolean("flagged"))
        assertTrue(j.rec().getString("note").contains("unknown"))
        assertFalse(j.rec().has("segments")); assertFalse(j.rec().has("sensors"))
        assertEquals(112.0, j.curve("oil")!!.getDouble("peakC"), 0.0)
        assertNull(j.curve("rdu"))
        // An unreadable record: same unknown-limits treatment, but the note says it exists and couldn't be read
        val u = export("5b-unreadable", drive("{broken", oil = Triple(40.0, 112.0, 110.0), coolant = Triple(45.0, 104.0, 95.0))).rec()
        assertEquals("none", u.getString("basis"))
        assertEquals("unreadable", u.getString("reason"))
        assertTrue(u.getString("note").contains("could not be read"))
        assertFalse(u.has("sensors"))
    }

    @Test
    fun `endpoints above the saved peak are not exported as a progression - the peak stays in the record`() {
        // Production case: an early v2 record (no first / last readings) falls back to the reading at stop. After a
        // pause the oil kept rising to 110 °C, above every recorded (fresh) sample — peak 100 °C. RDU: the record has
        // a peak, but the points hold an RDU value from before the drive (stale, 74 °C) above it.
        val j = export("6-endpoints-above-peak", drive(earlyRecord(Triple("street", ThermalProfile.STREET, mapOf(OIL to 100.0, COOLANT to 90.0, RDU to 70.0))),
            oil = Triple(40.0, 100.0, 110.0), coolant = Triple(45.0, 90.0, 88.0)),
            pts = listOf(DrivePointEntity(driveId = 1, timestamp = t0 + 1_000, lat = 0.0, lng = 0.0, rduTempC = 74.0),
                         DrivePointEntity(driveId = 1, timestamp = t0 + 2_000, lat = 0.0, lng = 0.0, rduTempC = 68.0)))
        assertNull(j.curve("oil"))                                      // 110 > 100: no "peak" curve ending above its peak
        assertNull(j.curve("rdu"))                                      // starts above the saved peak
        assertEquals(100.0, j.sensor("oil").getDouble("peakC"), 0.0)    // saved peak kept as is — not raised to 110
        assertEquals(70.0, j.sensor("rdu").getDouble("peakC"), 0.0)
        assertEquals(90.0, j.curve("coolant")!!.getDouble("peakC"), 0.0) // a coherent sensor is still exported
        // Missing endpoints (no reading at start / stop, no points) → no curve either; never -99 in a progression
        val k = export("6b-no-endpoints", drive(earlyRecord(Triple("street", ThermalProfile.STREET, mapOf(OIL to 100.0, RDU to 70.0))),
            oil = Triple(-99.0, 100.0, 95.0), coolant = Triple(-99.0, -99.0, -99.0)), pts = points(withAwd = false))
        assertNull(k.curve("oil")); assertNull(k.curve("coolant")); assertNull(k.curve("rdu"))
        assertEquals(100.0, k.sensor("oil").getDouble("peakC"), 0.0)
        assertEquals(2, j.getInt("version"))                            // additive change: version unchanged
    }

    @Test
    fun `the recorder's endpoints are the same fresh readings as its peaks`() {
        // A stale value before the drive never reaches the record; first / last are fresh readings, so start ≤ peak ≥ end
        val json = record(Seg("street", ThermalProfile.STREET, listOf(
            mapOf(RDU to -99.0), mapOf(RDU to 60.0), mapOf(RDU to 72.0), mapOf(RDU to 66.0), mapOf(RDU to -99.0))))
        val (first, last) = DriveThermalRecord.decodeEndpoints(json)
        assertEquals(60.0, first.getValue(RDU), 0.0)
        assertEquals(66.0, last.getValue(RDU), 0.0)
        val j = export("7-rdu-from-record", drive(json, oil = Triple(-99.0, -99.0, -99.0), coolant = Triple(-99.0, -99.0, -99.0)),
            pts = listOf(DrivePointEntity(driveId = 1, timestamp = t0, lat = 0.0, lng = 0.0, rduTempC = 74.0)))
        val rdu = j.curve("rdu")!!                                      // record endpoints win over the stale point value
        assertEquals(60.0, rdu.getDouble("startC"), 0.0)
        assertEquals(72.0, rdu.getDouble("peakC"), 0.0)
        assertEquals(66.0, rdu.getDouble("endC"), 0.0)
    }
}
