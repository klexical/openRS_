package com.openrs.dash

import com.openrs.dash.data.BoostSessionLog
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BoostSessionLog — rc.6 (#175): live window feeding the POWER boost sweep chart.
 */
class BoostSessionLogTest {

    private val sessionStart = 100_000L

    @Before fun setUp() = BoostSessionLog.reset(sessionStart)

    private fun vs(
        tipStamp: Long, actual: Double = 200.0,
        targetStamp: Long? = tipStamp, target: Double = 210.0,
        wgdcStamp: Long? = tipStamp, wgdc: Double = 55.0,
        rpm: Double = 4500.0
    ) = VehicleState(
        rpm = rpm, tipActualKpa = actual, tipDesiredKpa = target, wgdcDesired = wgdc,
        fieldLastUpdateMs = buildMap {
            put("tipActualKpa", tipStamp)
            targetStamp?.let { put("tipDesiredKpa", it) }
            wgdcStamp?.let { put("wgdcDesired", it) }
        }
    )

    @Test
    fun `one sample per fresh TIP decode, held values skipped`() {
        BoostSessionLog.onState(vs(101_000), 101_050)
        BoostSessionLog.onState(vs(101_000), 101_200)   // same decode
        val s = BoostSessionLog.samples.value
        assertEquals(1, s.size)
        assertEquals(200.0, s[0].actualKpa, 0.0)
        assertEquals(210.0, s[0].targetKpa!!, 0.0)
        assertEquals(55.0, s[0].wgdcPct!!, 0.0)
    }

    @Test
    fun `stale or missing target and wgdc ride along as null`() {
        BoostSessionLog.onState(vs(110_000, targetStamp = 105_000, wgdcStamp = null), 110_000)
        val s = BoostSessionLog.samples.value.single()
        assertNull(s.targetKpa)
        assertNull(s.wgdcPct)
    }

    @Test
    fun `TIP stamped before the session is rejected`() {
        BoostSessionLog.onState(vs(99_000), 100_500)
        assertTrue(BoostSessionLog.samples.value.isEmpty())
    }

    @Test
    fun `implausible TIP below 50 kPa is dropped`() {
        BoostSessionLog.onState(vs(101_000, actual = 14.7), 101_000)   // old /903.81 scale
        assertTrue(BoostSessionLog.samples.value.isEmpty())
    }

    @Test
    fun `window keeps only the last 90 s`() {
        BoostSessionLog.onState(vs(101_000), 101_000)
        BoostSessionLog.onState(vs(150_000), 150_000)
        BoostSessionLog.onState(vs(191_500), 191_500)   // 101_000 is now > 90 s old
        assertEquals(listOf(150_000L, 191_500L), BoostSessionLog.samples.value.map { it.tMs })
    }

    @Test
    fun `reset clears the window`() {
        BoostSessionLog.onState(vs(101_000), 101_000)
        BoostSessionLog.reset(200_000)
        assertTrue(BoostSessionLog.samples.value.isEmpty())
    }
}
