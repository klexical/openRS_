package com.openrs.dash.can

import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * The GATT calls the BLE transport makes (#218) — implemented over `BluetoothGatt` / `BluetoothDevice` by
 * [BleSlcanTransport], and by a fake in unit tests. Every call returns false when the stack refused to start it
 * (no callback will follow).
 */
interface GattLink {
    fun discoverServices(): Boolean
    fun setNotify(service: UUID, characteristic: UUID, enable: Boolean): Boolean
    /** Write the characteristic's CCCD; false = refused, or the characteristic has no CCCD. */
    fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean
    fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean
    fun requestMtu(mtu: Int): Boolean
    fun bondState(): Int
    fun createBond(): Boolean
    fun disconnect()
    fun close()
}

/**
 * The BLE transport's setup sequence, write path and line framing, driven by GATT callbacks ([onConnected] …
 * [onNotify]) — Android-free, so the callback sequence is unit-tested (BleTransportTest). Setup stops with a specific
 * error at the step that failed; every wait is bounded:
 *  1. connected → service discovery → a supported layout ([BleAdapterProfile.select]) whose write characteristic is
 *     writable and whose notify characteristic notifies or indicates;
 *  2. the notification subscription (CCCD: notify, or indicate when that is all it supports); an authentication /
 *     encryption failure pairs once ([BleSetupPolicy]) and repeats it;
 *  3. MTU 247 when the stack supports it.
 * Writes use the characteristic's own write type (with response when supported, else without) and wait at most
 * [writeTimeoutMs] for the write callback — a missing callback fails the write instead of holding the write lock, and
 * closes the link: the callback names no write, so a late one would otherwise acknowledge the next write.
 * A write's result is one of ([write], #248): sent; possibly delivered (GATT 13 — see [WriteOutcome.POSSIBLY_DELIVERED]);
 * not sent (refused before anything left the phone); or uncertain (failed after it was handed to the stack). Lines are
 * split at MTU − 3 as before; there is no chunking to a smaller size on purpose — the WiCAN Pro's SLCAN parser drops a
 * line whose continuation arrives more than 100 ms after its previous byte (upstream v4.48p `slcan_parse_str`).
 */
class BleGattCore(
    private val adapterType: String?,
    private val setupTimeoutMs: Long = 15_000L,
    private val pairingTimeoutMs: Long = 60_000L,
    private val writeTimeoutMs: Long = 2_000L,
) {
    companion object {
        // android.bluetooth constants (fixed by the platform), so this class stays Android-free
        const val PROPERTY_WRITE_NO_RESPONSE = 0x04
        const val PROPERTY_WRITE = 0x08
        const val PROPERTY_NOTIFY = 0x10
        const val PROPERTY_INDICATE = 0x20
        const val WRITE_TYPE_NO_RESPONSE = 1
        const val WRITE_TYPE_DEFAULT = 2
        val ENABLE_NOTIFICATION = byteArrayOf(0x01, 0x00)
        val ENABLE_INDICATION = byteArrayOf(0x02, 0x00)
        const val BOND_NONE = 10
        const val BOND_BONDING = 11
        const val BOND_BONDED = 12
        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 247
    }

    @Volatile var profile: BleAdapterProfile? = null
        private set
    @Volatile var mtu = DEFAULT_MTU
        private set
    /** This attempt's MTU request and its answer, for the export (#253); the core only writes to it. */
    val mtuTrace = BleMtuTrace()
    private var writeType = WRITE_TYPE_DEFAULT
    private var link: GattLink? = null

    private val lines = Channel<String>(256)
    private val lineBuffer = StringBuilder()
    /** The line being received passed [MAX_SLCAN_LINE_CHARS]: it is discarded up to its CR, and the next line starts clean. */
    private var discardingLine = false
    private val writeMutex = Mutex()
    @Volatile private var writeDone = CompletableDeferred<Int>()       // the write callback's GATT status
    /** A write went unacknowledged: callbacks can no longer be matched to writes, the link is closed for good. */
    @Volatile private var invalidated = false
    private val servicesReady = CompletableDeferred<Unit>()
    @Volatile private var subscribeStatus = CompletableDeferred<Int>()
    private val mtuReady = CompletableDeferred<Unit>()

    // ── GATT callbacks (any thread) ──────────────────────────────────────────

    fun onConnected(l: GattLink) {
        link = l
        if (!l.discoverServices()) servicesReady.completeExceptionally(IOException("BLE service discovery couldn't start"))
    }

    fun onDisconnected(status: Int) {
        val e = IOException("BLE disconnected (GATT $status)")
        if (!writeDone.isCompleted) writeDone.completeExceptionally(e)
        failSetup(IOException("BLE disconnected before setup completed (GATT $status)"))
        lines.close()                                          // EOF — SlcanConnection's retry takes over
    }

    /** [services]: service → characteristic → properties bitmask. */
    fun onServicesDiscovered(status: Int, services: Map<UUID, Map<UUID, Int>>) {
        if (status != BleSetupPolicy.GATT_SUCCESS) {
            servicesReady.completeExceptionally(IOException("BLE service discovery failed (GATT $status)")); return
        }
        val p = BleAdapterProfile.select(services.mapValues { it.value.keys }, adapterType)
        if (p == null) {
            val found = services.keys.joinToString { it.toString().substring(4, 8).uppercase() }
            servicesReady.completeExceptionally(IOException("No supported adapter service (found: ${found.ifEmpty { "none" }}) — " +
                "check the adapter's BLE is enabled and set to SLCAN")); return
        }
        val chars = services.getValue(p.service)
        val wp = chars.getValue(p.write)
        writeType = when {
            wp and PROPERTY_WRITE != 0 -> WRITE_TYPE_DEFAULT
            wp and PROPERTY_WRITE_NO_RESPONSE != 0 -> WRITE_TYPE_NO_RESPONSE
            else -> { servicesReady.completeExceptionally(IOException("${p.name}: write characteristic isn't writable")); return }
        }
        if (chars.getValue(p.notify) and (PROPERTY_NOTIFY or PROPERTY_INDICATE) == 0) {
            servicesReady.completeExceptionally(IOException("${p.name}: notify characteristic can't notify")); return
        }
        notifyProps = chars.getValue(p.notify)
        profile = p
        servicesReady.complete(Unit)
    }
    private var notifyProps = 0

    fun onDescriptorWrite(isCccd: Boolean, status: Int) { if (isCccd && !subscribeStatus.isCompleted) subscribeStatus.complete(status) }

    fun onMtuChanged(newMtu: Int, status: Int) {
        mtu = if (status == BleSetupPolicy.GATT_SUCCESS) newMtu else DEFAULT_MTU
        mtuTrace.callback(newMtu, status, mtu)
        if (!mtuReady.isCompleted) mtuReady.complete(Unit)
    }

    fun onWrite(status: Int) {
        if (invalidated) return                                // a late callback for a write that already timed out
        writeDone.complete(status)
    }

    /**
     * Notification bytes on [characteristic]; a frame may span notifications, a notification may hold several. A line past
     * [MAX_SLCAN_LINE_CHARS] is refused whole and counted; with the line queue full the newest line is dropped and counted.
     */
    fun onNotify(characteristic: UUID, bytes: ByteArray) {
        if (characteristic != profile?.notify) return
        for (b in bytes) {
            if (b.toInt() and 0xFF == 0x0D) {
                val line = lineBuffer.toString(); lineBuffer.clear()
                if (discardingLine) { discardingLine = false; continue }
                if (line.isNotEmpty()) {
                    val sent = lines.trySend(line)
                    if (sent.isFailure && !sent.isClosed) DiagnosticLogger.countLineDropped()   // full; closed = link gone
                }
            } else if (discardingLine) {
                continue
            } else if (lineBuffer.length < MAX_SLCAN_LINE_CHARS) {
                lineBuffer.append(b.toInt().toChar())
            } else {
                discardingLine = true
                lineBuffer.clear()
                DiagnosticLogger.countOverlongLine()
            }
        }
    }

    // ── Transport ────────────────────────────────────────────────────────────

    /** Waits for the connection callbacks and runs the setup steps; throws IOException naming the failed step. */
    suspend fun setup() {
        withTimeoutOrNull(setupTimeoutMs) { servicesReady.await() } ?: throw IOException("BLE connection timed out")
        val l = link ?: throw IOException("BLE not connected")
        val p = profile ?: throw IOException("BLE layout not selected")
        var retried = false
        while (true) {
            subscribeStatus = CompletableDeferred()
            if (!l.setNotify(p.service, p.notify, true)) throw IOException("BLE notification setup refused by the phone")
            val value = if (notifyProps and PROPERTY_NOTIFY != 0) ENABLE_NOTIFICATION else ENABLE_INDICATION
            if (!l.writeCccd(p.service, p.notify, value)) throw IOException("BLE subscription write couldn't be sent")
            val status = withTimeoutOrNull(setupTimeoutMs) { subscribeStatus.await() }
                ?: throw IOException("BLE notification subscription timed out")
            when (val action = BleSetupPolicy.afterSubscribe(status, retried)) {
                BleSetupPolicy.Action.Proceed -> break
                BleSetupPolicy.Action.BondAndRetry -> { bond(l); retried = true }
                is BleSetupPolicy.Action.Fail -> throw IOException(action.message)
            }
        }
        val started = l.requestMtu(REQUESTED_MTU)
        mtuTrace.requested(REQUESTED_MTU, started)
        if (started) withTimeoutOrNull(5_000L) { mtuReady.await() } ?: mtuTrace.waitTimedOut()
    }

    private suspend fun bond(l: GattLink) {
        if (l.bondState() == BOND_BONDED) return
        if (!l.createBond() && l.bondState() != BOND_BONDING) throw IOException("Couldn't start pairing with the adapter")
        var seenBonding = l.bondState() == BOND_BONDING
        val outcome = withTimeoutOrNull(pairingTimeoutMs) {
            while (true) {
                when (l.bondState()) {
                    BOND_BONDED -> return@withTimeoutOrNull true
                    BOND_BONDING -> seenBonding = true
                    BOND_NONE -> if (seenBonding) return@withTimeoutOrNull false
                }
                delay(250L)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        when (outcome) {
            true -> Unit
            false -> throw IOException("Pairing was refused or failed")
            null -> throw IOException("Pairing timed out — accept the pairing request on the phone")
        }
    }

    suspend fun readLine(): String? = try { lines.receive() } catch (_: Exception) { null }

    suspend fun writeLine(frame: String) { write(frame) }

    /** Writes [frame] (split at MTU − 3) and says what that established; throws [WriteFailure] not-sent / uncertain. */
    suspend fun write(frame: String): WriteOutcome = writeMutex.withLock {
        if (invalidated) throw WriteFailure.notSent("BLE link closed after an unacknowledged write")
        val l = link ?: throw WriteFailure.notSent("BLE not connected")
        val p = profile ?: throw WriteFailure.notSent("BLE not set up")
        val payload = frame.toByteArray(Charsets.ISO_8859_1)
        val max = mtu - 3                                       // ATT overhead
        var outcome = WriteOutcome.SENT
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + max, payload.size)
            writeDone = CompletableDeferred()
            if (!l.write(p.service, p.write, payload.copyOfRange(offset, end), writeType)) {
                // Nothing of this part left the phone — but an earlier part of the same line may have
                throw if (offset == 0) WriteFailure.notSent("BLE write couldn't be sent (stack busy or not connected)")
                    else WriteFailure.uncertain("BLE write couldn't be sent after $offset of ${payload.size} bytes")
            }
            val status = try {
                withTimeoutOrNull(writeTimeoutMs) { writeDone.await() } ?: run {
                    // Android's write callback carries no request id, so this write's late callback could not be told
                    // from the next write's — close the link instead of reusing it (#218). The reader sees EOF and
                    // SlcanConnection reconnects with a new transport; later writes here fail at once.
                    invalidated = true
                    close()
                    throw WriteFailure.uncertain("BLE write not acknowledged in $writeTimeoutMs ms — link closed")
                }
            } catch (e: IOException) {
                if (WriteFailure.isUncertain(e)) throw e
                throw WriteFailure.uncertain("BLE write interrupted (${e.message})", e)   // disconnected / closed mid-write
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw WriteFailure.uncertain("BLE write failed: ${e.message}", e)
            }
            when (status) {
                BleSetupPolicy.GATT_SUCCESS -> Unit
                BleSetupPolicy.GATT_INVALID_ATTRIBUTE_LENGTH -> outcome = WriteOutcome.POSSIBLY_DELIVERED
                else -> throw WriteFailure.uncertain(BleSetupPolicy.afterWrite(status) ?: "BLE write failed (GATT $status)")
            }
            offset = end
        }
        outcome
    }

    fun close() {
        if (!writeDone.isCompleted) writeDone.completeExceptionally(IOException("BLE closed"))
        failSetup(IOException("BLE closed"))
        link?.let { try { it.disconnect(); it.close() } catch (_: Exception) { } }
        link = null
        lines.close()
    }

    private fun failSetup(e: Exception) {
        if (!servicesReady.isCompleted) servicesReady.completeExceptionally(e)
        if (!subscribeStatus.isCompleted) subscribeStatus.completeExceptionally(e)
        if (!mtuReady.isCompleted) mtuReady.completeExceptionally(e)
    }
}
