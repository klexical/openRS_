package com.openrs.dash.ui

/**
 * The Bluetooth adapter choice inside Settings (#218): a picker selection or FORGET shows at once but is only staged —
 * SAVE persists it, CANCEL (or any other exit) leaves the saved device as it was. Address + name; null = none.
 */
data class BleDeviceDraft(val saved: Pair<String, String>?, val staged: Pair<String, String>?) {
    fun select(address: String, name: String) = copy(staged = address to name)
    fun forget() = copy(staged = null)
    val changed: Boolean get() = staged != saved

    /** On SAVE: persist the staged choice if it differs from the saved one. */
    fun commit(save: (address: String, name: String) -> Unit, clear: () -> Unit) {
        if (!changed) return
        staged?.let { (a, n) -> save(a, n) } ?: clear()
    }

    /** SAVE is refused while [connectionMethod] needs a device this draft doesn't have (#234) — FORGET counts. */
    fun saveProblem(connectionMethod: String): String? =
        com.openrs.dash.service.ConnectionSetup.problem(connectionMethod, staged?.first)

    companion object {
        fun of(address: String?, name: String?): BleDeviceDraft {
            val d = address?.let { it to (name ?: "WiCAN") }
            return BleDeviceDraft(d, d)
        }
    }
}
