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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * MeatPi Pro (WiCAN PRO) adapter — SLCAN over raw TCP.
 *
 * Connects to the device's TCP SLCAN socket (default 192.168.0.10:35000 in AP mode).
 * SLCAN frames are delimited by `\r` (0x0D) — no WebSocket framing overhead.
 * The SLCAN port and CAN bitrate are configured in the WiCAN Pro web UI (http://192.168.0.10/).
 *
 * OBD polling is identical to WiCAN: ISO-TP single-frame requests to PCM/BCM/AWD etc.
 * DTC scanning (Service 0x19) and clearing (Service 0x14) use [performDtcScan] / [performDtcClear].
 */
class MeatPiConnection(
    private val host: String = "192.168.0.10",
    private val port: Int = 35000,
    private val maxRetries: Int = 3,
    private val reconnectDelayMs: Long = 5_000L
) {
    private fun addDebugLine(line: String) = OpenRSDashApp.instance.pushDebugLine(line)

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Disconnected)
    val state: StateFlow<AdapterState> = _state.asStateFlow()

    private var frameCount  = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var _fps        = 0.0
    val fps: Double get() = _fps

    // ── DTC scan infrastructure ───────────────────────────────────────────────
    @Volatile private var _tcpOut: OutputStream? = null
    private val _dtcChannel    = Channel<Pair<Int, ByteArray>>(128)
    private val _dtcMutex      = Mutex()
    @Volatile private var _dtcWatchIds: Set<Int> = emptySet()
    @Volatile private var _dtcScanActive: Boolean = false

    @Volatile var firmwareVersion: String = "MeatPi Pro"
        private set

    // ── Connection ────────────────────────────────────────────────────────────

    fun connectHybrid(
        onObdUpdate: (VehicleState) -> Unit,
        getCurrentState: () -> VehicleState
    ): Flow<Pair<Int, ByteArray>> = channelFlow<Pair<Int, ByteArray>> {

        var failedAttempts = 0

        while (currentCoroutineContext().isActive && failedAttempts < maxRetries) {
            var connectionSucceeded = false
            try {
                _state.value = AdapterState.Connecting
                addDebugLine("--- MeatPi Pro TCP SLCAN ---")
                addDebugLine("Connecting to $host:$port")

                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), 5_000)
                    socket.soTimeout = 20_000

                    val inp = socket.getInputStream()
                    val out = socket.getOutputStream()

                    val cancelWatcher = launch {
                        try { awaitCancellation() }
                        finally { try { socket.close() } catch (_: Exception) { } }
                    }

                    out.write(ObdConstants.SLCAN_INIT.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    addDebugLine("SLCAN init sent (C / S6 / O)")

                    out.write("OPENRS?\r".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    addDebugLine("Probing firmware...")

                    _state.value = AdapterState.Connected
                    _tcpOut = out
                    connectionSucceeded = true
                    failedAttempts = 0

                    // ── BCM OBD poller ────────────────────────────────────────
                    val obdJob = launch {
                        delay(ObdConstants.BCM_INITIAL_DELAY_MS)
                        while (isActive) {
                            ObdConstants.BCM_QUERIES.forEach { q ->
                                try { sendFrame(out, q) } catch (_: Exception) { }
                                delay(ObdConstants.BCM_QUERY_GAP_MS)
                            }
                            ObdConstants.AWD_QUERIES.forEach { q ->
                                try { sendFrame(out, q) } catch (_: Exception) { }
                                delay(ObdConstants.BCM_QUERY_GAP_MS)
                            }
                            delay(ObdConstants.BCM_POLL_INTERVAL_MS)
                        }
                    }

                    // ── PCM OBD poller ────────────────────────────────────────
                    val pcmJob = launch {
                        delay(ObdConstants.PCM_INITIAL_DELAY_MS)
                        while (isActive) {
                            ObdConstants.PCM_QUERIES.forEach { q ->
                                try { sendFrame(out, q) } catch (_: Exception) { }
                                delay(ObdConstants.PCM_QUERY_GAP_MS)
                            }
                            delay(ObdConstants.PCM_POLL_INTERVAL_MS)
                        }
                    }

                    // ── Extended session poller ───────────────────────────────
                    val extJob = launch {
                        delay(ObdConstants.EXT_INITIAL_DELAY_MS)
                        while (isActive) {
                            try { sendFrame(out, ObdConstants.EXT_SESSION_BCM);   delay(ObdConstants.EXT_SESSION_GAP_MS)
                                  sendFrame(out, ObdConstants.BCM_QUERY_ODOMETER); delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            try { sendFrame(out, ObdConstants.EXT_SESSION_AWD);   delay(ObdConstants.EXT_SESSION_GAP_MS)
                                  sendFrame(out, ObdConstants.AWD_QUERY_RDU_STATUS); delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            try { sendFrame(out, ObdConstants.EXT_SESSION_PSCM);  delay(ObdConstants.EXT_SESSION_GAP_MS)
                                  sendFrame(out, ObdConstants.PSCM_QUERY_PDC);    delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            try { sendFrame(out, ObdConstants.EXT_SESSION_FENG);  delay(ObdConstants.EXT_SESSION_GAP_MS)
                                  sendFrame(out, ObdConstants.FENG_QUERY_STATUS); delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            ObdConstants.RSPROT_PROBE_QUERIES.forEach { q ->
                                try { sendFrame(out, ObdConstants.EXT_SESSION_RSPROT); delay(ObdConstants.EXT_SESSION_GAP_MS)
                                      sendFrame(out, q);                               delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
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

                    // ── Main frame loop ───────────────────────────────────────
                    try {
                        while (currentCoroutineContext().isActive) {
                            val line = try { readSlcanLine(inp) } catch (_: Exception) { null } ?: break

                            if (!firmwareKnown) {
                                if (line.startsWith("OPENRS:")) {
                                    firmwareKnown = true
                                    val version = line.removePrefix("OPENRS:").trim()
                                    firmwareVersion = "openRS_ $version"
                                    OpenRSDashApp.instance.isOpenRsFirmware.value = true
                                    DiagnosticLogger.isOpenRsFirmware = true
                                    DiagnosticLogger.firmwareVersion = firmwareVersion
                                    addDebugLine("Firmware: openRS_ $version ✓")
                                    DiagnosticLogger.event("FIRMWARE", "openRS_ $version detected (TCP)")
                                    continue
                                } else if (System.currentTimeMillis() - probeStartMs >= PROBE_GRACE_MS) {
                                    firmwareKnown = true
                                    firmwareVersion = "MeatPi Pro"
                                    OpenRSDashApp.instance.isOpenRsFirmware.value = false
                                    DiagnosticLogger.isOpenRsFirmware = false
                                    DiagnosticLogger.firmwareVersion = firmwareVersion
                                    addDebugLine("Firmware: MeatPi Pro stock (3 s timeout)")
                                    DiagnosticLogger.event("FIRMWARE", "MeatPi Pro stock (no openRS_ response in 3 s)")
                                }
                            }

                            val frame = SlcanParser.parse(line) ?: continue

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
                                addDebugLine("CAN: $debugCount frames / 3 s  (${_fps.toInt()} fps)")
                                DiagnosticLogger.fps(_fps)
                                debugCount = 0
                                debugTimer = now
                            }
                        }
                    } finally {
                        cancelWatcher.cancel()
                        obdJob.cancel()
                        pcmJob.cancel()
                        extJob.cancel()
                    }
                } finally {
                    _tcpOut = null
                    _dtcScanActive = false
                    try { socket.close() } catch (_: Exception) { }
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
            val delayMs = if (connectionSucceeded) reconnectDelayMs
                          else ObdConstants.BACKOFF_MS.getOrElse(failedAttempts - 1) { 30_000L }
            delay(delayMs)
        }
    }.flowOn(Dispatchers.IO)

    // ── DTC scan ──────────────────────────────────────────────────────────────

    suspend fun performDtcScan(modules: List<DtcModuleSpec>): Map<String, ByteArray> =
        _dtcMutex.withLock {
            val out = _tcpOut ?: return emptyMap()
            val results = mutableMapOf<String, ByteArray>()

            _dtcWatchIds = modules.map { it.responseId }.toSet()
            _dtcScanActive = true
            while (_dtcChannel.tryReceive().isSuccess) { /* drain stale */ }

            try {
                for (module in modules) {
                    val reqFrame = "t%03X8031902FF00000000\r".format(module.requestId)
                    try { sendFrame(out, reqFrame) } catch (_: Exception) { continue }
                    val payload = assembleIsotpResponse(module.responseId, module.requestId, out, 2_500L)
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
            val out = _tcpOut ?: return emptyMap()
            val results = mutableMapOf<String, Boolean>()

            _dtcWatchIds = modules.map { it.responseId }.toSet()
            _dtcScanActive = true
            while (_dtcChannel.tryReceive().isSuccess) { /* drain stale */ }

            try {
                for (module in modules) {
                    val reqFrame = "t%03X80414FFFFFF000000\r".format(module.requestId)
                    try { sendFrame(out, reqFrame) } catch (_: Exception) { continue }
                    val payload = assembleIsotpResponse(module.responseId, module.requestId, out, 2_000L)
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

    private suspend fun assembleIsotpResponse(
        responseId: Int,
        requestId: Int,
        out: OutputStream,
        timeoutMs: Long
    ): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var totalLen = 0; var received = 0
        val buf = ByteArrayOutputStream(); var seqNext = 1

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val frame = withTimeoutOrNull(remaining) { _dtcChannel.receive() } ?: break
            if (frame.first != responseId) continue
            val data = frame.second; if (data.isEmpty()) continue
            when ((data[0].toInt() and 0xF0) shr 4) {
                0 -> {
                    val len = data[0].toInt() and 0x0F
                    if (len <= 0 || data.size < len + 1) return null
                    return data.copyOfRange(1, 1 + len)
                }
                1 -> {
                    totalLen = ((data[0].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
                    val fb = minOf(6, data.size - 2); if (fb > 0) buf.write(data, 2, fb); received = fb
                    val fcFrame = "t%03X83000000000000000\r".format(requestId)
                    try { sendFrame(out, fcFrame) } catch (_: Exception) { }
                }
                2 -> {
                    val seq = data[0].toInt() and 0x0F; if (seq != seqNext) break
                    seqNext = (seqNext + 1) and 0x0F
                    val take = minOf(7, data.size - 1, totalLen - received)
                    if (take <= 0) break; buf.write(data, 1, take); received += take
                    if (received >= totalLen) return buf.toByteArray()
                }
            }
        }
        return if (buf.size() > 0) buf.toByteArray() else null
    }

    // ── TCP SLCAN I/O ─────────────────────────────────────────────────────────

    private fun readSlcanLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return null
            if (b == 0x0D) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun sendFrame(out: OutputStream, frame: String) {
        synchronized(out) {
            out.write(frame.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    // ── FPS tracking ──────────────────────────────────────────────────────────

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
