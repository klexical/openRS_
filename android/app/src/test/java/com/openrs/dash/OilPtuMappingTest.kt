package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveThermalRecord
import com.openrs.dash.data.DriveThermalReview
import com.openrs.dash.data.DriveThermalSensor
import com.openrs.dash.data.DriveThermalTracker
import com.openrs.dash.data.OilPtuMapping
import com.openrs.dash.data.ThermalSegment
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.ui.ThermalLimits
import com.openrs.dash.ui.ThermalProfile
import com.openrs.dash.ui.ThermalReadinessEngine
import com.openrs.dash.ui.ThermalSensor
import com.openrs.dash.ui.ThermalState
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.driveLimits
import com.openrs.dash.ui.sample
import com.openrs.dash.ui.trip.ColorMode
import com.openrs.dash.ui.trip.legendLabel
import com.openrs.dash.ui.validateThermalProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #229 — 0x0F8 byte 7 − 60 is ENGINE OIL and byte 1 − 50 is PTU (the reverse of the DBC names). Synthetic frames only;
 * every case runs the production path: CanDecoder.decode → stampFresh → readiness / the recorder's thermal tracker →
 * the drive's record → the export. Drives saved before the correction (no `oilPtuMapping`) keep their values, record,
 * flags and progression and are disclosed as "not recorded" — never relabelled.
 */
class OilPtuMappingTest {

    private val t0 = 1_800_000_000_000L
    private val street = UserPrefs(tempPreset = "street")

    /** 0x0F8 with byte 1 / byte 7 set (boost byte 5 = 0), decoded and stamped as CanDataService.processCanFrame does. */
    private fun frame(byte1: Int, byte7: Int, now: Long, prev: VehicleState = VehicleState()): VehicleState {
        val data = byteArrayOf(0, byte1.toByte(), 0, 0, 0, 0, 0, byte7.toByte())
        val decoded = CanDecoder.decode(CanDecoder.ID_ENGINE_TEMPS, data, prev)!!
        return CanDecoder.stampFresh(CanDecoder.ID_ENGINE_TEMPS, prev, decoded, now)
    }

    /** A connected car: the 0x0F8 frame plus a warm, fresh coolant reading (not the subject here). */
    private fun car(byte1: Int, byte7: Int, now: Long): VehicleState =
        frame(byte1, byte7, now).let { it.copy(isConnected = true, coolantTempC = 90.0).markFresh("coolantTempC", now = now) }

    @Test
    fun `decoder - byte 7 is oil, byte 1 is PTU, offsets travel with their bytes, boost byte 5 untouched`() {
        val vs = frame(byte1 = 94, byte7 = 127, now = t0)
        assertEquals(67.0, vs.oilTempC, 1e-9)            // 127 − 60 (before the fix: 94 − 50 = 44)
        assertEquals(44.0, vs.ptuTempC, 1e-9)            // 94 − 50  (before: 127 − 60 = 67)
        assertEquals(101.325, vs.boostKpa, 1e-9)         // byte 5 = 0 → 0 kPa gauge, unchanged
        assertEquals(t0, vs.fieldLastUpdateMs["oilTempC"]) // each tag stamps its own field, from the same frame
        assertEquals(t0, vs.fieldLastUpdateMs["ptuTempC"])
        // Extremes of each byte: oil −60..195, PTU −50..205 — the editor's reporting ranges
        frame(byte1 = 0, byte7 = 0, now = t0).let { assertEquals(-60.0, it.oilTempC, 1e-9); assertEquals(-50.0, it.ptuTempC, 1e-9) }
        frame(byte1 = 255, byte7 = 255, now = t0).let { assertEquals(195.0, it.oilTempC, 1e-9); assertEquals(205.0, it.ptuTempC, 1e-9) }
        assertTrue(CanDecoder.describeDecoded(CanDecoder.ID_ENGINE_TEMPS, vs).contains("oilTempC=67"))
    }

