package com.openrs.dash

import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * #403: the raw SLCAN log's time field has no width, so a reader that splits a line on white space gets `(12.345)` as its
 * first token. SavvyCAN's candump loader (its source, read, not run) collapses white space, splits on spaces and keeps a
 * line only when the first token matches `^\((\S+)\)$`; with the time padded to ten characters the first token was `(`
 * alone and every frame line was skipped. The test writes frames and a request through the logger's own paths and reads
 * the file it wrote.
 */
class SlcanLogTimeFieldTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "slcan-time-${System.nanoTime()}")
    private val base = 1_000_000L
    private var now = base

    @Before fun session() {
        DiagnosticLogger.sessionClock = { now }
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = dir)
    }
    @After fun realClock() {
        DiagnosticLogger.sessionClock = System::currentTimeMillis
        dir.deleteRecursively()
    }

    /** SavvyCAN's candump split: `simplified()` then `split(' ')`; the first token is the time field. */
    private fun savvyFirstToken(line: String): String = line.trim().split(Regex("\\s+")).first()
    private val savvyTime = Regex("""^\((\S+)\)$""")

    private fun log(): List<String> { DiagnosticLogger.flushSlcan(); return DiagnosticLogger.slcanLogFile!!.readLines() }

    @Test fun `every frame line's first token is the whole time field`() {
        for (ms in listOf(0L, 90L, 30_512L, 4_000_000L)) {
            now = base + ms
            DiagnosticLogger.logFrame(0x0F8, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), VehicleState(), "d", null)
        }
        val frames = log().filter { !it.startsWith("#") && it.isNotBlank() }
        assertEquals(frames.toString(), 4, frames.size)
        frames.forEach { line ->
            val m = savvyTime.matchEntire(savvyFirstToken(line))
            assertTrue("SavvyCAN would skip: $line", m != null)
            m!!.groupValues[1].toDouble()                                     // and the time inside reads as a number
        }
        assertEquals("(30.512) can0 0F8#0102030405060708", frames[2])
    }

    @Test fun `a request line carries the same unpadded time`() {
        now = base + 1_250L
        val stamp = DiagnosticLogger.txStamp()
        DiagnosticLogger.logTxLine("t7E080322033E00000000", stamp, "sent")
        val tx = log().single { it.startsWith("# TX ") }
        assertEquals("# TX (1.250) can0 7E0#0322033E00000000 sent", tx)
    }

    @Test fun `the formatter itself writes no width`() {
        assertEquals("(0.090) can0 090#0000C1FF617D0000\n", DiagnosticLogger.buildSlcanLine(90L, 0x090, "0000C1FF617D0000"))
        assertEquals("(123456.789) can0 7E8#00\n", DiagnosticLogger.buildSlcanLine(123_456_789L, 0x7E8, "00"))
    }
}
