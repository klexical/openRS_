package com.openrs.dash.diagnostics

import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.UserPrefs
import java.io.BufferedWriter
import java.io.File

/**
 * Session-scoped diagnostic data collector.
 *
 * Collects:
 *  - Frame inventory (Option B): every unique CAN ID seen, with total count,
 *    first + last raw hex, hasChanged flag, and periodic samples every 30 s.
 *  - SLCAN raw log (Option C): every CAN frame written to a candump-format
 *    .log file on disk in real-time, capped at MAX_SLCAN_LINES.
 *  - Decode trace: rolling last MAX_TRACE decode events (id, raw, decoded, issue).
 *  - Session events: connection events, errors, firmware probe result.
 *  - FPS timeline: sampled frame-rate across the session.
 *  - Snapshot of VehicleState and UserPrefs.
 *
 * All methods are thread-safe. DiagnosticExporter reads from this object to build the ZIP.
 *
 * SLCAN log format (compatible with candump / SavvyCAN / Kayak):
 *   (   0.000) can0 0F8#0102030405060708
 *   (  30.512) can0 190#AABBCCDDEE001122
 */
object DiagnosticLogger {

    // ── Capacity limits ──────────────────────────────────────────────────────

    /** Rolling recent-activity decode trace.  At ~100 decoded fps this covers ~100 s. */
    private const val MAX_TRACE          = 10_000

    /** Session lifecycle / error events.  Enough for even the busiest sessions. */
    private const val MAX_EVENTS         = 1_000

    /** FPS timeline at 1 sample/s → 2 h of data. */
    private const val MAX_FPS_POINTS     = 7_200

    /** Periodic sample interval per CAN ID (Option B). */
    private const val SAMPLE_INTERVAL_MS = 30_000L

    /** How many periodic samples to keep per CAN ID. */
    private const val MAX_SAMPLES_PER_ID = 10

    /**
     * Maximum SLCAN log lines written per session (Option C).
     * At 400 fps average, 2 M lines ≈ 83 min of continuous logging.
     * When the limit is reached, writing stops and a warning event is emitted.
     */
    private const val MAX_SLCAN_LINES    = 2_000_000L

    // ── Data models ─────────────────────────────────────────────────────────

    /**
     * A single raw-hex snapshot taken at a point in time (Option B periodic sampling).
     * Allows comparing how a frame's bytes evolve during a drive session.
     */
    data class PeriodicSample(val relMs: Long, val rawHex: String)

