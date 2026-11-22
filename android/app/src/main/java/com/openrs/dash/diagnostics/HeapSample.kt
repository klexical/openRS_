package com.openrs.dash.diagnostics

import android.os.Debug

/**
 * One reading of the app's memory, in KiB (#510): the Java heap's used part, its current size and its limit
 * (`Runtime.maxMemory`, the growth limit an OutOfMemoryError is thrown at), and the native heap allocated.
 * Numbers only: no clock time, nothing from the car.
 *
 * [read] allocates nothing beyond the result, so the crash handler can call it on a full heap; it never throws.
 */
data class HeapSample(val usedKb: Long, val totalKb: Long, val maxKb: Long, val nativeKb: Long) {
    companion object {
        /** The current reading, or null when the runtime gives none. */
        fun read(): HeapSample? = try {
            val rt = Runtime.getRuntime()
            val total = rt.totalMemory()
            HeapSample(
                usedKb = (total - rt.freeMemory()) / 1024,
                totalKb = total / 1024,
                maxKb = rt.maxMemory() / 1024,
                nativeKb = Debug.getNativeHeapAllocatedSize() / 1024,
            )
        } catch (_: Throwable) {
            null
        }
    }
}
