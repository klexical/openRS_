package com.openrs.dash.diagnostics

import android.content.Context
import com.openrs.dash.can.MeatPiConnection
import com.openrs.dash.can.WiCanConnection
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcStatus

/**
 * Orchestrates a full DTC scan across all Focus RS ECUs and returns
 * a parsed list of [DtcResult] records.
 *
 * Scanning uses UDS Service 0x19 (ReadDTCInformation), sub-function 0x02
 * (reportDTCByStatusMask), status mask 0xFF (all faults).
 *
 * Each result is looked up in the bundled [DtcDatabase] for a human-readable
 * description.
 */
class DtcScanner(private val ctx: Context) {

    companion object {
        private val MODULES = listOf(
            WiCanConnection.DtcModuleSpec("PCM",  0x7E0, 0x7E8),
            WiCanConnection.DtcModuleSpec("BCM",  0x726, 0x72E),
            WiCanConnection.DtcModuleSpec("ABS",  0x760, 0x768),
            WiCanConnection.DtcModuleSpec("AWD",  0x703, 0x70B),
            WiCanConnection.DtcModuleSpec("PSCM", 0x730, 0x738)
        )
    }

    /**
     * Runs the full scan via WiCAN adapter and returns parsed [DtcResult] records.
     * Returns an empty list if the connection is not live.
     * Suspends for up to ~15 seconds while querying all modules.
     */
    suspend fun scan(wican: WiCanConnection): List<DtcResult> {
        DtcDatabase.load(ctx)
        val raw = wican.performDtcScan(MODULES)
        return raw.flatMap { (moduleName, payload) -> parsePayload(moduleName, payload) }
    }

    /** Same as [scan] but uses the MeatPi Pro adapter. */
    suspend fun scanMeatPi(meatpi: MeatPiConnection): List<DtcResult> {
        DtcDatabase.load(ctx)
        val raw = meatpi.performDtcScan(MODULES)
        return raw.flatMap { (moduleName, payload) -> parsePayload(moduleName, payload) }
    }

    // ── ISO-TP payload → DtcResult list ──────────────────────────────────────

    private fun parsePayload(module: String, payload: ByteArray): List<DtcResult> {
        if (payload.size < 3) return emptyList()

        // Positive response to Service 0x19 has service ID 0x59
        val serviceId = payload[0].toInt() and 0xFF
        if (serviceId != 0x59) return emptyList()

        // Response layout: 59 02 availabilityMask [DTC_high DTC_mid statusByte]*
        // Skip the 3-byte header (59 02 mask) and iterate DTC records (3 bytes each)
        var offset = 3
        val results = mutableListOf<DtcResult>()

        while (offset + 2 < payload.size) {
            val high   = payload[offset].toInt()     and 0xFF
            val mid    = payload[offset + 1].toInt() and 0xFF
            val status = payload[offset + 2].toInt() and 0xFF
            offset += 3

            // Skip all-zero padding entries
            if (high == 0 && mid == 0) continue

            val code        = decodeDtcCode(high, mid)
            val description = DtcDatabase.describe(code)
            val dtcStatus   = classifyStatus(status)

            results += DtcResult(
                module      = module,
                code        = code,
                description = description,
                status      = dtcStatus
            )
        }

        return results
    }

    /**
     * Convert 2-byte raw DTC to the standard alphanumeric string.
     *
     * Byte layout (ISO 15031-6 / SAE J2012):
     *   high bits 7-6 → system: 00=P 01=C 10=B 11=U
     *   high bits 5-4 → first decimal digit (0–3)
     *   high bits 3-0 → second hex digit  (0–F)
     *   mid  bits 7-4 → third  hex digit  (0–F)
     *   mid  bits 3-0 → fourth hex digit  (0–F)
     */
    private fun decodeDtcCode(high: Int, mid: Int): String {
        val system = when ((high shr 6) and 0x03) {
            0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U"
        }
        val d1 = (high shr 4) and 0x03
        val d2 =  high        and 0x0F
        val d3 = (mid  shr 4) and 0x0F
        val d4 =  mid         and 0x0F
        return "%s%d%X%X%X".format(system, d1, d2, d3, d4)
    }

    /**
     * Map the UDS DTCStatusByte to a [DtcStatus].
     *
     * Relevant bits (ISO 14229-1 §D.2):
     *   bit 0 testFailed         → ACTIVE (current fault)
     *   bit 3 confirmedDTC       → PERMANENT (stored fault, confirmed across trips)
     *   bit 2 pendingDTC         → PENDING (single-trip, not yet confirmed)
     */
    private fun classifyStatus(status: Int): DtcStatus = when {
        (status and 0x01) != 0 -> DtcStatus.ACTIVE
        (status and 0x08) != 0 -> DtcStatus.PERMANENT
        (status and 0x04) != 0 -> DtcStatus.PENDING
        else                   -> DtcStatus.UNKNOWN
    }
}
