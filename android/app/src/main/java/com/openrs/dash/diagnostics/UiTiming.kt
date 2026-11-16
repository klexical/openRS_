package com.openrs.dash.diagnostics

import com.openrs.dash.can.BleDelivery

/**
 * UI timing for the diagnostic export (#421): counts only, never a clock time. Three measures, each a count, how many were
 * over each edge (strictly over: a value exactly on an edge is not counted) and the longest. Pure: the clocks and the
 * feeds are the callers' (CanDataService's Main ticker and OBD merge, MainActivity's frame listener). Each class locks
 * itself, so the feeding thread and the export's reader can't tear a reading.
 */
class TimingCounts(val count: Long, val overMs: Map<Int, Long>, val longestNanos: Long) {
    /** The longest in milliseconds, one decimal (half up). */
    val longestMs: Double get() = Math.round(longestNanos / 100_000.0) / 10.0
}

/** Counts, per-edge counts and the longest of one measure; its owner locks. A negative duration counts as zero. */
private class TimingBuckets(private val edgesMs: IntArray) {
    private var count = 0L
    private val over = LongArray(edgesMs.size)
    private var longestNanos = 0L
    fun add(nanos: Long) {
        val n = nanos.coerceAtLeast(0L)
        count++
        for (i in edgesMs.indices) if (n > edgesMs[i] * NANOS_PER_MS) over[i]++
        if (n > longestNanos) longestNanos = n
    }
    fun read() = TimingCounts(count, edgesMs.indices.associate { edgesMs[it] to over[it] }, longestNanos)
}

private const val NANOS_PER_MS = 1_000_000L

/** Main-thread lateness (#421): a ticker on Main asks to wake every [PERIOD_MS] and this counts how late each wake came. */
class MainThreadLag(private val clockNanos: () -> Long) {
    companion object {
        const val PERIOD_MS = 100L
        val EDGES_MS = intArrayOf(50, 200, 700)
        private const val NONE = Long.MIN_VALUE
    }
    private var buckets = TimingBuckets(EDGES_MS)
    private var askedAt = NONE

    /** The ticker is about to wait [PERIOD_MS]. */
    @Synchronized fun asking() { askedAt = clockNanos() }

    /** The ticker woke: one sample, late by the time past [PERIOD_MS] since [asking]. A wake with no ask is not one. */
    @Synchronized fun woke() {
        if (askedAt == NONE) return
        buckets.add(clockNanos() - askedAt - PERIOD_MS * NANOS_PER_MS)
        askedAt = NONE
    }

    /** A new connection: zero, and an ask still in flight belongs to the old one. */
    @Synchronized fun reset() { buckets = TimingBuckets(EDGES_MS); askedAt = NONE }

    @Synchronized fun read(): TimingCounts = buckets.read()
}

/** Screen frame durations by the tab in front (#421), fed (pager page, duration) by the frame-metrics listener. */
class FrameTimes {
    companion object {
        /** The pager's pages in order (MainActivity). */
        val TABS = listOf("DRIVE", "PERF", "THERMAL", "TRIP", "GARAGE")
        val EDGES_MS = intArrayOf(32, 100, 700)
    }
    private var byTab = Array(TABS.size) { TimingBuckets(EDGES_MS) }

    /** One frame of [nanos] while [page] is the settled tab; a page outside the five is not counted. */
    @Synchronized fun frame(page: Int, nanos: Long) { if (page in TABS.indices) byTab[page].add(nanos) }

    @Synchronized fun reset() { byTab = Array(TABS.size) { TimingBuckets(EDGES_MS) } }

    /** Every tab in pager order, a tab with no frames included. */
    @Synchronized fun read(): Map<String, TimingCounts> = TABS.indices.associate { TABS[it] to byTab[it].read() }
}

/** The OBD merges' wait for the link lock (#421): two times at the call site, before `deliver` and first inside it. */
class LinkLockWait {
    companion object { val EDGES_MS = intArrayOf(5, 50, 200) }
    private var buckets = TimingBuckets(EDGES_MS)
    @Synchronized fun waited(beforeNanos: Long, insideNanos: Long) { buckets.add(insideNanos - beforeNanos) }
    @Synchronized fun reset() { buckets = TimingBuckets(EDGES_MS) }
    @Synchronized fun read(): TimingCounts = buckets.read()
}

/** One export's UI timing: [bluetooth] is null for a session that is not Bluetooth. */
class UiTimingRecord(
    val mainThread: TimingCounts,
    val frames: Map<String, TimingCounts>,
    val mergeLockWait: TimingCounts,
    val bluetooth: BleDelivery?,
)
