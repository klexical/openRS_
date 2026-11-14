package com.openrs.dash

import com.openrs.dash.can.ProbeOutcome
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.service.AdapterNetworkFinder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #324: the adapter network is found among the joined Wi-Fi networks by probing each, then re-probing every 5 s for up to
 * 60 s after it appeared (an adapter's access point can be up before its server listens, QA's W2). Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdapterNetworkFinderTest {
    private class FakeTransport : SlcanTransport {
        var closed = false
        override suspend fun open() {}
        override suspend fun readLine(): String? = null
        override suspend fun writeLine(frame: String) {}
        override fun close() { closed = true }
        override val label = "fake"; override val stockFirmwareLabel = "fake"
    }

    private class Rig(val scope: TestScope, answerAtProbe: Int = Int.MAX_VALUE, var may: Boolean = true) {
        val probes = mutableListOf<Long>()
        val found = mutableListOf<Pair<String, List<String>>>()
        val logs = mutableListOf<Pair<String, String>>()
        val finder = AdapterNetworkFinder<String>(
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
            probe = { _ ->
                probes += scope.testScheduler.currentTime
                if (probes.size >= answerAtProbe) ProbeOutcome.Answered(FakeTransport(), "CR", 40)
                else ProbeOutcome.NoAnswer("the adapter did not answer on this Wi-Fi network", 2_000)
            },
            mayProbe = { may },
            onFound = { n, _, lines -> found += n to lines },
            log = { t, m -> logs += t to m },
        )
        fun refused() = logs.filter { it.first == AdapterNetworkFinder.EVENT_REFUSED }.map { it.second }
    }

    @Test fun `an adapter whose server starts late is found by a re-probe`() = runTest {
        val r = Rig(this, answerAtProbe = 3)
        r.finder.networkAvailable("wifi-1"); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(listOf(0L, 5_000L, 10_000L), r.probes)
        assertEquals("wifi-1", r.found.single().first)
        assertEquals(3, r.found.single().second.size)                       // the probe lines go to the new session
        assertEquals("wifi-1", r.finder.adapterNetwork)
        assertEquals(1, r.refused().size)                                     // the first refusal only, not one per probe
    }

    @Test fun `a network that never answers is probed for 60 s, then one gave-up line, and nothing more`() = runTest {
        val r = Rig(this)
        r.finder.networkAvailable("home"); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals((0..12).map { it * 5_000L }, r.probes)                   // 0 to 60 s: 13 probes
        assertTrue(r.found.isEmpty())
        val refused = r.refused()
        assertEquals(2, refused.size)
        assertTrue(refused[0], refused[0].startsWith("not started (automatic start): not an adapter network yet:"))
        assertEquals("not started (automatic start): no adapter network: gave up after 60 s (13 probes)", refused[1])
        assertEquals(13, r.logs.count { it.first == AdapterNetworkFinder.EVENT_PROBE })
        assertNull(r.finder.adapterNetwork)
    }

    @Test fun `a lost network stops its probing and is no longer the adapter network`() = runTest {
        val r = Rig(this)
        r.finder.networkAvailable("wifi-1"); runCurrent()
        advanceTimeBy(6_000); runCurrent()
        r.finder.networkLost("wifi-1")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(0L, 5_000L), r.probes)
        assertNull(r.finder.adapterNetwork)
    }

    @Test fun `the same network reported twice is probed once`() = runTest {
        val r = Rig(this)
        r.finder.networkAvailable("wifi-1"); r.finder.networkAvailable("wifi-1"); runCurrent()
        assertEquals(listOf(0L), r.probes)
    }

    @Test fun `nothing is probed while probing is not allowed (Bluetooth, demo, a hold, a connection running)`() = runTest {
        val r = Rig(this, may = false)
        r.finder.networkAvailable("wifi-1"); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertTrue(r.probes.isEmpty()); assertTrue(r.logs.isEmpty())
    }

    @Test fun `only the network that answers is the adapter network`() = runTest {
        val probed = mutableListOf<String>()
        val found = mutableListOf<String>()
        val finder = AdapterNetworkFinder<String>(
            scope = backgroundScope, clock = { testScheduler.currentTime },
            probe = { n -> probed += n; if (n == "adapter") ProbeOutcome.Answered(FakeTransport(), "101", 30)
                           else ProbeOutcome.NoAnswer("the adapter did not answer on this Wi-Fi network", 2_000) },
            mayProbe = { true }, onFound = { n, _, _ -> found += n }, log = { _, _ -> },
        )
        finder.networkAvailable("home"); finder.networkAvailable("adapter"); runCurrent()
        assertEquals(listOf("adapter"), found)
        assertEquals("adapter", finder.adapterNetwork)
    }
}
