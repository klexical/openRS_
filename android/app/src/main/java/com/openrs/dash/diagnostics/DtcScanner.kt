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
         *
         * Layout (ISO 14229-1 reportDTCByStatusMask; python-udsoncan parses it the same way):
         * `59 02 <availability mask>` then 4-byte records `DTC high, DTC middle, DTC low (failure type), status`.
         * Earlier builds read 3-byte records (high, mid, status), which takes the failure-type byte as the status and
         * misaligns every later record (#215). An incomplete trailing record is ignored here; [DtcReply.classify]
         * already rejects such a reply as MALFORMED. All-zero DTCs are padding.
         */
        internal fun parsePayload(module: String, payload: ByteArray): List<DtcResult> {
            if (payload.size < 3) return emptyList()

            val serviceId = payload[0].toInt() and 0xFF
            if (serviceId != 0x59) return emptyList()

            var offset = 3
            val results = mutableListOf<DtcResult>()

            while (offset + DtcReply.RECORD_SIZE <= payload.size) {
                val high    = payload[offset].toInt()     and 0xFF
                val mid     = payload[offset + 1].toInt() and 0xFF
                val failure = payload[offset + 2].toInt() and 0xFF
                val status  = payload[offset + 3].toInt() and 0xFF
                offset += DtcReply.RECORD_SIZE

                if (high == 0 && mid == 0 && failure == 0) continue

                val code      = decodeDtcCode(high, mid)
                val description = DtcDatabase.describe(code)
                val dtcStatus = classifyStatus(status)

                results += DtcResult(
                    module      = module,
                    code        = code,
                    description = description,
                    status      = dtcStatus,
                    failureType = failure
                )
            }

            return results
        }

        /**
         * Turn one scan pass's raw replies into codes + per-module status (#215). A module counts as scanned (OK) only
         * for a valid positive 0x19 02 reply; its records are parsed then. Negative → REJECTED, malformed → MALFORMED,
         * only "pending" → PENDING, unsent → ERROR, nothing → TIMEOUT — none of these can produce a clean result.
         */
        internal fun interpret(modules: List<DtcModuleSpec>, raw: DtcRawScan): Pair<List<DtcResult>, Map<String, ModuleScanStatus>> {
            val codes = mutableListOf<DtcResult>()
            val statuses = LinkedHashMap<String, ModuleScanStatus>()
            for (module in modules) {
                val payload = raw.payloads[module.name]
                statuses[module.name] = when {
                    payload != null -> DtcReply.classify(payload).also {
                        if (it == DtcReply.Positive) codes += parsePayload(module.name, payload)
                    }.status
                    else -> raw.statusWithoutReply(module.name)
                }
            }
            return codes to statuses
        }

        /** One scan pass over [modules] through the live connection, interpreted. No Room, no Context. */
        internal suspend fun runScan(
            conn: SlcanConnection,
            modules: List<DtcModuleSpec>,
            onModuleProgress: ((String, ModuleScanStatus?) -> Unit)? = null,
        ): DtcScanResult {
            val startMs = System.currentTimeMillis()
            val raw = conn.performDtcScan(modules, onModuleProgress)
            val (codes, statuses) = interpret(modules, raw)
            raw.payloads.forEach { (name, payload) ->
                val reply = DtcReply.classify(payload)
                if (reply is DtcReply.Negative) DiagnosticLogger.event("DTC", "$name refused 0x19 02 — NRC 0x%02X".format(reply.nrc))
                else if (reply == DtcReply.Malformed) DiagnosticLogger.event("DTC", "$name malformed reply: " +
                    payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
            }
            raw.pending.forEach { DiagnosticLogger.event("DTC", "$it: response pending, no final reply") }
            raw.failures.forEach { (name, f) -> DiagnosticLogger.event("DTC", "$name: ${f.note}") }
            // A note only where it explains the status: not beside a valid reply, and not beside PENDING — a failed
            // session write the module answered anyway isn't why it is still pending (the failure stays an event)
            val notes = raw.failures.filterKeys { statuses[it] != ModuleScanStatus.OK && statuses[it] != ModuleScanStatus.PENDING }
                .mapValues { it.value.note }
            return DtcScanResult(codes, statuses, System.currentTimeMillis() - startMs, notes)
        }

        /** A one-module scan pass as a retry outcome: codes, status and that pass's own explanation (or none). */
        internal fun retryOf(module: String, r: DtcScanResult) = DtcModuleRetry(
            module, r.codes.filter { it.module == module }, r.moduleStatuses[module] ?: ModuleScanStatus.TIMEOUT,
            r.moduleNotes[module])

        /**
         * NEW / RESOLVED against [previous] (#215), coverage-aware: only modules read OK in both scans are compared —
         * a module the previous scan failed to read can't make a current fault NEW, and one this scan failed to read
         * can't make a previous fault RESOLVED. Faults match on module + base code + failure type ([DtcResult.sameFault]).
         * No previous scan → nothing is compared.
         */
        fun computeDiff(
            current: List<DtcResult>,
            previous: PreviousDtcScan?,
            moduleStatuses: Map<String, ModuleScanStatus>,
        ): DtcDiff {
            if (previous == null) return DtcDiff(emptyList(), emptyList(), emptyList())
            val compared = moduleStatuses.filterValues { it == ModuleScanStatus.OK }.keys
                .filter { previous.moduleStatuses[it] == ModuleScanStatus.OK }.toSet()
            val cur = current.filter { it.module in compared }
            val prev = previous.codes.filter { it.module in compared }
            val baseOnly = cur.any { c -> prev.any { p -> p.module == c.module && p.code == c.code &&
                (p.failureType == null || c.failureType == null) } }
            return DtcDiff(
                newCodes   = cur.filter { c -> prev.none { it.sameFault(c) } },
                goneCodes  = prev.filter { p -> cur.none { it.sameFault(p) } },
                persistent = cur.filter { c -> prev.any { it.sameFault(c) } },
                comparedModules = compared,
                baseCodeOnly = baseOnly,
            )
        }

        /** A saved scan back as the NEW / RESOLVED baseline; an unreadable status JSON reads as "nothing read OK". */
        internal fun previousFrom(scan: DtcScanEntity, codes: List<DtcCodeEntity>): PreviousDtcScan {
            val statuses = try {
                val o = JSONObject(scan.moduleStatuses)
                o.keys().asSequence().mapNotNull { k ->
                    runCatching { k to ModuleScanStatus.valueOf(o.getString(k)) }.getOrNull()
                }.toMap()
            } catch (_: Exception) { emptyMap() }
            return PreviousDtcScan(scan.timestamp, codes.map {
                DtcResult(it.module, it.code, it.description,
                    runCatching { DtcStatus.valueOf(it.status) }.getOrDefault(DtcStatus.UNKNOWN), failureType = it.failureType)
            }, statuses)
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
        val result = runScan(conn, MODULES, onModuleProgress)
        val codes = result.codes
        val statuses = result.moduleStatuses
        val durationMs = result.scanDurationMs

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
                        status = dtc.status.name,
                        failureType = dtc.failureType
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
     * Retry scan for a single module that didn't give a valid reply — same validation as [scan], and its explanation
     * is kept ([retryOf]).
     */
    suspend fun scanSingleModule(conn: SlcanConnection, moduleName: String): DtcModuleRetry {
        DtcDatabase.load(ctx)
        val module = MODULES.find { it.name == moduleName }
            ?: return DtcModuleRetry(moduleName, emptyList(), ModuleScanStatus.ERROR, "unknown module")
        return retryOf(module.name, runScan(conn, listOf(module)))
    }

    /** The most recent saved scan — codes and module coverage — as the NEW / RESOLVED baseline (null = none). */
    fun getPreviousScan(): PreviousDtcScan? = try {
        val dao = DriveDatabase.getInstance(ctx).driveDao()
        dao.getRecentDtcScans(1).firstOrNull()?.let { previousFrom(it, dao.getDtcCodes(it.id)) }
    } catch (_: Exception) { null }

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
