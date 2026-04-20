package com.openrs.dash.diagnostics

import android.content.Context
import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.data.*
import org.json.JSONObject

/**
 * Orchestrates a full DTC scan across all Focus RS ECUs and returns
 * a parsed list of [DtcResult] records wrapped in [DtcScanResult].
 *
 * Scanning uses UDS Service 0x19 (ReadDTCInformation), sub-function 0x02
 * (reportDTCByStatusMask), status mask 0xFF (all faults).
 *
 * Each result is looked up in the bundled [DtcDatabase] for a human-readable
 * description.
 */
class DtcScanner(private val ctx: Context) {

    companion object {
        val MODULES = listOf(
            DtcModuleSpec("PCM",  0x7E0, 0x7E8),
            DtcModuleSpec("BCM",  0x726, 0x72E),
            DtcModuleSpec("ABS",  0x760, 0x768),
            DtcModuleSpec("AWD",  0x703, 0x70B, needsExtSession = true,
                extSessionFrame = ObdConstants.EXT_SESSION_AWD),
            DtcModuleSpec("PSCM", 0x730, 0x738, needsExtSession = true,
                extSessionFrame = ObdConstants.EXT_SESSION_PSCM),
            DtcModuleSpec("GFM",  0x7D2, 0x7DA, needsExtSession = true,
                extSessionFrame = "t7D280210030000000000\r")
        )

        // ── Pure parsing helpers (no Context dependency) ─────────────────────

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
        internal fun decodeDtcCode(high: Int, mid: Int): String {
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
        internal fun classifyStatus(status: Int): DtcStatus = when {
            (status and 0x01) != 0 -> DtcStatus.ACTIVE
            (status and 0x08) != 0 -> DtcStatus.PERMANENT
            (status and 0x04) != 0 -> DtcStatus.PENDING
            else                   -> DtcStatus.UNKNOWN
        }

        /**
         * Parse a UDS 0x19/02 response payload into [DtcResult] records.
         * Description lookup uses [DtcDatabase] — returns "" if DB not loaded.
         */
        internal fun parsePayload(module: String, payload: ByteArray): List<DtcResult> {
            if (payload.size < 3) return emptyList()

            val serviceId = payload[0].toInt() and 0xFF
            if (serviceId != 0x59) return emptyList()

            var offset = 3
            val results = mutableListOf<DtcResult>()

            while (offset + 2 < payload.size) {
                val high   = payload[offset].toInt()     and 0xFF
                val mid    = payload[offset + 1].toInt() and 0xFF
                val status = payload[offset + 2].toInt() and 0xFF
                offset += 3

                if (high == 0 && mid == 0) continue

                val code      = decodeDtcCode(high, mid)
                val description = DtcDatabase.describe(code)
                val dtcStatus = classifyStatus(status)

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
         * Compute the diff between the current scan and a previous set of codes.
         * [previousCodes] is a set of DTC code strings from the last scan.
         */
        fun computeDiff(current: List<DtcResult>, previousCodes: Set<String>): DtcDiff {
            val currentCodeSet = current.map { it.code }.toSet()
            return DtcDiff(
                newCodes   = current.filter { it.code !in previousCodes },
                goneCodes  = previousCodes.filter { it !in currentCodeSet }.toList(),
                persistent = current.filter { it.code in previousCodes }
            )
        }
    }

    /**
     * Runs the full scan and returns a [DtcScanResult] with parsed codes and
     * per-module status information.
     *
     * [onModuleProgress] is called on each module state change for live UI updates.
     */
    suspend fun scan(
        conn: SlcanConnection,
        onModuleProgress: ((String, ModuleScanStatus?) -> Unit)? = null
    ): DtcScanResult {
        DtcDatabase.load(ctx)
        val startMs = System.currentTimeMillis()
        val raw = conn.performDtcScan(MODULES, onModuleProgress)
        val durationMs = System.currentTimeMillis() - startMs

        val codes = mutableListOf<DtcResult>()
        val statuses = mutableMapOf<String, ModuleScanStatus>()

        for (module in MODULES) {
            val result = raw[module.name]
            when {
                result == null -> {
                    statuses[module.name] = ModuleScanStatus.TIMEOUT
                }
                result.isEmpty() -> {
                    statuses[module.name] = ModuleScanStatus.OK
                }
                else -> {
                    val parsed = parsePayload(module.name, result)
                    codes.addAll(parsed)
                    statuses[module.name] = ModuleScanStatus.OK
                }
            }
        }

        val result = DtcScanResult(
            codes = codes,
            moduleStatuses = statuses,
            scanDurationMs = durationMs
        )

        // Persist scan results to Room database
        try {
            val db = DriveDatabase.getInstance(ctx)
            val statusJson = JSONObject().apply {
                statuses.forEach { (k, v) -> put(k, v.name) }
            }.toString()
            val scanId = db.driveDao().insertDtcScan(DtcScanEntity(
                timestamp = System.currentTimeMillis(),
                moduleCount = statuses.size,
                totalCodes = codes.size,
                scanDurationMs = durationMs,
                moduleStatuses = statusJson
            ))
            if (codes.isNotEmpty()) {
                db.driveDao().insertDtcCodes(codes.map { dtc ->
                    DtcCodeEntity(
                        scanId = scanId,
                        module = dtc.module,
                        code = dtc.code,
                        description = dtc.description,
                        status = dtc.status.name
                    )
                })
            }
            // Prune to 50 most recent scans
            val count = db.driveDao().getDtcScanCount()
            if (count > 50) db.driveDao().deleteOldestDtcScans(count - 50)
        } catch (e: Exception) {
            android.util.Log.w("DTC", "Failed to persist scan results", e)
        }

        return result
    }

    /**
     * Fetch freeze frame data for DTCs that have snapshot records.
     * Returns updated DTC results with freeze frames attached.
     * This is an opt-in second pass after the main scan.
     */
    suspend fun fetchFreezeFrames(
        conn: SlcanConnection,
        codes: List<DtcResult>
    ): List<DtcResult> {
        if (codes.isEmpty()) return codes

        val moduleMap = MODULES.associateBy { it.name }
        val updatedCodes = codes.toMutableList()

        // Group codes by module to minimize session switches
        val byModule = codes.groupBy { it.module }
        for ((moduleName, moduleCodes) in byModule) {
            val module = moduleMap[moduleName] ?: continue

            // Step 1: query 0x19/04 to find which DTCs have snapshots
            val idPayload = conn.performFreezeFrameIdentification(module) ?: continue
            val snapshots = FreezeFrameParser.parseSnapshotIdentification(idPayload)
            if (snapshots.isEmpty()) continue

            // Step 2: for each DTC with a snapshot, fetch the record
            for ((dtcBytes, recordNum) in snapshots) {
                val dtcCode = decodeDtcCode(
                    dtcBytes[0].toInt() and 0xFF,
                    dtcBytes[1].toInt() and 0xFF
                )
                // Find matching code in our results
                val idx = updatedCodes.indexOfFirst { it.module == moduleName && it.code == dtcCode }
                if (idx < 0) continue

                val ffPayload = conn.performFreezeFrameRead(module, dtcBytes, recordNum) ?: continue
                // Skip the UDS header (SID + SF + DTC bytes + record num)
                val dataStart = minOf(7, ffPayload.size)
                if (dataStart >= ffPayload.size) continue
                val ffData = ffPayload.copyOfRange(dataStart, ffPayload.size)

                val freezeFrame = FreezeFrameParser.parse(dtcCode, recordNum, ffData)
                if (freezeFrame.entries.isNotEmpty()) {
                    updatedCodes[idx] = updatedCodes[idx].copy(freezeFrame = freezeFrame)
                }
            }
        }

        return updatedCodes
    }

    /**
     * Retry scan for a single module that previously timed out.
     */
    suspend fun scanSingleModule(
        conn: SlcanConnection,
        moduleName: String
    ): Pair<List<DtcResult>, ModuleScanStatus> {
        DtcDatabase.load(ctx)
        val module = MODULES.find { it.name == moduleName }
            ?: return emptyList<DtcResult>() to ModuleScanStatus.ERROR
        val raw = conn.performDtcScan(listOf(module), null)
        val result = raw[module.name]
        return if (result != null && result.isNotEmpty()) {
            parsePayload(module.name, result) to ModuleScanStatus.OK
        } else if (result != null) {
            emptyList<DtcResult>() to ModuleScanStatus.OK
        } else {
            emptyList<DtcResult>() to ModuleScanStatus.TIMEOUT
        }
    }

    /**
     * Get the set of DTC codes from the most recent persisted scan.
     * Used for diff comparison (NEW/RESOLVED badges).
     */
    fun getPreviousScanCodes(): Set<String> {
        return try {
            val db = DriveDatabase.getInstance(ctx)
            db.driveDao().getLastScanCodes().map { it.code }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    /**
     * Get code occurrence history for the DTC detail sheet.
     */
    fun getCodeHistory(code: String): List<DtcCodeEntity> {
        return try {
            val db = DriveDatabase.getInstance(ctx)
            db.driveDao().getCodeHistory(code)
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Sends UDS Service 0x14 (ClearDiagnosticInformation, group 0xFFFFFF) to all
     * target ECUs.
     *
     * Returns a map of module name → true when the ECU acknowledged the clear.
     * A missing key means no response was received from that module.
     */
    suspend fun clearDtcs(conn: SlcanConnection): Map<String, Boolean> =
        conn.performDtcClear(MODULES)

}
