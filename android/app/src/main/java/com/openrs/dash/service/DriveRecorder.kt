package com.openrs.dash.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.PeakEvent
import com.openrs.dash.data.PeakType
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorderJob: Job? = null

    /** Buffer for batch writes — flushed to Room every FLUSH_INTERVAL points. */
    @Volatile private var pointsBuffer = ArrayList<DrivePointEntity>(FLUSH_SIZE * 2)

    /** Recent points kept in memory for live polyline rendering. */
    @Volatile private var recentPointsList = ArrayList<DrivePointEntity>(MAX_RECENT_POINTS)

    /** Previous point for distance calculation. */
    @Volatile private var prevPoint: DrivePointEntity? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Start recording a new drive. Creates a DriveEntity in Room immediately.
     * If [sessionId] is provided, links this drive to a diagnostic session.
     */
    fun startDrive(sessionId: Long = 0) {
        if (recorderJob?.isActive == true) return

        val vs = vehicleStateFlow.value
        pointsBuffer = ArrayList(FLUSH_SIZE * 2)
        recentPointsList = ArrayList(MAX_RECENT_POINTS)
        prevPoint = null

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
        _driveState.update { it.copy(isPaused = true) }
        // Flush any buffered points immediately
        flushBuffer()
    }

    fun resumeDrive() {
        prevPoint = null  // Gap in polyline — next point won't connect to prev
        _driveState.update { it.copy(isPaused = false) }
    }

    fun stopDrive() {
        recorderJob?.cancel()
        recorderJob = null
        flushBuffer()

        val state = _driveState.value
        if (state.driveId > 0) {
            scope.launch(Dispatchers.IO) {
                try {
                    val drive = dao.getDrive(state.driveId) ?: return@launch
                    val modeJson = JSONObject().apply {
                        state.modeCounts.forEach { (mode, count) ->
                            val total = state.modeCounts.values.sum()
                            if (total > 0) put(mode.label, count.toDouble() / total)
                        }
                    }.toString()
                    dao.updateDrive(
                        drive.copy(
                            endTime = System.currentTimeMillis(),
                            distanceKm = state.cumulativeDistanceKm,
                            avgSpeedKph = state.avgSpeedKph,
                            maxSpeedKph = state.maxSpeedKph,
                            peakRpm = state.peakRpm.toInt(),
                            peakBoostPsi = state.peakBoostPsi,
                            peakLateralG = state.peakLateralG,
                            fuelUsedL = state.fuelUsedL,
                            driveModeBreakdown = modeJson,
                            weatherSummary = state.currentWeather?.description
                        )
                    )
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
                        driveId      = driveId,
                        timestamp    = now,
                        lat          = location.latitude,
                        lng          = location.longitude,
                        speedKph     = vs.speedKph,
                        rpm          = vs.rpm.toInt(),
                        gear         = vs.gearDisplay,
                        boostPsi     = vs.boostPsi,
                        coolantTempC = vs.coolantTempC,
                        oilTempC     = vs.oilTempC,
                        ambientTempC = vs.ambientTempC,
                        rduTempC     = vs.rduTempC,
                        ptuTempC     = vs.ptuTempC,
                        fuelLevelPct = vs.fuelLevelPct,
                        lateralG     = vs.lateralG,
                        throttlePct  = vs.throttlePct,
                        driveMode    = vs.driveMode.label,
                        wheelSpeedFL = vs.wheelSpeedFL,
                        wheelSpeedFR = vs.wheelSpeedFR,
                        wheelSpeedRL = vs.wheelSpeedRL,
                        wheelSpeedRR = vs.wheelSpeedRR,
                        tirePressLF  = vs.tirePressLF,
                        tirePressRF  = vs.tirePressRF,
                        tirePressLR  = vs.tirePressLR,
                        tirePressRR  = vs.tirePressRR,
                        tireTempLF   = vs.tireTempLF,
                        tireTempRF   = vs.tireTempRF,
                        tireTempLR   = vs.tireTempLR,
                        tireTempRR   = vs.tireTempRR,
                        isRaceReady  = vs.isReadyToRace
                    )

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

    private fun flushBuffer() {
        val toFlush = ArrayList(pointsBuffer)
        pointsBuffer.clear()
        if (toFlush.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                dao.insertPoints(toFlush)
            } catch (e: Exception) {
                Log.w(TAG, "Point flush failed (${toFlush.size} points)", e)
            }
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

    companion object {
        private const val TAG = "DriveRecorder"
        private const val WEATHER_REFRESH_MS = 15 * 60_000L
        private const val FLUSH_SIZE = 30           // flush every ~30 seconds at 1 Hz
        private const val MAX_RECENT_POINTS = 3600  // ~1 hour of points for live polyline
    }
}
