package com.openrs.dash.ui

import com.openrs.dash.data.BoostReading
import com.openrs.dash.data.BoostSession
import com.openrs.dash.data.BoostSource
import com.openrs.dash.data.DashCell

/**
 * The live pressure gauge's name (#207) — DRIVE, HUD, TRIP live readouts, Custom Dashboard: BOOST. They all show the one
 * measurement [com.openrs.dash.data.BoostRule.gauge] gives (PCM DID 0x033E − measured baro, "—" when TIP isn't fresh).
 * Only the name shown changes: the reading, its source ([BoostSource.TIP_033E], "TIP") and everything recorded keep the
 * technical name, and recorded / mixed-source views keep naming each source (TIP, or BOOST for CAN 0x0F8).
 */
object LiveBoostLabel {
    const val TITLE = "BOOST"
}

/**
 * The caption for a live gauge reading [r]: BOOST for the TIP gauge — also while it reads "—", so the caption never flips
 * with freshness. Any other source keeps its own label.
 */
fun liveGaugeLabel(r: BoostReading): String =
    if (r.source == BoostSource.TIP_033E) LiveBoostLabel.TITLE else r.source.label

/** A Custom Dashboard cell's caption: the pressure cell is the live gauge, BOOST whatever label a saved layout carries ("TIP" in rc.7). */
fun dashCellLabel(cell: DashCell): String = if (cell.fieldKey == "boostKpa") LiveBoostLabel.TITLE else cell.label

/**
 * Peak caption for a boost hero (#207): the session peak of the source being shown — never another source's — in the
 * selected pressure unit. No reading → no peak claim. The hero's caption names the gauge ([liveGaugeLabel]).
 */
fun boostPeakCaption(r: BoostReading?, prefs: UserPrefs): String? {
    val src = r?.source?.takeIf { it != BoostSource.NONE } ?: return null
    val peak = BoostSession.peakKpa(src) ?: return null
    return "PEAK ${prefs.displayGauge(peak).first}"
}

/** The value alone, for the peak-hold flash. */
fun boostPeakValue(r: BoostReading?, prefs: UserPrefs): String? {
    val src = r?.source?.takeIf { it != BoostSource.NONE } ?: return null
    return BoostSession.peakKpa(src)?.let { prefs.displayGauge(it).first }
}
