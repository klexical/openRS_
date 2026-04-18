package com.openrs.dash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * 0-60 / 0-100 mph performance timer using CAN speed (0x130, ~100 Hz).
 *
 * State machine: IDLE → ARMED → RUNNING → FINISHED
 *
 * CAN speed is more accurate than GPS-based timers (Dragy) because it's
 * filtered by the ABS module and updates at ~100 Hz vs 10 Hz GPS.
 *
 * Usage: call [arm] from UI, then feed speed updates via [onSpeedUpdate].
 * The timer auto-starts on first movement and auto-finishes at 100 mph
 * (or user can manually finish at 60 via [finishAt60]).
 */
object PerformanceTimer {

    enum class State { IDLE, ARMED, RUNNING, FINISHED }

    data class TimerResult(
        val zeroTo60Ms: Long,
        val zeroTo100Ms: Long? = null,
        val peakRpm: Double = 0.0,
        val peakBoostPsi: Double = 0.0,
        val launchRpm: Double = 0.0
    )

    private val _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()

    private val _result = MutableStateFlow<TimerResult?>(null)
    val result = _result.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs = _elapsedMs.asStateFlow()

    private val _currentSpeedMph = MutableStateFlow(0.0)
    val currentSpeedMph = _currentSpeedMph.asStateFlow()

    // Session bests
    private val _best60Ms = MutableStateFlow<Long?>(null)
    val best60Ms = _best60Ms.asStateFlow()

    private val _best100Ms = MutableStateFlow<Long?>(null)
    val best100Ms = _best100Ms.asStateFlow()

    // Internal run state — @Volatile for memory visibility across CAN
    // processing thread (onSpeedUpdate) and Main thread (arm/cancel).
    @Volatile private var startNanos = 0L
    @Volatile private var trackLaunchRpm = 0.0
    @Volatile private var trackPeakRpm = 0.0
    @Volatile private var trackPeakBoost = 0.0
    @Volatile private var split60Captured = false
    @Volatile private var split60Ms: Long = 0

    private const val SPEED_60_MPH_KPH = 96.5606
    private const val SPEED_100_MPH_KPH = 160.934
    private const val ARM_MAX_SPEED_KPH = 5.0
    private const val START_THRESHOLD_KPH = 3.0

    fun arm(): Boolean {
        if (_state.value != State.IDLE && _state.value != State.FINISHED) return false
        _result.value = null
        _elapsedMs.value = 0
        startNanos = 0
        trackLaunchRpm = 0.0
        trackPeakRpm = 0.0
        trackPeakBoost = 0.0
        split60Captured = false
        split60Ms = 0
        _state.value = State.ARMED
        return true
    }

    fun cancel() {
        _state.value = State.IDLE
        _elapsedMs.value = 0
    }

    /**
     * Called on every CAN speed update (~100 Hz from 0x130).
     *
     * @return pair of (sixty_split_just_captured, hundred_split_just_captured)
     *         for haptic feedback in the UI.
     */
    fun onSpeedUpdate(speedKph: Double, rpm: Double, boostPsi: Double): Pair<Boolean, Boolean> {
        _currentSpeedMph.value = speedKph * 0.621371

        return when (_state.value) {
            State.ARMED -> {
                if (speedKph > ARM_MAX_SPEED_KPH) {
                    // Moving too fast to start a clean run — disarm
                    cancel()
                    return false to false
                }
                if (speedKph >= START_THRESHOLD_KPH) {
                    startNanos = System.nanoTime()
                    trackLaunchRpm = rpm
                    trackPeakRpm = rpm
                    trackPeakBoost = boostPsi
                    _state.value = State.RUNNING
                }
                false to false
            }
            State.RUNNING -> {
                val elapsed = (System.nanoTime() - startNanos) / 1_000_000
                _elapsedMs.value = elapsed
                trackPeakRpm = max(trackPeakRpm, rpm)
                trackPeakBoost = max(trackPeakBoost, boostPsi)

                var sixty = false
                var hundred = false

                // 0-60 split
                if (!split60Captured && speedKph >= SPEED_60_MPH_KPH) {
                    split60Captured = true
                    split60Ms = elapsed
                    sixty = true
                    val cur = _best60Ms.value
                    if (cur == null || elapsed < cur) _best60Ms.value = elapsed
                }

                // 0-100 finish
                if (speedKph >= SPEED_100_MPH_KPH) {
                    hundred = true
                    val cur = _best100Ms.value
                    if (cur == null || elapsed < cur) _best100Ms.value = elapsed
                    _result.value = TimerResult(
                        zeroTo60Ms = split60Ms,
                        zeroTo100Ms = elapsed,
                        peakRpm = trackPeakRpm,
                        peakBoostPsi = trackPeakBoost,
                        launchRpm = trackLaunchRpm
                    )
                    _state.value = State.FINISHED
                }

                sixty to hundred
            }
            else -> false to false
        }
    }

    /** Manually finish at the 0-60 mark (user doesn't want to continue to 100). */
    fun finishAt60() {
        if (_state.value != State.RUNNING || !split60Captured) return
        _result.value = TimerResult(
            zeroTo60Ms = split60Ms,
            peakRpm = trackPeakRpm,
            peakBoostPsi = trackPeakBoost,
            launchRpm = trackLaunchRpm
        )
        _state.value = State.FINISHED
    }

    /** Reset session bests (called on adapter disconnect). */
    fun resetSession() {
        cancel()
        _result.value = null
        _best60Ms.value = null
        _best100Ms.value = null
    }
}
