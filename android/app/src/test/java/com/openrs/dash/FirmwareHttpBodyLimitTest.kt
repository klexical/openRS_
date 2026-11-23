package com.openrs.dash

import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.BusyException
import com.openrs.dash.can.FirmwareApi
import com.openrs.dash.can.WicanApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The adapter's HTTP replies (`/check_status`, `/api/frs`) are read into memory, and the reply's own `Content-Length`
 * used to size that buffer. A reply that claimed a huge length (any HTTP server at the adapter's address can send one)
 * asked for a char array of that size: `Int.MAX_VALUE` is an `OutOfMemoryError` at once, which is an `Error`, so the
 * `catch (e: Exception)` of both requests let it out — for `/check_status` from a coroutine of `CanDataService`, which
 * ends the process. A reply with no `Content-Length` was read to its end with no limit at all.
 *
 * Now every body read is bounded by [WicanApi.MAX_REPLY_CHARS]: `/check_status` refuses a reply that claims or sends
 * more (null, logged as a fixed line), and `/api/frs` reads at most that much of the reply to look for busy.
 *
 * Production path: [WicanApi.checkStatus] and [FirmwareApi.setDriveMode] against a local HTTP server.
 */
@RunWith(RobolectricTestRunner::class)
class FirmwareHttpBodyLimitTest {

    @Before fun setUp() { ShadowLog.clear() }
    @After fun tearDown() { ShadowLog.clear() }

    /** Serves one HTTP reply of [head] (status line + headers, CRLF-joined) and [body] to the first connection. */
    private fun serveOnce(head: List<String>, body: String): Int {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.use { srv ->
                srv.accept().use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                    val reply = head.joinToString("") { "$it\r\n" } + "\r\n" + body
                    try {
                        s.getOutputStream().apply { write(reply.toByteArray(Charsets.ISO_8859_1)); flush() }
                    } catch (_: java.io.IOException) { /* the client stopped reading: that is the point */ }
                }
            }
        }
        return server.localPort
    }

    private val validStatus = """{"fw_version":"4.48p","hw_version":"WiCAN-OBD-PRO","device_id":"0123abcd",""" +
        """"can_datarate":"500K","can_mode":"normal","protocol":"slcan","ble_status":"disable",""" +
        """"batt_voltage":"12.6V","sleep_status":"enable","sleep_volt":"13.1","wifi_mode":"AP"}"""

    private fun checkStatus(head: List<String>, body: String) = serveOnce(head, body).let { port ->
        runBlocking { WicanApi.checkStatus(ApplicationProvider.getApplicationContext(), "127.0.0.1:$port") }
    }

    private fun setDriveMode(head: List<String>, body: String) = serveOnce(head, body).let { port ->
        runBlocking { FirmwareApi.setDriveMode(ApplicationProvider.getApplicationContext(), "127.0.0.1:$port", 1) }
    }

    private fun wicanLines() = ShadowLog.getLogsForTag("WicanApi").map { it.msg }

    // ── /check_status ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a check_status reply claiming Int MAX_VALUE characters is refused, not an OutOfMemoryError`() {
        val status = checkStatus(listOf("HTTP/1.1 200 OK", "Content-Length: ${Int.MAX_VALUE}", "Connection: close"),
            validStatus)
        assertNull(status)
        assertEquals(listOf("check_status failed: reply over ${WicanApi.MAX_REPLY_CHARS} characters"), wicanLines())
    }

    @Test fun `a check_status reply claiming more than the limit is refused before its body is read`() {
        assertNull(checkStatus(listOf("HTTP/1.1 200 OK", "Content-Length: ${WicanApi.MAX_REPLY_CHARS + 1}",
            "Connection: close"), validStatus))
        assertEquals(listOf("check_status failed: reply over ${WicanApi.MAX_REPLY_CHARS} characters"), wicanLines())
    }

    @Test fun `a check_status reply with no Content-Length that runs past the limit is refused`() {
        // Valid JSON after leading whitespace: before the limit this parsed, read to its end with no bound.
        val body = " ".repeat(WicanApi.MAX_REPLY_CHARS) + validStatus
        assertNull(checkStatus(listOf("HTTP/1.1 200 OK", "Connection: close"), body))
        assertEquals(listOf("check_status failed: reply over ${WicanApi.MAX_REPLY_CHARS} characters"), wicanLines())
    }

    @Test fun `a check_status reply of exactly the limit is still read`() {
        val body = " ".repeat(WicanApi.MAX_REPLY_CHARS - validStatus.length) + validStatus
        assertEquals(WicanApi.MAX_REPLY_CHARS, body.length)
        assertNotNull(checkStatus(listOf("HTTP/1.1 200 OK", "Connection: close"), body))
        assertNotNull(checkStatus(listOf("HTTP/1.1 200 OK", "Content-Length: ${body.length}", "Connection: close"), body))
        assertTrue(wicanLines().isEmpty())
    }

    // ── /api/frs ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `an api frs reply claiming Int MAX_VALUE characters is read within the limit, not an OutOfMemoryError`() {
        val r = setDriveMode(listOf("HTTP/1.1 200 OK", "Content-Length: ${Int.MAX_VALUE}", "Connection: close"),
            """{"ok":false,"busy":true}""")
        assertTrue("busy is still seen: $r", r.exceptionOrNull() is BusyException)
    }

    @Test fun `an api frs reply claiming Int MAX_VALUE characters with no busy is accepted as before`() {
        val r = setDriveMode(listOf("HTTP/1.1 200 OK", "Content-Length: ${Int.MAX_VALUE}", "Connection: close"),
            """{"ok":true}""")
        assertTrue("$r", r.isSuccess)
    }

    @Test fun `an ordinary api frs reply keeps its meaning`() {
        val ok = """{"ok":true}"""
        val busy = """{"ok":false,"busy":true}"""
        assertTrue(setDriveMode(listOf("HTTP/1.1 200 OK", "Content-Length: ${ok.length}", "Connection: close"), ok)
            .isSuccess)
        assertTrue(setDriveMode(listOf("HTTP/1.1 200 OK", "Content-Length: ${busy.length}", "Connection: close"), busy)
            .exceptionOrNull() is BusyException)
    }
}