    /**
     * Per-CAN-ID statistics accumulated for the whole session.
     *
     * Option B enrichment:
     *  - [firstRawHex]       : bytes seen on first observation (never changes after set)
     *  - [hasChanged]        : true if any subsequent frame differed from the first
     *  - [periodicSamples]   : up to MAX_SAMPLES_PER_ID snapshots, taken every 30 s
     */
    data class FrameInfo(
        var totalReceived: Long = 0,
        var firstRawHex: String = "",
        var lastRawHex: String = "",
        var lastDecoded: String = "",
        var hasChanged: Boolean = false,
        val validationIssues: LinkedHashSet<String> = linkedSetOf(),
        val periodicSamples: ArrayDeque<PeriodicSample> = ArrayDeque()
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

    private val frameInventory = LinkedHashMap<Int, FrameInfo>()
    private val decodeTraceDeque   = ArrayDeque<TraceEvent>()
    private val sessionEventsDeque = ArrayDeque<SessionEvent>()
    private val fpsTimelineDeque   = ArrayDeque<FpsPoint>()

    /** Tracks when we last took a periodic sample for each CAN ID (Option B). */
    private val lastSampleTimeMs = HashMap<Int, Long>()

    // ── SLCAN log file (Option C) ────────────────────────────────────────────
    private var slcanWriter: BufferedWriter? = null
    private var _slcanFile: File? = null
    private var slcanLinesWritten = 0L
    private var slcanCapReached = false

    @Volatile var sessionStartMs: Long = 0L; private set
    @Volatile var lastVehicleState: VehicleState? = null
    @Volatile var isOpenRsFirmware: Boolean = false
    @Volatile var firmwareVersion: String = "WiCAN stock"
    @Volatile var sessionHost: String = ""
    @Volatile var sessionPort: Int = 0
    @Volatile var sessionPrefs: UserPrefs? = null

    // Read-only snapshots for DiagnosticExporter
    val decodeTrace: List<TraceEvent>
        get() = synchronized(lock) { decodeTraceDeque.toList() }
    val sessionEvents: List<SessionEvent>
        get() = synchronized(lock) { sessionEventsDeque.toList() }
    val fpsTimeline: List<FpsPoint>
        get() = synchronized(lock) { fpsTimelineDeque.toList() }
    val frameInventorySnapshot: Map<Int, FrameInfo>
        get() = synchronized(lock) {
            frameInventory.mapValues { (_, info) ->
                info.copy(
                    validationIssues = LinkedHashSet(info.validationIssues),
                    periodicSamples  = ArrayDeque(info.periodicSamples)
                )
            }
        }

    /** The SLCAN log file (Option C). Null if no logDir was supplied at session start. */
    val slcanLogFile: File?    get() = synchronized(lock) { _slcanFile }

    /** Number of SLCAN lines written so far this session. */
    val slcanLineCount: Long   get() = synchronized(lock) { slcanLinesWritten }

    val sessionDurationMs: Long
        get() = if (sessionStartMs > 0) System.currentTimeMillis() - sessionStartMs else 0L

    // ── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Reset and begin a new diagnostic session.
     *
     * @param logDir  If provided, a SLCAN raw log file is opened here at
     *                "session_slcan.log". Pass [android.content.Context.filesDir]
     *                resolved to a "diagnostics" sub-folder from the calling service.
     */
    fun sessionStart(host: String, port: Int, prefs: UserPrefs?, logDir: File? = null) {
        synchronized(lock) {
            // Close any previous SLCAN writer
            try { slcanWriter?.flush(); slcanWriter?.close() } catch (_: Exception) {}
            slcanWriter = null
            _slcanFile = null
            slcanLinesWritten = 0L
            slcanCapReached = false

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
            lastSampleTimeMs.clear()

            // Option C: open SLCAN log file
            if (logDir != null) {
                try {
                    logDir.mkdirs()
                    val f = File(logDir, "session_slcan.log")
                    f.delete()
                    val w = f.bufferedWriter(Charsets.UTF_8)
                    w.write("# openRS_ SLCAN log — $host:$port\n")
                    w.write("# Session started: ${System.currentTimeMillis()} ms epoch\n")
                    w.write("# Format: (relative_seconds) can0 ID#DATA\n")
                    w.write("# Compatible with candump, SavvyCAN, Kayak\n")
                    w.flush()
                    slcanWriter = w
                    _slcanFile = f
                } catch (_: Exception) {
                    // SLCAN logging silently disabled if file cannot be opened
                }
            }
        }
        event("SESSION", "Started — $host:$port")
    }

    fun sessionEnd() {
        event("SESSION", "Ended after ${formatDuration(sessionDurationMs)}")
        // L-9 fix: flush the SLCAN writer so up to 999 buffered frames are not lost
        // when the session ends between the 1,000-line periodic flush points.
        flushSlcan()
    }

    /**
     * Flush SLCAN log to disk without closing.
     * Safe to call mid-session (e.g. when user exports diagnostics mid-drive).
     */
    fun flushSlcan() {
        synchronized(lock) {
            try { slcanWriter?.flush() } catch (_: Exception) {}
        }
    }

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

    /**
     * Called after a successful decode.
     * Logs to: frame inventory (with Option B enrichment) + decode trace + SLCAN file (Option C).
     */
    fun logFrame(
        canId: Int,
        rawData: ByteArray,
        newState: VehicleState,
        decoded: String,
        issue: String?
    ) {
        synchronized(lock) {
            val relMs  = relativeMs()
            val rawHex = rawData.joinToString(" ") { "%02X".format(it) }

            // ── Option B: enriched frame inventory ───────────────────────────
            val info = frameInventory.getOrPut(canId) { FrameInfo() }
            info.totalReceived++
            if (info.firstRawHex.isEmpty()) info.firstRawHex = rawHex
            if (!info.hasChanged && rawHex != info.firstRawHex) info.hasChanged = true
            info.lastRawHex  = rawHex
            info.lastDecoded = decoded
            if (issue != null) info.validationIssues += issue

            // Periodic sample: one snapshot per ID every SAMPLE_INTERVAL_MS
            val lastSample = lastSampleTimeMs[canId] ?: 0L
            if (relMs - lastSample >= SAMPLE_INTERVAL_MS) {
                info.periodicSamples.addLast(PeriodicSample(relMs, rawHex))
                while (info.periodicSamples.size > MAX_SAMPLES_PER_ID) info.periodicSamples.removeFirst()
                lastSampleTimeMs[canId] = relMs
            }

            // ── Rolling decode trace ─────────────────────────────────────────
            decodeTraceDeque.addLast(TraceEvent(relMs, "0x%03X".format(canId), rawHex, decoded, issue))
            while (decodeTraceDeque.size > MAX_TRACE) decodeTraceDeque.removeFirst()

            lastVehicleState = newState

            // ── Option C: SLCAN raw log ──────────────────────────────────────
            writeSlcanLine(relMs, canId, rawData)
        }
    }

    /**
     * Called for CAN IDs with no decoder (unknown/unimplemented).
     * Updates inventory count and writes SLCAN line; no decode trace entry.
     */
    fun logUnknownFrame(canId: Int, rawData: ByteArray) {
        synchronized(lock) {
            val relMs  = relativeMs()
            val rawHex = rawData.joinToString(" ") { "%02X".format(it) }

            val info = frameInventory.getOrPut(canId) { FrameInfo() }
            info.totalReceived++
            if (info.firstRawHex.isEmpty()) info.firstRawHex = rawHex
            if (!info.hasChanged && rawHex != info.firstRawHex) info.hasChanged = true
            info.lastRawHex = rawHex

            // Periodic sample for unknown frames too
            val lastSample = lastSampleTimeMs[canId] ?: 0L
            if (relMs - lastSample >= SAMPLE_INTERVAL_MS) {
                info.periodicSamples.addLast(PeriodicSample(relMs, rawHex))
                while (info.periodicSamples.size > MAX_SAMPLES_PER_ID) info.periodicSamples.removeFirst()
                lastSampleTimeMs[canId] = relMs
            }

            writeSlcanLine(relMs, canId, rawData)
        }
    }

    /**
     * Write a single candump-format line to the SLCAN log.
     * Must be called within [synchronized](lock).
     * Stops silently once MAX_SLCAN_LINES is reached.
     */
    private fun writeSlcanLine(relMs: Long, canId: Int, rawData: ByteArray) {
        val w = slcanWriter ?: return
        if (slcanLinesWritten >= MAX_SLCAN_LINES) {
            if (!slcanCapReached) {
                slcanCapReached = true
                try { w.write("# LOG CAP REACHED — further frames not written\n"); w.flush() } catch (_: Exception) {}
                // Post event outside lock would cause deadlock; use direct deque insert
                sessionEventsDeque.addLast(
                    SessionEvent(relMs, "SLCAN", "SLCAN log cap reached ($MAX_SLCAN_LINES lines)")
                )
            }
            return
        }
        try {
            val seconds = relMs / 1000.0
            val dataHex = rawData.joinToString("") { "%02X".format(it) }
            w.write("(%10.3f) can0 %03X#%s\n".format(seconds, canId, dataHex))
            slcanLinesWritten++
            // Flush to disk every 1 000 lines to limit data loss on crash
            if (slcanLinesWritten % 1_000L == 0L) w.flush()
        } catch (_: Exception) {}
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun relativeMs() = (System.currentTimeMillis() - sessionStartMs).coerceAtLeast(0L)

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
