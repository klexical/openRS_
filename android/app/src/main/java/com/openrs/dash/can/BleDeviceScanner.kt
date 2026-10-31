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
 * What the picker's scan has established (#218). [PhoneBluetoothOff] is the PHONE's radio — not the adapter's own
 * BLE setting — and is reported instead of a silent "no adapter found".
 */
sealed interface BleScanState {
    /** No scan started yet (e.g. waiting for the permission prompt). */
    data object Idle : BleScanState
    data object Scanning : BleScanState
    /** A scan ran to its end — the device list (possibly empty) is its result. */
    data object Finished : BleScanState
    data object PhoneBluetoothOff : BleScanState
    /** This phone / box has no Bluetooth hardware. */
    data object NoBluetooth : BleScanState
    data object PermissionMissing : BleScanState
    data class Failed(val reason: String) : BleScanState
}

/** The platform side of a BLE scan — Android in the app ([AndroidBleScanBackend]), a fake in JVM tests. */
interface BleScanBackend {
    enum class Start { STARTED, BLUETOOTH_OFF, PERMISSION_MISSING }

    /** null = no Bluetooth hardware; false = present but the phone's radio is off. Read fresh on every call. */
    fun bluetoothOn(): Boolean?
    fun start(filterless: Boolean, onResult: (BleDeviceScanner.BleDevice) -> Unit, onFailed: (Int) -> Unit): Start
    fun stop()
    /** Runs [action] after [delayMs]; returns a cancel. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}

/**
 * Scans for BLE devices advertising a supported WiCAN service (FFF0 Pro / FEE0 USB / legacy FFE0 — see BleAdapterProfile).
 *
 * Results are exposed via [devices] StateFlow, the outcome via [state]. Scan auto-stops after
 * [SCAN_TIMEOUT_MS]. By default the scan is filtered to the SLCAN service
 * UUID; pass `filterless = true` to [startScan] to discover adapters whose
 * firmware advertises BLE without the expected service UUID (a documented
 * WiCAN firmware edge case when BLE was enabled without a power cycle).
 *
 * The platform scanner is never cached (#218): Android returns no scanner while the phone's Bluetooth is off, and a
 * cached null made Scan Again silently do nothing until the picker was reopened. Every [startScan] asks again, so
 * turning Bluetooth on — from the enable prompt or elsewhere — and retrying works in the same picker.
 */
class BleDeviceScanner(private val backend: BleScanBackend) {

    constructor(context: Context) : this(AndroidBleScanBackend(context))

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

    private val _state = MutableStateFlow<BleScanState>(BleScanState.Idle)
    val state: StateFlow<BleScanState> = _state.asStateFlow()

    /** The user declined Android's enable prompt in this picker — the message stays, no scan runs. */
    private val _enableDeclined = MutableStateFlow(false)
    val enableDeclined: StateFlow<Boolean> = _enableDeclined.asStateFlow()

    private val seen = mutableMapOf<String, BleDevice>()
    private var cancelTimeout: (() -> Unit)? = null
    /**
     * Identity of the scan whose callbacks may still change the picker; 0 = none (#218). Android can deliver a result,
     * a failure or a posted timeout after that scan was stopped or replaced — they carry their own id and are dropped
     * unless it is still [activeScan], so scan A can never stop scan B, put its error on B, or add a device to B's list.
     */
    private var scanSeq = 0
    private var activeScan = 0

    /**
     * Start a BLE scan. When [filterless] is true, no service-UUID filter is
     * applied — every advertising BLE device in range will be returned. Use
     * this as a fallback when a filtered scan misses an adapter whose
     * advertisement packet doesn't include one of the expected service UUIDs.
     */
    fun startScan(filterless: Boolean = false) {
        stopScan(finished = false)
        seen.clear()
        _devices.value = emptyList()
        _filterless.value = filterless

        when (backend.bluetoothOn()) {
            null -> { _state.value = BleScanState.NoBluetooth; return }
            false -> { _state.value = BleScanState.PhoneBluetoothOff; return }
            true -> Unit
        }
        val id = ++scanSeq
        activeScan = id                      // before start(): a callback delivered at once already belongs to it
        val started = backend.start(filterless,
            onResult = { d ->
                if (id == activeScan) { seen[d.address] = d; _devices.value = seen.values.sortedByDescending { it.rssi } }
            },
            onFailed = { code ->
                if (id == activeScan) {
                    stopScan(finished = false)
                    _state.value = BleScanState.Failed("the phone's scanner reported error $code")
                }
            })
        when (started) {
            BleScanBackend.Start.STARTED -> {
                _enableDeclined.value = false
                _scanning.value = true
                _state.value = BleScanState.Scanning
                cancelTimeout = backend.schedule(SCAN_TIMEOUT_MS) { if (id == activeScan) stopScan(finished = true) }
            }
            BleScanBackend.Start.BLUETOOTH_OFF -> { activeScan = 0; _state.value = BleScanState.PhoneBluetoothOff }
            BleScanBackend.Start.PERMISSION_MISSING -> { activeScan = 0; _state.value = BleScanState.PermissionMissing }
        }
    }

