package com.openrs.dash.can

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.io.IOException
import java.util.UUID

/**
 * BLE GATT SLCAN transport for WiCAN adapters.
 *
 * The GATT layout comes from [BleAdapterProfile], chosen from the services the adapter actually exposes (#218):
 * WiCAN Pro FFF0 (write FFF2, notify FFF1), WiCAN USB FEE0 (FEE1 write + notify), legacy FFE0 as a last fallback.
 * The setup sequence, write path and line framing live in [BleGattCore] (Android-free, unit-tested); this class only
 * connects and forwards `BluetoothGatt` callbacks to it, and implements [GattLink] over `BluetoothGatt`.
 *
 * BLE is protocol-compatible with the WiFi TCP interface — same `\r`-delimited SLCAN frames.
 */
@SuppressLint("MissingPermission")
class BleSlcanTransport(
    private val context: Context,
    private val macAddress: String,
    private val deviceName: String = "WiCAN",
    /** "MEATPI_PRO" / "MEATPI_USB" — only breaks ties between layouts the adapter exposes. */
    private val adapterType: String? = null,
) : SlcanTransport {

    companion object {
        val CCCD_UUID: UUID = BleAdapterProfile.CCCD
        /**
         * Adapters this process has completed setup with. Each attempt builds a new transport, so an instance field
         * never survived to the retry and autoConnect=true (Android reconnecting when the adapter returns) was never
         * used; keyed by MAC here, it is.
         */
        private val connectedBefore = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }

    private val core = BleGattCore(adapterType)
    private var gatt: BluetoothGatt? = null

    override val label: String get() = "BLE $deviceName ($macAddress)${core.profile?.let { " · ${it.name}" } ?: ""}"
    override val kind: String get() = "Bluetooth"
    override val stockFirmwareLabel: String get() = "WiCAN (BLE)"

    private inner class AndroidLink(private val g: BluetoothGatt, private val device: BluetoothDevice) : GattLink {
        private fun char(s: UUID, c: UUID) = g.getService(s)?.getCharacteristic(c)
        override fun discoverServices() = g.discoverServices()
        override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) =
            char(service, characteristic)?.let { g.setCharacteristicNotification(it, enable) } ?: false
        @Suppress("DEPRECATION")
        override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean {
            val d = char(service, characteristic)?.getDescriptor(CCCD_UUID) ?: return false
            d.value = value
            return g.writeDescriptor(d)
        }
        @Suppress("DEPRECATION")
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
            val c = char(service, characteristic) ?: return false
            c.value = value
            c.writeType = writeType
            return g.writeCharacteristic(c)
        }
        override fun requestMtu(mtu: Int) = g.requestMtu(mtu)
        override fun bondState() = device.bondState
        override fun createBond() = device.createBond()
        override fun disconnect() = g.disconnect()
        override fun close() = g.close()
    }

    private fun callback(device: BluetoothDevice) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) core.onConnected(AndroidLink(g, device))
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) core.onDisconnected(status)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            core.onServicesDiscovered(status, g.services.associate { s ->
                s.uuid to s.characteristics.associate { it.uuid to it.properties }
            })
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            core.onDescriptorWrite(descriptor.uuid == CCCD_UUID, status)
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            core.onMtuChanged(mtu, status)
            android.util.Log.d("BLE", "MTU negotiated: ${core.mtu} (requested 247, status=$status)")
        }
        @Deprecated("Deprecated in API 33, but needed for API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            core.onNotify(characteristic.uuid, characteristic.value ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            core.onNotify(characteristic.uuid, value)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            core.onWrite(status)
    }

    override suspend fun open() {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: throw IOException("Bluetooth not available")
        val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
        // After a first successful setup, autoConnect=true lets Android reconnect when the adapter comes back in
        // range — robust for an adapter that power-cycles with the ignition
        val auto = macAddress in connectedBefore
        gatt = device.connectGatt(context, auto, callback(device), BluetoothDevice.TRANSPORT_LE)
        try {
            core.setup()
            core.profile?.let { android.util.Log.i("BLE", "Using ${it.name}${if (it.verified) "" else " (unverified layout)"}") }
            connectedBefore += macAddress
        } catch (e: Exception) {
            close()
            if (e is kotlinx.coroutines.CancellationException) throw e   // a cancel is a cancel, not a setup error
            throw if (e is IOException) e else IOException(e.message ?: "BLE setup failed", e)
        }
    }

    override suspend fun readLine(): String? = core.readLine()

    override suspend fun writeLine(frame: String) = core.writeLine(frame)

    override fun close() {
        core.close()                                            // closes the GATT once connected (via the link)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }   // or before it ever connected
        gatt = null
    }
}
