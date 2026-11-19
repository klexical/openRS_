package com.openrs.dash.data

/**
 * Fuel used from the fuel gauge (#336): the one rule for a drive's figure (DriveState) and DRIVE's session cell
 * (FuelEconomy). The level (0x380) moves in steps of [GAUGE_STEP_PCT] and is clamped to 0–100 %, so a few steps are gauge
 * noise, not fuel: one five-minute idle stepped 100 → 98.4 % (#336). A figure is given only once the level has dropped by
 * at least [MIN_DROP_STEPS] steps from a measurable start; until then there is none (0.0 where a figure is stored).
 * A start on the 100 % clamp, or before the level was received (0), is not measurable: the start moves to the first
 * reading below the clamp ([rebase]). The fuel used while the gauge sat on the clamp is not counted (a stated limit).
 */
object FuelUsed {
    /** One step of the fuel gauge, 0x380 (×0.4 %). */
    const val GAUGE_STEP_PCT = 0.4
    /** The smallest drop, in gauge steps, that gives a figure. */
    const val MIN_DROP_STEPS = 4

    /** A level a figure can be measured from: received (above 0) and below the 100 % clamp. */
    fun measurable(pct: Double): Boolean = pct > 0.0 && pct < 100.0

    /** The start a figure is measured from, after [readingPct]: [startPct], or the reading while the start is not measurable. */
    fun rebase(startPct: Double, readingPct: Double): Double =
        if (!measurable(startPct) && measurable(readingPct)) readingPct else startPct

    /** Litres used from [startPct] to [nowPct] in a tank of [tankL], or null when there is no figure yet. */
    fun litres(startPct: Double, nowPct: Double, tankL: Double): Double? {
        if (!measurable(startPct) || nowPct <= 0.0) return null
        val drop = startPct - nowPct
        if (drop < MIN_DROP_STEPS * GAUGE_STEP_PCT - 1e-6) return null
        return drop / 100.0 * tankL
    }
}
