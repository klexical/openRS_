package com.openrs.dash.data

/**
 * A single fault code record returned by a DTC scan.
 *
 * @param module   Which ECU this code came from (e.g. "PCM", "BCM", "ABS").
 * @param code     The formatted DTC string (e.g. "P0234").
 * @param description Human-readable fault description, or empty if the code
 *                   is not in the bundled database.
 * @param status   Whether the fault is active, pending, or permanent.
 */
data class DtcResult(
    val module: String,
    val code: String,
    val description: String,
    val status: DtcStatus
)

enum class DtcStatus(val label: String) {
    ACTIVE("Active"),
    PENDING("Pending"),
    PERMANENT("Permanent"),
    UNKNOWN("Unknown")
}
