package com.openrs.dash

import com.openrs.dash.can.AdapterProbe
import com.openrs.dash.can.ProbeOutcome
import com.openrs.dash.can.TcpSlcanTransport
import com.openrs.dash.can.WebSocketSlcanTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * #324: an adapter network is one on which the adapter gave its own protocol reply. Real loopback sockets: a TCP accept
 * alone never passes (a captive portal or a router at the same address accepts too, QA's W1); a CR, a BEL (the SLCAN error
 * reply) or a WebSocket 101 does. Reasons tell a silent network from a refusing or a blocked one (W3), never with the host.
 */
class AdapterProbeTest {
    private val servers = mutableListOf<ServerSocket>()
    private val loopback = InetAddress.getLoopbackAddress()
    private val clock = { System.nanoTime() / 1_000_000 }
    @After fun close() { servers.forEach { runCatching { it.close() } } }

    /** A one-client server on loopback that runs [answer] on the accepted socket. */
    private fun server(answer: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 1, loopback); servers += ss
        thread(isDaemon = true) { runCatching { ss.accept().use { s -> answer(s) } } }
        return ss.localPort
    }
    private fun tcp(port: Int) = TcpSlcanTransport(loopback.hostAddress!!, port)
    private fun ws(port: Int) = WebSocketSlcanTransport(loopback.hostAddress!!, port)
    private fun Socket.readUntil(end: String) { val b = StringBuilder(); while (!b.endsWith(end)) { val c = getInputStream().read(); if (c < 0) return; b.append(c.toChar()) } }
    private fun probe(t: com.openrs.dash.can.SlcanTransport, overTcp: Boolean, limitMs: Long = 2_000L) =
        runBlocking { AdapterProbe.probe(t, overTcp, clock, limitMs) }
    private fun noHost(o: ProbeOutcome) {
        if (o is ProbeOutcome.NoAnswer) assertFalse(o.reason, loopback.hostAddress!! in o.reason)
    }

    @Test fun `the Pro's CR acknowledgement passes, and the transport stays open for the connection`() {
        val port = server { s -> s.readUntil("\r"); s.getOutputStream().write("\r".toByteArray()); Thread.sleep(2_000) }
        val o = probe(tcp(port), overTcp = true)
        assertTrue("$o", o is ProbeOutcome.Answered && o.answer == "CR")
        (o as ProbeOutcome.Answered).transport.close()
    }

    @Test fun `a BEL, the SLCAN error reply, is still the adapter answering`() {
        val port = server { s -> s.readUntil("\r"); s.getOutputStream().write("\u0007\r".toByteArray()); Thread.sleep(2_000) }
        val o = probe(tcp(port), overTcp = true)
        assertTrue("$o", o is ProbeOutcome.Answered && o.answer == "BEL")
        (o as ProbeOutcome.Answered).transport.close()
    }

    @Test fun `a TCP accept with no SLCAN answer is not an adapter network, and the limit ends the wait`() {
        val port = server { Thread.sleep(5_000) }
        val o = probe(tcp(port), overTcp = true, limitMs = 300L)
        assertTrue("$o", o is ProbeOutcome.NoAnswer && o.reason == "the adapter did not answer on this Wi-Fi network")
        assertTrue("took ${(o as ProbeOutcome.NoAnswer).ms} ms", o.ms < 2_000L)
        noHost(o)
    }

    @Test fun `a refused connection is told apart from a silent one`() {
        val ss = ServerSocket(0, 1, loopback); val port = ss.localPort; ss.close()
        val o = probe(tcp(port), overTcp = true)
        assertTrue("$o", o is ProbeOutcome.NoAnswer && o.reason == "the connection was refused on this Wi-Fi network")
        noHost(o)
    }

    @Test fun `a socket the network blocks (a lockdown VPN or a policy) reads blocked, not silent`() {
        val t = TcpSlcanTransport(loopback.hostAddress!!, 1, socketSource = { throw SocketException("connect failed: EACCES (Permission denied)") })
        val o = probe(t, overTcp = true)
        assertTrue("$o", o is ProbeOutcome.NoAnswer &&
            o.reason == "the connection was blocked on this Wi-Fi network (a VPN or a network policy)")
    }

    @Test fun `the USB adapter's WebSocket 101 passes`() {
        val port = server { s ->
            s.readUntil("\r\n\r\n")
            s.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
            Thread.sleep(2_000)
        }
        val o = probe(ws(port), overTcp = false)
        assertTrue("$o", o is ProbeOutcome.Answered && o.answer == "101")
        (o as ProbeOutcome.Answered).transport.close()
    }

    @Test fun `a captive portal's answer on port 80 is not the adapter's (QA's W1)`() {
        val port = server { s ->
            s.readUntil("\r\n\r\n")
            s.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://portal.example/login\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        val o = probe(ws(port), overTcp = false)
        assertTrue("$o", o is ProbeOutcome.NoAnswer && o.reason == "the adapter did not answer on this Wi-Fi network")
        noHost(o)
    }

    @Test fun `the answer kinds name the Pro's first line, never the line itself`() {
        assertEquals("CR", AdapterProbe.answerKind(""))
        assertEquals("BEL", AdapterProbe.answerKind("\u0007"))
        assertEquals("OPENRS", AdapterProbe.answerKind("OPENRS:1.4"))
        assertEquals("line", AdapterProbe.answerKind("t0F88010203040506070"))
    }
}
