package com.openrs.dash.ui

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.openrs.dash.BuildConfig

/**
 * Debug-only startup timeline (logcat tag `openRS_Startup`): each mark is milliseconds since the process started
 * (`Process.getStartUptimeMillis`), so app init, activity setup and the opening composition's first draws can be
 * compared with the system's launch request / "Displayed" lines. No-op in release builds.
 */
object StartupTrace {
    fun mark(what: String) {
        if (!BuildConfig.DEBUG) return
        Log.i("openRS_Startup", "+${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()} ms  $what")
    }
}
