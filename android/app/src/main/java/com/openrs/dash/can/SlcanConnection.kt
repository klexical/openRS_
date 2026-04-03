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

/**
 * Transport-agnostic SLCAN connection.
 *
 * Manages the full connection lifecycle: retry loop, SLCAN init, firmware probe,
 * OBD pollers (BCM/PCM/AWD/PSCM/FENG/RSProt), and the main frame dispatch loop.
 * DTC scanning, clearing, and raw DID queries are also handled here.
 *
 * The underlying transport ([SlcanTransport]) is created via [transportFactory] on
 * each connection attempt, allowing transparent switching between TCP, WebSocket,
 * and (future) BLE GATT.
 */
class SlcanConnection(
    private val transportFactory: () -> SlcanTransport,
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

    @Volatile var firmwareVersion: String = ""
        private set

    /** Active transport reference — used by BleFirmwareApi to send AT+FRS commands. */
    val transport: SlcanTransport? get() = _transport

    // ── Firmware command response channel (AT+FRS over BLE/TCP/WS) ────────────
    // Lines starting with "+FRS:" are routed here instead of to the SLCAN parser.
    // BleFirmwareApi reads from this channel with a timeout.
    val commandResponseChannel = Channel<String>(16)

    // ── DTC scan infrastructure ───────────────────────────────────────────────
    @Volatile private var _transport: SlcanTransport? = null
    private val _dtcChannel    = Channel<Pair<Int, ByteArray>>(128)
    private val _dtcMutex      = Mutex()
    @Volatile private var _dtcWatchIds: Set<Int> = emptySet()
    @Volatile private var _dtcScanActive: Boolean = false

    suspend fun sendRawQuery(
        responseId: Int,
        frame: String,
        timeoutMs: Long = 1_500L
    ): ByteArray? = _dtcMutex.withLock {
        val transport = _transport ?: return null
        _dtcWatchIds = setOf(responseId)
        _dtcScanActive = true
        while (_dtcChannel.tryReceive().isSuccess) { /* drain stale */ }
        try {
            transport.writeLine(frame)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val resp = withTimeoutOrNull(remaining) { _dtcChannel.receive() } ?: break
                if (resp.first == responseId) return@withLock resp.second
            }
            null
        } finally {
            _dtcScanActive = false
            _dtcWatchIds = emptySet()
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connectHybrid(
        onObdUpdate: (VehicleState) -> Unit,
        getCurrentState: () -> VehicleState
    ): Flow<Pair<Int, ByteArray>> = channelFlow<Pair<Int, ByteArray>> {

        var failedAttempts = 0
        var consecutiveDrops = 0

        while (currentCoroutineContext().isActive && failedAttempts < maxRetries) {
            var connectionSucceeded = false
            var connectedAtMs = 0L
            val transport = transportFactory()
            try {
                _state.value = AdapterState.Connecting
                addDebugLine("--- ${transport.label} ---")
                addDebugLine("Connecting to ${transport.label}")

                transport.open()

                transport.writeLine(ObdConstants.SLCAN_INIT)
                addDebugLine("SLCAN init sent (C / S6 / O)")

                transport.writeLine("OPENRS?\r")
                addDebugLine("Probing firmware...")

                // ── SLCAN handshake: wait for first valid response ────────
                // Non-SLCAN BLE devices (e.g. LED controllers sharing the
                // 0xFFE0 service UUID) will never send a valid frame.
                val HANDSHAKE_TIMEOUT_MS = 3_000L
                val handshakeDeadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
                var handshakeOk = false
                var firstLine: String? = null
                while (System.currentTimeMillis() < handshakeDeadline) {
                    val remaining = handshakeDeadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val line = withTimeoutOrNull(remaining) {
                        try { transport.readLine() } catch (_: Exception) { null }
                    }
                    if (line == null) break  // transport closed or timed out
                    // Accept firmware probe response OR any parseable SLCAN frame
                    if (line.startsWith("OPENRS:") || SlcanParser.parse(line) != null) {
                        handshakeOk = true
                        firstLine = line
                        break
                    }
                    // Some adapters echo back init commands — keep waiting
                }
                if (!handshakeOk) {
                    addDebugLine("No SLCAN response — device may not be a WiCAN adapter")
                    throw RuntimeException(
                        "Device did not respond to SLCAN init — may not be a CAN adapter"
                    )
                }
                addDebugLine("SLCAN handshake OK")

                _state.value = AdapterState.Connected
                _transport = transport
                connectionSucceeded = true
                connectedAtMs = System.currentTimeMillis()
                failedAttempts = 0
                firmwareVersion = transport.stockFirmwareLabel
                val bcmIsoTp = IsoTpBuffer()

                // ── BCM OBD poller ────────────────────────────────────────
                val obdJob = launch {
                    delay(ObdConstants.BCM_INITIAL_DELAY_MS)
                    ObdConstants.BCM_TPMS_ID_QUERIES.forEach { q ->
                        try { transport.writeLine(q) } catch (_: Exception) { }
                        delay(ObdConstants.BCM_QUERY_GAP_MS)
                    }
                    while (isActive) {
                        ObdConstants.BCM_QUERIES.forEach { q ->
                            try { transport.writeLine(q) } catch (_: Exception) { }
                            delay(ObdConstants.BCM_QUERY_GAP_MS)
                        }
                        ObdConstants.AWD_QUERIES.forEach { q ->
                            try { transport.writeLine(q) } catch (_: Exception) { }
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
                            try { transport.writeLine(q) } catch (_: Exception) { }
                            delay(ObdConstants.PCM_QUERY_GAP_MS)
                        }
                        delay(ObdConstants.PCM_POLL_INTERVAL_MS)
                    }
                }

                // ── Probe-timeout counters (shared with frame loop below) ──
                val fengMisses   = java.util.concurrent.atomic.AtomicInteger(0)
                val rsprotMisses = java.util.concurrent.atomic.AtomicInteger(0)
                val PROBE_TIMEOUT_CYCLES = 3

                // ── Extended session poller ───────────────────────────────
                val extJob = launch {
                    delay(ObdConstants.EXT_INITIAL_DELAY_MS)
                    var odometerPolled = false
                    while (isActive) {
                        if (!odometerPolled) {
                            try { transport.writeLine(ObdConstants.EXT_SESSION_BCM);   delay(ObdConstants.EXT_SESSION_GAP_MS)
                                  transport.writeLine(ObdConstants.BCM_QUERY_ODOMETER); delay(ObdConstants.EXT_QUERY_GAP_MS)
                                  odometerPolled = true } catch (_: Exception) { }
                        }
                        try { transport.writeLine(ObdConstants.EXT_SESSION_AWD);   delay(ObdConstants.EXT_SESSION_GAP_MS)
                              transport.writeLine(ObdConstants.AWD_QUERY_RDU_STATUS); delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                        try { transport.writeLine(ObdConstants.EXT_SESSION_PSCM);  delay(ObdConstants.EXT_SESSION_GAP_MS)
                              transport.writeLine(ObdConstants.PSCM_QUERY_PDC);    delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                        if (fengMisses.get() < PROBE_TIMEOUT_CYCLES) {
                            try { transport.writeLine(ObdConstants.EXT_SESSION_FENG);  delay(ObdConstants.EXT_SESSION_GAP_MS)
                                  transport.writeLine(ObdConstants.FENG_QUERY_STATUS); delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            if (fengMisses.incrementAndGet() >= PROBE_TIMEOUT_CYCLES) {
                                onObdUpdate(getCurrentState().copy(fengTimedOut = true))
                            }
                        }
                        if (rsprotMisses.get() < PROBE_TIMEOUT_CYCLES) {
                            ObdConstants.RSPROT_PROBE_QUERIES.forEach { q ->
                                try { transport.writeLine(ObdConstants.EXT_SESSION_RSPROT); delay(ObdConstants.EXT_SESSION_GAP_MS)
                                      transport.writeLine(q);                               delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            }
                            if (rsprotMisses.incrementAndGet() >= PROBE_TIMEOUT_CYCLES) {
                                onObdUpdate(getCurrentState().copy(rsprotTimedOut = true))
                            }
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

                // Process the first line captured during handshake
                if (firstLine != null && firstLine.startsWith("OPENRS:")) {
                    firmwareKnown = true
                    val version = firstLine.removePrefix("OPENRS:").trim()
                    firmwareVersion = "openRS_ $version"
                    OpenRSDashApp.instance.isOpenRsFirmware.value = true
                    OpenRSDashApp.instance.firmwareVersionLabel.value = firmwareVersion
                    DiagnosticLogger.isOpenRsFirmware = true
                    DiagnosticLogger.firmwareVersion = firmwareVersion
                    addDebugLine("Firmware: openRS_ $version \u2713")
                    DiagnosticLogger.event("FIRMWARE", "openRS_ $version detected (${transport.label})")
                }

                // ── Main frame loop ───────────────────────────────────────
                try {
                    while (currentCoroutineContext().isActive) {
                        val line = try { transport.readLine() } catch (e: Exception) {
                            android.util.Log.d("SLCAN", "readLine failed", e); null
                        } ?: break

                        if (!firmwareKnown) {
                            if (line.startsWith("OPENRS:")) {
                                firmwareKnown = true
                                val version = line.removePrefix("OPENRS:").trim()
                                firmwareVersion = "openRS_ $version"
                                OpenRSDashApp.instance.isOpenRsFirmware.value = true
                                OpenRSDashApp.instance.firmwareVersionLabel.value = firmwareVersion
                                DiagnosticLogger.isOpenRsFirmware = true
                                DiagnosticLogger.firmwareVersion = firmwareVersion
                                addDebugLine("Firmware: openRS_ $version \u2713")
                                DiagnosticLogger.event("FIRMWARE", "openRS_ $version detected (${transport.label})")
                                continue
                            } else if (System.currentTimeMillis() - probeStartMs >= PROBE_GRACE_MS) {
                                firmwareKnown = true
                                firmwareVersion = transport.stockFirmwareLabel
                                OpenRSDashApp.instance.isOpenRsFirmware.value = false
                                OpenRSDashApp.instance.firmwareVersionLabel.value = firmwareVersion
                                DiagnosticLogger.isOpenRsFirmware = false
                                DiagnosticLogger.firmwareVersion = firmwareVersion
                                addDebugLine("Firmware: ${transport.stockFirmwareLabel} (3 s timeout)")
                                DiagnosticLogger.event("FIRMWARE", "${transport.stockFirmwareLabel} (no openRS_ response in 3 s)")
                            }
                        }

                        // Route firmware command responses to dedicated channel
                        if (line.startsWith("+FRS:")) {
                            commandResponseChannel.trySend(line)
                            continue
                        }

                        val frame = SlcanParser.parse(line) ?: continue

                        if (_dtcScanActive && frame.first in _dtcWatchIds) {
                            _dtcChannel.trySend(frame)
                            continue
                        }

                        if (frame.first in ObdConstants.OBD_RESPONSE_IDS) {
                            DiagnosticLogger.logObdFrame(frame.first, frame.second)
                        }

                        if (frame.first == ObdConstants.BCM_RESPONSE_ID) {
                            val (reassembled, isFF, isSF) = bcmIsoTp.feed(frame.second)
                            if (isFF) {
                                try { transport.writeLine(ObdConstants.BCM_FLOW_CONTROL) } catch (_: Exception) {}
                            }
                            if (reassembled != null) {
                                if (isSF) ObdResponseParser.parseBcmResponse(frame.second, getCurrentState(), onObdUpdate)
                                else      ObdResponseParser.parseBcmReassembled(reassembled, getCurrentState(), onObdUpdate)
                            }
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
                        if (frame.first == ObdConstants.HVAC_RESPONSE_ID) {
                            ObdResponseParser.parseHvacResponse(frame.second, getCurrentState(), onObdUpdate)
                            continue
                        }
                        if (frame.first == ObdConstants.IPC_RESPONSE_ID) {
                            ObdResponseParser.parseIpcResponse(frame.second, getCurrentState(), onObdUpdate)
                            continue
                        }
                        if (frame.first == ObdConstants.FENG_RESPONSE_ID) {
                            fengMisses.set(0)
                            ObdResponseParser.parseFengResponse(frame.second, getCurrentState(), onObdUpdate)
                            continue
                        }
                        if (frame.first == ObdConstants.RSPROT_RESPONSE_ID) {
                            rsprotMisses.set(0)
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
                    obdJob.cancel()
                    pcmJob.cancel()
                    extJob.cancel()
                }
            } catch (e: CancellationException) {
                _state.value = AdapterState.Disconnected
                throw e
            } catch (e: Exception) {
                if (!connectionSucceeded) failedAttempts++
                _state.value = AdapterState.Error(e.message ?: "Connection failed")
                addDebugLine("Error: ${e.message}")
            } finally {
                _transport = null
                _dtcScanActive = false
                transport.close()
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

    // ── DTC scan ──────────────────────────────────────────────────────────────

    suspend fun performDtcScan(modules: List<DtcModuleSpec>): Map<String, ByteArray> =
        _dtcMutex.withLock {
            val transport = _transport ?: return emptyMap()
            val results = mutableMapOf<String, ByteArray>()

            _dtcWatchIds = modules.map { it.responseId }.toSet()
            _dtcScanActive = true
            while (_dtcChannel.tryReceive().isSuccess) { /* drain stale */ }

            try {
                for (module in modules) {
                    val reqFrame = "t%03X8031902FF00000000\r".format(module.requestId)
                    try { transport.writeLine(reqFrame) } catch (_: Exception) { continue }
                    val payload = assembleIsotpResponse(transport, module.responseId, module.requestId, 2_500L)
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
            val transport = _transport ?: return emptyMap()
            val results = mutableMapOf<String, Boolean>()

            _dtcWatchIds = modules.map { it.responseId }.toSet()
            _dtcScanActive = true
            while (_dtcChannel.tryReceive().isSuccess) { /* drain stale */ }

            try {
                for (module in modules) {
                    val reqFrame = "t%03X80414FFFFFF000000\r".format(module.requestId)
                    try { transport.writeLine(reqFrame) } catch (_: Exception) { continue }
                    val payload = assembleIsotpResponse(transport, module.responseId, module.requestId, 2_000L)
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
        transport: SlcanTransport,
        responseId: Int,
        requestId: Int,
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
                    val fb = minOf(6, totalLen, data.size - 2); if (fb > 0) buf.write(data, 2, fb); received = fb  // cap by totalLen (#129)
                    val fcFrame = "t%03X83000000000000000\r".format(requestId)
                    try { transport.writeLine(fcFrame) } catch (_: Exception) { }
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
