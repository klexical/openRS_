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
     * hypothesis, #517), not fuel, and is not counted ([FuelSpan.next]). Idle on this engine is about 1 L/h; the bound
     * leaves room for a cold, high idle.
     */
    const val MAX_STANDSTILL_L_PER_HR = 4.0
}

/**
 * The span fuel used and the average economy are measured over (#517): DRIVE's session (FuelEconomy) and a drive
 * (DriveState) each keep one. The average divides the fuel used since [basePct] by the distance driven since
 * [baseDistanceKm], the distance when that level was taken; the fuel start can move off the 100 % clamp, or wait for the
 * first reading, long after the distance began, and dividing by the whole distance read about 35 % low on a full tank.
 *
 * While the car stands still, the drop counted in one standstill is held to one gauge step plus
 * [FuelUsed.MAX_STANDSTILL_L_PER_HR] over its time; the rest of the drop is set aside ([setAsidePct]), not counted. It is
 * given back as the level comes back up past the level it was set aside at ([setAsideAtPct]), beyond one gauge step: a dip
 * that recovers was never fuel (80 → 78.8 → 80 % at a standstill, then 2 % driven, must give 2 %), while the gauge's
 * one-step jitter gives nothing back (six one-step rises and falls while moving after a settled drop gave 1.39 L when each
 * rise gave back what it rose). The give-back follows the highest level since ([peakSincePct]), so repeated jitter never
 * adds to it.
 */
data class FuelSpan(
    /** The level fuel used is measured from; until it is [FuelUsed.measurable] there is no figure. */
    val basePct: Double = -1.0,
    /** The distance driven, on the caller's odometer, when [basePct] was taken. */
    val baseDistanceKm: Double = 0.0,
    /** The previous reading judged, -1 before one. */
    val lastPct: Double = -1.0,
    /** When the current standstill began, -1 while moving. */
    val standStartMs: Long = -1L,
    /** The drop counted in the current standstill. */
    val standCountedPct: Double = 0.0,
    /** The drop set aside as faster than the car burns standing still, before any give-back. */
    val setAsidePct: Double = 0.0,
    /** The level the drop was last set aside at, -1 before any. */
    val setAsideAtPct: Double = -1.0,
    /** The highest level read since [setAsideAtPct]. */
    val peakSincePct: Double = -1.0,
) {
    /** The part of [setAsidePct] still set aside: less the rise of [peakSincePct] above [setAsideAtPct], beyond one step. */
    val heldBackPct: Double
        get() = if (setAsideAtPct < 0.0) 0.0
                else setAsidePct - (peakSincePct - setAsideAtPct - FuelUsed.GAUGE_STEP_PCT).coerceIn(0.0, setAsidePct)

    /** The span after a reading of [readingPct] at [distanceKm] and [nowMs], the car [standing] still or not. */
    fun next(readingPct: Double, distanceKm: Double, nowMs: Long, standing: Boolean, tankL: Double): FuelSpan {
        if (!FuelUsed.measurable(basePct)) {
            if (!FuelUsed.measurable(readingPct)) return this
            return FuelSpan(readingPct, distanceKm, lastPct = readingPct, standStartMs = if (standing) nowMs else -1L)
        }
        if (readingPct <= 0.0) return this                                   // not received: no reading to judge
        val drop = (if (lastPct > 0.0) lastPct else basePct) - readingPct
        val start = if (!standing) -1L else if (standStartMs >= 0L) standStartMs else nowMs
        var counted = if (start == standStartMs) standCountedPct else 0.0    // a new standstill, or moving
        var next = copy(lastPct = readingPct, standStartMs = start,
            peakSincePct = if (setAsideAtPct < 0.0) peakSincePct else maxOf(peakSincePct, readingPct))
        if (drop < -EPS) {
            counted = (counted - -drop).coerceAtLeast(0.0)                   // a rise frees the standstill's allowance
        } else if (drop > EPS && standing) {
            val hours = (nowMs - start).coerceAtLeast(0L) / 3_600_000.0
            val allowedPct = FuelUsed.GAUGE_STEP_PCT + FuelUsed.MAX_STANDSTILL_L_PER_HR * hours / tankL * 100.0
            val take = minOf(drop, (allowedPct - counted).coerceAtLeast(0.0))
            counted += take
            if (drop - take > EPS) {
                // Keep what was given back so far, add the new excess, and measure the give-back from this level on
                next = next.copy(setAsidePct = next.heldBackPct + (drop - take), setAsideAtPct = readingPct,
                    peakSincePct = readingPct)
            }
        }
        return next.copy(standCountedPct = counted)
    }

    /** Litres used from [basePct] to [nowPct], less the drop held back, or null when there is no figure yet ([FuelUsed.litres]). */
    fun litres(nowPct: Double, tankL: Double): Double? =
        if (!FuelUsed.measurable(basePct) || nowPct <= 0.0) null else FuelUsed.litres(basePct, nowPct + heldBackPct, tankL)

    /** The distance driven since [basePct] was taken, at [distanceKm] on the same odometer; 0 before a measurable start. */
    fun distanceKm(distanceKm: Double): Double =
        if (FuelUsed.measurable(basePct)) (distanceKm - baseDistanceKm).coerceAtLeast(0.0) else 0.0

    companion object {
        private const val EPS = 1e-9

        /** A span from a start level [startPct] read at distance 0 (a drive's START reading). */
        fun start(startPct: Double): FuelSpan = FuelSpan(basePct = startPct)
    }
}
