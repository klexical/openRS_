package com.openrs.dash

import com.openrs.dash.can.LinkPhase
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.PerformanceTimer
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * #212 ownership window through the production callers ([LinkSession.onCanFrame], [LinkSession.onVehicleState] —
 * what CanDataService's job collectors call). An old job's callback is held right after its ownership check, the
 * connection is reconnected and the new session gets going, then the old callback is released: it must not change
 * the new session's telemetry, derived values, session logging or recording state, and the new callbacks must work.
 */
class LinkSessionRaceTest {

    private class FakeRecorder : LinkSession.Recorder {
        override val activeDriveId: Long? = null
        val calls = mutableListOf<String>()
        override fun onEngineRunning(sessionId: Long) { calls += "engine:$sessionId" }
    }

    /** Holds the first callback that reaches the hook until [release]. */
    private class Gate {
        val reached = CountDownLatch(1); private val go = CountDownLatch(1); private var armed = true
        val hook: () -> Unit = { if (armed) { armed = false; reached.countDown(); go.await(5, TimeUnit.SECONDS) } }
        fun release() = go.countDown()
    }

    private val state = MutableStateFlow(VehicleState(isConnected = true))
    private val own = LinkOwnership()

    @Before fun freshLogger() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        FuelEconomy.reset(); PerformanceTimer.resetSession()
    }

    private fun session(gen: Int, rec: LinkSession.Recorder, frames: AtomicInteger, runs: MutableList<PerfRunEntity>,
                        hook: () -> Unit = {}) =
        LinkSession(own, gen, state, rec, onFrameCounted = { frames.incrementAndGet() }, perfRunSink = { runs += it },
            afterOwnershipCheck = hook)

    /** Reconnect as CanDataService does: old job loses ownership, session state is reset, a new job begins. */
    private fun reconnect(): Int = own.transition {
        own.end(LinkPhase.OFF)
        state.value = VehicleState(isConnected = true)
        FuelEconomy.reset(); PerformanceTimer.resetSession()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        own.begin()
    }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun `an old CAN frame held past its ownership check can't change the new session`() {
        val oldFrames = AtomicInteger(); val newFrames = AtomicInteger(); val runs = mutableListOf<PerfRunEntity>()
        val gate = Gate()
        val old = session(own.begin(), FakeRecorder(), oldFrames, runs, gate.hook)
        // 0x130 speed 100.00 km/h (bytes 6-7 BE × 0.01) — the old connection's last frame
        val t = Thread { old.onCanFrame(0x130, bytes("0000000000002710"), 50.0) }.apply { start() }
        assertTrue(gate.reached.await(2, TimeUnit.SECONDS))

        val newGen = reconnect()
        val current = session(newGen, FakeRecorder(), newFrames, runs)
        current.onCanFrame(0x090, bytes("0000C1FF617D0000"), 50.0)   // the new connection's frame (rpm)
        val afterNew = state.value

        gate.release(); t.join(2_000)

        assertEquals("telemetry: the old speed never lands", 0.0, state.value.speedKph, 0.0)
        assertEquals("the new frame is untouched", afterNew, state.value)
        assertTrue("the current callback works", state.value.rpm > 0)
        assertEquals("session frame count: old frame not counted", 0, oldFrames.get())
        assertEquals(1, newFrames.get())
        assertFalse("session logging: old frame not in the new session's inventory",
            DiagnosticLogger.frameInventorySnapshot.containsKey(0x130))
        assertTrue(DiagnosticLogger.frameInventorySnapshot.containsKey(0x090))
        assertEquals("derived: perf timer untouched", PerformanceTimer.State.IDLE, PerformanceTimer.state.value)
        assertTrue(runs.isEmpty())
    }

    @Test fun `an old engine-running report held past its ownership check never reaches the recorder`() {
        val rec = FakeRecorder(); val gate = Gate()
        val old = session(own.begin(), rec, AtomicInteger(), mutableListOf(), gate.hook)
        val running = VehicleState(isConnected = true, rpm = 1_200.0)
        val t = Thread { old.onVehicleState(running, { 11L }) }.apply { start() }
        assertTrue(gate.reached.await(2, TimeUnit.SECONDS))

        val current = session(reconnect(), rec, AtomicInteger(), mutableListOf())
        gate.release(); t.join(2_000)
        assertEquals("the stale decision did nothing", emptyList<String>(), rec.calls)

        current.onVehicleState(running, { 22L })
        assertEquals("the current session reports once per connection", listOf("engine:22"), rec.calls)
        current.onVehicleState(running, { 22L })
        assertEquals(listOf("engine:22"), rec.calls)
    }
}
