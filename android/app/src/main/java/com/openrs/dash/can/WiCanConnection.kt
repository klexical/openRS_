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

        private const val WS_PATH   = "/ws"
        // S6 = 500 kbps; standard SLCAN array index 6 (see slcan.c sl_bitrate[])
        private const val SLCAN_INIT = "C\rS6\rO\r"
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
    ): Flow<Pair<Int, ByteArray>> = flow<Pair<Int, ByteArray>> {

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

                // ── New-ID discovery for debug tab ─────────────
                val seenIds   = mutableSetOf<Int>()
                var debugCount = 0
                var debugTimer = System.currentTimeMillis()
                var firmwareChecked = false

                // ── Main frame loop ─────────────────────────────
                while (currentCoroutineContext().isActive) {
                    val (opcode, payload) = readWsFrame(inp, out) ?: break
                    if (opcode == 0x8) break  // close frame

                    val msg = payload.toString(Charsets.UTF_8).trimEnd()

                    // Check probe response on first incoming message
                    if (!firmwareChecked) {
                        firmwareChecked = true
                        if (msg.startsWith("OPENRS:")) {
                            val version = msg.removePrefix("OPENRS:").trim()
                            com.openrs.dash.OpenRSDashApp.instance.isOpenRsFirmware.value = true
                            com.openrs.dash.diagnostics.DiagnosticLogger.isOpenRsFirmware = true
                            com.openrs.dash.diagnostics.DiagnosticLogger.firmwareVersion = "openRS_ $version"
                            addDebugLine("Firmware: openRS_ $version ✓")
                            com.openrs.dash.diagnostics.DiagnosticLogger.event("FIRMWARE", "openRS_ $version detected")
                            continue
                        } else {
                            com.openrs.dash.OpenRSDashApp.instance.isOpenRsFirmware.value = false
                            com.openrs.dash.diagnostics.DiagnosticLogger.isOpenRsFirmware = false
                            com.openrs.dash.diagnostics.DiagnosticLogger.firmwareVersion = "WiCAN stock"
                            addDebugLine("Firmware: WiCAN stock")
                            com.openrs.dash.diagnostics.DiagnosticLogger.event("FIRMWARE", "WiCAN stock (no openRS_ response)")
                        }
                    }

                    val frame = parseSlcanFrame(msg) ?: continue

                    // Log first occurrence of each new CAN ID
                    if (seenIds.size < 40 && frame.first !in seenIds) {
                        seenIds += frame.first
                        addDebugLine("new ID: 0x%03X".format(frame.first))
                    }

                    emit(frame)
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

    /** Send a masked WebSocket text frame (client → server MUST be masked per RFC 6455). */
    private fun sendWsText(out: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        check(payload.size <= 125) { "Payload too large for single-frame send" }
        val mask  = ByteArray(4).also { rng.nextBytes(it) }
        val frame = ByteArray(6 + payload.size)
        frame[0] = 0x81.toByte()                        // FIN=1, opcode=text(1)
        frame[1] = (0x80 or payload.size).toByte()      // MASK=1, len
        mask.copyInto(frame, 2)
        for (i in payload.indices) frame[6 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        out.write(frame); out.flush()
    }

    private fun sendWsPong(out: OutputStream, payload: ByteArray) {
        val mask  = ByteArray(4).also { rng.nextBytes(it) }
        val frame = ByteArray(6 + payload.size)
        frame[0] = 0x8A.toByte()
        frame[1] = (0x80 or payload.size).toByte()
        mask.copyInto(frame, 2)
        for (i in payload.indices) frame[6 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        out.write(frame); out.flush()
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
