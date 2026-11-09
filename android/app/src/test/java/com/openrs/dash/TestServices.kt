package com.openrs.dash

import com.openrs.dash.service.CanDataService
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController

/**
 * Every CanDataService a Robolectric test creates goes through here and is destroyed at the test's teardown
 * ([destroyAll] from an `@After`). A service left alive keeps its scope's collectors (the drive recorder's state,
 * AppVisibility) and can publish a notification through its dead context after Robolectric's reset; the exception is then
 * charged to whichever coroutine test runs next (`UncaughtExceptionsBeforeTest`: N2's gate on 16c5c2c, PR #281's CI in
 * MapRecordingPollTest). [ServiceTeardownGuardTest] fails when a test builds a service any other way.
 */
internal object TestServices {
    private val live = java.util.Collections.synchronizedList(mutableListOf<CanDataService>())

    /** A created service, destroyed by [destroyAll]. */
    fun create(): CanDataService = controller().create().get()

    /** A not-yet-created controller (for tests that drive the lifecycle); its service is destroyed by [destroyAll]. */
    fun controller(): ServiceController<CanDataService> =
        Robolectric.buildService(CanDataService::class.java).also { live += it.get() }

    /** Destroys every service created since the last call: its scope (and every collector) is cancelled. */
    fun destroyAll() {
        val all = synchronized(live) { live.toList().also { live.clear() } }
        // A test may already have destroyed one (the SERVICE_DESTROYED path) or never created it: a second onDestroy is harmless
        all.forEach { runCatching { it.onDestroy() } }
    }
}
