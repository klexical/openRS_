package com.openrs.dash

import com.openrs.dash.can.ObdConstants
import com.openrs.dash.data.DashCell
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.resolveValue
import com.openrs.dash.ui.Level
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.batteryCell
import com.openrs.dash.ui.dashCellLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #365 — PCM 0x0304 is the charging system's target, not a measurement of the battery (#223: with the engine off it read
 * 10.60 V while a meter on the battery read 11.90 V, the same in every engine-off reply and equal to BCM 0x411D; running,
 * 15.0–15.3 V). So with the engine off (rpm at or below [ObdConstants.FAST_POLL_MIN_RPM]) DRIVE and the Custom Dashboard
 * read "— —" whatever 0x0304 reports; with it running they show the value, which never takes a warning colour (a target
 * is not a measurement to warn on). Pure functions, fixed clock; the reading is fresh in every case.
 */
class BatteryChargingTargetTest {
    private val now = 1_000_000L
    private fun vs(volts: Double, rpm: Double) = VehicleState(
        isConnected = true, batteryVoltage = volts, rpm = rpm,
        fieldLastUpdateMs = mapOf("batteryVoltage" to now - 5_000L),
    )
    private val cell = DashCell("batteryVoltage", "number", "CHG")

    @Test fun `engine off - DRIVE reads no value whatever 0x0304 reports (the parked car's fixed floor)`() {
        assertEquals("— —" to Level.NONE, batteryCell(vs(10.60, rpm = 0.0), now))
    }

    @Test fun `engine off at the threshold itself - still no value`() {
        assertEquals("— —" to Level.NONE, batteryCell(vs(10.60, rpm = ObdConstants.FAST_POLL_MIN_RPM), now))
    }

    @Test fun `engine off - the Custom Dashboard cell reads no value either`() {
        assertEquals("— —", resolveValue(vs(10.60, rpm = 0.0), UserPrefs(), cell, now).first)
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
}
