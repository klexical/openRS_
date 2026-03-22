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
 */
object CrashReporter {

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val diagDir = File(app.filesDir, "diagnostics").apply { mkdirs() }
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(diagDir, "crash_telemetry_$ts.json")
                CrashTelemetryBuffer.flushToFile(
                    file      = file,
                    exception = throwable,
                    threadName = thread.name,
                    appVersion = BuildConfig.VERSION_NAME
                )
            } catch (_: Throwable) {
                // Must not throw — fall through to default handler.
            }
            prev?.uncaughtException(thread, throwable)
        }
    }
}
