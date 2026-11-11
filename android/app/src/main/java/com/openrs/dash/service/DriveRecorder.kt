package com.openrs.dash.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.openrs.dash.data.DriveBookmarkEntity
import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.DriveThermalSensor
import com.openrs.dash.data.DriveThermalTracker
import com.openrs.dash.data.FreshnessGate
import com.openrs.dash.data.FuelTrimAccumulator
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.KnockCapture
import com.openrs.dash.data.PullTracker
import com.openrs.dash.data.RecordingGapMonitor
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.data.FixVerdict
import com.openrs.dash.data.LapEntity
import com.openrs.dash.data.LapFix
import com.openrs.dash.data.LapSessionEntity
import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.LapTimer
import com.openrs.dash.data.PauseReason
import com.openrs.dash.data.Refusal
import com.openrs.dash.data.PeakEvent
import com.openrs.dash.data.PeakType
import com.openrs.dash.data.ThermalPredictor
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.AppSettings
import com.openrs.dash.ui.ThermalReadinessSession
import com.openrs.dash.ui.UserPrefsStore
import com.openrs.dash.ui.sample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min

/**
 * Records GPS waypoints fused with live [VehicleState] telemetry at ~1 Hz.
 *
 * Replaces [TripRecorder] with Room-backed persistence, start/stop/pause/resume,
 * and connection-aware auto-record support.
 *
 * Architecture:
 *  - Owned by [CanDataService]; shares the app's [vehicleStateFlow].
 *  - Has its own [CoroutineScope] for recording independence.
 *  - [locationFlow] uses FusedLocationProviderClient at 1-second intervals.
 *  - On each GPS fix, snapshots vehicleStateFlow.value into a [DrivePointEntity].
 *  - Points are buffered and flushed to Room every ~30 seconds.
 *  - Pause keeps GPS alive (for live position dot) but stops recording points.
 */
