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
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.LapEntity
import com.openrs.dash.data.LapSessionEntity
import com.openrs.dash.data.LapTimer
import com.openrs.dash.data.PeakEvent
import com.openrs.dash.data.PeakType
import com.openrs.dash.data.ThermalPredictor
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val db: DriveDatabase
) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val dao = db.driveDao()

    private val _driveState = MutableStateFlow(DriveState())
    val driveState: StateFlow<DriveState> = _driveState.asStateFlow()

    /** Emitted once when a drive is stopped and finalized. */
    private val _driveCompleted = MutableSharedFlow<DriveEntity>(replay = 0, extraBufferCapacity = 1)
    val driveCompleted: SharedFlow<DriveEntity> = _driveCompleted.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorderJob: Job? = null

    /** Buffer for batch writes — flushed to Room every FLUSH_INTERVAL points. */
    @Volatile private var pointsBuffer = ArrayList<DrivePointEntity>(FLUSH_SIZE * 2)

    /** Recent points kept in memory for live polyline rendering. */
    @Volatile private var recentPointsList = ArrayList<DrivePointEntity>(MAX_RECENT_POINTS)

    /** Start-of-drive temps captured from VehicleState at startDrive(). */
    @Volatile private var startOilTempC = -99.0
    @Volatile private var startCoolantTempC = -99.0

    /** Previous point for distance calculation. */
    @Volatile private var prevPoint: DrivePointEntity? = null

    /** Per-drive thermal predictor for slope/peak snapshots at drive end. */
    private var driveThermalPredictor: ThermalPredictor? = null

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

        val vs = vehicleStateFlow.value
        startOilTempC = vs.oilTempC
        startCoolantTempC = vs.coolantTempC
        pointsBuffer = ArrayList(FLUSH_SIZE * 2)
        recentPointsList = ArrayList(MAX_RECENT_POINTS)
        prevPoint = null
        driveThermalPredictor = ThermalPredictor()

        // Prune old drives if over limit
        scope.launch(Dispatchers.IO) {
            try {
                val maxDrives = AppSettings.getMaxSavedDrives(context)
                val count = dao.getDriveCount()
                if (count >= maxDrives) {
                    dao.deleteOldestDrives(count - maxDrives + 1)
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
                        hasGps = hasLocationPermission()
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
                        peakBoostPsi = 0.0, peakLateralG = 0.0,
                        peakEvents = emptyList(),
                        rtrAchievedPoint = null,
                        currentWeather = null
                    )
                }
                startRecordingLoop(driveId)
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
        scope.launch { flushBuffer() }
    }

    fun resumeDrive() {
        pausedAtMs = 0L
        prevPoint = null  // Gap in polyline — next point won't connect to prev
        _driveState.update { it.copy(isPaused = false) }
    }

    /**
     * Milliseconds elapsed since the drive was paused, or 0 when not paused.
     * Used by auto-record to decide whether a paused drive is recent enough
     * to resume or has gone stale and should be finalized.
     */
    fun pausedDurationMs(): Long =
        if (pausedAtMs > 0L) System.currentTimeMillis() - pausedAtMs else 0L

    fun stopDrive() {
        pausedAtMs = 0L
        val jobToJoin = recorderJob
        recorderJob?.cancel()
        recorderJob = null

        val state = _driveState.value
        val endVs = vehicleStateFlow.value
        scope.launch(Dispatchers.IO) {
            // Wait for the recording loop's finally{} to flush buffered points
            // before we write the drive summary. This avoids racing our own
            // flush against the loop's flush (both would copy pointsBuffer
            // before either cleared it, duplicating points) and guarantees
            // the summary reflects every recorded point.
            jobToJoin?.join()

            if (state.driveId > 0) {
                try {
                    val drive = dao.getDrive(state.driveId) ?: return@launch
                    val modeJson = JSONObject().apply {
                        state.modeCounts.forEach { (mode, count) ->
                            val total = state.modeCounts.values.sum()
                            if (total > 0) put(mode.label, count.toDouble() / total)
                        }
                    }.toString()
                    // Compute aggression score from session averages
                    val score = computeAggressionScore(state)

                    // Auto-name via reverse geocoder if no user name set
                    val autoName = if (drive.name == null) reverseGeocode(state) else null

                    // Snapshot fuel economy
                    val fuelState = FuelEconomy.state.value

                    // Snapshot thermal peaks + climb rates
                    val thermalJson = buildThermalPeaksJson()

                    val finalDrive = drive.copy(
                        endTime = System.currentTimeMillis(),
                        name = autoName ?: drive.name,
                        distanceKm = state.cumulativeDistanceKm,
                        avgSpeedKph = state.avgSpeedKph,
                        maxSpeedKph = state.maxSpeedKph,
                        peakRpm = state.peakRpm.toInt(),
                        peakBoostPsi = state.peakBoostPsi,
                        peakLateralG = state.peakLateralG,
                        fuelUsedL = state.fuelUsedL,
                        driveModeBreakdown = modeJson,
                        weatherSummary = state.currentWeather?.description,
                        startOilTempC = startOilTempC,
                        endOilTempC = endVs.oilTempC,
                        peakOilTempC = state.peakOilTempC,
                        startCoolantTempC = startCoolantTempC,
                        endCoolantTempC = endVs.coolantTempC,
                        peakCoolantTempC = state.peakCoolantTempC,
                        aggressionScore = score,
                        avgFuelL100km = if (fuelState.isValid) fuelState.avgL100km else 0.0,
                        avgFuelMpg = if (fuelState.isValid) fuelState.avgMpg else 0.0,
                        thermalPeaksJson = thermalJson
                    )
                    dao.updateDrive(finalDrive)

                    // Generate route thumbnail for history list
                    try {
                        val allPoints = dao.getPoints(state.driveId)
                        com.openrs.dash.ui.trip.RouteThumbnail.generateAndCache(context, state.driveId, allPoints)
                    } catch (e: Exception) {
                        Log.d(TAG, "Thumbnail generation failed", e)
                    }

                    // Persist lap timer results if any laps were completed
                    persistLaps(state.driveId)

                    _driveCompleted.tryEmit(finalDrive)
                    Log.d(TAG, "Drive ${state.driveId} ended (${state.totalPointCount} points)")
                } catch (e: Exception) {
                    Log.w(TAG, "Drive finalize failed", e)
                }
            }
        }
        _driveState.update {
            it.copy(isRecording = false, isPaused = false)
        }
    }

    fun cancel() {
        recorderJob?.cancel()
        scope.cancel()
    }

    // ── Internal recording loop ──────────────────────────────────────────────

    private fun startRecordingLoop(driveId: Long) {
        recorderJob = scope.launch {
            try {
                fetchInitialWeather()
                var lastWeatherMs = System.currentTimeMillis()

                locationFlow().collect { location ->
                    val state = _driveState.value
                    if (!state.isRecording) return@collect

                    // Always update current location (for live position dot even when paused)
                    _driveState.update { it.copy(currentLocation = location) }

                    // Skip recording when paused
                    if (state.isPaused) return@collect

                    val now = System.currentTimeMillis()

                    // Periodic weather refresh
                    if (now - lastWeatherMs >= WEATHER_REFRESH_MS) {
                        launch { refreshWeather(location.latitude, location.longitude) }
                        lastWeatherMs = now
                    }

                    val vs = vehicleStateFlow.value
                    val point = DrivePointEntity(
                        driveId        = driveId,
                        timestamp      = now,
                        lat            = location.latitude,
                        lng            = location.longitude,
                        speedKph       = vs.speedKph,
                        rpm            = vs.rpm.toInt(),
                        gear           = vs.gearDisplay,
                        boostPsi       = vs.boostPsi,
                        coolantTempC   = vs.coolantTempC,
                        oilTempC       = vs.oilTempC,
                        ambientTempC   = vs.ambientTempC,
                        rduTempC       = vs.rduTempC,
                        ptuTempC       = vs.ptuTempC,
                        fuelLevelPct   = vs.fuelLevelPct,
                        lateralG       = vs.lateralG,
                        throttlePct    = vs.throttlePct,
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
                        isRaceReady    = vs.isReadyToRace
                    )

                    // Feed thermal predictor for drive-end snapshot
                    driveThermalPredictor?.let { tp ->
                        tp.recordSample("Oil", now, vs.oilTempC)
                        tp.recordSample("Coolant", now, vs.coolantTempC)
                        tp.recordSample("RDU", now, vs.rduTempC)
                        tp.recordSample("PTU", now, vs.ptuTempC)
                    }

                    // Buffer point
                    pointsBuffer.add(point)

                    // Update recent points list for live UI
                    recentPointsList.add(point)
                    if (recentPointsList.size > MAX_RECENT_POINTS) {
                        recentPointsList.removeAt(0)
                    }

                    // Flush to Room when buffer is full
                    if (pointsBuffer.size >= FLUSH_SIZE) {
                        flushBuffer()
                    }

                    // Update live state
                    val prev = prevPoint
                    val segDist = if (prev != null)
                        DriveState.haversineKm(prev.lat, prev.lng, point.lat, point.lng)
                    else 0.0

                    val peaks = buildPeakEvents(state, vs, point, now)
                    val isRaceReady = vs.isReadyToRace
                    val rtrPt = state.rtrAchievedPoint ?: if (isRaceReady) point else null

                    _driveState.update { s ->
                        s.copy(
                            recentPoints         = recentPointsList.toList(),
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
                            peakBoostPsi         = maxOf(s.peakBoostPsi, vs.boostPsi),
                            peakLateralG         = maxOf(s.peakLateralG, abs(vs.lateralG)),
                            peakOilTempC         = if (vs.oilTempC > -90) maxOf(s.peakOilTempC, vs.oilTempC) else s.peakOilTempC,
                            peakCoolantTempC     = if (vs.coolantTempC > -90) maxOf(s.peakCoolantTempC, vs.coolantTempC) else s.peakCoolantTempC,
                            peakEvents           = peaks,
                            rtrAchievedPoint     = rtrPt
                        )
                    }
                    prevPoint = point
                }
            } finally {
                // Ensure we flush any remaining buffered points
                flushBuffer()
                _driveState.update { it.copy(isRecording = false) }
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
    private suspend fun flushBuffer() {
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
        point: DrivePointEntity,
        now: Long
    ): List<PeakEvent> {
        val peaks = state.peakEvents.toMutableList()
        if (vs.rpm > state.peakRpm) {
            peaks.removeAll { it.type == PeakType.RPM }
            peaks += PeakEvent(PeakType.RPM, vs.rpm, point.lat, point.lng, now)
        }
        if (vs.boostPsi > state.peakBoostPsi) {
            peaks.removeAll { it.type == PeakType.BOOST }
            peaks += PeakEvent(PeakType.BOOST, vs.boostPsi, point.lat, point.lng, now)
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

    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(): Location? =
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
    private fun computeAggressionScore(state: DriveState): Int {
        if (state.totalPointCount < 10) return 0
        val points = recentPointsList  // use in-memory cache
        if (points.isEmpty()) return 0

        val avgThrottle = points.sumOf { it.throttlePct } / points.size / 100.0
        val avgLatG = points.sumOf { abs(it.lateralG) } / points.size / 1.0
        val avgRpmFrac = points.sumOf { it.rpm.toDouble() } / points.size / 6800.0
        val avgBoostFrac = points.sumOf { it.boostPsi.coerceAtLeast(0.0) } / points.size / 22.0

        val raw = (avgThrottle * 0.30 + avgLatG * 0.25 + avgRpmFrac * 0.25 + avgBoostFrac * 0.20) * 100.0
        return min(100, raw.toInt().coerceAtLeast(0))
    }

    // ── Auto-naming ─────────────────────────────────────────────────────────

    /**
     * Reverse-geocodes the drive's first recorded point to produce an auto-name.
     * Returns null on failure (no network, no geocoder, etc.).
     */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(state: DriveState): String? {
        val points = state.recentPoints
        val first = points.firstOrNull() ?: return null
        return try {
            val geocoder = Geocoder(context)
            val results = geocoder.getFromLocation(first.lat, first.lng, 1)
            if (results.isNullOrEmpty()) return null
            val addr = results[0]
            // Prefer street name, then locality, then admin area
            addr.thoroughfare
                ?: addr.locality
                ?: addr.subAdminArea
                ?: addr.adminArea
        } catch (e: Exception) {
            Log.d(TAG, "Geocode failed for (${first.lat}, ${first.lng})", e)
            null
        }
    }

    // ── Lap persistence ────────────────────────────────────────────────────

    /** Flush completed laps from the UI-scoped [LapTimer] to Room. */
    private fun persistLaps(driveId: Long) {
        val timer = lapTimer ?: return
        val sf = timer.startFinish ?: return
        val laps = timer.laps
        if (laps.isEmpty()) return
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

    // ── Thermal peak snapshot ──────────────────────────────────────────────

    /** Build JSON blob of per-sensor peak temps + climb rates from the drive. */
    private fun buildThermalPeaksJson(): String? {
        val tp = driveThermalPredictor ?: return null
        val sensors = listOf(
            Triple("Oil", 140.0, 60.0),
            Triple("Coolant", 115.0, 60.0),
            Triple("RDU", 120.0, 30.0),
            Triple("PTU", 120.0, 30.0)
        )
        val arr = JSONArray()
        for ((label, crit, floor) in sensors) {
            val pred = tp.predict(label, crit, floor) ?: continue
            arr.put(JSONObject().apply {
                put("sensor", pred.sensorLabel)
                put("peakC", pred.currentC)
                put("climbRateCPerMin", pred.climbRateCPerMin)
                put("critThresholdC", pred.critThresholdC)
                pred.timeToCriticalMin?.let { put("timeToCriticalMin", it) }
            })
        }
        return if (arr.length() > 0) arr.toString() else null
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
                        boostPsi = vs.boostPsi
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
        private const val WEATHER_REFRESH_MS = 15 * 60_000L
        private const val FLUSH_SIZE = 30           // flush every ~30 seconds at 1 Hz
        private const val MAX_RECENT_POINTS = 3600  // ~1 hour of points for live polyline
    }
}
