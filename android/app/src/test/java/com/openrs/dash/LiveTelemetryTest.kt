package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.BoostSession
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.Lamps
import com.openrs.dash.data.LaunchControl
import com.openrs.dash.data.LiveTelemetry
import com.openrs.dash.data.PerformanceTimer
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Live telemetry through its own interface (android/docs/adr/0001-live-telemetry-single-writer.md): the reset events
 * (new connection, RETRY's polled blanking, user reset, peaks, demo replay / exit) and the feeds (a decoded CAN frame, an
 * OBD reply, the knock count, a finished performance run). Plain JVM, wall clock passed in.
 */
class LiveTelemetryTest {

    private var now = 1_000_000L
    private val thermalCalls = mutableListOf<String>()
    private val thermal = object : LiveTelemetry.Thermal {
        override fun newConnection() { thermalCalls += "newConnection" }
        override fun userReset() { thermalCalls += "userReset" }
    }
    private val lt = LiveTelemetry(wallClock = { now }, thermal = thermal)

    @Before fun freshFigures() {
        FuelEconomy.reset(); PerformanceTimer.resetSession(); BoostSession.reset()
    }

    /** A state "held from the previous link": readings of every kind, a peak, link up. */
    private fun held() = VehicleState(
        isConnected = true,
        rpm = 3000.0, speedKph = 50.0, coolantTempC = 90.0, oilTempC = 100.0,
        escStatus = EscStatus.PARTIAL, lamps = Lamps(mil = false, srs = true), engineState360 = 0xB2,
        launchControlActive = true, launchControlEngaged = true,
        tirePressLF = 2.4, tirePressRF = 2.4, tirePressLR = 2.3, tirePressRR = 2.3,
        rduTempC = 40.0, ptuTempC = 45.0, odometerKm = 12_345L, batterySoc = 80.0, batteryTempC = 20.0, cabinTempC = 21.0,
        fieldLastUpdateMs = mapOf(CanDecoder.SPEED_TAG to now, LaunchControl.TAG_420 to now, LaunchControl.TAG_225 to now),
    ).withPeaksUpdated()

    /** One KR reply showing [retard]° on cylinder 1, stamped at [at]. */
    private fun krReply(retard: Double, at: Long) = lt.readings.value.copy(ignCorrCyl1 = retard).markFresh("ignCorrCyl1", now = at)

    // ── Reset events: one table ─────────────────────────────────────────────

    @Test fun `a new connection forgets the readings that need this connection's own frame and restarts the figures`() {
        lt.showForTest(held())
        lt.onObdReply(krReply(2.0, now))
        assertEquals(1, lt.knockCount.value)

        lt.newConnection()
        val vs = lt.readings.value
        assertEquals(EscStatus.UNKNOWN, vs.escStatus)
        assertNull("SRS lamp unknown until this connection's 0x040", vs.lamps.srs)
        assertEquals("the IPC lamps are not part of this reset", false, vs.lamps.mil)
        assertEquals(-1, vs.engineState360)
        assertFalse(vs.launchControlActive); assertFalse(vs.launchControlEngaged)
        assertNull(vs.fieldLastUpdateMs[CanDecoder.SPEED_TAG])
        assertNull(vs.fieldLastUpdateMs[LaunchControl.TAG_420]); assertNull(vs.fieldLastUpdateMs[LaunchControl.TAG_225])
        assertEquals("running figures restart", 0, lt.knockCount.value)
        assertEquals(listOf("newConnection"), thermalCalls)
    }

    @Test fun `RETRY's own blanking returns the polled readings to their sentinels and drops the link flags`() {
        lt.showForTest(held())
        lt.retryForgetPolled()
        val vs = lt.readings.value
        assertFalse(vs.isConnected); assertFalse(vs.isReconnecting)
        listOf(vs.tirePressLF, vs.tirePressRF, vs.tirePressLR, vs.tirePressRR, vs.batterySoc).forEach { assertEquals(-1.0, it, 0.0) }
        listOf(vs.rduTempC, vs.ptuTempC, vs.batteryTempC, vs.cabinTempC).forEach { assertEquals(-99.0, it, 0.0) }
        assertEquals(-1L, vs.odometerKm)
    }

    @Test fun `the user's reset forgets every reading, the figures, peaks and readiness, and keeps the link`() {
        lt.showForTest(held())
        lt.onObdReply(krReply(2.0, now))
        lt.userReset()
        assertEquals(VehicleState(isConnected = true), lt.readings.value)
        assertEquals(0, lt.knockCount.value)
        assertEquals(listOf("userReset"), thermalCalls)
    }

    @Test fun `resetting peaks keeps the readings`() {
        lt.showForTest(held())
        lt.resetPeaks()
        assertEquals(held().withPeaksReset(), lt.readings.value)
    }

    @Test fun `demo replay starts from an empty connected state, demo exit from an empty disconnected one`() {
        lt.showForTest(held())
        lt.onObdReply(krReply(2.0, now))
        lt.demoReplay()
        assertEquals(VehicleState(isConnected = true), lt.readings.value)
        assertEquals(0, lt.knockCount.value)
        lt.showForTest(held())
        lt.demoExit()
        assertEquals(VehicleState(), lt.readings.value)
        assertEquals(0, lt.knockCount.value)
        assertTrue("thermal readiness hears nothing from demo (it follows the demo epoch)", thermalCalls.isEmpty())
    }

    // ── Feeds ───────────────────────────────────────────────────────────────

    @Test fun `a decoded frame lands with peaks and fps, an unchanged or unknown one changes nothing`() {
        val out = lt.onCanFrame(0x090, 42.0) { it.copy(rpm = 6500.0) }
        assertTrue(out.decoded)
        assertEquals(6500.0, lt.readings.value.rpm, 0.0)
        assertEquals(42.0, lt.readings.value.framesPerSecond, 0.0)
        assertEquals(6500.0, lt.readings.value.session.peakRpm, 0.0)
        val before = lt.readings.value
        assertTrue(lt.onCanFrame(0x040, 1.0) { it }.decoded)
        assertFalse(lt.onCanFrame(0x7FF, 1.0) { null }.decoded)
        assertTrue("same instance: nothing emitted", before === lt.readings.value)
    }

    @Test fun `a KR reply is counted once per fresh stamp, and only above 1 degree`() {
        lt.onObdReply(krReply(2.0, now))
        lt.onObdReply(krReply(2.0, now))          // same stamp: the same reading
        assertEquals(1, lt.knockCount.value)
        lt.onObdReply(krReply(0.5, now + 100))
        assertEquals(1, lt.knockCount.value)
        lt.onObdReply(krReply(1.5, now + 200))
        assertEquals(2, lt.knockCount.value)
    }

    @Test fun `a finished 0-100 run is returned to the caller with the wall clock's time and the ambient reading`() {
        lt.showForTest(VehicleState(isConnected = true, ambientTempC = 18.0))
        assertTrue(PerformanceTimer.arm())
        assertNull(lt.onCanFrame(0x130, 50.0) { it.copy(speedKph = 0.0) }.finishedRun)
        assertNull(lt.onCanFrame(0x130, 50.0) { it.copy(speedKph = 4.0) }.finishedRun)   // starts the run (3–5 km/h)
        now += 5_000
        val run = lt.onCanFrame(0x130, 50.0) { it.copy(speedKph = 165.0) }.finishedRun
        assertNotNull(run)
        assertEquals(now, run!!.atMs)
        assertEquals(18.0, run.ambientTempC, 0.0)
        assertNotNull(run.result.zeroTo100Ms)
    }
}
