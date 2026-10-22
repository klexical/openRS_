package com.openrs.dash

import com.openrs.dash.data.KnockCapture
import com.openrs.dash.data.KnockSessionLog
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KnockSessionLog — rc.6 (#174): knock capture whenever connected, without recording.
 */
class KnockSessionLogTest {

    private val sessionStart = 50_000L

    @Before fun setUp() = KnockSessionLog.reset(sessionStart)

    private fun vs(kr1: Double, stamp: Long, rpm: Double = 5000.0, throttle: Double = 100.0) = VehicleState(
        rpm = rpm, throttlePct = throttle, throttleHasSource = true, ignCorrCyl1 = kr1,
        fieldLastUpdateMs = mapOf(KnockCapture.KR_TAGS[0] to stamp)
    )

    @Test
    fun `fresh retard is logged with driveId 0 and once per decode`() {
        KnockSessionLog.onState(vs(2.0, 51_000), 51_100)
        KnockSessionLog.onState(vs(2.0, 51_000), 51_300)   // same decode, held value
        val events = KnockSessionLog.events.value
        assertEquals(1, events.size)
        assertEquals(0L, events[0].driveId)
        assertEquals(2.0, events[0].krDeg, 0.0)
    }

    @Test
    fun `reading from before the session started is ignored`() {
        KnockSessionLog.onState(vs(3.0, 49_000), 50_500)
        assertTrue(KnockSessionLog.events.value.isEmpty())
    }

    @Test
    fun `pulls are counted and attributed from the same feed`() {
        var t = 51_000L
        while (t <= 54_000) { KnockSessionLog.onState(vs(0.0, t), t); t += 1_000 }
        KnockSessionLog.onState(vs(1.5, 54_500), 54_500)
        assertEquals(KnockSessionLog.Pulls(total = 1, knocked = 1), KnockSessionLog.pulls.value)
    }

    @Test
    fun `log is capped to the newest events`() {
        repeat(KnockSessionLog.MAX_EVENTS + 5) { i ->
            val ts = 51_000L + i
            KnockSessionLog.onState(vs(1.0 + i * 0.001, ts, rpm = 800.0, throttle = 0.0), ts)
        }
        val events = KnockSessionLog.events.value
        assertEquals(KnockSessionLog.MAX_EVENTS, events.size)
        assertEquals(51_005L, events.first().timestamp)
    }

    @Test
    fun `reset clears events and pulls`() {
        KnockSessionLog.onState(vs(2.0, 51_000), 51_000)
        KnockSessionLog.reset(60_000)
        assertTrue(KnockSessionLog.events.value.isEmpty())
        assertEquals(KnockSessionLog.Pulls(), KnockSessionLog.pulls.value)
    }

    @Test
    fun `pulls are detected from the pedal when the car has no throttle frame`() {
        var t = 51_000L
        while (t <= 54_000) {
            KnockSessionLog.onState(vs(0.0, t, throttle = 0.0).copy(throttleHasSource = false, accelPedalPct = 100.0), t)
            t += 1_000
        }
        assertEquals(1, KnockSessionLog.pulls.value.total)
    }
}
