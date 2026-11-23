package com.openrs.dash

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * #562: an export holds no TPMS sensor ID, as hex (spaced or compact, either case), as a decimal, or as bytes that rebuild one
 * from the raw log, ISO-TP framing included; the app keeps the real IDs for the wheel mapping. #527: CAN 0x398's constant
 * text (its last six data bytes) is masked in the raw log, the inventory and the summary. The sensor IDs and the text are
 * synthetic. The session runs through the live SlcanConnection (BCM replies) and LinkSession (CAN frames), and the real
 * exporter writes the ZIP that is read back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExportTpmsIdMaskTest {
    private companion object {
        /** LF, RF, RR, LR, as the BCM's DIDs 0x280F, 0x2810, 0x2811, 0x2812 answer them. */
        val IDS = listOf(0x5A1B2C3DL, 0x6E7F8091L, 0x72A3B4C5L, 0x86D7E8F9L)
        val ID_DIDS = listOf("280F", "2810", "2811", "2812")
        /** 0x398's six text bytes: printable, as on the car, and not the real text. */
        const val TEXT = "SYNTXT"
        val FRAME_398 = byteArrayOf(0x01, 0x02) + TEXT.toByteArray(Charsets.US_ASCII)
        val OTHER_0F8 = byteArrayOf(0x46, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x70)
    }

    private fun idHex(id: Long) = "%08X".format(id)
    private fun idBytes(id: Long) = ByteArray(4) { ((id shr (24 - 8 * it)) and 0xFF).toByte() }

    /**
     * A BCM that answers the four sensor-ID reads in single frames and the "last received sensor" read (0x280B) as a first
     * frame, then, on the app's flow control, a consecutive frame: the sensor ID spans both. Every other read gets a short
     * positive reply.
     */
    private class Bcm(private val lastReport: Long) : SlcanTransport {
        val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            if (frame.startsWith("t7268300")) {                                   // flow control after the 0x280B first frame
                lines.trySend("t72E821%02X02944106A500".format(lastReport and 0xFF))
                return
            }
            if (frame.length < 13 || frame[0] != 't' || frame.substring(5, 9) != "0322") return
            val did = frame.substring(9, 13)
            val resp = frame.substring(1, 4).toInt(16) + 8
            when {
                resp == 0x72E && did in ID_DIDS -> lines.trySend("t72E80762$did%08X".format(IDS[ID_DIDS.indexOf(did)]))
                resp == 0x72E && did == "280B" -> lines.trySend("t72E8100C62280B%06X".format(lastReport shr 8))
                else -> lines.trySend("t%03X8%s".format(resp, "0462" + did + "00000000"))
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
    private class Rec : LinkSession.Recorder { override val activeDriveId: Long? = null }

    @Before fun session() {
        root = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "tpms-${System.nanoTime()}").also { it.mkdirs() }
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
    }

    /** The live connection for one BCM cycle (sensor-ID reads at connect, 0x280B each cycle); returns the app's state after it. */
    private fun TestScope.runBcm(lastReport: Long = IDS[2]): VehicleState {
        var state = VehicleState(rpm = 0.0)
        val conn = SlcanConnection(transportFactory = { Bcm(lastReport) }, maxRetries = 1, rpmProvider = { 0.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime })
        backgroundScope.launch { conn.connectHybrid({ state = it }, { state }).collect {} }
        advanceTimeBy(20_000L)                       // one BCM cycle: the sensor-ID reads at 5 s, 0x280B in the cycle after them
        DiagnosticLogger.flushSlcan()
        return state
    }

    private fun zip(prefix: String): Map<String, String> {
        val f = File(root, "diagnostics").listFiles { x -> x.name.startsWith(prefix) }.orEmpty().single()
        return ZipFile(f).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).bufferedReader().readText() } }
    }
    private fun exportNow(): Map<String, String> {
        File(root, "diagnostics").listFiles { x -> x.name.startsWith("openrs_diag_") }.orEmpty().forEach { it.delete() }
        DiagnosticLogger.flushSlcan()
        DiagnosticExporter.export(ctx)
        return zip("openrs_diag_")
    }
    private fun Map<String, String>.member(prefix: String) = entries.single { it.key.startsWith(prefix) }.value

    private fun spaced(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
    private fun compact(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    /** Every form a text member could carry an ID in; 3-byte runs too, since a 0x280B first frame holds three of the four. */
    private val forbidden: List<String> = IDS.flatMap { id ->
        val b = idBytes(id)
        listOf(idHex(id), spaced(b), id.toString(), compact(b.copyOfRange(0, 3)), spaced(b.copyOfRange(0, 3)),
            compact(b.copyOfRange(1, 4)), spaced(b.copyOfRange(1, 4)))
    } + listOf(TEXT, compact(TEXT.toByteArray()), spaced(TEXT.toByteArray()))

    private fun assertNoIdentifier(name: String, text: String) {
        val hits = forbidden.filter { text.contains(it, ignoreCase = true) }
        assertEquals("$name holds $hits", emptyList<String>(), hits)
    }

    private fun frames(raw: String, id: String) = raw.lines().filter { it.startsWith("(") && it.contains(" can0 $id#") }.map { it.substringAfter('#') }

    /** Per CAN ID, the data bytes joined twice: as logged, and with each frame's first (PCI) byte dropped, which joins an ISO-TP reply. */
    private fun assertNothingRebuilds(raw: String) {
        val byId = linkedMapOf<String, MutableList<ByteArray>>()
        for (l in raw.lines()) {
            if (l.isEmpty() || l.startsWith("#")) continue
            val m = Regex("""^\(\d+\.\d{3}\) can0 ([0-9A-F]{3})#((?:[0-9A-F]{2})*)$""").matchEntire(l) ?: throw AssertionError("not a candump line: $l")
            byId.getOrPut(m.groupValues[1]) { mutableListOf() } += m.groupValues[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        for ((id, list) in byId) for (joined in listOf(list.reduce { a, b -> a + b }, list.map { it.drop(1).toByteArray() }.reduce { a, b -> a + b })) {
            val hex = compact(joined)
            val hits = IDS.map { idHex(it) }.filter { hex.contains(it) } + listOf(compact(TEXT.toByteArray())).filter { hex.contains(it) }
            assertEquals("0x$id rebuilds", emptyList<String>(), hits)
        }
    }

    // ── the BCM's replies ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the app keeps the real sensor IDs and maps a 0x280B report with them`() = runTest {
        val s = runBcm(lastReport = IDS[2])
        assertEquals(IDS, listOf(s.tpmsSensorIdLF, s.tpmsSensorIdRF, s.tpmsSensorIdRR, s.tpmsSensorIdLR))
        assertEquals("the RR report was matched by its real ID", 25.0, s.tireTempRR, 0.0)
    }

    @Test fun `a diagnostic export holds no sensor ID in any member, and nothing in the raw log rebuilds one`() = runTest {
        runBcm()
        val files = exportNow()
        assertTrue(files.keys.toString(), files.keys.any { it.startsWith("slcan_log_") })
        for ((name, text) in files) assertNoIdentifier(name, text)
        assertNothingRebuilds(files.member("slcan_log_"))
    }

    @Test fun `sensor-ID replies keep their ID, length, PCI, DID and the report's other bytes, and the ID reads 00`() = runTest {
        runBcm(lastReport = IDS[2])
        val bcm = frames(exportNow().member("slcan_log_"), "72E")
        for (did in ID_DIDS) assertTrue("$did: $bcm", bcm.contains("0762${did}00000000"))
        assertTrue(bcm.toString(), bcm.contains("100C62280B000000"))
        assertTrue("the consecutive frame keeps pressure, temperature, status and checksum: $bcm", bcm.contains("210002944106A500"))
    }

    @Test fun `a drive export with diagnostics holds no sensor ID in any member`() = runTest {
        runBcm()
        val drive = DriveEntity(id = 9, startTime = 1_700_000_000_000L, endTime = 1_700_000_060_000L, distanceKm = 1.0)
        val points = listOf(DrivePointEntity(driveId = 9, timestamp = 1_700_000_001_000L, lat = 51.5, lng = -0.12, speedKph = 30.0, rpm = 1800))
        DiagnosticExporter.shareDrive(ctx, drive, points, includeDiagnostics = true)
        val files = zip("openrs_export_")
        for ((name, text) in files) assertNoIdentifier(name, text)
        files.entries.firstOrNull { it.key.startsWith("slcan_log_") }?.let { assertNothingRebuilds(it.value) }
    }

    @Test fun `the summary and the JSON count the masked replies`() = runTest {
        runBcm()
        val files = exportNow()
        val limits = JSONObject(files.member("diagnostic_detail_")).getJSONObject("logLimits")
        assertEquals("four single frames, a first frame and a consecutive frame", 6, limits.getInt("tpmsFramesMaskedPage"))
        assertEquals(0, limits.getInt("tpmsFramesMaskedContent"))
        assertTrue(files.member("diagnostic_summary_").substringAfter("LOG LIMITS").take(900),
            files.member("diagnostic_summary_").contains("6 TPMS sensor-ID frame(s) masked"))
    }

    // ── the content layer: a known ID anywhere else ───────────────────────────────────────────────────────────────────

    @Test fun `once a sensor ID is known from its reply, those 4 bytes on any other ID read 00 and only those, counted apart`() = runTest {
        runBcm()
        val before = DiagnosticLogger.logLimits
        DiagnosticLogger.logUnknownFrame(0x3A1, byteArrayOf(0x7A) + idBytes(IDS[1]) + byteArrayOf(0x7B, 0x7C, 0x7D))
        assertEquals(listOf("7A000000007B7C7D"), frames(exportNow().member("slcan_log_"), "3A1"))
        assertEquals(before.tpmsFramesMaskedContent + 1, DiagnosticLogger.logLimits.tpmsFramesMaskedContent)
        assertEquals(before.tpmsFramesMaskedPage, DiagnosticLogger.logLimits.tpmsFramesMaskedPage)
    }

    @Test fun `a sensor ID the app holds in its state is matched too, before any reply is logged`() {
        val state = MutableStateFlow(VehicleState(isConnected = true, tpmsSensorIdLF = IDS[0]))
        val own = LinkOwnership(); val gen = own.transition { own.begin() }
        val s = LinkSession(own, gen, state, Rec(), onFrameCounted = {}, perfRunSink = {})
        s.onCanFrame(0x0F8, OTHER_0F8, 0.0)                                      // a decoded frame carries the state to the logger
        s.onCanFrame(0x3A2, idBytes(IDS[0]) + byteArrayOf(0x11, 0x22), 0.0)
        assertEquals(listOf("000000001122"), frames(exportNow().member("slcan_log_"), "3A2"))
    }

    @Test fun `collision control - 3 of an ID's bytes on another ID stay, as does every unrelated frame`() = runTest {
        runBcm()
        DiagnosticLogger.logUnknownFrame(0x3A3, idBytes(IDS[0]).copyOfRange(0, 3) + byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55))
        DiagnosticLogger.logUnknownFrame(0x0F8, OTHER_0F8)
        val raw = exportNow().member("slcan_log_")
        assertEquals(listOf(compact(idBytes(IDS[0]).copyOfRange(0, 3)) + "1122334455"), frames(raw, "3A3"))
        assertEquals(listOf(compact(OTHER_0F8)), frames(raw, "0F8"))
    }

    @Test fun `a new session forgets the IDs and the counts`() = runTest {
        runBcm()
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
        assertEquals(0L, DiagnosticLogger.logLimits.tpmsFramesMaskedPage)
        DiagnosticLogger.logUnknownFrame(0x3A4, idBytes(IDS[0]))
        assertEquals(0L, DiagnosticLogger.logLimits.tpmsFramesMaskedContent)
    }

    @Test fun `control - a reply for another BCM DID, and a 0x2810 reply from another module, stay byte for byte`() {
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x07, 0x62, 0x28, 0x13, 0x01, 0x6A, 0x00, 0x00))
        DiagnosticLogger.logObdFrame(0x7E8, byteArrayOf(0x07, 0x62, 0x28, 0x10, 0x11, 0x22, 0x33, 0x44))
        val raw = exportNow().member("slcan_log_")
        assertEquals(listOf("07622813016A0000"), frames(raw, "72E"))
        assertEquals(listOf("0762281011223344"), frames(raw, "7E8"))
    }

    // ── a DID prober scan of the BCM ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `a probed sensor-ID reply reads 00 in the probe CSV, the summary and the detail, and another DID's bytes stay`() {
        DiagnosticLogger.recordProbeSession("BCM", 0x726, 0x72E, listOf(
            DiagnosticLogger.ProbeResult(0x2810, "FOUND", spaced(idBytes(IDS[1]))),
            DiagnosticLogger.ProbeResult(0x280B, "FOUND", spaced(idBytes(IDS[2]).copyOfRange(0, 3))),
            DiagnosticLogger.ProbeResult(0x2813, "FOUND", "01 6A"),
        ))
        DiagnosticLogger.recordProbeSession("PCM", 0x7E0, 0x7E8, listOf(DiagnosticLogger.ProbeResult(0x2810, "FOUND", "11 22 33 44")))
        val files = exportNow()
        val csv = files.filterKeys { it.startsWith("did_probe_") }.values.joinToString("\n")   // one CSV per probe session
        assertTrue(csv, csv.contains("0x2810,FOUND,00 00 00 00"))
        assertTrue(csv, csv.contains("0x280B,FOUND,00 00 00"))
        assertTrue(csv, csv.contains("0x2813,FOUND,01 6A"))
        assertTrue("another module's DID 0x2810 is not a sensor ID: $csv", csv.contains("0x2810,FOUND,11 22 33 44"))
        for ((name, text) in files) assertNoIdentifier(name, text)
    }

    @Test fun `a known sensor ID inside a probe reply on any DID reads 00 at export`() = runTest {
        runBcm()
        DiagnosticLogger.recordProbeSession("BCM", 0x726, 0x72E, listOf(
            DiagnosticLogger.ProbeResult(0x4001, "FOUND", "7A " + spaced(idBytes(IDS[3])) + " 7B")))
        assertTrue(exportNow().member("did_probe_").contains("0x4001,FOUND,7A 00 00 00 00 7B"))
    }

    // ── #527: CAN 0x398 ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun feed398() {
        val state = MutableStateFlow(VehicleState(isConnected = true))
        val own = LinkOwnership(); val gen = own.transition { own.begin() }
        val s = LinkSession(own, gen, state, Rec(), onFrameCounted = {}, perfRunSink = {})
        repeat(3) { s.onCanFrame(0x398, FRAME_398, 0.0); s.onCanFrame(0x0F8, OTHER_0F8, 0.0) }
    }

    @Test fun `0x398's text reads 00 in the raw log and ** in the inventory, its first two bytes and every other frame stay`() {
        feed398()
        val files = exportNow()
        val raw = files.member("slcan_log_")
        assertEquals(List(3) { "0102000000000000" }, frames(raw, "398"))
        assertEquals(List(3) { compact(OTHER_0F8) }, frames(raw, "0F8"))
        val inv = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("canFrameInventory")
        val row = inv.getJSONObject(inv.keys().asSequence().first { it.contains("398", ignoreCase = true) })
        assertEquals(3, row.getInt("totalReceived"))
        assertTrue(row.toString(), row.toString().contains("01 02 ** ** ** ** ** **"))
        for ((name, text) in files) assertNoIdentifier(name, text)
    }

    @Test fun `the summary and the JSON count the masked 0x398 frames`() {
        feed398()
        val files = exportNow()
        assertEquals(3, JSONObject(files.member("diagnostic_detail_")).getJSONObject("logLimits").getInt("frames398Masked"))
        assertTrue(files.member("diagnostic_summary_").contains("3 frame(s) of 0x398 masked"))
    }
}
