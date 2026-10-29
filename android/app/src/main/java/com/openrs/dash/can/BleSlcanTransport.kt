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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * BLE GATT SLCAN transport for WiCAN adapters.
 *
 * The GATT layout comes from [BleAdapterProfile], chosen from the services the adapter actually exposes (#218):
 * WiCAN Pro FFF0 (write FFF2, notify FFF1), WiCAN USB FEE0 (FEE1 write + notify), legacy FFE0 as a last fallback.
 * Setup is a sequence that stops with a specific error at the step that failed — service discovery, the notification
 * subscription, pairing — instead of carrying on: both WiCAN layouts need an encrypted, authenticated (bonded) link,
 * so an unbonded phone is asked to pair once ([BleSetupPolicy]) and the subscription is retried.
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
    private val deviceName: String = "WiCAN",
    /** "MEATPI_PRO" / "MEATPI_USB" — only breaks ties between layouts the adapter exposes. */
    private val adapterType: String? = null,
) : SlcanTransport {

    companion object {
        val CCCD_UUID: UUID = BleAdapterProfile.CCCD
        private const val SETUP_TIMEOUT_MS = 15_000L
        /** The user has to read the PIN from the adapter's settings and type it — allow for that. */
        private const val PAIRING_TIMEOUT_MS = 60_000L
    }

    private var gatt: BluetoothGatt? = null
    @Volatile private var profile: BleAdapterProfile? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    // Incoming lines: BLE notifications accumulate into lineBuffer, complete
    // lines (\r-delimited) are sent to this channel.
    private val lineChannel = Channel<String>(64)
    private val lineBuffer = StringBuilder()

    // Write serialization: BLE allows only one outstanding write at a time.
    private val writeMutex = Mutex()
    private var writeComplete = CompletableDeferred<Unit>()

    // Setup steps — each completed (or failed) by its GATT callback.
    private var servicesReady = CompletableDeferred<Unit>()
    private var subscribeStatus = CompletableDeferred<Int>()
    private var mtuReady = CompletableDeferred<Unit>()
    private var negotiatedMtu = 23  // BLE default; updated after MTU negotiation

    // Auto-reconnect: after first successful connection, use autoConnect=true
    // so Android reconnects automatically when the device comes back in range.
    private var hasConnectedBefore = false

    override val label: String get() = "BLE $deviceName ($macAddress)${profile?.let { " · ${it.name}" } ?: ""}"
    override val stockFirmwareLabel: String get() = "WiCAN (BLE)"

    private fun failSetup(e: Exception) {
        if (!servicesReady.isCompleted) servicesReady.completeExceptionally(e)
        if (!subscribeStatus.isCompleted) subscribeStatus.completeExceptionally(e)
        if (!mtuReady.isCompleted) mtuReady.completeExceptionally(e)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Release any in-flight writeLine() blocked on writeComplete.await().
                // Without this, a disconnect mid-write deadlocks writeMutex indefinitely
                // because onCharacteristicWrite will never fire.
                if (!writeComplete.isCompleted) writeComplete.completeExceptionally(IOException("BLE disconnected"))
                // Also unblock open() if it's still in setup.
                failSetup(IOException("BLE disconnected before setup completed (GATT $status)"))
                // Signal EOF to the reader — SlcanConnection retry logic handles reconnect.
                lineChannel.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesReady.completeExceptionally(IOException("BLE service discovery failed (GATT $status)"))
                return
            }
            val services = gatt.services.associate { s -> s.uuid to s.characteristics.map { it.uuid }.toSet() }
            val p = BleAdapterProfile.select(services, adapterType)
            if (p == null) {
                val found = services.keys.joinToString { it.toString().substring(4, 8).uppercase() }
                servicesReady.completeExceptionally(IOException(
                    "No supported adapter service on $macAddress (found: ${found.ifEmpty { "none" }}) — " +
                        "check the adapter's BLE is enabled and set to SLCAN"))
                return
            }
            val service = gatt.getService(p.service)
            profile = p
            writeChar = service.getCharacteristic(p.write)
            notifyChar = service.getCharacteristic(p.notify)
            android.util.Log.i("BLE", "Using ${p.name}${if (p.verified) "" else " (unverified layout)"}")
            servicesReady.complete(Unit)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID && !subscribeStatus.isCompleted) subscribeStatus.complete(status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            android.util.Log.d("BLE", "MTU negotiated: $negotiatedMtu (requested 247, status=$status)")
            if (!mtuReady.isCompleted) mtuReady.complete(Unit)
        }

        @Deprecated("Deprecated in API 33, but needed for API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == profile?.notify) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value ?: return
                processIncoming(bytes)
            }
        }

        // API 33+ variant
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == profile?.notify) processIncoming(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // A failed write (e.g. not paired) used to complete as success
            when (val failure = BleSetupPolicy.afterWrite(status)) {
                null -> writeComplete.complete(Unit)
                else -> writeComplete.completeExceptionally(IOException(failure))
            }
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
        servicesReady = CompletableDeferred()
        subscribeStatus = CompletableDeferred()
        mtuReady = CompletableDeferred()

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: throw IOException("Bluetooth not available")
        val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
        // After first successful connection, use autoConnect=true so Android
        // automatically reconnects when the device comes back in range — more
        // robust for automotive use where the adapter may power-cycle with ignition.
        val auto = hasConnectedBefore
        val g = device.connectGatt(context, auto, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        try {
            // 1. Connected + services discovered + a supported layout found
            withTimeoutOrNull(SETUP_TIMEOUT_MS) { servicesReady.await() }
                ?: throw IOException("BLE connection timed out (autoConnect=$auto)")
            // 2. Subscribe to notifications; pair once if the adapter requires an authenticated link
            var retried = false
            while (true) {
                subscribeStatus = CompletableDeferred()
                subscribe(g)
                val status = withTimeoutOrNull(SETUP_TIMEOUT_MS) { subscribeStatus.await() }
                    ?: throw IOException("BLE notification subscription timed out")
                when (val action = BleSetupPolicy.afterSubscribe(status, retried)) {
                    BleSetupPolicy.Action.Proceed -> break
                    BleSetupPolicy.Action.BondAndRetry -> { bond(device); retried = true }
                    is BleSetupPolicy.Action.Fail -> throw IOException(action.message)
                }
            }
            // 3. Larger MTU if the stack supports it (23-byte default otherwise)
            if (g.requestMtu(247)) withTimeoutOrNull(5_000L) { mtuReady.await() }
            else android.util.Log.w("BLE", "requestMtu(247) not supported, using default MTU 23")
            hasConnectedBefore = true
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException(e.message ?: "BLE setup failed", e)
        }
    }

    /** Enable notifications on the profile's notify characteristic (local flag + CCCD write on the adapter). */
    private fun subscribe(g: BluetoothGatt) {
        val nc = notifyChar ?: throw IOException("BLE notify characteristic missing")
        if (!g.setCharacteristicNotification(nc, true)) throw IOException("BLE notification setup refused by the phone")
        val cccd = nc.getDescriptor(CCCD_UUID) ?: throw IOException("BLE notify characteristic has no CCCD")
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        if (!g.writeDescriptor(cccd)) throw IOException("BLE subscription write couldn't be sent")
    }

    /** Pair with the adapter (the system shows its PIN prompt) and wait for the result. */
    private suspend fun bond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        android.util.Log.i("BLE", "Adapter requires pairing — requesting bond with $macAddress")
        if (!device.createBond() && device.bondState != BluetoothDevice.BOND_BONDING) {
            throw IOException("Couldn't start pairing with the adapter")
        }
        val outcome = withTimeoutOrNull(PAIRING_TIMEOUT_MS) {
            var seenBonding = device.bondState == BluetoothDevice.BOND_BONDING
            while (true) {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> return@withTimeoutOrNull true
                    BluetoothDevice.BOND_BONDING -> seenBonding = true
                    BluetoothDevice.BOND_NONE -> if (seenBonding) return@withTimeoutOrNull false
                }
                delay(250L)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        when (outcome) {
            true -> android.util.Log.i("BLE", "Paired with $macAddress")
            false -> throw IOException("Pairing was refused or failed")
            null -> throw IOException("Pairing timed out — accept the pairing request on the phone")
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
        val char = writeChar ?: return
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
            val dispatched = g.writeCharacteristic(char)
            if (!dispatched) {
                // writeCharacteristic returns false when the stack is busy or the
                // GATT is not connected. Without this, the await below would hang
                // because no onCharacteristicWrite callback will fire.
                throw IOException("BLE writeCharacteristic returned false")
            }
            try {
                writeComplete.await()
            } catch (e: Exception) {
                // Disconnect, close() or a failed write status — surface it to SlcanConnection retry logic.
                throw IOException("BLE write failed: ${e.message}", e)
            }

            offset = end
        }
    }

    override fun close() {
        // Release any in-flight writeLine() awaiters before tearing down.
        if (!writeComplete.isCompleted) writeComplete.completeExceptionally(IOException("BLE closed"))
        failSetup(IOException("BLE closed"))
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) { }
        gatt = null
        writeChar = null
        notifyChar = null
        lineChannel.close()
    }
}
