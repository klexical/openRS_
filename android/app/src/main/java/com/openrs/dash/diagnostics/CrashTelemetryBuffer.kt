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

    private const val CAPACITY = 300

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
            // Simulated demo frames are not vehicle telemetry — keep them out of crash reports.
            OpenRSDashApp.instance.vehicleState.collectLatest {
                if (!com.openrs.dash.demo.DemoMode.isActive) push(it)
            }
        }
    }

    /**
     * Serializes the ring buffer + crash metadata to [file] as JSON, with the app's memory when the crash was caught
     * (`heapUsedKb` … `nativeHeapKb`, absent when it could not be read; #510). [heap] is a seam for tests.
     * Called from the uncaught exception handler — must not throw.
     */
    fun flushToFile(
        file: File,
        exception: Throwable,
        threadName: String,
        appVersion: String,
        versionCode: Int = 0,
        sourceCommit: String = "",
        buildType: String = "",
        heap: HeapSample? = HeapSample.read(),   // at the start of the flush, before the record is built (#510)
        appendSnapshot: (StringBuilder, Long, VehicleState) -> Unit = ::appendSnapshotJson,   // seam: a test makes it throw (#511)
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
            sb.append("  \"versionCode\": $versionCode,\n")
            sb.append("  \"sourceCommit\": \"${sourceCommit.jsonEsc()}\",\n")
            sb.append("  \"buildType\": \"${buildType.jsonEsc()}\",\n")
            if (heap != null) {
                sb.append("  \"heapUsedKb\": ${heap.usedKb},\n")
                sb.append("  \"heapTotalKb\": ${heap.totalKb},\n")
                sb.append("  \"heapMaxKb\": ${heap.maxKb},\n")
                sb.append("  \"nativeHeapKb\": ${heap.nativeKb},\n")
            }
            sb.append("  \"snapshotCount\": ${snapshots.size},\n")
            sb.append("  \"snapshots\": [\n")

            snapshots.forEachIndexed { i, snap ->
                val comma = if (i < snapshots.size - 1) "," else ""
                sb.append("    ")
                appendSnapshot(sb, snap.timestampMs, snap.state)
                sb.append(comma).append('\n')
            }

            sb.append("  ]\n")
            sb.append("}\n")

            file.writeText(sb.toString())
        } catch (_: Throwable) {
            // Swallow — we must never throw from inside the crash handler.
        }
    }

    /** Appends one snapshot to [sb] as a one-line JSON object, without a trailing comma. */
    internal fun appendSnapshotJson(sb: StringBuilder, timestampMs: Long, s: VehicleState) {
        sb.append("{")
        sb.append("\"ts\":$timestampMs,")
        sb.append("\"rpm\":${"%.1f".format(Locale.ROOT, s.rpm)},")
        sb.append("\"speedKph\":${"%.1f".format(Locale.ROOT, s.speedKph)},")
        sb.append("\"boostKpa\":${"%.1f".format(Locale.ROOT, s.boostKpa)},")
        sb.append("\"throttle\":${"%.1f".format(Locale.ROOT, s.throttlePct)},")
        sb.append("\"pedal\":${"%.1f".format(Locale.ROOT, s.accelPedalPct)},")
        sb.append("\"brake\":${"%.1f".format(Locale.ROOT, s.brakePressure)},")
        sb.append("\"coolant\":${"%.1f".format(Locale.ROOT, s.coolantTempC)},")
        sb.append("\"oilTemp\":${"%.1f".format(Locale.ROOT, s.oilTempC)},")
        sb.append("\"gear\":${s.gear},")
        sb.append("\"latG\":${"%.3f".format(Locale.ROOT, s.lateralG)},")
        sb.append("\"lonG\":${"%.3f".format(Locale.ROOT, s.longitudinalG)},")
        sb.append("\"steer\":${"%.1f".format(Locale.ROOT, s.steeringAngle)},")
        sb.append("\"battV\":${"%.2f".format(Locale.ROOT, s.batteryVoltage)},")
        sb.append("\"esc\":\"${s.escStatus.diagnostic}\",")
        sb.append("\"mode\":\"${s.driveMode.label}\"")
        sb.append("}")
    }

    private fun String.jsonEsc() =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
