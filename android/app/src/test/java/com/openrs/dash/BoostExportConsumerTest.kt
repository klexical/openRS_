package com.openrs.dash

import com.openrs.dash.data.DriveBookmarkEntity
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.LapEntity
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.ui.trip.ColorMode
import com.openrs.dash.ui.trip.pointColor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #207 — recorded boost through the real export builders and map colouring (2026-10-29 corrections): genuine 0,
 * no reading, CAN, TIP, mixed sources and legacy rows. Unavailable must never become a measured 0, a genuine 0 must
 * stay, and CAN / TIP must never become one peak or one series. Only APIs that existed before the fix, so the same
 * file runs against the previous build (failing-before evidence).
 */
class BoostExportConsumerTest {
    private val v3 = "rc7-boost-v3"
    private fun drive(peak: Double, src: String?, policy: String?) =
        DriveEntity(id = 1, startTime = 0, endTime = 60_000, peakBoostPsi = peak, peakBoostSource = src, boostPolicy = policy)
    private fun pt(t: Long, psi: Double, src: String?) =
        DrivePointEntity(driveId = 1, timestamp = t, lat = 50.0 + t / 1e7, lng = 19.0, boostPsi = psi, boostSource = src, baroKpa = 96.5)

    /** A drive that switched source: TIP while the engine ran, CAN 0x0F8 at idle (genuine 0), one point with none. */
    private val mixed = listOf(pt(0, 0.0, "CAN_0F8"), pt(1_000, 8.9, "TIP_033E"), pt(2_000, 0.0, "NONE"),
        pt(3_000, 3.1, "TIP_033E"), pt(4_000, 0.0, "CAN_0F8"))

    private fun boostPeaks(json: JSONObject) = (0 until json.getJSONArray("peaks").length())
        .map { json.getJSONArray("peaks").getJSONObject(it) }.filter { it.getString("type") == "BOOST" }

    @Test fun `profile JSON — one BOOST peak per source, a genuine 0 included, never one max across sources`() {
        val json = JSONObject(DriveExportBuilder.buildProfileJson(drive(8.9, "TIP_033E", v3), mixed))
        val peaks = boostPeaks(json).associate { it.getString("source") to it.getDouble("value") }
        assertEquals(mapOf("TIP_033E" to 8.9, "CAN_0F8" to 0.0), peaks)
    }

    @Test fun `profile JSON — a drive recorded under the rule with no reading has no peak, not 0`() {
        val pts = listOf(pt(0, 0.0, "NONE"), pt(1_000, 0.0, "NONE"))
        val json = JSONObject(DriveExportBuilder.buildProfileJson(drive(0.0, null, v3), pts))
        assertFalse(json.getJSONObject("drive").toString(), json.getJSONObject("drive").has("peakBoostPsi"))
        assertTrue(boostPeaks(json).isEmpty())
    }

    @Test fun `profile JSON — legacy drive keeps its value as recorded and claims no source`() {
        val pts = listOf(pt(0, 4.2, null), pt(1_000, 7.5, null))
        val json = JSONObject(DriveExportBuilder.buildProfileJson(drive(7.5, null, null), pts))
        assertEquals(7.5, json.getJSONObject("drive").getDouble("peakBoostPsi"), 1e-9)
        assertFalse(json.getJSONObject("drive").has("peakBoostSource"))
        val p = boostPeaks(json).single()
        assertEquals(7.5, p.getDouble("value"), 1e-9); assertFalse(p.has("source"))
    }

    @Test fun `profile JSON — bookmarks, laps and perf runs carry no unnamed boost`() {
        val json = JSONObject(DriveExportBuilder.buildProfileJson(drive(8.9, "TIP_033E", v3), mixed,
            bookmarks = listOf(DriveBookmarkEntity(driveId = 1, timestamp = 0, lat = 0.0, lng = 0.0, boostPsi = 0.0)),
            laps = listOf(LapEntity(sessionId = 1, lapNumber = 1, lapTimeMs = 60_000, peakBoostPsi = 7.0)),
            perfRuns = listOf(PerfRunEntity(timestamp = 0, zeroTo60Ms = 5_000, peakBoostPsi = 7.0))))
        assertFalse(json.getJSONArray("bookmarks").getJSONObject(0).has("boostPsi"))
        assertFalse(json.getJSONArray("laps").getJSONObject(0).has("peakBoostPsi"))
        assertFalse(json.getJSONArray("perfRuns").getJSONObject(0).has("peakBoostPsi"))
    }

