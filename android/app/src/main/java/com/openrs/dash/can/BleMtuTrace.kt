package com.openrs.dash.can

/**
 * What one Bluetooth connection attempt asked for and was told about the ATT MTU, kept apart from the size its writes
 * really use (#253). The export read "MTU" from nothing before: a capture could not say whether 23 was negotiated,
 * refused, never answered or never asked for.
 *
 * One trace belongs to one [BleGattCore], so to one attempt: a reconnect builds a new core and a new, empty trace.
 * Written from the setup coroutine and from GATT callbacks (any thread), read by the export; every access is locked.
 */
class BleMtuTrace {
    private var requested: Int? = null
    private var started: Boolean? = null
    private var cbStatus: Int? = null
    private var cbMtu: Int? = null
    private var timedOut = false
    private var used = BleGattCore.DEFAULT_MTU

    /** The setup asked the phone for [mtu]; [started] is what the phone's stack returned for the request itself. */
    @Synchronized fun requested(mtu: Int, started: Boolean) { requested = mtu; this.started = started }

    /** The MTU callback arrived (also a late one, after the wait ended); [usedNow] is what writes use from here on. */
    @Synchronized fun callback(mtu: Int, status: Int, usedNow: Int) { cbMtu = mtu; cbStatus = status; used = usedNow }

    /** The bounded wait for the callback ended without one. */
    @Synchronized fun waitTimedOut() { timedOut = true }

    @Synchronized fun read() = BleMtuContext(requested, started, cbStatus, cbMtu, timedOut, used)
}

/**
 * One reading of a [BleMtuTrace]. [callbackMtu] is the number the callback carried, whatever its status; [usedMtu] is
 * the size the app's writes are split by, which is the 23-byte default unless a callback reported success.
 */
data class BleMtuContext(
    val requestedMtu: Int?,
    val requestStarted: Boolean?,
    val callbackStatus: Int?,
    val callbackMtu: Int?,
    val waitTimedOut: Boolean,
    val usedMtu: Int,
) {
    /** True while no callback has reported success: [usedMtu] is then the default, not a negotiated size. */
    val usedIsFallback: Boolean get() = callbackStatus != BleSetupPolicy.GATT_SUCCESS

    /** Fixed words for the export (never free text): what became of the request. */
    val outcome: String get() = when {
        requestedMtu == null -> NOT_REQUESTED
        requestStarted == false -> REQUEST_REFUSED
        callbackStatus == BleSetupPolicy.GATT_SUCCESS -> NEGOTIATED
        callbackStatus != null -> CALLBACK_FAILED
        waitTimedOut -> NO_CALLBACK
        else -> WAITING
    }

    companion object {
        const val NOT_REQUESTED = "not_requested"
        const val REQUEST_REFUSED = "request_refused"
        const val NEGOTIATED = "negotiated"
        const val CALLBACK_FAILED = "callback_failed"
        const val NO_CALLBACK = "no_callback"
        const val WAITING = "waiting"
    }
}
