package com.openrs.dash

import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.service.AppVisibility
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PR #290's CI (run 42620361464, release unit tests): a CanDataService's driveState collector ran `retypeForeground` after
 * Robolectric had cleared the environment for the next test, and the NPE (`activityThread` null in `checkSelfPermission`)
 * failed the next Robolectric `runTest` (LinkCountsFollowUpTest) with UncaughtExceptionsBeforeTest.
 *
 * The mechanism, reproduced here without any production change: a collector body that has passed its flow's last
 * cancellation check waits for `CanDataService.link`'s lock while the test thread holds it (`onDestroy` → `stopConnection`).
 * `scope.cancel()` cannot stop it (`synchronized` is no suspension point); it runs as soon as the test thread lets go, and
 * [TestServices.destroyAll] used to return without waiting for it.
 *
 * Each round: a helper holds the link lock; AppVisibility turns visible, so the service's visibility collector (onCreate's
 * second collector, the same shape as the driveState one) blocks on the lock past its cancellation check; the test thread
 * calls destroyAll and blocks on the lock too; the helper lets go. When the test thread gets the lock first (a decisive
 * round), the body is still to run after onDestroy; it is held at its first step inside the lock (the recorder read in
 * retypeForeground) until destroyAll is seen waiting for the scope. A destroyAll that does not wait returns first, and the
 * scope's Job is not complete. When the collector gets the lock first the round proves nothing and is repeated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ServiceScopeTeardownTest {
    private lateinit var real: Lazy<DriveRecorder>
    @Volatile private var hold: CountDownLatch? = null                   // the held body waits on this
    @Volatile private var roundVoid = false

    @After fun destroyServices() {
        hold?.countDown()
        TestServices.destroyAll()
        AppVisibility.visible.value = false
        if (::real.isInitialized && real.isInitialized()) real.value.cancel()
    }

    private fun scopeJob(s: CanDataService): Job =
        (CanDataService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(s) as CoroutineScope).coroutineContext.job

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(1) }
        return cond()
    }

    private fun collectorThreads(frame: String) = Thread.getAllStackTraces().filter { (t, st) ->
        t.name.startsWith("DefaultDispatcher-worker") && st.any { it.className.startsWith("com.openrs.dash.service.CanDataService\$onCreate$frame") }
    }.keys

    private fun inAwaitScopes(t: Thread) =
        t.stackTrace.any { it.className.startsWith(TestServices::class.java.name) && it.methodName == "awaitScopes" }

    /** The app's recorder, read through [hold] when the visibility collector reads it inside retypeForeground. */
    private inner class GatedRecorder(private val real: Lazy<DriveRecorder>, private val main: Thread) : Lazy<DriveRecorder> {
        override val value: DriveRecorder get() {
            val t = Thread.currentThread()
            val h = hold
            if (h != null && t.name.startsWith("DefaultDispatcher-worker") &&
                t.stackTrace.any { it.className.startsWith("com.openrs.dash.service.CanDataService\$onCreate\$4") }
            ) {
                val end = System.currentTimeMillis() + 10_000
                while (h.count > 0 && System.currentTimeMillis() < end) {
                    // the collector won the lock: the test thread waits for it in onDestroy, nothing to prove this round
                    if (main.state == Thread.State.BLOCKED) { roundVoid = true; break }
                    if (inAwaitScopes(main)) break                     // destroyAll is waiting for the scope: let it finish
                    Thread.sleep(1)
                }
            }
            return real.value
        }
        override fun isInitialized() = real.isInitialized()
    }

    @Test fun `destroyAll returns only after a collector body waiting for the link lock has run`() {
        val app = ApplicationProvider.getApplicationContext<OpenRSDashApp>()
        val main = Thread.currentThread()
        real = lazy { app.driveRecorder }
        val gated = GatedRecorder(real, main)

        var decisive = 0
        var round = 0
        while (decisive < 3 && round < 40) {
            round++
            AppVisibility.visible.value = false
            roundVoid = false
            CanDataService.recorderProvider = { gated.value }             // #316 seam 5; destroyAll puts the app's back
            val service = TestServices.create()
            // onCreate's collectors have run their first emission and are suspended
            assertTrue("collectors settled", waitUntil(5_000) { (1..5).all { collectorThreads("").isEmpty().also { Thread.sleep(1) } } })
            val job = scopeJob(service)

            val holding = CountDownLatch(1)
            val letGo = CountDownLatch(1)
            val holder = Thread { CanDataService.link.transition { holding.countDown(); letGo.await(10, TimeUnit.SECONDS) } }.apply { isDaemon = true; start() }
            assertTrue(holding.await(5, TimeUnit.SECONDS))
            val gate = CountDownLatch(1).also { hold = it }
            AppVisibility.visible.value = true
            assertTrue("the visibility collector waits for the link lock",
                waitUntil(5_000) { collectorThreads("\$4").any { it.state == Thread.State.BLOCKED } })
            // let go once the test thread waits for the lock too (in onDestroy → stopConnection)
            Thread { waitUntil(5_000) { main.state == Thread.State.BLOCKED }; letGo.countDown() }.apply { isDaemon = true; start() }
            try {
                TestServices.destroyAll()
                if (!roundVoid) {
                    decisive++
                    assertTrue("round $round: every coroutine of the destroyed service has finished when destroyAll returns", job.isCompleted)
                }
            } finally {
                letGo.countDown()
                gate.countDown()
                hold = null
                // A red round's body runs now, while this test's sandbox is alive, and must end before the test does
                check(waitUntil(5_000) { job.isCompleted }) { "round $round: the collector body did not finish" }
                holder.join(5_000)
            }
        }
        assertTrue("at least one round where the test thread took the lock first ($decisive of $round)", decisive > 0)
    }
}