    @Test
    fun `readiness - oil warm on byte 7 with a cold PTU on byte 1 reads DRIVETRAIN WARMING, not ENGINE WARMING`() {
        // byte 7 = 145 → oil 85 °C (≥ 80 + 1, warm); byte 1 = 80 → PTU 30 °C (< 40). The old mapping read oil 30 / PTU 85.
        val engine = ThermalReadinessEngine()
        val limits = ThermalLimits.from(street)
        var verdict = engine.evaluate(car(80, 145, t0), limits, t0, 0L)
        for (s in 1..15L) verdict = engine.evaluate(car(80, 145, t0 + s * 1000), limits, t0 + s * 1000, s * 1000)
        assertEquals(ThermalState.DRIVETRAIN_WARMING, verdict.state)
        assertEquals(85.0, verdict.sensors.first { it.sensor == ThermalSensor.OIL }.valueC!!, 1e-9)
        assertEquals(30.0, verdict.sensors.first { it.sensor == ThermalSensor.PTU }.valueC!!, 1e-9)
    }

    @Test
    fun `recorded drive - the thermal record and every export put byte 7 on OIL and name the mapping`() {
        val tracker = DriveThermalTracker()
        val points = (0 until 3).map { i ->
            val now = t0 + i * 1000L
            val vs = car(94, 127, now)
            tracker.sample(vs, street, now)                                   // the recorder's sampling call
            DrivePointEntity(driveId = 7, timestamp = now, lat = 51.5, lng = -0.1, oilTempC = vs.oilTempC,
                ptuTempC = vs.ptuTempC, coolantTempC = vs.coolantTempC)
        }
        val drive = DriveEntity(id = 7, startTime = t0, endTime = t0 + 3_000, startOilTempC = 67.0, endOilTempC = 67.0,
            peakOilTempC = 67.0, thermalPeaksJson = tracker.toJson(), oilPtuMapping = OilPtuMapping.CURRENT)

        val review = DriveThermalReview.from(drive.thermalPeaksJson)
        assertEquals(67.0, review.sensor(DriveThermalSensor.OIL)!!.peakC, 1e-9)
        assertEquals(44.0, review.sensor(DriveThermalSensor.PTU)!!.peakC, 1e-9)

        val csv = DriveExportBuilder.buildCsv(points, oilPtuMapping = drive.oilPtuMapping)
        assertEquals("# openrs_csv_v2 oil_ptu_mapping=${OilPtuMapping.CURRENT}", csv.lines()[0])
        val header = csv.lines()[1].split(","); val row = csv.lines()[2].split(",")
        assertEquals("67.0", row[header.indexOf("oil_c")]); assertEquals("44.0", row[header.indexOf("ptu_c")])

        val profile = JSONObject(DriveExportBuilder.buildProfileJson(drive, points))
        assertEquals(OilPtuMapping.CURRENT, profile.getJSONObject("drive").getString("oilPtuMapping"))
        val record = profile.getJSONObject("thermalRecord")
        assertEquals(OilPtuMapping.CURRENT, record.getString("oilPtuMapping"))
        assertEquals(67.0, record.getJSONObject("sensors").getJSONObject("oil").getDouble("peakC"), 1e-9)
        assertEquals(44.0, record.getJSONObject("sensors").getJSONObject("ptu").getDouble("peakC"), 1e-9)

        assertTrue(DriveExportBuilder.buildSummary(drive, points).contains("Oil / PTU   : oil = 0x0F8 byte 7 − 60 °C"))
        assertTrue(DriveExportBuilder.buildGpx(drive, points, "t").contains("<openrs:oilPtuMapping>${OilPtuMapping.CURRENT}</openrs:oilPtuMapping>"))
    }

