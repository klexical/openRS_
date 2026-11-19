package com.openrs.dash

import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.ChargeTarget
import com.openrs.dash.demo.DemoScenario
import com.openrs.dash.demo.DemoSimulator
import com.openrs.dash.data.DashCell
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.resolveValue
import com.openrs.dash.ui.Level
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.batteryCell
import com.openrs.dash.ui.dashCellLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #365 — PCM 0x0304 is the charging system's target, not a measurement of the battery (#223: with the engine off, on one
 * car, it read 10.60 V while a meter on the battery read 11.90 V, the same in every engine-off reply and equal to BCM
 * 0x411D; running, 15.0–15.3 V). DRIVE and the Custom Dashboard show it only with the engine running (rpm above
 * [ObdConstants.FAST_POLL_MIN_RPM]) and only a value from a reply received while it ran: the engine-off sweep's reply (the
 * fixed floor) stays fresh into a start until the running schedule asks 0x0304 again, up to 20 batches later. A shown
 * value never takes a warning colour (a target is not a measurement to warn on), and reads STALE while no CAN frame has
 * arrived for [com.openrs.dash.data.BoostRule.LINK_FRESH_MS], since the engine's state is then not known. Pure functions,
 * fixed clock, except the decode-and-merge cases, which run the production decoder and merge at the wall clock.
 */
class BatteryChargingTargetTest {
    private val now = 1_000_000L
    /** A 0x0304 reply received with the engine running ([running]), 5 s ago; CAN frames current unless [lastFrameAgeMs]. */
    private fun vs(volts: Double, rpm: Double, running: Boolean = true, lastFrameAgeMs: Long = 0L) = VehicleState(
        isConnected = true, batteryVoltage = volts, rpm = rpm, lastUpdate = now - lastFrameAgeMs,
        fieldLastUpdateMs = mapOf("batteryVoltage" to now - 5_000L) +
            (if (running) mapOf(ChargeTarget.RUNNING_TAG to now - 5_000L) else emptyMap()),
    )
    private val cell = DashCell("batteryVoltage", "number", "CHG")

    @Test fun `engine off - DRIVE reads no value whatever 0x0304 reports (the parked car's fixed floor)`() {
        assertEquals("— —" to Level.NONE, batteryCell(vs(10.60, rpm = 0.0, running = false), now))
    }

    @Test fun `engine off at the threshold itself - still no value`() {
        assertEquals("— —" to Level.NONE, batteryCell(vs(10.60, rpm = ObdConstants.FAST_POLL_MIN_RPM), now))
    }

    @Test fun `engine off - the Custom Dashboard cell reads no value either`() {
        assertEquals("— —", resolveValue(vs(10.60, rpm = 0.0, running = false), UserPrefs(), cell, now).first)
    }

    @Test fun `engine off at the threshold itself - the Custom Dashboard reads no value`() {
        assertEquals("— —", resolveValue(vs(10.60, rpm = ObdConstants.FAST_POLL_MIN_RPM), UserPrefs(), cell, now).first)
    }

    @Test fun `control - engine running, DRIVE shows the charging target as normal`() {
        assertEquals("15.1V" to Level.NORMAL, batteryCell(vs(15.10, rpm = 850.0), now))
    }

    @Test fun `control - engine running, a low target still never takes a warning level`() {
        assertEquals("11.0V" to Level.NORMAL, batteryCell(vs(11.0, rpm = 850.0), now))
    }

    @Test fun `a Custom Dashboard layout saved before #365 shows the field as CHG, not BATTERY`() {
        assertEquals("CHG", dashCellLabel(DashCell("batteryVoltage", "number", "BATTERY")))
    }

    @Test fun `control - engine running, the Custom Dashboard shows the value`() {
        assertEquals("15.10V", resolveValue(vs(15.10, rpm = 850.0), UserPrefs(), cell, now).first)
    }

    // ── a reply received with the engine off is never shown after a start ────────────────────────────────────────────

    @Test fun `after a start, DRIVE does not show the reply received with the engine off`() {
        assertEquals("— —" to Level.NONE, batteryCell(vs(10.60, rpm = 850.0, running = false), now))
    }

