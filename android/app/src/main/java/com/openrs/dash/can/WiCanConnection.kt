package com.openrs.dash.can

import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * WiCAN WebSocket SLCAN connection.
 *
 * Connects to ws://host:80/ws, initializes SLCAN at 500 kbps (S6),
 * and streams raw HS-CAN frames for CanDecoder.
 *
 * The WiCAN ELM327 TCP path cannot reliably send/receive OBD requests
 * (the firmware's internal ~200 ms timeout cascades into TCP stalls).
 * The WebSocket monitor path works out-of-the-box at full bus speed.
 *
 * SLCAN speed table in slcan.c:
 *   sl_bitrate[] = {10K, 20K, 50K, 100K, 125K, 250K, 500K, 800K, 1000K}
 *   → S6 = 500 kbps (standard SLCAN index 6)
 *
 * Frame format from firmware (slcan_parse_frame):
 *   t{ID3}{DLC}{DATA}   standard 11-bit frame
 *   T{ID8}{DLC}{DATA}   extended 29-bit frame (rare on HS-CAN)
 */
class WiCanConnection(
    private val host: String = "192.168.80.1",
    private val port: Int = 80,
    private val maxRetries: Int = 3,
    /** Delay (ms) before reconnecting after a dropped connection. */
    private val reconnectDelayMs: Long = 5_000L
) {
    // ── DEBUG ONLY (do not commit) ──────────────────────────
    val debugLines: StateFlow<List<String>> get() = OpenRSDashApp.instance.debugLines
    private fun addDebugLine(line: String) = OpenRSDashApp.instance.pushDebugLine(line)

    companion object {
        val  BACKOFF_MS        = listOf(5_000L, 15_000L, 30_000L)
        const val RECONNECT_DELAY_MS = 5_000L

        private const val WS_PATH    = "/ws"
        private const val SLCAN_INIT = "C\rS6\rO\r"

        // ── BCM OBD Mode 22 polling (ISO-TP over SLCAN) ────────────────────
        // Request address 0x726, response address 0x72E (standard Ford BCM).
        // SLCAN standard frame: t{ID3}{DLC}{DATA_HEX}\r
        // ISO-TP single-frame: PCI=0x03 (3 payload bytes), Mode=0x22, DID (2 bytes), padding
        const val BCM_RESPONSE_ID         = 0x72E
        private const val BCM_QUERY_ODOMETER   = "t72680322DD0100000000\r"  // 0xDD01 odometer
        private const val BCM_QUERY_SOC        = "t72680322402800000000\r"  // 0x4028 battery SOC
        private const val BCM_QUERY_BATT_TEMP  = "t72680322402900000000\r"  // 0x4029 battery temp
        private const val BCM_QUERY_CABIN_TEMP = "t72680322DD0400000000\r"  // 0xDD04 cabin temp
        private val BCM_QUERIES = listOf(
            BCM_QUERY_ODOMETER, BCM_QUERY_SOC, BCM_QUERY_BATT_TEMP, BCM_QUERY_CABIN_TEMP
        )
        /** How long between complete BCM poll cycles (ms). */
        private const val BCM_POLL_INTERVAL_MS = 30_000L
        /** Gap between individual queries in a cycle (ms). */
        private const val BCM_QUERY_GAP_MS     =    300L
        /** Delay after connect before first BCM poll (ms). */
        private const val BCM_INITIAL_DELAY_MS =  5_000L
    }

    sealed class State {
        data object Disconnected : State()
        data object Connecting   : State()
        data object Connected    : State()
        /** All [maxRetries] attempts failed — waiting for manual reconnect. */
        data object Idle         : State()
        data class  Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private var frameCount   = 0
    private var lastFpsTime  = System.currentTimeMillis()
    private var _fps         = 0.0
    val fps: Double get() = _fps

    private val rng = SecureRandom()

    /**
     * Connect to the WiCAN WebSocket, open the SLCAN channel, and emit
     * (canId, dataBytes) pairs for every received CAN frame.
     *
     * [onObdUpdate] is kept for API compatibility but is never invoked
     * in this WebSocket-based implementation (no OBD queries needed —
     * all engine data arrives as broadcast CAN frames).
     */
    fun connectHybrid(
        onObdUpdate: (VehicleState) -> Unit,
        getCurrentState: () -> VehicleState
    ): Flow<Pair<Int, ByteArray>> = channelFlow<Pair<Int, ByteArray>> {

        var failedAttempts = 0

        while (currentCoroutineContext().isActive && failedAttempts < maxRetries) {
            var connectionSucceeded = false
            try {
                _state.value = State.Connecting
                addDebugLine("--- WebSocket SLCAN ---")
                addDebugLine("Connecting to ws://$host:$port$WS_PATH")

                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 10_000  // 10 s read timeout for frames

                val inp = socket.getInputStream()
                val out = socket.getOutputStream()

                // ── HTTP → WebSocket upgrade ──────────────────
                sendHttpUpgrade(out)
                val headers = readHttpHeaders(inp)
                if (!headers.contains("101")) {
                    throw IOException("Upgrade failed: ${headers.take(80).replace('\n', ' ')}")
                }
                addDebugLine("WebSocket handshake OK")

                // ── SLCAN init: close, set 500 K, open ────────
                sendWsText(out, SLCAN_INIT)
                addDebugLine("SLCAN init sent (C / S6 / O)")

                // ── Probe for openRS_ firmware ─────────────────
                // Stock WiCAN ignores this; openRS_ responds with "OPENRS:<version>"
                // The probe response (if any) arrives as the very first WS frame.
                sendWsText(out, "OPENRS?\r")
                addDebugLine("Probing firmware...")

                _state.value = State.Connected
                connectionSucceeded = true
                failedAttempts = 0

                // ── BCM OBD poller — polls 4 Mode 22 PIDs every 30 s ──────────
                // Sends ISO-TP single-frame requests to BCM (0x726) via SLCAN.
                // Responses arrive as normal CAN frames on ID 0x72E and are
                // intercepted in the main frame loop below.
                val obdJob = launch {
                    delay(BCM_INITIAL_DELAY_MS)
                    while (isActive) {
                        BCM_QUERIES.forEach { q ->
                            try { sendWsText(out, q) } catch (_: Exception) { return@forEach }
                            delay(BCM_QUERY_GAP_MS)
                        }
                        delay(BCM_POLL_INTERVAL_MS)
                    }
                }

                // ── New-ID discovery for debug tab ─────────────
                val seenIds   = mutableSetOf<Int>()
                var debugCount = 0
                var debugTimer = System.currentTimeMillis()
                // Firmware probe: check every incoming message until confirmed.
                // At ~2000 fps the first WebSocket frame is almost always a CAN
                // frame, not the probe response — so we can't rely on checking
                // only the first message. We check all frames and declare "stock"
                // after PROBE_GRACE_FRAMES normal SLCAN frames with no response.
                var firmwareKnown = false
                var probeFramesSeen = 0
                val PROBE_GRACE_FRAMES = 20

                // ── Main frame loop ─────────────────────────────
                try {
                while (currentCoroutineContext().isActive) {
                    val (opcode, payload) = readWsFrame(inp, out) ?: break
                    if (opcode == 0x8) break  // close frame

                    val msg = payload.toString(Charsets.UTF_8).trimEnd()

                    // Check every message for openRS_ probe response until confirmed
                    if (!firmwareKnown) {
                        if (msg.startsWith("OPENRS:")) {
                            firmwareKnown = true
                            val version = msg.removePrefix("OPENRS:").trim()
                            com.openrs.dash.OpenRSDashApp.instance.isOpenRsFirmware.value = true
                            com.openrs.dash.diagnostics.DiagnosticLogger.isOpenRsFirmware = true
                            com.openrs.dash.diagnostics.DiagnosticLogger.firmwareVersion = "openRS_ $version"
                            addDebugLine("Firmware: openRS_ $version ✓")
                            com.openrs.dash.diagnostics.DiagnosticLogger.event("FIRMWARE", "openRS_ $version detected")
                            continue
                        } else if (msg.startsWith("t") || msg.startsWith("T")) {
                            // Count normal SLCAN frames; after grace period declare stock
                            probeFramesSeen++
                            if (probeFramesSeen >= PROBE_GRACE_FRAMES) {
                                firmwareKnown = true
                                com.openrs.dash.OpenRSDashApp.instance.isOpenRsFirmware.value = false
                                com.openrs.dash.diagnostics.DiagnosticLogger.isOpenRsFirmware = false
                                com.openrs.dash.diagnostics.DiagnosticLogger.firmwareVersion = "WiCAN stock"
                                addDebugLine("Firmware: WiCAN stock")
                                com.openrs.dash.diagnostics.DiagnosticLogger.event("FIRMWARE", "WiCAN stock (no openRS_ response)")
                            }
                        }
                    }

                    val frame = parseSlcanFrame(msg) ?: continue

                    // ── BCM OBD response — parse and forward via callback ───────
                    if (frame.first == BCM_RESPONSE_ID) {
                        parseBcmResponse(frame.second, getCurrentState(), onObdUpdate)
                        continue
                    }

                    // Log first occurrence of each new CAN ID
                    if (seenIds.size < 40 && frame.first !in seenIds) {
                        seenIds += frame.first
                        addDebugLine("new ID: 0x%03X".format(frame.first))
                    }

                    send(frame)
                    trackFps()
                    debugCount++

                    val now = System.currentTimeMillis()
                    if (now - debugTimer >= 3_000) {
                        addDebugLine("CAN: ${debugCount} frames / 3 s  (${_fps.toInt()} fps)")
                        com.openrs.dash.diagnostics.DiagnosticLogger.fps(_fps)
                        debugCount = 0
                        debugTimer = now
                    }
                }
                } finally {
                    obdJob.cancel()
                }

                socket.close()

            } catch (e: CancellationException) {
                _state.value = State.Disconnected
                throw e
            } catch (e: Exception) {
                if (!connectionSucceeded) failedAttempts++
                _state.value = State.Error(e.message ?: "Connection failed")
                addDebugLine("Error: ${e.message}")
            }

            if (!currentCoroutineContext().isActive) break

            if (failedAttempts >= maxRetries) {
                _state.value = State.Idle
                break
            }

            _state.value = State.Disconnected
            val delayMs = if (connectionSucceeded) reconnectDelayMs
                          else BACKOFF_MS.getOrElse(failedAttempts - 1) { 30_000L }
            delay(delayMs)
        }
    }.flowOn(Dispatchers.IO)

    // ── SLCAN frame parser ──────────────────────────────────────────────────

    private fun parseSlcanFrame(msg: String): Pair<Int, ByteArray>? {
        if (msg.isEmpty()) return null
        return when (msg[0]) {
            't' -> parseStdFrame(msg)   // 11-bit standard
            'T' -> parseExtFrame(msg)   // 29-bit extended (unlikely on HS-CAN)
            else -> null
        }
    }

    private fun parseStdFrame(msg: String): Pair<Int, ByteArray>? {
        // t{ID3}{DLC}{DATA}
        if (msg.length < 5) return null
        val id  = msg.substring(1, 4).toIntOrNull(16) ?: return null
        val dlc = msg[4].digitToIntOrNull(10) ?: return null
        if (dlc < 0 || dlc > 8 || msg.length < 5 + dlc * 2) return null
        return Pair(id, parseDataBytes(msg, 5, dlc))
    }

    private fun parseExtFrame(msg: String): Pair<Int, ByteArray>? {
        // T{ID8}{DLC}{DATA}
        if (msg.length < 10) return null
        val id  = msg.substring(1, 9).toLongOrNull(16)?.toInt() ?: return null
        val dlc = msg[9].digitToIntOrNull(10) ?: return null
        if (dlc < 0 || dlc > 8 || msg.length < 10 + dlc * 2) return null
        return Pair(id, parseDataBytes(msg, 10, dlc))
    }

    private fun parseDataBytes(msg: String, start: Int, dlc: Int): ByteArray =
        try {
            ByteArray(dlc) { i -> msg.substring(start + i * 2, start + i * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) { ByteArray(0) }

    // ── WebSocket helpers ───────────────────────────────────────────────────

    private fun sendHttpUpgrade(out: OutputStream) {
        // Static key is fine for local-network, non-TLS usage
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val req = "GET $WS_PATH HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n"
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readHttpHeaders(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) break
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    /** Send a masked WebSocket text frame (client → server MUST be masked per RFC 6455).
     *  Synchronized on [out] so the OBD poller coroutine and the pong responder
     *  don't interleave bytes on the same socket stream. */
    private fun sendWsText(out: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        check(payload.size <= 125) { "Payload too large for single-frame send" }
        synchronized(out) {
            val mask  = ByteArray(4).also { rng.nextBytes(it) }
            val frame = ByteArray(6 + payload.size)
            frame[0] = 0x81.toByte()
            frame[1] = (0x80 or payload.size).toByte()
            mask.copyInto(frame, 2)
            for (i in payload.indices) frame[6 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            out.write(frame); out.flush()
        }
    }

    private fun sendWsPong(out: OutputStream, payload: ByteArray) {
        synchronized(out) {
            val mask  = ByteArray(4).also { rng.nextBytes(it) }
            val frame = ByteArray(6 + payload.size)
            frame[0] = 0x8A.toByte()
            frame[1] = (0x80 or payload.size).toByte()
            mask.copyInto(frame, 2)
            for (i in payload.indices) frame[6 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            out.write(frame); out.flush()
        }
    }

    /**
     * Parse an ISO-TP single-frame response from BCM (CAN ID 0x72E) and call
     * [onObdUpdate] with the relevant field updated in [currentState].
     *
     * Response frame layout (from MeatPi vehicle profile, Ford BCM 0x726→0x72E):
     *   data[0] = PCI (SF: high nibble 0x0, low nibble = payload length)
     *   data[1] = 0x62 (positive response to Mode 0x22)
     *   data[2] = DID high byte
     *   data[3] = DID low byte
     *   data[4..] = B4, B5, B6… (MeatPi B-notation data bytes)
     */
    private fun parseBcmResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) return
        if ((data[1].toInt() and 0xFF) != 0x62) return  // not positive response
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0xDD01 -> {  // Odometer: [B4:B6] km
                if (data.size < 7) return
                val km = (b4 shl 16) or
                         ((data[5].toInt() and 0xFF) shl 8) or
                         (data[6].toInt() and 0xFF)
                onObdUpdate(currentState.copy(odometerKm = km.toLong()))
            }
            0x4028 -> {  // Battery SOC: B4 %
                onObdUpdate(currentState.copy(batterySoc = b4.toDouble()))
            }
            0x4029 -> {  // Battery temp: B4 - 40 °C
                onObdUpdate(currentState.copy(batteryTempC = (b4 - 40).toDouble()))
            }
            0xDD04 -> {  // Cabin temp: (B4 × 10/9) - 45 °C
                onObdUpdate(currentState.copy(cabinTempC = (b4 * 10.0 / 9.0) - 45.0))
            }
        }
    }

    private fun readExactly(inp: InputStream, n: Int): ByteArray {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r == -1) throw IOException("Stream closed")
            off += r
        }
        return buf
    }

    /**
     * Read one WebSocket frame.  Handles ping (auto-pong) and pong (ignore).
     * Returns (opcode, payload) or null if the connection is closed gracefully.
     */
    private fun readWsFrame(inp: InputStream, out: OutputStream): Pair<Int, ByteArray>? {
        while (true) {
            val b0 = inp.read(); if (b0 == -1) return null
            val b1 = inp.read(); if (b1 == -1) return null

            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len    = (b1 and 0x7F)

            len = when {
                len == 126 -> {
                    val ext = readExactly(inp, 2)
                    ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
                }
                len == 127 -> {
                    // 8-byte extended length — skip frame
                    val lenBytes = readExactly(inp, 8)
                    val bigLen = lenBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                    readExactly(inp, bigLen.toInt())  // discard
                    continue
                }
                else -> len
            }

            val mask    = if (masked) readExactly(inp, 4) else null
            val payload = readExactly(inp, len)
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }

            when (opcode) {
                0x9 -> { sendWsPong(out, payload); continue }  // ping → pong
                0xA -> continue                                  // pong, ignore
                else -> return Pair(opcode, payload)
            }
        }
    }

    // ── FPS tracking ────────────────────────────────────────────────────────

    private fun trackFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1_000) {
            _fps = frameCount * 1_000.0 / (now - lastFpsTime)
            frameCount  = 0
            lastFpsTime = now
        }
    }
}
