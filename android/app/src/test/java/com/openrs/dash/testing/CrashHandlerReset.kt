package com.openrs.dash.testing

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * #505: puts back the JVM's default uncaught-exception handler before Robolectric creates each test's application.
 *
 * `OpenRSDashApp.onCreate` installs `CrashReporter`, whose handler keeps that application and chains to the handler it
 * found. Robolectric creates a new application for every test, so without this the default handler became a chain with
 * one link per test, and every earlier test's application, with its contexts and resources, stayed reachable until the
 * suite ended (`EarlierTestApplicationReleasedTest`). An uncaught error also ran through every link of the chain.
 *
 * Robolectric finds this through `META-INF/services/org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin` (test
 * resources) and calls it at the start of every test, before the application exists. A handler that is not
 * `CrashReporter`'s is kept as the one to put back. The class is loaded once per Robolectric sandbox; the Gradle test
 * JVM starts with no default handler, which is also what a sandbox puts back before it has seen another.
 */
class CrashHandlerReset : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current != null && isCrashReporters(current)) Thread.setDefaultUncaughtExceptionHandler(original)
        else original = current
    }

    companion object {
        @Volatile private var original: Thread.UncaughtExceptionHandler? = null

        /** By name, because each sandbox loads its own `CrashReporter` and the handler may come from another one. */
        fun isCrashReporters(handler: Thread.UncaughtExceptionHandler): Boolean =
            handler.javaClass.name.startsWith("com.openrs.dash.diagnostics.CrashReporter$")
    }
}
