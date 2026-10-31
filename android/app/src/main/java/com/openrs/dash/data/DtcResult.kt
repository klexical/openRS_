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
    val freezeFrame: FreezeFrame? = null,
    /** ISO 14229 DTC low byte (failure type, FORScan's "-1C" suffix). Null = unknown (saved before Room v13). */
    val failureType: Int? = null,
) {
    /** Full fault as shown and exported: base code + failure type when known ("P0123-1C"). Descriptions stay base-code. */
    val displayCode: String get() = failureType?.let { String.format(java.util.Locale.ROOT, "%s-%02X", code, it) } ?: code

    /**
     * Same fault as [other]: same module and base code, and the same failure type when both are known. Two different
     * failure types are different faults; when either side's type is unknown (an older saved record) only the base
     * code can be compared — [DtcDiff.baseCodeOnly] says so.
     */
    fun sameFault(other: DtcResult): Boolean =
        module == other.module && code == other.code &&
            (failureType == null || other.failureType == null || failureType == other.failureType)
}

enum class DtcStatus(val label: String) {
    ACTIVE("Active"),
    PENDING("Pending"),
    PERMANENT("Permanent"),
    UNKNOWN("Unknown")
}

// ── Scan result wrapper ─────────────────────────────────────────────────────

/**
 * Per-module outcome from a DTC scan. Only [OK] — a valid positive reply to the request — counts as scanned (#215);
 * every other value means that module's codes are unknown.
 */
enum class ModuleScanStatus {
    /** Valid positive reply (59 02 …) — zero records here means no codes stored in that module. */
    OK,
    /** No reply before the deadline. */
    TIMEOUT,
    /** The request could not be sent. */
    ERROR,
    NO_RESPONSE,
    /** Negative response (7F 19 <NRC>) — the module refused the request. */
    REJECTED,
    /** Only "response pending" (7F 19 78) arrived — the final answer never came. */
    PENDING,
    /** A reply that isn't a valid 0x19 02 answer (wrong service / sub-function, too short). */
    MALFORMED,
}

/** What one module's reply to UDS 0x19 02 (reportDTCByStatusMask) says (#215). */
sealed interface DtcReply {
    /** 59 02 <availability mask> [records…] — each record 4 bytes (3-byte DTC + status), ISO 14229-1. */
    data object Positive : DtcReply
    /** 7F 19 <nrc> — refused (any NRC except 0x78). */
    data class Negative(val nrc: Int) : DtcReply
    /** 7F 19 78 — the module is still working; its final reply follows. */
    data object Pending : DtcReply
    data object Malformed : DtcReply

    /** Module status for a final reply ([Pending] isn't final: the scan keeps waiting). */
    val status: ModuleScanStatus get() = when (this) {
        Positive -> ModuleScanStatus.OK
        is Negative -> ModuleScanStatus.REJECTED
        Pending -> ModuleScanStatus.PENDING
        Malformed -> ModuleScanStatus.MALFORMED
    }

    companion object {
        const val SID = 0x19
        const val SUB_FUNCTION = 0x02
        const val NRC_RESPONSE_PENDING = 0x78

        /** Bytes per DTCAndStatusRecord: DTC high, middle, low (failure type) + status (ISO 14229-1; python-udsoncan). */
        const val RECORD_SIZE = 4

        /**
         * Classify a reassembled ISO-TP payload (PCI removed). A positive reply must be whole records — a truncated
         * tail such as `59 02 FF 01` is MALFORMED, never a clean scan (#215).
         */
        fun classify(payload: ByteArray): DtcReply {
            val b = { i: Int -> payload[i].toInt() and 0xFF }
            return when {
                payload.size >= 3 && b(0) == 0x7F && b(1) == SID ->
                    if (b(2) == NRC_RESPONSE_PENDING) Pending else Negative(b(2))
                payload.size >= 3 && b(0) == SID + 0x40 && b(1) == SUB_FUNCTION &&
                    (payload.size - 3) % RECORD_SIZE == 0 -> Positive
                else -> Malformed
            }
        }
    }
}

/** Which write of a module's scan failed (#215). */
enum class DtcStage(val label: String) {
    EXTENDED_SESSION("extended-session request"),
    REQUEST("0x19 02 request"),
    FLOW_CONTROL("flow control"),
}

/** A scan write that could not be sent: [stage] and a sanitized [reason] (exception class + bounded message). */
data class DtcSendFailure(val stage: DtcStage, val reason: String) {
    /** "request not sent — IOException: …" / "flow control not sent — …" (export, events, DIAG). */
    val note: String get() = "${stage.label} not sent — $reason"
}

/**
 * Raw replies collected by one scan pass: final payload per module, modules left on "pending", and the failed write
 * that explains each module (one per module, [keeps] — #215). [sendFailed] = modules whose 0x19 02 request itself wasn't sent.
 */
