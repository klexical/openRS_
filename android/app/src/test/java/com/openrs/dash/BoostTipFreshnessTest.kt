package com.openrs.dash

import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.PcmRequestLedger
import com.openrs.dash.can.PcmPollSchedule
import com.openrs.dash.data.BoostRule
import com.openrs.dash.data.BoostSource
import com.openrs.dash.data.PolledFreshness
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #357 — over Bluetooth 10–30 % of the fast group's replies did not arrive (one car, parked). While a drive records, the
 * fast group with MAP plus one slow DID at the 150 ms gap is a 1.35 s batch, so one missed TIP reply makes TIP's interval
 * 2.7 s; the old 2.5 s limit then switched the recorded source TIP → CAN 0x0F8 and back on every miss (53 changes in a
 * 4.5 minute idle run). On a link whose writes report sent (Wi-Fi, a Bluetooth adapter whose write succeeds) the missed
 * request also holds its slot for the poller's reply window (300 ms) instead of the gap. The limit tolerates one missed
 * reply on either path, and a real loss of TIP still switches the source, before the recorder's own 4 s limit (#279) is
 * reached. Pure, fixed times; BleWriteOutcomeTest measures the interval on the real poller.
 */
class BoostTipFreshnessTest {
    private val t0 = 1_000_000L
    /** Engine running, baro measured, TIP decoded at [tipAt], CAN 0x0F8 arriving with the link frames at [now]. */
    private fun vs(tipAt: Long, now: Long) = VehicleState(
        isConnected = true, rpm = 900.0, barometricPressure = 96.5, tipActualKpa = 158.2, boostKpa = 0.0,
        lastUpdate = now,
        fieldLastUpdateMs = mapOf(BoostRule.TIP_TAG to tipAt, BoostRule.CAN_TAG to now),
    )
    /** The recording batch from the schedule itself: the fast group with MAP, then one slow DID, at the batch's gap. */
    private val batch = PcmPollSchedule.nextBatch(slowIndex = 0, engineRunning = true, mapPoll = true)
    private val batchMs = batch.queries.size * batch.gapMs

    @Test fun `the recording batch the limit is stated from is 1,35 s (fast group with MAP + one slow DID, 150 ms each)`() {
        assertEquals(1_350L, batchMs)
        assertEquals(batchMs, PcmPollSchedule.recordingBatchMs())
    }

    @Test fun `one missed TIP reply at the recording batch period keeps the source on TIP`() {
        val next = t0 + 2 * batchMs + ObdConstants.PCM_FAST_GAP_MS / 2   // the reply after the missed one, a little late
        assertEquals(BoostSource.TIP_033E, BoostRule.read(vs(tipAt = t0, now = next - 1), next - 1).source)
    }

    @Test fun `one missed TIP reply on a reply-waiting link (the slot held for the reply window) keeps the source on TIP`() {
        // 2 batches, the missed request's slot at the reply window instead of the gap, and a 10 ms write
        val next = t0 + 2 * batchMs + (PcmRequestLedger.REPLY_WINDOW_MS - ObdConstants.PCM_FAST_GAP_MS) + 10L
        assertEquals(BoostSource.TIP_033E, BoostRule.read(vs(tipAt = t0, now = next), next).source)
    }

    @Test fun `TIP exactly at the limit is still fresh`() {
        val at = t0 + BoostRule.TIP_FRESH_MS
        assertEquals(BoostSource.TIP_033E, BoostRule.read(vs(tipAt = t0, now = at), at).source)
    }

    @Test fun `TIP absent for longer than the stated limit switches the source to CAN 0x0F8`() {
        val past = t0 + BoostRule.TIP_FRESH_MS + 1
        assertEquals(BoostSource.CAN_0F8, BoostRule.read(vs(tipAt = t0, now = past), past).source)
    }

    @Test fun `the limit is 3 s - two recording batches plus the reply window - and stays below the recorder's own limit (#279)`() {
        assertEquals(3_000L, BoostRule.TIP_FRESH_MS)
        assertTrue(BoostRule.TIP_FRESH_MS < 2 * PolledFreshness.FAST_REFRESH_MS)
    }
}
