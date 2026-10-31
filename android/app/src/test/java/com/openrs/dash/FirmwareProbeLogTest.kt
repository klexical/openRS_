package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * #220 — what the export says about the OPENRS? probe: transport kind, probe N of 3, time, write written / failed
 * (sanitized), and whether a reply was recognized — bounded, with no adapter text and no identifiers. The control gate
 * itself is unchanged (FirmwareProbeTest); "not detected" is never "stock".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareProbeLogTest {

    /** Streams 0x090 every 100 ms; answers probe [answerProbe] (0 = never); throws on probe write [failProbe]. */
    private class Adapter(
        private val scope: CoroutineScope, private val answerProbe: Int = 0, private val failProbe: Int = 0,
        private val noise: List<String> = emptyList(),
        override val label: String = "BLE WiC_secretid0001 (AA:BB:CC:DD:EE:FF)",
        /** Never sends a frame or a reply — the SLCAN handshake times out. */
        private val silent: Boolean = false,
    ) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED)
        var probes = 0
        override suspend fun open() {
            if (silent) return
            scope.launch {
                noise.forEach { lines.trySend(it) }
                while (isActive && lines.trySend("t09080000C1FF617D0000").isSuccess) delay(100L)
            }
        }
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            if (frame == "OPENRS?\r") {
                probes++
                if (probes == failProbe) throw IOException("BLE write couldn't be sent (stack busy) to AA:BB:CC:DD:EE:FF\u0007")
                if (answerProbe > 0 && probes == answerProbe) scope.launch { delay(200L); lines.trySend("OPENRS:PRO v1.2-rc.1") }
            }
        }
        override fun close() { lines.close() }
        override val stockFirmwareLabel = "WiCAN (BLE)"
        override val kind = "Bluetooth"
    }

    private fun TestScope.run(adapter: Adapter, seconds: Long): List<SlcanConnection.FirmwareStatus> {
        val seen = mutableListOf<SlcanConnection.FirmwareStatus>()
        val conn = SlcanConnection(
            transportFactory = { adapter }, maxRetries = 1, ioDispatcher = StandardTestDispatcher(testScheduler),
            debugSink = {}, clock = { testScheduler.currentTime }, firmwareSink = { seen += it },
        )
        val job = backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(seconds * 1_000L)
        job.cancel()
        return seen
    }

    private fun probeEvents() = DiagnosticLogger.sessionEvents.filter { it.type == "FIRMWARE" }.map { it.message }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @Test fun `a failed probe write is logged as failed, the next probe still goes out, the gate stays off`() = runTest {
        val adapter = Adapter(backgroundScope, failProbe = 2)
        val seen = run(adapter, 20)
        val ev = probeEvents()
        assertTrue(ev.toString(), ev.any { it.startsWith("Probe 1 of 3 written (Bluetooth") })
        assertTrue(ev.toString(), ev.any { it.startsWith("Probe 2 of 3 write FAILED (Bluetooth") && it.contains("IOException") })
        assertTrue(ev.toString(), ev.any { it.startsWith("Probe 3 of 3 written") })
        assertTrue(ev.toString(), ev.any { it.contains("no reply recognized to 2 written probes (3 attempted)") })
        assertEquals(3, adapter.probes)
        assertFalse(seen.last().openRs)
    }

    @Test fun `silence ends in not detected — capability unknown, never 'stock'`() = runTest {
        val seen = run(Adapter(backgroundScope), 20)
        val verdict = probeEvents().last()
        assertTrue(verdict, verdict.contains("no reply recognized to 3 written probes (3 attempted)"))
        assertTrue(verdict, verdict.contains("capability unknown"))
        assertFalse(verdict, verdict.contains("confirmed stock"))
        assertFalse(seen.last().openRs)
    }

    @Test fun `a late reply is recognized with the probe it answered`() = runTest {
        val seen = run(Adapter(backgroundScope, answerProbe = 3), 20)
        assertTrue(probeEvents().toString(), probeEvents().any { it.startsWith("Reply recognized: openRS_ PRO v1.2-rc.1 after probe 3 of 3 (Bluetooth") })
        assertTrue(seen.last().openRs)
    }

    @Test fun `probe events carry no adapter identifiers or adapter text, and non-frame lines are only counted`() = runTest {
        run(Adapter(backgroundScope, failProbe = 2, noise = listOf("\u0007", "WiC_secretid0001 hello", "zzz")), 20)
        val all = DiagnosticLogger.sessionEvents.filter { it.type == "FIRMWARE" }.joinToString("\n") { it.message }
        assertFalse(all, all.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(all, all.contains("secretid0001"))
        assertFalse(all, all.contains("hello"))
        assertFalse(all, all.contains("\u0007"))
        assertTrue(all, all.contains("3 non-frame lines ignored"))
        assertTrue("bounded", DiagnosticLogger.sessionEvents.count { it.type == "FIRMWARE" } <= 6)
    }

    @Test fun `Bluetooth detection never carries into a later Wi-Fi session`() = runTest {
        val ble = run(Adapter(backgroundScope, answerProbe = 1), 5)
        assertTrue(ble.last().openRs)
        assertTrue(DiagnosticLogger.isOpenRsFirmware)
        DiagnosticLogger.sessionStart("192.168.0.10", 35000, prefs = null)          // the next connection's session
        assertFalse("new session starts undetected", DiagnosticLogger.isOpenRsFirmware)
        val wifi = run(Adapter(backgroundScope, label = "TCP 192.168.0.10:35000").also { }, 20)
        assertTrue("probing first", wifi.first().probing && !wifi.first().openRs)
        assertTrue(wifi.none { it.openRs })
        assertFalse(DiagnosticLogger.isOpenRsFirmware)
        assertTrue(probeEvents().none { it.startsWith("Reply recognized") })
    }

    @Test fun `sanitized reasons keep the class and message but mask identifiers and control characters`() {
        val r = com.openrs.dash.diagnostics.LogText.sanitizeReason(
            IOException("write to WiC_0123456789abc (02:1A:2B:3C:4D:5E) failed\u0007 id 0a1b2c3d4e5f " + "x".repeat(200)))
        assertTrue(r, r.startsWith("IOException: write to <device> (<mac>) failed"))
        assertTrue(r, r.contains("id <id>"))
        assertFalse(r, r.contains("\u0007"))
        assertTrue(r.length <= 100)
    }

    // ── Source review 2026-10-31: probe 1 is logged even when the attempt fails ──

    @Test fun `a failed first probe write is logged with kind, number, time and sanitized reason — and the attempt still retries`() = runTest {
        val first = Adapter(backgroundScope, failProbe = 1)
        var attempts = 0
        val debug = mutableListOf<String>()                                       // AdapterState.Error is conflated away
        val conn = SlcanConnection(
            transportFactory = { attempts++; if (attempts == 1) first else Adapter(backgroundScope) }, maxRetries = 2,
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = { debug += it }, clock = { testScheduler.currentTime },
        )
        val job = backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(20_000L)
        job.cancel()
        val ev = probeEvents()
        val failed = ev.firstOrNull { it.startsWith("Probe 1 of 3 write FAILED (Bluetooth, +") }
        assertTrue(ev.toString(), failed != null && failed.contains("IOException: BLE write couldn't be sent"))
        assertFalse(failed!!, failed.contains("AA:BB:CC:DD:EE:FF") || failed.contains("\u0007"))
        assertTrue("unchanged: the failed write fails the attempt", debug.any { it.startsWith("Error: BLE write couldn't be sent") })
        assertEquals("unchanged: the next attempt runs", 2, attempts)
        assertTrue(ev.toString(), ev.indexOfFirst { it.startsWith("Probe 1 of 3 written") } > ev.indexOf(failed))
    }

    @Test fun `probe 1 written, then handshake silence — the write and the missing verdict are both logged`() = runTest {
        val adapter = Adapter(backgroundScope, silent = true)
        val seen = run(adapter, 10)
        val ev = probeEvents()
        assertEquals(1, adapter.probes)
        assertTrue(ev.toString(), ev.any { it.startsWith("Probe 1 of 3 written (Bluetooth, +") })
        assertTrue(ev.toString(), ev.any { it.startsWith("No probe verdict — the SLCAN handshake ended without a frame or reply") })
        assertTrue(ev.toString(), ev.none { it.startsWith("Reply recognized") || it.startsWith("Not detected") })
        assertTrue("never claimed openRS_", seen.none { it.openRs })
    }
}
