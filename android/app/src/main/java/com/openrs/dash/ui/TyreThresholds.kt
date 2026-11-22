package com.openrs.dash.ui

import com.openrs.dash.ui.units.UnitIds
import com.openrs.dash.ui.units.UnitFactors
import com.openrs.dash.ui.units.tyreToPsi
import java.util.Locale

/**
 * One TPMS threshold in the Settings editor (#219). The physical value is stored in PSI ([psi]); the field shows it
 * in the staged Tire Pressure unit. Until the field is edited the exact stored PSI is kept — switching units, an
 * unchanged SAVE, CANCEL or RESET never feed a rounded display string back into the saved threshold. An edited field
 * is converted to PSI exactly once, on SAVE (or when the unit changes).
 */
data class TyreThreshold(val psi: Float, val text: String, val edited: Boolean = false) {

    /** The user typed [newText] (in [unit]). */
    fun edit(newText: String) = copy(text = newText, edited = true)

    /** The staged unit changed from [from] to [to]: re-show the same physical value in the new unit. */
    fun convert(from: String, to: String): TyreThreshold {
        val p = resolvePsi(from) ?: return copy(text = text)          // unparseable edit stays as typed
        return TyreThreshold(p, display(p, to), edited = false)
    }

    /** Canonical PSI for SAVE: the stored value if untouched, else the parsed field. Null = not a number. */
    fun resolvePsi(unit: String): Float? {
        if (!edited) return psi
        val v = text.trim().replace(',', '.').toFloatOrNull() ?: return null
        return tyreToPsi(v.toDouble(), unit).toFloat()
    }

    companion object {
        fun of(psi: Float, unit: String) = TyreThreshold(psi, display(psi, unit))

        /**
         * bar to 2 decimals, kPa to 0, psi to 1 — the same precision the readings use (#464). Locale.ROOT: the field is
         * parsed back, so it never takes the phone's decimal comma.
         */
        fun display(psi: Float, unit: String): String = when (unit) {
            UnitIds.BAR -> "%.2f".format(Locale.ROOT, psi * UnitFactors.PSI_TO_BAR)
            UnitIds.KPA -> "%.0f".format(Locale.ROOT, psi / UnitFactors.KPA_TO_PSI)
            else -> "%.1f".format(Locale.ROOT, psi)
        }
    }
}
