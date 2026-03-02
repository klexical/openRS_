package com.openrs.dash

import com.openrs.dash.can.ObdPids
import com.openrs.dash.data.VehicleState
import org.junit.Assert.*
import org.junit.Test

class ObdPidsTest {

    private val blank = VehicleState()

    @Test
    fun `all PIDs have valid request strings`() {
        for (pid in ObdPids.ALL) {
            assertTrue("${pid.name} request should start with 01 or 22",
                pid.requestStr.startsWith("01") || pid.requestStr.startsWith("22"))
        }
    }

    @Test
    fun `priority groups are correct`() {
        val p1 = ObdPids.ALL.filter { it.priority == 1 }
        val p6 = ObdPids.ALL.filter { it.priority == 6 }

        assertTrue("Should have priority 1 PIDs", p1.isNotEmpty())
        assertTrue("Should have priority 6 PIDs (TPMS)", p6.isNotEmpty())

        // TPMS should be priority 6
        assertTrue("TPMS should be priority 6",
            p6.any { it.name.contains("TPMS") })
    }

    @Test
    fun `BCM PIDs have correct header`() {
        val bcmPids = ObdPids.ALL.filter { it.header == ObdPids.HDR_BCM }
        assertTrue("Should have BCM PIDs", bcmPids.isNotEmpty())
        assertTrue("All BCM PIDs should be TPMS",
            bcmPids.all { it.name.contains("TPMS") })
    }

    @Test
    fun `cycle grouping works`() {
        // Cycle 1 should include priority 1 PIDs
        val cycle1 = ObdPids.getPidsForCycle(1)
        assertTrue(cycle1.any { it.priority == 1 })
        assertFalse(cycle1.any { it.priority == 2 }) // 1 % 2 != 0

        // Cycle 6 should include all priorities
        val cycle6 = ObdPids.getPidsForCycle(6)
        assertTrue(cycle6.any { it.priority == 1 })
        assertTrue(cycle6.any { it.priority == 2 })
        assertTrue(cycle6.any { it.priority == 3 })
        assertTrue(cycle6.any { it.priority == 6 })
    }

    @Test
    fun `grouped by header batches correctly`() {
        val groups = ObdPids.getPidsForCycleGrouped(6) // All PIDs active
        assertTrue("Should have broadcast group", groups.containsKey(ObdPids.HDR_BROADCAST))
        assertTrue("Should have PCM group", groups.containsKey(ObdPids.HDR_PCM))
        assertTrue("Should have BCM group", groups.containsKey(ObdPids.HDR_BCM))
    }

    @Test
    fun `AFR actual parse produces valid range`() {
        val afrPid = ObdPids.ALL.first { it.name == "AFR Actual" }
        // Stoich ~14.7:1 → raw value around 32768
        val data = byteArrayOf(0x80.toByte(), 0x00)
        val result = afrPid.parse(data, blank)
        assertTrue("AFR should be in reasonable range",
            result.afrActual in 5.0..25.0)
    }

    @Test
    fun `TPMS pressure parse`() {
        val tpmsPid = ObdPids.ALL.first { it.name == "TPMS LF Press" }
        // ~35 PSI: 35 / 0.145038 * 2.9 = ~699 raw → 0x02BB
        val data = byteArrayOf(0x02.toByte(), 0xBB.toByte())
        val result = tpmsPid.parse(data, blank)
        assertTrue("Tire pressure should be near 35 PSI",
            result.tirePressLF in 30.0..40.0)
    }

    @Test
    fun `total PID count is 33`() {
        assertEquals(33, ObdPids.ALL.size)
    }
}
