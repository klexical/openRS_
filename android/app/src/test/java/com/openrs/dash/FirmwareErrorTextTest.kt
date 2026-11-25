package com.openrs.dash

import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.EscCommand
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.FirmwareFailure
import com.openrs.dash.can.WebSocketSlcanTransport
import com.openrs.dash.can.WiFiFirmwareApi
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * #604 — a failed firmware command or WebSocket upgrade showed text the app did not write: the reply's status line
 * (reason text included) and any exception's message reached the drive mode dock, the DM_CMD / ESC_CMD events and the
 * export, and up to 80 characters of a refused upgrade's headers reached the STATE event and GARAGE's debug lines.
 * Every such failure now reads one of the fixed [FirmwareFailure] texts, with at most the HTTP status code's three digits.
 *
 * Production path: [WiFiFirmwareApi] against a local HTTP server → [executeDriveModeChange] / [EscCommand.execute];
 * [WebSocketSlcanTransport.open] against a local server. [MARK] stands for anything an adapter could put in a reply.
 */
@RunWith(RobolectricTestRunner::class)
class FirmwareErrorTextTest {

    private companion object { const val MARK = "ZQXREPLYTEXT" }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    /** Serves [reply] verbatim to the first connection, after reading the request's head. */
    private fun serveOnce(reply: String): Int {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.use { srv ->
                srv.accept().use { s ->
                    val r = BufferedReader(InputStreamReader(s.getInputStream()))
                    while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                    try { s.getOutputStream().apply { write(reply.toByteArray(Charsets.ISO_8859_1)); flush() } }
                    catch (_: IOException) { }
                }
            }
        }
        return server.localPort
    }

    /** A loopback port with nothing listening on it. */
    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun wifi(port: Int) = WiFiFirmwareApi(ApplicationProvider.getApplicationContext(), "127.0.0.1:$port")

    private fun events(type: String) = DiagnosticLogger.sessionEvents.filter { it.type == type }.map { it.message }

    private fun driveMode(sender: FirmwareCommandSender) = runBlocking {
        executeDriveModeChange(sender, DriveMode.SPORT, DriveMode.NORMAL, state = { VehicleState(isConnected = true) },
            connectionId = { 1 }, clock = { 0L }, firmwareLabel = { "test" })
    }

    private fun failedText(r: DriveCommandResult): String {
        assertTrue("$r", r is DriveCommandResult.Failed)
        return (r as DriveCommandResult.Failed).message
    }

    private fun assertNoForeignText(what: String, text: String, vararg foreign: String) {
        for (f in foreign) assertFalse("$what carries \"$f\": $text", text.contains(f, ignoreCase = true))
    }

    // ── /api/frs over Wi-Fi ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a refused drive mode request shows the status code only, never the reply's reason text`() {
        val port = serveOnce("HTTP/1.1 403 $MARK\r\nContent-Length: 0\r\n\r\n")
        val text = failedText(driveMode(wifi(port)))
        assertEquals(FirmwareFailure.driveModeFailed(FirmwareFailure.http(403)), text)
        assertNoForeignText("screen", text, MARK)
        assertNoForeignText("DM_CMD", events("DM_CMD").joinToString("\n"), MARK)
        assertTrue(events("DM_CMD").toString(), events("DM_CMD").any { it == "FAILED: ${FirmwareFailure.http(403)}" })
    }

    @Test fun `a 200 inside the reason text is not acceptance`() {
        val port = serveOnce("HTTP/1.1 500 x200 $MARK\r\nContent-Length: 0\r\n\r\n")
        assertEquals(FirmwareFailure.driveModeFailed(FirmwareFailure.http(500)), failedText(driveMode(wifi(port))))
    }

    @Test fun `an adapter that cannot be reached reads fixed text, never the socket's message`() {
        val text = failedText(driveMode(wifi(closedPort())))
        assertEquals(FirmwareFailure.driveModeFailed(FirmwareFailure.NOT_REACHED), text)
        assertNoForeignText("screen", text, "127.0.0.1", "refused", "Exception")
        assertNoForeignText("DM_CMD", events("DM_CMD").joinToString("\n"), "127.0.0.1", "refused", "Exception")
    }

    @Test fun `a refused ESC request logs the status code only`() {
        val port = serveOnce("HTTP/1.1 404 $MARK\r\nContent-Length: 0\r\n\r\n")
        val vs = VehicleState(isConnected = true, escStatus = EscStatus.ON)   // synthetic: ESC Normal reported
        val outcome = runBlocking { EscCommand.execute(wifi(port), EscStatus.OFF, { vs }, connectionId = { 1 }) }
        assertEquals(EscCommand.Outcome.Failed(FirmwareFailure.http(404)), outcome)
        assertEquals("FAILED — ${FirmwareFailure.http(404)}", EscCommand.describe(EscStatus.OFF, outcome))
    }

    // ── any sender: a foreign exception's message never reaches the screen or a log ────────────────────────────────

    private class Failing(val e: Throwable) : FirmwareCommandSender {
        override suspend fun setDriveMode(mode: Int): Result<Unit> = Result.failure(e)
        override suspend fun setEscMode(mode: Int): Result<Unit> = Result.failure(e)
    }

    @Test fun `a sender failing with another exception reads the generic fixed text`() {
        val text = failedText(driveMode(Failing(RuntimeException(MARK))))
        assertEquals(FirmwareFailure.driveModeFailed(FirmwareFailure.FAILED), text)
        assertNoForeignText("DM_CMD", events("DM_CMD").joinToString("\n"), MARK)

        val vs = VehicleState(isConnected = true, escStatus = EscStatus.ON)
        val outcome = runBlocking { EscCommand.execute(Failing(IOException(MARK)), EscStatus.OFF, { vs }, connectionId = { 1 }) }
        assertEquals(EscCommand.Outcome.Failed(FirmwareFailure.FAILED), outcome)
    }

    // ── WebSocket upgrade ───────────────────────────────────────────────────────────────────────────────────────────

    private fun upgradeError(reply: String): IOException {
        val t = WebSocketSlcanTransport("127.0.0.1", serveOnce(reply))
        try {
            runBlocking { t.open() }
        } catch (e: IOException) {
            return e
        } finally { t.close() }
        throw AssertionError("open() accepted: $reply")
    }

    @Test fun `a refused upgrade names the status code only, never the reply's headers`() {
        val e = upgradeError("HTTP/1.1 403 $MARK\r\nX-Info: $MARK\r\n\r\n")
        assertEquals("Upgrade failed: HTTP 403", e.message)
    }

    @Test fun `a 101 inside the reason text or a header is not an upgrade`() {
        assertEquals("Upgrade failed: HTTP 400", upgradeError("HTTP/1.1 400 x101\r\nX-Info: 101\r\n\r\n").message)
        assertEquals("Upgrade failed: no HTTP status code", upgradeError("$MARK 101\r\n\r\n").message)
    }
}
