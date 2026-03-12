package com.openrs.dash.can

import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DtcModuleSpec
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
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
    private fun addDebugLine(line: String) = OpenRSDashApp.instance.pushDebugLine(line)

    companion object {
        private const val WS_PATH = "/ws"
    }

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Disconnected)
    val state: StateFlow<AdapterState> = _state.asStateFlow()

    private var frameCount   = 0
    private var lastFpsTime  = System.currentTimeMillis()
    private var _fps         = 0.0
    val fps: Double get() = _fps

    private val rng = SecureRandom()

    // ── DTC scan infrastructure ──────────────────────────────────────────────
    @Volatile private var _wsOut: OutputStream? = null
    private val _dtcChannel = Channel<Pair<Int, ByteArray>>(128)
    private val _dtcMutex = Mutex()
    @Volatile private var _dtcWatchIds: Set<Int> = emptySet()
    @Volatile private var _dtcScanActive: Boolean = false

    suspend fun performDtcScan(modules: List<DtcModuleSpec>): Map<String, ByteArray> =
        _dtcMutex.withLock {
            val out = _wsOut ?: return emptyMap()
            val results = mutableMapOf<String, ByteArray>()

            _dtcWatchIds = modules.map { it.responseId }.toSet()
            _dtcScanActive = true
            while (_dtcChannel.tryReceive().isSuccess) { /* drain stale */ }

            try {
                for (module in modules) {
                    val reqFrame = buildDtcRequestFrame(module.requestId)
                    try { sendWsText(out, reqFrame) } catch (_: Exception) { continue }
                    val payload = assembleIsotpResponse(
                        responseId = module.responseId,
                        requestId  = module.requestId,
                        out        = out,
                        timeoutMs  = 2_500L
                    )
                    if (payload != null) results[module.name] = payload
                    delay(350L)
                }
            } finally {
                _dtcScanActive = false
                _dtcWatchIds   = emptySet()
            }
            results
        }

    suspend fun performDtcClear(modules: List<DtcModuleSpec>): Map<String, Boolean> =
        _dtcMutex.withLock {
            val out = _wsOut ?: return emptyMap()
            val results = mutableMapOf<String, Boolean>()

            _dtcWatchIds = modules.map { it.responseId }.toSet()
            _dtcScanActive = true
            while (_dtcChannel.tryReceive().isSuccess) { /* drain stale */ }

            try {
                for (module in modules) {
                    val reqFrame = buildDtcClearFrame(module.requestId)
                    try { sendWsText(out, reqFrame) } catch (_: Exception) { continue }
                    val payload = assembleIsotpResponse(
                        responseId = module.responseId,
                        requestId  = module.requestId,
                        out        = out,
                        timeoutMs  = 2_000L
                    )
                    results[module.name] = payload != null &&
                        payload.isNotEmpty() &&
                        (payload[0].toInt() and 0xFF) == 0x54
                    delay(300L)
                }
            } finally {
                _dtcScanActive = false
                _dtcWatchIds   = emptySet()
            }
            results
        }

    private fun buildDtcRequestFrame(requestId: Int): String =
        "t%03X8031902FF00000000\r".format(requestId)

    private fun buildDtcClearFrame(requestId: Int): String =
        "t%03X80414FFFFFF000000\r".format(requestId)

    private fun buildFlowControlFrame(requestId: Int): String =
        "t%03X83000000000000000\r".format(requestId)

    private suspend fun assembleIsotpResponse(
        responseId: Int,
        requestId: Int,
        out: OutputStream,
        timeoutMs: Long
    ): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var totalLen = 0
        var received = 0
        val buf = ByteArrayOutputStream()
        var seqNext = 1

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break

            val frame = withTimeoutOrNull(remaining) { _dtcChannel.receive() }
                ?: break

            if (frame.first != responseId) continue
            val data = frame.second
            if (data.isEmpty()) continue

            when ((data[0].toInt() and 0xF0) shr 4) {
                0 -> {
                    val len = data[0].toInt() and 0x0F
                    if (len <= 0 || data.size < len + 1) return null
                    return data.copyOfRange(1, 1 + len)
                }
                1 -> {
                    totalLen = ((data[0].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
                    val firstBytes = minOf(6, data.size - 2)
                    if (firstBytes > 0) buf.write(data, 2, firstBytes)
                    received = firstBytes
                    try { sendWsText(out, buildFlowControlFrame(requestId)) } catch (_: Exception) { }
                }
                2 -> {
                    val seq = data[0].toInt() and 0x0F
                    if (seq != seqNext) break
                    seqNext = (seqNext + 1) and 0x0F
                    val need = totalLen - received
                    val take = minOf(7, data.size - 1, need)
                    if (take <= 0) break
                    buf.write(data, 1, take)
                    received += take
                    if (received >= totalLen) return buf.toByteArray()
                }
            }
        }

        return if (buf.size() > 0) buf.toByteArray() else null
    }

    // ── Connection ───────────────────────────────────────────────────────────

    fun connectHybrid(
        onObdUpdate: (VehicleState) -> Unit,
        getCurrentState: () -> VehicleState
    ): Flow<Pair<Int, ByteArray>> = channelFlow<Pair<Int, ByteArray>> {

        var failedAttempts = 0
        var consecutiveDrops = 0

        while (currentCoroutineContext().isActive && failedAttempts < maxRetries) {
            var connectionSucceeded = false
            var connectedAtMs = 0L
            try {
                _state.value = AdapterState.Connecting
                addDebugLine("--- WebSocket SLCAN ---")
                addDebugLine("Connecting to ws://$host:$port$WS_PATH")

                val socket = Socket()
                try {
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 20_000

                val inp = socket.getInputStream()
                val out = socket.getOutputStream()

                sendHttpUpgrade(out)
                val headers = readHttpHeaders(inp)
                if (!headers.contains("101")) {
                    throw IOException("Upgrade failed: ${headers.take(80).replace('\n', ' ')}")
                }
                addDebugLine("WebSocket handshake OK")

                sendWsText(out, ObdConstants.SLCAN_INIT)
                addDebugLine("SLCAN init sent (C / S6 / O)")

                sendWsText(out, "OPENRS?\r")
                addDebugLine("Probing firmware...")

                _state.value = AdapterState.Connected
                _wsOut = out
                connectionSucceeded = true
                connectedAtMs = System.currentTimeMillis()
                failedAttempts = 0

                // ── BCM OBD poller ───────────────────────────────────────────
                val obdJob = launch {
                    delay(ObdConstants.BCM_INITIAL_DELAY_MS)
                    while (isActive) {
                        ObdConstants.BCM_QUERIES.forEach { q ->
                            try { sendWsText(out, q) } catch (_: Exception) { }
                            delay(ObdConstants.BCM_QUERY_GAP_MS)
                        }
                        ObdConstants.AWD_QUERIES.forEach { q ->
                            try { sendWsText(out, q) } catch (_: Exception) { }
                            delay(ObdConstants.BCM_QUERY_GAP_MS)
                        }
                        delay(ObdConstants.BCM_POLL_INTERVAL_MS)
                    }
                }

                val pcmJob = launch {
                    delay(ObdConstants.PCM_INITIAL_DELAY_MS)
                    while (isActive) {
                        ObdConstants.PCM_QUERIES.forEach { q ->
                            try { sendWsText(out, q) } catch (_: Exception) { }
                            delay(ObdConstants.PCM_QUERY_GAP_MS)
                        }
                        delay(ObdConstants.PCM_POLL_INTERVAL_MS)
                    }
                }

                // ── Extended diagnostic session poller ───────────────────────
                val extJob = launch {
                    delay(ObdConstants.EXT_INITIAL_DELAY_MS)
                    while (isActive) {
                        try {
                            sendWsText(out, ObdConstants.EXT_SESSION_BCM);   delay(ObdConstants.EXT_SESSION_GAP_MS)
                            sendWsText(out, ObdConstants.BCM_QUERY_ODOMETER); delay(ObdConstants.EXT_QUERY_GAP_MS)
                        } catch (_: Exception) { }

                        try {
                            sendWsText(out, ObdConstants.EXT_SESSION_AWD);   delay(ObdConstants.EXT_SESSION_GAP_MS)
                            sendWsText(out, ObdConstants.AWD_QUERY_RDU_STATUS); delay(ObdConstants.EXT_QUERY_GAP_MS)
                        } catch (_: Exception) { }

                        try {
                            sendWsText(out, ObdConstants.EXT_SESSION_PSCM);  delay(ObdConstants.EXT_SESSION_GAP_MS)
                            sendWsText(out, ObdConstants.PSCM_QUERY_PDC);    delay(ObdConstants.EXT_QUERY_GAP_MS)
                        } catch (_: Exception) { }

                        try {
                            sendWsText(out, ObdConstants.EXT_SESSION_FENG);  delay(ObdConstants.EXT_SESSION_GAP_MS)
                            sendWsText(out, ObdConstants.FENG_QUERY_STATUS); delay(ObdConstants.EXT_QUERY_GAP_MS)
                        } catch (_: Exception) { }

                        ObdConstants.RSPROT_PROBE_QUERIES.forEach { q ->
                            try {
                                sendWsText(out, ObdConstants.EXT_SESSION_RSPROT); delay(ObdConstants.EXT_SESSION_GAP_MS)
                                sendWsText(out, q);                               delay(ObdConstants.EXT_QUERY_GAP_MS)
                            } catch (_: Exception) { }
                        }

                        delay(ObdConstants.EXT_POLL_INTERVAL_MS)
                    }
                }

                val seenIds   = mutableSetOf<Int>()
                var debugCount = 0
                var debugTimer = System.currentTimeMillis()
                var firmwareKnown = false
                val probeStartMs  = System.currentTimeMillis()
                val PROBE_GRACE_MS = 3_000L

                // ── Main frame loop ──────────────────────────────────────────
                try {
                while (currentCoroutineContext().isActive) {
                    val (opcode, payload) = readWsFrame(inp, out) ?: break
                    if (opcode == 0x8) break

                    val msg = payload.toString(Charsets.UTF_8).trimEnd()

                    if (!firmwareKnown) {
                        if (msg.startsWith("OPENRS:")) {
                            firmwareKnown = true
                            val version = msg.removePrefix("OPENRS:").trim()
                            OpenRSDashApp.instance.isOpenRsFirmware.value = true
                            DiagnosticLogger.isOpenRsFirmware = true
                            DiagnosticLogger.firmwareVersion = "openRS_ $version"
                            addDebugLine("Firmware: openRS_ $version ✓")
                            DiagnosticLogger.event("FIRMWARE", "openRS_ $version detected")
                            continue
                        } else if (System.currentTimeMillis() - probeStartMs >= PROBE_GRACE_MS) {
                            firmwareKnown = true
                            OpenRSDashApp.instance.isOpenRsFirmware.value = false
                            DiagnosticLogger.isOpenRsFirmware = false
                            DiagnosticLogger.firmwareVersion = "WiCAN stock"
                            addDebugLine("Firmware: WiCAN stock (3 s timeout)")
                            DiagnosticLogger.event("FIRMWARE", "WiCAN stock (no openRS_ response in 3 s)")
                        }
                    }

                    val frame = SlcanParser.parse(msg) ?: continue

                    if (_dtcScanActive && frame.first in _dtcWatchIds) {
                        _dtcChannel.trySend(frame)
                        continue
                    }

                    if (frame.first == ObdConstants.BCM_RESPONSE_ID) {
                        ObdResponseParser.parseBcmResponse(frame.second, getCurrentState(), onObdUpdate)
                        continue
                    }
                    if (frame.first == ObdConstants.AWD_RESPONSE_ID) {
                        ObdResponseParser.parseAwdResponse(frame.second, getCurrentState(), onObdUpdate)
                        continue
                    }
                    if (frame.first == ObdConstants.PCM_RESPONSE_ID) {
                        ObdResponseParser.parsePcmResponse(frame.second, getCurrentState(), onObdUpdate)
                        continue
                    }
                    if (frame.first == ObdConstants.PSCM_RESPONSE_ID) {
                        ObdResponseParser.parsePscmResponse(frame.second, getCurrentState(), onObdUpdate)
                        continue
                    }
                    if (frame.first == ObdConstants.FENG_RESPONSE_ID) {
                        ObdResponseParser.parseFengResponse(frame.second, getCurrentState(), onObdUpdate)
                        continue
                    }
                    if (frame.first == ObdConstants.RSPROT_RESPONSE_ID) {
                        ObdResponseParser.parseRsprotResponse(frame.second, getCurrentState(), onObdUpdate) { addDebugLine(it) }
                        continue
                    }

                    if (seenIds.size < 200 && frame.first !in seenIds) {
                        seenIds += frame.first
                        addDebugLine("new ID: 0x%03X".format(frame.first))
                    }

                    send(frame)
                    trackFps()
                    debugCount++

                    val now = System.currentTimeMillis()
                    if (now - debugTimer >= 3_000) {
                        addDebugLine("CAN: ${debugCount} frames / 3 s  (${_fps.toInt()} fps)")
                        DiagnosticLogger.fps(_fps)
                        debugCount = 0
                        debugTimer = now
                    }
                }
                } finally {
                    obdJob.cancel()
                    pcmJob.cancel()
                    extJob.cancel()
                }
                } finally {
                    _wsOut = null
                    _dtcScanActive = false
                    try { socket.close() } catch (_: Exception) {}
                }

            } catch (e: CancellationException) {
                _state.value = AdapterState.Disconnected
                throw e
            } catch (e: Exception) {
                if (!connectionSucceeded) failedAttempts++
                _state.value = AdapterState.Error(e.message ?: "Connection failed")
                addDebugLine("Error: ${e.message}")
            }

            if (!currentCoroutineContext().isActive) break

            if (failedAttempts >= maxRetries) {
                _state.value = AdapterState.Idle
                break
            }

            _state.value = AdapterState.Disconnected
            val delayMs = if (connectionSucceeded) {
                val uptime = System.currentTimeMillis() - connectedAtMs
                if (uptime < 30_000) {
                    consecutiveDrops++
                    minOf(consecutiveDrops * reconnectDelayMs, 60_000L)
                } else {
                    consecutiveDrops = 0
                    reconnectDelayMs
                }
            } else {
                ObdConstants.BACKOFF_MS.getOrElse(failedAttempts - 1) { 30_000L }
            }
            delay(delayMs)
        }
    }.flowOn(Dispatchers.IO)

    // ── WebSocket helpers ───────────────────────────────────────────────────

    private fun sendHttpUpgrade(out: OutputStream) {
        val keyBytes = ByteArray(16).also { rng.nextBytes(it) }
        val key = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
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
                    val lenBytes = readExactly(inp, 8)
                    val bigLen = lenBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                    if (bigLen < 0 || bigLen > 1_048_576L) throw IOException("WS frame too large: $bigLen bytes")
                    readExactly(inp, bigLen.toInt())
                    DiagnosticLogger.event("WS", "Skipped 64-bit frame: $bigLen bytes")
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
                0x9 -> { sendWsPong(out, payload); continue }
                0xA -> continue
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
