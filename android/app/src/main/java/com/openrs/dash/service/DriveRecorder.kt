package com.openrs.dash.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Looper
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
import com.openrs.dash.data.LapEntity
import com.openrs.dash.data.LapSessionEntity
import com.openrs.dash.data.LapTimer
import com.openrs.dash.data.PeakEvent
import com.openrs.dash.data.PeakType
import com.openrs.dash.data.ThermalPredictor
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.AppSettings
import com.openrs.dash.ui.ThermalReadinessSession
import com.openrs.dash.ui.UserPrefsStore
import com.openrs.dash.ui.sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private var recorderJob: Job? = null

    /**
     * Everything one drive accumulates (#236). Each drive gets its own at [startDrive]; its recording loop and its
     * STOP finalization hold that instance, so a drive started while the previous one is still being finalized
     * (auto-record's finalize + start, START right after STOP) can't hand its buffer, thermal record, start temps or
     * points to the old one. These used to be recorder fields that [startDrive] reassigned under the old drive.
     */
    private class DriveRun(vs: VehicleState, startMs: Long) {
        /** Buffer for batch writes — flushed to Room every FLUSH_SIZE points. */
        val pointsBuffer = ArrayList<DrivePointEntity>(FLUSH_SIZE * 2)
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
    }

    /** The drive being recorded (or the last one); see [DriveRun]. */
    @Volatile private var run = DriveRun(VehicleState(), 0L)

    /**
     * Set by TripPage when a LapTimer is active during recording.
     * DriveRecorder flushes laps to Room on [stopDrive].
     */
    @Volatile var lapTimer: LapTimer? = null

    /**
     * Timestamp of the most recent [pauseDrive] call, or 0 when not paused.
     * Used by the auto-record pipeline to decide whether a resume is valid
     * (recent pause) or whether to finalize and start fresh.
     */
    @Volatile private var pausedAtMs: Long = 0L

    init {
        // Finalize any drives left with endTime=0 from a previous app session
        // (app killed mid-drive, crash, etc.). In-memory pause state is lost
        // at restart, so these cannot be resumed — close them out cleanly so
        // "endTime == 0" keeps meaning "truly in progress."
        scope.launch(Dispatchers.IO) {
            try {
                val orphans = dao.getUnfinishedDrives()
                for (orphan in orphans) {
                    val lastPt = dao.getLastPointTimestamp(orphan.id) ?: orphan.startTime
                    dao.updateDrive(orphan.copy(endTime = lastPt))
                    Log.d(TAG, "Finalized orphan drive ${orphan.id} (endTime=$lastPt)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Orphan drive cleanup failed", e)
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Start recording a new drive. Creates a DriveEntity in Room immediately.
     * If [sessionId] is provided, links this drive to a diagnostic session.
     */
    fun startDrive(sessionId: Long = 0) {
        if (recorderJob?.isActive == true) return
        // Simulated telemetry never enters trip history.
        if (com.openrs.dash.demo.DemoMode.isActive) {
            Log.i(TAG, "startDrive ignored — demo mode active")
            return
        }

        val vs = vehicleStateFlow.value
        // A new instance — a drive still being finalized keeps its own (#236)
        val newRun = DriveRun(vs, System.currentTimeMillis())
        run = newRun

        // Prune old drives if over limit
        scope.launch(Dispatchers.IO) {
            try {
                val maxDrives = AppSettings.getMaxSavedDrives(context)
                val count = dao.getDriveCount()
                if (count >= maxDrives) {
                    dao.deleteOldestDrives(count - maxDrives + 1)
                    dao.deleteOrphanLapSessions()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Drive prune failed", e)
            }
        }

        // Create drive entity
        val now = System.currentTimeMillis()
        var driveId = 0L
        scope.launch(Dispatchers.IO) {
            try {
                driveId = dao.insertDrive(
                    DriveEntity(
                        startTime = now,
                        sessionId = sessionId,
                        startFuelPct = vs.fuelLevelPct,
                        hasGps = hasLocationPermission(),
                        boostPolicy = com.openrs.dash.data.BoostRule.POLICY
                    )
                )
                _driveState.update { state ->
                    state.copy(
                        isRecording = true,
                        isPaused = false,
                        driveId = driveId,
                        startFuelPct = vs.fuelLevelPct,
                        startTime = now,
                        recentPoints = emptyList(),
                        totalPointCount = 0,
                        cumulativeDistanceKm = 0.0,
                        rpmSum = 0.0, rpmSamples = 0L,
                        speedSum = 0.0, speedSamples = 0L,
                        modeCounts = emptyMap(),
                        maxSpeedKph = 0.0, peakRpm = 0.0,
                        peakBoostPsi = 0.0, peakBoostSource = null, peakLateralG = 0.0,
                        peakEvents = emptyList(),
                        rtrAchievedPoint = null,
                        currentWeather = null,
                        pullCount = 0,
                        knockPullCount = 0
                    )
                }
                startRecordingLoop(driveId, newRun)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start drive", e)
                _driveState.update { it.copy(isRecording = false) }
            }
        }
    }

    fun pauseDrive() {
        pausedAtMs = System.currentTimeMillis()
        _driveState.update { it.copy(isPaused = true) }
        // Flush any buffered points immediately. flushBuffer() is suspend +
        // NonCancellable so it completes even if the scope is cancelled while
        // the pause is being handled.
        val r = run
        scope.launch { flushBuffer(r.pointsBuffer) }
    }

    fun resumeDrive() {
        pausedAtMs = 0L
        run.prevPoint = null  // Gap in polyline — next point won't connect to prev
        _driveState.update { it.copy(isPaused = false) }
    }

    /**
     * Milliseconds elapsed since the drive was paused, or 0 when not paused.
     * Used by auto-record to decide whether a paused drive is recent enough
     * to resume or has gone stale and should be finalized.
     */
    fun pausedDurationMs(): Long =
        if (pausedAtMs > 0L) System.currentTimeMillis() - pausedAtMs else 0L

    /**
     * Ends the drive and finalizes it on IO; the returned job completes when the row, laps and summary are written
     * (tests join it). Everything the drive saves is taken from this call (#236): its [DriveRun], STOP time, end
     * temps, fuel economy and laps — a drive started meanwhile has its own. The row is re-read and written in one
     * transaction, recorder-owned columns only: a rename / tag made during STOP stays, and a drive deleted during
     * STOP gets no laps, thumbnail or summary.
     */
    fun stopDrive(): Job {
        val stoppedAt = System.currentTimeMillis()
        pausedAtMs = 0L
        val jobToJoin = recorderJob
        recorderJob?.cancel()
        recorderJob = null

        val state = _driveState.value
        val endVs = vehicleStateFlow.value
        val stopped = run
        val fuelState = FuelEconomy.state.value
        val laps = lapTimer?.takeLaps()
        val finalize = scope.launch(Dispatchers.IO) {
            // Wait for the recording loop's finally{} to flush buffered points
            // before we write the drive summary. This avoids racing our own
            // flush against the loop's flush (both would copy pointsBuffer
            // before either cleared it, duplicating points) and guarantees
            // the summary reflects every recorded point.
            jobToJoin?.join()

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
                    val score = computeAggressionScore(state, stopped.recentPoints)

                    // Auto-name via reverse geocoder if no user name set — outside the transaction (network)
                    val autoName = if (drive.name == null) reverseGeocode(state) else null

                    // Snapshot thermal peaks + climb rates
                    val thermalJson = buildThermalPeaksJson(stopped)

                    // Fuel trim averages — read after join() so the loop's last sample is in
                    val trims = stopped.fuelTrims.result()
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
                    Log.d(TAG, "Drive $id ended (${state.totalPointCount} points)")
                } catch (e: Exception) {
                    Log.w(TAG, "Drive finalize failed", e)
                }
            }
        }
        _driveState.update {
            it.copy(isRecording = false, isPaused = false)
        }
        return finalize
    }

    fun cancel() {
        recorderJob?.cancel()
        scope.cancel()
    }

    // ── Internal recording loop ──────────────────────────────────────────────

    private fun startRecordingLoop(driveId: Long, run: DriveRun) {
        recorderJob = scope.launch {
            try {
                fetchInitialWeather()
                var lastWeatherMs = System.currentTimeMillis()
                // Gap diagnostics (#221): why points stopped — never fills them in
                val gaps = RecordingGapMonitor().also { it.start(System.currentTimeMillis()) }
                launch {
                    while (isActive) {
                        delay(RecordingGapMonitor.GAP_AFTER_MS)
                        val st = _driveState.value
                        if (!st.isRecording) continue
                        gaps.tick(System.currentTimeMillis(), vehicleStateFlow.value.isConnected, st.isPaused)
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
                        gaps.onLocation(System.currentTimeMillis(), written = false, skipped = RecordingGapMonitor.Reason.PAUSED)
                            ?.let { DiagnosticLogger.event("REC_GAP", "drive $driveId: ${it.describe()}") }
                        return@collect
                    }

                    val now = System.currentTimeMillis()

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
                        run.prevPoint = null
                        gaps.onLocation(now, written = false, skipped = RecordingGapMonitor.Reason.DISCONNECTED)
                            ?.let { DiagnosticLogger.event("REC_GAP", "drive $driveId: ${it.describe()}") }
                        return@collect
                    }
                    gaps.onLocation(now, written = true)?.let { DiagnosticLogger.event("REC_GAP", "drive $driveId: ${it.describe()}") }
                    val gate = run.freshGate
                    val stamps = vs.fieldLastUpdateMs
                    // Boost-sweep DIDs: value only on a fresh decode, otherwise the
                    // "absent" sentinel (0.0 kPa / -1.0 %) so stats never count a held value.
                    val tipFresh    = gate.takeIfFresh("tipActualKpa", stamps)
                    val targetFresh = gate.takeIfFresh("tipDesiredKpa", stamps)
                    val wgdcFresh   = gate.takeIfFresh("wgdcDesired", stamps)
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
                        stftFresh = gate.takeIfFresh("shortFuelTrim", stamps), stft = vs.shortFuelTrim,
                        ltftFresh = gate.takeIfFresh("longFuelTrim", stamps),  ltft = vs.longFuelTrim,
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

                    // Lap timer (#237): every recorded fix of this drive — paused / disconnected fixes returned above, and
                    // a point still finishing after STOP is not fed (the timer belongs to the next drive by then)
                    if (isActive) lapTimer?.onLocationUpdate(
                        location.latitude, location.longitude, location.bearing, vs.speedKph, now,
                        vs.rpm.toInt(), boost.psi ?: 0.0, vs.lateralG
                    )

                    // Buffer point
                    run.pointsBuffer.add(point)

                    // Update recent points list for live UI
                    run.recentPoints.add(point)
                    if (run.recentPoints.size > MAX_RECENT_POINTS) {
                        run.recentPoints.removeAt(0)
                    }

                    // Flush to Room when buffer is full
                    if (run.pointsBuffer.size >= FLUSH_SIZE) {
                        flushBuffer(run.pointsBuffer)
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
                }
            } finally {
                // Ensure we flush any remaining buffered points
                flushBuffer(run.pointsBuffer)
                // Only this drive's state: a drive started since (finalize + start) is recording (#236)
                _driveState.update { if (it.driveId == driveId) it.copy(isRecording = false) else it }
            }
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
     */
    private suspend fun flushBuffer(pointsBuffer: ArrayList<DrivePointEntity>) {
        val toFlush = ArrayList(pointsBuffer)
        pointsBuffer.clear()
        if (toFlush.isEmpty()) return
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                dao.insertPoints(toFlush)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Point flush failed (${toFlush.size} points)", e)
        }
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
                // Synchronous flush — avoid a race with the point query below.
                val pointsBuffer = run.pointsBuffer
                val pending = ArrayList(pointsBuffer)
                pointsBuffer.clear()
                if (pending.isNotEmpty()) {
                    dao.insertPoints(pending)
                }

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
    private fun computeAggressionScore(state: DriveState, points: List<DrivePointEntity>): Int {
        if (state.totalPointCount < 10) return 0
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
                    startFinishBearing = sf.bearing,
                    createdAt = System.currentTimeMillis()
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
     * Inserts a GPS-tagged bookmark at the current location with live vehicle state.
     * No-op when not recording or no location is available.
     */
    fun addBookmark(label: String = "") {
        val state = _driveState.value
        if (!state.isRecording || state.driveId <= 0L) return
        val loc = state.currentLocation ?: return
        val vs = vehicleStateFlow.value
        scope.launch(Dispatchers.IO) {
            try {
                dao.insertBookmark(
                    DriveBookmarkEntity(
                        driveId = state.driveId,
                        timestamp = System.currentTimeMillis(),
                        lat = loc.latitude,
                        lng = loc.longitude,
                        label = label,
                        speedKph = vs.speedKph,
                        rpm = vs.rpm.toInt(),
                        // Not shown or exported (#207): bookmarks have no source column, so a value couldn't be named
                        boostPsi = com.openrs.dash.data.BoostSession.read(vs, System.currentTimeMillis()).psi ?: 0.0
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

        /** Street name, else locality, else admin area of the first result; null when there is none. */
        @Suppress("DEPRECATION")
        private fun platformGeocoder(context: Context): (Double, Double) -> String? = { lat, lng ->
            Geocoder(context).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { addr ->
                addr.thoroughfare ?: addr.locality ?: addr.subAdminArea ?: addr.adminArea
            }
        }
        private const val WEATHER_REFRESH_MS = 15 * 60_000L
        private const val FLUSH_SIZE = 30           // flush every ~30 seconds at 1 Hz
        private const val MAX_RECENT_POINTS = 3600  // ~1 hour of points for live polyline
    }
}
