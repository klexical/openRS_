package com.openrs.dash.diagnostics

import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanRecord
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.DtcScanStatus
import com.openrs.dash.data.ModuleScanStatus

/**
 * The session's DTC scan record as the diagnostic export reports it (#215) — the one publication path for manual scans,
 * the auto-scan on connect and module retries. Scoped to the diagnostic session: [newSession] (every
 * [DiagnosticLogger.sessionStart]) clears it, and a scan or retry that began in an earlier session, or that a newer
 * scan has replaced, can no longer publish. A scan is visible from its start (RUNNING, with per-module progress), so an
 * interrupted one never leaves an older clean result standing or reads as "no scan".
 *
 * Saved scan history (Room `dtc_scans`) is separate and untouched.
 */
class DtcScanLedger {

    /** Handed out by [begin]; identifies one scan in one session. */
    class Ticket internal constructor(
        internal val session: Int, internal val scan: Int, val source: String, val modules: List<String>,
    )

    private var session = 0
    private var scanSeq = 0
    /** Scan whose record is [current]; [open] while it is still running. */
    private var currentScan = 0
    private var open = false

    @Volatile var current: DtcScanRecord? = null
        private set

    /** New diagnostic session: no scan yet, and nothing from an earlier session may publish into it. */
    @Synchronized fun newSession() { session++; currentScan = 0; open = false; current = null }

    /** A scan starts: it replaces the previous record at once (RUNNING, no module answered yet). */
    @Synchronized fun begin(source: String, modules: List<String>, nowMs: Long): Ticket {
        val t = Ticket(session, ++scanSeq, source, modules)
        currentScan = t.scan; open = true
        current = DtcScanRecord(DtcScanStatus.RUNNING, nowMs, source, modules.associateWith { null })
        return t
    }

    /** Live per-module progress (null = querying) while the scan runs — kept if it is interrupted. */
    @Synchronized fun progress(t: Ticket, module: String, status: ModuleScanStatus?) {
        if (!isOpen(t)) return
        current = current?.let { it.copy(modules = it.modules + (module to status)) }
    }

    /** The scan finished: COMPLETE / PARTIAL / FAILED from its validated module statuses. */
    @Synchronized fun complete(t: Ticket, result: DtcScanResult, nowMs: Long): Boolean {
        if (!isOpen(t)) return false
        current = DtcScanRecord.from(result, nowMs, t.source, t.modules)
        open = false
        return true
    }

    /** The scan threw / returned nothing. */
    @Synchronized fun fail(t: Ticket, nowMs: Long, message: String?): Boolean =
        close(t, DtcScanStatus.FAILED, nowMs, message)

    /** The user aborted it. */
    @Synchronized fun cancel(t: Ticket, nowMs: Long): Boolean = close(t, DtcScanStatus.CANCELLED, nowMs, null)

    /** It was cut off — the DIAG screen went away, or the connection ended — before it finished. */
    @Synchronized fun interrupt(t: Ticket, nowMs: Long, reason: String): Boolean =
        close(t, DtcScanStatus.INTERRUPTED, nowMs, reason)

    /**
     * A retried module's outcome merges into the finished record it belongs to: that module's status and codes are
     * replaced, the scan status re-derived (a retry can complete coverage or add codes). Ignored when the record is no
     * longer that scan's (newer scan, new session) or the scan never finished.
     */
    @Synchronized fun retry(t: Ticket, module: String, codes: List<DtcResult>, status: ModuleScanStatus, nowMs: Long): Boolean {
        val rec = current
        if (t.session != session || t.scan != currentScan || open || rec == null) return false
        if (rec.status == DtcScanStatus.CANCELLED || rec.status == DtcScanStatus.INTERRUPTED) return false
        val modules = rec.modules + (module to status)
        val merged = rec.codes.filter { it.module != module } + codes.filter { it.module == module }
        current = rec.copy(status = DtcScanRecord.statusFor(modules), finishedAtMs = nowMs, modules = modules, codes = merged)
        return true
    }

    private fun isOpen(t: Ticket) = t.session == session && t.scan == currentScan && open

    private fun close(t: Ticket, status: DtcScanStatus, nowMs: Long, message: String?): Boolean {
        if (!isOpen(t)) return false
        current = current?.copy(status = status, finishedAtMs = nowMs, message = message)
        open = false
        return true
    }
}
