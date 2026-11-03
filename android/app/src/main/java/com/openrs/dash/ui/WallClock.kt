package com.openrs.dash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Wall-clock time for freshness decisions, re-read at least every [periodMs] even when no new VehicleState arrives —
 * a stalled CAN link emits nothing, and a reading judged only on recomposition would stay "fresh" on screen (#207,
 * #217). Returns the current time on every call (recompositions from new frames are never behind).
 */
@Composable
fun wallClockMs(periodMs: Long = 1_000L): Long {
    val tick by produceState(System.currentTimeMillis()) {
        while (true) { delay(periodMs); value = System.currentTimeMillis() }
    }
    return maxOf(tick, System.currentTimeMillis())
}
