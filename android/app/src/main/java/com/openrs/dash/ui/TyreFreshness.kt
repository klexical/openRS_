package com.openrs.dash.ui

/**
 * One wheel as THERMAL's TYRES panel shows it. The pressure classification ([level]) counts only while the reading is
 * current; a held pressure (its own stamp older than 60 s, or the link down) is last-known: it keeps its value and, when
 * it was outside the Low / High limits, that fact with its age ([wasLow] / [wasHigh]), but it is never a live alarm.
 * Every part of the panel reads this one view: the card, the wheel tint, the lead line, the panel edge and the chips.
 */
internal data class TyreView(
    /** "Front Left" … "Rear Right". */
    val position: String,
    val psi: Double,
    val avail: TempAvail,
    /** [tireLevel] of the held pressure, whatever its age. */
    val heldLevel: Level,
    /** Time since this wheel's own last reading; null when it has no stamp. */
    val ageMs: Long?,
    /** The held pressure is under the Low threshold / over the High threshold, whatever its age. */
    val underLow: Boolean,
    val overHigh: Boolean,
) {
    val stale: Boolean get() = avail == TempAvail.STALE
    /** A last-known reading that was outside a limit: kept on screen as a fact with its age, never as an alarm. */
    val wasLow: Boolean get() = stale && underLow
    val wasHigh: Boolean get() = stale && overHigh
    /** A reading that may be judged now. */
    val current: Boolean get() = avail == TempAvail.AVAILABLE && psi >= 0
    /** What colours and alarms may show: the pressure state while current, STALE for a held value, NONE without one. */
    val level: Level get() = when {
        current -> heldLevel
        stale   -> Level.STALE
        else    -> Level.NONE
    }
}

/**
 * The view of one wheel from its pressure and its own freshness stamp (`fieldLastUpdateMs["tirePress…"]`). TPMS is polled,
 * so offline there is nothing to warm up: no reading and no link is a quiet placeholder, not WARMING (as before).
 */
internal fun tyreView(
    position: String, psi: Double, lastUpdateMs: Long?, connected: Boolean,
    low: Double, warn: Double, high: Double, nowMs: Long,
): TyreView {
    val a = scalarAvailFor(psi >= 0, lastUpdateMs, now = nowMs)
    val avail = if (!connected && a == TempAvail.WARMING) TempAvail.AVAILABLE else a.whenDisconnected(connected)
    return TyreView(
        position = position, psi = psi, avail = avail,
        heldLevel = tireLevel(psi, low, warn, high),
        ageMs = lastUpdateMs?.takeIf { it > 0L }?.let { (nowMs - it).coerceAtLeast(0L) },
        underLow = psi >= 0 && psi < low,
        overHigh = psi > high,
    )
}

/** "45s" / "3m" / "2h" — the same steps as the TPMS footer. */
internal fun tyreAgeText(ageMs: Long): String {
    val s = (ageMs / 1000).coerceAtLeast(0L)
    return when {
        s < 60   -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else     -> "${s / 3600}h"
    }
}

/**
 * The card's one status line. A held value says so with its age ("STALE 3m"); a held value that was under Low or over
 * High keeps that fact ("WAS LOW 3m" / "WAS HIGH 3m"). A current reading shows its pressure change ([deltaText]).
 */
internal fun tyreStatusText(t: TyreView, deltaText: String): String = when (t.avail) {
    TempAvail.WARMING     -> "WARMING"
    TempAvail.UNAVAILABLE -> "N/A"
    TempAvail.AVAILABLE   -> deltaText
    TempAvail.STALE       -> {
        val word = when { t.wasLow -> "WAS LOW"; t.wasHigh -> "WAS HIGH"; else -> "STALE" }
        t.ageMs?.let { "$word ${tyreAgeText(it)}" } ?: word
    }
}

/** What the TYRES panel as a whole may say, from the four [TyreView]s (FL, FR, RL, RR). */
internal data class TyrePanel(
    /** Edge of the panel: the worst CURRENT reading. Held values never tint it. */
    val level: Level,
    /** Positions with a current reading under Low — the live "… LOW" chip. */
    val liveLow: List<String>,
    /** Positions whose last-known reading was under Low. */
    val staleLow: List<String>,
    /** Age of the oldest reading in [staleLow] (the chip never claims a fresher reading than it has). */
    val staleLowAgeMs: Long?,
    /** Highest minus lowest CURRENT pressure in psi; 0 with fewer than two current readings. */
    val currentSpreadPsi: Double,
) {
    /** "FRONT LEFT WAS LOW · 3m AGO", or null. Each card carries its own wheel's age. */
    val staleLowText: String? get() = if (staleLow.isEmpty()) null else
        staleLow.joinToString(", ").uppercase() + " WAS LOW" + (staleLowAgeMs?.let { " · ${tyreAgeText(it)} AGO" } ?: "")
}

internal fun tyrePanel(tyres: List<TyreView>): TyrePanel {
    val current = tyres.filter { it.current }
    val wasLow = tyres.filter { it.wasLow }
    return TyrePanel(
        level = worstLevel(tyres.map { it.level }),
        liveLow = current.filter { it.underLow }.map { it.position },
        staleLow = wasLow.map { it.position },
        staleLowAgeMs = wasLow.mapNotNull { it.ageMs }.maxOrNull(),
        currentSpreadPsi = if (current.size < 2) 0.0 else current.maxOf { it.psi } - current.minOf { it.psi },
    )
}
