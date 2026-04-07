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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE GATT SLCAN transport for WiCAN adapters.
 *
 * Connects to the adapter's BLE service (UUID 0xFFE0) and exchanges SLCAN
 * frames via two characteristics:
 *   - FFE1 (write): app sends SLCAN commands
 *   - FFE2 (notify): firmware sends SLCAN responses
 *
 * BLE is protocol-compatible with the WiFi TCP interface — same `\r`-delimited
 * SLCAN frames. The transport handles BLE packet boundaries transparently:
 * a single SLCAN frame may span multiple BLE notifications, or one notification
 * may contain multiple frames.
 */
@SuppressLint("MissingPermission")
class BleSlcanTransport(
    private val context: Context,
    private val macAddress: String,
    private val deviceName: String = "WiCAN"
) : SlcanTransport {

    companion object {
        val SERVICE_UUID: UUID  = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val CHAR_RX_UUID: UUID  = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val CHAR_TX_UUID: UUID  = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID     = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    // Incoming lines: BLE notifications accumulate into lineBuffer, complete
    // lines (\r-delimited) are sent to this channel.
    private val lineChannel = Channel<String>(64)
    private val lineBuffer = StringBuilder()

    // Write serialization: BLE allows only one outstanding write at a time.
    private val writeMutex = Mutex()
    private var writeComplete = CompletableDeferred<Unit>()

    // Connection setup deferred — signals when GATT is ready (services discovered,
    // notifications enabled, MTU negotiated).
    private var connectDeferred = CompletableDeferred<Unit>()
    private var negotiatedMtu = 23  // BLE default; updated after MTU negotiation

    // Auto-reconnect: after first successful connection, use autoConnect=true
    // so Android reconnects automatically when the device comes back in range.
    private var hasConnectedBefore = false

    override val label: String get() = "BLE $deviceName ($macAddress)"
    override val stockFirmwareLabel: String get() = "WiCAN (BLE)"

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Signal EOF to the reader — SlcanConnection retry logic handles reconnect.
                lineChannel.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectDeferred.completeExceptionally(
                    RuntimeException("BLE service discovery failed (status=$status)")
                )
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                connectDeferred.completeExceptionally(
                    RuntimeException("BLE service 0xFFE0 not found on $macAddress")
                )
                return
            }

            rxChar = service.getCharacteristic(CHAR_RX_UUID)
            val txChar = service.getCharacteristic(CHAR_TX_UUID)

            if (rxChar == null || txChar == null) {
                connectDeferred.completeExceptionally(
                    RuntimeException("BLE characteristics FFE1/FFE2 not found")
                )
                return
            }

            // Enable notifications on TX characteristic (FFE2)
            gatt.setCharacteristicNotification(txChar, true)
            val descriptor = txChar.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            } else {
                // No CCCD — proceed with MTU negotiation anyway
                gatt.requestMtu(247)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            // CCCD written — now request larger MTU for bigger SLCAN frames.
            // If requestMtu returns false (unsupported), proceed with 23-byte default.
            if (!gatt.requestMtu(247)) {
                android.util.Log.w("BLE", "requestMtu(247) not supported, using default MTU 23")
                hasConnectedBefore = true
                connectDeferred.complete(Unit)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            android.util.Log.d("BLE", "MTU negotiated: $negotiatedMtu (requested 247, status=$status)")
            // GATT is fully ready
            hasConnectedBefore = true
            connectDeferred.complete(Unit)
        }

        @Deprecated("Deprecated in API 33, but needed for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_TX_UUID) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value ?: return
                processIncoming(bytes)
            }
        }

        // API 33+ variant
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_TX_UUID) {
                processIncoming(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            writeComplete.complete(Unit)
        }
    }

    /**
     * Process incoming bytes from a BLE notification.
     * Handles packet boundaries: a notification may contain partial lines,
     * complete lines, or multiple lines.
     */
    private fun processIncoming(bytes: ByteArray) {
        for (b in bytes) {
            if (b.toInt() and 0xFF == 0x0D) {
                // \r delimiter — emit complete line
                val line = lineBuffer.toString()
                lineBuffer.clear()
                if (line.isNotEmpty()) {
                    lineChannel.trySend(line)
                }
            } else {
                if (lineBuffer.length < 256) {  // guard runaway
                    lineBuffer.append(b.toInt().toChar())
                }
            }
        }
    }

    override suspend fun open() {
        connectDeferred = CompletableDeferred()

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
            ?: throw RuntimeException("Bluetooth not available")

        val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
        // After first successful connection, use autoConnect=true so Android
        // automatically reconnects when the device comes back in range — more
        // robust for automotive use where the adapter may power-cycle with ignition.
        val auto = hasConnectedBefore
        gatt = device.connectGatt(context, auto, gattCallback, BluetoothDevice.TRANSPORT_LE)

        // Wait for full GATT setup (services + notifications + MTU).
        // 15s timeout covers autoConnect=true which may take longer.
        val result = withTimeoutOrNull(15_000L) { connectDeferred.await() }
        if (result == null) {
            // Timed out — close and let SlcanConnection retry logic handle it.
            close()
            throw RuntimeException("BLE connection timed out (autoConnect=$auto)")
        }
    }

    override suspend fun readLine(): String? {
        return try {
            lineChannel.receive()
        } catch (_: Exception) {
            // Channel closed = disconnected
            null
        }
    }

    override suspend fun writeLine(frame: String) = writeMutex.withLock {
        val g = gatt ?: return
        val char = rxChar ?: return
        val payload = frame.toByteArray(Charsets.ISO_8859_1)
        val maxPayload = negotiatedMtu - 3  // ATT overhead

        // Chunk writes if payload exceeds MTU
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxPayload, payload.size)
            val chunk = payload.copyOfRange(offset, end)

            writeComplete = CompletableDeferred()
            @Suppress("DEPRECATION")
            char.value = chunk
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
            writeComplete.await()

            offset = end
        }
    }

    override fun close() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) { }
        gatt = null
        rxChar = null
        lineChannel.close()
    }
}
