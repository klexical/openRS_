package com.openrs.dash

import com.openrs.dash.data.FreshnessGate
import com.openrs.dash.data.KnockCapture
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KnockCapture — rc.5 (#186 / #187): one event per cylinder per fresh KR decode,
 * retard positive, never from a pre-drive reading.
 */
class KnockCaptureTest {

    private val driveStart = 10_000L

    private fun vs(
        kr: DoubleArray,
        stamps: LongArray
    ) = VehicleState(
        rpm = 5200.0,
        ignCorrCyl1 = kr[0], ignCorrCyl2 = kr[1], ignCorrCyl3 = kr[2], ignCorrCyl4 = kr[3],
        fieldLastUpdateMs = KnockCapture.KR_TAGS.zip(stamps.toList())
            .filter { it.second > 0 }.toMap()
    )

    @Test
    fun `fresh retard on several cylinders yields one event per cylinder`() {
        val gate = FreshnessGate(driveStart)
        val s = vs(doubleArrayOf(2.5, 0.0, 4.25, 0.0), longArrayOf(11_000, 11_000, 11_000, 11_000))
        val events = KnockCapture.eventsFrom(s, gate, driveId = 7, now = 11_100, lat = null, lng = null)
        assertEquals(listOf(1, 3), events.map { it.cylinder })
        assertEquals(listOf(2.5, 4.25), events.map { it.krDeg })
        assertTrue(events.all { it.driveId == 7L && it.rpm == 5200 })
    }

    @Test
    fun `reading stamped before drive start is not logged`() {
        val gate = FreshnessGate(driveStart)
        val s = vs(doubleArrayOf(3.0, 0.0, 0.0, 0.0), longArrayOf(9_500, 0, 0, 0))
        assertTrue(KnockCapture.eventsFrom(s, gate, 1, 10_500, null, null).isEmpty())
    }

    @Test
    fun `same decode is logged once, identical value on next poll is logged again`() {
        val gate = FreshnessGate(driveStart)
        val first = vs(doubleArrayOf(1.5, 0.0, 0.0, 0.0), longArrayOf(11_000, 0, 0, 0))
        assertEquals(1, KnockCapture.eventsFrom(first, gate, 1, 11_100, null, null).size)
        // Next 1 Hz sample, no new poll yet → nothing
        assertEquals(0, KnockCapture.eventsFrom(first, gate, 1, 12_100, null, null).size)
        // New poll, same retard value → a second, genuine event
        val second = vs(doubleArrayOf(1.5, 0.0, 0.0, 0.0), longArrayOf(12_300, 0, 0, 0))
        assertEquals(1, KnockCapture.eventsFrom(second, gate, 1, 13_100, null, null).size)
    }

    @Test
    fun `negative or sub-threshold KR is not an event`() {
        val gate = FreshnessGate(driveStart)
        val s = vs(doubleArrayOf(-1.5, 0.05, 0.0, 0.1), longArrayOf(11_000, 11_000, 11_000, 11_000))
        val events = KnockCapture.eventsFrom(s, gate, 1, 11_100, null, null)
        assertEquals(listOf(4), events.map { it.cylinder })   // only 0.1° meets KNOCK_MIN_DEG
    }
}
