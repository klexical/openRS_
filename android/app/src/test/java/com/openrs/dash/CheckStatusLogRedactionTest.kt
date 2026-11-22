package com.openrs.dash

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.DeviceStatus
import com.openrs.dash.can.WicanApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The adapter's `/check_status` reply carries its Wi-Fi and MQTT passwords in plain text, so no part of it may reach the
 * phone's log (#370). Android's `org.json` repeats its whole input in a parse error's message, and the old catch logged
 * that exception, so a truncated, non-object or non-JSON reply put the passwords into logcat (adb, bug reports; release
 * builds keep debug log calls).
 *
 * Production path: [WicanApi.checkStatus] against a local HTTP server that answers like the adapter, then every line
 * Robolectric captured from `android.util.Log` (message and throwable, as `Log.getStackTraceString` would print it).
 * Robolectric runs Android's own `org.json`, the one whose messages repeat the input. The password value is a synthetic
 * marker and the field names are illustrative: nothing here is a real key.
 */
@RunWith(RobolectricTestRunner::class)
class CheckStatusLogRedactionTest {

    private val marker = "SYNTHETIC-PASSWORD-MARKER-370"

    @Before fun setUp() { ShadowLog.clear() }
    @After fun tearDown() { ShadowLog.clear() }

    /** Serves one HTTP reply of [statusLine], [headers] and [body] to the first connection, then closes it. */
    private fun serveOnce(statusLine: String, body: String, headers: List<String>): Int {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.use { srv ->
                srv.accept().use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                    val reply = buildString {
                        append(statusLine).append("\r\n")
                        headers.forEach { append(it).append("\r\n") }
                        append("\r\n").append(body)
                    }
                    s.getOutputStream().apply { write(reply.toByteArray(Charsets.ISO_8859_1)); flush() }
                }
            }
        }
        return server.localPort
    }

    private fun checkStatus(statusLine: String, body: String, headers: List<String> = listOf(
        "Content-Type: application/json", "Content-Length: ${body.length}", "Connection: close")): DeviceStatus? {
        val port = serveOnce(statusLine, body, headers)
        return runBlocking { WicanApi.checkStatus(ApplicationProvider.getApplicationContext(), "127.0.0.1:$port") }
    }

    /** Everything the app logged, as logcat would show it: each line's message plus its throwable's full trace. */
    private fun everythingLogged(): String = ShadowLog.getLogs().joinToString("\n") {
        "${it.tag}: ${it.msg}" + (it.throwable?.let { t -> "\n" + Log.getStackTraceString(t) } ?: "")
    }

    private fun assertMarkerNeverLogged(case: String) {
        val logged = everythingLogged()
        assertFalse("$case: the reply's password reached the log:\n$logged", logged.contains(marker))
    }

    private fun wicanLines() = ShadowLog.getLogsForTag("WicanApi")

    private val replyFields = """"fw_version":"4.48p","hw_version":"WiCAN-OBD-PRO","device_id":"0123abcd",""" +
        """"can_datarate":"500K","can_mode":"normal","protocol":"slcan","ble_status":"disable",""" +
        """"batt_voltage":"12.6V","sleep_status":"enable","sleep_volt":"13.1","wifi_mode":"AP""""

    @Test fun `a truncated reply never puts the password in the log`() {
        // The adapter promises the whole reply but the connection ends inside the password's value.
        val full = """{$replyFields,"sta_pass":"$marker","mqtt_pass":"$marker"}"""
        val cut = full.substring(0, full.indexOf(marker) + marker.length)
        val status = checkStatus("HTTP/1.1 200 OK", cut, listOf("Content-Length: ${full.length}", "Connection: close"))
        assertNull(status)
        assertMarkerNeverLogged("truncated reply")
        assertTrue("the failure is still logged, by class name", wicanLines().any { it.msg.contains("JSONException") })
    }

    @Test fun `a JSON array reply never puts the password in the log`() {
        assertNull(checkStatus("HTTP/1.1 200 OK", """[{"sta_pass":"$marker"}]"""))
        assertMarkerNeverLogged("array reply")
    }

    @Test fun `a JSON string reply never puts the password in the log`() {
        assertNull(checkStatus("HTTP/1.1 200 OK", "\"sta_pass=$marker\""))
        assertMarkerNeverLogged("string reply")
    }

    @Test fun `a 200 reply that is not JSON never puts its body in the log`() {
        assertNull(checkStatus("HTTP/1.1 200 OK", "<html>$marker</html>",
            listOf("Content-Type: text/html", "Connection: close")))     // no Content-Length: read to EOF
        assertMarkerNeverLogged("non-JSON reply")
    }

    @Test fun `a non-200 reply logs the status code alone, never its reason text`() {
        assertNull(checkStatus("HTTP/1.1 500 $marker", "{\"sta_pass\":\"$marker\"}"))
        assertMarkerNeverLogged("non-200 reply")
        val line = wicanLines().single()
        assertEquals("check_status failed: HTTP 500", line.msg)
        assertNull("no throwable is logged", line.throwable)
    }

    @Test fun `no failure line carries a throwable`() {
        checkStatus("HTTP/1.1 200 OK", "{\"sta_pass\":\"$marker\"")
        assertTrue(wicanLines().isNotEmpty())
        assertTrue("a throwable's message is the reply", wicanLines().all { it.throwable == null })
    }

    @Test fun `a valid reply still yields the same eleven fields and logs nothing`() {
        val status = checkStatus("HTTP/1.1 200 OK", """{$replyFields,"sta_pass":"$marker","mqtt_pass":"$marker"}""")
        assertEquals(DeviceStatus(
            firmwareVersion = "4.48p", hardwareVersion = "WiCAN-OBD-PRO", deviceId = "0123abcd", canDatarate = "500K",
            canMode = "normal", protocol = "slcan", bleStatus = "disable", obdPortVoltage = 12.6, sleepStatus = "enable",
            sleepVolt = "13.1", wifiMode = "AP"), status)
        assertFalse("no field holds the password", status.toString().contains(marker))
        assertTrue(wicanLines().isEmpty())
        assertMarkerNeverLogged("valid reply")
    }

    @Test fun `the status code is read alone from the status line`() {
        assertEquals(404, WicanApi.httpStatusCode("HTTP/1.1 404 Not Found"))
        assertEquals(500, WicanApi.httpStatusCode("HTTP/1.0 500"))
        assertEquals(500, WicanApi.httpStatusCode("HTTP/1.1 500 x200"))
        assertNull(WicanApi.httpStatusCode("HTTP/1.1 50x $marker"))
        assertNull(WicanApi.httpStatusCode(marker))
    }

    @Test fun `a 500 reply whose reason text holds 200 is a failure`() {
        assertNull(checkStatus("HTTP/1.1 500 x200", """{$replyFields}"""))
        assertEquals("check_status failed: HTTP 500", wicanLines().single().msg)
    }

    @Test fun `a reply with no status code is logged as such`() {
        assertNull(checkStatus("200 $marker", """{$replyFields}"""))
        assertMarkerNeverLogged("no status code")
        assertEquals("check_status failed: no HTTP status code", wicanLines().single().msg)
    }

    private fun reboot(port: Int): Boolean =
        runBlocking { WicanApi.reboot(ApplicationProvider.getApplicationContext(), "127.0.0.1:$port") }

    @Test fun `a failed reboot logs the exception's class name alone, with no throwable`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }   // nothing listens
        assertFalse(reboot(closedPort))
        val line = wicanLines().single()
        assertEquals("reboot failed: ConnectException", line.msg)
        assertNull("no throwable is logged", line.throwable)
    }

    @Test fun `a refused reboot logs the status code alone, and 200 in its reason text is no success`() {
        assertFalse(reboot(serveOnce("HTTP/1.1 500 $marker x200", "", listOf("Content-Length: 0", "Connection: close"))))
        assertMarkerNeverLogged("refused reboot")
        assertEquals("reboot failed: HTTP 500", wicanLines().single().msg)
    }

    @Test fun `an acknowledged reboot logs nothing`() {
        assertTrue(reboot(serveOnce("HTTP/1.1 200 OK", "", listOf("Content-Length: 0", "Connection: close"))))
        assertTrue(wicanLines().isEmpty())
    }
}
