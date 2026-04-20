package com.openrs.dash

import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcStatus
import com.openrs.dash.data.FreezeFrame
import com.openrs.dash.data.FreezeFrameEntry
import com.openrs.dash.diagnostics.DriveExportBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [DriveExportBuilder] — CSV, GPX, profile JSON, summary, and DTC exports.
 */
class DriveExportBuilderTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Test fixtures
    // ═══════════════════════════════════════════════════════════════════════

    private fun testDrive(
        startTime: Long = 1700000000000L,
        endTime: Long = 1700003600000L,     // +1 hour
        distanceKm: Double = 42.5,
        avgSpeedKph: Double = 42.5,
        maxSpeedKph: Double = 180.0,
        peakRpm: Int = 6500,
        peakBoostPsi: Double = 21.3,
        peakLateralG: Double = 1.05,
        fuelUsedL: Double = 8.2,
        driveModeBreakdown: String = """{"Normal":0.6,"Sport":0.3,"Track":0.1}""",
        peakOilTempC: Double = 115.0,
        peakCoolantTempC: Double = 98.0,
        startOilTempC: Double = 45.0,
        endOilTempC: Double = 105.0,
        startCoolantTempC: Double = 40.0,
        endCoolantTempC: Double = 92.0,
        aggressionScore: Int = 65
    ) = DriveEntity(
        id = 1,
        startTime = startTime,
        endTime = endTime,
        hasGps = true,
        distanceKm = distanceKm,
        avgSpeedKph = avgSpeedKph,
        maxSpeedKph = maxSpeedKph,
        peakRpm = peakRpm,
        peakBoostPsi = peakBoostPsi,
        peakLateralG = peakLateralG,
        fuelUsedL = fuelUsedL,
        driveModeBreakdown = driveModeBreakdown,
        peakOilTempC = peakOilTempC,
        peakCoolantTempC = peakCoolantTempC,
        startOilTempC = startOilTempC,
        endOilTempC = endOilTempC,
        startCoolantTempC = startCoolantTempC,
        endCoolantTempC = endCoolantTempC,
        aggressionScore = aggressionScore
    )

    private fun testPoint(
        driveId: Long = 1,
        timestamp: Long = 1700000000000L,
        lat: Double = 52.5200,
        lng: Double = 13.4050,
        speedKph: Double = 100.0,
        rpm: Int = 4500,
        gear: String = "3",
        boostPsi: Double = 15.5,
        coolantTempC: Double = 90.0,
        oilTempC: Double = 105.0,
        ambientTempC: Double = 22.0,
        rduTempC: Double = 55.0,
        ptuTempC: Double = 65.0,
        fuelLevelPct: Double = 72.0,
        lateralG: Double = 0.35,
        throttlePct: Double = 80.0,
        driveMode: String = "Sport",
        longitudinalG: Double = 0.15,
        brakePressure: Double = 0.0,
        steeringAngle: Double = -12.5,
        isRaceReady: Boolean = true
    ) = DrivePointEntity(
        driveId = driveId,
        timestamp = timestamp,
        lat = lat,
        lng = lng,
        speedKph = speedKph,
        rpm = rpm,
        gear = gear,
        boostPsi = boostPsi,
        coolantTempC = coolantTempC,
        oilTempC = oilTempC,
        ambientTempC = ambientTempC,
        rduTempC = rduTempC,
        ptuTempC = ptuTempC,
        fuelLevelPct = fuelLevelPct,
        lateralG = lateralG,
        throttlePct = throttlePct,
        driveMode = driveMode,
        longitudinalG = longitudinalG,
        brakePressure = brakePressure,
        steeringAngle = steeringAngle,
        isRaceReady = isRaceReady
    )

    private fun testDtc(
        module: String = "PCM",
        code: String = "P0300",
        description: String = "Random/Multiple Cylinder Misfire Detected",
        status: DtcStatus = DtcStatus.ACTIVE,
        freezeFrame: FreezeFrame? = null
    ) = DtcResult(module, code, description, status, freezeFrame)

    // ═══════════════════════════════════════════════════════════════════════
    // CSV tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `csv starts with schema version comment`() {
        val csv = DriveExportBuilder.buildCsv(listOf(testPoint()))
        assertTrue(csv.startsWith("# openrs_csv_v1"))
    }

    @Test fun `csv header has expected column count`() {
        val csv = DriveExportBuilder.buildCsv(listOf(testPoint()))
        val lines = csv.lines().filter { it.isNotBlank() }
        val header = lines[1] // line 0 is schema comment
        val headerCols = header.split(",")
        assertEquals(32, headerCols.size)
    }

    @Test fun `csv data row column count matches header`() {
        val csv = DriveExportBuilder.buildCsv(listOf(testPoint()))
        val lines = csv.lines().filter { it.isNotBlank() }
        val headerCols = lines[1].split(",").size
        val dataCols = lines[2].split(",").size
        assertEquals(headerCols, dataCols)
    }

    @Test fun `csv contains all expected column names`() {
        val csv = DriveExportBuilder.buildCsv(emptyList())
        val header = csv.lines()[1]
        val expected = listOf(
            "timestamp_ms", "lat", "lng", "speed_kph", "rpm", "gear", "boost_psi",
            "coolant_c", "oil_c", "ambient_c", "rdu_c", "ptu_c", "fuel_pct",
            "tire_press_lf_psi", "tire_press_rf_psi", "tire_press_lr_psi", "tire_press_rr_psi",
            "tire_temp_lf_c", "tire_temp_rf_c", "tire_temp_lr_c", "tire_temp_rr_c",
            "wheel_fl_kph", "wheel_fr_kph", "wheel_rl_kph", "wheel_rr_kph",
            "lateral_g", "longitudinal_g", "brake_pressure", "steering_angle",
            "throttle_pct", "drive_mode", "race_ready"
        )
        for (col in expected) {
            assertTrue("Missing column: $col", header.contains(col))
        }
    }

    @Test fun `csv renders correct values`() {
        val pt = testPoint(timestamp = 1700000000000L, lat = 52.52, lng = 13.405, rpm = 4500)
        val csv = DriveExportBuilder.buildCsv(listOf(pt))
        val dataLine = csv.lines().filter { it.isNotBlank() }[2]
        assertTrue(dataLine.startsWith("1700000000000,"))
        assertTrue(dataLine.contains("52.520000"))
        assertTrue(dataLine.contains("13.405000"))
        assertTrue(dataLine.contains(",4500,"))
    }

    @Test fun `csv empty points produces header only`() {
        val csv = DriveExportBuilder.buildCsv(emptyList())
        val lines = csv.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size) // schema comment + header
    }

    @Test fun `csv multiple points produces correct row count`() {
        val points = (1..5).map { testPoint(timestamp = 1700000000000L + it * 1000) }
        val csv = DriveExportBuilder.buildCsv(points)
        val lines = csv.lines().filter { it.isNotBlank() }
        assertEquals(7, lines.size) // schema + header + 5 data rows
    }

    @Test fun `csv escape handles commas in gear`() {
        val result = DriveExportBuilder.csvEscape("3,4")
        assertEquals("\"3,4\"", result)
    }

    @Test fun `csv escape handles quotes`() {
        val result = DriveExportBuilder.csvEscape("he said \"hello\"")
        assertEquals("\"he said \"\"hello\"\"\"", result)
    }

    @Test fun `csv escape passes simple values through`() {
        assertEquals("Sport", DriveExportBuilder.csvEscape("Sport"))
        assertEquals("3", DriveExportBuilder.csvEscape("3"))
    }

    @Test fun `csv sentinel values preserved`() {
        val pt = testPoint(coolantTempC = -99.0, oilTempC = -99.0, fuelLevelPct = -1.0)
        val csv = DriveExportBuilder.buildCsv(listOf(pt))
        val dataLine = csv.lines().filter { it.isNotBlank() }[2]
        assertTrue(dataLine.contains("-99.0"))
        assertTrue(dataLine.contains("-1.0"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GPX tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `gpx has valid XML declaration`() {
        val gpx = DriveExportBuilder.buildGpx(testDrive(), listOf(testPoint()), "20240101_120000")
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
    }

    @Test fun `gpx contains track segment`() {
        val gpx = DriveExportBuilder.buildGpx(testDrive(), listOf(testPoint()), "20240101_120000")
        assertTrue(gpx.contains("<trkseg>"))
        assertTrue(gpx.contains("</trkseg>"))
    }

    @Test fun `gpx contains track points`() {
        val gpx = DriveExportBuilder.buildGpx(testDrive(), listOf(testPoint(lat = 52.52, lng = 13.405)), "ts")
        assertTrue(gpx.contains("""<trkpt lat="52.52" lon="13.405">"""))
    }

    @Test fun `gpx contains openrs extensions`() {
        val gpx = DriveExportBuilder.buildGpx(testDrive(), listOf(testPoint(rpm = 4500, boostPsi = 15.5)), "ts")
        assertTrue(gpx.contains("<openrs:rpm>4500</openrs:rpm>"))
        assertTrue(gpx.contains("<openrs:boostPsi>15.50</openrs:boostPsi>"))
    }

    @Test fun `gpx splits segments on pause gaps`() {
        val points = listOf(
            testPoint(timestamp = 1000L),
            testPoint(timestamp = 2000L),
            testPoint(timestamp = 10000L), // 8s gap > 5s threshold
            testPoint(timestamp = 11000L)
        )
        val gpx = DriveExportBuilder.buildGpx(testDrive(), points, "ts")
        val segmentCount = Regex("<trkseg>").findAll(gpx).count()
        assertEquals(2, segmentCount)
    }

    @Test fun `gpx no segment split for small gaps`() {
        val points = listOf(
            testPoint(timestamp = 1000L),
            testPoint(timestamp = 2000L),
            testPoint(timestamp = 3000L)
        )
        val gpx = DriveExportBuilder.buildGpx(testDrive(), points, "ts")
        val segmentCount = Regex("<trkseg>").findAll(gpx).count()
        assertEquals(1, segmentCount)
    }

    @Test fun `gpx metadata includes timestamp`() {
        val gpx = DriveExportBuilder.buildGpx(testDrive(), listOf(testPoint()), "ts")
        assertTrue(gpx.contains("<metadata>"))
        assertTrue(gpx.contains("<time>"))
    }

    @Test fun `gpx empty points still has valid structure`() {
        val gpx = DriveExportBuilder.buildGpx(testDrive(), emptyList(), "ts")
        assertTrue(gpx.contains("<trkseg>"))
        assertTrue(gpx.contains("</trkseg>"))
        assertTrue(gpx.contains("</gpx>"))
        assertFalse(gpx.contains("<trkpt"))
    }

    @Test fun `gpx extensions include all temp sensors`() {
        val gpx = DriveExportBuilder.buildGpx(testDrive(), listOf(testPoint()), "ts")
        assertTrue(gpx.contains("<openrs:coolantC>"))
        assertTrue(gpx.contains("<openrs:oilC>"))
        assertTrue(gpx.contains("<openrs:ambientC>"))
        assertTrue(gpx.contains("<openrs:rduC>"))
        assertTrue(gpx.contains("<openrs:ptuC>"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Summary text tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `summary contains drive stats`() {
        val summary = DriveExportBuilder.buildSummary(testDrive(distanceKm = 42.5, peakRpm = 6500), listOf(testPoint()))
        assertTrue(summary.contains("42.50 km"))
        assertTrue(summary.contains("6500"))
    }

    @Test fun `summary contains fuel info`() {
        val summary = DriveExportBuilder.buildSummary(testDrive(fuelUsedL = 8.2), listOf(testPoint()))
        assertTrue(summary.contains("8.20 L"))
    }

    @Test fun `summary contains duration`() {
        val drive = testDrive(startTime = 1000000L, endTime = 1003600L) // 3600ms = ~3.6s
        val summary = DriveExportBuilder.buildSummary(drive, emptyList())
        assertTrue(summary.contains("Duration"))
    }

    @Test fun `summary contains drive mode breakdown`() {
        val summary = DriveExportBuilder.buildSummary(testDrive(), emptyList())
        assertTrue(summary.contains("Normal"))
        assertTrue(summary.contains("Sport"))
    }

    @Test fun `summary has separator lines`() {
        val summary = DriveExportBuilder.buildSummary(testDrive(), emptyList())
        val separators = summary.lines().count { it.contains("═══") }
        assertEquals(3, separators)
    }

    @Test fun `summary point count reflects input`() {
        val pts = (1..15).map { testPoint(timestamp = 1700000000000L + it * 1000) }
        val summary = DriveExportBuilder.buildSummary(testDrive(), pts)
        assertTrue(summary.contains("15"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DTC text report tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `dtc text groups by module`() {
        val dtcs = listOf(
            testDtc(module = "PCM", code = "P0300"),
            testDtc(module = "BCM", code = "B1234"),
            testDtc(module = "PCM", code = "P0301")
        )
        val text = DriveExportBuilder.buildDtcText(dtcs)
        assertTrue(text.contains("─── PCM"))
        assertTrue(text.contains("─── BCM"))
        assertTrue(text.contains("P0300"))
        assertTrue(text.contains("P0301"))
        assertTrue(text.contains("B1234"))
    }

    @Test fun `dtc text shows status label`() {
        val dtcs = listOf(testDtc(status = DtcStatus.ACTIVE))
        val text = DriveExportBuilder.buildDtcText(dtcs)
        assertTrue(text.contains("[Active]"))
    }

    @Test fun `dtc text shows total count`() {
        val dtcs = listOf(
            testDtc(module = "PCM", code = "P0300"),
            testDtc(module = "BCM", code = "B1234")
        )
        val text = DriveExportBuilder.buildDtcText(dtcs)
        assertTrue(text.contains("Total: 2 fault code(s) across 2 module(s)"))
    }

    @Test fun `dtc text handles empty description`() {
        val dtcs = listOf(testDtc(description = ""))
        val text = DriveExportBuilder.buildDtcText(dtcs)
        assertTrue(text.contains("(no description)"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DTC JSON report tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `dtc json has correct structure`() {
        val dtcs = listOf(testDtc())
        val json = JSONObject(DriveExportBuilder.buildDtcJson(dtcs))
        assertEquals(1, json.getInt("version"))
        assertTrue(json.has("timestamp"))
        assertTrue(json.getString("app").contains("openRS_"))
        assertEquals(1, json.getInt("totalCodes"))
    }

    @Test fun `dtc json codes array has correct fields`() {
        val dtcs = listOf(testDtc(module = "PCM", code = "P0300", status = DtcStatus.ACTIVE))
        val json = JSONObject(DriveExportBuilder.buildDtcJson(dtcs))
        val code = json.getJSONArray("codes").getJSONObject(0)
        assertEquals("PCM", code.getString("module"))
        assertEquals("P0300", code.getString("code"))
        assertEquals("ACTIVE", code.getString("status"))
        assertTrue(code.has("severity"))
    }

    @Test fun `dtc json includes freeze frame when present`() {
        val ff = FreezeFrame(
            dtcCode = "P0300",
            recordNumber = 1,
            entries = listOf(
                FreezeFrameEntry(did = 0x0105, rawBytes = byteArrayOf(0x50), label = "Coolant", value = "80 °C")
            )
        )
        val dtcs = listOf(testDtc(freezeFrame = ff))
        val json = JSONObject(DriveExportBuilder.buildDtcJson(dtcs))
        val code = json.getJSONArray("codes").getJSONObject(0)
        assertTrue(code.has("freezeFrame"))
        val ffJson = code.getJSONObject("freezeFrame")
        assertEquals(1, ffJson.getInt("recordNumber"))
        val entries = ffJson.getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals("Coolant", entries.getJSONObject(0).getString("label"))
    }

    @Test fun `dtc json omits freeze frame when absent`() {
        val dtcs = listOf(testDtc(freezeFrame = null))
        val json = JSONObject(DriveExportBuilder.buildDtcJson(dtcs))
        val code = json.getJSONArray("codes").getJSONObject(0)
        assertFalse(code.has("freezeFrame"))
    }

    @Test fun `dtc json multiple codes`() {
        val dtcs = listOf(
            testDtc(module = "PCM", code = "P0300"),
            testDtc(module = "BCM", code = "B1234"),
            testDtc(module = "ABS", code = "C0035")
        )
        val json = JSONObject(DriveExportBuilder.buildDtcJson(dtcs))
        assertEquals(3, json.getInt("totalCodes"))
        assertEquals(3, json.getJSONArray("codes").length())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Profile JSON tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `profile json has version 2`() {
        val json = JSONObject(DriveExportBuilder.buildProfileJson(testDrive(), listOf(testPoint())))
        assertEquals(2, json.getInt("version"))
    }

    @Test fun `profile json contains drive summary`() {
        val drive = testDrive(distanceKm = 42.5, maxSpeedKph = 180.0, aggressionScore = 65)
        val json = JSONObject(DriveExportBuilder.buildProfileJson(drive, listOf(testPoint())))
        val driveObj = json.getJSONObject("drive")
        assertEquals(42.5, driveObj.getDouble("distanceKm"), 0.01)
        assertEquals(180.0, driveObj.getDouble("maxSpeedKph"), 0.01)
        assertEquals(65, driveObj.getInt("aggressionScore"))
    }

    @Test fun `profile json contains thermal progression`() {
        val drive = testDrive(startOilTempC = 45.0, peakOilTempC = 115.0, endOilTempC = 105.0)
        val json = JSONObject(DriveExportBuilder.buildProfileJson(drive, listOf(testPoint())))
        val thermal = json.getJSONObject("thermalProgression")
        val oil = thermal.getJSONObject("oil")
        assertEquals(45.0, oil.getDouble("startC"), 0.01)
        assertEquals(115.0, oil.getDouble("peakC"), 0.01)
        assertEquals(105.0, oil.getDouble("endC"), 0.01)
    }

    @Test fun `profile json builds peak events`() {
        val points = listOf(
            testPoint(rpm = 3000, boostPsi = 10.0, lateralG = 0.3, speedKph = 80.0),
            testPoint(rpm = 6500, boostPsi = 21.0, lateralG = 1.1, speedKph = 180.0, timestamp = 1700000001000L)
        )
        val json = JSONObject(DriveExportBuilder.buildProfileJson(testDrive(), points))
        val peaks = json.getJSONArray("peaks")
        assertTrue(peaks.length() > 0)
        // Verify peaks contain the maximum values
        val peakTypes = (0 until peaks.length()).map { peaks.getJSONObject(it).getString("type") }
        assertTrue(peakTypes.contains("RPM"))
        assertTrue(peakTypes.contains("BOOST"))
    }

    @Test fun `profile json builds mode timeline`() {
        val points = listOf(
            testPoint(driveMode = "Normal", timestamp = 1700000000000L),
            testPoint(driveMode = "Normal", timestamp = 1700000001000L),
            testPoint(driveMode = "Sport", timestamp = 1700000002000L),
            testPoint(driveMode = "Sport", timestamp = 1700000003000L)
        )
        val drive = testDrive(startTime = 1700000000000L, endTime = 1700000003000L)
        val json = JSONObject(DriveExportBuilder.buildProfileJson(drive, points))
        val timeline = json.getJSONArray("modeTimeline")
        assertEquals(2, timeline.length())
        assertEquals("Normal", timeline.getJSONObject(0).getString("mode"))
        assertEquals("Sport", timeline.getJSONObject(1).getString("mode"))
    }

    @Test fun `profile json mode timeline handles single mode`() {
        val points = listOf(
            testPoint(driveMode = "Track", timestamp = 1700000000000L),
            testPoint(driveMode = "Track", timestamp = 1700000001000L)
        )
        val json = JSONObject(DriveExportBuilder.buildProfileJson(testDrive(), points))
        val timeline = json.getJSONArray("modeTimeline")
        assertEquals(1, timeline.length())
        assertEquals("Track", timeline.getJSONObject(0).getString("mode"))
    }

    @Test fun `profile json empty points produces empty arrays`() {
        val json = JSONObject(DriveExportBuilder.buildProfileJson(testDrive(), emptyList()))
        assertEquals(0, json.getJSONArray("peaks").length())
        assertEquals(0, json.getJSONArray("modeTimeline").length())
    }

    @Test fun `profile json peak events skip zero values`() {
        val points = listOf(
            testPoint(rpm = 0, boostPsi = 0.0, lateralG = 0.0, speedKph = 0.0)
        )
        val json = JSONObject(DriveExportBuilder.buildProfileJson(testDrive(), points))
        assertEquals(0, json.getJSONArray("peaks").length())
    }

    @Test fun `profile json peak events include GPS coords`() {
        val points = listOf(
            testPoint(lat = 52.52, lng = 13.405, rpm = 6000)
        )
        val json = JSONObject(DriveExportBuilder.buildProfileJson(testDrive(), points))
        val peaks = json.getJSONArray("peaks")
        val rpmPeak = (0 until peaks.length())
            .map { peaks.getJSONObject(it) }
            .first { it.getString("type") == "RPM" }
        assertEquals(52.52, rpmPeak.getDouble("lat"), 0.001)
        assertEquals(13.405, rpmPeak.getDouble("lng"), 0.001)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RaceChrono CSV tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `racechrono csv has expected columns`() {
        val csv = DriveExportBuilder.buildRaceChronoCsv(listOf(testPoint()))
        val header = csv.lines().first()
        assertTrue(header.contains("Timestamp (s)"))
        assertTrue(header.contains("Speed (m/s)"))
        assertTrue(header.contains("Lateral Acceleration (G)"))
        assertTrue(header.contains("RPM (rpm)"))
        assertTrue(header.contains("Throttle Position (%)"))
    }

    @Test fun `racechrono csv speed is in meters per second`() {
        val pt = testPoint(speedKph = 108.0) // 108 kph = 30 m/s
        val csv = DriveExportBuilder.buildRaceChronoCsv(listOf(pt))
        val dataLine = csv.lines().filter { it.isNotBlank() }[1]
        assertTrue(dataLine.contains("30.00"))
    }

    @Test fun `racechrono csv timestamp is relative seconds`() {
        val points = listOf(
            testPoint(timestamp = 1700000000000L),
            testPoint(timestamp = 1700000005000L) // +5s
        )
        val csv = DriveExportBuilder.buildRaceChronoCsv(points)
        val lines = csv.lines().filter { it.isNotBlank() }
        assertTrue(lines[1].startsWith("0.000,"))
        assertTrue(lines[2].startsWith("5.000,"))
    }

    @Test fun `racechrono csv empty points produces header only`() {
        val csv = DriveExportBuilder.buildRaceChronoCsv(emptyList())
        val lines = csv.lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size) // header only
    }

    @Test fun `racechrono csv has no schema version comment`() {
        val csv = DriveExportBuilder.buildRaceChronoCsv(listOf(testPoint()))
        assertFalse(csv.startsWith("#"))
    }

    @Test fun `racechrono csv column count matches data`() {
        val csv = DriveExportBuilder.buildRaceChronoCsv(listOf(testPoint()))
        val lines = csv.lines().filter { it.isNotBlank() }
        val headerCols = lines[0].split(",").size
        val dataCols = lines[1].split(",").size
        assertEquals(headerCols, dataCols)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TrackAddict CSV tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `trackaddict csv has expected columns`() {
        val csv = DriveExportBuilder.buildTrackAddictCsv(listOf(testPoint()))
        val header = csv.lines().first()
        assertTrue(header.contains("Speed (MPH)"))
        assertTrue(header.contains("Lateral Gs"))
        assertTrue(header.contains("RPM"))
        assertTrue(header.contains("Coolant Temp (F)"))
    }

    @Test fun `trackaddict csv speed is in mph`() {
        val pt = testPoint(speedKph = 160.934) // ~100 mph
        val csv = DriveExportBuilder.buildTrackAddictCsv(listOf(pt))
        val dataLine = csv.lines().filter { it.isNotBlank() }[1]
        assertTrue(dataLine.contains("100.0"))
    }

    @Test fun `trackaddict csv temps are in fahrenheit`() {
        val pt = testPoint(coolantTempC = 100.0) // 100°C = 212°F
        val csv = DriveExportBuilder.buildTrackAddictCsv(listOf(pt))
        val dataLine = csv.lines().filter { it.isNotBlank() }[1]
        assertTrue(dataLine.contains("212.0"))
    }

    @Test fun `trackaddict csv column count matches data`() {
        val csv = DriveExportBuilder.buildTrackAddictCsv(listOf(testPoint()))
        val lines = csv.lines().filter { it.isNotBlank() }
        val headerCols = lines[0].split(",").size
        val dataCols = lines[1].split(",").size
        assertEquals(headerCols, dataCols)
    }
}
