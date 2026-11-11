package com.openrs.dash.data

import com.openrs.dash.diagnostics.LogText

/**
 * A drive bookmark is an observer's note: what the person in the car saw or did at that moment ("Cluster showed SPORT").
 * It is the observer's word, not a decoded signal, and every surface that shows one says so.
 *
 * One note is written twice by `DriveRecorder.addBookmark`: as a bookmark row with the drive (place, speed, RPM), and as
 * a `NOTE` session event on the diagnostic timeline, the clock that requests, link events and the FPS timeline use, so an
 * export reader can line it up with what the app sent and received.
 */
object ObserverNote {
    /** `kind` of every bookmark in the drive export. */
    const val KIND = "observer_note"
    /** Session-event type in the diagnostic export. */
    const val EVENT = "NOTE"
    /** Longest stored label. */
    const val MAX = 60

    /** What the cluster can show for the drive mode: one tap each in the note form. */
    val PRESETS = listOf("Cluster showed NORMAL", "Cluster showed SPORT", "Cluster showed TRACK", "Cluster showed DRIFT")

    /** The label as stored: surrounding space trimmed, runs of whitespace (line breaks included) to one space, bounded. */
    fun clean(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX).trim()

    /**
     * The session-event text: printable ASCII with identifiers masked ([LogText.sanitize]), since the diagnostic export
     * is shared more widely than a drive. [placed] = false when there was no GPS fix, so no bookmark row exists.
     */
    fun eventText(label: String, placed: Boolean): String {
        val text = LogText.sanitize(clean(label), MAX)
        val base = if (text.isEmpty()) "Observer note (no text)" else "Observer note: $text"
        return if (placed) base else "$base (no GPS fix, not on the map)"
    }
}