    @Test fun `after a start, the Custom Dashboard does not show the reply received with the engine off`() {
        assertEquals("— —", resolveValue(vs(10.60, rpm = 850.0, running = false), UserPrefs(), cell, now).first)
    }

    @Test fun `a reply received with the engine off after a running one is not shown either`() {
        val s = vs(10.60, rpm = 850.0).let { it.copy(fieldLastUpdateMs = it.fieldLastUpdateMs + ("batteryVoltage" to now - 1_000L)) }
        assertEquals("— —" to Level.NONE, batteryCell(s, now))
        assertEquals("— —", resolveValue(s, UserPrefs(), cell, now).first)
    }

    private fun reply0304(volts: Double): ByteArray {
        val raw = (volts * 2048).toInt()
        return byteArrayOf(0x05, 0x62, 0x03, 0x04, (raw shr 8).toByte(), raw.toByte())
    }
    private fun decode(snapshot: VehicleState, volts: Double): VehicleState {
        var out: VehicleState? = null
        ObdResponseParser.parsePcmResponse(reply0304(volts), snapshot) { out = it }
        return ObdMerge.merge(snapshot, out!!)
    }

    @Test fun `production path - the engine-off sweep's reply, then a start - no value until a running reply`() {
        val parked = VehicleState(isConnected = true, rpm = 0.0, lastUpdate = System.currentTimeMillis())
        val afterSweep = decode(parked, 10.60)
        assertNull("not stamped as a running reply", afterSweep.fieldLastUpdateMs[ChargeTarget.RUNNING_TAG])
        val t = System.currentTimeMillis()
        val started = afterSweep.copy(rpm = 850.0, lastUpdate = t)       // CAN 0x090 after the start
        assertEquals("— —" to Level.NONE, batteryCell(started, t))
        assertEquals("— —", resolveValue(started, UserPrefs(), cell, t).first)

        val running = decode(started, 14.90)                              // the running schedule's 0x0304
        val t2 = System.currentTimeMillis()
        assertEquals("14.9V" to Level.NORMAL, batteryCell(running.copy(lastUpdate = t2), t2))
        assertEquals("14.90V", resolveValue(running, UserPrefs(), cell, t2).first)
    }

    @Test fun `a reply decoded with the engine at the threshold itself is not a running reply`() {
        val atThreshold = VehicleState(isConnected = true, rpm = ObdConstants.FAST_POLL_MIN_RPM, lastUpdate = System.currentTimeMillis())
        assertNull(decode(atThreshold, 12.0).fieldLastUpdateMs[ChargeTarget.RUNNING_TAG])
    }

    @Test fun `demo - IDLE and DRIVING frames show CHG as a live value (demo idles at 850 rpm or more)`() {
        for (sc in listOf(DemoScenario.IDLE, DemoScenario.DRIVING)) {
            val f = DemoSimulator.apply(VehicleState(isConnected = true), sc, 30_000L, DriveMode.NORMAL, EscStatus.ON, now, true)
            assertEquals(sc.name, Level.NORMAL, batteryCell(f, now).second)
        }
    }

    // ── the engine's state is not known while no CAN frame arrives ──────────────────────────────────────────────────

    @Test fun `connected with no CAN frame for 30 s - DRIVE marks a held running reading stale`() {
        assertEquals("14.9V" to Level.STALE, batteryCell(vs(14.90, rpm = 3_000.0, lastFrameAgeMs = 30_000L), now))
    }

    @Test fun `control - a CAN frame within the link limit keeps the reading normal`() {
        assertEquals("14.9V" to Level.NORMAL, batteryCell(vs(14.90, rpm = 3_000.0, lastFrameAgeMs = 2_000L), now))
    }

    @Test fun `control - a CAN frame stamped a little after the screen's clock keeps the reading normal`() {
        assertEquals("14.9V" to Level.NORMAL, batteryCell(vs(14.90, rpm = 3_000.0, lastFrameAgeMs = -50L), now))
    }
}
