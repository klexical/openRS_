package com.openrs.dash.can

import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Hybrid WiCAN connection — time-sliced ATMA + OBD PID queries.
 *
 * Strategy:
 *   1. Run ATMA for ~150ms → collect real-time CAN frames (AWD, G-force, wheels, etc.)
 *   2. Break out of ATMA (send any character)
 *   3. Send 2-3 OBD PID requests (calc load, fuel trims, timing, etc.)
 *   4. Parse responses
 *   5. Jump back to ATMA
 *   6. Repeat
 *
 * One full cycle: ~250ms = 4 Hz for OBD data, continuous for sniffed data.
 * This matches RSdash's 250ms polling rate while getting MORE data.
 *
 * The fast-changing CAN data (RPM, boost, speed, G-force, AWD) arrives
 * during the ATMA window at bus speed. The slow OBD data (fuel trims,
 * timing, temps) gets queried in the gaps. Best of both worlds.
 */
class WiCanConnection(
    private val host: String = "192.168.80.1",
    private val port: Int = 3333,
    private val maxRetries: Int = 3
) {
    companion object {
        const val ATMA_WINDOW_MS = 150L
        const val PID_TIMEOUT_MS = 80L
        const val PIDS_PER_CYCLE = 3

        // Exponential backoff delays per consecutive failure
        val BACKOFF_MS = listOf(5_000L, 15_000L, 30_000L)

        // Short wait before retrying after a successful-then-dropped connection
        const val RECONNECT_DELAY_MS = 5_000L
    }

    sealed class State {
        data object Disconnected : State()
        data object Connecting : State()
        data object Connected : State()
        /** All [maxRetries] attempts failed — waiting for manual reconnect. */
        data object Idle : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var _fps = 0.0
    val fps: Double get() = _fps

    private var pidCycle = 0
    private var currentHeader = ObdPids.HDR_BROADCAST  // Track which ECU we're talking to

    /**
     * Connect and emit CAN frame updates + OBD PID responses.
     * Returns a Flow of (canId, dataBytes) for CAN frames,
     * and updates VehicleState directly for OBD responses.
     */
    fun connectHybrid(
        onObdUpdate: (VehicleState) -> Unit,
        getCurrentState: () -> VehicleState
    ): Flow<Pair<Int, ByteArray>> = flow {
        var failedAttempts = 0

        while (currentCoroutineContext().isActive && failedAttempts < maxRetries) {
            var connectionSucceeded = false
            try {
                _state.value = State.Connecting

                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 500

                val output = socket.getOutputStream()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // ── ELM327 Init ─────────────────────────────
                elmCommand(output, reader, "ATZ", 1000)
                elmCommand(output, reader, "ATE0", 300)
                elmCommand(output, reader, "ATH1", 300)
                elmCommand(output, reader, "ATS0", 300)
                elmCommand(output, reader, "ATL0", 300)
                elmCommand(output, reader, "ATSP6", 500)
                elmCommand(output, reader, "ATCAF0", 300)

                _state.value = State.Connected
                connectionSucceeded = true
                failedAttempts = 0   // reset consecutive-failure counter on first successful connect

                // ── Main hybrid loop ────────────────────────
                while (currentCoroutineContext().isActive) {
                    // ── Phase 1: ATMA sniffing ──────────────
                    output.write("ATMA\r".toByteArray())
                    output.flush()

                    val atmaStart = System.currentTimeMillis()
                    val buf = StringBuilder()

                    while (System.currentTimeMillis() - atmaStart < ATMA_WINDOW_MS) {
                        try {
                            if (reader.ready()) {
                                val c = reader.read()
                                if (c == -1) break
                                buf.append(c.toChar())

                                // Process complete lines
                                while (true) {
                                    val idx = buf.indexOfFirst { it == '\r' || it == '\n' }
                                    if (idx == -1) break
                                    val line = buf.substring(0, idx).trim()
                                    buf.delete(0, idx + 1)

                                    val frame = parseLine(line)
                                    if (frame != null) {
                                        emit(frame)
                                        trackFps()
                                    }
                                }
                            } else {
                                delay(1) // Yield briefly
                            }
                        } catch (_: Exception) {
                            break
                        }
                    }

                    // ── Phase 2: Exit ATMA, query PIDs ──────
                    // Send any character to break out of ATMA
                    output.write("\r".toByteArray())
                    output.flush()
                    delay(20) // Let ELM settle
                    drainReader(reader) // Clear any remaining ATMA output

                    // Get PIDs for this cycle, grouped by ECU header
                    val pidGroups = ObdPids.getPidsForCycleGrouped(pidCycle)

                    if (pidGroups.isNotEmpty()) {
                        var state = getCurrentState().copy(dataMode = "PID_QUERY")

                        // Query each ECU group in order: broadcast first, PCM, then BCM
                        val headerOrder = listOf(ObdPids.HDR_BROADCAST, ObdPids.HDR_PCM, ObdPids.HDR_BCM)

                        for (header in headerOrder) {
                            val pids = pidGroups[header] ?: continue
                            val batch = pids.take(PIDS_PER_CYCLE)

                            // Set the appropriate header
                            when (header) {
                                ObdPids.HDR_BROADCAST -> {
                                    if (currentHeader != ObdPids.HDR_BROADCAST) {
                                        elmCommand(output, reader, "ATSH7DF", 100)
                                        currentHeader = ObdPids.HDR_BROADCAST
                                    }
                                }
                                ObdPids.HDR_PCM -> {
                                    if (currentHeader != ObdPids.HDR_PCM) {
                                        elmCommand(output, reader, "ATSH7E0", 100)
                                        currentHeader = ObdPids.HDR_PCM
                                    }
                                }
                                ObdPids.HDR_BCM -> {
                                    if (currentHeader != ObdPids.HDR_BCM) {
                                        elmCommand(output, reader, "ATSH726", 100)
                                        currentHeader = ObdPids.HDR_BCM
                                    }
                                }
                            }

                            for (pid in batch) {
                                val response = queryPid(output, reader, pid.requestStr)
                                if (response != null) {
                                    state = pid.parse(response, state)
                                }
                            }
                        }

                        state = state.copy(dataMode = "ATMA")
                        onObdUpdate(state)
                    }

                    pidCycle++

                    // Reset ATCAF0 in case PID queries changed it
                    elmCommand(output, reader, "ATCAF0", 50)
                }

                socket.close()

            } catch (e: CancellationException) {
                _state.value = State.Disconnected
                throw e
            } catch (e: Exception) {
                // Only count as a failure if we never established the TCP connection;
                // a drop after a successful session resets the counter above.
                if (!connectionSucceeded) failedAttempts++
                _state.value = State.Error(e.message ?: "Connection failed")
            }

            if (!currentCoroutineContext().isActive) break

            if (failedAttempts >= maxRetries) {
                // Exhausted — go idle and stop the loop.
                _state.value = State.Idle
                break
            }

            _state.value = State.Disconnected
            val delayMs = if (connectionSucceeded) {
                // Was connected before drop — quick 5 s retry
                RECONNECT_DELAY_MS
            } else {
                // Never connected this attempt — exponential backoff
                BACKOFF_MS.getOrElse(failedAttempts - 1) { 30_000L }
            }
            delay(delayMs)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Query a single OBD PID and return the data bytes from the response.
     * Returns null if no valid response within timeout.
     */
    private fun queryPid(output: OutputStream, reader: BufferedReader, command: String): ByteArray? {
        output.write("$command\r".toByteArray())
        output.flush()

        val start = System.currentTimeMillis()
        val buf = StringBuilder()

        while (System.currentTimeMillis() - start < PID_TIMEOUT_MS) {
            if (reader.ready()) {
                val c = reader.read()
                if (c == -1) return null
                buf.append(c.toChar())

                // Look for the > prompt indicating response is complete
                if (buf.contains(">")) break
            } else {
                Thread.sleep(2)
            }
        }

        // Parse response: find the hex data line
        // Mode 1 response: "41 XX DD DD..." (41 = mode 1 response, XX = PID)
        // Mode 22 response: "62 XX XX DD DD..." (62 = mode 22 response)
        val lines = buf.toString().split(Regex("[\\r\\n]+"))
        for (line in lines) {
            val clean = line.trim().replace(" ", "")
            // Mode 1 response starts with "41"
            if (clean.startsWith("41") && clean.length >= 6) {
                val dataHex = clean.substring(4) // Skip mode+pid bytes
                return hexToBytes(dataHex)
            }
            // Mode 22 response starts with "62"
            if (clean.startsWith("62") && clean.length >= 8) {
                val dataHex = clean.substring(6) // Skip mode+pid(2 byte) bytes
                return hexToBytes(dataHex)
            }
        }
        return null
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        if (!hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun parseLine(line: String): Pair<Int, ByteArray>? {
        if (line.length < 5) return null
        if (!line.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
        val id = line.substring(0, 3).toIntOrNull(16) ?: return null
        val dataHex = line.substring(3)
        if (dataHex.length % 2 != 0) return null
        val data = ByteArray(dataHex.length / 2) { i ->
            dataHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return Pair(id, data)
    }

    private suspend fun elmCommand(output: OutputStream, reader: BufferedReader, cmd: String, delayMs: Long) {
        output.write("$cmd\r".toByteArray())
        output.flush()
        delay(delayMs)
        drainReader(reader)
    }

    private fun drainReader(reader: BufferedReader) {
        try {
            while (reader.ready()) reader.read()
        } catch (_: Exception) {}
    }

    private fun trackFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            _fps = frameCount * 1000.0 / (now - lastFpsTime)
            frameCount = 0
            lastFpsTime = now
        }
    }
}
