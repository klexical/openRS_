package com.openrs.dash

import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.CrashTelemetryBuffer
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DriveExportBuilder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Machine-readable exports must not depend on the phone's language (#211). Each export is built under en_US, pl_PL and
 * de_DE (decimal comma) and must keep header/row field alignment and parse back to the original values.
 */
class ExportLocaleTest {

    private val saved = Locale.getDefault()
    private val locales = listOf(Locale.US, Locale("pl", "PL"), Locale.GERMANY)

    @Before fun setUp() { Locale.setDefault(Locale.US) }
    @After fun tearDown() { Locale.setDefault(saved) }

    private fun eachLocale(block: (Locale) -> Unit) = locales.forEach { loc ->
        Locale.setDefault(loc)
        try { block(loc) } finally { Locale.setDefault(Locale.US) }
    }

    private val point = DrivePointEntity(
        driveId = 1, timestamp = 1_700_000_000_000L, lat = 52.520008, lng = 13.404954,
        speedKph = 100.25, rpm = 4500, gear = "3", boostPsi = 15.75, coolantTempC = 90.5, oilTempC = 105.5,
        ambientTempC = 21.5, fuelLevelPct = 40.4, lateralG = 0.2512, throttlePct = 62.5,
        wheelSpeedFL = 100.12, tirePressLF = 35.5, longitudinalG = -0.4321, brakePressure = 12.5, steeringAngle = -7.5,
        tipActualKpa = 180.5, boostTargetKpa = 182.5, wgdcPct = 55.5
    )

