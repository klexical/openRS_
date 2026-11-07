package com.openrs.dash.diagnostics

import com.openrs.dash.data.DtcModuleRetry
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.ModuleScanStatus

/**
 * The DIAG screen's DTC scan state (#215): which scan it shows ([ticket]) and its result. Scan completion and module
 * retries go through here *and* [DtcScanLedger] together, so the screen and the exported record can't diverge:
 *  - a retry merges into the latest result (never a copy captured when the retry started), so two retries finishing
 *    in either order both land;
 *  - a retry or a late completion that belongs to an older scan, or to a session the ledger has moved past, changes
 *    neither the screen nor the export.
 * Every publication — this screen's result, the exported record and the header's active-code count ([countSink]) —
 * happens only when the ledger accepts the scan's ticket, so rejected (superseded, cancelled, interrupted, other-session)
 * work changes none of them.
 * Main-thread confined (DiagPage applies results on Main).
 */
class DtcScanView(private val ledger: DtcScanLedger, private val countSink: (Int) -> Unit = {}) {
    var ticket: DtcScanLedger.Ticket? = null
        private set
    var result: DtcScanResult? = null
        private set

    /** A new scan starts on this screen: it replaces whatever was shown. */
    fun begin(t: DtcScanLedger.Ticket) { ticket = t; result = null }

    /**
     * The scan finished; applied only if it is still the scan this screen shows *and* the ledger accepts it — a newer
     * scan (e.g. the auto-scan on connect, which doesn't go through this screen), a new session, a cancel or an
     * interruption leaves the screen, the export and the count unchanged.
     */
    fun finish(t: DtcScanLedger.Ticket, r: DtcScanResult, nowMs: Long): Boolean {
        if (t !== ticket) return false
        if (!ledger.complete(t, r, nowMs)) return false
        result = r
        countSink(activeCount(r))
        return true
    }

    /**
     * A module retry started under [t] finished. Applied only while [t] is still this screen's scan and the ledger
     * accepts it (same scan, same session) — then merged into the *current* result.
     */
    fun retryFinished(t: DtcScanLedger.Ticket, r: DtcModuleRetry, nowMs: Long): Boolean {
        val current = result
        if (t !== ticket || current == null) return false
        if (!ledger.retry(t, r, nowMs)) return false
        result = mergeRetry(current, r).also { countSink(activeCount(it)) }
        return true
    }

    /** DISMISS: the screen shows nothing (the exported record is unchanged). */
    fun clear() { result = null }

    companion object {
        fun activeCount(r: DtcScanResult) = r.codes.count { it.status == com.openrs.dash.data.DtcStatus.ACTIVE }

        /**
         * The auto-scan on connect (#215) — same publication rule as DIAG: the export record through the ledger, and the
         * header count ([publishCount]) only if the ledger accepted the result. [deliver] is the connection's ownership
         * check (`link.deliver(gen)`). Codes found → count; a clean scan leaves the count alone (as before).
         */
        suspend fun autoScan(
            ledger: DtcScanLedger, modules: List<String>, clock: () -> Long,
            scan: suspend (progress: (String, ModuleScanStatus?) -> Unit) -> DtcScanResult,
            deliver: (() -> Unit) -> Unit, publishCount: (Int) -> Unit,
        ): Pair<DtcScanResult, Boolean>? {
            val ticket = ledger.begin("auto", modules, clock())
            try {
                val result = scan { m, st -> ledger.progress(ticket, m, st) }
                val accepted = ledger.complete(ticket, result, clock())   // zero codes recorded too
                if (accepted && result.codes.isNotEmpty()) deliver { publishCount(activeCount(result)) }
                return result to accepted
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The connection ended mid-scan: say so (a plain ledger write — no suspending under cancellation)
                ledger.interrupt(ticket, clock(), "connection ended")
                throw e
            } catch (e: Exception) {
                // Best-effort, but a failed one is still reported as failed, never as clean
                ledger.fail(ticket, clock(), e.message?.let { LogText.sanitize(it) })
                return null
            }
        }

        /**
         * A retried module's outcome: its status, codes and explanation replace the old ones (same rule as the ledger's
         * record); other modules are untouched.
         */
        fun mergeRetry(r: DtcScanResult, retry: DtcModuleRetry) = r.copy(
            codes = r.codes.filter { it.module != retry.module } + retry.codes.filter { it.module == retry.module },
            moduleStatuses = r.moduleStatuses + (retry.module to retry.status),
            moduleNotes = DtcScanLedger.withNote(r.moduleNotes, retry),
        )

        /**
         * DIAG's NOT SCANNED row for [module] of the DISPLAYED result [r]: its own explanation, else the status reason.
         * Never the global record — a newer scan or connection there must not label an older row.
         */
        fun notScannedText(r: DtcScanResult, module: String, statusReason: (ModuleScanStatus) -> String): String =
            "$module — ${r.moduleNotes[module] ?: statusReason(r.moduleStatuses[module] ?: ModuleScanStatus.TIMEOUT)}"
    }
}
