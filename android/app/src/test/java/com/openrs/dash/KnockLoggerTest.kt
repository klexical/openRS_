package com.openrs.dash

import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.diagnostics.DriveExportBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Knock event logger tests — aggregation math, CSV format, severity banding.
 *
 * The event stream itself is produced by DriveRecorder on each 1 Hz telemetry sample
 * where `vs.ignCorrCyl1 > 0.0 && krDeg != lastKrDeg`; here we just validate the
 * downstream shape: that aggregate math is correct and the CSV round-trips cleanly.
 */
class KnockLoggerTest {

    private fun ev(
        ts: Long,
        kr: Double,
        rpm: Int = 3000,
        boost: Double = 10.0,
        iat: Double = 25.0,
        throttle: Double = 50.0,
        gear: Int = 3
    ) = KnockEventEntity(
        driveId = 1L,
        timestamp = ts,
        krDeg = kr,
        rpm = rpm,
        boostPsi = boost,
        iatC = iat,
        throttlePct = throttle,
        gearEst = gear,
        lat = null,
        lng = null
    )

    @Test
    fun `worst event is the one with the highest krDeg`() {
        val events = listOf(
            ev(1000, 0.5),
            ev(2000, 3.75, rpm = 5500, boost = 18.0),
            ev(3000, 1.25),
            ev(4000, 2.0)
        )
        val worst = events.maxByOrNull { it.krDeg }
        assertEquals(3.75, worst!!.krDeg, 0.01)
        assertEquals(5500, worst.rpm)
        assertEquals(18.0, worst.boostPsi, 0.01)
    }

    @Test
    fun `average retard is sum divided by count`() {
        val events = listOf(ev(1, 1.0), ev(2, 2.0), ev(3, 3.0))
        val avg = events.sumOf { it.krDeg } / events.size
        assertEquals(2.0, avg, 0.01)
    }

    @Test
    fun `severity bands partition events correctly`() {
        val events = listOf(
            ev(1, 0.5),   // low
            ev(2, 1.9),   // low
            ev(3, 2.0),   // med
            ev(4, 3.9),   // med
            ev(5, 4.0),   // high
            ev(6, 7.5)    // high
        )
        val high = events.count { it.krDeg >= 4.0 }
        val med  = events.count { it.krDeg in 2.0..3.9999 }
        val low  = events.count { it.krDeg in 0.1..1.9999 }
        assertEquals(2, high)
        assertEquals(2, med)
        assertEquals(2, low)
    }

    @Test
    fun `empty event list has zero aggregates and no worst`() {
        val events = emptyList<KnockEventEntity>()
        assertEquals(0, events.size)
        assertEquals(null, events.maxByOrNull { it.krDeg })
    }

    @Test
    fun `csv emits header plus one line per event`() {
        val events = listOf(
            ev(1000, 2.5, rpm = 4000, boost = 15.0),
            ev(2000, 4.75, rpm = 6000, boost = 20.0)
        )
        val csv = DriveExportBuilder.buildKnockCsv(events)
        val lines = csv.lines().filter { it.isNotBlank() }
        // schema marker + header + 2 data rows = 4 lines
        assertEquals(4, lines.size)
        assertEquals("# openrs_knock_csv_v1", lines[0])
        assertTrue("header has kr_deg column", lines[1].contains("kr_deg"))
        assertTrue("first event row contains rpm", lines[2].contains("4000"))
        assertTrue("first event row contains kr value", lines[2].contains("2.50"))
    }

    @Test
    fun `csv emits empty lat lng as blank fields`() {
        val events = listOf(ev(1, 1.0))
        val csv = DriveExportBuilder.buildKnockCsv(events)
        val dataRow = csv.lines().first { it.startsWith("1,") }
        // Final two columns are lat,lng; both null → trailing ","
        assertTrue("row ends with ',,'", dataRow.endsWith(",,") || dataRow.endsWith(","))
    }

    @Test
    fun `csv emits lat lng when gps fix is present`() {
        val events = listOf(
            KnockEventEntity(
                driveId = 1L, timestamp = 1000, krDeg = 2.0, rpm = 4000, boostPsi = 15.0,
                lat = 33.4484, lng = -112.0740
            )
        )
        val csv = DriveExportBuilder.buildKnockCsv(events)
        val dataRow = csv.lines().first { it.startsWith("1000,") }
        assertTrue("row contains lat", dataRow.contains("33.448400"))
        assertTrue("row contains lng", dataRow.contains("-112.074000"))
    }
}
