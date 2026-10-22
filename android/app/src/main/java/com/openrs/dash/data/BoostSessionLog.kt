package com.openrs.dash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live boost sweep window for the POWER tab chart (rc.6, #175) — the last [WINDOW_MS] of
 * fresh TIP decodes with the matching target and WGDC. In memory only; no recording needed.
 *
 * A sample is taken once per fresh `tipActualKpa` decode (own [FreshnessGate], so never a
 * held value and never from before the session). Target and WGDC ride along only if they
 * were themselves decoded within [PAIR_MAX_AGE_MS] — they are in the same fast PCM group,
 * so a stale one means its DID stopped answering. Fed from `CanDataService.mergeObdState`
 * OUTSIDE the state `update{}`, reset with the knock session log.
 */
object BoostSessionLog {

    const val WINDOW_MS = 90_000L
    const val PAIR_MAX_AGE_MS = 2_500L

    data class Sample(
        val tMs: Long,
        val rpm: Double,
        val actualKpa: Double,       // TIP actual, absolute
        val targetKpa: Double?,      // TIP desired, absolute; null = not fresh with this sample
        val wgdcPct: Double?         // null = not fresh with this sample
    )

    private var gate = FreshnessGate(0L)

    private val _samples = MutableStateFlow<List<Sample>>(emptyList())
    val samples: StateFlow<List<Sample>> = _samples.asStateFlow()

    @Synchronized
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        gate = FreshnessGate(nowMs)
        _samples.value = emptyList()
    }

    /** Feed the merged live state after an OBD merge. */
    @Synchronized
    fun onState(vs: VehicleState, nowMs: Long) {
        val stamps = vs.fieldLastUpdateMs
        if (!gate.takeIfFresh("tipActualKpa", stamps)) return
        // < 50 kPa absolute can only be a bad decode (same gate as the POWER TIP cell)
        if (vs.tipActualKpa < 50.0) return
        fun pairedFresh(tag: String) = stamps[tag]?.let { nowMs - it <= PAIR_MAX_AGE_MS } == true
        val sample = Sample(
            tMs = nowMs,
            rpm = vs.rpm,
            actualKpa = vs.tipActualKpa,
            targetKpa = vs.tipDesiredKpa.takeIf { pairedFresh("tipDesiredKpa") && it >= 50.0 },
            wgdcPct = vs.wgdcDesired.takeIf { pairedFresh("wgdcDesired") && it >= 0.0 }
        )
        val cutoff = nowMs - WINDOW_MS
        _samples.value = _samples.value.filter { it.tMs > cutoff } + sample
    }
}