    fun stopScan() = stopScan(finished = _scanning.value)

    /** Ends the current scan and invalidates its id — anything it still delivers is ignored. */
    private fun stopScan(finished: Boolean) {
        activeScan = 0
        cancelTimeout?.invoke()
        cancelTimeout = null
        val was = _scanning.value
        _scanning.value = false
        if (was) try { backend.stop() } catch (_: Exception) { }
        if (finished) _state.value = BleScanState.Finished
    }

    /** The phone's Bluetooth changed while the picker is open: off stops the scan; on retries if it was waiting for it. */
    fun onPhoneBluetoothChanged(on: Boolean) {
        if (!on) {
            stopScan(finished = false)
            _state.value = BleScanState.PhoneBluetoothOff
        } else if (_state.value == BleScanState.PhoneBluetoothOff) {
            startScan(_filterless.value)
        }
    }

    /**
     * Result of Android's enable prompt: accepted → scan (when the radio reports on); declined → keep the message.
     * The STATE_ON broadcast and this result arrive in either order; whichever comes second finds the scan already
     * running (no longer [BleScanState.PhoneBluetoothOff]) and leaves it alone — one scan, never a restart.
     */
    fun onEnablePromptResult(accepted: Boolean) {
        if (!accepted) { _enableDeclined.value = true; return }
        if (_state.value != BleScanState.PhoneBluetoothOff) return
        if (backend.bluetoothOn() == true) startScan(_filterless.value)    // else ACTION_STATE_CHANGED will retry
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

/** Android scan backend: adapter + scanner looked up on every call, never cached (#218). */
@SuppressLint("MissingPermission")
class AndroidBleScanBackend(private val context: Context) : BleScanBackend {
    private val handler = android.os.Handler(context.mainLooper)
    private var callback: ScanCallback? = null

    private fun adapter() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun bluetoothOn(): Boolean? = adapter()?.isEnabled

    override fun start(
        filterless: Boolean, onResult: (BleDeviceScanner.BleDevice) -> Unit, onFailed: (Int) -> Unit,
    ): BleScanBackend.Start {
        // Null while the phone's radio is off (or turning off)
        val scanner = adapter()?.bluetoothLeScanner ?: return BleScanBackend.Start.BLUETOOTH_OFF
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val address = device.address ?: return
                // In filterless mode many devices advertise without a name —
                // fall back to the MAC rather than a misleading "WiCAN" default.
                val name = device.name
                    ?: result.scanRecord?.deviceName
                    ?: if (filterless) "(unnamed device)" else "WiCAN"
                handler.post { onResult(BleDeviceScanner.BleDevice(name, address, result.rssi)) }
            }
            override fun onScanFailed(errorCode: Int) { handler.post { onFailed(errorCode) } }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            if (filterless) {
                // No filter — every advertising peripheral is reported.
                scanner.startScan(null, settings, cb)
            } else {
                // Every supported adapter layout (#218): WiCAN Pro FFF0, WiCAN USB FEE0, legacy FFE0. A match only
                // means the device advertises the service — it isn't proof that it will connect.
                val filters = BleAdapterProfile.SCAN_SERVICES.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
                scanner.startScan(filters, settings, cb)
            }
            callback = cb
            BleScanBackend.Start.STARTED
        } catch (e: SecurityException) {
            android.util.Log.w("BLE", "BLUETOOTH_SCAN permission not granted", e)
            BleScanBackend.Start.PERMISSION_MISSING
        } catch (e: IllegalStateException) {
            // "BT Adapter is not turned ON" — the radio went off between the check and the call
            BleScanBackend.Start.BLUETOOTH_OFF
        }
    }

    override fun stop() {
        val cb = callback ?: return
        callback = null
        try { adapter()?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) { }
    }

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val r = Runnable(action)
        handler.postDelayed(r, delayMs)
        return { handler.removeCallbacks(r) }
    }
}
