package com.openrs.dash.can

import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DtcModuleSpec
import com.openrs.dash.data.DtcRawScan
import com.openrs.dash.data.DtcStage
import com.openrs.dash.data.DtcSendFailure
import com.openrs.dash.data.DtcReply
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.LogText
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
    private val reconnectDelayMs: Long = 5_000L,
    /** Live engine RPM — selects fast vs slow PCM polling (rc.5). */
    private val rpmProvider: () -> Double = { 0.0 },
    /**
     * True while the link must not be given up after [maxRetries] failures — an active drive recording. A recording
     * that lost its link is not an idle session: it keeps retrying (backoff capped at 30 s) until the link returns or the
     * connection is stopped. Polled every second during that backoff: once the recording ends, the ordinary bounded
     * policy applies again — its budget is already spent, so the job ends then rather than after another wait and
     * attempt. A connected link is never dropped because recording stopped.
     */
    private val keepRetrying: () -> Boolean = { false },
    /** True while a drive is recording, not paused: MAP 0xF40B joins the PCM fast group (#271). Read for every batch. */
    private val mapPoll: () -> Boolean = { false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Request capture (#209 Step B): the switch this connection reads, and turns off at every link up and down. */
    private val capture: RequestCapture = RequestCapture.shared,
    private val debugSink: (String) -> Unit = { OpenRSDashApp.instance.pushDebugLine(it) },
    /** Wall clock for NRC attribution / re-probe timing — tests pass the virtual clock. */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Ms that keep counting while the phone sleeps, for measuring gaps (#356): a wall-clock correction must not make or
     * hide one, and with the screen off the phone may sleep (System.nanoTime stops then; elapsedRealtime does not).
     */
    private val monotonicClock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** Where a gap is written: type, text and the wall time of its start (#356); tests capture it. */
    private val gapLog: (type: String, message: String, wallMs: Long) -> Unit = DiagnosticLogger::eventAt,
    /**
     * #316: an established connection dropped and the normal reconnect attempts have failed. Called once per dropped
     * connection, with the moment the drop was found ([clock]); never for attempts with no connection before them.
     */
    private val onLinkLost: (dropAtMs: Long) -> Unit = {},
    /**
     * #316 (b), owner "Yes, carry it": a drop handed over from the connection this one replaces (a manual reconnect during
     * that connection's attempts). Held from construction exactly as this connection's own drop would be: reported with
     * this moment once this job's attempts fail, cleared by a connect, takeable once by a closure or the next reconnect.
     */
    heldDropAtMs: Long? = null,
    /** Where firmware capability is published (#220) — the app's flags by default; tests capture it. */
    private val firmwareSink: (FirmwareStatus) -> Unit = { st ->
        OpenRSDashApp.instanceOrNull?.let { app ->
            app.isOpenRsFirmware.value = st.openRs
            app.firmwareProbing.value = st.probing
            app.firmwareVersionLabel.value = st.label
        }
    },
) {
    /**
     * What the `OPENRS?` probe has established (#220): [probing] while probes are outstanding, [openRs] once an
     * `OPENRS:<version>` reply arrived (at any time — a late reply still counts). Not detected after every probe is a
     * capability statement about this connection, not proof the firmware is stock.
     */
    data class FirmwareStatus(val openRs: Boolean, val probing: Boolean, val label: String)
    private fun addDebugLine(line: String) = debugSink(line)

    /** The transport of the attempt in progress (opening, handshaking or connected) — closed by [abort]. */
    @Volatile private var attemptTransport: SlcanTransport? = null

    /**
     * Close the current attempt's transport from another thread so a blocking connect / read returns at once instead of
     * after its timeout. The caller cancels the collecting coroutine; this only makes that prompt.
     */
    fun abort() {
        try { attemptTransport?.close() } catch (_: Exception) { }
    }

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
    /**
     * Service the active DTC operation expects replies to (0x19 read DTC, 0x14 clear), −1 = none. Ordinary polling
     * continues during a scan, so only replies to this service — and the consecutive frames of a multi-frame reply that
     * started as one — are diverted; a `62` poll reply or the scan's own `50 03` session reply goes to the normal path
     * instead of finishing the module (#215). The DID prober no longer uses this channel (#250, [ProbeQuery]).
     */
    @Volatile private var _dtcExpectSid: Int = -1
    private val _dtcMultiFrameIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Frame loop: should this frame from a watched ID go to the DTC channel? */
    private fun divertToDtc(id: Int, d: ByteArray): Boolean {
        val sid = _dtcExpectSid
        if (sid < 0) return false
        if (d.isEmpty()) return false
        return when ((d[0].toInt() and 0xF0) shr 4) {
            0 -> { _dtcMultiFrameIds.remove(id); d.size >= 2 && isReplyTo(sid, d, 1) }
            1 -> (d.size >= 3 && isReplyTo(sid, d, 2)).also { if (it) _dtcMultiFrameIds.add(id) else _dtcMultiFrameIds.remove(id) }
            2 -> id in _dtcMultiFrameIds
            else -> false
        }
    }

    /** Positive reply (SID + 0x40) or a negative reply to [sid] starting at payload index [i]. */
    private fun isReplyTo(sid: Int, d: ByteArray, i: Int): Boolean {
        val b = d[i].toInt() and 0xFF
        return b == sid + 0x40 || (b == 0x7F && d.size > i + 1 && (d[i + 1].toInt() and 0xFF) == sid)
    }

    /** Start a DTC-channel operation expecting replies to [sid] (−1 = any) from [ids]. */
    private fun beginDtcOperation(ids: Set<Int>, sid: Int) {
        _dtcExpectSid = sid
        _dtcMultiFrameIds.clear()
        _dtcWatchIds = ids
        _dtcScanActive = true
    }

    private fun endDtcOperation() {
        _dtcScanActive = false
        _dtcWatchIds = emptySet()
        _dtcExpectSid = -1
        _dtcMultiFrameIds.clear()
    }

    /**
     * The DID prober's one in-flight query (#250). Only its own reply is taken from the frame loop: a positive matched by
     * response ID + `62` + DID, or a `7F 22` the module's ledger attributes to its DID. Every other frame — poll replies,
     * NRCs another request could own — takes the normal path (parser, ledger, NRC tracker).
     */
    private class ProbeQuery(val responseId: Int, val did: Int, val ledger: PcmRequestLedger) {
        /** A `7F 22 78` came while this PCM probe waited: its capture timeout line says so (#209). */
        @Volatile var pending = false
        val reply = CompletableDeferred<ByteArray>()
        /** A `7F 22` arrived while another request could own it (charged to nobody). */
        @Volatile var ambiguousNrc: Int? = null
    }
    @Volatile private var _probe: ProbeQuery? = null

    /**
     * Request capture (#209 Step B): held by the PCM poller and by a PCM DID probe from the write until the answer or
     * [RequestCapture.WAIT_MS], while the capture is on, so the two never have requests waiting at once. A probe takes it
     * inside [_dtcMutex]; the poller never takes [_dtcMutex], so the order is fixed.
     */
    private val captureGate = Mutex()
    /** The poller's request waiting under the capture: ended by its DID's positive reply or any final `7F 22` (not 78). */
    private class CaptureWait(val did: Int) {
        val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
        /** A `7F 22 78` came: the timeout line then says the answer was pending (the spec review's N3). */
        @Volatile var pending = false
    }
    @Volatile private var captureWait: CaptureWait? = null
    /** C1 (owner, 2026-11-14): after a timed-out PCM request, nothing goes to the PCM before this ([clock] ms); 0 = no pause. */
    @Volatile private var captureLateUntil = 0L
    /** The DID of the request that opened the pause, whose late answer it waits for. */
    @Volatile private var captureLateDid: Int? = null
    /** DIDs whose capture request timed out and whose answer has not come: a later positive for one is counted late. */
    private val captureTimedOut: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())
    /** The capture this connection's turns belong to ([RequestCapture.switchOns]) and the clock when its first turn came. */
    @Volatile private var captureSeen = -1
    @Volatile private var captureFloor = 0L
    /** The last PCM probe, read after it returns: whether a response pending came for it. */
    @Volatile private var lastPcmProbe: ProbeQuery? = null

    /**
     * Request capture's turn, inside [captureGate] before any write to the PCM: the late-answer pause after a timeout
     * (C1), then any PCM request still waiting from before the switch, up to [RequestCapture.WAIT_MS] after its write.
     */
    private suspend fun awaitCaptureTurn() {
        val rest = captureLateUntil - clock()
        if (rest > 0) delay(rest)
        // The first turn of a capture marks its start: only requests sent before it are waited for (the capture's own
        // requests can stay outstanding after an unattributable rejection, and are not waited for again)
        if (captureSeen != capture.switchOns) { captureSeen = capture.switchOns; captureFloor = clock() }
        _ledgerFor?.invoke(ObdConstants.PCM_RESPONSE_ID)?.outstandingRequests()?.filter { it.sentAtMs < captureFloor }?.forEach { r ->
            val left = r.sentAtMs + RequestCapture.WAIT_MS - clock()
            if (left > 0) withTimeoutOrNull(left) { r.reply.await() }
        }
    }

    /** A capture request for [did], written at [writtenAt], got no answer in time: the late-answer pause starts (C1). */
    private fun captureTimedOut(did: Int, writtenAt: Long) {
        captureLateUntil = writtenAt + RequestCapture.LATE_WINDOW_MS
        captureLateDid = did
        captureTimedOut += did
    }

    /**
     * A DTC scan, clear or freeze-frame read (C2, owner 2026-11-14): under [_dtcMutex] as always; while the capture is on
     * it also holds [captureGate] for the whole operation, after its turn, so the PCM poller pauses for its length.
     */
    private suspend inline fun <T> dtcOperation(block: () -> T): T = _dtcMutex.withLock {
        if (capture.on.value) captureGate.withLock { awaitCaptureTurn(); block() } else block()
    }
    /** This connection's Mode 22 request ledger for a response ID (the PCM's is the poller's own), null when offline. */
    @Volatile private var _ledgerFor: ((Int) -> PcmRequestLedger)? = null
    private val possiblyDeliveredNoted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * A frame write outside the tracked requests (flow control, a DTC clear, a freeze-frame read): its `# TX` line too, with
     * what the write established (#209). Through [SlcanTransport.write], never `writeLine`: on Bluetooth `writeLine` returns
     * normally for a possibly delivered write and drops that outcome.
     */
    private suspend fun writeLogged(transport: SlcanTransport, frame: String) {
        val at = DiagnosticLogger.txStamp()
        val outcome = try { transport.write(frame) } catch (e: Exception) { DiagnosticLogger.logTxLine(frame, at, failureKind(e)); throw e }
        DiagnosticLogger.logTxLine(frame, at, outcomeKind(outcome))
    }

    /** What a write that returned established, in the link-integrity counters' words (#248, #209). */
    private fun outcomeKind(outcome: WriteOutcome): String =
        if (outcome == WriteOutcome.POSSIBLY_DELIVERED) DiagnosticLogger.WRITE_POSSIBLY_DELIVERED else DiagnosticLogger.WRITE_SENT

    /** What a write that threw established, in the link-integrity counters' words (#253); a cancelled write may have gone out. */
    private fun failureKind(e: Exception): String = when {
        e is CancellationException -> DiagnosticLogger.WRITE_UNCERTAIN
        WriteFailure.isNotSent(e) -> DiagnosticLogger.WRITE_NOT_SENT
        WriteFailure.isUncertain(e) -> DiagnosticLogger.WRITE_UNCERTAIN
        else -> DiagnosticLogger.WRITE_OTHER_FAILURE
    }

    /**
     * A tracked request write that threw: counted by what it established (#253 diagnostics), and its `# TX` line written with
     * [atMs], the time taken before the write (#209).
     */
    private fun noteWriteFailure(frame: String, atMs: Long, e: Exception) {
        DiagnosticLogger.logTxLine(frame, atMs, failureKind(e))
        if (e is CancellationException) return
        DiagnosticLogger.countWrite(failureKind(e))
    }

    /**
     * A tracked request write that returned: counted, its `# TX` line written with [atMs] (taken before the write, #209), and
     * one bounded line per connection the first time a write comes back possibly delivered (#248).
     */
    private fun noteWrite(frame: String, atMs: Long, outcome: WriteOutcome) {
        val kind = outcomeKind(outcome)
        DiagnosticLogger.logTxLine(frame, atMs, kind)
        DiagnosticLogger.countWrite(kind)
        if (outcome == WriteOutcome.POSSIBLY_DELIVERED && possiblyDeliveredNoted.compareAndSet(false, true)) {
            DiagnosticLogger.event("BLE_WRITE", "Adapter answered a write with GATT 13 (longer than its declared maximum); " +
                "treated as possibly delivered — the request stays outstanding until its reply and is never resent")
        }
    }

    /**
     * Send one Mode 22 read ([frame], `tIII8 03 22 DH DL …`) for the DID prober and wait up to [timeoutMs] for ITS reply
     * from [responseId] (#250). The request is registered in that module's ledger like a poll (e1 decision B, #216): a
     * probe DID that never answers lapses like any unanswered request, so NRC suppression stays off until the next
     * connection — it can lose suppression, never charge an NRC to the wrong DID. Never retransmitted. Throws on a write
     * failure ([WriteFailure]: withdrawn only when certainly not sent) or [com.openrs.dash.diagnostics.QUERY_NOT_CONNECTED].
     */
    suspend fun sendRawQuery(
        responseId: Int,
        frame: String,
        timeoutMs: Long = 1_500L
    ): RawReply? = _dtcMutex.withLock {
        // While the capture is on, a PCM probe waits for the poller's request and holds the poller off (#209 Step B)
        // While the capture is on, a PCM probe waits for the poller's request and holds the poller off, waits up to
        // max(its timeout, the capture's set time), and marks no answer as the poller does (#209 Step B, the spec review's C3)
        if (responseId == ObdConstants.PCM_RESPONSE_ID && capture.on.value) captureGate.withLock {
            awaitCaptureTurn()
            val waitMs = maxOf(timeoutMs, RequestCapture.WAIT_MS)
            val writtenAt = clock()
            probeOnce(responseId, frame, waitMs).also {
                if (it == null && _transport != null) {
                    DiagnosticLogger.logCaptureTimeout(frame, DiagnosticLogger.txStamp(), waitMs, lastPcmProbe?.pending == true)
                    Mode22.request(frame)?.second?.let { did -> captureTimedOut(did, writtenAt) }
                }
            }
        }
        else probeOnce(responseId, frame, timeoutMs)
    }

    private suspend fun probeOnce(responseId: Int, frame: String, timeoutMs: Long): RawReply? {
        val transport = _transport ?: throw java.io.IOException(com.openrs.dash.diagnostics.QUERY_NOT_CONNECTED)
        val ledgerFor = _ledgerFor ?: throw java.io.IOException(com.openrs.dash.diagnostics.QUERY_NOT_CONNECTED)
        val did = Mode22.request(frame)?.second ?: throw WriteFailure.notSent("not a Mode 22 read request")
        val ledger = ledgerFor(responseId)
        val probe = ProbeQuery(responseId, did, ledger)
        if (responseId == ObdConstants.PCM_RESPONSE_ID) lastPcmProbe = probe
        val req = ledger.sent(did, clock())
        _probe = probe
        try {
            val at = DiagnosticLogger.txStamp()
            try { noteWrite(frame, at, transport.write(frame)) } catch (e: Exception) {
                noteWriteFailure(frame, at, e)
                // Withdrawn only when certainly not sent — an uncertain or cancelled write may have reached CAN (#248)
                if (e !is CancellationException && !WriteFailure.isUncertain(e)) ledger.withdraw(req)
                throw e
            }
            return withTimeoutOrNull(timeoutMs) { probe.reply.await() }?.let { RawReply.Frame(it) }
                ?: probe.ambiguousNrc?.let { RawReply.AmbiguousNrc(it) }
        } finally {
            if (_probe === probe) _probe = null
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    /**
     * #316: the moment an established connection dropped whose loss is not reported yet (the reconnect attempts are
     * still under way), handed over once: the service's closure reports that one loss with this time instead of its
     * own, and a manual reconnect hands it to the new connection (`heldDropAtMs`). Null when there is none.
     */
    fun takeUnreportedDrop(): Long? = synchronized(dropLock) { unreportedDropAtMs.also { unreportedDropAtMs = null } }

    private val dropLock = Any()
    /**
     * Guarded by [dropLock]: set when an established connection ends (or handed in at construction), cleared by a new
     * connection or a hand-over.
     */
    private var unreportedDropAtMs: Long? = heldDropAtMs

    fun connectHybrid(
        onObdUpdate: (VehicleState) -> Unit,
        getCurrentState: () -> VehicleState
    ): Flow<Pair<Int, ByteArray>> = channelFlow<Pair<Int, ByteArray>> {

        var failedAttempts = 0
        var consecutiveDrops = 0
        // Once this job has been connected, every later attempt (and its backoff) is a reconnect.
        var everConnected = false

        while (currentCoroutineContext().isActive) {
            var connectionSucceeded = false
            var captureToken: Any? = null                          // this attempt's link, for request capture (#209)
            var connectedAtMs = 0L
            _state.value = if (everConnected) AdapterState.Reconnecting else AdapterState.Connecting
            val transport = try {
                transportFactory()
            } catch (e: Exception) {
                if (e is CancellationException) { _state.value = AdapterState.Disconnected; throw e }
                // A transport that can't be built (#234 — Bluetooth selected with no device saved) is a failed attempt
                // like a refused connect: counted, reported, retried on the usual backoff. It used to escape the flow
                // and crash the app at every launch.
                UnbuiltTransport(LogText.sanitizeReason(e))
            }
            attemptTransport = transport
            // The export's MTU context is this attempt's from here on; the previous attempt's is gone (#253). Its delivery
            // counts add to the session's (#421)
            DiagnosticLogger.connectionAttempt(transport.bleMtu, transport.bleDelivery)
            try {
                addDebugLine("--- ${transport.label} ---")
                addDebugLine("Connecting to ${transport.label}")

                val attemptStartMs = clock()
                transport.open()

                transport.writeLine(ObdConstants.SLCAN_INIT)
                addDebugLine("SLCAN init sent (C / S6 / O)")

                // Probe 1's outcome is logged at once (#220) — also when the write or the handshake then fails the
                // attempt. A failed write still fails the attempt, as before.
                val probeTotal = PROBE_TIMES_MS.size + 1
                val kind = transport.kind
                try {
                    transport.writeLine("OPENRS?\r")
                } catch (e: Exception) {
                    if (e !is CancellationException) DiagnosticLogger.event("FIRMWARE",
                        "Probe 1 of $probeTotal write FAILED ($kind, +${clock() - attemptStartMs} ms): ${LogText.sanitizeReason(e)}")
                    throw e
                }
                DiagnosticLogger.event("FIRMWARE", "Probe 1 of $probeTotal written ($kind, +${clock() - attemptStartMs} ms)")
                addDebugLine("Probing firmware...")
                var probeNoise = 0          // non-frame, non-empty lines seen before a verdict: counted, never logged (#220)

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
                    // Accept firmware probe response OR any SLCAN data frame, plain or timestamped (#251: the framing
                    // mode is decided by the frame loop)
                    if (line.startsWith("OPENRS:") || SlcanParser.shape(line) != null) {
                        handshakeOk = true
                        firstLine = line
                        break
                    }
                    // A refused frame line counts here as in the frame loop; the handshake never logs a line (#253)
                    if (line.startsWith("t") || line.startsWith("T")) DiagnosticLogger.countRefusedFrameLine(otherShape = false)
                    // Some adapters echo back init commands — keep waiting
                    if (line.isNotEmpty()) probeNoise++
                }
                if (!handshakeOk) {
                    addDebugLine("No SLCAN response — device may not be a WiCAN adapter")
                    // Also reached when the read ended early (transport closed) — the limit is stated, not the time waited
                    DiagnosticLogger.event("FIRMWARE", "No probe verdict — the SLCAN handshake ended without a frame or reply " +
                        "(${HANDSHAKE_TIMEOUT_MS / 1000} s limit; $kind, +${clock() - attemptStartMs} ms); this attempt ends")
                    throw RuntimeException(
                        "Device did not respond to SLCAN init — may not be a CAN adapter"
                    )
                }
                addDebugLine("SLCAN handshake OK")

                _state.value = AdapterState.Connected
                _transport = transport
                captureToken = capture.linkUp()                        // every connection starts with request capture off (#209)
                captureLateUntil = 0L; captureLateDid = null; captureTimedOut.clear()
                connectionSucceeded = true
                everConnected = true
                connectedAtMs = System.currentTimeMillis()
                failedAttempts = 0
                synchronized(dropLock) { unreportedDropAtMs = null }   // the connection is back: no loss to report (#316)
                firmwareVersion = transport.stockFirmwareLabel
                val bcmIsoTp = IsoTpBuffer { DiagnosticLogger.countIsoTpRefused() }
                val nrcTracker = NrcTracker()
                val nrcLog = UnattributedNrcLog()
                // Requests waiting for a reply, oldest first: a 7F 22 NRC belongs to the oldest one (#216)
                val pcmRequests = PcmRequestLedger()
                val framing = SlcanFraming()                     // #251: plain unless the first frames are timestamped
                var framingNoted = false
                val gaps = ReceptionStall()                     // #356: gaps on this connection, named in the session log
                // Each gap is logged at its start: the wall time now, less the time counted since the gap began
                fun logGaps(reports: List<ReceptionStall.Report>) = reports.forEach { r ->
                    gapLog(r.type, r.message, clock() - (monotonicClock() - r.startMs))
                }
                // Other modules' Mode 22 requests (pollers + prober) by response ID: decides who may own a 7F 22 (#250)
                val moduleLedgers = java.util.concurrent.ConcurrentHashMap<Int, PcmRequestLedger>()
                fun ledgerFor(responseId: Int): PcmRequestLedger =
                    if (responseId == ObdConstants.PCM_RESPONSE_ID) pcmRequests
                    else moduleLedgers.getOrPut(responseId) { PcmRequestLedger() }
                _ledgerFor = ::ledgerFor
                possiblyDeliveredNoted.set(false)
                /**
                 * A non-PCM poll write: a Mode 22 read is registered in its module's ledger (response ID = request ID + 8
                 * for every polled module) and withdrawn only when certainly not sent (#248); other lines pass through.
                 */
                suspend fun pollWrite(q: String) {
                    val target = Mode22.request(q)
                    val ledger = target?.let { ledgerFor(it.first + 8) }
                    val req = target?.let { ledger!!.sent(it.second, clock()) }
                    val at = DiagnosticLogger.txStamp()
                    try { noteWrite(q, at, transport.write(q)) } catch (e: Exception) {
                        noteWriteFailure(q, at, e)
                        if (req != null && !WriteFailure.isUncertain(e)) ledger!!.withdraw(req)
                        else if (target != null) DiagnosticLogger.countDidSent(target.first + 8, target.second)   // may have gone out
                        throw e
                    }
                    target?.let { DiagnosticLogger.countDidSent(it.first + 8, it.second) }                     // inventory (#253)
                }
                /** Keeps a non-PCM module's ledger in step with its replies; true = the frame is the prober's NRC (taken). */
                fun moduleReply(ledger: PcmRequestLedger, id: Int, d: ByteArray): Boolean {
                    Mode22.positiveDid(d)?.let { ledger.positive(it); return false }
                    val code = Mode22.negativeCode(d) ?: return false
                    if (code == NrcTracker.NRC_RESPONSE_PENDING) { ledger.pending(clock()); return false }
                    val owner = ledger.negative(clock())
                    val probing = _probe?.takeIf { it.responseId == id }
                    if (owner != null && owner != probing?.did) DiagnosticLogger.countDidNrc(id, owner)          // inventory (#253)
                    probing ?: return false
                    if (owner == probing.did) { DiagnosticLogger.logObdFrame(id, d); probing.reply.complete(d); return true }
                    if (owner == null) probing.ambiguousNrc = code
                    return false
                }

                // ── BCM OBD poller ────────────────────────────────────────
                // TPMS sensor-ID reads (#518): all four on the first cycle, then only a position still unmapped in the
                // state, each at most TPMS_ID_MAX_SENDS times on this connection (read-only Mode 22 to 0x726)
                val tpmsIdReads = TpmsIdReads()
                val obdJob = launch {
                    delay(ObdConstants.BCM_INITIAL_DELAY_MS)
                    while (isActive) {
                        tpmsIdReads.due(getCurrentState()).forEach { q ->
                            try { pollWrite(q) } catch (_: Exception) { }
                            delay(ObdConstants.BCM_QUERY_GAP_MS)
                        }
                        ObdConstants.BCM_QUERIES.forEach { q ->
                            try { pollWrite(q) } catch (_: Exception) { }
                            delay(ObdConstants.BCM_QUERY_GAP_MS)
                        }
                        ObdConstants.AWD_QUERIES.forEach { q ->
                            try { pollWrite(q) } catch (_: Exception) { }
                            delay(ObdConstants.BCM_QUERY_GAP_MS)
                        }
                        delay(ObdConstants.BCM_POLL_INTERVAL_MS)
                    }
                }

                // ── PCM OBD poller ────────────────────────────────────────
                // Engine running: fast group (TIP/WGDC/KR) every cycle + one slow DID.
                // Engine off: full sweep + 30 s idle. See PcmPollSchedule.
                val pcmJob = launch {
                    delay(ObdConstants.PCM_INITIAL_DELAY_MS)
                    var slowIndex = 0
                    while (isActive) {
                        val running = rpmProvider() > ObdConstants.FAST_POLL_MIN_RPM
                        val batch = PcmPollSchedule.nextBatch(slowIndex, running, mapPoll())
                        slowIndex = batch.nextSlowIndex
                        var sentAny = false
                        batch.queries.forEach { q ->
                            val did = ObdConstants.extractDid(q)
                            // Request capture (#209 Step B): one PCM request at a time, every DID (suppression skips none
                            // while it is on; shouldPoll is not asked, so its re-probe state is left as it was)
                            val capturing = did != null && capture.on.value
                            if (did != null && !capturing && !nrcTracker.shouldPoll(did, clock())) return@forEach
                            // #271: the batch was built while recording; a STOP / PAUSE since then sends no MAP request
                            if (q == ObdConstants.PCM_QUERY_MAP && !mapPoll()) return@forEach
                            // Registered before the write so an instant reply finds it; withdrawn if the write
                            // certainly didn't reach the bus (#216) — a BLE write that failed after it was handed to the
                            // stack may have, so it keeps its request (it lapses) and is never resent (#248)
                            val start = clock()
                            suspend fun writeAndWait() {
                                var req = did?.let { pcmRequests.sent(it, clock()) }
                                // Before the write, so an instant answer finds it (#209 Step B)
                                val wait = if (capturing) CaptureWait(did!!).also { captureWait = it } else null
                                var outcome: WriteOutcome? = null
                                val at = DiagnosticLogger.txStamp()
                                val writtenAt = clock()
                                try { outcome = transport.write(q); noteWrite(q, at, outcome) } catch (e: Exception) {
                                    noteWriteFailure(q, at, e)
                                    if (!WriteFailure.isUncertain(e)) { req?.let { pcmRequests.withdraw(it) }; req = null }
                                }
                                if (did != null && req != null) DiagnosticLogger.countDidSent(ObdConstants.PCM_RESPONSE_ID, did)   // inventory (#253)
                                if (wait != null) {
                                    // Request capture: the next request only after an answer or the set time, after a possibly
                                    // delivered write too; a certainly-not-sent write waits for nothing. The end of a wait with
                                    // no answer is a CAPTURE-TIMEOUT line, so a later answer reads as late.
                                    try {
                                        if (req != null && withTimeoutOrNull(RequestCapture.WAIT_MS) { wait.answered.await() } == null) {
                                            DiagnosticLogger.logCaptureTimeout(q, DiagnosticLogger.txStamp(), RequestCapture.WAIT_MS, wait.pending)
                                            captureTimedOut(did!!, writtenAt)
                                        }
                                    } finally { if (captureWait === wait) captureWait = null }
                                // Wait (bounded) for the reply, so normally one request is in flight and its NRC attributable.
                                // Not after a possibly delivered write (GATT 13, every Pro poll over Bluetooth): its request is
                                // kept and its reply still resolves it, but the gap alone paces the next one, as in rc.7 — the
                                // wait cost every reply's Bluetooth latency and halved the fast group's rate (#333). Replies
                                // slower than the gap then overlap, and the ledger charges their NRCs to nobody.
                                } else if (req != null && outcome != WriteOutcome.POSSIBLY_DELIVERED) {
                                    withTimeoutOrNull(PcmRequestLedger.REPLY_WINDOW_MS) { req.reply.await() }
                                }
                            }
                            if (capturing) captureGate.withLock { awaitCaptureTurn(); writeAndWait() } else writeAndWait()
                            sentAny = true
                            delay((batch.gapMs - (clock() - start)).coerceAtLeast(0L))
                        }
                        // Every DID of the batch suppressed: still suspend, never spin
                        if (!sentAny) delay(batch.gapMs)
                        // Idle in 1 s steps so an engine start switches to fast polling promptly
                        var idled = 0L
                        while (idled < batch.idleAfterMs && isActive &&
                               rpmProvider() <= ObdConstants.FAST_POLL_MIN_RPM) {
                            delay(1_000L)
                            idled += 1_000L
                        }
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
                            try { pollWrite(ObdConstants.EXT_SESSION_BCM);   delay(ObdConstants.EXT_SESSION_GAP_MS)
                                  pollWrite(ObdConstants.BCM_QUERY_ODOMETER); delay(ObdConstants.EXT_QUERY_GAP_MS)
                                  odometerPolled = true } catch (_: Exception) { }
                        }
                        try { pollWrite(ObdConstants.EXT_SESSION_AWD);   delay(ObdConstants.EXT_SESSION_GAP_MS)
                              pollWrite(ObdConstants.AWD_QUERY_RDU_STATUS); delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                        try { pollWrite(ObdConstants.EXT_SESSION_PSCM);  delay(ObdConstants.EXT_SESSION_GAP_MS)
                              pollWrite(ObdConstants.PSCM_QUERY_PDC);    delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                        if (fengMisses.get() < PROBE_TIMEOUT_CYCLES) {
                            try { pollWrite(ObdConstants.EXT_SESSION_FENG);  delay(ObdConstants.EXT_SESSION_GAP_MS)
                                  pollWrite(ObdConstants.FENG_QUERY_STATUS); delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
                            if (fengMisses.incrementAndGet() >= PROBE_TIMEOUT_CYCLES) {
                                onObdUpdate(getCurrentState().copy(fengTimedOut = true))
                            }
                        }
                        if (rsprotMisses.get() < PROBE_TIMEOUT_CYCLES) {
                            ObdConstants.RSPROT_PROBE_QUERIES.forEach { q ->
                                try { pollWrite(ObdConstants.EXT_SESSION_RSPROT); delay(ObdConstants.EXT_SESSION_GAP_MS)
                                      pollWrite(q);                               delay(ObdConstants.EXT_QUERY_GAP_MS) } catch (_: Exception) { }
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

                // ── Firmware capability probe (#220) ──────────────────────
                // OPENRS? went out with the SLCAN init. Up to PROBE_TIMES_MS.size + 1 probes; the stock label shows
                // after the first window as before, but a reply at any time still upgrades the capability (a late
                // reply was ignored and nothing retried, so a slow adapter stayed VIEW ONLY for the session).
                val probeStartMs = clock()
                // Shared by the read loop and the probe schedule (own coroutine). Attempted ≠ written (#220): a probe whose
                // write threw is logged as failed and doesn't count as written.
                val probesSent = java.util.concurrent.atomic.AtomicInteger(1)
                val probesWritten = java.util.concurrent.atomic.AtomicInteger(1)
                val firmwareDetected = java.util.concurrent.atomic.AtomicBoolean(false)
                val stockShown = java.util.concurrent.atomic.AtomicBoolean(false)
                val verdictDone = java.util.concurrent.atomic.AtomicBoolean(false)
                val noise = java.util.concurrent.atomic.AtomicInteger(probeNoise)
                fun fromAttempt() = clock() - attemptStartMs
                fun publish(st: FirmwareStatus) {
                    firmwareVersion = st.label
                    DiagnosticLogger.isOpenRsFirmware = st.openRs
                    DiagnosticLogger.firmwareVersion = st.label
                    firmwareSink(st)
                }
                // Probe events (#220): transport kind (never its label — a Bluetooth label carries name + MAC), probe N
                // of 3, time since the attempt began, written / failed, recognized or not. No adapter text beyond the
                // sanitized version; non-frame lines are only counted. Probe 1 was logged when it was written.
                fun detected(line: String) = synchronized(firmwareDetected) {
                    if (!firmwareDetected.compareAndSet(false, true)) return@synchronized
                    val version = LogText.sanitize(line.removePrefix("OPENRS:").trim(), 40)   // adapter text, in every export (#263)
                    publish(FirmwareStatus(openRs = true, probing = false, label = "openRS_ $version"))
                    val ms = clock() - probeStartMs
                    addDebugLine("Firmware: openRS_ $version \u2713 (${ms} ms, probe ${probesSent.get()})")
                    DiagnosticLogger.event("FIRMWARE", "Reply recognized: openRS_ $version after probe " +
                        "${probesSent.get()} of $probeTotal ($kind, +${fromAttempt()} ms" +
                        "${if (verdictDone.get()) ", late reply after the verdict" else ""})")
                }
                publish(FirmwareStatus(openRs = false, probing = true, label = transport.stockFirmwareLabel))
                if (firstLine != null && firstLine.startsWith("OPENRS:")) detected(firstLine)
                // The retry / give-up schedule runs on its own clock (#220): inside the read loop it only advanced when
                // a line arrived, so an adapter that went quiet after the handshake never got a retry or a verdict
                val probeJob = launch {
                    for (at in PROBE_TIMES_MS) {
                        delay((at - (clock() - probeStartMs)).coerceAtLeast(0L))
                        if (firmwareDetected.get()) return@launch
                        if (stockShown.compareAndSet(false, true)) {
                            addDebugLine("Firmware: no openRS_ reply in ${PROBE_TIMES_MS.first() / 1000} s — probing again")
                        }
                        val n = probesSent.incrementAndGet()
                        try {
                            transport.writeLine("OPENRS?\r")
                            probesWritten.incrementAndGet()
                            DiagnosticLogger.event("FIRMWARE", "Probe $n of $probeTotal written ($kind, +${fromAttempt()} ms)")
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            DiagnosticLogger.event("FIRMWARE", "Probe $n of $probeTotal write FAILED ($kind, +${fromAttempt()} ms): " +
                                LogText.sanitizeReason(e))
                        }
                    }
                    delay((PROBE_GIVE_UP_MS - (clock() - probeStartMs)).coerceAtLeast(0L))
                    synchronized(firmwareDetected) {    // never after a detection that landed meanwhile
                        if (firmwareDetected.get()) return@launch
                        verdictDone.set(true)
                        publish(FirmwareStatus(openRs = false, probing = false, label = transport.stockFirmwareLabel))
                    }
                    val ignored = noise.get().let { if (it > 0) "; $it non-frame lines ignored" else "" }
                    DiagnosticLogger.event("FIRMWARE", "Not detected — no reply recognized to ${probesWritten.get()} written probes " +
                        "(${probesSent.get()} attempted) in ${PROBE_GIVE_UP_MS / 1000} s ($kind)$ignored — capability unknown " +
                        "(no reply is not proof of stock firmware); drive mode / ESC stay view-only; " +
                        "a later reply still enables them")
                }

                // ── Main frame loop ───────────────────────────────────────
                try {
                    while (currentCoroutineContext().isActive) {
                        val line = try { transport.readLine() } catch (e: Exception) {
                            android.util.Log.d("SLCAN", "readLine failed", e); null
                        }?.removePrefix("\n") ?: break     // the firmware ends OPENRS: with CR LF: the LF leads the next line

                        if (line.startsWith("OPENRS:")) {
                            detected(line)
                            continue
                        }
                        // Route firmware command responses to dedicated channel
                        if (line.startsWith("+FRS:")) {
                            // Nobody waiting and full: the newest is dropped; a command drains stale replies before it writes
                            if (commandResponseChannel.trySend(line).isFailure) DiagnosticLogger.countCommandReplyDropped()
                            continue
                        }

                        framing.observe(line)?.let { DiagnosticLogger.event("FRAMING", it) }
                        if (!framingNoted && framing.locked) { framingNoted = true; DiagnosticLogger.framingDecided(framing.timestamped) }
                        val frame = SlcanParser.parse(line, framing.timestamped)
                        if (frame == null) {
                            if (line.startsWith("t") || line.startsWith("T")) DiagnosticLogger.countRefusedFrameLine(framing.isOtherShape(line))
                            framing.refused(line)?.let { DiagnosticLogger.event("FRAMING", it) }
                            // Counted for the probe verdict only — adapter text is never logged (#220)
                            if (line.isNotEmpty() && !firmwareDetected.get() && !verdictDone.get()) noise.incrementAndGet()
                            continue
                        }

                        framing.accepted()
                        gaps.onFrame(monotonicClock()).let { if (it.isNotEmpty()) logGaps(it) }

                        // The prober's own positive reply: response ID + 62 + DID (#250); anything else takes the normal path
                        val probing = _probe
                        if (probing != null && frame.first == probing.responseId &&
                            Mode22.positiveDid(frame.second) == probing.did) {
                            probing.ledger.positive(probing.did)
                            DiagnosticLogger.logObdFrame(frame.first, frame.second)
                            probing.reply.complete(frame.second)
                            continue
                        }

                        if (_dtcScanActive && frame.first in _dtcWatchIds && divertToDtc(frame.first, frame.second)) {
                            DiagnosticLogger.logObdFrame(frame.first, frame.second)   // the reply itself, once (#215)
                            // Full: a lost frame leaves the reply incomplete, so the module ends unread — counted, never OK
                            if (_dtcChannel.trySend(frame).isFailure) DiagnosticLogger.countDtcFrameDropped()
                            continue
                        }

                        if (frame.first != ObdConstants.PCM_RESPONSE_ID) {
                            val ledger = moduleLedgers[frame.first]
                            if (ledger != null && moduleReply(ledger, frame.first, frame.second)) continue
                        }

                        if (frame.first in ObdConstants.OBD_RESPONSE_IDS) {
                            DiagnosticLogger.logObdFrame(frame.first, frame.second)
                            // Inventory (#253): a positive answer (single or first frame) to a poll — the prober's own answers aren't counted
                            Mode22.positiveDid(frame.second)?.let { did ->
                                val probing = _probe
                                if (probing == null || probing.responseId != frame.first || probing.did != did)
                                    DiagnosticLogger.countDidPositive(frame.first, did)
                            }
                        }

                        if (frame.first == ObdConstants.BCM_RESPONSE_ID) {
                            val (reassembled, isFF, isSF) = bcmIsoTp.feed(frame.second)
                            if (isFF) {
                                try { writeLogged(transport, ObdConstants.BCM_FLOW_CONTROL) } catch (_: Exception) {}
                            }
                            // A single frame goes to its parser even when the buffer refused it: the parser counts a truncated reply
                            if (isSF) ObdResponseParser.parseBcmResponse(frame.second, getCurrentState(), onObdUpdate)
                            else if (reassembled != null) ObdResponseParser.parseBcmReassembled(reassembled, getCurrentState(), onObdUpdate)
                            continue
                        }
                        if (frame.first == ObdConstants.AWD_RESPONSE_ID) {
                            ObdResponseParser.parseAwdResponse(frame.second, getCurrentState(), onObdUpdate)
                            continue
                        }
                        if (frame.first == ObdConstants.PCM_RESPONSE_ID) {
                            val d = frame.second
                            // PCI first: a first / consecutive frame's bytes can read 7F 22 xx or 62 DH DL by chance (#253)
                            if (Mode22.wholeSingleFrame(d) && (d[1].toInt() and 0xFF) == 0x7F) {
                                val nrcCode = d[3].toInt() and 0xFF
                                // 0x78 = response pending: the real reply follows (no time bound assumed), still outstanding
                                if ((d[2].toInt() and 0xFF) == 0x22 && nrcCode == NrcTracker.NRC_RESPONSE_PENDING) {
                                    pcmRequests.pending(clock())
                                    captureWait?.pending = true
                                    _probe?.takeIf { it.responseId == ObdConstants.PCM_RESPONSE_ID }?.pending = true
                                } else if ((d[2].toInt() and 0xFF) == 0x22) {
                                    // A final rejection ends a capture's wait even when the ledger can't charge it (#209 Step B);
                                    // inside the late-answer pause, with nothing waiting, it is the timed-out request's (C1)
                                    val waitingNow = captureWait
                                    if (waitingNow == null && clock() < captureLateUntil) {
                                        captureLateDid?.let { captureTimedOut.remove(it) }
                                        DiagnosticLogger.countCaptureLate(inWindow = true)
                                    }
                                    waitingNow?.answered?.complete(Unit)
                                    val waiting = pcmRequests.waiting(clock())
                                    val unresolved = pcmRequests.unresolved(clock())
                                    val did = pcmRequests.negative(clock())
                                    val probing = _probe?.takeIf { it.responseId == ObdConstants.PCM_RESPONSE_ID }
                                    if (probing != null && did == null) probing.ambiguousNrc = nrcCode
                                    if (probing != null && did == probing.did) probing.reply.complete(d)
                                    if (did == null) {
                                        // Same policy; the LINE is rate-limited per (NRC, reason) and totals kept (#216)
                                        DiagnosticLogger.countUnattributedNrc()
                                        val (reason, why) = when {
                                            unresolved -> "unresolved" to ("an earlier request is still unanswered (owner unknown), may own it: " +
                                                UnattributedNrcLog.compact(waiting).ifEmpty { "evicted request" })
                                            waiting.isEmpty() -> "none outstanding" to "no PCM request outstanding"
                                            else -> "ambiguous" to ("ambiguous, waiting: " + UnattributedNrcLog.compact(waiting))
                                        }
                                        nrcLog.record(nrcCode, reason,
                                            "NRC 0x%02X (%s) — %s; not counted".format(nrcCode, nrcLabel(nrcCode), why), clock())
                                            ?.let { DiagnosticLogger.event("NRC_UNATTRIBUTED", it) }
                                    } else {
                                        DiagnosticLogger.countAttributedNrc()
                                        if (probing == null || did != probing.did) DiagnosticLogger.countDidNrc(ObdConstants.PCM_RESPONSE_ID, did)
                                        // No rejection counts toward suppression while request capture is on (#209: the
                                        // owner's "suppression off in the mode"); it resumes from where it was afterwards
                                        if (!capture.on.value && (probing == null || did != probing.did || did in PCM_POLLED_DIDS) &&
                                            nrcTracker.recordNrc(did, nrcCode, clock())) {
                                            DiagnosticLogger.event("NRC_SUPPRESS",
                                                "DID 0x%04X suppressed: NRC 0x%02X (%s), the only request waiting; re-probed after %d s".format(
                                                    did, nrcCode, nrcLabel(nrcCode), NrcTracker.REPROBE_MS / 1000))
                                            DiagnosticLogger.snapshotNrc(nrcTracker)
                                        }
                                    }
                                }
                                continue
                            }
                            // A single frame or the first frame of a multi-frame reply: either way the DID answered (no flow control is sent)
                            Mode22.positiveDid(d)?.let { did ->
                                val waitingNow = captureWait
                                if (waitingNow != null && waitingNow.did == did) { waitingNow.answered.complete(Unit); captureTimedOut.remove(did) }
                                // A timed-out request's answer, late: inside the pause or after it (C1); never another DID's wait
                                else if (captureTimedOut.remove(did)) DiagnosticLogger.countCaptureLate(inWindow = clock() < captureLateUntil)
                                pcmRequests.positive(did)
                                if (nrcTracker.recordPositive(did)) {
                                    DiagnosticLogger.event("NRC_RECOVER", "DID 0x%04X answered — no longer suppressed".format(did))
                                    DiagnosticLogger.snapshotNrc(nrcTracker)
                                }
                            }
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
                    logGaps(gaps.onEnd(monotonicClock()))
                    obdJob.cancel()
                    probeJob.cancel()
                    pcmJob.cancel()
                    extJob.cancel()
                }
            } catch (e: CancellationException) {
                _state.value = AdapterState.Disconnected
                throw e
            } catch (e: Exception) {
                if (!connectionSucceeded) failedAttempts++
                val why = LogText.sanitize(e.message ?: "Connection failed")   // exported as a STATE line (#263)
                _state.value = AdapterState.Error(why)
                addDebugLine("Error: $why")
            } finally {
                _transport = null
                // A link drop ends a capture, never silently; only this attempt's link: a late end of an old one is ignored (#209)
                if (captureToken != null) capture.linkDown(captureToken, RequestCapture.REASON_LINK_ENDED)
                _ledgerFor = null
                attemptTransport = null
                _dtcScanActive = false
                transport.close()
            }

            if (!currentCoroutineContext().isActive) break

            // #316: an established connection ended (the read ended, or the connected attempt failed): hold the moment the
            // app found it dropped. It is reported once the normal attempts have failed, or handed to a closure first.
            if (connectionSucceeded) synchronized(dropLock) { unreportedDropAtMs = clock() }
            if (failedAttempts >= maxRetries) takeUnreportedDrop()?.let(onLinkLost)

            val retryingRecording = failedAttempts >= maxRetries && keepRetrying()
            if (failedAttempts >= maxRetries && !retryingRecording) {
                _state.value = AdapterState.Idle
                break
            }

            // Backoff: still "connecting" until this job has been connected once, then "reconnecting"
            _state.value = if (everConnected) AdapterState.Reconnecting else AdapterState.Connecting
            val delayMs = if (retryingRecording) {
                30_000L
            } else if (connectionSucceeded) {
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
            if (retryingRecording) {
                var waited = 0L
                while (waited < delayMs && keepRetrying()) { delay(1_000L); waited += 1_000L }
                if (!keepRetrying()) {                              // recording ended mid-backoff: budget spent
                    _state.value = AdapterState.Idle
                    break
                }
            } else {
                delay(delayMs)
            }
        }
    }.flowOn(ioDispatcher)

    // ── DTC scan ──────────────────────────────────────────────────────────────

    /**
     * Sends UDS 0x19 02 (all status bits) to [modules] and collects each module's final reply. Progress reports a module
     * as OK only for a valid positive reply ([DtcReply.classify]); a negative / malformed reply reports its own status.
     * "Response pending" (7F 19 78) is not final: that module's buffer restarts and the deadline extends (P2*, capped)
     * while its real answer is awaited — if it never comes the module ends PENDING, never OK (#215).
     */
    suspend fun performDtcScan(
        modules: List<DtcModuleSpec>,
        onModuleProgress: ((String, ModuleScanStatus?) -> Unit)? = null
    ): DtcRawScan =
        dtcOperation {
            val transport = _transport ?: return DtcRawScan()
            val results = mutableMapOf<String, ByteArray>()
            val pending = mutableSetOf<String>()
            val failures = LinkedHashMap<String, DtcSendFailure>()   // first failed write per module (#215)
            // A scan write, logged as TX in the private SLCAN log; a failure is kept with its stage, never swallowed
            suspend fun send(module: String, stage: DtcStage, frame: String): Boolean {
                val at = DiagnosticLogger.txStamp()
                return try {
                    val outcome = transport.write(frame)                          // write, not writeLine: keeps the outcome (#209)
                    DiagnosticLogger.logTxLine(frame, at, outcomeKind(outcome))
                    true
                } catch (e: Exception) {
                    DiagnosticLogger.logTxLine(frame, at, failureKind(e))         // #209: a failed write is a line too, never "sent"
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val f = DtcSendFailure(stage, LogText.sanitizeReason(e))
                    // Request > flow control > extended session (DtcRawScan.keeps) — the failure that explains the module
                    if (DtcRawScan.keeps(failures[module], f)) failures[module] = f
                    false
                }
            }

            beginDtcOperation(modules.map { it.responseId }.toSet(), sid = DtcReply.SID)
            while (_dtcChannel.tryReceive().isSuccess) { /* drain stale */ }

            try {
                // Phase 1: establish extended diagnostic session for modules that need it
                val extModules = modules.filter { it.needsExtSession && it.extSessionFrame != null }
                for (m in extModules) {
                    send(m.name, DtcStage.EXTENDED_SESSION, m.extSessionFrame!!)
                    delay(ObdConstants.EXT_SESSION_GAP_MS)
                }
                if (extModules.isNotEmpty()) delay(200L)

                // Phase 2: burst-send all DTC scan requests (parallel approach)
                val buffers = mutableMapOf<Int, IsoTpBuffer>()
                val fcSent = mutableSetOf<Int>()
                val moduleByResp = modules.associateBy { it.responseId }

                // Mark all as querying
                modules.forEach { m ->
                    onModuleProgress?.invoke(m.name, null)
                    buffers[m.responseId] = IsoTpBuffer { DiagnosticLogger.countIsoTpRefused() }
                }

                // Send all requests with 50ms between each
                for (module in modules) {
                    val reqFrame = "t%03X8031902FF00000000\r".format(module.requestId)
                    if (!send(module.name, DtcStage.REQUEST, reqFrame)) onModuleProgress?.invoke(module.name, ModuleScanStatus.ERROR)
                    delay(50L)
                }

                // Phase 3: single receiver loop with shared deadline (extended while a module reports "pending")
                val scanStart = System.currentTimeMillis()
                var deadline = scanStart + 3_500L

                while (results.size < modules.size && System.currentTimeMillis() < deadline) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val frame = withTimeoutOrNull(remaining) { _dtcChannel.receive() } ?: break

                    val buf = buffers[frame.first] ?: continue
                    val (payload, isFF, isSF) = buf.feed(frame.second)

                    // Send flow control if we got a First Frame
                    if (isFF && frame.first !in fcSent) {
                        val mod = moduleByResp[frame.first] ?: continue
                        val fcFrame = "t%03X83000000000000000\r".format(mod.requestId)
                        // Marked sent only when it was: a failed flow control means the rest never comes (#215)
                        if (send(mod.name, DtcStage.FLOW_CONTROL, fcFrame)) fcSent += frame.first
                        else if (mod.name !in results) onModuleProgress?.invoke(mod.name, ModuleScanStatus.ERROR)
                    }

                    // Check for completed payload
                    if (payload != null || isSF) {
                        val completedPayload = payload ?: continue
                        val mod = moduleByResp[frame.first] ?: continue
                        if (mod.name in results) continue                  // already final
                        val reply = DtcReply.classify(completedPayload)
                        if (reply == DtcReply.Pending) {
                            // Real answer follows: fresh buffer, wait P2* more (never past the cap)
                            pending += mod.name
                            buffers[frame.first] = IsoTpBuffer { DiagnosticLogger.countIsoTpRefused() }
                            fcSent -= frame.first
                            deadline = minOf(maxOf(deadline, System.currentTimeMillis() + DTC_P2_STAR_MS), scanStart + DTC_SCAN_CAP_MS)
                            continue
                        }
                        pending -= mod.name
                        results[mod.name] = completedPayload
                        onModuleProgress?.invoke(mod.name, reply.status)
                    }
                }

                buffers.values.forEach { it.abandon() }               // a reply still open at the deadline: counted (#253 L1)
                // Modules without a final reply: the same rule as the final result (#215) — progress, screen and export agree
                val raw = DtcRawScan(results, pending - results.keys, failures.filterKeys { it !in results })
                for (module in modules) {
                    if (module.name !in results) onModuleProgress?.invoke(module.name, raw.statusWithoutReply(module.name))
                }
            } finally {
                endDtcOperation()
            }
            DtcRawScan(results, pending - results.keys, failures)
        }

    suspend fun performDtcClear(modules: List<DtcModuleSpec>): Map<String, Boolean> =
        dtcOperation {
            val transport = _transport ?: return emptyMap()
            val results = mutableMapOf<String, Boolean>()

            beginDtcOperation(modules.map { it.responseId }.toSet(), sid = 0x14)
            while (_dtcChannel.tryReceive().isSuccess) { /* drain stale */ }

            try {
                for (module in modules) {
                    val reqFrame = "t%03X80414FFFFFF000000\r".format(module.requestId)
                    try { writeLogged(transport, reqFrame) } catch (_: Exception) { continue }
                    val payload = assembleIsotpResponse(transport, module.responseId, module.requestId, 2_000L)
                    results[module.name] = payload != null &&
                        payload.isNotEmpty() &&
                        (payload[0].toInt() and 0xFF) == 0x54
                    delay(300L)
                }
            } finally {
                endDtcOperation()
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
        // Same rule as IsoTpBuffer: a reply is whole or nothing (#253). Kept separate because it writes flow control itself

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val frame = withTimeoutOrNull(remaining) { _dtcChannel.receive() } ?: break
            if (frame.first != responseId) continue
            val data = frame.second; if (data.isEmpty()) continue
            when ((data[0].toInt() and 0xF0) shr 4) {
                0 -> {
                    if (totalLen > 0) DiagnosticLogger.countIsoTpRefused()   // a partial reply superseded by a single frame (#253 L1)
                    val len = data[0].toInt() and 0x0F
                    if (len <= 0 || data.size < len + 1) { DiagnosticLogger.countIsoTpRefused(); return null }
                    return data.copyOfRange(1, 1 + len)
                }
                1 -> {
                    if (totalLen > 0) DiagnosticLogger.countIsoTpRefused()   // a partial reply superseded by a first frame (#253 L1)
                    buf.reset(); received = 0; totalLen = 0; seqNext = 1     // a new reply started: nothing of the old one stays
                    if (data.size < IsoTpBuffer.CAN_FRAME_BYTES) { DiagnosticLogger.countIsoTpRefused(); continue }
                    val length = ((data[0].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
                    if (length < IsoTpBuffer.MIN_FF_LENGTH) { DiagnosticLogger.countIsoTpRefused(); continue }
                    totalLen = length
                    buf.write(data, 2, IsoTpBuffer.FF_DATA_BYTES); received = IsoTpBuffer.FF_DATA_BYTES
                    val fcFrame = "t%03X83000000000000000\r".format(requestId)
                    try { writeLogged(transport, fcFrame) } catch (_: Exception) { }
                }
                2 -> {
                    val seq = data[0].toInt() and 0x0F; if (seq != seqNext) { DiagnosticLogger.countIsoTpRefused(); return null }
                    seqNext = (seqNext + 1) and 0x0F
                    val take = minOf(IsoTpBuffer.CF_DATA_BYTES, totalLen - received)
                    if (take <= 0 || data.size - 1 < take) { DiagnosticLogger.countIsoTpRefused(); return null }
                    buf.write(data, 1, take); received += take
                    if (received >= totalLen) return buf.toByteArray()
                }
            }
        }
        if (totalLen > 0) DiagnosticLogger.countIsoTpRefused()       // a reply still open at the deadline: abandoned (#253 L1)
        return null                                                  // the deadline passed: partial bytes aren't a reply
    }

    // ── Freeze frame reads ─────────────────────────────────────────────────

    /**
     * Read freeze frame snapshot identification (UDS 0x19/04) from a module.
     * Returns raw payload or null on timeout.
     */
    suspend fun performFreezeFrameIdentification(
        module: DtcModuleSpec
    ): ByteArray? = dtcOperation {
        val transport = _transport ?: return null
        beginDtcOperation(setOf(module.responseId), sid = DtcReply.SID)
        while (_dtcChannel.tryReceive().isSuccess) { /* drain */ }
        try {
            // UDS 0x19/04: reportDTCSnapshotIdentification
            val req = "t%03X8031904FF00000000\r".format(module.requestId)
            try { writeLogged(transport, req) } catch (_: Exception) { return null }
            assembleIsotpResponse(transport, module.responseId, module.requestId, 3_000L)
        } finally {
            endDtcOperation()
        }
    }

    /**
     * Read a specific freeze frame record (UDS 0x19/05) for a given DTC.
     * [dtcBytes] = 3-byte DTC (high, mid, status), [recordNumber] = snapshot record.
     */
    suspend fun performFreezeFrameRead(
        module: DtcModuleSpec,
        dtcBytes: ByteArray,
        recordNumber: Int
    ): ByteArray? = dtcOperation {
        val transport = _transport ?: return null
        beginDtcOperation(setOf(module.responseId), sid = DtcReply.SID)
        while (_dtcChannel.tryReceive().isSuccess) { /* drain */ }
        try {
            // UDS 0x19/05: reportDTCSnapshotRecordByDTCNumber
            // Frame: [SID=19, SF=05, DTC_high, DTC_mid, DTC_low, RecordNum]
            val req = "t%03X8061905%02X%02X%02X%02X00\r".format(
                module.requestId,
                dtcBytes[0].toInt() and 0xFF,
                dtcBytes[1].toInt() and 0xFF,
                dtcBytes[2].toInt() and 0xFF,
                recordNumber
            )
            try { writeLogged(transport, req) } catch (_: Exception) { return null }
            assembleIsotpResponse(transport, module.responseId, module.requestId, 3_000L)
        } finally {
            endDtcOperation()
        }
    }

    private companion object {
        /** UDS P2*: how long a module may take after "response pending" (7F xx 78). */
        const val DTC_P2_STAR_MS = 5_000L
        /** A scan never waits longer than this in total, however often modules report pending. */
        const val DTC_SCAN_CAP_MS = 12_000L
        /** DIDs the PCM poller asks for: a probe NRC charged to any other DID never reaches the NRC tracker (#250). */
        val PCM_POLLED_DIDS: Set<Int> = ObdConstants.PCM_QUERIES.mapNotNull { ObdConstants.extractDid(it) }.toSet()
    }

    // ── NRC helpers ─────────────────────────────────────────────────────────

    // Firmware probe timing (#220): re-send OPENRS? at these offsets after the first, give up after PROBE_GIVE_UP_MS
    private val PROBE_TIMES_MS = listOf(3_000L, 8_000L)
    private val PROBE_GIVE_UP_MS = 13_000L

    private fun nrcLabel(code: Int): String = when (code) {
        0x31 -> "requestOutOfRange"
        0x22 -> "conditionsNotCorrect"
        0x33 -> "securityAccessDenied"
        else -> "0x%02X".format(code)
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

/** Stands in for a transport the factory couldn't build (#234): its open() fails the attempt with the sanitized reason. */
private class UnbuiltTransport(private val reason: String) : SlcanTransport {
    override suspend fun open(): Unit = throw java.io.IOException("transport not created — $reason")
    override suspend fun readLine(): String? = null
    override suspend fun writeLine(frame: String) = Unit
    override fun close() = Unit
    override val label = "transport not created"
    override val stockFirmwareLabel = ""
}
