package com.openrs.dash.data

/**
 * Which 0x0F8 bytes a drive's OIL and PTU readings came from (#229) — saved with every new drive
 * (`DriveEntity.oilPtuMapping`, Room v14) and exported with it.
 *
 * Earlier builds read byte 1 − 50 as oil and byte 7 − 60 as PTU. On the one car checked against oil and PTU replies on
 * the same clock, those are the other way round. Their drives have no value (null): the mapping they were recorded
 * under was never stored, so it is shown as unknown. It is never inferred (date, app version, thermal-record format),
 * never restamped, and their readings, peaks, TEMP flags and progression stay exactly as recorded.
 */
object OilPtuMapping {
    /** Oil = 0x0F8 byte 7 − 60 °C, PTU = byte 1 − 50 °C — what this build's CanDecoder reads. */
    const val CURRENT = "0f8-oil-b7-ptu-b1"

    /** True only for a drive saved with this build's mapping; null / other values are not claimed. */
    fun isCurrent(mapping: String?): Boolean = mapping == CURRENT

    /** Export / text description of a drive's [mapping]. */
    fun describe(mapping: String?): String = when (mapping) {
        CURRENT -> "oil = 0x0F8 byte 7 − 60 °C, PTU = byte 1 − 50 °C (#229 correction)"
        null -> "not recorded — the drive predates the #229 correction (oil = byte 1, PTU = byte 7 then); " +
            "its OIL and PTU readings may be each other's (they were on the car checked) — values as recorded"
        else -> "$mapping (not known to this version)"
    }

    /** Short tag beside a recorded OIL / PTU value of a drive without [CURRENT] (like boost's "src n/r"). */
    const val UNKNOWN_TAG = "map n/r"

    /** One-line note where a drive's recorded OIL / PTU are shown and its mapping isn't [CURRENT]. */
    const val UNKNOWN_NOTE = "Recorded before the oil / PTU correction (#229) — OIL and PTU may be each other's reading"

    /**
     * [drives] (oldest first) of the newest drive's kind — this mapping, or not recorded — so a trend never joins
     * corrected and earlier readings on one line (like boost's StoredBoost.peakTrend).
     */
    fun sameKindAsNewest(drives: List<DriveEntity>): List<DriveEntity> {
        val newest = drives.lastOrNull() ?: return emptyList()
        return drives.filter { isCurrent(it.oilPtuMapping) == isCurrent(newest.oilPtuMapping) }
    }
}
