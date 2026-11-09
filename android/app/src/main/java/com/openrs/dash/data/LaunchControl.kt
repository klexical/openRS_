package com.openrs.dash.data

/**
 * Launch control, the one rule for every surface (#289; owner: show "LC", what the car's cluster shows).
 *
 * - The driver-facing indication is one small "LC", backed only by 0x420 byte 6 bit 2 (RS_HS.dbc "LaunchControlStatus") in a
 *   documented 0x420 frame (CanDecoder), and only while that frame is fresh on this connection: its stamp
 *   [TAG_420] is at most [FRESH_MS] old. The bit is not verified against the cluster's "LC" yet.
 * - 0x225 byte 5 bit 3 ([VehicleState.launchControlEngaged]) is MS-CAN per DigiCluster and has never been seen on HS on this
 *   car: diagnostics only, never on screen.
 * - The 0x731 probe DIDs (DE00 → `lcArmed`, DE01 → `lcRpmTarget`, DE02 → `assEnabled`) are undocumented, at the keyless
 *   module's address: diagnostics only, with their age.
 * - Both CAN stamps are cleared with both flags at connection start ([clearedForNewConnection]) and never come back
 *   through an OBD merge (ObdMerge), so a flag held from an earlier link never shows.
 */
object LaunchControl {
    /** Receipt of the latest documented 0x420 frame (every frame, not throttled). */
    const val TAG_420 = "lcStatus420"
    /** Receipt of the latest 0x225 frame. */
    const val TAG_225 = "lc225"
    /** CAN-only stamps: an OBD reply's snapshot never carries one back. */
    val CAN_TAGS = setOf(TAG_420, TAG_225)

    /** Provisional: two 0x420 periods (~600 ms each) plus margin. Not measured against the cluster. */
    const val FRESH_MS = 1_500L

    const val LABEL_420 = "DBC LaunchControlStatus (unverified against the cluster)"
    const val LABEL_225 = "0x225 active (MS-CAN per DigiCluster; not on HS on this car)"
    const val PROBE_SOURCE = "0x731 (keyless-module address), undocumented DIDs DE00 / DE01 / DE02"

    enum class Status(val text: String) { NOT_RECEIVED("not received"), STALE("stale"), OFF("off"), ON("on") }

    private fun status(vs: VehicleState, tag: String, flag: Boolean, nowMs: Long): Status {
        val at = vs.fieldLastUpdateMs[tag] ?: return Status.NOT_RECEIVED
        return when {
            !vs.isConnected || nowMs - at !in 0L..FRESH_MS -> Status.STALE
            flag -> Status.ON
            else -> Status.OFF
        }
    }

    fun status420(vs: VehicleState, nowMs: Long) = status(vs, TAG_420, vs.launchControlActive, nowMs)
    fun status225(vs: VehicleState, nowMs: Long) = status(vs, TAG_225, vs.launchControlEngaged, nowMs)

    /** The driver-facing "LC": connected, and a fresh documented 0x420 on this connection with bit 2 set. */
    fun shown(vs: VehicleState, nowMs: Long) = status420(vs, nowMs) == Status.ON

    /** At connection start: both flags off and both stamps gone (pure; for the CAS `update {}`). */
    fun clearedForNewConnection(vs: VehicleState) = vs.copy(
        launchControlActive = false, launchControlEngaged = false,
        fieldLastUpdateMs = vs.fieldLastUpdateMs - CAN_TAGS,
    )
}
