package com.openrs.dash.diagnostics

/**
 * Text that may reach a diagnostic export from outside the app — exception messages, adapter replies (#220 / #215).
 * Printable ASCII only, bounded, with MAC addresses, WiCAN device names and long hex identifiers masked: an export
 * explains a failure without carrying adapter identifiers or arbitrary adapter output.
 */
internal object LogText {
    private val MAC = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
    private val DEVICE_NAME = Regex("(?i)\\bWiC_[0-9a-z]+")
    private val LONG_HEX = Regex("(?i)\\b[0-9a-f]{10,}\\b")

    fun sanitize(text: String, max: Int = 100): String =
        text.map { if (it in ' '..'~') it else ' ' }.joinToString("").trim()
            .replace(MAC, "<mac>").replace(DEVICE_NAME, "<device>").replace(LONG_HEX, "<id>")
            .take(max)

    /** "IOException: BLE write couldn't be sent (stack busy)" — class name + bounded, masked message. */
    fun sanitizeReason(t: Throwable): String = sanitize("${t.javaClass.simpleName}: ${t.message ?: "no message"}")
}
