package com.openrs.dash

import com.openrs.dash.service.ConnectionSetup
import com.openrs.dash.ui.BleDeviceDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #234 — Bluetooth selected with no device has nothing to connect to. Settings SAVE checks the DRAFT (a FORGET counts
 * before it is saved); the service start and the dashboard check the SAVED settings with the same rule.
 */
class ConnectionSetupTest {
    private val msg = "Select a Bluetooth device, or switch to Wi-Fi."

    @Test fun `SAVE with Bluetooth and no device is refused with the explanation`() {
        assertEquals(msg, BleDeviceDraft.of(null, null).saveProblem("BLUETOOTH"))
    }

    @Test fun `forgetting the last device and then SAVE is refused — the draft is checked, not the saved device`() {
        val draft = BleDeviceDraft.of("AA:BB:CC:DD:EE:FF", "WiCAN_PRO").forget()
        assertEquals(msg, draft.saveProblem("BLUETOOTH"))
        // CANCEL drops the draft: the saved selection is what it was
        assertEquals("AA:BB:CC:DD:EE:FF" to "WiCAN_PRO", draft.saved)
    }

    @Test fun `Bluetooth with a selected device saves, and Wi-Fi saves without any Bluetooth device`() {
        assertNull(BleDeviceDraft.of(null, null).select("AA:BB:CC:DD:EE:FF", "WiCAN_PRO").saveProblem("BLUETOOTH"))
        assertNull(BleDeviceDraft.of(null, null).saveProblem("WIFI"))
        assertNull(BleDeviceDraft.of("AA:BB:CC:DD:EE:FF", "WiCAN_PRO").forget().saveProblem("WIFI"))
    }

    @Test fun `settings already saved as Bluetooth without a device are reported, never switched`() {
        assertEquals(msg, ConnectionSetup.problem("BLUETOOTH", null))
        assertEquals(msg, ConnectionSetup.problem("BLUETOOTH", " "))
        assertNull(ConnectionSetup.problem("BLUETOOTH", "AA:BB:CC:DD:EE:FF"))
        assertNull(ConnectionSetup.problem("WIFI", null))
    }
}
