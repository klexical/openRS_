package com.openrs.dash

import com.openrs.dash.data.BoostSource
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.data.StoredBoost
import com.openrs.dash.ui.KnockScatterMath
import com.openrs.dash.ui.trip.ColorMode
import com.openrs.dash.ui.trip.legendLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #207 — what a stored boost value is, as TRIP's trend / compare / drive card / replay, the map legend and the knock
 * scatter read it (2026-10-29 corrections): genuine 0, no reading, CAN, TIP, mixed and legacy.
 */
class BoostProvenanceTest {
    private fun drive(peak: Double, src: String?, policy: String? = "rc7-boost-v3") =
        DriveEntity(id = 1, startTime = 0, endTime = 1, peakBoostPsi = peak, peakBoostSource = src, boostPolicy = policy)
    private fun pt(psi: Double, src: String?) = DrivePointEntity(driveId = 1, timestamp = 0, lat = 0.0, lng = 0.0, boostPsi = psi, boostSource = src)
    private fun knock(psi: Double, src: String?) = KnockEventEntity(driveId = 1, timestamp = 0, krDeg = 1.0, rpm = 4000, boostPsi = psi, boostSource = src)

    @Test fun `drive peak — genuine 0 is a reading, no reading is none, legacy keeps its value without a source`() {
        assertEquals(StoredBoost.Reading(0.0, BoostSource.CAN_0F8), StoredBoost.drivePeak(drive(0.0, "CAN_0F8")))
        assertEquals(StoredBoost.Unavailable, StoredBoost.drivePeak(drive(0.0, null)))           // v13+ with no reading
        assertEquals(StoredBoost.Legacy(7.5), StoredBoost.drivePeak(drive(7.5, null, policy = null)))
        assertEquals(StoredBoost.Unavailable, StoredBoost.drivePeak(drive(0.0, null, policy = null)))
        assertEquals("TIP", StoredBoost.drivePeak(drive(8.9, "TIP_033E")).label)
    }

    @Test fun `trend — only peaks of the latest drive's kind, no-reading drives left out rather than plotted as 0`() {
        // The 2026-10-29 summary screenshot: "PEAK TIP 8.9 PSI" above a trend labelled "PEAK BOOST" that mixed sources
        val drives = listOf(drive(0.0, "CAN_0F8"), drive(6.1, "TIP_033E"), drive(0.0, null), drive(5.0, null, policy = null),
            drive(8.9, "TIP_033E"))
        val (kind, series) = StoredBoost.peakTrend(drives)!!
        assertEquals("TIP", kind.label)
        assertEquals(listOf(6.1, 8.9), series)
        val (canKind, canSeries) = StoredBoost.peakTrend(drives + drive(0.0, "CAN_0F8"))!!
        assertEquals("BOOST", canKind.label); assertEquals(listOf(0.0, 0.0), canSeries)   // genuine zeros kept
        assertNull(StoredBoost.peakTrend(drives + drive(0.0, null)))                      // latest has no reading
        assertEquals(listOf(5.0, 4.0), StoredBoost.peakTrend(drives + drive(0.0, null, null) + drive(4.0, null, null))!!.second)
    }

    @Test fun `compare — a delta only between peaks of one kind`() {
        val tip = StoredBoost.drivePeak(drive(8.9, "TIP_033E")); val can = StoredBoost.drivePeak(drive(0.0, "CAN_0F8"))
        assertTrue(tip.sameKind(StoredBoost.drivePeak(drive(6.0, "TIP_033E"))))
        assertFalse(tip.sameKind(can))
        assertFalse(tip.sameKind(StoredBoost.drivePeak(drive(7.5, null, null))))
        assertFalse(StoredBoost.Unavailable.sameKind(StoredBoost.Unavailable))
    }

    @Test fun `primary source — the drive peak's measurement, none for legacy or no reading`() {
        assertEquals(BoostSource.TIP_033E, StoredBoost.primarySource(listOf(pt(0.0, "CAN_0F8"), pt(8.9, "TIP_033E"), pt(0.0, "NONE"))))
        assertEquals(BoostSource.CAN_0F8, StoredBoost.primarySource(listOf(pt(0.0, "CAN_0F8"), pt(0.0, "NONE"))))
        assertNull(StoredBoost.primarySource(listOf(pt(4.0, null))))
        assertEquals("TIP", legendLabel(ColorMode.BOOST, listOf(pt(0.0, "CAN_0F8"), pt(8.9, "TIP_033E"))))
        assertEquals("BOOST · SRC N/R", legendLabel(ColorMode.BOOST, listOf(pt(4.0, null))))
        assertEquals("SPD", legendLabel(ColorMode.SPEED, listOf(pt(4.0, null))))
    }

    @Test fun `knock scatter — one measurement on the boost axis, events without a reading are not drawn at 0`() {
        val events = listOf(knock(8.0, "TIP_033E"), knock(0.0, "NONE"), knock(0.0, "CAN_0F8"), knock(3.0, null))
        val axis = KnockScatterMath.axisSource(events)
        assertEquals(BoostSource.TIP_033E, axis)
        assertEquals(listOf(true, false, false, true), events.map { KnockScatterMath.plotted(it, axis) })
        assertEquals(StoredBoost.Unavailable, KnockScatterMath.boost(events[1]))
    }
}
