package com.openrs.dash.ui

import com.openrs.dash.data.KnockEventEntity

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
        left = PAD * 2.5f, top = PAD, right = width - PAD, bottom = height - PAD * 2f
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

    /** Index of the event nearest the tap within [radiusPx], or null. Ties → worst KR. */
    fun nearest(
        events: List<KnockEventEntity>, tapX: Float, tapY: Float, plot: Plot, radiusPx: Float,
        rpmMin: Float, rpmMax: Float, boostMin: Float, boostMax: Float
    ): Int? {
        var best: Int? = null
        var bestD2 = radiusPx * radiusPx
        for (i in events.indices) {
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
