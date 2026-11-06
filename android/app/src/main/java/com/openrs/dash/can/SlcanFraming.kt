package com.openrs.dash.can

/**
 * One connection's SLCAN framing (#251). Plain — exactly DLC × 2 data characters — unless the connection's first
 * [DETECT_FRAMES] data frames all carry SLCAN's 4-hex timestamp suffix (another client left `Z1` on the adapter; the app
 * never sends it). A plain frame during detection, i.e. any mixed start, locks plain, so one corrupt early line can't
 * switch a connection. The mode is never changed mid-connection: [REJECT_RUN] consecutive lines of the other shape log
 * one FRAMING event instead. A new connection detects again (one instance per connection).
 */
internal class SlcanFraming {
    @Volatile var timestamped = false
        private set
    private var detecting = true
    private var timestampedSeen = 0
    private var otherRun = 0
    private var reported = false
    /** Lines of the other shape refused on this connection (area 4 diagnostics). */
    @Volatile var otherShapeRejected = 0
        private set

    /** Before parsing [line]: advances detection. Returns an event text to log once, else null. */
    fun observe(line: String): String? {
        if (!detecting) return null
        when (SlcanParser.shape(line) ?: return null) {
            SlcanParser.Shape.PLAIN -> detecting = false
            SlcanParser.Shape.TIMESTAMPED -> if (++timestampedSeen >= DETECT_FRAMES) {
                detecting = false
                timestamped = true
                return "SLCAN frames carry a 4-hex timestamp suffix (Z1 is set on the adapter) — parsed as timestamped " +
                    "for this connection"
            }
        }
        return null
    }

    /** [line] parsed under the connection's mode. */
    fun accepted() { otherRun = 0 }

    /** [line] was refused under the connection's mode: counts it if it has the other shape; returns an event once. */
    fun refused(line: String): String? {
        if (detecting) return null
        val shape = SlcanParser.shape(line) ?: return null
        if ((shape == SlcanParser.Shape.TIMESTAMPED) == timestamped) return null
        otherShapeRejected++
        if (++otherRun < REJECT_RUN || reported) return null
        reported = true
        return "$REJECT_RUN consecutive ${if (timestamped) "plain" else "timestamped"} SLCAN frames refused on a " +
            "${if (timestamped) "timestamped" else "plain"} connection (the mode is fixed per connection; reconnect to " +
            "detect again)"
    }

    companion object {
        const val DETECT_FRAMES = 8
        const val REJECT_RUN = 50
    }
}
