package com.openrs.dash

import com.openrs.dash.data.DashCell
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.resolveValue
import com.openrs.dash.ui.Level
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.batteryCell
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * rc.8 reliability pass, #223 — DRIVE → VEHICLE → BATT and the Custom Dashboard BATTERY cell show PCM 0x0304 only while
 * the reading is current. 0x0304 is a slow PCM DID (PolledFreshness: 40 s, stale past 2×); before the fix DRIVE showed
 * any held positive value as NORMAL for as long as the link was up, and the dashboard showed it as live. A value is
 * shown with its own state; nothing here judges the voltage itself (no limit is established). Pure functions, fixed clock.
 */
class BatteryFreshnessTest {
    private val now = 1_000_000L
    private fun vs(volts: Double, stampAgeMs: Long?, connected: Boolean = true) = VehicleState(
        isConnected = connected, batteryVoltage = volts,
        fieldLastUpdateMs = stampAgeMs?.let { mapOf("batteryVoltage" to now - it) } ?: emptyMap(),
    )
    private val cell = DashCell("batteryVoltage", "number", "BATTERY")

    @Test fun `control - DRIVE shows a current reading as normal`() {
        assertEquals("15.0V" to Level.NORMAL, batteryCell(vs(14.96, stampAgeMs = 10_000L), now))
    }

    @Test fun `DRIVE marks a held reading stale once it is older than the polled-value rule`() {
        assertEquals("15.0V" to Level.STALE, batteryCell(vs(14.96, stampAgeMs = 120_000L), now))
    }

    @Test fun `control - DRIVE marks the last reading stale while disconnected`() {
        assertEquals("15.0V" to Level.STALE, batteryCell(vs(14.96, stampAgeMs = 1_000L, connected = false), now))
    }

    @Test fun `control - DRIVE shows no value before a reading, or for a reported 0`() {
        assertEquals("— —" to Level.NONE, batteryCell(vs(0.0, stampAgeMs = null), now))
        assertEquals("— —" to Level.NONE, batteryCell(vs(0.0, stampAgeMs = 1_000L), now))
    }

    @Test fun `control - the dashboard shows a current reading`() {
        assertEquals("14.96V", resolveValue(vs(14.96, stampAgeMs = 10_000L), UserPrefs(), cell, now).first)
    }

    @Test fun `the dashboard never shows a held reading as live`() {
        assertEquals("— —", resolveValue(vs(14.96, stampAgeMs = 120_000L), UserPrefs(), cell, now).first)
    }
}
