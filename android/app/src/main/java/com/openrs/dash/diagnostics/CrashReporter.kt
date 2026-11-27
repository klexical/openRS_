package com.openrs.dash.diagnostics

import android.app.Application
import com.openrs.dash.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installs a global [Thread.UncaughtExceptionHandler] that persists
 * the [CrashTelemetryBuffer] ring to disk before the process terminates.
 *
 * The crash report is saved as `crash_telemetry_<timestamp>.json` inside
 * the app's `diagnostics/` directory and gets included in the next
 * diagnostic ZIP export.
 *
 * [install] also sets aside [RESERVE_BYTES] of heap that the handler lets go of before it allocates anything, so a
 * crash caught on a full heap still has room for the minimal record (#552, #511). The cost is the reserve's size for
 * the process's whole life.
 */
object CrashReporter {

    /** Heap kept for the crash handler: room for the minimal record's stack text and a few KB more (#552). */
    internal const val RESERVE_BYTES = 128 * 1024

    @Volatile private var reserve: ByteArray? = null

    /** True while the reserve is held: from [install] until the handler first runs (#552). */
    internal val reserveHeld: Boolean get() = reserve != null

    /**
     * [write] is a seam for tests; the app uses the default, which writes the crash record with this build's details.
     */
    fun install(
        app: Application,
        write: (file: File, exception: Throwable, threadName: String) -> Unit = ::writeRecord,
    ) {
        reserve = ByteArray(RESERVE_BYTES)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // First, before the file name or anything else is allocated: the next allocation's garbage collection
            // can then reclaim the reserve (#552).
            reserve = null
            try {
                val diagDir = File(app.filesDir, "diagnostics").apply { mkdirs() }
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                write(crashFile(diagDir, ts), throwable, thread.name)
            } catch (_: Throwable) {
                // Must not throw — fall through to default handler.
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    private fun writeRecord(file: File, exception: Throwable, threadName: String) =
        CrashTelemetryBuffer.flushToFile(
            file       = file,
            exception  = exception,
            threadName = threadName,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            sourceCommit = BuildConfig.SOURCE_COMMIT + if (BuildConfig.SOURCE_DIRTY) "-dirty" else "",
            buildType = BuildConfig.BUILD_TYPE
        )

    /**
     * Claims the file a crash caught at [ts] (`yyyyMMdd_HHmmss`) is written to, in [dir]: `crash_telemetry_<ts>.json`,
     * or `crash_telemetry_<ts>_<n>.json` (n from 2) when that name is taken. On a full heap several threads can throw
     * at once, and the stamp has one-second resolution; the name is claimed by creating the file (atomic, one winner),
     * so each crash gets a file of its own and no record overwrites or garbles another (#511). Every name keeps the
     * `crash_telemetry_*.json` form the crash list and the export select by. If no name can be claimed (no folder, a
     * full disk) the plain name is returned and the write fails there, as before.
     */
    internal fun crashFile(dir: File, ts: String): File {
        for (n in 1..MAX_SAME_SECOND) {
            val f = File(dir, if (n == 1) "crash_telemetry_$ts.json" else "crash_telemetry_${ts}_$n.json")
            try {
                if (f.createNewFile()) return f
            } catch (_: Throwable) {
                break
            }
        }
        return File(dir, "crash_telemetry_$ts.json")
    }

    /** Crashes in one second that get a name of their own; past that they share the plain name (never seen). */
    private const val MAX_SAME_SECOND = 32
}