data class DtcRawScan(
    val payloads: Map<String, ByteArray> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val failures: Map<String, DtcSendFailure> = emptyMap(),
) {
    val sendFailed: Set<String> get() = failures.filterValues { it.stage == DtcStage.REQUEST }.keys

    /**
     * Status of a module WITHOUT a final reply — the one rule shared by live progress and the final result (#215):
     * its request or flow control failed → ERROR (the final reply can't come — also after a "pending", which only
     * means the module was still working); still pending → PENDING (a failed extended-session write the module
     * answered anyway doesn't override it); any other failed write → ERROR; otherwise TIMEOUT.
     */
    fun statusWithoutReply(module: String): ModuleScanStatus = when {
        failures[module]?.stage.let { it == DtcStage.REQUEST || it == DtcStage.FLOW_CONTROL } -> ModuleScanStatus.ERROR
        module in pending -> ModuleScanStatus.PENDING
        module in failures -> ModuleScanStatus.ERROR
        else -> ModuleScanStatus.TIMEOUT
    }

    companion object {
        /**
         * Which failure a module keeps: the 0x19 02 request's own, else a flow control's over an extended-session one
         * (the more decisive for the final reply), else the first.
         */
        fun keeps(existing: DtcSendFailure?, new: DtcSendFailure): Boolean = when {
            existing == null -> true
            existing.stage == DtcStage.REQUEST -> false
            new.stage == DtcStage.REQUEST -> true
            else -> existing.stage == DtcStage.EXTENDED_SESSION && new.stage == DtcStage.FLOW_CONTROL
        }
    }
}

/** Progress callback for per-module scan updates. */
typealias DtcProgressCallback = (String, ModuleScanStatus?) -> Unit

/**
 * Complete DTC scan result including per-module status information.
 * Allows the UI to distinguish "0 codes found" from "module timed out".
 */
data class DtcScanResult(
    val codes: List<DtcResult>,
    val moduleStatuses: Map<String, ModuleScanStatus>,
    val scanDurationMs: Long = 0,
    /** Why a module has no valid reply when the app knows (#215): "0x19 02 request not sent — IOException: …". */
    val moduleNotes: Map<String, String> = emptyMap(),
)

/**
 * One module retry's outcome (#215): its codes, status and — when the app knows why it has no valid reply — the
 * explanation ([note], e.g. "flow control not sent — …"). A retry's note replaces the module's earlier one; null clears
 * it (a valid reply, or a genuine timeout with no failed write).
 */
data class DtcModuleRetry(
    val module: String,
    val codes: List<DtcResult>,
    val status: ModuleScanStatus,
    val note: String? = null,
)

/** Outcome of a DTC scan as the diagnostic export reports it (#215). No record at all = no scan this session. */
enum class DtcScanStatus {
    /** Every scanned module gave a valid positive reply — zero codes here means a clean scan. */
    COMPLETE,
    /** Some modules gave a valid reply; others timed out, refused, stayed pending or replied malformed — zero codes is NOT a clean scan. */
    PARTIAL,
    /** No module gave a valid reply, or the scan threw. */
    FAILED,
    /** The user aborted it. */
    CANCELLED,
    /** Cut off before it finished — the DIAG screen closed or the connection ended. */
    INTERRUPTED,
    /** Still running when the export was made. */
    RUNNING,
}

/**
 * The last DTC scan of the session (#215): status, per-module coverage (null = no answer recorded before it ended),
 * the codes and when / how it ran. Exported whenever a scan ran, including with zero codes. Published only through
 * [com.openrs.dash.diagnostics.DtcScanLedger].
 */
data class DtcScanRecord(
    val status: DtcScanStatus,
    val finishedAtMs: Long,
    /** "manual" (GARAGE → DIAG) or "auto" (scan on connect). */
    val source: String,
    val modules: Map<String, ModuleScanStatus?>,
    val codes: List<DtcResult> = emptyList(),
    val message: String? = null,
    /** Per-module explanation where the app knows it (a write that failed) — exported beside the status (#215). */
    val notes: Map<String, String> = emptyMap(),
) {
    /** Modules whose codes are known — a valid positive reply (REJECTED / MALFORMED / PENDING replies don't count). */
    val modulesAnswered: Int get() = modules.values.count { it == ModuleScanStatus.OK }

    companion object {
        /** A finished scan: COMPLETE only when every expected module gave a valid reply (OK). */
        fun from(result: DtcScanResult, finishedAtMs: Long, source: String, expectedModules: List<String>): DtcScanRecord {
            val modules: Map<String, ModuleScanStatus?> = expectedModules.associateWith { result.moduleStatuses[it] } + result.moduleStatuses
            return DtcScanRecord(statusFor(modules), finishedAtMs, source, modules, result.codes, notes = result.moduleNotes)
        }

        /** Status of a finished scan from its module coverage — shared by a full scan and a module retry. */
        fun statusFor(modules: Map<String, ModuleScanStatus?>): DtcScanStatus {
            val ok = modules.values.count { it == ModuleScanStatus.OK }
            return when {
                ok == modules.size && ok > 0 -> DtcScanStatus.COMPLETE
                ok > 0 -> DtcScanStatus.PARTIAL
                else -> DtcScanStatus.FAILED
            }
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
/**
 * The latest saved scan, as the baseline for NEW / RESOLVED (#215): its faults *and* which modules it actually read —
 * a module it failed to read says nothing about that module's faults.
 */
data class PreviousDtcScan(
    val timestampMs: Long,
    val codes: List<DtcResult>,
    val moduleStatuses: Map<String, ModuleScanStatus>,
)

/**
 * Comparison with the previous scan, only over [comparedModules] — modules read OK (valid positive reply) in *both*
 * scans. A fault outside them is neither NEW nor RESOLVED: its status in one of the scans is unknown.
 */
data class DtcDiff(
    val newCodes: List<DtcResult>,
    /** Previous faults absent now, in modules read OK both times. */
    val goneCodes: List<DtcResult>,
    val persistent: List<DtcResult>,
    val comparedModules: Set<String> = emptySet(),
    /** True when a matched pair lacked a failure type on one side — compared by base code only. */
    val baseCodeOnly: Boolean = false,
) {
    fun isNew(dtc: DtcResult): Boolean = newCodes.any { it === dtc }
}

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
