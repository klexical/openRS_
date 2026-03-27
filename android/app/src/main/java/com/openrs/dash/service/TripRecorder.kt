package com.openrs.dash.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.PeakEvent
import com.openrs.dash.data.PeakType
import com.openrs.dash.data.TripPoint
import com.openrs.dash.data.TripState
import com.openrs.dash.data.VehicleState
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
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Records GPS waypoints fused with live [VehicleState] telemetry at ~1 Hz (GPS update rate).
 *
 * Architecture:
 *  - Lives on [OpenRSDashApp] as a lazy singleton; shares the app's [vehicleStateFlow].
 *  - Has its own [CoroutineScope] so it is independent of [CanDataService] lifecycle.
 *  - [locationFlow] uses [FusedLocationProviderClient] at 1-second intervals.
 *  - On each GPS fix, snapshots [vehicleStateFlow].value — no combine() needed since
 *    vehicle state updates at ~2100 fps and we only want one point per GPS fix.
 *  - Weather is fetched at trip start and refreshed every [WEATHER_REFRESH_MS].
 */
class TripRecorder(
    private val context: Context,
    private val vehicleStateFlow: StateFlow<VehicleState>,
    private val weatherRepo: WeatherRepository
) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    private val _tripState = MutableStateFlow(TripState())
    val tripState: StateFlow<TripState> = _tripState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorderJob: Job? = null

    /** Internal buffer so GPS appends are O(1) amortized; copied to TripState on each GPS fix. */
    @Volatile private var pointsBuffer = ArrayList<TripPoint>(3600)

    fun startTrip() {
        if (recorderJob?.isActive == true) return

        _tripState.update { current ->
            if (current.isRecording) return@update current
            val vs = vehicleStateFlow.value
            pointsBuffer = ArrayList(3600)
            TripState(
                isRecording  = true,
                startFuelPct = vs.fuelLevelPct,
                startTime    = System.currentTimeMillis()
            )
        }
        if (!_tripState.value.isRecording) return
        recorderJob = scope.launch {
            try {
                fetchInitialWeather()
                var lastWeatherMs = System.currentTimeMillis()

                locationFlow().collect { location ->
                    if (!_tripState.value.isRecording) return@collect
                    val now = System.currentTimeMillis()

                    if (now - lastWeatherMs >= WEATHER_REFRESH_MS) {
                        launch { refreshWeather(location.latitude, location.longitude) }
                        lastWeatherMs = now
                    }

                    val vs = vehicleStateFlow.value
                    val isRaceReady = vs.isReadyToRace
                    val point = TripPoint(
                        lat          = location.latitude,
                        lng          = location.longitude,
                        timestamp    = now,
                        speedKph     = vs.speedKph,
                        rpm          = vs.rpm,
                        gear         = vs.gearDisplay,
                        boostPsi     = vs.boostPsi,
                        coolantTempC = vs.coolantTempC,
                        oilTempC     = vs.oilTempC,
                        ambientTempC = vs.ambientTempC,
                        rduTempC     = vs.rduTempC,
                        ptuTempC     = vs.ptuTempC,
                        fuelLevelPct = vs.fuelLevelPct,
                        wheelSpeedFL = vs.wheelSpeedFL,
                        wheelSpeedFR = vs.wheelSpeedFR,
                        wheelSpeedRL = vs.wheelSpeedRL,
                        wheelSpeedRR = vs.wheelSpeedRR,
                        lateralG     = vs.lateralG,
                        driveMode    = vs.driveMode,
                        isRaceReady  = isRaceReady
                    )
                    pointsBuffer.add(point)

                    _tripState.update { state ->
                        val prev    = if (pointsBuffer.size >= 2) pointsBuffer[pointsBuffer.size - 2] else null
                        val segDist = if (prev != null)
                            TripState.haversineKm(prev.lat, prev.lng, point.lat, point.lng)
                        else 0.0

                        val newRpmSamples   = if (vs.rpm > 400) state.rpmSamples + 1 else state.rpmSamples
                        val newRpmSum       = if (vs.rpm > 400) state.rpmSum + vs.rpm else state.rpmSum
                        val newSpeedSum     = state.speedSum + vs.speedKph
                        val newSpeedSamples = state.speedSamples + 1
                        val newModeCounts   = state.modeCounts.toMutableMap().also {
                            it[vs.driveMode] = (it[vs.driveMode] ?: 0) + 1
                        }

                        val peaks = buildPeakEvents(state, vs, point, now)
                        val rtrPt = state.rtrAchievedPoint ?: if (isRaceReady) point else null

                        state.copy(
                            points                = pointsBuffer.toList(),
                            cumulativeDistanceKm  = state.cumulativeDistanceKm + segDist,
                            rpmSum                = newRpmSum,
                            rpmSamples            = newRpmSamples,
                            speedSum              = newSpeedSum,
                            speedSamples          = newSpeedSamples,
                            modeCounts            = newModeCounts,
                            maxSpeedKph           = maxOf(state.maxSpeedKph, vs.speedKph),
                            peakRpm               = maxOf(state.peakRpm, vs.rpm),
                            peakBoostPsi          = maxOf(state.peakBoostPsi, vs.boostPsi),
                            peakLateralG          = maxOf(state.peakLateralG, abs(vs.lateralG)),
                            peakEvents            = peaks,
                            rtrAchievedPoint      = rtrPt
                        )
                    }
                }
            } finally {
                _tripState.update { it.copy(isRecording = false) }
            }
        }
    }

    fun stopTrip() {
        recorderJob?.cancel()
        recorderJob = null
        _tripState.update { it.copy(isRecording = false) }
    }

    fun resetTrip() {
        stopTrip()
        // Replace the reference atomically — the cancelled job may still hold the old
        // reference and add to it momentarily, but that list is simply orphaned (no clear race).
        pointsBuffer = ArrayList(3600)
        _tripState.value = TripState()
    }

    fun cancel() {
        recorderJob?.cancel()
        scope.cancel()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun fetchInitialWeather() {
        try {
            val loc = getLastKnownLocation() ?: return
            weatherRepo.fetchWeather(loc.latitude, loc.longitude)?.let { weather ->
                _tripState.update { it.copy(currentWeather = weather) }
            }
        } catch (_: Exception) {}
    }

    private suspend fun refreshWeather(lat: Double, lon: Double) {
        try {
            weatherRepo.fetchWeather(lat, lon)?.let { weather ->
                _tripState.update { it.copy(currentWeather = weather) }
            }
        } catch (_: Exception) {}
    }

    /**
     * Rebuilds the peak events list. Each peak type retains only its current best position —
     * the pin moves to wherever the latest peak occurred rather than accumulating duplicates.
     */
    private fun buildPeakEvents(
        state: TripState,
        vs: VehicleState,
        point: TripPoint,
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
        return peaks
    }

    @SuppressLint("MissingPermission")
    private fun locationFlow(): Flow<Location> = callbackFlow {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
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
    private suspend fun getLastKnownLocation(): Location? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPermission) { cont.resume(null); return@suspendCancellableCoroutine }

                fusedClient.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }

    companion object {
        private const val WEATHER_REFRESH_MS = 15 * 60_000L
    }
}
