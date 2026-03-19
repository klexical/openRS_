package com.openrs.dash.can

/**
 * Shared OBD polling constants for all CAN adapter implementations.
 *
 * Contains ISO-TP query frames (SLCAN-formatted), ECU response CAN IDs,
 * polling intervals, and UDS extended-session open frames.
 *
 * All frames target the Ford Focus RS MK3 HS-CAN bus (500 kbps).
 */
object ObdConstants {

    val BACKOFF_MS = listOf(5_000L, 15_000L, 30_000L)

    const val SLCAN_INIT = "C\rS6\rO\r"

    // ── BCM OBD Mode 22 (0x726 → 0x72E) ─────────────────────────────────────
    const val BCM_RESPONSE_ID = 0x72E

    const val BCM_QUERY_SOC        = "t72680322402800000000\r"
    const val BCM_QUERY_BATT_TEMP  = "t72680322402900000000\r"
    const val BCM_QUERY_CABIN_TEMP = "t72680322DD0400000000\r"
    const val BCM_QUERY_TPMS_LF   = "t72680322281300000000\r"
    const val BCM_QUERY_TPMS_RF   = "t72680322281400000000\r"
    const val BCM_QUERY_TPMS_LR   = "t72680322281600000000\r"
    const val BCM_QUERY_TPMS_RR   = "t72680322281500000000\r"
    val BCM_QUERIES = listOf(
        BCM_QUERY_SOC, BCM_QUERY_BATT_TEMP, BCM_QUERY_CABIN_TEMP,
        BCM_QUERY_TPMS_LF, BCM_QUERY_TPMS_RF, BCM_QUERY_TPMS_LR, BCM_QUERY_TPMS_RR
    )
    const val BCM_QUERY_ODOMETER   = "t72680322DD0100000000\r"
    const val BCM_POLL_INTERVAL_MS = 30_000L
    const val BCM_QUERY_GAP_MS     =    300L
    const val BCM_INITIAL_DELAY_MS =  5_000L

    // ── AWD module OBD Mode 22 (0x703 → 0x70B) ──────────────────────────────
    const val AWD_RESPONSE_ID      = 0x70B
    const val AWD_QUERY_RDU_TEMP   = "t703803221E8A00000000\r"
    val AWD_QUERIES = listOf(AWD_QUERY_RDU_TEMP)

    // ── PCM OBD Mode 22 (0x7E0 → 0x7E8) ─────────────────────────────────────
    const val PCM_RESPONSE_ID = 0x7E8

    const val PCM_QUERY_ETC_ACTUAL   = "t7E080322093C00000000\r"
    const val PCM_QUERY_ETC_DESIRED  = "t7E080322091A00000000\r"
    const val PCM_QUERY_WGDC         = "t7E080322046200000000\r"
    const val PCM_QUERY_KR_CYL1      = "t7E08032203EC00000000\r"
    const val PCM_QUERY_OAR          = "t7E08032203E800000000\r"
    const val PCM_QUERY_CHARGE_AIR   = "t7E080322046100000000\r"
    const val PCM_QUERY_CAT_TEMP     = "t7E080322F43C00000000\r"
    const val PCM_QUERY_AFR_ACTUAL   = "t7E080322F43400000000\r"
    const val PCM_QUERY_AFR_DESIRED  = "t7E080322F44400000000\r"
    const val PCM_QUERY_TIP_ACTUAL   = "t7E080322033E00000000\r"
    const val PCM_QUERY_TIP_DESIRED  = "t7E080322046600000000\r"
    const val PCM_QUERY_VCT_INTAKE   = "t7E080322031800000000\r"
    const val PCM_QUERY_VCT_EXHAUST  = "t7E080322031900000000\r"
    const val PCM_QUERY_OIL_LIFE     = "t7E080322054B00000000\r"
    const val PCM_QUERY_HP_FUEL_RAIL = "t7E080322F42200000000\r"
    const val PCM_QUERY_FUEL_LEVEL   = "t7E080322F42F00000000\r"
    const val PCM_QUERY_BATTERY     = "t7E080322030400000000\r"
    val PCM_QUERIES = listOf(
        PCM_QUERY_ETC_ACTUAL, PCM_QUERY_ETC_DESIRED,
        PCM_QUERY_WGDC, PCM_QUERY_KR_CYL1, PCM_QUERY_OAR,
        PCM_QUERY_CHARGE_AIR, PCM_QUERY_CAT_TEMP,
        PCM_QUERY_AFR_ACTUAL, PCM_QUERY_AFR_DESIRED,
        PCM_QUERY_TIP_ACTUAL, PCM_QUERY_TIP_DESIRED,
        PCM_QUERY_VCT_INTAKE, PCM_QUERY_VCT_EXHAUST,
        PCM_QUERY_OIL_LIFE, PCM_QUERY_HP_FUEL_RAIL, PCM_QUERY_FUEL_LEVEL,
        PCM_QUERY_BATTERY
    )
    const val PCM_POLL_INTERVAL_MS  = 30_000L
    const val PCM_QUERY_GAP_MS      =    200L
    const val PCM_INITIAL_DELAY_MS  = 20_000L

    // ── Extended diagnostic session (UDS 10 03 + Mode 22) ────────────────────
    const val PSCM_RESPONSE_ID   = 0x738
    const val FENG_RESPONSE_ID   = 0x72F
    const val RSPROT_RESPONSE_ID = 0x739

    const val EXT_SESSION_BCM    = "t72680210030000000000\r"
    const val EXT_SESSION_AWD    = "t70380210030000000000\r"
    const val EXT_SESSION_PSCM   = "t73080210030000000000\r"
    const val EXT_SESSION_FENG   = "t72780210030000000000\r"
    const val EXT_SESSION_RSPROT = "t73180210030000000000\r"

    const val AWD_QUERY_RDU_STATUS = "t70380322ee0b00000000\r"
    const val PSCM_QUERY_PDC       = "t73080322fd0700000000\r"
    const val FENG_QUERY_STATUS    = "t72780322ee0300000000\r"

    val RSPROT_PROBE_QUERIES = listOf(
        "t73180322de0000000000\r",
        "t73180322de0100000000\r",
        "t73180322de0200000000\r",
        "t73180322ee0100000000\r",
        "t73180322fd0100000000\r"
    )
    const val EXT_POLL_INTERVAL_MS = 60_000L
    const val EXT_INITIAL_DELAY_MS = 15_000L
    const val EXT_SESSION_GAP_MS   =    150L
    const val EXT_QUERY_GAP_MS     =    300L

    val OBD_RESPONSE_IDS = setOf(
        BCM_RESPONSE_ID, AWD_RESPONSE_ID, PCM_RESPONSE_ID,
        PSCM_RESPONSE_ID, FENG_RESPONSE_ID, RSPROT_RESPONSE_ID
    )
}
