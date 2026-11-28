package com.openrs.dash

import com.openrs.dash.data.DriveEntity
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.trip.deleteTripTitle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * #631: the delete confirmation names the drive (the owner's choice): the history list's date label for its start and
 * its distance in the chosen unit, each kept whole on one line (no-break spaces).
 */
class DeleteTripTitleTest {
    private var tz: TimeZone? = null
    private var locale: Locale? = null

    @Before fun setUp() {
        tz = TimeZone.getDefault(); locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC")); Locale.setDefault(Locale.US)
    }

    @After fun tearDown() { tz?.let { TimeZone.setDefault(it) }; locale?.let { Locale.setDefault(it) } }

    private companion object {
        const val NOV_27_NOON = 1_795_780_800_000L          // 2026-11-27 12:00 UTC
        const val DAY = 86_400_000L
    }

    private val drive = DriveEntity(startTime = NOV_27_NOON, endTime = NOV_27_NOON + 1_800_000L, distanceKm = 20.0)

    @Test fun `an older drive is named by its date and distance in miles`() =
        assertEquals("Delete the drive from Nov\u00A027, 12.4\u00A0mi?", deleteTripTitle(drive, UserPrefs(), NOV_27_NOON + 3 * DAY))

    @Test fun `kilometres when the unit setting says so`() =
        assertEquals("Delete the drive from Nov\u00A027, 20.0\u00A0km?",
            deleteTripTitle(drive, UserPrefs(speedUnit = "KPH"), NOV_27_NOON + 3 * DAY))

    @Test fun `today and yesterday as the list labels them, in lower case`() {
        assertEquals("Delete the drive from today, 12.4\u00A0mi?", deleteTripTitle(drive, UserPrefs(), NOV_27_NOON + 3_600_000L))
        assertEquals("Delete the drive from yesterday, 12.4\u00A0mi?", deleteTripTitle(drive, UserPrefs(), NOV_27_NOON + DAY))
    }
}
