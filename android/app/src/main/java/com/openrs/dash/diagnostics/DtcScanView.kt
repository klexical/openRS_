package com.openrs.dash.diagnostics

import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.ModuleScanStatus

/**
 * The DIAG screen's DTC scan state (#215): which scan it shows ([ticket]) and its result. Scan completion and module
 * retries go through here *and* [DtcScanLedger] together, so the screen and the exported record can't diverge:
 *  - a retry merges into the latest result (never a copy captured when the retry started), so two retries finishing
 *    in either order both land;
 *  - a retry or a late completion that belongs to an older scan, or to a session the ledger has moved past, changes
 *    neither the screen nor the export.
 * Main-thread confined (DiagPage applies results on Main).
 */
class DtcScanView(private val ledger: DtcScanLedger) {
    var ticket: DtcScanLedger.Ticket? = null
        private set
    var result: DtcScanResult? = null
        private set

    /** A new scan starts on this screen: it replaces whatever was shown. */
    fun begin(t: DtcScanLedger.Ticket) { ticket = t; result = null }

    /** The scan finished; applied only if it is still the scan this screen shows. */
    fun finish(t: DtcScanLedger.Ticket, r: DtcScanResult, nowMs: Long): Boolean {
        if (t !== ticket) return false
        ledger.complete(t, r, nowMs)
        result = r
        return true
    }

    /**
     * A module retry started under [t] finished. Applied only while [t] is still this screen's scan and the ledger
     * accepts it (same scan, same session) — then merged into the *current* result.
     */
    fun retryFinished(t: DtcScanLedger.Ticket, module: String, codes: List<DtcResult>, status: ModuleScanStatus,
                      nowMs: Long): Boolean {
        val current = result
        if (t !== ticket || current == null) return false
        if (!ledger.retry(t, module, codes, status, nowMs)) return false
        result = mergeRetry(current, module, codes, status)
        return true
    }

    /** DISMISS: the screen shows nothing (the exported record is unchanged). */
    fun clear() { result = null }

    companion object {
        /** A retried module's outcome: its status and codes replace the old ones; other modules are untouched. */
        fun mergeRetry(r: DtcScanResult, module: String, codes: List<DtcResult>, status: ModuleScanStatus) = r.copy(
            codes = r.codes.filter { it.module != module } + codes.filter { it.module == module },
            moduleStatuses = r.moduleStatuses + (module to status),
        )
    }
}
