package com.openrs.dash

import android.os.Looper
import com.openrs.dash.service.CanDataService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
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

    /**
     * Destroys every service created since the last call and returns only once every coroutine of their scopes has finished.
     * Cancelling is not enough (PR #290's CI): a collector body that has already started (a StateFlow's first emission, no
     * suspension point before `link.transition`) runs on to its end, and if that end comes after Robolectric's reset it
     * throws into the next test. Call it from an `@After`, never while holding `CanDataService.link`.
     */
    fun destroyAll() {
        val all = synchronized(live) { live.toList().also { live.clear() } }
        // A test may already have destroyed one (the SERVICE_DESTROYED path) or never created it: a second onDestroy is harmless
        try {
            all.forEach { runCatching { it.onDestroy() } }
            awaitScopes(all)
        } finally {
            // #316 seam 5: a test's own recorder never reaches a later class (its database is closed by then), also when
            // a scope fails to finish
            CanDataService.recorderProvider = CanDataService.APP_RECORDER
            // #316 "auto-record switched": the service tells its recorder the setting from UserPrefsStore, so a test's
            // setting never reaches a later class either (the next service loads the saved one at its creation)
            com.openrs.dash.ui.UserPrefsStore.resetForTest()
        }
    }

    private val scopeField by lazy { CanDataService::class.java.getDeclaredField("scope").apply { isAccessible = true } }

    /**
     * Cancels each service's scope (again: an onDestroy that threw before its own cancel is not trusted) and waits until its
     * Job is complete. A cancelled child queued on the main looper completes only when the looper runs it, so the looper is
     * idled while waiting. Fails loudly, in the test that owns the service, when a coroutine does not end within [timeoutMs].
     */
    private fun awaitScopes(all: List<CanDataService>, timeoutMs: Long = 10_000L) {
        val jobs: List<Job> = all.map { s -> (scopeField.get(s) as CoroutineScope).also { it.cancel() }.coroutineContext.job }
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (jobs.any { !it.isCompleted }) {
            if (Looper.myLooper() == Looper.getMainLooper()) shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > end) throw AssertionError(
                "a CanDataService coroutine is still running $timeoutMs ms after onDestroy: " +
                    jobs.filter { !it.isCompleted }.flatMap { it.children.toList() }.filter { !it.isCompleted }
            )
            Thread.sleep(1)
        }
    }
}
