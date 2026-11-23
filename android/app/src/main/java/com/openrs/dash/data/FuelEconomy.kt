package com.openrs.dash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time fuel economy calculator.
 *
 * Uses a 60-second rolling window of fuel level + distance samples to compute:
 * - Instant fuel economy (L/100km or MPG)
 * - Session average fuel economy
 * - Distance to empty (DTE)
 *
 * Fuel level from CAN 0x380 has 0.4% resolution (~0.2L steps), so short windows
 * are noisy. The 60-second window provides stable readings.
 */
object FuelEconomy {

    data class EconomyState(
        val instantL100km: Double = 0.0,
        val instantMpg: Double = 0.0,
        val avgL100km: Double = 0.0,
        val avgMpg: Double = 0.0,
        val distanceToEmptyKm: Double = 0.0,
        val fuelUsedL: Double = 0.0,
        val sessionDistanceKm: Double = 0.0,
        val idleFuelLPerHr: Double = 0.0,
        val isValid: Boolean = false
    )

    private val _state = MutableStateFlow(EconomyState())
    val state = _state.asStateFlow()

    private const val TANK_CAPACITY_L = 49.8
    private const val WINDOW_MS = 60_000L  // 60-second rolling window
    private const val L100KM_TO_MPG = 282.48  // conversion factor

    // Rolling window of (timestamp, fuelPct, cumulativeDistanceKm)
    private data class Sample(val timeMs: Long, val fuelPct: Double, val distKm: Double)
    private val samples = mutableListOf<Sample>()

    // Every field below (and the sample window above) is guarded by [lock]: onUpdate runs on the CAN-processing or
    // demo-tick thread while reset() runs on the main thread (demo scenario switch / reset) or the service's
    // connection path. Unguarded, a reset between onUpdate's size check and samples.first() threw
    // NoSuchElementException (demo crash, 2026-10-24), and an in-flight update could republish the old session
    // after a reset. Both functions hold the lock for their whole body; nothing else touches this state.
    private val lock = Any()

    // Session tracking
    /** What the session's fuel used and average are measured over (#517): the fuel start and the distance at it. */
    private var span = FuelSpan()
    private var sessionDistanceKm: Double = 0.0
    private var lastSpeedKph: Double = 0.0
    private var lastUpdateMs: Long = 0L

    fun reset() = synchronized(lock) {
        samples.clear()
        span = FuelSpan()
        sessionDistanceKm = 0.0
        lastSpeedKph = 0.0
        lastUpdateMs = 0L
        _state.value = EconomyState()
    }

    /**
     * Called on every VehicleState update with current fuel level and speed; [nowMs] is the update's time (wall clock;
     * tests pass their own).
     */
    fun onUpdate(fuelPct: Double, speedKph: Double, nowMs: Long = System.currentTimeMillis()): Unit = synchronized(lock) {
        if (fuelPct < 0 || fuelPct > 110) return  // invalid fuel reading

        val now = nowMs

        // Integrate distance from speed (trapezoidal)
        if (lastUpdateMs > 0 && now > lastUpdateMs) {
            val dtHrs = (now - lastUpdateMs) / 3_600_000.0
            val avgSpeed = (speedKph + lastSpeedKph) / 2.0
            sessionDistanceKm += avgSpeed * dtHrs
        }
        lastSpeedKph = speedKph
        lastUpdateMs = now

        // The fuel start: the first measurable level, moved off the 100 % clamp to the first reading below it (#336), with
        // the distance at it (#517); a drop at a standstill faster than the car burns is not counted (IDLE's speed rule)
        span = span.next(fuelPct, sessionDistanceKm, now, standing = speedKph < 2.0, tankL = TANK_CAPACITY_L)

        // Add sample to rolling window
        samples.add(Sample(now, fuelPct, sessionDistanceKm))

        // Prune old samples
        samples.removeAll { now - it.timeMs > WINDOW_MS }

        if (samples.size < 2) return

        // ── Instant economy (rolling window) ─────────────────────────────
        val oldest = samples.first()
        val newest = samples.last()
        val windowDeltaFuelPct = oldest.fuelPct - newest.fuelPct
        val windowDeltaDistKm = newest.distKm - oldest.distKm
        val windowFuelUsedL = windowDeltaFuelPct / 100.0 * TANK_CAPACITY_L

        var instantL100 = 0.0
        var instantMpg = 0.0
        var idleLPerHr = 0.0

        if (speedKph < 2.0 && windowFuelUsedL > 0.001) {
            // At idle: show L/hr instead
            val windowHrs = (newest.timeMs - oldest.timeMs) / 3_600_000.0
            if (windowHrs > 0) idleLPerHr = windowFuelUsedL / windowHrs
        } else if (windowDeltaDistKm > 0.01 && windowFuelUsedL > 0.001) {
            instantL100 = (windowFuelUsedL / windowDeltaDistKm) * 100.0
            instantMpg = if (instantL100 > 0) L100KM_TO_MPG / instantL100 else 0.0
        }

        // ── Session average ──────────────────────────────────────────────
        // Only from a fuel-used figure (#336, owner Q165): under four gauge steps there is none, and no average either
        // (0.0, the existing "no average"); the stored drive average and DRIVE's AVERAGE read this. The fuel and the
        // distance cover one span, from the fuel start (#517): not the distance since the connection began
        val sessionFuelUsedL = span.litres(fuelPct, TANK_CAPACITY_L) ?: 0.0
        val spanDistanceKm = span.distanceKm(sessionDistanceKm)
        var avgL100 = 0.0
        var avgMpg = 0.0
        if (spanDistanceKm > 0.1 && sessionFuelUsedL > 0.01) {
            avgL100 = (sessionFuelUsedL / spanDistanceKm) * 100.0
            avgMpg = if (avgL100 > 0) L100KM_TO_MPG / avgL100 else 0.0
        }

        // ── Distance to empty ────────────────────────────────────────────
        val remainingFuelL = fuelPct / 100.0 * TANK_CAPACITY_L
        val dte = if (avgL100 > 0.5) (remainingFuelL / avgL100) * 100.0
                  else remainingFuelL / 15.0 * 100.0  // conservative fallback: 15 L/100km

        _state.value = EconomyState(
            instantL100km = instantL100.coerceIn(0.0, 99.9),
            instantMpg = instantMpg.coerceIn(0.0, 999.0),
            avgL100km = avgL100.coerceIn(0.0, 99.9),
            avgMpg = avgMpg.coerceIn(0.0, 999.0),
            distanceToEmptyKm = dte.coerceIn(0.0, 9999.0),
            fuelUsedL = sessionFuelUsedL,   // #336: no figure yet = 0
            sessionDistanceKm = sessionDistanceKm,
            idleFuelLPerHr = idleLPerHr.coerceIn(0.0, 20.0),
            isValid = sessionDistanceKm > 0.1 || (now - samples.first().timeMs > 10_000)
        )
    }
}
