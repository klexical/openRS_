package com.openrs.dash.diagnostics

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.DeviceStatus
import com.openrs.dash.can.NrcTracker
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
 * SLCAN log format (candump-style lines; the app's requests are `# TX` comment lines, #209):
 *   (   0.000) can0 0F8#0102030405060708
 *   (  30.512) can0 190#AABBCCDDEE001122
 *   # TX (  30.600) can0 7E0#0322033E00000000 sent
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

    const val EVENT_LINK_STOP = "LINK_STOP"
    /** A stop with no job running: the second teardown after `stopSelf` → `onDestroy`, which must not mask the real reason. */
    const val EVENT_LINK_STOP_IDLE = "LINK_STOP_IDLE"
    const val EVENT_LINK_START_REFUSED = "LINK_START_REFUSED"
    /** The foreground service's location type was added, dropped, deferred (app not visible) or refused (#261). */
    const val EVENT_FGS_LOCATION = "FGS_LOCATION"
    const val EVENT_LINK_TASK_REMOVED = "LINK_TASK_REMOVED"
    const val EVENT_LINK_PREV = "LINK_PREV"
    const val EVENT_LINK_PREV_EXIT = "LINK_PREV_EXIT"
    /** Request capture went on or off, with the reason (#209 Step B). */
    const val EVENT_REQUEST_CAPTURE = "REQUEST_CAPTURE"
    private val CARRIED_EVENT_TYPES = listOf(EVENT_LINK_STOP, EVENT_LINK_START_REFUSED, EVENT_LINK_TASK_REMOVED)

    /** Maximum DID probe sessions stored per diagnostic session. */
    private const val MAX_PROBE_SESSIONS = 50

    /**
     * Unique CAN IDs in the frame inventory. Above every standard ID (2,048), so only extended IDs can reach it; their
     * key space is 2^29 and comes straight off the wire. They fill it in arrival order, so a flood of extended IDs can leave a
     * standard ID first seen after it without a row (counted as not listed). Frames of an ID past it still reach the SLCAN log.
     */
    private const val MAX_INVENTORY_IDS  = 4_096

    // ── Pre-allocated hex lookup (eliminates per-byte format() calls) ────────

    /** 256-entry table: index 0→"00", 1→"01", …, 255→"FF". */
    private val HEX = Array(256) { "%02X".format(it) }

    /** Convert bytes to space-separated hex (e.g. "0F 8A 01") using lookup table. */
    private fun ByteArray.toSpacedHex(): String {
        if (isEmpty()) return ""
        val sb = StringBuilder(size * 3 - 1)
        for (i in indices) {
            if (i > 0) sb.append(' ')
            sb.append(HEX[this[i].toInt() and 0xFF])
        }
        return sb.toString()
    }

    /** Convert bytes to compact hex (e.g. "0F8A01") using lookup table. */
    private fun ByteArray.toCompactHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(HEX[b.toInt() and 0xFF])
        return sb.toString()
    }

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
        var lastSeenMs: Long = 0,
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

    /** A single DID probe result from the DID Prober. */
    data class ProbeResult(val did: Int, val status: String, val responseHex: String)

    /**
     * One DID probe session (one module scan). [outcome] says how it ended and [planned] how many DIDs it set out to
     * probe, so a stopped or failed scan's partial results are kept and never read as a whole scan.
     */
    data class ProbeSession(
        val relMs: Long,
        val module: String,
        val requestId: Int,
        val responseId: Int,
        val results: List<ProbeResult>,
        val outcome: String = PROBE_COMPLETE,
        val planned: Int = results.size,
    )

    // ── Internal state ───────────────────────────────────────────────────────

    private val lock = Any()

    private val frameInventory = LinkedHashMap<Int, FrameInfo>()
    private val decodeTraceDeque   = ArrayDeque<TraceEvent>()
    private val sessionEventsDeque = ArrayDeque<SessionEvent>()
    private val fpsTimelineDeque   = ArrayDeque<FpsPoint>()
    private val probeSessionsList  = mutableListOf<ProbeSession>()

    /** Tracks when we last took a periodic sample for each CAN ID (Option B). */
    private val lastSampleTimeMs = HashMap<Int, Long>()

    // ── SLCAN log file (Option C) ────────────────────────────────────────────
    private var slcanWriter: BufferedWriter? = null
    private var _slcanFile: File? = null
    private var slcanLinesWritten = 0L
    private var slcanWriteFailed = false  // a write failed: lines are missing from the raw log, first one logs an event
    private var slcanCapHit = false       // the line cap itself, for the export; first one marks the log and logs an event
    private var slcanLinesDropped = 0L    // lines not written at the cap, received frames and request lines alike (#209)

    @Volatile var sessionStartMs: Long = 0L; private set
    @Volatile var lastVehicleState: VehicleState? = null
    @Volatile var isOpenRsFirmware: Boolean = false
    @Volatile var firmwareVersion: String = "WiCAN stock"
    @Volatile var sessionHost: String = ""
    @Volatile var sessionPort: Int = 0
    @Volatile var sessionTransport: String = ""
    @Volatile var sessionPrefs: UserPrefs? = null
    @Volatile private var nrcSnapshot: Map<Int, NrcTracker.NrcRecord> = emptyMap()
    /**
     * The adapter's own status page (`/check_status`) for THIS session only — reset by [sessionStart], so a Bluetooth
     * session or another adapter never exports an earlier Wi-Fi session's fields (#233). Separate from [firmwareVersion]
     * (the live OPENRS? handshake): neither is derived from the other.
     */
    @Volatile var adapterStatus: AdapterStatusRecord = AdapterStatusRecord.NotRequested(AdapterStatusRecord.NO_REQUEST)
        private set
    /** Fetched device status of this session, or null (not requested, waiting, or failed). */
    val deviceStatus: DeviceStatus? get() = (adapterStatus as? AdapterStatusRecord.Fetched)?.status

    /** Every PCM NRC of this session that couldn't be attributed — exact, though repeated log lines are folded (#216). */
    @Volatile var nrcUnattributedTotal: Int = 0; private set
    fun countUnattributedNrc() = synchronized(lock) { nrcUnattributedTotal++ }

    /**
     * Link-integrity counters of this session (rc.8 #253), exported as LINK INTEGRITY / `linkIntegrity`. Bounded: counts
     * only, never a raw line. [LinkIntegrity.writes] covers the tracked request writes (Mode 22 polls and DID probes):
     * what each write established (#248), not what a module did with it.
     */
    class LinkIntegrity {
        val writes = linkedMapOf(WRITE_SENT to 0, WRITE_POSSIBLY_DELIVERED to 0, WRITE_NOT_SENT to 0, WRITE_UNCERTAIN to 0,
            WRITE_OTHER_FAILURE to 0)
        /** null = no connection reached a framing decision yet; else whether frames carried the Z1 timestamp (#251). */
        var framingTimestamped: Boolean? = null
        var refusedFrameLines = 0          // frame-shaped lines refused by strict framing (#251)
        var refusedOtherShape = 0          // … of them, lines of the other framing shape on a locked connection
        var refusedReplies = 0             // Mode 22 replies refused for their length / PCI (#251)
        var nrcAttributed = 0              // PCM NRCs charged to exactly one request (#216)
        var overlongLines = 0              // adapter lines past the transport's bound, refused whole (#253)
        var linesDropped = 0               // BLE lines lost to a full receive queue (#253)
        var commandRepliesDropped = 0      // +FRS: lines lost to a full reply channel, nobody waiting (#253)
        var dtcFramesDropped = 0           // DTC reply frames lost to a full channel (#253)
        var isoTpRefused = 0               // ISO-TP frames refused or replies abandoned: truncated SF / FF / CF, FF < 8, sequence break, superseded, deadline (#253)
        fun copy() = LinkIntegrity().also {
            it.writes.putAll(writes); it.framingTimestamped = framingTimestamped; it.refusedFrameLines = refusedFrameLines
            it.refusedOtherShape = refusedOtherShape; it.refusedReplies = refusedReplies; it.nrcAttributed = nrcAttributed
            it.overlongLines = overlongLines; it.linesDropped = linesDropped
            it.commandRepliesDropped = commandRepliesDropped; it.dtcFramesDropped = dtcFramesDropped
            it.isoTpRefused = isoTpRefused
        }
    }

    /**
     * Per-module, per-DID Mode 22 inventory of this session (#253): requests the pollers wrote (not certainly lost),
     * positive answers (single or first frame; DID-prober answers are not counted), and rejections charged to that DID.
     * Counts only. At most [MAX_INVENTORY_DIDS] rows; further DIDs are counted in [DidInventory.overflow].
     */
    class DidStat(val responseId: Int, val did: Int) {
        var sent = 0; var positive = 0; var nrcAttributed = 0
        /** Session-relative time of the last positive answer, or -1. */
        var lastPositiveRelMs = -1L
        fun copy() = DidStat(responseId, did).also { it.sent = sent; it.positive = positive; it.nrcAttributed = nrcAttributed; it.lastPositiveRelMs = lastPositiveRelMs }
    }
    class DidInventory(val rows: List<DidStat>, val overflow: Int)
    const val MAX_INVENTORY_DIDS = 256
    private val didInventoryLive = LinkedHashMap<Long, DidStat>()
    private var didInventoryOverflow = 0
    private fun didStatLocked(responseId: Int, did: Int): DidStat? {
        val key = (responseId.toLong() shl 16) or (did.toLong() and 0xFFFF)
        didInventoryLive[key]?.let { return it }
        if (didInventoryLive.size >= MAX_INVENTORY_DIDS) { didInventoryOverflow++; return null }
        return DidStat(responseId, did).also { didInventoryLive[key] = it }
    }
    fun countDidSent(responseId: Int, did: Int) = synchronized(lock) { didStatLocked(responseId, did)?.let { it.sent++ } }
    fun countDidPositive(responseId: Int, did: Int) = synchronized(lock) {
        didStatLocked(responseId, did)?.let { it.positive++; it.lastPositiveRelMs = System.currentTimeMillis() - sessionStartMs }
    }
    fun countDidNrc(responseId: Int, did: Int) = synchronized(lock) { didStatLocked(responseId, did)?.let { it.nrcAttributed++ } }
    val didInventory: DidInventory get() = synchronized(lock) { DidInventory(didInventoryLive.values.map { it.copy() }, didInventoryOverflow) }
    private var linkIntegrityLive = LinkIntegrity()
    // ── UI timing (#421) ──────────────────────────────────────────────────────
    /** Main-thread lateness, fed by CanDataService's Main ticker while a connection runs. */
    val mainThreadLag = MainThreadLag(System::nanoTime)
    /** Screen frames by tab, fed by MainActivity's frame-metrics listener while [uiTimingCounting]. */
    val frameTimes = FrameTimes()
    /** The OBD merges' wait for the link lock, fed at CanDataService's merge call site. */
    val linkLockWait = LinkLockWait()
    /** True from [sessionStart] to [sessionEnd]: frames are counted only while a connection's session runs. */
    @Volatile var uiTimingCounting = false
        private set
    /** Bluetooth delivery of this session's earlier attempts, and the current attempt's live counts (read at export). */
    private var bleDeliveryEarlier: com.openrs.dash.can.BleDelivery? = null
    private var attemptBleDelivery: com.openrs.dash.can.BleDeliveryCounts? = null
    /** The session's Bluetooth delivery: null while no attempt of it was Bluetooth. Under [lock]. */
    private fun bleDeliveryLocked(): com.openrs.dash.can.BleDelivery? {
        val now = attemptBleDelivery?.read()
        val earlier = bleDeliveryEarlier
        return if (earlier == null) now else if (now == null) earlier else earlier + now
    }

    /** A consistent copy of this session's counters. */
    val linkIntegrity: LinkIntegrity get() = synchronized(lock) { linkIntegrityLive.copy() }

    /** One reading of the counters an export reports, taken under one lock: its summary and detail state the same numbers (L12). */
    class ExportCounters(val integrity: LinkIntegrity, val nrcUnattributed: Int, val limits: LogLimits,
                         val dids: DidInventory = DidInventory(emptyList(), 0),
                         val bleMtu: BleMtuAttempt? = null,
                         val capture: RequestCaptureRecord = RequestCaptureRecord(emptyList(), 0, 0, 0, 0),
                         /** UI timing (#421): null before the first connection of this app run. */
                         val uiTiming: UiTimingRecord? = null)
    val exportCounters: ExportCounters get() = synchronized(lock) {
        ExportCounters(linkIntegrityLive.copy(), nrcUnattributedTotal, logLimits,
            DidInventory(didInventoryLive.values.map { it.copy() }, didInventoryOverflow),
            attemptMtuTrace?.let { BleMtuAttempt(connectionAttempts, it.read()) },
            RequestCaptureRecord(captureSpans.toList(), captureSpansDropped, captureTimeouts, captureLateInWindow, captureLateAfterWindow),
            if (sessionStartMs > 0) UiTimingRecord(mainThreadLag.read(), frameTimes.read(), linkLockWait.read(), bleDeliveryLocked()) else null)
    }

    /** The current connection attempt's MTU context: [attempt] counts this session's attempts from 1 (#253). */
    class BleMtuAttempt(val attempt: Int, val context: com.openrs.dash.can.BleMtuContext)
    private var connectionAttempts = 0
    private var attemptMtuTrace: com.openrs.dash.can.BleMtuTrace? = null

    /**
     * A connection attempt starts. [bleMtu] is that attempt's trace, null for a transport that is not Bluetooth: the
     * previous attempt's context is replaced either way, so an export never shows an older link's MTU. The trace is read
     * at export time, so a callback that arrives after the setup stopped waiting is still reported.
     */
    fun connectionAttempt(bleMtu: com.openrs.dash.can.BleMtuTrace?,
                          bleDelivery: com.openrs.dash.can.BleDeliveryCounts? = null) = synchronized(lock) {
        connectionAttempts++
        attemptMtuTrace = bleMtu
        // UI timing (#421): unlike the MTU, delivery is the session's: the finished attempt's counts are kept
        bleDeliveryEarlier = bleDeliveryLocked()
        attemptBleDelivery = bleDelivery
    }
    fun countWrite(kind: String) = synchronized(lock) { linkIntegrityLive.writes[kind] = (linkIntegrityLive.writes[kind] ?: 0) + 1 }
    fun framingDecided(timestamped: Boolean) = synchronized(lock) { linkIntegrityLive.framingTimestamped = timestamped }
    fun countRefusedFrameLine(otherShape: Boolean) = synchronized(lock) {
        linkIntegrityLive.refusedFrameLines++; if (otherShape) linkIntegrityLive.refusedOtherShape++
    }
    fun countRefusedReply() = synchronized(lock) { linkIntegrityLive.refusedReplies++ }
    fun countAttributedNrc() = synchronized(lock) { linkIntegrityLive.nrcAttributed++ }
    fun countOverlongLine() = synchronized(lock) { linkIntegrityLive.overlongLines++ }
    fun countLineDropped() = synchronized(lock) { linkIntegrityLive.linesDropped++ }
    fun countCommandReplyDropped() = synchronized(lock) { linkIntegrityLive.commandRepliesDropped++ }
    fun countDtcFrameDropped() = synchronized(lock) { linkIntegrityLive.dtcFramesDropped++ }
    fun countIsoTpRefused() = synchronized(lock) { linkIntegrityLive.isoTpRefused++ }

    /**
     * What this session's logs no longer hold (#253), so a rolling or capped list is never read as the whole session.
     * Oldest entries roll off the trace, events, FPS timeline and probe sessions; the SLCAN log stops at its cap.
     */
    data class LogLimits(
        val decodeTraceDropped: Long = 0, val eventsDropped: Long = 0, val fpsPointsDropped: Long = 0,
        val probeSessionsDropped: Long = 0, val inventoryFramesNotListed: Long = 0, val slcanCapReached: Boolean = false, val slcanWriteFailed: Boolean = false,
        val vinFramesMaskedPage: Long = 0, val vinFramesMaskedContent: Long = 0, val slcanLinesDropped: Long = 0,
    )
    /** Second ID holding the VIN (#260). No decoder. */
    private const val ID_VIN_MUX_2 = 0x405
    /** Consecutive VIN bytes that make a frame count as holding it, once the VIN is known. Cheap to tune. */
    internal const val VIN_RUN_MIN = 4
    @Volatile private var knownVin: ByteArray? = null
    private val f190CfBytesLeft = HashMap<Int, Int>()
    private var vinFramesMaskedPage = 0L
    private var vinFramesMaskedContent = 0L
    private var traceDropped = 0L
    private var eventsDropped = 0L
    private var fpsDropped = 0L
    private var probeSessionsDropped = 0L
    private var inventoryNotListed = 0L
    /** A consistent copy of this session's counts. */
    val logLimits: LogLimits get() = synchronized(lock) {
        LogLimits(traceDropped, eventsDropped, fpsDropped, probeSessionsDropped, inventoryNotListed, slcanCapHit, slcanWriteFailed,
            vinFramesMaskedPage, vinFramesMaskedContent, slcanLinesDropped)
    }

    /** Identity of the current session — [sessionStart] returns it; adapter-status writes carry it (#233). */
    @Volatile private var sessionToken: Long = 0L

    /** Applies [record] only while [token] is still the current session — a late write from an older connection is dropped. */
    private fun setAdapterStatus(token: Long, record: AdapterStatusRecord) = synchronized(lock) {
        if (token == sessionToken) adapterStatus = record
    }
    fun adapterStatusRequested(token: Long) = setAdapterStatus(token, AdapterStatusRecord.Requested)
    fun adapterStatusFetched(token: Long, status: DeviceStatus, atMs: Long = System.currentTimeMillis()) =
        setAdapterStatus(token, AdapterStatusRecord.Fetched(status, atMs))
    fun adapterStatusFailed(token: Long) = setAdapterStatus(token, AdapterStatusRecord.Failed)

    /** One read of the adapter status + session start — an export builds its text and JSON from the same snapshot (#233). */
    fun snapshotAdapterStatus(): AdapterStatusSnapshot = synchronized(lock) { AdapterStatusSnapshot(adapterStatus, sessionStartMs) }
    /** This session's DTC scan record — the single publication path for scans and retries (#215). */
    val dtcScans = DtcScanLedger()
    /** Last DTC scan of this session (any outcome) — null = no scan; exported with status and module coverage (#215). */
    val lastDtcScan: com.openrs.dash.data.DtcScanRecord? get() = dtcScans.current

    /** Capture NRC suppression state from the active NrcTracker for diagnostic export. */
    fun snapshotNrc(tracker: NrcTracker) {
        nrcSnapshot = tracker.getSuppressed()
    }

    /** Read-only snapshot of NRC suppressions for report builders. */
    val nrcSuppressed: Map<Int, NrcTracker.NrcRecord> get() = nrcSnapshot

    // Read-only snapshots for DiagnosticExporter
    val decodeTrace: List<TraceEvent>
        get() = synchronized(lock) { decodeTraceDeque.toList() }
    val sessionEvents: List<SessionEvent>
        get() = synchronized(lock) { sessionEventsDeque.toList() }
    val fpsTimeline: List<FpsPoint>
        get() = synchronized(lock) { fpsTimelineDeque.toList() }
    val probeSessions: List<ProbeSession>
        get() = synchronized(lock) {
            // At read, not at record: a reply stored before the VIN was decoded is covered once the VIN is decoded before the export. An export
            // written before that (knownVin is cleared at session start) keeps the reply's bytes in every member: cold start only. The exports are the only readers (#260)
            if (knownVin == null) probeSessionsList.toList()
            else probeSessionsList.map { s -> s.copy(results = s.results.map { it.vinRunMasked() }) }
        }
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
    /**
     * Starts a diagnostic session and returns its token. [adapterStatusReason] is the session's initial adapter-status
     * state (Bluetooth: [AdapterStatusRecord.BLUETOOTH]) — set together with the session, so no window exists in which an
     * older connection's status could show (#233).
     */
    fun sessionStart(
        host: String, port: Int, prefs: UserPrefs?, logDir: File? = null, transport: String = "",
        adapterStatusReason: String = AdapterStatusRecord.NO_REQUEST,
    ): Long {
        val token: Long
        val carried: List<String>
        val safeHost = LogText.sanitize(host)            // goes into the summary, JSON, SLCAN header and share text (#263)
        val safeTransport = LogText.sanitize(transport)
        synchronized(lock) {
            // Why the last link stopped survives this wipe, one hop (#212); a first session's events have no session to be relative to
            val hadSession = sessionStartMs > 0
            carried = CARRIED_EVENT_TYPES.mapNotNull { type ->
                sessionEventsDeque.lastOrNull { it.type == type }?.let { ev ->
                    "$type +${if (hadSession) "${ev.relMs}ms" else "?"} ${ev.message}"
                }
            }

            // Close any previous SLCAN writer
            try { slcanWriter?.flush(); slcanWriter?.close() } catch (_: Exception) {}
            slcanWriter = null
            _slcanFile = null
            slcanLinesWritten = 0L
            slcanWriteFailed = false
            knownVin = null
            f190CfBytesLeft.clear()
            vinFramesMaskedPage = 0L
            vinFramesMaskedContent = 0L
            captureSpans.clear(); captureSpansDropped = 0; captureTimeouts = 0; captureLateInWindow = 0; captureLateAfterWindow = 0

            sessionStartMs = sessionClock()
            sessionHost = safeHost
            sessionPort = port
            sessionTransport = safeTransport
            sessionPrefs = prefs
            isOpenRsFirmware = false
            firmwareVersion = "WiCAN stock"
            token = ++sessionToken
            adapterStatus = AdapterStatusRecord.NotRequested(adapterStatusReason)
            lastVehicleState = null
            frameInventory.clear()
            decodeTraceDeque.clear()
            sessionEventsDeque.clear()
            fpsTimelineDeque.clear()
            probeSessionsList.clear()
            lastSampleTimeMs.clear()
            dtcScans.newSession()      // a new session starts with no scan; older scans can't publish into it
            nrcSnapshot = emptyMap()   // NRC suppression is per connection (#216)
            nrcUnattributedTotal = 0
            linkIntegrityLive = LinkIntegrity()
            connectionAttempts = 0; attemptMtuTrace = null
            // UI timing (#421): every counter starts at this connection's start
            mainThreadLag.reset(); frameTimes.reset(); linkLockWait.reset()
            bleDeliveryEarlier = null; attemptBleDelivery = null
            uiTimingCounting = true
            didInventoryLive.clear(); didInventoryOverflow = 0
            traceDropped = 0L; eventsDropped = 0L; fpsDropped = 0L; probeSessionsDropped = 0L; inventoryNotListed = 0L
            slcanCapHit = false; slcanLinesDropped = 0L

            // Option C: open SLCAN log file
            if (logDir != null) {
                try {
                    logDir.mkdirs()
                    val f = File(logDir, "session_slcan.log")
                    f.delete()
                    val w = f.bufferedWriter(Charsets.UTF_8)
                    val transportLabel = if (safeTransport.isNotEmpty()) " ($safeTransport)" else ""
                    w.write("# openRS_ SLCAN log — $safeHost:$port$transportLabel\n")
                    w.write("# Session started: ${System.currentTimeMillis()} ms epoch\n")
                    // The request form sits inside the Format line: a header line starting "# TX " would read as a request (#209)
                    w.write("# Format: (relative_seconds) can0 ID#DATA; the app's requests: # TX (relative_seconds) can0 ID#DATA <result>\n")
                    w.write("# Read by can-utils' canplayer and log2asc, which skip the # lines\n")
                    w.write("# VIN-bearing frames keep ID, length, time and mux; their data bytes read 00\n")
                    w.flush()
                    slcanWriter = w
                    _slcanFile = f
                } catch (_: Exception) {
                    // SLCAN logging silently disabled if file cannot be opened
                }
            }
        }
        event("SESSION", "Started — $safeHost:$port")
        carried.forEach { event(EVENT_LINK_PREV, it) }
        return token
    }

    fun sessionEnd() {
        event("SESSION", "Ended after ${formatDuration(sessionDurationMs)}")
        synchronized(lock) {
            try {
                slcanWriter?.flush()
                slcanWriter?.close()
            } catch (e: Exception) { noteSlcanWriteFailedLocked(relativeMs(), e) }
            slcanWriter = null
            uiTimingCounting = false   // #421: no frames counted between sessions; the counts stay for the export
        }
    }

    /**
     * Flush SLCAN log to disk without closing.
     * Safe to call mid-session (e.g. when user exports diagnostics mid-drive).
     */
    fun flushSlcan() {
        synchronized(lock) {
            try { slcanWriter?.flush() } catch (e: Exception) { noteSlcanWriteFailedLocked(relativeMs(), e) }
        }
    }

    // ── Logging methods ──────────────────────────────────────────────────────

    fun event(type: String, msg: String) {
        synchronized(lock) {
            addEventLocked(SessionEvent(relativeMs(), type, msg))
        }
    }

    /**
     * An event that happened at [wallMs] (System.currentTimeMillis) and is written now: an observer's note is stamped
     * when the driver asked for it, not when the text was saved. It takes its place in time order among the events
     * already logged. A moment before this session started reads as the session's start (0).
     */
    fun eventAt(type: String, msg: String, wallMs: Long) {
        synchronized(lock) {
            val relMs = (wallMs - sessionStartMs).coerceAtLeast(0L).coerceAtMost(relativeMs())
            var i = sessionEventsDeque.size
            while (i > 0 && sessionEventsDeque[i - 1].relMs > relMs) i--
            addEventLocked(SessionEvent(relMs, type, msg), at = i)
        }
    }

    /** Must be called within [synchronized](lock). */
    private fun addEventLocked(ev: SessionEvent, at: Int = sessionEventsDeque.size) {
        sessionEventsDeque.add(at, ev)
        while (sessionEventsDeque.size > MAX_EVENTS) {
            sessionEventsDeque.removeFirst()
            eventsDropped++
        }
    }

    /** The ID's inventory row, or null once [MAX_INVENTORY_IDS] are listed (counted). Within [synchronized](lock). */
    private fun inventoryRowLocked(canId: Int): FrameInfo? {
        frameInventory[canId]?.let { return it }
        if (frameInventory.size >= MAX_INVENTORY_IDS) {
            inventoryNotListed++
            return null
        }
        return FrameInfo().also { frameInventory[canId] = it }
    }

    fun fps(value: Double) {
        synchronized(lock) {
            fpsTimelineDeque.addLast(FpsPoint(relativeMs(), value))
            while (fpsTimelineDeque.size > MAX_FPS_POINTS) {
                fpsTimelineDeque.removeFirst()
                fpsDropped++
            }
        }
    }

    /**
     * Called after a successful decode.
     * Logs to: frame inventory (with Option B enrichment) + decode trace + SLCAN file (Option C).
     */
    /**
     * Which of a frame's data bytes can hold the VIN (#260), as a bit per byte; 0 for an ordinary frame, which is nearly all of them.
     * One choke point for the raw log, the inventory and the trace:
     *  - pages, known before the VIN is: 0x40A mux C1 from byte 2; 0x405 (no decoder, real captures, one car) page 10 from byte 5, pages 11 and 12
     *    from byte 1. Other 0x405 pages held no VIN bytes in those captures; what they do hold is unknown, and they stay;
     *  - a DID 0xF190 reply on a module ID, with any consecutive frames that follow it;
     *  - once the VIN is decoded, [VIN_RUN_MIN] or more consecutive bytes of it in any other frame, for another car's layout. That can mask an
     *    unrelated frame, so it has its own count. Three bytes collide too easily; a test measures that.
     */
    private fun vinBytes(canId: Int, d: ByteArray): Int {
        if (d.isEmpty()) return 0
        val b0 = d[0].toInt() and 0xFF
        val page = when {
            canId == CanDecoder.ID_VIN_MUX && b0 == 0xC1 -> fromByte(2, d.size)
            canId == ID_VIN_MUX_2 && b0 == 0x10 -> fromByte(5, d.size)
            canId == ID_VIN_MUX_2 && (b0 == 0x11 || b0 == 0x12) -> fromByte(1, d.size)
            canId in 0x700..0x7FF -> f190Bytes(canId, d, b0)
            else -> 0
        }
        if (page != 0) { synchronized(lock) { vinFramesMaskedPage++ }; return page }
        val run = vinRunBytes(d)
        if (run != 0) synchronized(lock) { vinFramesMaskedContent++ }
        return run
    }

    private fun fromByte(first: Int, size: Int): Int = if (first >= size) 0 else ((1 shl size) - 1) and ((1 shl first) - 1).inv()

    /** SF `0N 62 F1 90 …`, FF `1L LL 62 F1 90 …`, then the CFs that FF announced. Any other FF on that ID ends the count. */
    private fun f190Bytes(canId: Int, d: ByteArray, b0: Int): Int = synchronized(lock) {
        fun u(i: Int) = d[i].toInt() and 0xFF
        when (b0 and 0xF0) {
            0x00 -> if (d.size > 4 && u(1) == 0x62 && u(2) == 0xF1 && u(3) == 0x90) fromByte(4, d.size) else 0
            0x10 -> if (d.size >= 5 && u(2) == 0x62 && u(3) == 0xF1 && u(4) == 0x90) {
                f190CfBytesLeft[canId] = (((b0 and 0x0F) shl 8) or u(1)) - (d.size - 2)
                fromByte(5, d.size)
            } else { f190CfBytesLeft.remove(canId); 0 }
            0x20 -> {
                val left = f190CfBytesLeft[canId] ?: 0
                if (left > 0) { f190CfBytesLeft[canId] = left - (d.size - 1); fromByte(1, d.size) } else 0
            }
            else -> 0
        }
    }

    private fun vinRunBytes(d: ByteArray): Int {
        val vin = knownVin ?: return 0
        var bits = 0
        var o = 0
        while (o <= d.size - VIN_RUN_MIN) {
            var best = 0
            for (v in vin.indices) {
                var k = 0
                while (o + k < d.size && v + k < vin.size && d[o + k] == vin[v + k]) k++
                if (k > best) best = k
            }
            if (best >= VIN_RUN_MIN) { for (i in o until o + best) bits = bits or (1 shl i); o += best } else o++
        }
        return bits
    }

    /** "C1 02 ** ** ** ** ** **": the rest still says which page arrived and how long it was. */
    private fun ByteArray.maskedSpacedHex(bits: Int) = indices.joinToString(" ") { i -> if (bits and (1 shl i) != 0) "**" else "%02X".format(this[i]) }

    /** The raw log keeps candump's fields, so a masked byte reads 00. */
    private fun ByteArray.maskedCompactHex(bits: Int) = copyOf().also { c -> for (i in c.indices) if (bits and (1 shl i) != 0) c[i] = 0 }.toCompactHex()

    fun logFrame(
        canId: Int,
        rawData: ByteArray,
        newState: VehicleState,
        decoded: String,
        issue: String?
    ) {
        // Pre-compute hex + SLCAN line outside lock — no allocations under contention
        if (newState.vin.isNotEmpty()) knownVin = newState.vin.toByteArray(Charsets.US_ASCII)
        val vin        = vinBytes(canId, rawData)
        val rawHex     = if (vin != 0) rawData.maskedSpacedHex(vin) else rawData.toSpacedHex()
        val compactHex = if (vin != 0) rawData.maskedCompactHex(vin) else rawData.toCompactHex()
        val idHex      = "0x%03X".format(canId)
        val relMs      = relativeMs()
        val slcanLine  = buildSlcanLine(relMs, canId, compactHex)

        synchronized(lock) {
            // ── Option B: enriched frame inventory ───────────────────────────
            inventoryRowLocked(canId)?.let { info ->
                info.totalReceived++
                info.lastSeenMs = System.currentTimeMillis()
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
            }

            // ── Rolling decode trace ─────────────────────────────────────────
            decodeTraceDeque.addLast(TraceEvent(relMs, idHex, rawHex, decoded, issue))
            while (decodeTraceDeque.size > MAX_TRACE) {
                decodeTraceDeque.removeFirst()
                traceDropped++
            }

            lastVehicleState = newState

            // ── Option C: SLCAN raw log ──────────────────────────────────────
            writePreformattedSlcanLine(relMs, slcanLine)
        }
    }

    /**
     * Called for CAN IDs with no decoder (unknown/unimplemented).
     * Updates inventory count and writes SLCAN line; no decode trace entry.
     */
    fun logUnknownFrame(canId: Int, rawData: ByteArray) {
        // Pre-compute hex + SLCAN line outside lock
        val vin        = vinBytes(canId, rawData)
        val rawHex     = if (vin != 0) rawData.maskedSpacedHex(vin) else rawData.toSpacedHex()
        val compactHex = if (vin != 0) rawData.maskedCompactHex(vin) else rawData.toCompactHex()
        val relMs      = relativeMs()
        val slcanLine  = buildSlcanLine(relMs, canId, compactHex)

        synchronized(lock) {
            inventoryRowLocked(canId)?.let { info ->
                info.totalReceived++
                info.lastSeenMs = System.currentTimeMillis()
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
            }

            writePreformattedSlcanLine(relMs, slcanLine)
        }
    }

    /**
     * Log an OBD response frame to the SLCAN raw log only.
     * OBD responses (0x7E8, 0x72E, 0x70B, etc.) are consumed before reaching
     * processCanFrame, so this ensures they still appear in the SLCAN export.
     */
    fun logObdFrame(canId: Int, rawData: ByteArray) {
        val vin        = vinBytes(canId, rawData)
        val compactHex = if (vin != 0) rawData.maskedCompactHex(vin) else rawData.toCompactHex()
        val relMs      = relativeMs()
        val slcanLine  = buildSlcanLine(relMs, canId, compactHex)
        synchronized(lock) {
            writePreformattedSlcanLine(relMs, slcanLine)
        }
    }

    /** The session clock now, for a request's line: taken BEFORE its write (#209, QA's T1), as the ledger stamps it. */
    fun txStamp(): Long = relativeMs()

    /**
     * A frame the app wrote (#209; the DTC scan's since #215): a `# TX` comment line in the SLCAN log, on the replies'
     * clock, with [stampMs] taken before the write ([txStamp]) and the write's [result] in the link-integrity counters' own
     * words ([WRITE_SENT] …; a failed write is logged too). Being a comment, candump's readers skip it (can-utils'
     * canplayer and log2asc skip any line not starting with "("). Written when the result is known, so the file can hold a
     * reply before the request line that is earlier than it: a reader orders by the time field.
     * Only a CAN frame (`t<id><dlc><data>\r`) is a TX line: the SLCAN init, `OPENRS?` and `AT+FRS` lines never are. The
     * data goes through the VIN mask's content layer (#260) like any frame; no request carries the VIN.
     */
    fun logTxLine(slcanFrame: String, stampMs: Long, result: String) {   // no defaults: the time before the write and the result are always the caller's
        val body = requestBody(slcanFrame, stampMs) ?: return
        synchronized(lock) { writePreformattedSlcanLine(stampMs, "# TX $body $result\n") }
    }

    /**
     * Request capture (#209 Step B): [slcanFrame]'s request got no answer within [waitedMs]; [stampMs] is when the wait
     * ended. A comment line that does not start with `# TX`, so no reader picks it as a request; the frame goes through the
     * same parse and VIN mask as a TX line.
     */
    fun logCaptureTimeout(slcanFrame: String, stampMs: Long, waitedMs: Long, pending: Boolean) {
        val body = requestBody(slcanFrame, stampMs) ?: return
        val what = if (pending) "response pending, no final answer" else "no answer"   // a 7F 22 78 came (N3)
        synchronized(lock) {
            captureTimeouts++
            writePreformattedSlcanLine(stampMs, "# CAPTURE-TIMEOUT $body $what within $waitedMs ms\n")
        }
    }

    /** A request frame's candump part (`(time) can0 ID#DATA`), VIN-masked; null for anything but a `t` frame. */
    private fun requestBody(slcanFrame: String, stampMs: Long): String? {
        val f = slcanFrame.trimEnd('\r')
        if (f.length < 5 || f[0] != 't') return null
        val id = f.substring(1, 4).toIntOrNull(16) ?: return null
        val hex = f.substring(5)
        if (hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) return null
        val data = ByteArray(hex.length / 2) { ((Character.digit(hex[2 * it], 16) shl 4) or Character.digit(hex[2 * it + 1], 16)).toByte() }
        val run = vinRunBytes(data)
        if (run != 0) synchronized(lock) { vinFramesMaskedContent++ }
        return buildSlcanLine(stampMs, id, if (run != 0) data.maskedCompactHex(run) else data.toCompactHex()).trimEnd('\n')
    }

    // ── Request capture record (#209 Step B) ─────────────────────────────────
    /** One stretch of request capture, session-relative ms: on at [onMs], off at [offMs] (null: still on) for [offReason]. */
    data class CaptureSpan(val onMs: Long, val offMs: Long?, val offReason: String?)
    /** The session's request capture record for the export: spans (capped), spans not kept, answers that timed out. */
    class RequestCaptureRecord(val spans: List<CaptureSpan>, val spansDropped: Int, val timeouts: Int,
                               val lateInWindow: Int, val lateAfterWindow: Int)
    private val captureSpans = mutableListOf<CaptureSpan>()     // guarded by lock; cleared by sessionStart
    private var captureSpansDropped = 0
    private var captureTimeouts = 0
    private var captureLateInWindow = 0
    private var captureLateAfterWindow = 0

    /**
     * A late PCM answer under request capture (#209, the spec review's C1): [inWindow] = an answer inside the late-answer
     * window after a timed-out request (that request's); false = a positive reply after the window for a DID that timed
     * out and is not the one being waited for.
     */
    fun countCaptureLate(inWindow: Boolean) = synchronized(lock) { if (inWindow) captureLateInWindow++ else captureLateAfterWindow++ }
    private const val MAX_CAPTURE_SPANS = 100

    /** Request capture went [on] or off for [reason]: a session event, and the span the export reports. */
    fun requestCaptureChanged(on: Boolean, reason: String) = synchronized(lock) {
        val relMs = relativeMs()
        if (on) {
            if (captureSpans.size < MAX_CAPTURE_SPANS) captureSpans += CaptureSpan(relMs, null, null) else captureSpansDropped++
        } else {
            val i = captureSpans.indexOfLast { it.offMs == null }
            if (i >= 0) captureSpans[i] = captureSpans[i].copy(offMs = relMs, offReason = reason)
        }
        addEventLocked(SessionEvent(relMs, EVENT_REQUEST_CAPTURE,
            if (on) "Request capture on: PCM requests one at a time, up to ${com.openrs.dash.can.RequestCapture.WAIT_MS} ms per answer"
            else "Request capture off: $reason"))
    }

    /** Build a candump-format line outside the lock to keep hot-path holds short. Always `.` decimals (candump). */
    internal fun buildSlcanLine(relMs: Long, canId: Int, compactHex: String): String {
        val seconds = relMs / 1000.0
        return "(%10.3f) can0 %03X#%s\n".format(java.util.Locale.ROOT, seconds, canId, compactHex)
    }

    /**
     * Write a pre-formatted candump line to the SLCAN log.
     * Must be called within [synchronized](lock).
     * Stops silently once MAX_SLCAN_LINES is reached.
     */
    private fun writePreformattedSlcanLine(relMs: Long, line: String) {
        val w = slcanWriter ?: return
        if (slcanLinesWritten >= MAX_SLCAN_LINES) {
            slcanLinesDropped++
            if (!slcanCapHit) {
                slcanCapHit = true
                try { w.write("# LOG CAP REACHED — further lines (frames and requests) not written\n"); w.flush() } catch (_: Exception) {}
                // Already within the lock
                addEventLocked(SessionEvent(relMs, "SLCAN", "SLCAN log cap reached ($MAX_SLCAN_LINES lines)"))
            }
            return
        }
        try {
            w.write(line)
            slcanLinesWritten++
            // Flush to disk every 200 lines to limit data loss on crash
            if (slcanLinesWritten % 200L == 0L) w.flush()
        } catch (e: Exception) {
            noteSlcanWriteFailedLocked(relMs, e)
        }
    }

    /** The first write or flush failure: lines are missing from the raw log, so the export says so (#253). */
    private fun noteSlcanWriteFailedLocked(relMs: Long, e: Exception) {
        if (slcanWriteFailed) return
        slcanWriteFailed = true
        addEventLocked(SessionEvent(relMs, "SLCAN", "SLCAN write failed: ${LogText.sanitizeReason(e)}"))
    }

    // ── DID Probe results ────────────────────────────────────────────────────

    const val WRITE_SENT = "sent"
    const val WRITE_POSSIBLY_DELIVERED = "possiblyDelivered"
    const val WRITE_NOT_SENT = "notSent"
    const val WRITE_UNCERTAIN = "deliveryUnknown"
    const val WRITE_OTHER_FAILURE = "otherFailure"

    const val PROBE_COMPLETE = "COMPLETE"
    const val PROBE_STOPPED = "STOPPED"
    const val PROBE_FAILED = "FAILED"

    /** A 0xF190 reply's bytes are the VIN (#260): the export says the DID answered and how long, not what. The screen keeps its own list. */
    private fun ProbeResult.vinMasked() =
        if (did == 0xF190 && status != "NRC" && status != PROBE_NRC_AMBIGUOUS) copy(responseHex = responseHex.replace(Regex("[0-9A-Fa-f]{2}"), "00")) else this

    /** The content layer for a stored reply on any DID: the same run rule as a frame. The frame itself is already counted, so this adds no count. */
    private fun ProbeResult.vinRunMasked(): ProbeResult {
        if (!PROBE_HEX.matches(responseHex)) return this
        val tokens = responseHex.split(' ')
        val bits = vinRunBytes(ByteArray(tokens.size) { tokens[it].toInt(16).toByte() })
        if (bits == 0) return this
        return copy(responseHex = tokens.indices.joinToString(" ") { i -> if (bits and (1 shl i) != 0) "00" else tokens[i] })
    }
    private val PROBE_HEX = Regex("[0-9A-Fa-f]{2}( [0-9A-Fa-f]{2}){0,30}")

    /** A FAILED session logs no event here — the scan's own failure event names the DID and the reason. */
    fun recordProbeSession(
        module: String,
        requestId: Int,
        responseId: Int,
        results: List<ProbeResult>,
        outcome: String = PROBE_COMPLETE,
        planned: Int = results.size,
    ) {
        synchronized(lock) {
            probeSessionsList.add(
                ProbeSession(relativeMs(), module, requestId, responseId, results.map { it.vinMasked() }, outcome, planned)
            )
            // removeAt(0), not removeFirst(): on a MutableList that compiles to java.util.List.removeFirst
            // (API 35+) and throws NoSuchMethodError on Android 9–14 (#196 lint NewApi)
            while (probeSessionsList.size > MAX_PROBE_SESSIONS) {
                probeSessionsList.removeAt(0)
                probeSessionsDropped++
            }
        }
        val found = results.count { it.status == "FOUND" }
        when (outcome) {
            PROBE_COMPLETE -> event("PROBE", "$module scan complete: ${results.size} probed, $found found")
            PROBE_STOPPED -> event("PROBE", "$module scan stopped: ${results.size} of $planned probed, $found found")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The wall clock behind the session clock ([relativeMs], so every raw-log time). Production never sets it; tests that
     * must tell a time taken before a write from one taken after it (#209, QA's T1) set a virtual clock, and restore it.
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    @Volatile internal var sessionClock: () -> Long = System::currentTimeMillis

    private fun relativeMs() = (sessionClock() - sessionStartMs).coerceAtLeast(0L)

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}

/**
 * Where this session's adapter status came from (#233). Only [Fetched] carries fields; the others say why there are none,
 * so an export never shows a status from another session, transport or adapter — and never a substitute version.
 */
/** This session's adapter-status record and session start, read once per export (#233). */
data class AdapterStatusSnapshot(val record: AdapterStatusRecord, val sessionStartMs: Long) {
    val deviceStatus: DeviceStatus? get() = (record as? AdapterStatusRecord.Fetched)?.status
}

sealed interface AdapterStatusRecord {
    /** Not requested in this session — [reason] says why (Bluetooth: the status page is read over Wi-Fi only). */
    data class NotRequested(val reason: String) : AdapterStatusRecord
    /** Requested over Wi-Fi, no answer yet. */
    data object Requested : AdapterStatusRecord
    /** Read from `/check_status` over Wi-Fi in this session at [atMs]. */
    data class Fetched(val status: DeviceStatus, val atMs: Long) : AdapterStatusRecord
    /** The Wi-Fi request failed or the reply couldn't be read. */
    data object Failed : AdapterStatusRecord

    companion object {
        const val NO_REQUEST = "no status request in this session"
        const val BLUETOOTH = "Bluetooth session — the adapter status page (/check_status) is read over Wi-Fi only"
    }
}
