package com.openrs.dash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.diagnostics.CrashReporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #552: the installed crash handler lets go of its heap reserve before it writes anything, then writes and hands the
 * crash on to the handler it found. The full-heap case itself is [CrashHandlerHeapReserveTest].
 *
 * A plain Robolectric `Application`, so `OpenRSDashApp.onCreate` does not install a handler of its own first.
 */
@RunWith(RobolectricTestRunner::class)
class CrashHandlerReserveOrderTest {

    private var before: Thread.UncaughtExceptionHandler? = null
    private val passedOn = mutableListOf<Throwable>()

    @Before fun setUp() {
        before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn += e }
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(before)
    }

    @Test fun `install holds the reserve, the handler releases it before it writes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var heldAtWrite: Boolean? = null
        var written: File? = null
        CrashReporter.install(app) { file, _, _ ->
            heldAtWrite = CrashReporter.reserveHeld
            written = file
        }
        assertTrue("install sets the reserve aside", CrashReporter.reserveHeld)

        val crash = IllegalStateException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)

        assertEquals("the record was written", false, heldAtWrite)
        assertFalse(CrashReporter.reserveHeld)
        assertEquals(File(app.filesDir, "diagnostics"), written!!.parentFile)
        assertTrue(written!!.name.startsWith("crash_telemetry_"))
        assertEquals(1, passedOn.size)
        assertSame("the crash goes on to the handler found at install", crash, passedOn[0])
    }
}