    @Test fun `summary text — no reading says so instead of 0 PSI, and the rule text matches the drive policy`() {
        val none = DriveExportBuilder.buildSummary(drive(0.0, null, v3), listOf(pt(0, 0.0, "NONE")))
        assertFalse(none, none.contains("Peak Boost  : 0.0 PSI"))
        assertTrue(none, none.contains("no boost reading recorded"))
        assertFalse(none, none.contains("wasn't tracking boost"))
        val tip = DriveExportBuilder.buildSummary(drive(8.9, "TIP_033E", v3), mixed)
        assertTrue(tip, tip.contains("8.9 PSI (TIP 0x033E"))
        val legacy = DriveExportBuilder.buildSummary(drive(7.5, null, null), listOf(pt(0, 7.5, null)))
        assertTrue(legacy, legacy.contains("7.5 PSI (source not recorded)"))
    }

    @Test fun `RaceChrono and TrackAddict carry no pressure column — it could only say Boost — the summary says why`() {
        // Follow-up 2026-10-29: the 2026-10-29 version wrote TIP into "Boost Pressure (psi)" / "Boost (PSI)"
        for (csv in listOf(DriveExportBuilder.buildRaceChronoCsv(mixed), DriveExportBuilder.buildTrackAddictCsv(mixed))) {
            val header = csv.lines()[0]
            assertFalse(header, header.contains("Boost", ignoreCase = true))
            assertEquals(header.split(",").size, csv.lines()[1].split(",").size)
        }
        val summary = DriveExportBuilder.buildSummary(drive(8.9, "TIP_033E", v3), mixed)
        assertTrue(summary, summary.contains("RaceChrono / TrackAddict CSVs carry no pressure column"))
        // the native drive CSV keeps every reading and its source
        val native = DriveExportBuilder.buildCsv(mixed).lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val h = native[0].split(","); val iB = h.indexOf("boost_psi"); val iS = h.indexOf("boost_source")
        assertEquals(listOf("0.000" to "CAN_0F8", "8.900" to "TIP_033E", "" to "NONE", "3.100" to "TIP_033E", "0.000" to "CAN_0F8"),
            native.drop(1).map { it.split(",").let { r -> r[iB] to r[iS] } })
    }

    @Test fun `GPX — each boost value names its source`() {
        val gpx = DriveExportBuilder.buildGpx(drive(8.9, "TIP_033E", v3), mixed, "20261029_120000")
        assertEquals(4, Regex("<openrs:boostPsi>").findAll(gpx).count())                 // not for the NONE point
        assertEquals(2, Regex("<openrs:boostSource>TIP_033E</openrs:boostSource>").findAll(gpx).count())
        assertEquals(2, Regex("<openrs:boostSource>CAN_0F8</openrs:boostSource>").findAll(gpx).count())
    }

    @Test fun `map BOOST colouring — one source coloured, the other source and no reading dimmed`() {
        // Drawn by DriveMap through buildColorSegments → pointColor(point, mode, primarySource)
        val tipLow = pointColor(mixed[3], ColorMode.BOOST, com.openrs.dash.data.BoostSource.TIP_033E)
        val canZero = pointColor(mixed[0], ColorMode.BOOST, com.openrs.dash.data.BoostSource.TIP_033E)
        val none = pointColor(mixed[2], ColorMode.BOOST, com.openrs.dash.data.BoostSource.TIP_033E)
        assertEquals(com.openrs.dash.ui.Dim, canZero)
        assertEquals(com.openrs.dash.ui.Dim, none)
        assertNotEquals(com.openrs.dash.ui.Dim, tipLow)
    }
}
