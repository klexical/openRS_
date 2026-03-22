package com.openrs.dash.diagnostics

import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe ring buffer holding the most recent [CAPACITY] VehicleState
 * snapshots. On an uncaught exception [CrashReporter] calls [flushToFile]
 * to persist the buffer before the process dies.
 */
object CrashTelemetryBuffer {

    private const val CAPACITY = 100

    private val ring = arrayOfNulls<Snapshot>(CAPACITY)
    private var head = 0
    private var size = 0
    private val lock = Any()

    private var collectorJob: Job? = null

    private data class Snapshot(val timestampMs: Long, val state: VehicleState)

    fun push(state: VehicleState) {
        synchronized(lock) {
            ring[head] = Snapshot(System.currentTimeMillis(), state)
            head = (head + 1) % CAPACITY
            if (size < CAPACITY) size++
        }
    }

    /**
     * Launches a coroutine that collects VehicleState from the global flow
     * and pushes each snapshot into the ring buffer.
     */
    fun startCollecting() {
        collectorJob?.cancel()
        collectorJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            OpenRSDashApp.instance.vehicleState.collectLatest { push(it) }
        }
    }

    /**
     * Serializes the ring buffer + crash metadata to [file] as JSON.
     * Called from the uncaught exception handler — must not throw.
     */
    fun flushToFile(
        file: File,
        exception: Throwable,
        threadName: String,
        appVersion: String
    ) {
        try {
            val snapshots: List<Snapshot>
            synchronized(lock) {
                val start = if (size < CAPACITY) 0 else head
                snapshots = (0 until size).map { i ->
                    ring[(start + i) % CAPACITY]!!
                }
            }

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val sb = StringBuilder(8192)
            sb.append("{\n")
            sb.append("  \"crashedAt\": \"$ts\",\n")
            sb.append("  \"exception\": \"${exception.javaClass.name.jsonEsc()}\",\n")
            sb.append("  \"message\": \"${(exception.message ?: "").jsonEsc()}\",\n")
            sb.append("  \"stackTrace\": \"${exception.stackTraceToString().jsonEsc()}\",\n")
            sb.append("  \"thread\": \"${threadName.jsonEsc()}\",\n")
            sb.append("  \"appVersion\": \"${appVersion.jsonEsc()}\",\n")
            sb.append("  \"snapshotCount\": ${snapshots.size},\n")
            sb.append("  \"snapshots\": [\n")

            snapshots.forEachIndexed { i, snap ->
                val s = snap.state
                val comma = if (i < snapshots.size - 1) "," else ""
                sb.append("    {")
                sb.append("\"ts\":${snap.timestampMs},")
                sb.append("\"rpm\":${"%.1f".format(s.rpm)},")
                sb.append("\"speedKph\":${"%.1f".format(s.speedKph)},")
                sb.append("\"boostKpa\":${"%.1f".format(s.boostKpa)},")
                sb.append("\"throttle\":${"%.1f".format(s.throttlePct)},")
                sb.append("\"pedal\":${"%.1f".format(s.accelPedalPct)},")
                sb.append("\"brake\":${"%.1f".format(s.brakePressure)},")
                sb.append("\"coolant\":${"%.1f".format(s.coolantTempC)},")
                sb.append("\"oilTemp\":${"%.1f".format(s.oilTempC)},")
                sb.append("\"gear\":${s.gear},")
                sb.append("\"latG\":${"%.3f".format(s.lateralG)},")
                sb.append("\"lonG\":${"%.3f".format(s.longitudinalG)},")
                sb.append("\"steer\":${"%.1f".format(s.steeringAngle)},")
                sb.append("\"battV\":${"%.2f".format(s.batteryVoltage)},")
                sb.append("\"esc\":\"${s.escStatus.label}\",")
                sb.append("\"mode\":\"${s.driveMode.label}\"")
                sb.append("}$comma\n")
            }

            sb.append("  ]\n")
            sb.append("}\n")

            file.writeText(sb.toString())
        } catch (_: Throwable) {
            // Swallow — we must never throw from inside the crash handler.
        }
    }

    private fun String.jsonEsc() =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
