package com.openrs.dash.can

/**
 * Log lines for PCM NRCs that can't be attributed (#216) — the LINES only; polling, attribution, suppression and
 * re-probing are untouched. After one lost reply every later 0x31 is unattributable for the connection, which logged
 * one line per NRC with an ever-growing waiting list (522 lines in the owner's 8.5 min drive). Now, per (NRC, reason):
 * the first is logged in full (the useful example), repeats are counted, and at most one summary line per [windowMs]
 * says how many were folded in. [total] counts every unattributed NRC of the connection exactly.
 */
internal class UnattributedNrcLog(private val windowMs: Long = 60_000L) {

    private data class Key(val nrc: Int, val reason: String)

    private val lastLineAt = HashMap<Key, Long>()
    private val folded = HashMap<Key, Int>()

    /** Every unattributed NRC of this connection, logged or folded. */
    var total = 0; private set

    /**
     * Records one unattributed NRC. Returns the line to log, or null when it is folded into the next summary.
     * [reason] is the kind ("ambiguous" / "unresolved" / "none outstanding"); [detail] the full text for this NRC.
     */
    fun record(nrc: Int, reason: String, detail: String, nowMs: Long): String? {
        total++
        val key = Key(nrc, reason)
        val last = lastLineAt[key]
        if (last == null) { lastLineAt[key] = nowMs; return detail }
        if (nowMs - last < windowMs) { folded[key] = (folded[key] ?: 0) + 1; return null }
        val n = folded.remove(key) ?: 0
        lastLineAt[key] = nowMs
        return "$detail — +$n more like this in the last ${(nowMs - last) / 1000} s (total $total unattributed this connection)"
    }

    companion object {
        /** A waiting list as counts, most frequent first, at most [max] DIDs: "03ED ×18, 03EE ×17, 033E". */
        fun compact(waiting: List<Int>, max: Int = 6): String {
            if (waiting.isEmpty()) return ""
            val counts = waiting.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            val shown = counts.take(max).joinToString { (did, n) -> if (n > 1) "%04X ×%d".format(did, n) else "%04X".format(did) }
            return if (counts.size > max) "$shown, +${counts.size - max} more" else shown
        }
    }
}
