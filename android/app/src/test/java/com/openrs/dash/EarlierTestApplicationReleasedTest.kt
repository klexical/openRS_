package com.openrs.dash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

/**
 * #505: an earlier test's application must not stay reachable once the next test has its own. Robolectric builds a new
 * [OpenRSDashApp] for every test, and its `onCreate` installs `CrashReporter`, whose handler keeps the application and
 * chains to the handler it found. Without `CrashHandlerReset` (test sources, run by Robolectric before each test's
 * application) every test's application, its contexts and its resources stayed reachable from the JVM-wide default
 * handler until the suite ended: one more link per test.
 *
 * The methods run in name order, in one sandbox. The first only lets the application-wide sessions start: the thermal
 * readiness session keeps the application it started with for the rest of the run (`ThermalReadinessSession.start`), one
 * per sandbox, which does not grow, so the application this class checks is the second one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EarlierTestApplicationReleasedTest {
    companion object {
        private var recorded: WeakReference<Application>? = null
    }

    @Test fun `t1 an application starts the application-wide sessions`() {
        assertNotNull(OpenRSDashApp.instanceOrNull)
    }

    @Test fun `t2 records this test's application`() {
        recorded = WeakReference(ApplicationProvider.getApplicationContext())
    }

    @Test fun `t3 the previous test's application can be collected`() {
        val ref = recorded
        assertNotNull("run the whole class: t2 records the application this test checks", ref)
        val previous = ref!!
        assertNotNull("this test has an application of its own", OpenRSDashApp.instanceOrNull)
        for (attempt in 1..40) {
            if (previous.get() == null) break
            System.gc()
            System.runFinalization()
            Thread.sleep(25)
        }
        assertNull("the previous test's application is still reachable", previous.get())
    }
}
