package com.openrs.dash.data

/**
 * Aggregate view of a set of knock events (rc.5, #191) — shared by the PERF card and tests.
 *
 * Severity buckets are half-open (<2°, 2–4°, ≥4°) and the low bucket takes everything below
 * 2°, so `low + med + high == count` always holds (#191).
 */
data class KnockStats(
    val count: Int,
    val worst: KnockEventEntity?,
    val avgDeg: Double,
    val lowCount: Int,          // kr < 2°
    val medCount: Int,          // 2° ≤ kr < 4°
    val highCount: Int,         // kr ≥ 4°
    val perCylinder: IntArray   // index 0..3 = cyl 1..4 (legacy cylinder 0 rows count as cyl 1)
) {
    companion object {
        const val MED_DEG = 2.0
        const val HIGH_DEG = 4.0

        val EMPTY = KnockStats(0, null, 0.0, 0, 0, 0, IntArray(4))

        fun from(events: List<KnockEventEntity>): KnockStats {
            if (events.isEmpty()) return EMPTY
            var sum = 0.0
            var low = 0; var med = 0; var high = 0
            var worst: KnockEventEntity? = null
            val perCyl = IntArray(4)
            for (ev in events) {
                sum += ev.krDeg
                if (worst == null || ev.krDeg > worst.krDeg) worst = ev
                when {
                    ev.krDeg >= HIGH_DEG -> high++
                    ev.krDeg >= MED_DEG  -> med++
                    else                 -> low++   // incl. sub-threshold v10 rows (rc.4 captured any kr > 0)
                }
                val cyl = if (ev.cylinder in 1..4) ev.cylinder else 1
                perCyl[cyl - 1]++
            }
            return KnockStats(events.size, worst, sum / events.size, low, med, high, perCyl)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KnockStats && count == other.count && worst == other.worst && avgDeg == other.avgDeg &&
            lowCount == other.lowCount && medCount == other.medCount && highCount == other.highCount &&
            perCylinder.contentEquals(other.perCylinder)

    override fun hashCode(): Int = 31 * count + perCylinder.contentHashCode()
}
