package com.openrs.dash

import com.openrs.dash.data.LaunchControl
import com.openrs.dash.data.LaunchControl.Status
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #289: the launch-control rule's boundaries (pure). The 1.5 s window is provisional (two 0x420 periods + margin). */
class LaunchControlRuleTest {
    private fun vs(flag: Boolean, at: Long?, connected: Boolean = true) = VehicleState(isConnected = connected, launchControlActive = flag,
        fieldLastUpdateMs = at?.let { mapOf(LaunchControl.TAG_420 to it) } ?: emptyMap())

    @Test fun `fresh up to 1500 ms inclusive, stale after`() {
        assertEquals(Status.ON, LaunchControl.status420(vs(true, 10_000L), 11_500L))
        assertEquals(Status.STALE, LaunchControl.status420(vs(true, 10_000L), 11_501L))
        assertTrue(LaunchControl.shown(vs(true, 10_000L), 11_500L))
        assertFalse(LaunchControl.shown(vs(true, 10_000L), 11_501L))
    }

    @Test fun `a stamp from the future, a dropped link and no stamp are never shown`() {
        assertEquals(Status.STALE, LaunchControl.status420(vs(true, 12_000L), 11_000L))
        assertEquals(Status.STALE, LaunchControl.status420(vs(true, 10_000L, connected = false), 10_100L))
        assertEquals(Status.NOT_RECEIVED, LaunchControl.status420(vs(true, null), 10_100L))
        assertEquals(Status.OFF, LaunchControl.status420(vs(false, 10_000L), 10_100L))
    }

    @Test fun `connection start clears both flags and both stamps, nothing else`() {
        val before = VehicleState(isConnected = true, launchControlActive = true, launchControlEngaged = true, lcArmed = true,
            fieldLastUpdateMs = mapOf(LaunchControl.TAG_420 to 1L, LaunchControl.TAG_225 to 1L, "lcArmed" to 1L, "driveMode" to 1L))
        val after = LaunchControl.clearedForNewConnection(before)
        assertFalse(after.launchControlActive); assertFalse(after.launchControlEngaged)
        assertEquals(setOf("lcArmed", "driveMode"), after.fieldLastUpdateMs.keys)
        assertEquals(true, after.lcArmed)
    }
}
