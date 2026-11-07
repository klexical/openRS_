package com.openrs.dash

import android.util.Log
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * #253: refused lines are counted, never printed — logcat included (it
 * ships in release; proguard strips no Log call). Robolectric only because android.util.Log is a no-op stub in plain JVM
 * tests, so nothing else can observe the sink. Production path: SlcanConnection + SlcanParser through ReplayRun; SYNTHETIC.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SlcanRefusedLineLogcatTest {
    @Before fun session() {
        ShadowLog.clear()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
    }

    private fun logcat() = ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg}" }

    @Test fun `control - this test can see logcat`() {
        Log.w("SLCAN", "visible")
        assertTrue(logcat(), logcat().contains("visible"))
    }

    @Test fun `a refused line never reaches logcat - non-hex data, ID, DLC and suffix`() = runTest {
        val refused = listOf(
            "t0F88C0FFEE0000G00082",        // non-hex data, right length: the case that reaches parseDataBytes
            "t0F88C0FFEE00000000G2",
            "t0f88c0ffee0000g00082",
            "tG0F8C0FFEE0000000082".take(21),
            "t0F8GC0FFEE00000082",
        )
        val rows = (listOf("t09080000C1FF617D0000", "t0F88005A000000000082") + refused)
            .mapIndexed { i, l -> ReplayRow(100L + i * 50L, "RX", l) }
        val run = ReplayRun(this, rows)
        advanceTimeBy(5_000L)
        assertEquals("the lines reached the refusal path", 70.0, run.state.value.oilTempC, 0.0)
        assertEquals(refused.size, DiagnosticLogger.linkIntegrity.refusedFrameLines)
        val text = logcat()
        for (marker in listOf("C0FFEE", "c0ffee", "t0F8", "t0f8")) assertTrue(text, !text.contains(marker))
    }
}
