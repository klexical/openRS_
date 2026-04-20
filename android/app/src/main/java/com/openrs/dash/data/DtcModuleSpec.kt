package com.openrs.dash.data

/**
 * Describes one ECU module to scan or clear DTCs on.
 *
 * [requestId]  — CAN ID the app sends UDS requests to
 * [responseId] — CAN ID the ECU responds on
 * [needsExtSession] — true if the ECU requires UDS 0x10/03 before DTC queries
 * [extSessionFrame] — pre-formatted SLCAN frame for extended diagnostic session
 */
data class DtcModuleSpec(
    val name: String,
    val requestId: Int,
    val responseId: Int,
    val needsExtSession: Boolean = false,
    val extSessionFrame: String? = null
)
