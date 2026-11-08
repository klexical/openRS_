package com.openrs.dash.data

import com.openrs.dash.can.ObdConstants

/**
 * One freshness rule for PCM-polled readings (#217): how old a polled value may be before it is shown as stale rather
 * than current. Derived from the poll schedule ([com.openrs.dash.can.PcmPollSchedule]):
 *  - engine running: the fast group (TIP, WGDC, knock retard) refreshes every cycle (~1.2 s) → [FAST_REFRESH_MS];
 *    every other PCM DID rotates through the slow group (~25 s) → [ObdConstants.PCM_SLOW_REFRESH_MS];
 *  - engine off: every PCM DID is swept every ~34 s → [ObdConstants.PCM_SLOW_REFRESH_MS].
 * A value is stale past twice its refresh period (the [fieldState] default). A value whose DID stopped answering or
 * stopped being polled therefore stops looking live, instead of being held forever.
 */
object PolledFreshness {
    const val FAST_REFRESH_MS = 2_000L

    /** Fields refreshed by the fast group while the engine runs. */
    val FAST_TAGS = setOf(
        "tipActualKpa", "tipDesiredKpa", "wgdcDesired",
        "ignCorrCyl1", "ignCorrCyl2", "ignCorrCyl3", "ignCorrCyl4",
        MapF40b.TAG,
    )

    fun refreshMs(tag: String, engineRunning: Boolean): Long =
        if (engineRunning && tag in FAST_TAGS) FAST_REFRESH_MS else ObdConstants.PCM_SLOW_REFRESH_MS

    /** [fieldState] of [value] under this rule: NotPolled (never decoded), Available, or Stale (with its age). */
    fun <T> state(vs: VehicleState, tag: String, value: T, nowMs: Long = System.currentTimeMillis()): FieldState<T> =
        fieldState(value, vs.fieldLastUpdateMs[tag], nowMs,
            pollIntervalMs = refreshMs(tag, vs.rpm > ObdConstants.FAST_POLL_MIN_RPM))

    /** True only while the reading is current under this rule. */
    fun isFresh(vs: VehicleState, tag: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        state(vs, tag, Unit, nowMs) is FieldState.Available
}
