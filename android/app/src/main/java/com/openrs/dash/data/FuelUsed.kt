package com.openrs.dash.data

/**
 * Fuel used from the fuel gauge (#336): the one rule for a drive's figure (DriveState) and DRIVE's session cell
 * (FuelEconomy). The level (0x380) moves in steps of [GAUGE_STEP_PCT] and is clamped to 0–100 %, so a few steps are gauge
 * noise, not fuel: one five-minute idle stepped 100 → 98.4 % (#336). A figure is given only once the level has dropped by
 * at least [MIN_DROP_STEPS] steps from a measurable start; until then there is none (0.0 where a figure is stored).
 * A start on the 100 % clamp, or before the level was received (0), is not measurable: the start moves to the first
 * reading below the clamp ([FuelSpan.next]). The fuel used while the gauge sat on the clamp is not counted (a stated
 * limit), and neither is the distance driven then (#517).
 */
object FuelUsed {
    /** One step of the fuel gauge, 0x380 (×0.4 %). */
    const val GAUGE_STEP_PCT = 0.4
    /** The smallest drop, in gauge steps, that gives a figure. */
    const val MIN_DROP_STEPS = 4

    /** A level a figure can be measured from: received (above 0) and below the 100 % clamp. */
    fun measurable(pct: Double): Boolean = pct > 0.0 && pct < 100.0

    /** Litres used from [startPct] to [nowPct] in a tank of [tankL], or null when there is no figure yet. */
    fun litres(startPct: Double, nowPct: Double, tankL: Double): Double? {
        if (!measurable(startPct) || nowPct <= 0.0) return null
        val drop = startPct - nowPct
        if (drop < MIN_DROP_STEPS * GAUGE_STEP_PCT - 1e-6) return null
        return drop / 100.0 * tankL
    }

    /**
     * The most fuel the car is taken to burn standing still (#517), beyond one gauge step of quantization: a faster drop
     * while parked is taken for the level settling (one cold start fell 7 steps in 48 s, standing still; the cause is a
     * hypothesis, #517), not fuel, and is not counted ([FuelSpan.next]). Idle on this engine is about 1 L/h; the bound leaves room for a cold, high idle.
     */
    const val MAX_STANDSTILL_L_PER_HR = 4.0
}

/**
 * The span fuel used and the average economy are measured over (#517): DRIVE's session (FuelEconomy) and a drive
 * (DriveState) each keep one. The average divides the fuel used since [basePct] by the distance driven since
 * [baseDistanceKm], the distance when that level was taken; the fuel start can move off the 100 % clamp, or wait for the
 * first reading, long after the distance began, and dividing by the whole distance read about 35 % low on a full tank.
 * While the car stands still a drop beyond [FuelUsed.MAX_STANDSTILL_L_PER_HR] (after one gauge step) moves [basePct] down
 * by the excess, so it is not counted.
 */
data class FuelSpan(
    /** The level fuel used is measured from; until it is [FuelUsed.measurable] there is no figure. */
    val basePct: Double = -1.0,
    /** The distance driven, on the caller's odometer, when [basePct] was taken. */
    val baseDistanceKm: Double = 0.0,
    /** The level when the current standstill began, -1 while moving (or before a measurable start). */
    val standPct: Double = -1.0,
    /** When the current standstill began. */
    val standStartMs: Long = 0L,
) {
    /** The span after a reading of [readingPct] at [distanceKm] and [nowMs], the car [standing] still or not. */
    fun next(readingPct: Double, distanceKm: Double, nowMs: Long, standing: Boolean, tankL: Double): FuelSpan {
        if (!FuelUsed.measurable(basePct)) {
            if (!FuelUsed.measurable(readingPct)) return this
            return FuelSpan(readingPct, distanceKm, if (standing) readingPct else -1.0, nowMs)
        }
        if (readingPct <= 0.0) return this                                   // not received: no reading to judge
        if (!standing) return if (standPct < 0.0) this else copy(standPct = -1.0)
        if (standPct < 0.0) return copy(standPct = readingPct, standStartMs = nowMs)
        val hours = (nowMs - standStartMs).coerceAtLeast(0L) / 3_600_000.0
        val allowedPct = FuelUsed.GAUGE_STEP_PCT + FuelUsed.MAX_STANDSTILL_L_PER_HR * hours / tankL * 100.0
        val excess = standPct - readingPct - allowedPct
        if (excess <= 1e-9) return this
        return copy(basePct = basePct - excess, standPct = standPct - excess)
    }

    /** Litres used from [basePct] to [nowPct], or null when there is no figure yet ([FuelUsed.litres]). */
    fun litres(nowPct: Double, tankL: Double): Double? = FuelUsed.litres(basePct, nowPct, tankL)

    /** The distance driven since [basePct] was taken, at [distanceKm] on the same odometer; 0 before a measurable start. */
    fun distanceKm(distanceKm: Double): Double =
        if (FuelUsed.measurable(basePct)) (distanceKm - baseDistanceKm).coerceAtLeast(0.0) else 0.0

    companion object {
        /** A span from a start level [startPct] read at distance 0 and [atMs] (a drive's START reading). */
        fun start(startPct: Double): FuelSpan = FuelSpan(basePct = startPct)
    }
}