    @Test
    fun `a drive saved before the correction keeps its values, flags and progression and says the mapping is not recorded`() {
        // As an earlier build stored it: byte 1 read as oil (here 125 °C, flagged under STREET's 130), byte 7 as PTU.
        val seg = ThermalSegment("street", t0, ThermalProfile.STREET.driveLimits(),
            mapOf(DriveThermalSensor.OIL to 125.0, DriveThermalSensor.PTU to 70.0))
        val json = DriveThermalRecord.encode(listOf(seg), firstC = mapOf(DriveThermalSensor.OIL to 60.0),
            lastC = mapOf(DriveThermalSensor.OIL to 110.0))
        val old = DriveEntity(id = 3, startTime = t0, endTime = t0 + 60_000, startOilTempC = 60.0, peakOilTempC = 125.0,
            endOilTempC = 110.0, thermalPeaksJson = json)                     // oilPtuMapping: null — never restamped
        val pts = listOf(DrivePointEntity(driveId = 3, timestamp = t0, lat = 51.5, lng = -0.1, oilTempC = 125.0, ptuTempC = 70.0))

        assertNull(old.oilPtuMapping)
        assertFalse(OilPtuMapping.isCurrent(old.oilPtuMapping))
        val review = DriveThermalReview.from(old.thermalPeaksJson)
        assertEquals(DriveThermalReview.Basis.PROFILE, review.basis)          // a v2 record says nothing about the mapping
        assertTrue(review.sensor(DriveThermalSensor.OIL)!!.flagged)           // flag as recorded
        assertEquals(DriveThermalReview.Progression(60.0, 125.0, 110.0),
            review.progression(DriveThermalSensor.OIL, old.startOilTempC, old.peakOilTempC, old.endOilTempC))

        assertEquals("# openrs_csv_v2 oil_ptu_mapping=not_recorded", DriveExportBuilder.buildCsv(pts, oilPtuMapping = old.oilPtuMapping).lines()[0])
        val profile = JSONObject(DriveExportBuilder.buildProfileJson(old, pts))
        assertFalse(profile.getJSONObject("drive").has("oilPtuMapping"))      // absent = unknown, never assumed
        assertEquals(125.0, profile.getJSONObject("drive").getDouble("peakOilTempC"), 1e-9)
        val record = profile.getJSONObject("thermalRecord")
        assertEquals("not_recorded", record.getString("oilPtuMapping"))
        assertTrue(record.getString("oilPtuMappingNote").contains("may be each other's"))
        assertEquals(125.0, record.getJSONObject("sensors").getJSONObject("oil").getDouble("peakC"), 1e-9)
        assertTrue(DriveExportBuilder.buildSummary(old, pts).contains("Oil / PTU   : not recorded"))

        // The legacy fixed-limit array still reads as before, also with no mapping
        val legacy = DriveThermalReview.from("""[{"sensor":"Oil","peakC":134.0,"critThresholdC":140.0}]""")
        assertEquals(DriveThermalReview.Basis.LEGACY, legacy.basis)
        assertTrue(legacy.sensor(DriveThermalSensor.OIL)!!.flagged)
    }

    @Test
    fun `TRIP disclosures - map legend and the peak-oil trend never join corrected and earlier drives`() {
        assertEquals("TEMP", legendLabel(ColorMode.OIL_TEMP, emptyList(), OilPtuMapping.CURRENT))
        assertEquals("TEMP · MAP N/R", legendLabel(ColorMode.OIL_TEMP, emptyList(), null))
        assertEquals("SPD", legendLabel(ColorMode.SPEED, emptyList(), null))

        val old = (1..3L).map { DriveEntity(id = it, startTime = it, peakOilTempC = 100.0) }
        val new = (4..5L).map { DriveEntity(id = it, startTime = it, peakOilTempC = 90.0, oilPtuMapping = OilPtuMapping.CURRENT) }
        assertEquals(listOf(4L, 5L), OilPtuMapping.sameKindAsNewest(old + new).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), OilPtuMapping.sameKindAsNewest(new + old).map { it.id })
    }

    @Test
    fun `Custom editor - reporting ranges follow the bytes, saved limits load unchanged`() {
        fun cells(p: ThermalProfile) = ThermalProfile.FIELDS.associate { it.key to it.get(p) as Double? }
        val saved = ThermalProfile.STREET.copy(oilCritC = 200.0, ptuMinC = -55.0)  // inside the old ranges only
        val (loaded, _) = ThermalProfile.fromJson(saved.toJson("street"))!!
        assertEquals(200.0, loaded.oilCritC, 1e-9)                          // never altered on load
        assertEquals(-55.0, loaded.ptuMinC, 1e-9)
        val errors = validateThermalProfile(cells(loaded), "C")              // surfaced when the editor saves
        assertTrue(errors["oilCritC"]!!.contains("-60°C to 195°C"))
        assertTrue(errors["ptuMinC"]!!.contains("-50°C to 205°C"))
        assertTrue(validateThermalProfile(cells(ThermalProfile.STREET.copy(oilCritC = 195.0, ptuCritC = 205.0)), "C").isEmpty())
        for (p in listOf(ThermalProfile.STREET, ThermalProfile.TRACK, ThermalProfile.RACE))
            assertTrue(validateThermalProfile(cells(p), "C").isEmpty())
    }
}
