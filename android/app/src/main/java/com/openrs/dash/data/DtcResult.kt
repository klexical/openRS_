package com.openrs.dash.data

/**
 * A single fault code record returned by a DTC scan.
 *
 * @param module   Which ECU this code came from (e.g. "PCM", "BCM", "ABS").
 * @param code     The formatted DTC string (e.g. "P0234").
 * @param description Human-readable fault description, or empty if the code
 *                   is not in the bundled database.
 * @param status   Whether the fault is active, pending, or permanent.
 * @param freezeFrame Snapshot of vehicle state at fault occurrence (null if not captured).
 */
data class DtcResult(
    val module: String,
    val code: String,
    val description: String,
    val status: DtcStatus,
    val freezeFrame: FreezeFrame? = null
)

enum class DtcStatus(val label: String) {
    ACTIVE("Active"),
    PENDING("Pending"),
    PERMANENT("Permanent"),
    UNKNOWN("Unknown")
}

// ── Scan result wrapper ─────────────────────────────────────────────────────

/** Per-module outcome from a DTC scan. */
enum class ModuleScanStatus { OK, TIMEOUT, ERROR, NO_RESPONSE }

/** Progress callback for per-module scan updates. */
typealias DtcProgressCallback = (String, ModuleScanStatus?) -> Unit

/**
 * Complete DTC scan result including per-module status information.
 * Allows the UI to distinguish "0 codes found" from "module timed out".
 */
data class DtcScanResult(
    val codes: List<DtcResult>,
    val moduleStatuses: Map<String, ModuleScanStatus>,
    val scanDurationMs: Long = 0
)

/** Outcome of a DTC scan as the diagnostic export reports it (#215). No record at all = no scan this session. */
enum class DtcScanStatus {
    /** Every scanned module answered — zero codes here means a clean scan. */
    COMPLETE,
    /** Some modules answered, others timed out / errored / didn't respond — zero codes is NOT a clean scan. */
    PARTIAL,
    /** No module answered, or the scan threw. */
    FAILED,
    /** The user aborted it. */
    CANCELLED,
}

/**
 * The last DTC scan of the session (#215): status, per-module coverage (null = no answer recorded before it ended),
 * the codes and when / how it ran. Exported whenever a scan ran, including with zero codes.
 */
data class DtcScanRecord(
    val status: DtcScanStatus,
    val finishedAtMs: Long,
    /** "manual" (GARAGE → DIAG) or "auto" (scan on connect). */
    val source: String,
    val modules: Map<String, ModuleScanStatus?>,
    val codes: List<DtcResult> = emptyList(),
    val message: String? = null,
) {
    val modulesAnswered: Int get() = modules.values.count { it == ModuleScanStatus.OK }

    companion object {
        /** A finished scan: COMPLETE only when every expected module answered OK. */
        fun from(result: DtcScanResult, finishedAtMs: Long, source: String, expectedModules: List<String>): DtcScanRecord {
            val modules: Map<String, ModuleScanStatus?> = expectedModules.associateWith { result.moduleStatuses[it] } + result.moduleStatuses
            val ok = modules.values.count { it == ModuleScanStatus.OK }
            val status = when {
                ok == modules.size && ok > 0 -> DtcScanStatus.COMPLETE
                ok > 0 -> DtcScanStatus.PARTIAL
                else -> DtcScanStatus.FAILED
            }
            return DtcScanRecord(status, finishedAtMs, source, modules, result.codes)
        }
    }
}

// ── Freeze frame data ───────────────────────────────────────────────────────

/**
 * A single DID-value pair from a UDS freeze frame snapshot.
 */
data class FreezeFrameEntry(
    val did: Int,
    val rawBytes: ByteArray,
    val label: String,
    val value: String
) {
    override fun equals(other: Any?): Boolean =
        other is FreezeFrameEntry && did == other.did && rawBytes.contentEquals(other.rawBytes)
    override fun hashCode(): Int = 31 * did + rawBytes.contentHashCode()
}

/**
 * Freeze frame snapshot captured by an ECU at the time a DTC was set.
 * Contains DID-value pairs (RPM, speed, coolant temp, etc.).
 */
data class FreezeFrame(
    val dtcCode: String,
    val recordNumber: Int,
    val entries: List<FreezeFrameEntry>
)

// ── DTC diff ────────────────────────────────────────────────────────────────

/** Diff between current and previous DTC scan. */
data class DtcDiff(
    val newCodes: List<DtcResult>,
    val goneCodes: List<String>,   // code strings that were in previous scan but not current
    val persistent: List<DtcResult>
)

// ── DTC severity ────────────────────────────────────────────────────────────

enum class DtcSeverity(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    companion object {
        /** Derive severity from the DTC code prefix and digit. */
        fun fromCode(code: String): DtcSeverity {
            if (code.length < 2) return MEDIUM
            return when (code[0]) {
                'P' -> when {
                    code[1] == '0' -> HIGH    // SAE-defined powertrain (emissions-critical)
                    else           -> MEDIUM  // manufacturer-specific powertrain
                }
                'C' -> MEDIUM                 // chassis
                'B' -> LOW                    // body
                'U' -> LOW                    // network/communication
                else -> MEDIUM
            }
        }
    }
}
