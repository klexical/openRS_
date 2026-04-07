package com.openrs.dash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 0-60 mph / 0-100 kph performance timer using CAN speed data (100 Hz).
 *
 * States: IDLE → ARMED → RUNNING → FINISHED
 * - IDLE: waiting for user to arm
 * - ARMED: waiting for speed to increase from standstill
 * - RUNNING: timing in progress, speed increasing
 * - FINISHED: target speed reached, result stored
 */
object PerformanceTimer {

    enum class State { IDLE, ARMED, RUNNING, FINISHED }
    enum class Target(val label: String, val kph: Double) {
        ZERO_TO_60("0-60 mph", 96.56),
        ZERO_TO_100("0-100 kph", 100.0)
    }

    data class TimerState(
        val state: State = State.IDLE,
        val target: Target = Target.ZERO_TO_60,
        val startTimeMs: Long = 0L,
        val elapsedMs: Long = 0L,
        val launchRpm: Double = 0.0,
        val peakBoostPsi: Double = 0.0,
        val resultMs: Long = 0L,
        val bestResultMs: Long = 0L
    )

    private val _state = MutableStateFlow(TimerState())
    val state = _state.asStateFlow()

    private const val STANDSTILL_KPH = 2.0  // below this = stopped

    fun arm(target: Target = Target.ZERO_TO_60) {
        _state.value = TimerState(state = State.ARMED, target = target, bestResultMs = _state.value.bestResultMs)
    }

    fun reset() {
        _state.value = TimerState(bestResultMs = _state.value.bestResultMs)
    }

    fun clearBest() {
        _state.value = TimerState()
    }

    /**
     * Called on every VehicleState update with current speed, RPM, and boost.
     * Speed is CAN 0x130 at ~100 Hz for sub-100ms precision.
     */
    fun onSpeedUpdate(speedKph: Double, rpm: Double, boostPsi: Double) {
        val current = _state.value
        val now = System.currentTimeMillis()

        when (current.state) {
            State.ARMED -> {
                if (speedKph > STANDSTILL_KPH) {
                    // Speed started increasing — begin timing
                    _state.value = current.copy(
                        state = State.RUNNING,
                        startTimeMs = now,
                        launchRpm = rpm,
                        peakBoostPsi = boostPsi
                    )
                }
            }
            State.RUNNING -> {
                val elapsed = now - current.startTimeMs
                val peakBoost = maxOf(current.peakBoostPsi, boostPsi)

                if (speedKph >= current.target.kph) {
                    // Target reached
                    val best = if (current.bestResultMs <= 0L) elapsed
                               else minOf(current.bestResultMs, elapsed)
                    _state.value = current.copy(
                        state = State.FINISHED,
                        elapsedMs = elapsed,
                        resultMs = elapsed,
                        peakBoostPsi = peakBoost,
                        bestResultMs = best
                    )
                } else {
                    _state.value = current.copy(
                        elapsedMs = elapsed,
                        peakBoostPsi = peakBoost
                    )
                }

                // Abort if speed drops back to zero (false start / stall)
                if (speedKph < STANDSTILL_KPH && elapsed > 1000) {
                    _state.value = current.copy(state = State.IDLE, elapsedMs = 0)
                }
            }
            else -> { /* IDLE / FINISHED — no action */ }
        }
    }
}
