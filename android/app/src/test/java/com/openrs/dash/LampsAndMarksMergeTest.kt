package com.openrs.dash

import com.openrs.dash.data.Lamps
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.SessionMarks
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #199: an OBD reply is a copy of the live state it was parsed against, so it can be older than the live state when it
 * is merged. The session marks and the CAN-only SRS lamp must never come from it; the IPC lamps take the reply's value
 * when it holds one, else keep the live one.
 */
class LampsAndMarksMergeTest {

    @Test fun `an OBD merge cannot move a session mark back`() {
        val snapshot = VehicleState(session = SessionMarks(peakRpm = 4000.0, peakOilTempC = 90.0, tireStartLF = 31.0))
        val live = snapshot.copy(session = SessionMarks(peakRpm = 6500.0, peakOilTempC = 112.0, tireStartLF = 31.0))
        assertSame(live.session, ObdMerge.merge(live, snapshot).session)
    }

    @Test fun `an IPC lamp in the reply is taken - a reply without it keeps the live one`() {
        val live = VehicleState(lamps = Lamps(mil = false, abs = true))
        val merged = ObdMerge.merge(live, live.copy(lamps = Lamps(mil = true)))
        assertEquals(true, merged.lamps.mil)
        assertEquals("not in the reply → the live value", true, merged.lamps.abs)
        assertNull(merged.lamps.brake)
    }

    @Test fun `the SRS lamp is never taken from a reply - an older snapshot cannot turn it back on`() {
        val snapshot = VehicleState(lamps = Lamps(srs = true))               // what the reply was parsed against
        val live = snapshot.copy(lamps = Lamps(srs = false))                  // CAN turned the lamp off since
        assertEquals(false, ObdMerge.merge(live, snapshot).lamps.srs)
        val unknownLive = VehicleState()
        assertNull("nor set from a reply when CAN has said nothing", ObdMerge.merge(unknownLive, snapshot).lamps.srs)
    }

    @Test fun `a reply that changes no lamp leaves the lamps instance as it was`() {
        val live = VehicleState(lamps = Lamps(mil = false, srs = true))
        assertSame(live.lamps, ObdMerge.merge(live, live).lamps)
    }
}
