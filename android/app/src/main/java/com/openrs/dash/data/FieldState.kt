package com.openrs.dash.data

/**
 * Availability state for a single telemetry field.
 *
 * Polled fields (Mode 22 DIDs, catalog PIDs) can be in multiple distinct
 * non-value states that `— —` placeholders historically conflated:
 *   * Never attempted                     → [NotPolled]
 *   * Poll scheduled but no response yet  → [Warming]
 *   * Fresh data                          → [Available]
 *   * Stale data (missed poll cycles)     → [Stale]
 *   * Confirmed not present on this car   → [Unavailable]
 *
 * The UI renders each state distinctly so the user can tell whether the
 * app is working and the car is quiet vs. the app is broken.
 */
sealed class FieldState<out T> {
    object NotPolled : FieldState<Nothing>()
    data class Warming(val etaSeconds: Int? = null) : FieldState<Nothing>()
    data class Available<T>(val value: T, val lastUpdateMs: Long) : FieldState<T>()
    data class Stale<T>(val value: T, val ageSeconds: Int) : FieldState<T>()
    object Unavailable : FieldState<Nothing>()
}

/**
 * Classify a raw telemetry value into a [FieldState].
 *
 * @param value          current value, or null if sentinel-absent
 * @param lastUpdateMs   timestamp from [VehicleState.fieldLastUpdateMs], or null
 * @param nowMs          reference time (injectable for tests)
 * @param pollIntervalMs expected poll cycle for this field (ms)
 * @param staleFactor    how many poll intervals before a field is declared stale
 * @param expected       false if this field is known not to exist on this vehicle
 */
fun <T> fieldState(
    value: T?,
    lastUpdateMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
    pollIntervalMs: Long = 2_000L,
    staleFactor: Int = 2,
    expected: Boolean = true,
): FieldState<T> {
    if (!expected) return FieldState.Unavailable
    if (value == null || lastUpdateMs == null || lastUpdateMs <= 0L) {
        return FieldState.NotPolled
    }
    val age = nowMs - lastUpdateMs
    val stalenessThreshold = pollIntervalMs * staleFactor
    return if (age <= stalenessThreshold) {
        FieldState.Available(value, lastUpdateMs)
    } else {
        FieldState.Stale(value, (age / 1000L).toInt())
    }
}
