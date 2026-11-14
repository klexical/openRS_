package com.openrs.dash

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * #209, the export (PLAN-209's seam 3, the acceptance line's "a diagnostic export shows"): after a scripted session through
 * the live SlcanConnection, the exported ZIP's raw log holds the app's polls as `# TX` lines with their result, and its
 * header names them. The real exporter, the ZIP read back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RequestLogExportTest {
    /** Answers a Mode 22 read with a positive reply. */
    private class Adapter : SlcanTransport {
        val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            if (frame.length >= 13 && frame[0] == 't' && frame.substring(5, 9) == "0322") {
                val resp = frame.substring(1, 4).toInt(16) + 8
                lines.trySend("t%03X8%s".format(resp, "0462" + frame.substring(9, 13) + "00000000"))
            }
        }
        override fun close() { lines.close() }
        override val label = "scripted"; override val stockFirmwareLabel = "scripted fw"
    }

    private lateinit var root: File
    private val ctx by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        object : ContextWrapper(app) { override fun getFilesDir(): File = root }
    }

    @Before fun session() {
        root = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "txexport-${System.nanoTime()}").also { it.mkdirs() }
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
    }

    @Test fun `an exported diagnostic ZIP's raw log holds the app's requests as TX lines, and its header says so`() = runTest {
        val a = Adapter()
        val conn = SlcanConnection(transportFactory = { a }, maxRetries = 1, rpmProvider = { 1_000.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime })
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState(rpm = 1_000.0) }).collect {} }
        advanceTimeBy(30_000L)                       // the PCM poller starts 20 s after the connection
        DiagnosticExporter.export(ctx)
        val zip = File(root, "diagnostics").listFiles { f -> f.name.startsWith("openrs_diag_") }.orEmpty().single()
        val raw = ZipFile(zip).use { z ->
            val e = z.entries().toList().single { it.name.startsWith("slcan_log_") }
            z.getInputStream(e).bufferedReader().readLines()
        }
        val polls = raw.filter { it.startsWith("# TX ") && it.contains(" can0 7E0#0322") }
        assertTrue("PCM poll TX lines in the export: ${raw.take(12)}", polls.size >= 5)
        assertTrue(polls.toString(), polls.all { Regex("""^# TX \(\s*\d+\.\d{3}\) can0 7E0#0322[0-9A-F]{12} sent$""").matches(it) })
        assertTrue("replies are there too", raw.any { it.startsWith("(") && it.contains(" can0 7E8#0462") })
        assertTrue("the header names the TX form: ${raw.take(6)}",
            raw.take(6).any { it.startsWith("# Format:") && it.contains("# TX (relative_seconds) can0 ID#DATA <result>") })
        assertTrue("no header line reads as a request: ${raw.take(6)}", raw.filter { it.startsWith("# TX ") }.all { it.contains(" can0 ") && !it.contains("ID#DATA") })
    }
}
