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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The adapter's HTTP replies (`/check_status`, `/api/frs`) are read into memory, and the reply's own `Content-Length`
 * used to size that buffer. A reply that claimed a huge length (any HTTP server at the adapter's address can send one)
 * asked for a char array of that size: `Int.MAX_VALUE` is an `OutOfMemoryError` at once, which is an `Error`, so the
 * `catch (e: Exception)` of both requests let it out — for `/check_status` from a coroutine of `CanDataService`, which
 * ends the process. A reply with no `Content-Length` was read to its end with no limit at all.
 *
 * Now every body read is bounded by [WicanApi.MAX_REPLY_CHARS]: `/check_status` refuses a reply that claims or sends
 * more (null, logged as a fixed line), and `/api/frs` reads at most that much of the reply to look for busy. The head
 * (status line and headers, read with an unbounded `readLine()` before) is bounded by [WicanApi.MAX_HEAD_CHARS] in all
 * three requests: over it, `/check_status` and the reboot fail with a fixed log line and `/api/frs` fails.
 *
 * Production path: [WicanApi.checkStatus] and [FirmwareApi.setDriveMode] against a local HTTP server.
 */
@RunWith(RobolectricTestRunner::class)
class FirmwareHttpBodyLimitTest {

    /** Released in [tearDown]: a server told to hold its connection open keeps it until then. */
    private val release = CountDownLatch(1)

    @Before fun setUp() { ShadowLog.clear() }
    @After fun tearDown() { release.countDown(); ShadowLog.clear() }

    /**
     * Serves one HTTP reply of [head] (status line + headers, CRLF-joined) and [body] to the first connection. With
     * [holdOpen] the connection stays open after the reply, as the ESP-IDF server may keep it, so a read that waits
     * for the end of the reply instead of stopping at `Content-Length` runs into the 5 s read timeout.
     */
    private fun serveOnce(head: List<String>, body: String, holdOpen: Boolean = false): Int {
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
                    if (holdOpen) release.await(30, TimeUnit.SECONDS)
                }
            }
        }
        return server.localPort
    }

    private val validStatus = """{"fw_version":"4.48p","hw_version":"WiCAN-OBD-PRO","device_id":"0123abcd",""" +
        """"can_datarate":"500K","can_mode":"normal","protocol":"slcan","ble_status":"disable",""" +
        """"batt_voltage":"12.6V","sleep_status":"enable","sleep_volt":"13.1","wifi_mode":"AP"}"""

    private fun checkStatus(head: List<String>, body: String, holdOpen: Boolean = false) =
        serveOnce(head, body, holdOpen).let { port ->
            runBlocking { WicanApi.checkStatus(ApplicationProvider.getApplicationContext(), "127.0.0.1:$port") }
        }

    private fun setDriveMode(head: List<String>, body: String, holdOpen: Boolean = false) =
        serveOnce(head, body, holdOpen).let { port ->
            runBlocking { FirmwareApi.setDriveMode(ApplicationProvider.getApplicationContext(), "127.0.0.1:$port", 1) }
        }

    private fun reboot(head: List<String>) = serveOnce(head, "").let { port ->
        runBlocking { WicanApi.reboot(ApplicationProvider.getApplicationContext(), "127.0.0.1:$port") }
    }

    /** The head as sent: every line with its CRLF, then the blank line. */
    private fun headChars(head: List<String>) = head.sumOf { it.length + 2 } + 2

    /** A 200 head with one padding header, [total] characters long as sent. */
    private fun headOf(total: Int, vararg lines: String): List<String> {
        val base = listOf("HTTP/1.1 200 OK", *lines, "Connection: close")
        val pad = total - headChars(base) - ("X-Pad: ".length + 2)
        return (base + "X-Pad: ${"p".repeat(pad)}").also { assertEquals(total, headChars(it)) }
    }

    private val headTooLong = "reply head over ${WicanApi.MAX_HEAD_CHARS} characters"

    /** Runs [block] and returns its result with the time it took, in ms. */
    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
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

    // ── Content-Length ends the read, on a connection the server keeps open ──────────────────────────────────────────

    @Test fun `a check_status body stops at its Content-Length while the connection stays open`() {
        val (status, ms) = timed {
            checkStatus(listOf("HTTP/1.1 200 OK", "Content-Length: ${validStatus.length}"), validStatus, holdOpen = true)
        }
        assertNotNull("read to Content-Length, not to the 5 s read timeout", status)
        assertTrue("took $ms ms: the read waited for the end of the reply", ms < 3_000)
        assertTrue(wicanLines().isEmpty())
    }

    @Test fun `an api frs body stops at its Content-Length while the connection stays open`() {
        val busy = """{"ok":false,"busy":true}"""
        val (r, ms) = timed {
            setDriveMode(listOf("HTTP/1.1 200 OK", "Content-Length: ${busy.length}"), busy, holdOpen = true)
        }
        assertTrue("busy, not a read timeout: $r", r.exceptionOrNull() is BusyException)
        assertTrue("took $ms ms: the read waited for the end of the reply", ms < 3_000)
    }

    // ── the head (status line + headers) ─────────────────────────────────────────────────────────────────────────────

    @Test fun `a check_status head of exactly the limit is still read`() {
        val head = headOf(WicanApi.MAX_HEAD_CHARS, "Content-Length: ${validStatus.length}")
        assertNotNull(checkStatus(head, validStatus))
        assertTrue(wicanLines().isEmpty())
    }

    @Test fun `a check_status head one character over the limit is refused`() {
        val head = headOf(WicanApi.MAX_HEAD_CHARS + 1, "Content-Length: ${validStatus.length}")
        assertNull(checkStatus(head, validStatus))
        assertEquals(listOf("check_status failed: $headTooLong"), wicanLines())
    }

    @Test fun `a check_status status line longer than the limit is refused`() {
        val head = listOf("HTTP/1.1 200 " + "x".repeat(WicanApi.MAX_HEAD_CHARS), "Connection: close")
        assertNull(checkStatus(head, validStatus))
        assertEquals(listOf("check_status failed: $headTooLong"), wicanLines())
    }

    @Test fun `an api frs head over the limit is a failed command`() {
        val ok = """{"ok":true}"""
        val r = setDriveMode(headOf(WicanApi.MAX_HEAD_CHARS + 1, "Content-Length: ${ok.length}"), ok)
        val e = r.exceptionOrNull()
        assertTrue("$r", e is WicanApi.ReplyHeadTooLongException)
        assertEquals(headTooLong, e?.message)
        assertTrue(setDriveMode(headOf(WicanApi.MAX_HEAD_CHARS, "Content-Length: ${ok.length}"), ok).isSuccess)
    }

    @Test fun `a reboot reply whose status line is longer than the limit is refused`() {
        assertEquals(false, reboot(listOf("HTTP/1.1 200 " + "x".repeat(WicanApi.MAX_HEAD_CHARS), "Content-Length: 0")))
        assertEquals(listOf("reboot failed: $headTooLong"), wicanLines())
        ShadowLog.clear()
        assertEquals(true, reboot(listOf("HTTP/1.1 200 OK", "Content-Length: 0", "Connection: close")))
        assertTrue(wicanLines().isEmpty())
    }
}
