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
 * Results are exposed via [devices] StateFlow. Scan auto-stops after 10 seconds.
 * Only devices matching the service UUID are reported.
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

    private val seen = mutableMapOf<String, BleDevice>()

    private val scanner by lazy {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btManager.adapter?.bluetoothLeScanner
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: "WiCAN"
            val entry = BleDevice(name, address, result.rssi)
            seen[address] = entry
            _devices.value = seen.values.sortedByDescending { it.rssi }
        }
    }

    private val stopRunnable = Runnable { stopScan() }

    fun startScan() {
        val s = scanner ?: return
        seen.clear()
        _devices.value = emptyList()
        _scanning.value = true

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleSlcanTransport.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            s.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            android.util.Log.w("BLE", "BLUETOOTH_SCAN permission not granted", e)
            _scanning.value = false
            return
        }

        // Auto-stop after 10 seconds
        android.os.Handler(context.mainLooper).postDelayed(stopRunnable, 10_000L)
    }

    fun stopScan() {
        _scanning.value = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
        android.os.Handler(context.mainLooper).removeCallbacks(stopRunnable)
    }
}
