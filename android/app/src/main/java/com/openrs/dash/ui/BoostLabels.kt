package com.openrs.dash.ui

import com.openrs.dash.data.BoostReading
import com.openrs.dash.data.BoostSession
import com.openrs.dash.data.BoostSource
import com.openrs.dash.data.DashCell

/**
 * The live pressure gauge's name (#207) — DRIVE, HUD, TRIP live readouts, Custom Dashboard. They all show the one
 * measurement [com.openrs.dash.data.BoostRule.gauge] gives (PCM DID 0x033E throttle inlet pressure − measured baro), so
 * they all say BOOST, qualified as measured at the throttle inlet. Only the name shown changes: the reading, its source
 * ([BoostSource.TIP_033E], "TIP") and everything recorded keep the technical name, and recorded / mixed-source views
 * keep naming each source (TIP, or BOOST for CAN 0x0F8).
 */
object LiveBoostLabel {
    const val TITLE = "BOOST"
    val QUALIFIER = Qualifier("THROTTLE INLET", "THR INLET")
    const val HELP = "Boost pressure at the throttle inlet, relative to measured atmospheric pressure."
}

/** A live gauge's caption: [title] plus an optional [qualifier] line. */
data class GaugeName(val title: String, val qualifier: Qualifier?)

/**
 * The caption for a live gauge reading [r]: BOOST · THROTTLE INLET for the TIP gauge — also while it reads "—", so the
 * caption never flips with freshness. Any other source keeps its own label without the qualifier: a CAN 0x0F8 reading
 * is never given the throttle-inlet name.
 */
fun liveGaugeName(r: BoostReading): GaugeName =
    if (r.source == BoostSource.TIP_033E) GaugeName(LiveBoostLabel.TITLE, LiveBoostLabel.QUALIFIER)
    else GaugeName(r.source.label, null)

/** The pressure cell is the live gauge: named by it whatever label a saved layout carries ("TIP" in rc.7, older "BOOST"). */
private fun isLiveBoostCell(cell: DashCell) = cell.fieldKey == "boostKpa"

/** A Custom Dashboard cell's caption. */
fun dashCellLabel(cell: DashCell): String = if (isLiveBoostCell(cell)) LiveBoostLabel.TITLE else cell.label

/** A Custom Dashboard cell's qualifier line (only the live pressure cell has one). */
fun dashCellQualifier(cell: DashCell): Qualifier? = if (isLiveBoostCell(cell)) LiveBoostLabel.QUALIFIER else null

/** The name in the dashboard editor and field picker (one line, room for the qualifier). */
fun dashCellEditorLabel(cell: DashCell): String =
    dashCellQualifier(cell)?.let { "${dashCellLabel(cell)} · ${it.full}" } ?: dashCellLabel(cell)

/**
 * Peak caption for a boost hero (#207): the session peak of the source being shown — never another source's — in the
 * selected pressure unit. No reading → no peak claim. The hero's caption names the gauge ([liveGaugeName]).
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
