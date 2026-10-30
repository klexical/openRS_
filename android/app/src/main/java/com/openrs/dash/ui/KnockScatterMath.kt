package com.openrs.dash.ui

import com.openrs.dash.data.BoostSource
import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.data.StoredBoost

/**
 * Pure geometry for [KnockScatter] (rc.6, #174) — the draw pass and tap hit-testing share
 * one projection so a tapped dot is always the dot that was drawn there.
 */
internal object KnockScatterMath {

    private const val PAD = 24f

    data class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    fun plot(width: Float, height: Float) = Plot(
        left = PAD * 2.5f, top = PAD * 2f,   // top row holds the PSI title
        right = width - PAD, bottom = height - PAD * 2f
    )

    /** Canvas position of an event (values clamped to the axis ranges). */
    fun project(
        ev: KnockEventEntity, plot: Plot,
        rpmMin: Float, rpmMax: Float, boostMin: Float, boostMax: Float
    ): Pair<Float, Float> {
        val rpm = ev.rpm.toFloat().coerceIn(rpmMin, rpmMax)
        val psi = ev.boostPsi.toFloat().coerceIn(boostMin, boostMax)
        val x = plot.left + plot.width * (rpm - rpmMin) / (rpmMax - rpmMin)
        val y = plot.top + plot.height * (1f - (psi - boostMin) / (boostMax - boostMin))
        return x to y
    }

    /** An event's boost (#207): its source when recorded, "NONE" = no reading, no column value = legacy. */
    fun boost(ev: KnockEventEntity): StoredBoost = when (val s = ev.boostSource) {
        null -> StoredBoost.Legacy(ev.boostPsi)
        else -> BoostSource.parse(s)?.takeIf { it != BoostSource.NONE }?.let { StoredBoost.Reading(ev.boostPsi, it) }
            ?: StoredBoost.Unavailable
    }

    /** The one measurement the boost axis plots: the source of the highest reading (null = legacy events only). */
    fun axisSource(events: List<KnockEventEntity>): BoostSource? =
        events.mapNotNull { boost(it) as? StoredBoost.Reading }.maxByOrNull { it.psi }?.source

    /**
     * Drawn on the RPM × pressure plane: a reading of [axisSource] only. Events whose source wasn't recorded are drawn
     * only on a "source not recorded" axis ([axisSource] null) — never mixed onto a TIP or CAN axis; "no reading" is
     * never drawn (as 0 or otherwise).
     */
    fun plotted(ev: KnockEventEntity, axisSource: BoostSource?): Boolean = when (val b = boost(ev)) {
        is StoredBoost.Reading -> b.source == axisSource
        is StoredBoost.Legacy -> axisSource == null
        StoredBoost.Unavailable -> false
    }

    /** The y-axis title: which pressure it plots. */
    fun axisTitle(axisSource: BoostSource?, unit: String): String = when (axisSource) {
        BoostSource.TIP_033E -> "TIP $unit"
        BoostSource.CAN_0F8 -> "BOOST $unit"
        else -> "$unit · SRC N/R"
    }

    /** Why some events aren't on the plot, or null when all are. */
    fun notPlottedNote(events: List<KnockEventEntity>, axisSource: BoostSource?): String? {
        val left = events.filterNot { plotted(it, axisSource) }
        if (left.isEmpty()) return null
        val none = left.count { boost(it) == StoredBoost.Unavailable }
        val legacy = left.count { boost(it) is StoredBoost.Legacy }
        val other = left.size - none - legacy
        return "${left.size} not plotted: " + listOfNotNull(
            none.takeIf { it > 0 }?.let { "$it no pressure reading" },
            other.takeIf { it > 0 }?.let { "$it other pressure source" },
            legacy.takeIf { it > 0 }?.let { "$it source not recorded" },
        ).joinToString(" · ")
    }

    /** Index of the event nearest the tap within [radiusPx], or null. Ties → worst KR. Only [plotted] events. */
    fun nearest(
        events: List<KnockEventEntity>, tapX: Float, tapY: Float, plot: Plot, radiusPx: Float,
        rpmMin: Float, rpmMax: Float, boostMin: Float, boostMax: Float
    ): Int? {
        var best: Int? = null
        var bestD2 = radiusPx * radiusPx
        val axis = axisSource(events)
        for (i in events.indices) {
            if (!plotted(events[i], axis)) continue
            val (x, y) = project(events[i], plot, rpmMin, rpmMax, boostMin, boostMax)
            val d2 = (x - tapX) * (x - tapX) + (y - tapY) * (y - tapY)
            val b = best
            if (d2 < bestD2 || (d2 == bestD2 && b != null && events[i].krDeg > events[b].krDeg)) {
                best = i
                bestD2 = d2
            }
        }
        return best
    }
}
