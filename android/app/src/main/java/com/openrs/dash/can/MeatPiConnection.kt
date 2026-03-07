package com.openrs.dash.can

import com.openrs.dash.OpenRSDashApp
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

/**
 * MeatPi Pro adapter — SLCAN over raw TCP.
 *
 * The MeatPi Pro connects over TCP rather than WebSocket.
 * SLCAN frames are delimited by `\r` (0x0D) without any framing overhead.
 *
 * Default: TCP to 192.168.4.1:3333 (HS-CAN, 500 kbps)
 * Optional: TCP to host:msCan port for MS-CAN (125 kbps) — see [connectMsCan].
 *
 * OBD polling is identical to WiCAN: ISO-TP single-frame requests to PCM/BCM/AWD etc.
 * DTC scanning uses the same [performDtcScan] mechanism.
 */
class MeatPiConnection(
    private val host: String = "192.168.0.10",
    private val port: Int = 35000,
    private val maxRetries: Int = 3,
    private val reconnectDelayMs: Long = 5_000L
) {
    private fun addDebugLine(line: String) = OpenRSDashApp.instance.pushDebugLine(line)

    companion object {
        val BACKOFF_MS = listOf(5_000L, 15_000L, 30_000L)

        // CAN bus bitrate is configured via the WiCAN Pro web UI (http://192.168.0.10/).
        // This init string opens the SLCAN channel at the bitrate already set on the device.
        private const val SLCAN_INIT_HS = "C\rS6\rO\r"

        // ── OBD polling frames (identical to WiCanConnection) ─────────────────
        // BCM (0x726 → 0x72E)
        const val BCM_RESPONSE_ID   = 0x72E
        private const val BCM_QUERY_SOC        = "t72680322402800000000\r"
        private const val BCM_QUERY_BATT_TEMP  = "t72680322402900000000\r"
        private const val BCM_QUERY_CABIN_TEMP = "t72680322DD0400000000\r"
        private const val BCM_QUERY_TPMS_LF   = "t72680322281300000000\r"
        private const val BCM_QUERY_TPMS_RF   = "t72680322281400000000\r"
        private const val BCM_QUERY_TPMS_LR   = "t72680322281600000000\r"
        private const val BCM_QUERY_TPMS_RR   = "t72680322281500000000\r"
        private val BCM_QUERIES = listOf(
            BCM_QUERY_SOC, BCM_QUERY_BATT_TEMP, BCM_QUERY_CABIN_TEMP,
            BCM_QUERY_TPMS_LF, BCM_QUERY_TPMS_RF, BCM_QUERY_TPMS_LR, BCM_QUERY_TPMS_RR
        )
        private const val BCM_QUERY_ODOMETER   = "t72680322DD0100000000\r"
        private const val BCM_POLL_INTERVAL_MS = 30_000L
        private const val BCM_QUERY_GAP_MS     =    300L
        private const val BCM_INITIAL_DELAY_MS =  5_000L

        // AWD (0x703 → 0x70B)
        const val AWD_RESPONSE_ID = 0x70B
        private const val AWD_QUERY_RDU_TEMP   = "t703803221E8A00000000\r"
        private val AWD_QUERIES = listOf(AWD_QUERY_RDU_TEMP)

        // PCM (0x7E0 → 0x7E8)
        const val PCM_RESPONSE_ID = 0x7E8
        private const val PCM_QUERY_ETC_ACTUAL   = "t7E080322093C00000000\r"
        private const val PCM_QUERY_ETC_DESIRED  = "t7E080322091A00000000\r"
        private const val PCM_QUERY_WGDC         = "t7E080322046200000000\r"
        private const val PCM_QUERY_KR_CYL1      = "t7E08032203EC00000000\r"
        private const val PCM_QUERY_OAR          = "t7E08032203E800000000\r"
        private const val PCM_QUERY_CHARGE_AIR   = "t7E080322046100000000\r"
        private const val PCM_QUERY_CAT_TEMP     = "t7E080322F43C00000000\r"
        private const val PCM_QUERY_AFR_ACTUAL   = "t7E080322F43400000000\r"
        private const val PCM_QUERY_AFR_DESIRED  = "t7E080322F44400000000\r"
        private const val PCM_QUERY_TIP_ACTUAL   = "t7E080322033e00000000\r"
        private const val PCM_QUERY_TIP_DESIRED  = "t7E080322046600000000\r"
        private const val PCM_QUERY_VCT_INTAKE   = "t7E080322031800000000\r"
        private const val PCM_QUERY_VCT_EXHAUST  = "t7E080322031900000000\r"
        private const val PCM_QUERY_OIL_LIFE     = "t7E080322054B00000000\r"
        private const val PCM_QUERY_HP_FUEL_RAIL = "t7E080322F42200000000\r"
        private const val PCM_QUERY_FUEL_LEVEL   = "t7E080322F42F00000000\r"
        private val PCM_QUERIES = listOf(
            PCM_QUERY_ETC_ACTUAL, PCM_QUERY_ETC_DESIRED,
            PCM_QUERY_WGDC, PCM_QUERY_KR_CYL1, PCM_QUERY_OAR,
            PCM_QUERY_CHARGE_AIR, PCM_QUERY_CAT_TEMP,
            PCM_QUERY_AFR_ACTUAL, PCM_QUERY_AFR_DESIRED,
            PCM_QUERY_TIP_ACTUAL, PCM_QUERY_TIP_DESIRED,
            PCM_QUERY_VCT_INTAKE, PCM_QUERY_VCT_EXHAUST,
            PCM_QUERY_OIL_LIFE, PCM_QUERY_HP_FUEL_RAIL, PCM_QUERY_FUEL_LEVEL
        )
        private const val PCM_POLL_INTERVAL_MS = 30_000L
        private const val PCM_QUERY_GAP_MS     =    200L
        private const val PCM_INITIAL_DELAY_MS = 20_000L

        // Extended session (UDS 10 03)
        const val PSCM_RESPONSE_ID   = 0x738
        const val FENG_RESPONSE_ID   = 0x72F
        const val RSPROT_RESPONSE_ID = 0x739
        private const val EXT_SESSION_BCM    = "t72680210030000000000\r"
        private const val EXT_SESSION_AWD    = "t70380210030000000000\r"
        private const val EXT_SESSION_PSCM   = "t73080210030000000000\r"
        private const val EXT_SESSION_FENG   = "t72780210030000000000\r"
        private const val EXT_SESSION_RSPROT = "t73180210030000000000\r"
        private const val AWD_QUERY_RDU_STATUS = "t70380322ee0b00000000\r"
        private const val PSCM_QUERY_PDC       = "t73080322fd0700000000\r"
        private const val FENG_QUERY_STATUS    = "t72780322ee0300000000\r"
        private val RSPROT_PROBE_QUERIES = listOf(
            "t73180322de0000000000\r", "t73180322de0100000000\r",
            "t73180322de0200000000\r", "t73180322ee0100000000\r",
            "t73180322fd0100000000\r"
        )
        private const val EXT_POLL_INTERVAL_MS = 60_000L
        private const val EXT_INITIAL_DELAY_MS = 15_000L
        private const val EXT_SESSION_GAP_MS   =    150L
        private const val EXT_QUERY_GAP_MS     =    300L
    }

    sealed class State {
        data object Disconnected : State()
        data object Connecting   : State()
        data object Connected    : State()
        data object Idle         : State()
        data class  Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

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

    /** Identifies the MeatPi Pro firmware version (reported via OPENRS: probe or TCP). */
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
                _state.value = State.Connecting
                addDebugLine("--- MeatPi Pro TCP SLCAN ---")
                addDebugLine("Connecting to $host:$port")

                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), 5_000)
                    socket.soTimeout = 20_000

                    val inp = socket.getInputStream()
                    val out = socket.getOutputStream()

                    // Init HS-CAN at 500 kbps
                    out.write(SLCAN_INIT_HS.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    addDebugLine("SLCAN init sent (C / S6 / O)")

                    _state.value = State.Connected
                    _tcpOut = out
                    connectionSucceeded = true
                    failedAttempts = 0

                    DiagnosticLogger.firmwareVersion = firmwareVersion
                    DiagnosticLogger.isOpenRsFirmware = false

                    // ── BCM OBD poller ────────────────────────────────────────
                    val obdJob = launch {
                        delay(BCM_INITIAL_DELAY_MS)
                        while (isActive) {
                            BCM_QUERIES.forEach { q ->
                                try { sendFrame(out, q) } catch (_: Exception) { }
                                delay(BCM_QUERY_GAP_MS)
                            }
                            AWD_QUERIES.forEach { q ->
                                try { sendFrame(out, q) } catch (_: Exception) { }
                                delay(BCM_QUERY_GAP_MS)
                            }
                            delay(BCM_POLL_INTERVAL_MS)
                        }
                    }

                    // ── PCM OBD poller ────────────────────────────────────────
                    val pcmJob = launch {
                        delay(PCM_INITIAL_DELAY_MS)
                        while (isActive) {
                            PCM_QUERIES.forEach { q ->
                                try { sendFrame(out, q) } catch (_: Exception) { }
                                delay(PCM_QUERY_GAP_MS)
                            }
                            delay(PCM_POLL_INTERVAL_MS)
                        }
                    }

                    // ── Extended session poller ───────────────────────────────
                    val extJob = launch {
                        delay(EXT_INITIAL_DELAY_MS)
                        while (isActive) {
                            try { sendFrame(out, EXT_SESSION_BCM);   delay(EXT_SESSION_GAP_MS)
                                  sendFrame(out, BCM_QUERY_ODOMETER); delay(EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            try { sendFrame(out, EXT_SESSION_AWD);   delay(EXT_SESSION_GAP_MS)
                                  sendFrame(out, AWD_QUERY_RDU_STATUS); delay(EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            try { sendFrame(out, EXT_SESSION_PSCM);  delay(EXT_SESSION_GAP_MS)
                                  sendFrame(out, PSCM_QUERY_PDC);    delay(EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            try { sendFrame(out, EXT_SESSION_FENG);  delay(EXT_SESSION_GAP_MS)
                                  sendFrame(out, FENG_QUERY_STATUS); delay(EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            RSPROT_PROBE_QUERIES.forEach { q ->
                                try { sendFrame(out, EXT_SESSION_RSPROT); delay(EXT_SESSION_GAP_MS)
                                      sendFrame(out, q);                  delay(EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            }
                            delay(EXT_POLL_INTERVAL_MS)
                        }
                    }

                    val seenIds   = mutableSetOf<Int>()
                    var debugCount = 0
                    var debugTimer = System.currentTimeMillis()

                    // ── Main frame loop ───────────────────────────────────────
                    try {
                        while (currentCoroutineContext().isActive) {
                            val line = readSlcanLine(inp) ?: break
                            val frame = parseSlcanFrame(line) ?: continue

                            // DTC scan intercept
                            if (_dtcScanActive && frame.first in _dtcWatchIds) {
                                _dtcChannel.trySend(frame)
                                continue
                            }

                            // OBD response routing (same logic as WiCanConnection)
                            if (frame.first == BCM_RESPONSE_ID) {
                                parseBcmResponse(frame.second, getCurrentState(), onObdUpdate)
                                continue
                            }
                            if (frame.first == AWD_RESPONSE_ID) {
                                parseAwdResponse(frame.second, getCurrentState(), onObdUpdate)
                                continue
                            }
                            if (frame.first == PCM_RESPONSE_ID) {
                                parsePcmResponse(frame.second, getCurrentState(), onObdUpdate)
                                continue
                            }
                            if (frame.first == PSCM_RESPONSE_ID) {
                                parsePscmResponse(frame.second, getCurrentState(), onObdUpdate)
                                continue
                            }
                            if (frame.first == FENG_RESPONSE_ID) {
                                parseFengResponse(frame.second, getCurrentState(), onObdUpdate)
                                continue
                            }
                            if (frame.first == RSPROT_RESPONSE_ID) {
                                parseRsprotResponse(frame.second, getCurrentState(), onObdUpdate)
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
                } finally {
                    _tcpOut = null
                    _dtcScanActive = false
                    try { socket.close() } catch (_: Exception) { }
                }

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

    // ── DTC scan ──────────────────────────────────────────────────────────────

    suspend fun performDtcScan(modules: List<WiCanConnection.DtcModuleSpec>): Map<String, ByteArray> =
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

    /**
     * Sends UDS Service 0x14 (ClearDiagnosticInformation, group 0xFFFFFF) to each module.
     * Returns module name → true if the ECU responded with 0x54 (positive response).
     */
    suspend fun performDtcClear(modules: List<WiCanConnection.DtcModuleSpec>): Map<String, Boolean> =
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

    /** Read one SLCAN frame (terminated by 0x0D) from the TCP stream. Returns null on EOF. */
    private fun readSlcanLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return null
            if (b == 0x0D) break            // \r terminates SLCAN frame
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** Send a SLCAN frame string (already includes trailing \r) to the TCP socket.
     *  Synchronized so OBD pollers and DTC scanner don't interleave bytes. */
    private fun sendFrame(out: OutputStream, frame: String) {
        synchronized(out) {
            out.write(frame.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    // ── SLCAN frame parser ────────────────────────────────────────────────────

    private fun parseSlcanFrame(msg: String): Pair<Int, ByteArray>? {
        if (msg.isEmpty()) return null
        return when (msg[0]) {
            't' -> parseStdFrame(msg)
            'T' -> parseExtFrame(msg)
            else -> null
        }
    }

    private fun parseStdFrame(msg: String): Pair<Int, ByteArray>? {
        if (msg.length < 5) return null
        val id  = msg.substring(1, 4).toIntOrNull(16) ?: return null
        val dlc = msg[4].digitToIntOrNull(10) ?: return null
        if (dlc < 0 || dlc > 8 || msg.length < 5 + dlc * 2) return null
        return Pair(id, parseDataBytes(msg, 5, dlc))
    }

    private fun parseExtFrame(msg: String): Pair<Int, ByteArray>? {
        if (msg.length < 10) return null
        val id  = msg.substring(1, 9).toLongOrNull(16)?.toInt() ?: return null
        val dlc = msg[9].digitToIntOrNull(10) ?: return null
        if (dlc < 0 || dlc > 8 || msg.length < 10 + dlc * 2) return null
        return Pair(id, parseDataBytes(msg, 10, dlc))
    }

    private fun parseDataBytes(msg: String, start: Int, dlc: Int): ByteArray =
        try {
            ByteArray(dlc) { i -> msg.substring(start + i*2, start + i*2 + 2).toInt(16).toByte() }
        } catch (_: Exception) { ByteArray(0) }

    // ── OBD response parsers (identical formulas to WiCanConnection) ──────────

    private fun parseBcmResponse(data: ByteArray, s: VehicleState, cb: (VehicleState) -> Unit) {
        if (data.size < 5 || (data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0xDD01 -> { if (data.size < 7) return
                val km = (b4 shl 16) or ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
                cb(s.copy(odometerKm = km.toLong())) }
            0x4028 -> cb(s.copy(batterySoc = b4.toDouble()))
            0x4029 -> cb(s.copy(batteryTempC = (b4 - 40).toDouble()))
            0xDD04 -> cb(s.copy(cabinTempC = (b4 * 10.0 / 9.0) - 45.0))
            0x2813, 0x2814, 0x2816, 0x2815 -> {
                if (data.size < 6) return
                val b5 = data[5].toInt() and 0xFF
                val psi = ((b4 * 256.0 + b5) / 3.0 + 22.0 / 3.0) * 0.145
                if (psi < 5.0 || psi > 70.0) return
                cb(when (did) {
                    0x2813 -> s.copy(tirePressLF = psi)
                    0x2814 -> s.copy(tirePressRF = psi)
                    0x2816 -> s.copy(tirePressLR = psi)
                    else   -> s.copy(tirePressRR = psi)
                })
            }
        }
    }

    private fun parseAwdResponse(data: ByteArray, s: VehicleState, cb: (VehicleState) -> Unit) {
        if (data.size < 5 || (data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0x1E8A -> cb(s.copy(rduTempC = (b4 - 40).toDouble()))
            0xEE0B -> cb(s.copy(rduEnabled = b4 == 0x01))
        }
    }

    private fun parsePcmResponse(data: ByteArray, s: VehicleState, cb: (VehicleState) -> Unit) {
        if (data.size < 5 || (data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        val b4s = data[4].toInt()
        val b5  = if (data.size > 5) data[5].toInt() and 0xFF else 0
        when (did) {
            0x093C -> cb(s.copy(etcAngleActual  = ((b4 shl 8) or b5) * (100.0 / 8192.0)))
            0x091A -> cb(s.copy(etcAngleDesired = ((b4 shl 8) or b5) * (100.0 / 8192.0)))
            0x0462 -> cb(s.copy(wgdcDesired = b4 * 100.0 / 128.0))
            0x03EC -> cb(s.copy(ignCorrCyl1 = ((b4s.toByte().toInt() shl 8) or b5) / -512.0))
            0x03E8 -> cb(s.copy(octaneAdjustRatio = ((b4s.toByte().toInt() shl 8) or b5) / 16384.0))
            0x0461 -> cb(s.copy(chargeAirTempC = ((b4s.toByte().toInt() shl 8) or b5) / 64.0))
            0xF43C -> cb(s.copy(catalyticTempC = ((b4 shl 8) or b5) / 10.0 - 40.0))
            0xF434 -> { val afr = ((b4 shl 8) or b5) * 0.0004486
                        cb(s.copy(afrActual = afr, lambdaActual = afr / 14.7)) }
            0xF444 -> cb(s.copy(afrDesired = b4 * 0.1144))
            0x033E -> cb(s.copy(tipActualKpa  = ((b4 shl 8) or b5) / 903.81))
            0x0466 -> cb(s.copy(tipDesiredKpa = ((b4 shl 8) or b5) / 903.81))
            0x0318 -> cb(s.copy(vctIntakeAngle  = ((b4s.toByte().toInt() shl 8) or b5) / 16.0))
            0x0319 -> cb(s.copy(vctExhaustAngle = ((b4s.toByte().toInt() shl 8) or b5) / 16.0))
            0x054B -> cb(s.copy(oilLifePct = b4.toDouble().coerceIn(0.0, 100.0)))
            0xF422 -> cb(s.copy(hpFuelRailPsi = ((b4 shl 8) or b5) * 1.45038))
            0xF42F -> cb(s.copy(fuelLevelPct = (b4 * 100.0 / 255.0).coerceIn(0.0, 100.0)))
        }
    }

    private fun parsePscmResponse(data: ByteArray, s: VehicleState, cb: (VehicleState) -> Unit) {
        if (data.size < 5 || (data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) { 0xFD07 -> cb(s.copy(pdcEnabled = b4 == 0x01)) }
    }

    private fun parseFengResponse(data: ByteArray, s: VehicleState, cb: (VehicleState) -> Unit) {
        if (data.size < 5 || (data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) { 0xEE03 -> cb(s.copy(fengEnabled = b4 == 0x01)) }
    }

    private fun parseRsprotResponse(data: ByteArray, s: VehicleState, cb: (VehicleState) -> Unit) {
        if (data.size < 5 || (data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        val b5  = if (data.size > 5) data[5].toInt() and 0xFF else 0
        addDebugLine("RSProt 0x%04X → B4=0x%02X B5=0x%02X".format(did, b4, b5))
        when (did) {
            0xDE00 -> cb(s.copy(lcArmed = b4 == 0x01))
            0xDE01 -> cb(s.copy(lcRpmTarget = (b4 shl 8) or b5))
            0xDE02 -> cb(s.copy(assEnabled = b4 == 0x01))
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
