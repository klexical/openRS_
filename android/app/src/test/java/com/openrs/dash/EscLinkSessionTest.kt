package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.EscCommand
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.PerformanceTimer
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #125: 0x1C0 through the real LinkSession.onCanFrame / processCanFrame (real decode, `previous` as production passes it, real
 * merge), on the wall clock: EscStampThrottleTest calls stampFresh itself, so it can't see a mistake at that call. Synthetic frames.
 */
class EscLinkSessionTest {
    private class Rec : LinkSession.Recorder {
        override val activeDriveId: Long? = null
    }
    private class Api : FirmwareCommandSender {
        override suspend fun setDriveMode(mode: Int) = Result.success(Unit)
        override suspend fun setEscMode(mode: Int) = Result.success(Unit)
    }
    private val state = MutableStateFlow(VehicleState(isConnected = true))
    private val own = LinkOwnership()
    private val gen = own.transition { own.begin() }
    private val s = LinkSession(own, gen, state, Rec(), onFrameCounted = {}, perfRunSink = {})
    private val stamp get() = state.value.fieldLastUpdateMs[EscCommand.STAMP_TAG]
    private fun f(raw: Int) = s.onCanFrame(0x1C0, byteArrayOf(0, (raw shl 4).toByte(), 0, 0, 0, 0, 0, 0), 0.0)
    @Before fun fresh() { DiagnosticLogger.sessionStart("test", 0, prefs = null); FuelEconomy.reset(); PerformanceTimer.resetSession(); CanDecoder.resetSessionState() }

    @Test fun `first frame default ON no stamp - stamped by the due path`() {
        val t0 = System.currentTimeMillis(); f(0); assertTrue("$stamp", (stamp ?: 0) >= t0); assertEquals(EscStatus.ON, state.value.escStatus)
    }
    @Test fun `first frame SPORT on a blank state - stamped by the change path`() {
        val t0 = System.currentTimeMillis(); f(2); assertTrue("$stamp", (stamp ?: 0) >= t0); assertEquals(EscStatus.PARTIAL, state.value.escStatus)
    }
    @Test fun `change inside the throttle via LinkSession is stamped at once, unchanged next is not`() {
        f(0); val a = stamp!!
        Thread.sleep(30)
        f(0); assertEquals("unchanged inside throttle", a, stamp)
        f(2); val b = stamp!!; assertTrue("change stamped at once: $a -> $b", b >= a + 30)
        Thread.sleep(30)
        f(2); assertEquals("unchanged after change", b, stamp)
    }
    @Test fun `held state after a reconnect - same value inside 1 s not stamped, changed value stamped`() {
        // startConnection keeps VehicleState: the 'previous' of a new link's first frame is the old link's value + stamps
        f(1); val a = stamp!!
        val nextLink = LinkSession(own, gen, state, Rec(), onFrameCounted = {}, perfRunSink = {})
        fun g(raw: Int) = nextLink.onCanFrame(0x1C0, byteArrayOf(0, (raw shl 4).toByte(), 0, 0, 0, 0, 0, 0), 0.0)
        Thread.sleep(20); g(1); assertEquals(a, stamp)
        Thread.sleep(20); g(0); assertTrue("$a -> $stamp", stamp!! > a)
    }
    @Test fun `short 0x1C0 frame decodes to null - no stamp, state instance kept`() {
        val before = state.value
        s.onCanFrame(0x1C0, byteArrayOf(0), 0.0)
        assertTrue(before === state.value)
    }
    @Test fun `an older OBD snapshot merged after a change restores neither the value nor the stamp`() {
        f(0); val snap = state.value
        Thread.sleep(20); f(2); val b = stamp!!
        state.value = ObdMerge.merge(state.value, snap)
        assertEquals(EscStatus.PARTIAL, state.value.escStatus); assertEquals(b, stamp)
    }
    @Test fun `end to end on the wall clock - change inside the throttle confirms well inside 1 s`() = runBlocking {
        f(0)                                                   // unchanged stamp just before the request
        Thread.sleep(20)
        val job = launch { delay(300); f(2); delay(20); f(2) }                 // rule C: two frames after the request
        val o = EscCommand.execute(Api(), EscStatus.PARTIAL, { state.value }, connectionId = { gen })
        job.join()
        assertTrue("$o", o is EscCommand.Outcome.Confirmed && o.afterMs < 900L)
    }
}