    private fun dataLines(csv: String) = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }

    private fun row(csv: String): Map<String, String> {
        val lines = dataLines(csv)
        val header = lines[0].split(",")
        val values = lines[1].split(",")
        assertEquals("field count (header vs row) under ${Locale.getDefault()}", header.size, values.size)
        return header.zip(values).toMap()
    }

    @Test fun `drive CSV keeps field alignment and values in every locale`() = eachLocale { loc ->
        val r = row(DriveExportBuilder.buildCsv(listOf(point)))
        assertEquals("$loc", 52.520008, r.getValue("lat").toDouble(), 1e-6)
        assertEquals("$loc", 13.404954, r.getValue("lng").toDouble(), 1e-6)
        assertEquals("$loc", 100.25, r.getValue("speed_kph").toDouble(), 1e-9)
        assertEquals("$loc", 15.75, r.getValue("boost_psi").toDouble(), 1e-9)
        assertEquals("$loc", 90.5, r.getValue("coolant_c").toDouble(), 1e-9)
        assertEquals("$loc", 40.4, r.getValue("fuel_pct").toDouble(), 1e-9)
        assertEquals("$loc", -0.4321, r.getValue("longitudinal_g").toDouble(), 1e-9)
        assertEquals("$loc", 4500, r.getValue("rpm").toInt())
    }

    @Test fun `no-GPS drive CSV keeps empty coordinates aligned in every locale`() = eachLocale { loc ->
        val r = row(DriveExportBuilder.buildCsv(listOf(point), hasGps = false))
        assertEquals("$loc", "", r.getValue("lat"))
        assertEquals("$loc", "", r.getValue("lng"))
        assertEquals("$loc", 100.25, r.getValue("speed_kph").toDouble(), 1e-9)
    }

    @Test fun `knock CSV keeps field alignment and values in every locale`() = eachLocale { loc ->
        val ev = KnockEventEntity(driveId = 1, timestamp = 1_700_000_000_000L, krDeg = 2.25, rpm = 5200, boostPsi = 18.5,
            iatC = 31.5, throttlePct = 99.5, gearEst = 3, lat = 52.520008, lng = 13.404954, cylinder = 2)
        val r = row(DriveExportBuilder.buildKnockCsv(listOf(ev)))
        assertEquals("$loc", 2.25, r.getValue("kr_deg").toDouble(), 1e-9)
        assertEquals("$loc", 18.5, r.getValue("boost_psi").toDouble(), 1e-9)
        assertEquals("$loc", 52.520008, r.getValue("lat").toDouble(), 1e-6)
        assertEquals("$loc", 2, r.getValue("cylinder").toInt())
    }

    @Test fun `RaceChrono and TrackAddict CSVs keep field alignment in every locale`() = eachLocale { loc ->
        val rc = row(DriveExportBuilder.buildRaceChronoCsv(listOf(point, point.copy(timestamp = point.timestamp + 1000))))
        assertEquals("$loc", 52.520008, rc.getValue("Latitude (deg)").toDouble(), 1e-6)
        assertEquals("$loc", 100.25, rc.getValue("Speed (kph)").toDouble(), 0.01)
        val ta = row(DriveExportBuilder.buildTrackAddictCsv(listOf(point)))
        assertEquals("$loc", 13.404954, ta.getValue("Longitude").toDouble(), 1e-6)
        assertTrue("$loc", "Boost (PSI)" !in ta.keys)                               // pressure column removed (#207)
    }

    @Test fun `GPX parses as XML with numeric fields in every locale`() = eachLocale { loc ->
        val drive = DriveEntity(id = 1, startTime = point.timestamp, endTime = point.timestamp + 60_000)
        val gpx = DriveExportBuilder.buildGpx(drive, listOf(point), "20231114_221320")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(gpx.byteInputStream())
        val pt = doc.getElementsByTagNameNS("*", "trkpt").item(0)
        assertEquals("$loc", 52.520008, pt.attributes.getNamedItem("lat").nodeValue.toDouble(), 1e-6)
        assertEquals("$loc", 100.25, doc.getElementsByTagNameNS("*", "speed").item(0).textContent.toDouble(), 0.051)
        assertEquals("$loc", 15.75, doc.getElementsByTagNameNS("*", "boostPsi").item(0).textContent.toDouble(), 0.006)
        assertEquals("$loc", -0.432, doc.getElementsByTagNameNS("*", "longitudinalG").item(0).textContent.toDouble(), 0.001)
    }

    @Test fun `crash telemetry JSON parses with numeric values in every locale`() = eachLocale { loc ->
        CrashTelemetryBuffer.push(VehicleState(rpm = 762.5, speedKph = 12.5, boostKpa = 96.5, lateralG = -0.125, batteryVoltage = 14.6))
        val f = File.createTempFile("crash", ".json")
        try {
            CrashTelemetryBuffer.flushToFile(f, RuntimeException("boom"), "main", "2.2.7")
            val snaps = JSONObject(f.readText()).getJSONArray("snapshots")
            val last = snaps.getJSONObject(snaps.length() - 1)
            assertEquals("$loc", 762.5, last.getDouble("rpm"), 1e-9)
            assertEquals("$loc", -0.125, last.getDouble("latG"), 1e-9)
            assertEquals("$loc", 14.6, last.getDouble("battV"), 1e-9)
        } finally { f.delete() }
    }

    @Test fun `SLCAN log timestamps use a decimal point in every locale`() = eachLocale { loc ->
        val line = DiagnosticLogger.buildSlcanLine(90L, 0x090, "0000C1FF617D0000")
        assertEquals("$loc", "(     0.090) can0 090#0000C1FF617D0000\n", line)
        assertTrue(Regex("""^\(\s*\d+\.\d{3}\) can0 [0-9A-F]{3}#[0-9A-F]*\n$""").matches(line))
    }

    /**
     * `OPENRS_LOCALE_SAMPLES=<dir>` writes a synthetic three-point drive exported under pl_PL (drive CSV, GPX, knock
     * CSV) — the Sapphire fixture that proves a corrected decimal-comma-locale export imports cleanly. No-op otherwise.
     */
    @Test fun `write pl_PL export sample when requested`() {
        val dir = System.getenv("OPENRS_LOCALE_SAMPLES") ?: return
        Locale.setDefault(Locale("pl", "PL"))
        try {
            val ts = "20231114_221320"
            val pts = (0 until 3).map { i ->
                point.copy(timestamp = point.timestamp + i * 1000L, rpm = 3000 + i * 500, speedKph = 50.25 + i * 10,
                    lat = point.lat + i * 0.0001, lng = point.lng + i * 0.0001)
            }
            val drive = DriveEntity(id = 1, startTime = pts.first().timestamp, endTime = pts.last().timestamp)
            val ev = KnockEventEntity(driveId = 1, timestamp = pts[1].timestamp, krDeg = 1.5, rpm = 3500, boostPsi = 12.25,
                iatC = 25.5, throttlePct = 80.5, gearEst = 3, lat = pts[1].lat, lng = pts[1].lng, cylinder = 1)
            File(dir).mkdirs()
            File(dir, "drive_$ts.csv").writeText(DriveExportBuilder.buildCsv(pts))
            File(dir, "drive_$ts.gpx").writeText(DriveExportBuilder.buildGpx(drive, pts, ts))
            File(dir, "drive_knock_$ts.csv").writeText(DriveExportBuilder.buildKnockCsv(listOf(ev)))
        } finally { Locale.setDefault(Locale.US) }
    }
}
