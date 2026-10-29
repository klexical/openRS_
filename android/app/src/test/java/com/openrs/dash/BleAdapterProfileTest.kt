package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleSetupPolicy
import com.openrs.dash.ui.BleDeviceDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * #218 — Bluetooth adapter layouts, setup outcomes and the Settings selection. Source-verified against upstream
 * WiCAN firmware (Pro v4.45p / v4.48p, USB v4.13u–v4.21u); not yet verified against a physical adapter.
 */
class BleAdapterProfileTest {
    private fun u(v: Int) = UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(v))
    private val gap = u(0x1800) to setOf(u(0x2A00))
    private val devInfo = u(0x180A) to setOf(u(0x2A29), u(0x2A26))
    private val pro = u(0xFFF0) to setOf(u(0xFFF1), u(0xFFF2))
    private val usb = u(0xFEE0) to setOf(u(0xFEE1), u(0xFEE3))
    private val legacy = u(0xFFE0) to setOf(u(0xFFE1), u(0xFFE2))

    @Test fun `a WiCAN Pro (FFF0) gets write FFF2 and notify FFF1 — not the old FFE mapping`() {
        val p = BleAdapterProfile.select(mapOf(gap, devInfo, pro), "MEATPI_PRO")!!
        assertEquals(u(0xFFF0), p.service); assertEquals(u(0xFFF2), p.write); assertEquals(u(0xFFF1), p.notify)
        assertTrue(p.verified)
    }

    @Test fun `a WiCAN USB (FEE0) uses FEE1 for both directions`() {
        val p = BleAdapterProfile.select(mapOf(gap, usb), "MEATPI_USB")!!
        assertEquals(u(0xFEE1), p.write); assertEquals(u(0xFEE1), p.notify)
    }

    @Test fun `the chosen layout follows what the device exposes, whatever the adapter setting says`() {
        assertEquals(u(0xFFF0), BleAdapterProfile.select(mapOf(pro), "MEATPI_USB")!!.service)
        assertEquals(u(0xFEE0), BleAdapterProfile.select(mapOf(usb), "MEATPI_PRO")!!.service)
        // Both exposed: the adapter type breaks the tie
        assertEquals(u(0xFFF0), BleAdapterProfile.select(mapOf(pro, usb), "MEATPI_PRO")!!.service)
        assertEquals(u(0xFEE0), BleAdapterProfile.select(mapOf(pro, usb), "MEATPI_USB")!!.service)
    }

    @Test fun `legacy FFE0 is a last, unverified fallback`() {
        val p = BleAdapterProfile.select(mapOf(gap, legacy), "MEATPI_PRO")!!
        assertEquals(u(0xFFE0), p.service); assertFalse(p.verified)
    }

    @Test fun `no supported service, or a service missing its characteristics, is not an adapter`() {
        assertNull(BleAdapterProfile.select(mapOf(gap, devInfo), "MEATPI_PRO"))
        assertNull(BleAdapterProfile.select(mapOf(u(0xFFF0) to setOf(u(0xFFF1))), "MEATPI_PRO"))   // no FFF2
    }

    @Test fun `the scan filter covers every supported service`() {
        assertEquals(setOf(u(0xFFF0), u(0xFEE0), u(0xFFE0)), BleAdapterProfile.SCAN_SERVICES.toSet())
    }

    @Test fun `subscription outcomes — success, pair once on an auth error, then a specific failure`() {
        assertEquals(BleSetupPolicy.Action.Proceed, BleSetupPolicy.afterSubscribe(0, alreadyRetried = false))
        assertEquals(BleSetupPolicy.Action.BondAndRetry, BleSetupPolicy.afterSubscribe(5, alreadyRetried = false))
        assertEquals(BleSetupPolicy.Action.BondAndRetry, BleSetupPolicy.afterSubscribe(15, alreadyRetried = false))
        assertTrue(BleSetupPolicy.afterSubscribe(5, alreadyRetried = true) is BleSetupPolicy.Action.Fail)
        val other = BleSetupPolicy.afterSubscribe(133, alreadyRetried = false)
        assertTrue(other is BleSetupPolicy.Action.Fail && "133" in other.message)
    }

    @Test fun `a failed write is reported, not treated as sent`() {
        assertNull(BleSetupPolicy.afterWrite(0))
        assertTrue(BleSetupPolicy.afterWrite(5)!!.contains("not paired"))
        assertTrue(BleSetupPolicy.afterWrite(1)!!.contains("failed"))
    }

    @Test fun `Settings selection shows at once, SAVE persists it, CANCEL leaves the saved device`() {
        var saved: Pair<String, String>? = "AA:BB" to "old"
        val start = BleDeviceDraft.of("AA:BB", "old")
        val picked = start.select("CC:DD", "WiCAN_PRO")
        assertEquals("CC:DD" to "WiCAN_PRO", picked.staged)            // the screen shows the new device immediately
        assertTrue(picked.changed)
        // CANCEL: the draft is dropped — nothing persisted
        assertEquals("AA:BB" to "old", saved)
        // SAVE
        picked.commit(save = { a, n -> saved = a to n }, clear = { saved = null })
        assertEquals("CC:DD" to "WiCAN_PRO", saved)
        // FORGET + SAVE clears; an unchanged SAVE writes nothing
        start.forget().commit(save = { _, _ -> error("no") }, clear = { saved = null })
        assertNull(saved)
        start.commit(save = { _, _ -> error("unchanged must not write") }, clear = { error("unchanged must not clear") })
    }
}
