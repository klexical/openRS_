package com.openrs.dash.data

/**
 * Knock event capture (rc.5, #186 / #187) — pure so per-cylinder behaviour is testable.
 *
 * One [KnockEventEntity] per cylinder whose KR value was freshly polled (per the drive's
 * [FreshnessGate]) and shows at least [KnockEventEntity.KNOCK_MIN_DEG] of retard.
 * Sign convention: retard is POSITIVE (ObdResponseParser 0x03EC–EF use raw / −512).
 *
 * KR cyl 2–4 DIDs (0x03ED–EF) are unverified on this car; if the PCM NRCs them the
 * poller suppresses them and their tags simply never turn fresh.
 */
object KnockCapture {

    /** Freshness tags for ignCorrCyl1..4, index 0 = cylinder 1. */
    val KR_TAGS = listOf("ignCorrCyl1", "ignCorrCyl2", "ignCorrCyl3", "ignCorrCyl4")

    fun krFor(vs: VehicleState, cylinder: Int): Double = when (cylinder) {
        1 -> vs.ignCorrCyl1
        2 -> vs.ignCorrCyl2
        3 -> vs.ignCorrCyl3
        4 -> vs.ignCorrCyl4
        else -> 0.0
    }

    /**
     * Consumes fresh KR stamps from [gate] (each cylinder's stamp is consumed whether or
     * not it shows retard, so a clean reading isn't re-examined next sample).
     */
    fun eventsFrom(
        vs: VehicleState,
        gate: FreshnessGate,
        driveId: Long,
        now: Long,
        lat: Double?,
        lng: Double?
    ): List<KnockEventEntity> {
        val stamps = vs.fieldLastUpdateMs
        var out: MutableList<KnockEventEntity>? = null
        for (cyl in 1..4) {
            if (!gate.takeIfFresh(KR_TAGS[cyl - 1], stamps)) continue
            val kr = krFor(vs, cyl)
            if (kr < KnockEventEntity.KNOCK_MIN_DEG) continue
            val list = out ?: ArrayList<KnockEventEntity>(4).also { out = it }
            list += KnockEventEntity(
                driveId     = driveId,
                timestamp   = now,
                krDeg       = kr,
                rpm         = vs.rpm.toInt(),
                boostPsi    = vs.boostPsi,
                iatC        = vs.intakeTempC,
                throttlePct = vs.throttleOrPedalPct,
                gearEst     = vs.derivedGear,
                lat         = lat,
                lng         = lng,
                cylinder    = cyl
            )
        }
        return out ?: emptyList()
    }
}
