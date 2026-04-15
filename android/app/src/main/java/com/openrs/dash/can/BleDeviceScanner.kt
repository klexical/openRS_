package com.openrs.dash.can

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scans for BLE devices advertising the WiCAN SLCAN service (UUID 0xFFE0).
 *
 * Results are exposed via [devices] StateFlow. Scan auto-stops after
 * [SCAN_TIMEOUT_MS]. By default the scan is filtered to the SLCAN service
 * UUID; pass `filterless = true` to [startScan] to discover adapters whose
 * firmware advertises BLE without the expected service UUID (a documented
 * WiCAN firmware edge case when BLE was enabled without a power cycle).
 */
@SuppressLint("MissingPermission")
class BleDeviceScanner(private val context: Context) {

    data class BleDevice(
        val name: String,
        val address: String,
        val rssi: Int
    )

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** True when the current/last scan ran without a service-UUID filter. */
    private val _filterless = MutableStateFlow(false)
    val filterless: StateFlow<Boolean> = _filterless.asStateFlow()

    private val seen = mutableMapOf<String, BleDevice>()

    private val scanner by lazy {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btManager.adapter?.bluetoothLeScanner
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            // In filterless mode many devices advertise without a name —
            // fall back to the MAC rather than a misleading "WiCAN" default.
            val name = device.name
                ?: result.scanRecord?.deviceName
                ?: if (_filterless.value) "(unnamed device)" else "WiCAN"
            val entry = BleDevice(name, address, result.rssi)
            seen[address] = entry
            _devices.value = seen.values.sortedByDescending { it.rssi }
        }
    }

    private val stopRunnable = Runnable { stopScan() }

    /**
     * Start a BLE scan. When [filterless] is true, no service-UUID filter is
     * applied — every advertising BLE device in range will be returned. Use
     * this as a fallback when a filtered scan misses an adapter whose
     * advertisement packet doesn't include the expected 0xFFE0 service UUID.
     */
    fun startScan(filterless: Boolean = false) {
        val s = scanner ?: return
        seen.clear()
        _devices.value = emptyList()
        _scanning.value = true
        _filterless.value = filterless

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            if (filterless) {
                // No filter — every advertising peripheral is reported.
                s.startScan(null, settings, scanCallback)
            } else {
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleSlcanTransport.SERVICE_UUID))
                    .build()
                s.startScan(listOf(filter), settings, scanCallback)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("BLE", "BLUETOOTH_SCAN permission not granted", e)
            _scanning.value = false
            return
        }

        android.os.Handler(context.mainLooper).postDelayed(stopRunnable, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        _scanning.value = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
        android.os.Handler(context.mainLooper).removeCallbacks(stopRunnable)
    }

    companion object {
        /**
         * 18 s window. Previous 10 s was too short when the adapter uses a
         * slow advertising interval (2–5 s) — the first couple of packets
         * could easily be missed, leaving no time for another hit.
         */
        const val SCAN_TIMEOUT_MS = 18_000L
    }
}
