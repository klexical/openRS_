package com.openrs.dash

import com.openrs.dash.data.PerformanceTimer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PerformanceTimerTest {

    @Before
    fun setUp() {
        PerformanceTimer.resetSession()
    }

    // ── State machine basics ────────────────────────────────────────────────

    @Test
    fun `initial state is IDLE`() {
        assertEquals(PerformanceTimer.State.IDLE, PerformanceTimer.state.value)
        assertNull(PerformanceTimer.result.value)
        assertNull(PerformanceTimer.best60Ms.value)
        assertNull(PerformanceTimer.best100Ms.value)
    }

    @Test
    fun `arm transitions IDLE to ARMED`() {
        val armed = PerformanceTimer.arm()
        assertTrue(armed)
        assertEquals(PerformanceTimer.State.ARMED, PerformanceTimer.state.value)
    }

    @Test
    fun `arm from FINISHED succeeds`() {
        PerformanceTimer.arm()
        // Run to 60
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0) // starts
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0) // crosses 60 mph
        PerformanceTimer.onSpeedUpdate(161.0, 7000.0, 22.0) // crosses 100 mph → FINISHED
        assertEquals(PerformanceTimer.State.FINISHED, PerformanceTimer.state.value)

        val rearmed = PerformanceTimer.arm()
        assertTrue(rearmed)
        assertEquals(PerformanceTimer.State.ARMED, PerformanceTimer.state.value)
    }

    @Test
    fun `arm from RUNNING fails`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0) // starts
        assertEquals(PerformanceTimer.State.RUNNING, PerformanceTimer.state.value)

        val rearmed = PerformanceTimer.arm()
        assertFalse(rearmed)
        assertEquals(PerformanceTimer.State.RUNNING, PerformanceTimer.state.value)
    }

    @Test
    fun `cancel returns to IDLE`() {
        PerformanceTimer.arm()
        PerformanceTimer.cancel()
        assertEquals(PerformanceTimer.State.IDLE, PerformanceTimer.state.value)
    }

    @Test
    fun `cancel from RUNNING returns to IDLE`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0) // starts
        assertEquals(PerformanceTimer.State.RUNNING, PerformanceTimer.state.value)
        PerformanceTimer.cancel()
        assertEquals(PerformanceTimer.State.IDLE, PerformanceTimer.state.value)
    }

    // ── Armed → Running transition ──────────────────────────────────────────

    @Test
    fun `movement above 3 kph starts timer`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0)
        assertEquals(PerformanceTimer.State.RUNNING, PerformanceTimer.state.value)
    }

    @Test
    fun `movement below 3 kph stays armed`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(2.9, 4000.0, 10.0)
        assertEquals(PerformanceTimer.State.ARMED, PerformanceTimer.state.value)
    }

    @Test
    fun `rolling start above 5 kph cancels`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(5.1, 4000.0, 10.0)
        assertEquals(PerformanceTimer.State.IDLE, PerformanceTimer.state.value)
    }

    @Test
    fun `exactly 5 kph stays armed`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(5.0, 4000.0, 10.0)
        // 5.0 is not > 5.0 (ARM_MAX), so should stay armed
        // But 5.0 >= 3.0 (START_THRESHOLD), so it should start
        assertEquals(PerformanceTimer.State.RUNNING, PerformanceTimer.state.value)
    }

    // ── 0-60 split ──────────────────────────────────────────────────────────

    @Test
    fun `crossing 60 mph captures split`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0) // start
        val (sixty, _) = PerformanceTimer.onSpeedUpdate(96.6, 6500.0, 20.0) // 60 mph = 96.56 kph
        assertTrue("60 mph split should be captured", sixty)
    }

    @Test
    fun `below 60 mph does not capture split`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0) // start
        val (sixty, _) = PerformanceTimer.onSpeedUpdate(90.0, 6000.0, 18.0) // ~56 mph
        assertFalse(sixty)
    }

    @Test
    fun `60 split captured only once`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0) // start
        val (sixty1, _) = PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0) // first 60 crossing
        assertTrue(sixty1)
        val (sixty2, _) = PerformanceTimer.onSpeedUpdate(98.0, 6600.0, 20.5) // still above 60
        assertFalse("60 split should not be re-captured", sixty2)
    }

    // ── 0-100 finish ────────────────────────────────────────────────────────

    @Test
    fun `crossing 100 mph finishes run`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0) // start
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0) // 60 mph
        val (_, hundred) = PerformanceTimer.onSpeedUpdate(161.0, 7000.0, 22.0) // 100 mph = 160.93 kph
        assertTrue("100 mph should finish", hundred)
        assertEquals(PerformanceTimer.State.FINISHED, PerformanceTimer.state.value)
        assertNotNull(PerformanceTimer.result.value)
    }

    @Test
    fun `result contains 60 and 100 splits`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0)
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0)
        PerformanceTimer.onSpeedUpdate(161.0, 7000.0, 22.0)
        val result = PerformanceTimer.result.value
        assertNotNull(result)
        assertTrue("zeroTo60Ms should be non-negative", result!!.zeroTo60Ms >= 0)
        assertNotNull("zeroTo100Ms should be present", result.zeroTo100Ms)
        assertTrue("zeroTo100Ms should be >= zeroTo60Ms", result.zeroTo100Ms!! >= result.zeroTo60Ms)
    }

    // ── finishAt60 ──────────────────────────────────────────────────────────

    @Test
    fun `finishAt60 after 60 captured produces result`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0)
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0) // captures 60
        PerformanceTimer.finishAt60()
        assertEquals(PerformanceTimer.State.FINISHED, PerformanceTimer.state.value)
        val result = PerformanceTimer.result.value
        assertNotNull(result)
        assertTrue(result!!.zeroTo60Ms >= 0)
        assertNull("Should not have 100 split", result.zeroTo100Ms)
    }

    @Test
    fun `finishAt60 before 60 captured is no-op`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0) // start, no 60 yet
        PerformanceTimer.finishAt60()
        assertEquals(PerformanceTimer.State.RUNNING, PerformanceTimer.state.value)
        assertNull(PerformanceTimer.result.value)
    }

    @Test
    fun `finishAt60 when not RUNNING is no-op`() {
        PerformanceTimer.arm()
        PerformanceTimer.finishAt60() // still ARMED, not RUNNING
        assertEquals(PerformanceTimer.State.ARMED, PerformanceTimer.state.value)
    }

    // ── Peak tracking ───────────────────────────────────────────────────────

    @Test
    fun `result captures peak RPM across run`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0)
        PerformanceTimer.onSpeedUpdate(50.0, 7200.0, 18.0) // peak RPM
        PerformanceTimer.onSpeedUpdate(80.0, 6000.0, 20.0) // lower RPM
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 22.0) // 60 split
        PerformanceTimer.onSpeedUpdate(161.0, 7000.0, 21.0) // finish
        val result = PerformanceTimer.result.value
        assertNotNull(result)
        assertEquals(7200.0, result!!.peakRpm, 0.01)
    }

    @Test
    fun `result captures peak boost across run`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 5.0) // start
        PerformanceTimer.onSpeedUpdate(50.0, 6000.0, 22.5) // peak boost
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0) // 60
        PerformanceTimer.onSpeedUpdate(161.0, 7000.0, 18.0) // finish
        val result = PerformanceTimer.result.value
        assertEquals(22.5, result!!.peakBoostPsi, 0.01)
    }

    @Test
    fun `result captures launch RPM`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4500.0, 10.0) // launch at 4500 RPM
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0) // 60
        PerformanceTimer.onSpeedUpdate(161.0, 7000.0, 22.0) // finish
        val result = PerformanceTimer.result.value
        assertEquals(4500.0, result!!.launchRpm, 0.01)
    }

    // ── Session bests ───────────────────────────────────────────────────────

    @Test
    fun `best60 updated on first run`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0)
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0) // 60
        assertNotNull(PerformanceTimer.best60Ms.value)
    }

    @Test
    fun `best100 updated on first run`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0)
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0) // 60
        PerformanceTimer.onSpeedUpdate(161.0, 7000.0, 22.0) // 100
        assertNotNull(PerformanceTimer.best100Ms.value)
    }

    @Test
    fun `resetSession clears everything`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0)
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0)
        PerformanceTimer.onSpeedUpdate(161.0, 7000.0, 22.0)
        assertNotNull(PerformanceTimer.best60Ms.value)
        assertNotNull(PerformanceTimer.best100Ms.value)

        PerformanceTimer.resetSession()
        assertEquals(PerformanceTimer.State.IDLE, PerformanceTimer.state.value)
        assertNull(PerformanceTimer.result.value)
        assertNull(PerformanceTimer.best60Ms.value)
        assertNull(PerformanceTimer.best100Ms.value)
    }

    // ── Speed conversion ────────────────────────────────────────────────────

    @Test
    fun `currentSpeedMph tracks mph conversion`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(100.0, 5000.0, 15.0)
        assertEquals(62.137, PerformanceTimer.currentSpeedMph.value, 0.01)
    }

    // ── Updates in non-active states ────────────────────────────────────────

    @Test
    fun `onSpeedUpdate in IDLE state returns false-false`() {
        val (sixty, hundred) = PerformanceTimer.onSpeedUpdate(100.0, 5000.0, 15.0)
        assertFalse(sixty)
        assertFalse(hundred)
    }

    @Test
    fun `onSpeedUpdate in FINISHED state returns false-false`() {
        PerformanceTimer.arm()
        PerformanceTimer.onSpeedUpdate(3.1, 4000.0, 10.0)
        PerformanceTimer.onSpeedUpdate(97.0, 6500.0, 20.0)
        PerformanceTimer.onSpeedUpdate(161.0, 7000.0, 22.0)
        assertEquals(PerformanceTimer.State.FINISHED, PerformanceTimer.state.value)

        val (sixty, hundred) = PerformanceTimer.onSpeedUpdate(200.0, 7500.0, 25.0)
        assertFalse(sixty)
        assertFalse(hundred)
    }
}
