package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #252: a Sport / Track confirmation rests on details of that mode. 0x1B0 nibble 1 resolves through 0x420 bit 0, and Normal's 10C4 and
 * Drift's 12C4 have bit 0 = 0 like Sport's, so a stale pair of them used to corroborate Sport while the car was on its way to
 * Track. Real decoder and stamps on a virtual clock, the real wait, 0x1B0 every 100 ms and 0x420 every 600 ms at every 50 ms phase.
 * That 0x1B0 can lead 0x420 at all is this script's model: the order on a car is unknown. SYNTHETIC.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeDetailClassTest {
    private class Sender : FirmwareCommandSender {
        var calls = 0
        override suspend fun setDriveMode(mode: Int): Result<Unit> { calls++; return Result.success(Unit) }
        override suspend fun setEscMode(mode: Int): Result<Unit> = Result.success(Unit)
    }

    private class Car(var mode: DriveMode, val detail: (DriveMode) -> Int = ::defaultDetail) {
        var vs = VehicleState(isConnected = true, driveMode = mode)
        var sportClassDetails = 0
    }

    private companion object {
        const val REQUEST_MS = 3_000L
        const val LANDS_AFTER_MS = 4_000L
        val PHASES = 0L until 600L step 50L
        fun defaultDetail(m: DriveMode) = when (m) {
            DriveMode.NORMAL -> 0x10C4; DriveMode.SPORT -> 0x11CC; DriveMode.TRACK -> 0x11CD; else -> 0x12C4
        }
        fun nibble(m: DriveMode) = when (m) { DriveMode.NORMAL -> 0; DriveMode.DRIFT -> 2; else -> 1 }
    }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null); CanDecoder.resetSessionState() }

    private fun TestScope.feed(car: Car, id: Int, data: ByteArray) {
        val prev = car.vs
        val d = CanDecoder.decode(id, data, prev)
        if (d != null && d !== prev) car.vs = CanDecoder.stampFresh(id, prev, d, testScheduler.currentTime)
    }

    /** [gap]: 0x420 goes silent for that span of virtual time (capture A holds a 6.64 s gap); 0x1B0 carries on. */
    private fun TestScope.bus(car: Car, phase: Long, gap: LongRange? = null) {
        backgroundScope.launch {
            while (true) {
                feed(car, 0x1B0, ByteArray(8).also { it[6] = (nibble(car.mode) shl 4).toByte() })
                delay(100)
            }
        }
        backgroundScope.launch {
            delay(phase)
            while (true) {
                if (gap == null || testScheduler.currentTime !in gap) {
                    val v = car.detail(car.mode)
                    if (testScheduler.currentTime > REQUEST_MS && (v ushr 8) and 0xFB == 0x11) car.sportClassDetails++
                    feed(car, 0x420, ByteArray(8).also { it[6] = (v ushr 8).toByte(); it[7] = v.toByte() })
                }
                delay(600)
            }
        }
    }

    private class Out(val result: DriveCommandResult, val afterMs: Long, val calls: Int, val car: Car)

    private fun scenario(from: DriveMode, target: DriveMode, lands: DriveMode, phase: Long, detail: (DriveMode) -> Int = ::defaultDetail,
                         landsAfterMs: Long = LANDS_AFTER_MS, gap: LongRange? = null): Out {
        var out: Out? = null
        runTest {
            CanDecoder.resetSessionState()
            val car = Car(from, detail)
            bus(car, phase, gap)
            backgroundScope.launch { delay(REQUEST_MS + landsAfterMs); car.mode = lands }
            delay(REQUEST_MS)
            val sender = Sender()
            val current = car.vs.driveMode
            val r = executeDriveModeChange(sender, target, current, state = { car.vs }, connectionId = { 1 },
                clock = { testScheduler.currentTime }, firmwareLabel = { "t" })
            out = Out(r, testScheduler.currentTime - REQUEST_MS, sender.calls, car)
        }
        return out!!
    }

    private fun assertNoFalseSport(from: DriveMode) {
        val wrong = PHASES.filter { scenario(from, DriveMode.SPORT, DriveMode.TRACK, it).result == DriveCommandResult.Success }.toList()
        assertEquals("phases that confirmed Sport with the car landing on Track, from $from", emptyList<Long>(), wrong)
    }

    @Test fun `Sport requested from Normal, car lands on Track - never confirmed, at any 0x420 phase`() = assertNoFalseSport(DriveMode.NORMAL)

    @Test fun `Sport requested from Drift, car lands on Track - never confirmed, at any 0x420 phase`() = assertNoFalseSport(DriveMode.DRIFT)

    @Test fun `the one correction goes out for that overshoot, at every phase`() {
        for (p in PHASES) assertEquals("phase $p", 2, scenario(DriveMode.NORMAL, DriveMode.SPORT, DriveMode.TRACK, p).calls)
    }

    @Test fun `a real Sport landing confirms on two real Sport details, at every phase, inside the window`() {
        for (p in PHASES) {
            val o = scenario(DriveMode.NORMAL, DriveMode.SPORT, DriveMode.SPORT, p)
            assertEquals("phase $p", DriveCommandResult.Success, o.result)
            assertEquals("phase $p", 1, o.calls)
            assertTrue("phase $p: ${o.car.sportClassDetails} Sport details", o.car.sportClassDetails >= 2)
            // Landing at +4000, two details at most 1200 ms later, then a poll; the next report carries the pair at once
            assertTrue("phase $p: ${o.afterMs} ms", o.afterMs <= LANDS_AFTER_MS + 1_400L)
        }
    }

    private val longGap = (REQUEST_MS + LANDS_AFTER_MS - 100L)..(REQUEST_MS + LANDS_AFTER_MS - 100L + 6_640L)   // from capture A

    @Test fun `a 6_64 s 0x420 gap over the change - a Track landing never confirms Sport on the stale pair`() {
        for (p in PHASES) {
            val o = scenario(DriveMode.NORMAL, DriveMode.SPORT, DriveMode.TRACK, p, gap = longGap)
            assertNotEquals("phase $p: ${o.afterMs} ms", DriveCommandResult.Success, o.result)
            assertEquals("phase $p: the correction, once the car's Track is corroborated", 2, o.calls)
        }
    }

    @Test fun `a 6_64 s 0x420 gap over the change - a real Sport landing waits for its details and still confirms`() {
        for (p in PHASES) {
            val o = scenario(DriveMode.NORMAL, DriveMode.SPORT, DriveMode.SPORT, p, gap = longGap)
            assertEquals("phase $p", DriveCommandResult.Success, o.result)
            assertTrue("phase $p: ${o.car.sportClassDetails}", o.car.sportClassDetails >= 2)
            assertTrue("phase $p: ${o.afterMs} ms is after the gap", o.afterMs >= LANDS_AFTER_MS - 100L + 6_640L)
        }
    }

    @Test fun `the known risk, pinned - a real change late in the window plus a long gap reads NOT CONFIRMED, no correction`() {
        // Real captures: a Drift confirmed at 14,063 ms, a 6.64 s 0x420 gap. Together they end past the 17 s window. Hardware item.
        val late = (REQUEST_MS + 14_000L - 100L)..(REQUEST_MS + 14_000L - 100L + 6_640L)
        for (p in PHASES) {
            val o = scenario(DriveMode.NORMAL, DriveMode.SPORT, DriveMode.SPORT, p, landsAfterMs = 14_000L, gap = late)
            assertEquals("phase $p", DriveCommandResult.NoConfirmation, o.result)
            assertEquals("phase $p", 1, o.calls)
        }
    }

    @Test fun `control - a Track landing from Normal still confirms once, at every phase`() {
        for (p in PHASES) {
            val o = scenario(DriveMode.NORMAL, DriveMode.TRACK, DriveMode.TRACK, p)
            assertEquals("phase $p", DriveCommandResult.Success, o.result)
            assertEquals("phase $p", 1, o.calls)
        }
    }

    @Test fun `control - Track requested, car lands on Sport - never confirmed`() {
        for (p in PHASES) assertNotEquals("phase $p", DriveCommandResult.Success,
            scenario(DriveMode.NORMAL, DriveMode.TRACK, DriveMode.SPORT, p).result)
    }

    @Test fun `control - Normal and Drift need no 0x420 pair`() {
        for (p in PHASES) {
            assertEquals(DriveCommandResult.Success, scenario(DriveMode.SPORT, DriveMode.NORMAL, DriveMode.NORMAL, p).result)
            assertEquals(DriveCommandResult.Success, scenario(DriveMode.NORMAL, DriveMode.DRIFT, DriveMode.DRIFT, p).result)
        }
    }

    @Test fun `control - details with the launch-control bit still count for their mode`() {
        val lc: (DriveMode) -> Int = { m -> if (m == DriveMode.SPORT) 0x15CC else defaultDetail(m) }
        for (p in PHASES) assertEquals("phase $p", DriveCommandResult.Success, scenario(DriveMode.NORMAL, DriveMode.SPORT, DriveMode.SPORT, p, lc).result)
    }
}
