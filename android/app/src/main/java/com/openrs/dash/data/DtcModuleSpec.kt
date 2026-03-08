package com.openrs.dash.data

/**
 * Describes one ECU module to scan or clear DTCs on.
 *
 * [requestId]  — CAN ID the app sends UDS requests to
 * [responseId] — CAN ID the ECU responds on
 */
data class DtcModuleSpec(
    val name: String,
    val requestId: Int,
    val responseId: Int
)
