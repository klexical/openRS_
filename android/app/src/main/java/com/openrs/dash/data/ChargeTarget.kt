package com.openrs.dash.data

import com.openrs.dash.can.ObdConstants

/**
 * PCM 0x0304, the charging system's target (#365), as DRIVE and the Custom Dashboard may show it. With the engine off it
 * holds a fixed floor, not a measurement (#223), and the engine-off sweep's reply stays fresh into a start until the
 * running schedule asks 0x0304 again (up to 20 batches later). So a reply decoded while the engine runs (the reply's
 * snapshot has rpm above [ObdConstants.FAST_POLL_MIN_RPM]) also stamps [RUNNING_TAG], and only a value whose latest reply
 * carried that stamp is shown, with the engine running now. GARAGE → DIAG shows the raw value whatever the engine does.
 */
object ChargeTarget {
    /** The value's own freshness tag (PolledFreshness, ObdMerge's carried rule). */
    const val TAG = "batteryVoltage"
    /** The raw value's label on GARAGE → DIAG and in the diagnostic report. */
    const val DIAG_LABEL = "CHG (PCM)"
    /** Stamped beside [TAG] by a 0x0304 reply decoded while the engine runs. */
    const val RUNNING_TAG = "batteryVoltageRunning"

    /** The tags a 0x0304 reply decoded against [snapshot] stamps. */
    fun replyTags(snapshot: VehicleState): Array<String> =
        if (snapshot.rpm > ObdConstants.FAST_POLL_MIN_RPM) arrayOf(TAG, RUNNING_TAG) else arrayOf(TAG)

    /** The engine runs now, and the value came from a reply received while it ran (no newer engine-off reply). */
    fun showable(vs: VehicleState): Boolean {
        if (vs.batteryVoltage <= 0 || vs.rpm <= ObdConstants.FAST_POLL_MIN_RPM) return false
        val valueAt = vs.fieldLastUpdateMs[TAG] ?: return false
        val runningAt = vs.fieldLastUpdateMs[RUNNING_TAG] ?: return false
        return runningAt >= valueAt
    }
}
