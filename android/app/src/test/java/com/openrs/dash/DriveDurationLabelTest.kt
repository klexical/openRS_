package com.openrs.dash

import com.openrs.dash.data.DriveEntity
import com.openrs.dash.ui.trip.driveDurationLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #325 — a drive left open by a process death is closed at the next launch (#262); with no stored points its end time is
 * its start time, a duration of 0 ms. TRIP's history row and the selected-drive overlay read "Active" for it, because the
 * duration formatter took 0 ms for a drive in progress. The schema's marker for a drive in progress is `endTime == 0`: only
 * that reads "Active"; a closed drive reads its duration, "0:00" when it has none. Pure, fixed times.
 */
class DriveDurationLabelTest {
    private val start = 1_700_000_000_000L

    @Test fun `a drive closed with no recorded time reads 0-00, not Active`() {
        assertEquals("0:00", driveDurationLabel(DriveEntity(startTime = start, endTime = start)))
    }

    @Test fun `a drive in progress (endTime 0) reads Active`() {
        assertEquals("Active", driveDurationLabel(DriveEntity(startTime = start, endTime = 0L)))
    }

    @Test fun `control - a closed drive reads its duration in minutes and in hours`() {
        assertEquals("1:05", driveDurationLabel(DriveEntity(startTime = start, endTime = start + 65_000L)))
        assertEquals("1:00:07", driveDurationLabel(DriveEntity(startTime = start, endTime = start + 3_607_000L)))
    }

    @Test fun `a closed drive under one second reads 0-00`() {
        assertEquals("0:00", driveDurationLabel(DriveEntity(startTime = start, endTime = start + 400L)))
    }
}
