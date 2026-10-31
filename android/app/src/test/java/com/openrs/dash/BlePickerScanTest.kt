package com.openrs.dash

import com.openrs.dash.can.BleDeviceScanner
import com.openrs.dash.can.BleScanBackend
import com.openrs.dash.can.BleScanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #218 — the Bluetooth picker with the PHONE's radio off: the scanner reports it (never a silent "no adapter found"),
 * nothing is cached, and turning Bluetooth on retries in the SAME picker. A fake backend stands in for Android's
 * BluetoothAdapter / BluetoothLeScanner (null scanner while off, IllegalStateException if it goes off mid-call).
 */
class BlePickerScanTest {

    private class FakeBackend : BleScanBackend {
        var radio: Boolean? = false
        var starts = 0
        var onResult: ((BleDeviceScanner.BleDevice) -> Unit)? = null
        var onFailed: ((Int) -> Unit)? = null
        var timeout: (() -> Unit)? = null
        override fun bluetoothOn() = radio
        override fun start(filterless: Boolean, onResult: (BleDeviceScanner.BleDevice) -> Unit, onFailed: (Int) -> Unit): BleScanBackend.Start {
            if (radio != true) return BleScanBackend.Start.BLUETOOTH_OFF          // Android: no scanner while off
            starts++; this.onResult = onResult; this.onFailed = onFailed
            return BleScanBackend.Start.STARTED
        }
        override fun stop() { onResult = null }
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit { timeout = action; return { timeout = null } }
    }

    @Test fun `phone Bluetooth off is reported, then turning it on retries in the same picker and finds the adapter`() {
        val b = FakeBackend(); val s = BleDeviceScanner(b)
        s.startScan()
        assertEquals(BleScanState.PhoneBluetoothOff, s.state.value)
        assertFalse(s.scanning.value)
        assertEquals(0, b.starts)
        s.startScan()                                                             // Scan Again while still off
        assertEquals(BleScanState.PhoneBluetoothOff, s.state.value)
        b.radio = true                                                            // enable prompt accepted
        s.onEnablePromptResult(accepted = true)
        assertEquals(BleScanState.Scanning, s.state.value)
        b.onResult!!(BleDeviceScanner.BleDevice("WiC_test", "AA:BB:CC:DD:EE:FF", -60))
        assertEquals(listOf("WiC_test"), s.devices.value.map { it.name })
    }

    @Test fun `turned on elsewhere — the state broadcast retries without the prompt`() {
        val b = FakeBackend(); val s = BleDeviceScanner(b)
        s.startScan()
        b.radio = true
        s.onPhoneBluetoothChanged(on = true)
        assertEquals(BleScanState.Scanning, s.state.value)
        assertEquals(1, b.starts)
    }

    @Test fun `declining the prompt keeps the message and runs no scan`() {
        val b = FakeBackend(); val s = BleDeviceScanner(b)
        s.startScan()
        s.onEnablePromptResult(accepted = false)
        assertEquals(BleScanState.PhoneBluetoothOff, s.state.value)
        assertTrue(s.enableDeclined.value)
        assertEquals(0, b.starts)
    }

    @Test fun `switching Bluetooth off again mid-scan stops the scan and says so`() {
        val b = FakeBackend().apply { radio = true }; val s = BleDeviceScanner(b)
        s.startScan()
        assertTrue(s.scanning.value)
        b.radio = false
        s.onPhoneBluetoothChanged(on = false)
        assertFalse(s.scanning.value)
        assertEquals(BleScanState.PhoneBluetoothOff, s.state.value)
        assertEquals("the timeout is cancelled", null, b.timeout)
        s.startScan()                                                             // retry while off: no crash, no scan
        assertEquals(BleScanState.PhoneBluetoothOff, s.state.value)
        assertEquals(1, b.starts)
    }

    @Test fun `a scan that runs and finds nothing ends Finished with no devices — the only 'no adapter found' case`() {
        val b = FakeBackend().apply { radio = true }; val s = BleDeviceScanner(b)
        s.startScan()
        b.timeout!!()                                                             // 18 s elapse, nothing advertised
        assertEquals(BleScanState.Finished, s.state.value)
        assertTrue(s.devices.value.isEmpty())
        assertFalse(s.scanning.value)
    }

    @Test fun `no Bluetooth hardware and a scanner failure are their own states`() {
        val none = BleDeviceScanner(FakeBackend().apply { radio = null })
        none.startScan()
        assertEquals(BleScanState.NoBluetooth, none.state.value)
        val b = FakeBackend().apply { radio = true }; val s = BleDeviceScanner(b)
        s.startScan()
        b.onFailed!!(2)
        assertTrue(s.state.value is BleScanState.Failed)
        assertFalse(s.scanning.value)
    }
}
