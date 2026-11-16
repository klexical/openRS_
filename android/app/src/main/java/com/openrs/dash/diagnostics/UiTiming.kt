package com.openrs.dash.diagnostics

import com.openrs.dash.can.BleDelivery

/**
 * UI timing for the diagnostic export (#421): counts only, never a clock time. Three measures, each a count, how many were
 * over each edge (strictly over: a value exactly on an edge is not counted) and the longest. Pure: the clocks and the
 * feeds are the callers' (CanDataService's Main ticker and OBD merge, MainActivity's frame listener).
 */
class TimingCounts(val count: Long, val overMs: Map<Int, Long>, val longestNanos: Long) {
    /** The longest in milliseconds, one decimal. */
    val longestMs: Double get() = 0.0
}

/** Main-thread lateness (#421): a ticker on Main asks to wake every [PERIOD_MS] and this counts how late each wake came. */
class MainThreadLag(private val clockNanos: () -> Long) {
    companion object {
        const val PERIOD_MS = 100L
        val EDGES_MS = intArrayOf(50, 200, 700)
    }
    /** The ticker is about to wait [PERIOD_MS]. */
    fun asking() {}
    /** The ticker woke: one sample, late by the time past [PERIOD_MS] since [asking]. */
    fun woke() {}
    fun reset() {}
    fun read(): TimingCounts = TimingCounts(0, emptyMap(), 0)
}

/** Screen frame durations by the tab in front (#421), fed (pager page, duration) by the frame-metrics listener. */
class FrameTimes {
    companion object {
        /** The pager's pages in order (MainActivity). */
        val TABS = listOf("DRIVE", "PERF", "THERMAL", "TRIP", "GARAGE")
        val EDGES_MS = intArrayOf(32, 100, 700)
    }
    fun frame(page: Int, nanos: Long) {}
    fun reset() {}
    /** Every tab, a tab with no frames included. */
    fun read(): Map<String, TimingCounts> = TABS.associateWith { TimingCounts(0, emptyMap(), 0) }
}

/** The OBD merges' wait for the link lock (#421): two times at the call site, before `deliver` and first inside it. */
class LinkLockWait {
    companion object { val EDGES_MS = intArrayOf(5, 50, 200) }
    fun waited(beforeNanos: Long, insideNanos: Long) {}
    fun reset() {}
    fun read(): TimingCounts = TimingCounts(0, emptyMap(), 0)
}

/** One export's UI timing: [bluetooth] is null for a session that is not Bluetooth. */
class UiTimingRecord(
    val mainThread: TimingCounts,
    val frames: Map<String, TimingCounts>,
    val mergeLockWait: TimingCounts,
    val bluetooth: BleDelivery?,
)
