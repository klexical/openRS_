package com.openrs.dash.diagnostics

import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.UserPrefs

/**
 * Session-scoped diagnostic data collector.
 *
 * Collects:
 *  - Frame inventory: every unique CAN ID seen, with total count, last raw hex, last decoded values
 *  - Decode trace: last MAX_TRACE decode events (timestamp, ID, raw, decoded, any validation issue)
 *  - Session events: connection events, errors, firmware probe result
 *  - FPS timeline: sampled frame-rate across the session
 *  - Snapshot of VehicleState and UserPrefs
 *
 * All methods are thread-safe. DiagnosticExporter reads from this object to build the ZIP.
 */
object DiagnosticLogger {

    private const val MAX_TRACE       = 500
    private const val MAX_EVENTS      = 300
    private const val MAX_FPS_POINTS  = 200

    // ── Data models ─────────────────────────────────────────────────────────

    data class FrameInfo(
        var totalReceived: Long = 0,
        var lastRawHex: String = "",
        var lastDecoded: String = "",
        val validationIssues: LinkedHashSet<String> = linkedSetOf()
    )

    data class TraceEvent(
        val relMs: Long,
        val idHex: String,
        val rawHex: String,
        val decoded: String,
        val issue: String?
    )

    data class SessionEvent(
        val relMs: Long,
        val type: String,
        val message: String
    )

    data class FpsPoint(val relMs: Long, val fps: Double)

    // ── Internal state ───────────────────────────────────────────────────────

    private val lock = Any()

    val frameInventory = LinkedHashMap<Int, FrameInfo>()
    private val decodeTraceDeque  = ArrayDeque<TraceEvent>()
    private val sessionEventsDeque = ArrayDeque<SessionEvent>()
    private val fpsTimelineDeque  = ArrayDeque<FpsPoint>()

    @Volatile var sessionStartMs: Long = 0L; private set
    @Volatile var lastVehicleState: VehicleState? = null
    @Volatile var isOpenRsFirmware: Boolean = false
    @Volatile var firmwareVersion: String = "WiCAN stock"
    @Volatile var sessionHost: String = ""
    @Volatile var sessionPort: Int = 0
    @Volatile var sessionPrefs: UserPrefs? = null

    // Read-only snapshots for DiagnosticExporter
    val decodeTrace: List<TraceEvent>  get() = synchronized(lock) { decodeTraceDeque.toList() }
    val sessionEvents: List<SessionEvent> get() = synchronized(lock) { sessionEventsDeque.toList() }
    val fpsTimeline: List<FpsPoint>    get() = synchronized(lock) { fpsTimelineDeque.toList() }
    val frameInventorySnapshot: Map<Int, FrameInfo>
        get() = synchronized(lock) { frameInventory.toMap() }

    val sessionDurationMs: Long
        get() = if (sessionStartMs > 0) System.currentTimeMillis() - sessionStartMs else 0L

    // ── Session lifecycle ────────────────────────────────────────────────────

    fun sessionStart(host: String, port: Int, prefs: UserPrefs?) {
        synchronized(lock) {
            sessionStartMs = System.currentTimeMillis()
            sessionHost = host
            sessionPort = port
            sessionPrefs = prefs
            isOpenRsFirmware = false
            firmwareVersion = "WiCAN stock"
            lastVehicleState = null
            frameInventory.clear()
            decodeTraceDeque.clear()
            sessionEventsDeque.clear()
            fpsTimelineDeque.clear()
        }
        event("SESSION", "Started — $host:$port")
    }

    fun sessionEnd() = event("SESSION", "Ended after ${formatDuration(sessionDurationMs)}")

    // ── Logging methods ──────────────────────────────────────────────────────

    fun event(type: String, msg: String) {
        synchronized(lock) {
            val rel = relativeMs()
            sessionEventsDeque.addLast(SessionEvent(rel, type, msg))
            while (sessionEventsDeque.size > MAX_EVENTS) sessionEventsDeque.removeFirst()
        }
    }

    fun fps(value: Double) {
        synchronized(lock) {
            fpsTimelineDeque.addLast(FpsPoint(relativeMs(), value))
            while (fpsTimelineDeque.size > MAX_FPS_POINTS) fpsTimelineDeque.removeFirst()
        }
    }

    /** Called after a successful decode. Logs frame inventory + decode trace. */
    fun logFrame(
        canId: Int,
        rawData: ByteArray,
        newState: VehicleState,
        decoded: String,
        issue: String?
    ) {
        synchronized(lock) {
            val rawHex = rawData.joinToString(" ") { "%02X".format(it) }
            val info = frameInventory.getOrPut(canId) { FrameInfo() }
            info.totalReceived++
            info.lastRawHex = rawHex
            info.lastDecoded = decoded
            if (issue != null) info.validationIssues += issue

            decodeTraceDeque.addLast(
                TraceEvent(relativeMs(), "0x%03X".format(canId), rawHex, decoded, issue)
            )
            while (decodeTraceDeque.size > MAX_TRACE) decodeTraceDeque.removeFirst()

            lastVehicleState = newState
        }
    }

    /** Called for CAN IDs with no decoder (unknown/unimplemented). Just updates inventory count. */
    fun logUnknownFrame(canId: Int, rawData: ByteArray) {
        synchronized(lock) {
            val info = frameInventory.getOrPut(canId) { FrameInfo() }
            info.totalReceived++
            info.lastRawHex = rawData.joinToString(" ") { "%02X".format(it) }
            // lastDecoded stays empty → visible in report as "(no decoder)"
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun relativeMs() = (System.currentTimeMillis() - sessionStartMs).coerceAtLeast(0L)

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
