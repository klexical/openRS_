package com.openrs.dash.ui

import com.openrs.dash.data.BoostReading
import com.openrs.dash.data.BoostSession
import com.openrs.dash.data.BoostSource

/**
 * Peak caption for a boost hero (#207): the session peak of the source being shown — never another source's — in the
 * selected pressure unit. No reading → no peak claim. The hero's label names the source ([BoostSource.label]).
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