class DriveRecorder(
    private val context: Context,
    private val vehicleStateFlow: StateFlow<VehicleState>,
    private val weatherRepo: WeatherRepository,
    private val db: DriveDatabase,
    // Seams for DriveFinalizeTest (#236); production uses the defaults.
    private val dao: DriveDao = db.driveDao(),
    private val geocode: (lat: Double, lng: Double) -> String? = platformGeocoder(context),
    locationSource: LocationSource? = null,
    onSummary: ((DriveEntity) -> Unit)? = null,
    /** Wall clock for every time the recorder takes (#316, seam 2); production uses the system clock. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** GPS for the recording loop + the weather lookup (fused provider in production). */
    interface LocationSource {
        fun updates(): Flow<Location>
        suspend fun last(): Location?
    }

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val locations: LocationSource = locationSource ?: object : LocationSource {
        override fun updates() = locationFlow()
        override suspend fun last() = fusedLastLocation()
    }

    private val _driveState = MutableStateFlow(DriveState())
    val driveState: StateFlow<DriveState> = _driveState.asStateFlow()

    /**
     * Summary of the last finalized drive until TRIP shows it. Was a no-replay SharedFlow collected by
     * TripPage — lost when the drive ended before TRIP had been composed.
     */
    val pendingSummary = com.openrs.dash.data.PendingSummary()
    private val postSummary: (DriveEntity) -> Unit = onSummary ?: pendingSummary::post

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /**
     * The recording drive's job: insert, state, loop. Assigned in [startDrive] on the caller's thread before the job
     * runs anything, so a STOP or a second START always sees it (it used to be assigned on an IO thread after the
     * loop was already running: a STOP in that window neither cancelled nor joined the loop).
     */
    @Volatile private var recorderJob: Job? = null

    /**
     * Everything one drive accumulates (#236). Each drive gets its own at [startDrive]; its recording loop and its
     * STOP finalization hold that instance, so a drive started while the previous one is still being finalized
     * (auto-record's finalize + start, START right after STOP) can't hand its buffer, thermal record, start temps or
     * points to the old one. These used to be recorder fields that [startDrive] reassigned under the old drive.
     */
    private class DriveRun(vs: VehicleState, startMs: Long) {
        /** Buffer for batch writes — flushed to Room every FLUSH_SIZE points. Shared by the loop (add, batch / final
         *  drain) and the drains outside it (pause, export): every access goes through [addPoint] / [bufferedCount] /
         *  [takePoints], one monitor, so a point added during a take is never cleared unstored and two takes never
         *  hold the same points (#238 review). Nothing suspends inside it; inserts run after the take. */
        val pointsBuffer = ArrayList<DrivePointEntity>(FLUSH_SIZE * 2)
        fun addPoint(p: DrivePointEntity) = synchronized(pointsBuffer) { pointsBuffer.add(p) }
        fun bufferedCount() = synchronized(pointsBuffer) { pointsBuffer.size }
        fun takePoints(): List<DrivePointEntity> =
            synchronized(pointsBuffer) { ArrayList(pointsBuffer).also { pointsBuffer.clear() } }
        /** Recent points kept in memory for live polyline rendering + the aggression score. */
        val recentPoints = ArrayList<DrivePointEntity>(MAX_RECENT_POINTS)
        /** Start-of-drive temps captured from VehicleState at startDrive(). */
        val startOilTempC = vs.oilTempC
        val startCoolantTempC = vs.coolantTempC
        /** Previous point for distance calculation. */
        @Volatile var prevPoint: DrivePointEntity? = null
        /** Polled DIDs are recorded only once per new decode, and never from a decode that predates the drive
         *  (rc.5, #184 / #187). */
        val freshGate = FreshnessGate(notBeforeMs = startMs)
        /** STFT/LTFT averages from fresh, warm-engine polls (rc.5, roadmap 2.2). */
        val fuelTrims = FuelTrimAccumulator()
        /** Pull detection for "% of pulls with KR > 0" (rc.6, #174). */
        val pulls = PullTracker()
        /** End-of-drive climb rates only. */
        val thermalPredictor = ThermalPredictor()
        /** Thermal peaks with the profile limits in effect at each sample (DriveThermalRecord). */
        val thermal = DriveThermalTracker()
        /** What the lap timer did with this drive's fixes (logged at STOP; loop thread only, read after join). */
        val lapVerdicts = java.util.EnumMap<FixVerdict, Int>(FixVerdict::class.java)
        /** Held by point writes from outside this drive's loop (pause flush, active export) while they take from
         *  [pointsBuffer] and insert. STOP's finalize takes it after the loop has ended, so the thumbnail reads every
         *  point those writes took (#241). Never another drive's — each run has its own. */
        val outsideLoopWrites = Mutex()
        /** Point inserts that failed for this drive (their points are not in Room); reported at STOP. */
        val failedPointWrites = java.util.concurrent.atomic.AtomicInteger()
        /** When the drive last resumed from a pause (N1, QA Q1): the loop raises its gate's floor to it at the next fix, so a
         *  decode made during a pause that saw no fix is not stored either. Written by [resumeDrive], read by the loop. */
        @Volatile var resumedAtMs = 0L
        /** Point inserts of this drive under way (#262 N2): counted from before the points leave the buffer, so a
         *  [durabilityBarrier] can't miss a batch the loop has taken but Room hasn't stored yet. */
        val inFlight = MutableStateFlow(0)
        /** This drive's Room id, set once its row is inserted (0 until then; STOP may come before). */
        @Volatile var driveId = 0L
        /** When the connection loss that began the current link pause happened (#316); 0 when not link-paused. */
        @Volatile var linkLostAtMs = 0L
    }

    /** The drive being recorded (or the last one); see [DriveRun]. */
    @Volatile private var run = DriveRun(VehicleState(), 0L)

    /**
     * Set by TripPage when a LapTimer is active during recording.
     * DriveRecorder flushes laps to Room on [stopDrive].
     */
    @Volatile var lapTimer: LapTimer? = null

    /**
     * The lap timer TRIP shows and STOP takes laps from: one per recorder (app-scoped), created on first use. TRIP takes
     * this one on every composition instead of installing its own — a recreated activity (rotation, "Don't keep
     * activities") used to compose a new, unarmed timer and put it here mid-drive, so the line vanished and STOP saved
     * none of the drive's laps (#238 review). The line stays across drives as before (`takeLaps` re-arms it).
     */
    @Synchronized fun lapTimerForUi(): LapTimer = lapTimer ?: LapTimer().also { lapTimer = it }

    /**
     * TRIP's SET S/F: puts the start/finish line at the drive's position, with its bearing when the fix has one (no
     * bearing ≠ 0° north: the line then takes the direction the car leaves it in, LapTimer). Only with [freshFix]
     * (#311): before the drive's first fix or in a GPS gap it returns false and sets nothing (refused, no message).
     */
    fun setStartFinishHere(): Boolean {
        val loc = freshFix() ?: return false
        lapTimerForUi().setStartFinish(loc.latitude, loc.longitude, loc.bearing.takeIf { loc.hasBearing() })
        return true
    }

    /**
     * Timestamp of the most recent [pauseDrive] call, or 0 when not paused.
     * Used by the auto-record pipeline to decide whether a resume is valid
     * (recent pause) or whether to finalize and start fresh.
     */
    @Volatile private var pausedAtMs: Long = 0L
    @Volatile private var finalizeJob: Job? = null

    /** STOP's writes are still running: the process must not lose its foreground service now (#212). */
    val isFinalizing: Boolean get() = finalizeJob?.isActive == true

    init {
        // Finalize any drives left with endTime=0 from a previous app session
        // (app killed mid-drive, crash, etc.). In-memory pause state is lost
        // at restart, so these cannot be resumed — close them out cleanly so
        // "endTime == 0" keeps meaning "truly in progress." Such a drive never got STOP's summary: it gets the parts
        // that follow from its stored points (#262, [closedOrphan]); the rest stays at the row's defaults.
        // Only drives started before this recorder existed are orphans: a drive this recorder starts can be inserted before
        // the query below runs, and used to be closed here at its start with no points (#262, found by its regression).
        val createdAtMs = clock()
        scope.launch(Dispatchers.IO) {
            try {
                val orphans = dao.getUnfinishedDrives().filter { it.startTime < createdAtMs }
                for (orphan in orphans) {
                    val points = dao.getPoints(orphan.id)
                    val closed = closedOrphan(orphan, points)
                    dao.updateDrive(closed)
                    Log.d(TAG, "Finalized orphan drive ${orphan.id} (endTime=${closed.endTime}, ${points.size} points)")
                    DiagnosticLogger.event("REC", "drive ${orphan.id} closed after the app ended mid-drive: " +
                        "${points.size} stored point(s), summary from those points")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Orphan drive cleanup failed", e)
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * START (#316): refused in demo mode, while a drive is open (recording or paused) and while a start is under way;
     * allowed while the previous drive is still being saved. Otherwise the phase is STARTING at once (TRIP reads
     * STARTING), the drive's row is written, and the drive becomes RECORDING. If [sessionId] is provided, links this
     * drive to a diagnostic session.
     *
     * Lock order: callers may hold the service's link lock when they call a command; nothing in the recorder calls back
     * into the service while holding the recorder's lock.
     */
    @Synchronized fun startDrive(sessionId: Long = 0): CommandResult {
        // Simulated telemetry never enters trip history.
        if (com.openrs.dash.demo.DemoMode.isActive) return refused("START", Refusal.DEMO)
        val phase = _driveState.value.phase
        if (phase == DrivePhase.RECORDING || phase == DrivePhase.PAUSED) return refused("START", Refusal.DRIVE_OPEN)
        if (phase == DrivePhase.STARTING || recorderJob?.isActive == true) return refused("START", Refusal.STARTING)

        val vs = vehicleStateFlow.value
        // A new instance — a drive still being saved keeps its own (#236)
        val newRun = DriveRun(vs, clock())
        run = newRun
        _driveState.value = DriveState(phase = DrivePhase.STARTING)
        pendingLossAtMs = 0L

        // One job per drive, assigned before it runs anything: a STOP or a second START during the insert sees it.
        val now = clock()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            pruneOldDrives()
            ensureActive()                                    // STOP before the insert: no row at all
            val driveId = try {
                insertDriveRow(newRun, now, sessionId, vs)
            } catch (e: CancellationException) {
                throw e                                       // a STOP, not a failed start (the rethrow rule)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start drive", e)
                _driveState.update { if (it.phase == DrivePhase.STARTING) it.copy(phase = DrivePhase.IDLE) else it }
                return@launch
            }
            ensureActive()                                    // STOP during the insert: cancelStart removes the row
            try {
                // Every accumulator starts empty — a new DriveState, not a copy, so none is carried over from the last
                // drive (peak oil / coolant were, #236). The position too (#311): a drive has one only once its own
                // collector receives a fix; until then nothing is placed and TRIP draws no dot.
                _driveState.update {
                    DriveState(
                        phase = DrivePhase.RECORDING,
                        driveId = driveId,
                        startFuelPct = vs.fuelLevelPct,
                        startTime = now
                    )
                }
                applyLossDuringStart(driveId)
                runRecordingLoop(driveId, newRun)
            } finally {
                // Only this drive's state: a drive started since (finalize + start) is recording (#236). Here, not in
                // the loop, so a STOP between the state and the loop clears it too.
                _driveState.update {
                    if (it.driveId == driveId) it.copy(phase = DrivePhase.IDLE, pauseReason = null, currentLocation = null) else it
                }
            }
        }
        recorderJob = job
        job.start()
        return CommandResult.Done
    }

    /** A refused command: dropped, with one REC line in the diagnostic log (the reason; TRIP shows START's). */
    private fun refused(command: String, reason: Refusal): CommandResult {
        Log.i(TAG, "$command refused: $reason")
        DiagnosticLogger.event("REC", "$command refused: ${reason.name.lowercase().replace('_', ' ')}")
        return CommandResult.Refused(reason)
    }

    /** Deletes the oldest drives when the saved-drives limit is reached. A failure is logged; a STOP is rethrown. */
    private fun pruneOldDrives() {
        try {
            val maxDrives = AppSettings.getMaxSavedDrives(context)
            val count = dao.getDriveCount()
            if (count >= maxDrives) {
                dao.deleteOldestDrives(count - maxDrives + 1)
                dao.deleteOrphanLapSessions()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Drive prune failed", e)
        }
    }

    /**
     * Writes the drive's row under NonCancellable, so a STOP during the insert still leaves [run] knowing the row's id
     * (STOP then removes it, see [cancelStart]).
     */
    private suspend fun insertDriveRow(run: DriveRun, startTime: Long, sessionId: Long, vs: VehicleState): Long =
        withContext(NonCancellable) {
            dao.insertDrive(
                DriveEntity(
                    startTime = startTime,
                    sessionId = sessionId,
                    startFuelPct = vs.fuelLevelPct,
                    hasGps = hasLocationPermission(),
                    boostPolicy = com.openrs.dash.data.BoostRule.POLICY,
                    oilPtuMapping = com.openrs.dash.data.OilPtuMapping.CURRENT
                )
            ).also { run.driveId = it }
        }

    /** PAUSE (#316): refused unless a drive is recording (also during a start and when already paused). */
    @Synchronized fun pauseDrive(): CommandResult {
        if (_driveState.value.phase != DrivePhase.RECORDING) return refused("PAUSE", Refusal.NOT_RECORDING)
        pauseLocked(PauseReason.HAND, clock())
        return CommandResult.Done
    }

    /** Pauses the recording drive for [reason]; the buffered points are stored now. Call holding the lock. */
    private fun pauseLocked(reason: PauseReason, atMs: Long) {
        pausedAtMs = atMs
        if (reason == PauseReason.LINK) run.linkLostAtMs = atMs
        _driveState.update {
            if (it.phase == DrivePhase.RECORDING) it.copy(phase = DrivePhase.PAUSED, pauseReason = reason) else it
        }
        // Flush any buffered points immediately. flushBuffer() is suspend +
        // NonCancellable so it completes even if the scope is cancelled while
        // the pause is being handled.
        val r = run
        scope.launch { r.outsideLoopWrites.withLock { flushBuffer(r) } }
    }

    /**
     * The point at which every point the drive recorded is in Room (#262, N2): stores the buffered points now, without pausing
     * the drive (same lock as [pauseDrive]'s flush, #241), then waits for every point insert under way — the loop's 30-point
     * batch too, which takes its points from the buffer before Room stores them — and for a STOP's finalize. The service keeps
     * its foreground until this completes: with auto-record off a drive keeps recording through a link gap, and without the
     * foreground nothing keeps the process alive to finish the writes. Null when there is nothing to wait for. Never blocks the
     * caller; a failed insert still completes it (the failure is counted, as before).
     */
    fun durabilityBarrier(): Job? {
        val r = run
        val recording = _driveState.value.isRecording
        val finalizing = finalizeJob?.takeIf { it.isActive }
        if (!recording && r.inFlight.value == 0 && finalizing == null) return null
        return scope.launch {
            if (recording) r.outsideLoopWrites.withLock { flushBuffer(r) }
            r.inFlight.first { it == 0 }
            finalizing?.join()
        }
    }

    // ── Events (#316): the service reports these and applies no rule itself ─────────────────────────────────────

    /** Auto-record, as last reported by [onAutoRecordSwitched] (the service reports it when it binds the recorder). */
    @Volatile private var autoRecord = false

    /** Whether the connection is up, as last reported (lost / back). */
    @Volatile private var linkUp = false
    /** A loss reported while a start was under way (#316): acted on the moment the start completes. 0 = none. */
    private var pendingLossAtMs = 0L

    /**
     * The connection to the adapter was lost at [atMs] (closed by the app, or dropped and not recovered). With auto-record
     * on, a recording drive is link-paused from [atMs]; with it off it keeps recording (a recording gap). A paused drive
     * is unchanged: a hand pause holds, a link pause keeps the moment of the loss that began it. During a start the loss
     * is held and applied when the start completes.
     */
    @Synchronized fun onConnectionLost(atMs: Long) {
        linkUp = false
        when (_driveState.value.phase) {
            DrivePhase.STARTING -> if (pendingLossAtMs == 0L) pendingLossAtMs = atMs
            DrivePhase.RECORDING -> if (autoRecord) pauseLocked(PauseReason.LINK, atMs)
            else -> Unit
        }
    }

    /** A connection to the adapter is established. A link pause waits for the engine as well. */
    @Synchronized fun onConnectionBack() { linkUp = true }

    /** The connection sees the engine running (RPM above the auto-record start threshold): a link pause ends. */
    @Synchronized fun onEngineRunning() {
        val s = _driveState.value
        if (linkUp && s.phase == DrivePhase.PAUSED && s.pauseReason == PauseReason.LINK) resumeLocked()
    }

    /** The start of [driveId] completed: a loss reported during it is applied now (auto-record on → link-paused). */
    @Synchronized private fun applyLossDuringStart(driveId: Long) {
        val at = pendingLossAtMs
        pendingLossAtMs = 0L
        val s = _driveState.value
        if (at > 0L && autoRecord && s.driveId == driveId && s.phase == DrivePhase.RECORDING) pauseLocked(PauseReason.LINK, at)
    }

    /** Auto-record was switched on or off. */
    @Synchronized fun onAutoRecordSwitched(on: Boolean) { autoRecord = on }

    /** Demo mode was entered. */
    @Synchronized fun onDemoModeEntered() {}

    /** RESUME (#316): refused unless a drive is paused. */
    @Synchronized fun resumeDrive(): CommandResult {
        if (_driveState.value.phase != DrivePhase.PAUSED) return refused("RESUME", Refusal.NOT_PAUSED)
        resumeLocked()
        return CommandResult.Done
    }

    /** Ends the pause, whatever its reason. Call holding the lock. */
    private fun resumeLocked() {
        pausedAtMs = 0L
        run.linkLostAtMs = 0L
        run.resumedAtMs = clock()   // N1 / Q1: the loop's next fix takes nothing decoded before this
        run.prevPoint = null  // Gap in polyline — next point won't connect to prev
        _driveState.update { if (it.phase == DrivePhase.PAUSED) it.copy(phase = DrivePhase.RECORDING, pauseReason = null) else it }
    }

    /**
     * Milliseconds elapsed since the drive was paused, or 0 when not paused.
     * Used by auto-record to decide whether a paused drive is recent enough
     * to resume or has gone stale and should be finalized.
     */
    fun pausedDurationMs(): Long =
        if (pausedAtMs > 0L) clock() - pausedAtMs else 0L

    /**
     * Ends the drive and finalizes it on IO; the returned job completes when the row, laps and summary are written
     * (tests join it). Everything the drive saves is taken from this call (#236): its [DriveRun], STOP time, end
     * temps, fuel economy and laps — a drive started meanwhile has its own. The row is re-read and written in one
     * transaction, recorder-owned columns only: a rename / tag made during STOP stays, and a drive deleted during
     * STOP gets no laps, thumbnail or summary.
     */
    @Synchronized fun stopDrive(): Job {
        val stoppedAt = clock()
        val jobToJoin = recorderJob
        val state = _driveState.value
        // Not recording and no start under way: nothing to stop (a second STOP must not rewrite the drive it ended)
        if (jobToJoin == null && !state.isRecording) return finalizeJob ?: Job().apply { complete() }
        pausedAtMs = 0L
        recorderJob?.cancel()
        recorderJob = null
        // A STOP while the drive is still being created (its state not yet published): the start is cancelled and
        // nothing is finalized — the previous drive, whose id the state still holds, is never touched
        if (!state.isRecording) {
            _driveState.update { if (it.phase == DrivePhase.STARTING) it.copy(phase = DrivePhase.IDLE) else it }
            return cancelStart(jobToJoin!!, run)
        }

        val endVs = vehicleStateFlow.value
        val stopped = run
        val fuelState = FuelEconomy.state.value
        val laps = lapTimer?.takeLaps(state.driveId)
        val finalize = scope.launch(start = CoroutineStart.LAZY, context = Dispatchers.IO) {
            // Wait for the recording loop's finally{} to flush buffered points
            // before we write the drive summary. This avoids racing our own
            // flush against the loop's flush (both would copy pointsBuffer
            // before either cleared it, duplicating points) and guarantees
            // the summary reflects every recorded point.
            jobToJoin?.join()
            // …and for a pause flush or an active export still writing points it took from this drive's buffer (#241).
            // Only this drive's writers; each holds the lock for one insert, completed, failed or cancelled.
            stopped.outsideLoopWrites.withLock { }

            if (state.driveId > 0) {
                try {
                    val id = state.driveId
                    val drive = dao.getDrive(id) ?: return@launch
                    val modeJson = JSONObject().apply {
                        state.modeCounts.forEach { (mode, count) ->
                            val total = state.modeCounts.values.sum()
                            if (total > 0) put(mode.label, count.toDouble() / total)
                        }
                    }.toString()
                    // Compute aggression score from session averages
                    val score = computeAggressionScore(state.totalPointCount, stopped.recentPoints)

                    // Auto-name via reverse geocoder if no user name set — outside the transaction (network)
                    val autoName = if (drive.name == null) reverseGeocode(state) else null

                    // Snapshot thermal peaks + climb rates
                    val thermalJson = buildThermalPeaksJson(stopped)

                    // Fuel trim averages — read after join() so the loop's last sample is in
                    val trims = stopped.fuelTrims.result()
                    if (stopped.lapVerdicts.isNotEmpty()) {
                        val counts = stopped.lapVerdicts.entries.joinToString(" ") { (v, n) -> "${v.name}=$n" }
                        Log.d(LAP_TAG, "drive $id fixes: $counts; laps saved: ${laps?.second?.size ?: 0}")
                        DiagnosticLogger.event("LAP", "drive $id fixes: $counts; laps: ${laps?.second?.size ?: 0}")
                    }
                    val pullTracker = stopped.pulls

                    val finalDrive = db.runInTransaction(java.util.concurrent.Callable {
                        // The row as it is now: name / tags edited during STOP win; gone = deleted during STOP
                        val current = dao.getDrive(id) ?: return@Callable null
                        current.copy(
                            endTime = stoppedAt,
                            name = current.name ?: autoName,
                            distanceKm = state.cumulativeDistanceKm,
                            avgSpeedKph = state.avgSpeedKph,
                            maxSpeedKph = state.maxSpeedKph,
                            peakRpm = state.peakRpm.toInt(),
                            peakBoostPsi = state.peakBoostPsi,
                            peakBoostSource = state.peakBoostSource?.name,
                            peakLateralG = state.peakLateralG,
                            fuelUsedL = state.fuelUsedL,
                            driveModeBreakdown = modeJson,
                            weatherSummary = state.currentWeather?.description,
                            startOilTempC = stopped.startOilTempC,
                            endOilTempC = endVs.oilTempC,
                            peakOilTempC = state.peakOilTempC,
                            startCoolantTempC = stopped.startCoolantTempC,
                            endCoolantTempC = endVs.coolantTempC,
                            peakCoolantTempC = state.peakCoolantTempC,
                            aggressionScore = score,
                            avgFuelL100km = if (fuelState.isValid) fuelState.avgL100km else 0.0,
                            avgFuelMpg = if (fuelState.isValid) fuelState.avgMpg else 0.0,
                            thermalPeaksJson = thermalJson,
                            avgStftPct = trims.avgStftPct,
                            avgLtftPct = trims.avgLtftPct,
                            trimSamples = trims.samples,
                            pullCount = pullTracker.pullCount,
                            knockPullCount = pullTracker.knockPullCount
                        ).also { final ->
                            dao.updateDrive(final)
                            // Lap timer results completed in this drive, with the row (no FK from lap_sessions)
                            laps?.let { (sf, done) -> persistLaps(id, sf, done) }
                        }
                    })
                    if (finalDrive == null) {
                        Log.d(TAG, "Drive $id deleted during STOP — nothing finalized")
                        return@launch
                    }

                    // Generate route thumbnail for history list
                    try {
                        val allPoints = dao.getPoints(id)
                        com.openrs.dash.ui.trip.RouteThumbnail.generateAndCache(context, id, allPoints)
                    } catch (e: Exception) {
                        Log.d(TAG, "Thumbnail generation failed", e)
                    }

                    postSummary(finalDrive)
                    val failed = stopped.failedPointWrites.get()
                    if (failed > 0) {
                        Log.w(TAG, "Drive $id ended with $failed failed point write(s) — those points are not saved")
                        DiagnosticLogger.event("REC", "drive $id: $failed point write(s) failed — points not saved")
                    }
                    Log.d(TAG, "Drive $id ended (${state.totalPointCount} points)")
                } catch (e: Exception) {
                    Log.w(TAG, "Drive finalize failed", e)
                }
            }
        }
        // Set before the state says not recording, so a reader never sees neither (#212)
        finalizeJob = finalize
        finalize.start()
        // The stopped drive's position goes with it (#311); only that drive's, as a drive started since owns its own
        _driveState.update {
            if (it.driveId == state.driveId) it.copy(phase = DrivePhase.IDLE, pauseReason = null, currentLocation = null)
            else it.copy(phase = DrivePhase.IDLE, pauseReason = null)
        }
        return finalize
    }

    /**
     * STOP during a start: wait for the start's job (its insert completes, nothing after it runs), then remove the row
     * it may have written. No drive is shown for a start that never recorded, and the next app start does not close it
     * as "ended mid-drive". Publishes nothing to the state: the job's own finally clears its flags if it got that far.
     */
    private fun cancelStart(start: Job, cancelled: DriveRun): Job {
        val cleanup = scope.launch(start = CoroutineStart.LAZY, context = Dispatchers.IO) {
            start.join()
            val created = cancelled.driveId
            if (created <= 0) return@launch                     // stopped before its insert: no row
            try {
                dao.deleteDrive(created)
                Log.d(TAG, "Drive $created: STOP during its start — row removed")
                DiagnosticLogger.event("REC", "drive $created: start cancelled by STOP before recording, nothing kept")
            } catch (e: Exception) {
                Log.w(TAG, "Removing a drive stopped during its start failed", e)
            }
        }
        finalizeJob = cleanup                                    // isFinalizing / durabilityBarrier see it (#212)
        cleanup.start()
        return cleanup
    }

    fun cancel() {
        recorderJob?.cancel()
        scope.cancel()
    }

    // ── Internal recording loop ──────────────────────────────────────────────

    private suspend fun runRecordingLoop(driveId: Long, run: DriveRun) = withContext(Dispatchers.Default) {
        try {
            fetchInitialWeather()
            var lastWeatherMs = clock()
            // Gap diagnostics (#221): why points stopped — never fills them in
            val gaps = RecordingGapMonitor().also { it.start(clock()) }
            launch {
                while (isActive) {
                    delay(RecordingGapMonitor.GAP_AFTER_MS)
                    val st = _driveState.value
                    if (!st.isRecording) continue
                    gaps.tick(clock(), vehicleStateFlow.value.isConnected, st.isPaused)
                        ?.let { DiagnosticLogger.event("REC_GAP", "drive $driveId: $it") }
                }
            }

            locations.updates().collect { location ->
                val state = _driveState.value
                if (!state.isRecording) return@collect

                // Always update current location (for live position dot even when paused)
                _driveState.update { if (it.driveId == driveId) it.copy(currentLocation = location) else it }

                // Skip recording when paused
                if (state.isPaused) {
                    // N1: nothing decoded before or during the pause may be stored at a fix after it
                    run.freshGate.skip(clock())
                    lapTimer?.breakTrack(driveId)
                    gaps.onLocation(clock(), written = false, skipped = RecordingGapMonitor.Reason.PAUSED)
                        ?.let { DiagnosticLogger.event("REC_GAP", "drive $driveId: ${it.describe()}") }
                    return@collect
                }

                val now = clock()

                // Periodic weather refresh
                if (now - lastWeatherMs >= WEATHER_REFRESH_MS) {
                    launch { refreshWeather(location.latitude, location.longitude) }
                    lastWeatherMs = now
                }

                val vs = vehicleStateFlow.value
                // Adapter dropped (retry / IDLE, or a manually started drive): VehicleState
                // still holds the last frame. Writing it would store held values as fresh
                // samples (and feed pulls / knock / trims) — skip like a pause and break the
                // polyline. The live position dot above keeps updating; distance driven during
                // the gap is not counted.
                if (!vs.isConnected) {
                    run.freshGate.skip(now)                       // N1: the same across a link gap
                    run.prevPoint = null
                    lapTimer?.breakTrack(driveId)
                    gaps.onLocation(now, written = false, skipped = RecordingGapMonitor.Reason.DISCONNECTED)
                        ?.let { DiagnosticLogger.event("REC_GAP", "drive $driveId: ${it.describe()}") }
                    return@collect
                }
                gaps.onLocation(now, written = true)?.let { DiagnosticLogger.event("REC_GAP", "drive $driveId: ${it.describe()}") }
                val gate = run.freshGate
                // Boost-sweep DIDs: value only on a fresh decode, otherwise the
                // "absent" sentinel (0.0 kPa / -1.0 %) so stats never count a held value.
                // N1: each polled value only from a decode made after the last skipped fix and within twice its poll budget;
                // Q1: and after the last resume (a pause can pass with no fix to skip). The gate is touched only here.
                run.resumedAtMs.takeIf { it > 0 }?.let { gate.skip(it) }
                val tipFresh    = gate.takeIfCurrent("tipActualKpa", vs, now)
                val targetFresh = gate.takeIfCurrent("tipDesiredKpa", vs, now)
                val wgdcFresh   = gate.takeIfCurrent("wgdcDesired", vs, now)
                // MAP 0xF40B (#271): a value only on a fresh decode, otherwise null — never a held reading
                val mapFresh    = gate.takeIfCurrent(com.openrs.dash.data.MapF40b.TAG, vs, now)
                // Boost by the source rule (#207): the reading + its source; NONE = no fresh source (0.0 is not a reading)
                val boost = com.openrs.dash.data.BoostSession.read(vs, now)
                // The shared verdict (DRIVE row / THERMAL card) including its holds: TEMPS READY only.
                val raceReady = ThermalReadinessSession.verdict.value.isReady
                val point = DrivePointEntity(
                    driveId        = driveId,
                    timestamp      = now,
                    lat            = location.latitude,
                    lng            = location.longitude,
                    speedKph       = vs.speedKph,
                    rpm            = vs.rpm.toInt(),
                    gear           = vs.gearDisplay,
                    boostPsi       = boost.psi ?: 0.0,
                    boostSource    = boost.source.name,
                    baroKpa        = com.openrs.dash.data.BoostRule.baroKpa(vs),
                    coolantTempC   = vs.coolantTempC,
                    oilTempC       = vs.oilTempC,
                    ambientTempC   = vs.ambientTempC,
                    rduTempC       = vs.rduTempC,
                    ptuTempC       = vs.ptuTempC,
                    fuelLevelPct   = vs.fuelLevelPct,
                    lateralG       = vs.lateralG,
                    throttlePct    = vs.throttleOrPedalPct,   // pedal when 0x076 is absent
                    driveMode      = vs.driveMode.label,
                    wheelSpeedFL   = vs.wheelSpeedFL,
                    wheelSpeedFR   = vs.wheelSpeedFR,
                    wheelSpeedRL   = vs.wheelSpeedRL,
                    wheelSpeedRR   = vs.wheelSpeedRR,
                    tirePressLF    = vs.tirePressLF,
                    tirePressRF    = vs.tirePressRF,
                    tirePressLR    = vs.tirePressLR,
                    tirePressRR    = vs.tirePressRR,
                    tireTempLF     = vs.tireTempLF,
                    tireTempRF     = vs.tireTempRF,
                    tireTempLR     = vs.tireTempLR,
                    tireTempRR     = vs.tireTempRR,
                    longitudinalG  = vs.longitudinalG,
                    brakePressure  = vs.brakePressure,
                    steeringAngle  = vs.steeringAngle,
                    tipActualKpa   = if (tipFresh) vs.tipActualKpa else 0.0,
                    boostTargetKpa = if (targetFresh) vs.tipDesiredKpa else 0.0,
                    wgdcPct        = if (wgdcFresh) vs.wgdcDesired else -1.0,
                    mapKpa         = if (mapFresh) com.openrs.dash.data.MapF40b.kpa(vs) else null,
                    isRaceReady    = raceReady
                )

                // Knock event capture (#174/#186) — one event per cylinder per fresh KR decode
                // showing retard (positive). Written inline under NonCancellable so a stopDrive()
                // cancel can't drop it (#193); events are rare, so the brief suspend is fine.
                run.pulls.update(now, vs.rpm, vs.throttleOrPedalPct)
                val knocks = KnockCapture.eventsFrom(vs, gate, driveId, now, location.latitude, location.longitude)
                if (knocks.isNotEmpty()) {
                    run.pulls.markKnock(now)
                    try {
                        withContext(NonCancellable + Dispatchers.IO) { dao.insertKnockEvents(knocks) }
                    } catch (e: Exception) {
                        Log.w(TAG, "knock insert failed (${knocks.size} events)", e)
                    }
                }

                // Fuel trim drift — fresh decodes only, warm-engine gate inside the accumulator
                run.fuelTrims.add(
                    stftFresh = gate.takeIfCurrent("shortFuelTrim", vs, now), stft = vs.shortFuelTrim,
                    ltftFresh = gate.takeIfCurrent("longFuelTrim", vs, now),  ltft = vs.longFuelTrim,
                    coolantC = vs.coolantTempC, rpm = vs.rpm
                )

                // Thermal record: fresh readings judged against the profile in effect now (a mid-drive
                // change starts a new segment). Prefs are loaded before any connection (CanDataService).
                run.thermal.sample(vs, UserPrefsStore.prefs.value, now)
                // Climb rates for the drive-end record
                run.thermalPredictor.let { tp ->
                    tp.recordSample("Oil", now, vs.oilTempC)
                    tp.recordSample("Coolant", now, vs.coolantTempC)
                    tp.recordSample("RDU", now, vs.rduTempC)
                    tp.recordSample("PTU", now, vs.ptuTempC)
                }

                // Lap timer (#237): every recorded fix of this drive, with its own time, age and bearing (none ≠ 0°).
                // Paused / disconnected fixes returned above (and broke the track); a point still finishing after STOP
                // is not fed, and one that slips past this check is refused by the timer (drive closed at STOP).
                if (isActive) lapTimer?.let { timer ->
                    val ageMs = fixAgeMs(location, now)
                    val bearing = if (location.hasBearing()) location.bearing else null
                    val report = timer.onFix(driveId, LapFix(
                        lat = location.latitude, lng = location.longitude,
                        bearing = bearing,
                        timeMs = location.time, ageMs = ageMs, receivedAtMs = now,
                        speedKph = vs.speedKph, rpm = vs.rpm.toInt(), boostPsi = boost.psi ?: 0.0, lateralG = vs.lateralG
                    ))
                    if (report.verdict != FixVerdict.NO_LINE) {
                        run.lapVerdicts.merge(report.verdict, 1) { a, b -> a + b }
                        // One line per fix while a line is set: provider delivery vs timer verdict (no coordinates)
                        Log.d(LAP_TAG, "drive $driveId fix t=${location.time} age=${ageMs}ms " +
                            "b=${bearing?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "none"} " +
                            "x=${"%.1f".format(java.util.Locale.ROOT, report.xM ?: 0.0)} y=${"%.1f".format(java.util.Locale.ROOT, report.yM ?: 0.0)} " +
                            "-> ${report.verdict}${report.lap?.let { " lap ${it.lapNumber} ${it.lapTimeMs} ms" } ?: ""}")
                    }
                }

                // Buffer point
                run.addPoint(point)

                // Update recent points list for live UI
                run.recentPoints.add(point)
                if (run.recentPoints.size > MAX_RECENT_POINTS) {
                    run.recentPoints.removeAt(0)
                }

                // Update live state
                val prev = run.prevPoint
                val segDist = if (prev != null)
                    DriveState.haversineKm(prev.lat, prev.lng, point.lat, point.lng)
                else 0.0

                val peaks = buildPeakEvents(state, vs, boost, point, now)
                val newBoostPeak = com.openrs.dash.data.BoostRule.nextDrivePeak(state.peakBoostPsi, state.peakBoostSource, boost)
                val isRaceReady = raceReady
                val rtrPt = state.rtrAchievedPoint ?: if (isRaceReady) point else null

                // A point still in progress when STOP cancelled this loop finishes here; once another drive
                // owns the live state it isn't counted there (#236)
                _driveState.update { s ->
                    if (s.driveId != driveId) s else s.copy(
                        recentPoints         = run.recentPoints.toList(),
                        totalPointCount      = s.totalPointCount + 1,
                        cumulativeDistanceKm = s.cumulativeDistanceKm + segDist,
                        rpmSum               = if (vs.rpm > 400) s.rpmSum + vs.rpm else s.rpmSum,
                        rpmSamples           = if (vs.rpm > 400) s.rpmSamples + 1 else s.rpmSamples,
                        speedSum             = s.speedSum + vs.speedKph,
                        speedSamples         = s.speedSamples + 1,
                        modeCounts           = s.modeCounts.toMutableMap().also {
                            it[vs.driveMode] = (it[vs.driveMode] ?: 0) + 1
                        },
                        maxSpeedKph          = maxOf(s.maxSpeedKph, vs.speedKph),
                        peakRpm              = maxOf(s.peakRpm, vs.rpm),
                        peakBoostPsi         = newBoostPeak?.first ?: s.peakBoostPsi,
                        peakBoostSource      = newBoostPeak?.second ?: s.peakBoostSource,
                        peakLateralG         = maxOf(s.peakLateralG, abs(vs.lateralG)),
                        peakOilTempC         = if (vs.oilTempC > -90) maxOf(s.peakOilTempC, vs.oilTempC) else s.peakOilTempC,
                        peakCoolantTempC     = if (vs.coolantTempC > -90) maxOf(s.peakCoolantTempC, vs.coolantTempC) else s.peakCoolantTempC,
                        peakEvents           = peaks,
                        rtrAchievedPoint     = rtrPt,
                        pullCount            = run.pulls.pullCount,
                        knockPullCount       = run.pulls.knockPullCount
                    )
                }
                run.prevPoint = point

                // Flush to Room when the buffer is full — last, so a STOP cancellation delivered when the insert
                // returns (flushBuffer rethrows it) can't cut this point's state update short
                if (run.bufferedCount() >= FLUSH_SIZE) {
                    flushBuffer(run)
                }
            }
        } finally {
            // Ensure we flush any remaining buffered points (STOP's cancellation is rethrown after it).
            // The state is cleared by the drive's job (startDrive), after this
            flushBuffer(run)
        }
    }

    /**
     * Persists the in-memory point buffer to Room. Suspends until the write
     * completes so callers can order subsequent work against it (e.g. writing
     * the drive summary only after points land).
     *
     * Wrapped in [NonCancellable] so the write runs to completion even when
     * the recording coroutine is being cancelled from its `finally` block —
     * the common stop path. Without this, `pointsBuffer.clear()` has already
     * discarded the points by the time the IO suspension throws.
     *
     * Returns false when the insert failed (counted on the run). Callers outside the drive's loop hold
     * [DriveRun.outsideLoopWrites] around it (#241).
     *
     * The outcome is the insert's own, decided inside the NonCancellable block. A cancellation of the caller (STOP
     * cancelling the loop, an export's caller going away) is delivered only when that block returns — after the insert
     * has landed or failed — so it never counts as a failed write; it is rethrown once the outcome is recorded.
     */
    private suspend fun flushBuffer(run: DriveRun): Boolean {
        run.inFlight.update { it + 1 }                 // before the take: a barrier never sees taken-but-unstored points as done
        var failure: Exception? = null
        try {
            val toFlush = run.takePoints()
            if (toFlush.isEmpty()) return true
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { dao.insertPoints(toFlush) } catch (e: Exception) { failure = e }
                }
            } finally {
                failure?.let {
                    run.failedPointWrites.incrementAndGet()
                    Log.w(TAG, "Point flush failed (${toFlush.size} points)", it)
                }
            }
        } finally {
            run.inFlight.update { it - 1 }
        }
        return failure == null
    }

    // ── Peak event tracking ──────────────────────────────────────────────────

    private fun buildPeakEvents(
        state: DriveState,
        vs: VehicleState,
        boost: com.openrs.dash.data.BoostReading,
        point: DrivePointEntity,
        now: Long
    ): List<PeakEvent> {
        val peaks = state.peakEvents.toMutableList()
        if (vs.rpm > state.peakRpm) {
            peaks.removeAll { it.type == PeakType.RPM }
            peaks += PeakEvent(PeakType.RPM, vs.rpm, point.lat, point.lng, now)
        }
        com.openrs.dash.data.BoostRule.nextDrivePeak(state.peakBoostPsi, state.peakBoostSource, boost)?.let { (psi, src) ->
            peaks.removeAll { it.type == PeakType.BOOST }
            peaks += PeakEvent(PeakType.BOOST, psi, point.lat, point.lng, now, boostSource = src)
        }
        if (abs(vs.lateralG) > state.peakLateralG) {
            peaks.removeAll { it.type == PeakType.LATERAL_G }
            peaks += PeakEvent(PeakType.LATERAL_G, abs(vs.lateralG), point.lat, point.lng, now)
        }
        if (vs.speedKph > state.maxSpeedKph) {
            peaks.removeAll { it.type == PeakType.SPEED }
            peaks += PeakEvent(PeakType.SPEED, vs.speedKph, point.lat, point.lng, now)
        }
        return peaks
    }

    // ── Weather ──────────────────────────────────────────────────────────────

    private suspend fun fetchInitialWeather() {
        try {
            val loc = getLastKnownLocation() ?: return
            weatherRepo.fetchWeather(loc.latitude, loc.longitude)?.let { weather ->
                _driveState.update { it.copy(currentWeather = weather) }
            }
        } catch (_: Exception) {}
    }

    private suspend fun refreshWeather(lat: Double, lon: Double) {
        try {
            weatherRepo.fetchWeather(lat, lon)?.let { weather ->
                _driveState.update { it.copy(currentWeather = weather) }
            }
        } catch (_: Exception) {}
    }

    // ── Location helpers ─────────────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun locationFlow(): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            close(SecurityException("Location permission not granted"))
            return@callbackFlow
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(750L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }

        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }

    suspend fun getLastKnownLocation(): Location? = locations.last()

    /**
     * The recording drive's own last fix, if it is at most [LapTimer.MAX_FIX_AGE_MS] old at [nowMs] (#311): the only
     * position a note or the start/finish line may be placed at. Null before the drive's first fix, after STOP, when
     * not recording, and during a GPS gap (the last fix is then not where the car is).
     */
    fun freshFix(nowMs: Long = clock()): Location? {
        val state = _driveState.value
        if (!state.isRecording) return null
        return state.currentLocation?.takeIf { fixAgeMs(it, nowMs) <= LapTimer.MAX_FIX_AGE_MS }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fusedLastLocation(): Location? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (!hasLocationPermission()) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                fusedClient.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }

    /**
     * Returns a live snapshot of the active drive (drive entity + all persisted
     * points) for export. Flushes any buffered points synchronously first so the
     * caller sees up-to-the-moment data. Does NOT stop recording — safe to call
     * while a drive is in progress.
     *
     * Returns null when no drive is currently recording. The returned
     * [DriveEntity] has live accumulators (distance/peaks/avg) applied from
     * [DriveState], so summaries reflect the in-flight session rather than the
     * zeroed Room record.
     */
    suspend fun snapshotActiveDrive(): Pair<DriveEntity, List<DrivePointEntity>>? {
        val state = _driveState.value
        if (!state.isRecording || state.driveId <= 0L) return null

        return withContext(Dispatchers.IO) {
            try {
                // Synchronous flush — avoid a race with the point query below. Under the run's lock, so a STOP
                // finalizing this drive meanwhile waits for these points before its thumbnail (#241); a failed
                // insert fails the export.
                val r = run
                if (!r.outsideLoopWrites.withLock { flushBuffer(r) }) return@withContext null

                val drive = dao.getDrive(state.driveId) ?: return@withContext null
                val allPoints = dao.getPoints(state.driveId)

                // Apply live state so the exported summary isn't the zeroed
                // skeleton that gets written at drive start.
                val liveDrive = drive.copy(
                    distanceKm   = state.cumulativeDistanceKm,
                    avgSpeedKph  = state.avgSpeedKph,
                    maxSpeedKph  = state.maxSpeedKph,
                    peakRpm      = state.peakRpm.toInt(),
                    peakBoostPsi = state.peakBoostPsi,
                    peakBoostSource = state.peakBoostSource?.name,
                    peakLateralG = state.peakLateralG,
                    fuelUsedL    = state.fuelUsedL,
                    weatherSummary = state.currentWeather?.description
                )
                Pair(liveDrive, allPoints)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e                                       // the caller went away — not a failed export
            } catch (e: Exception) {
                Log.w(TAG, "snapshotActiveDrive failed", e)
                null
            }
        }
    }

    // ── Aggression score ───────────────────────────────────────────────────

    /**
     * Computes a 0-100 aggression score from session averages:
     *   30% avg throttle, 25% avg |lateralG|, 25% avg RPM fraction, 20% avg boost fraction.
     */
    private fun computeAggressionScore(totalPointCount: Int, points: List<DrivePointEntity>): Int {
        if (totalPointCount < 10) return 0
        if (points.isEmpty()) return 0

        val avgThrottle = points.sumOf { it.throttlePct } / points.size / 100.0
        val avgLatG = points.sumOf { abs(it.lateralG) } / points.size / 1.0
        val avgRpmFrac = points.sumOf { it.rpm.toDouble() } / points.size / 6800.0
        // Boost term from readings of one source only (the drive peak's, #207); points without a reading are left out,
        // never averaged in as 0. No reading at all: the other three terms carry the score.
        val src = com.openrs.dash.data.StoredBoost.primarySource(points)
        val boostPsis = points.mapNotNull { (com.openrs.dash.data.StoredBoost.point(it) as? com.openrs.dash.data.StoredBoost.Reading)
            ?.takeIf { r -> r.source == src }?.psi }
        val base = avgThrottle * 0.30 + avgLatG * 0.25 + avgRpmFrac * 0.25
        val raw = if (boostPsis.isEmpty()) base / 0.80 * 100.0
                  else (base + boostPsis.sumOf { it.coerceAtLeast(0.0) } / boostPsis.size / 22.0 * 0.20) * 100.0
        return min(100, raw.toInt().coerceAtLeast(0))
    }

    /**
     * A drive left open by a process death, closed (#262): it ends at its last stored point (its start without one), and
     * gets the summary fields that follow from the stored points alone — max / average speed, peak RPM, peak |lateral G|,
     * peak / first / last oil and coolant (valid readings only), the mode breakdown and the aggression score, each by the
     * same rule STOP uses over the recorded points. Distance joins only consecutive stored points at most
     * [ORPHAN_SEGMENT_MAX_GAP_MS] apart: a pause or a link loss breaks the live polyline and its fixes are never stored, so
     * a longer gap is not driven distance (a late fix is left out too, and a single skipped fix can be bridged — so it can
     * differ slightly from what STOP would have written). Fuel, fuel economy, trims, pulls, the thermal record, laps,
     * weather and the name stay at the row's values: they lived in memory only. No summary sheet is posted for it.
     */
    private fun closedOrphan(orphan: DriveEntity, points: List<DrivePointEntity>): DriveEntity {
        if (points.isEmpty()) return orphan.copy(endTime = orphan.startTime)
        var distanceKm = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            if (b.timestamp - a.timestamp <= ORPHAN_SEGMENT_MAX_GAP_MS) distanceKm += DriveState.haversineKm(a.lat, a.lng, b.lat, b.lng)
        }
        val oil = points.filter { it.oilTempC > -90 }
        val coolant = points.filter { it.coolantTempC > -90 }
        val modes = JSONObject().apply {
            points.groupingBy { it.driveMode }.eachCount().forEach { (mode, n) -> put(mode, n.toDouble() / points.size) }
        }.toString()
        return orphan.copy(
            endTime = points.last().timestamp,
            distanceKm = distanceKm,
            avgSpeedKph = points.sumOf { it.speedKph } / points.size,
            maxSpeedKph = points.maxOf { it.speedKph },
            peakRpm = points.maxOf { it.rpm },
            peakLateralG = points.maxOf { abs(it.lateralG) },
            peakOilTempC = oil.maxOfOrNull { it.oilTempC } ?: orphan.peakOilTempC,
            startOilTempC = oil.firstOrNull()?.oilTempC ?: orphan.startOilTempC,
            endOilTempC = oil.lastOrNull()?.oilTempC ?: orphan.endOilTempC,
            peakCoolantTempC = coolant.maxOfOrNull { it.coolantTempC } ?: orphan.peakCoolantTempC,
            startCoolantTempC = coolant.firstOrNull()?.coolantTempC ?: orphan.startCoolantTempC,
            endCoolantTempC = coolant.lastOrNull()?.coolantTempC ?: orphan.endCoolantTempC,
            driveModeBreakdown = modes,
            aggressionScore = computeAggressionScore(points.size, points)
        )
    }

    // ── Auto-naming ─────────────────────────────────────────────────────────

    /**
     * Reverse-geocodes the drive's first recorded point to produce an auto-name.
     * Returns null on failure (no network, no geocoder, etc.).
     */
    private fun reverseGeocode(state: DriveState): String? {
        val first = state.recentPoints.firstOrNull() ?: return null
        return try {
            geocode(first.lat, first.lng)
        } catch (e: Exception) {
            Log.d(TAG, "Geocode failed for (${first.lat}, ${first.lng})", e)
            null
        }
    }

    // ── Lap persistence ────────────────────────────────────────────────────

    /** Writes the laps one drive completed (taken from the UI-scoped [LapTimer] at its STOP) to Room. */
    private fun persistLaps(driveId: Long, sf: com.openrs.dash.data.StartFinishLine, laps: List<com.openrs.dash.data.LapResult>) {
        try {
            val sessionId = dao.insertLapSession(
                LapSessionEntity(
                    driveId = driveId,
                    startFinishLat = sf.lat,
                    startFinishLng = sf.lng,
                    startFinishBearing = sf.bearing ?: return,   // laps exist only once the line's direction is known
                    createdAt = clock()
                )
            )
            dao.insertLaps(laps.map { lap ->
                LapEntity(
                    sessionId = sessionId,
                    lapNumber = lap.lapNumber,
                    lapTimeMs = lap.lapTimeMs,
                    peakRpm = lap.peakRpm,
                    peakBoostPsi = lap.peakBoostPsi,
                    peakLateralG = lap.peakLateralG,
                    peakSpeedKph = lap.peakSpeedKph
                )
            })
            Log.d(TAG, "Persisted ${laps.size} laps for drive $driveId")
        } catch (e: Exception) {
            Log.w(TAG, "Lap persistence failed", e)
        }
    }

    // ── Thermal record ───────────────────────────────────────────────────

    /**
     * Per-sensor peaks + the limits each part of the drive was judged against (DriveThermalRecord).
     * Replaces the fixed 140 / 115 / 120 / 120 °C snapshot, which matched no profile and stored the
     * drive-end reading as its "peak".
     */
    private fun buildThermalPeaksJson(run: DriveRun): String? {
        val tp = run.thermalPredictor
        val climb = DriveThermalSensor.entries.mapNotNull { s -> tp.slopeCPerMin(s.key)?.let { s to it } }.toMap()
        return run.thermal.toJson(climb)
    }

    // ── Event bookmarks ──────────────────────────────────────────────────

    /**
     * The moment an observer's note refers to: taken when the driver asks for the note (the long-press on MARK), used
     * when its text is saved. The note's time, place and vehicle readings are this moment's, not the save's.
     */
    class NoteMark internal constructor(
        internal val driveId: Long, val atMs: Long, internal val location: Location?, internal val vs: VehicleState,
    )

    /** The mark for a note asked for now; null when no drive is recording. */
    fun markNow(): NoteMark? {
        val state = _driveState.value
        if (!state.isRecording || state.driveId <= 0L) return null
        val now = clock()
        return NoteMark(state.driveId, now, freshFix(now), vehicleStateFlow.value)
    }

    /**
     * Records an observer's note ([com.openrs.dash.data.ObserverNote]) while a drive is recording: a session event on
     * the diagnostic timeline, always, and a GPS-tagged bookmark row with vehicle state when a location was known.
     * Everything is stamped with [at] — by default now (a tap on MARK); a note typed in the form passes the mark taken
     * when the form opened. No-op without a mark, or when its drive is no longer the one recording.
     */
    fun addBookmark(label: String = "", at: NoteMark? = markNow()) {
        val state = _driveState.value
        if (at == null || !state.isRecording || state.driveId != at.driveId) return
        val note = com.openrs.dash.data.ObserverNote.clean(label)
        val loc = at.location
        DiagnosticLogger.eventAt(com.openrs.dash.data.ObserverNote.EVENT,
            com.openrs.dash.data.ObserverNote.eventText(note, placed = loc != null), at.atMs)
        if (loc == null) return
        val vs = at.vs
        scope.launch(Dispatchers.IO) {
            try {
                dao.insertBookmark(
                    DriveBookmarkEntity(
                        driveId = at.driveId,
                        timestamp = at.atMs,
                        lat = loc.latitude,
                        lng = loc.longitude,
                        label = note,
                        speedKph = vs.speedKph,
                        rpm = vs.rpm.toInt(),
                        // Not shown or exported (#207): bookmarks have no source column, so a value couldn't be named
                        boostPsi = com.openrs.dash.data.BoostSession.read(vs, at.atMs).psi ?: 0.0
                    )
                )
                Log.d(TAG, "Bookmark added at (${loc.latitude}, ${loc.longitude})")
            } catch (e: Exception) {
                Log.w(TAG, "Bookmark insert failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "DriveRecorder"

        /** How old a fix is at [nowMs] — on the monotonic clock when the fix carries it, else the wall clock. */
        internal fun fixAgeMs(location: Location, nowMs: Long): Long =
            if (location.elapsedRealtimeNanos > 0L) (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            else nowMs - location.time
        private const val LAP_TAG = "LapTimer"

        /** Street name, else locality, else admin area of the first result; null when there is none. */
        @Suppress("DEPRECATION")
        private fun platformGeocoder(context: Context): (Double, Double) -> String? = { lat, lng ->
            Geocoder(context).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { addr ->
                addr.thoroughfare ?: addr.locality ?: addr.subAdminArea ?: addr.adminArea
            }
        }
        private const val WEATHER_REFRESH_MS = 15 * 60_000L
        private const val FLUSH_SIZE = 30           // flush every ~30 seconds at 1 Hz
        /** An orphan's distance joins two stored points only this close (1 Hz fixes; see [closedOrphan], #262). */
        private const val ORPHAN_SEGMENT_MAX_GAP_MS = 2_000L
        private const val MAX_RECENT_POINTS = 3600  // ~1 hour of points for live polyline
    }
}
