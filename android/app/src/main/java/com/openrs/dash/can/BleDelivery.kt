package com.openrs.dash.can

/** Bluetooth delivery of one session or attempt (#421): notifications, SLCAN lines completed, most lines in one. */
data class BleDelivery(val notifications: Long, val lines: Long, val mostLinesInOne: Int) {
    /** Two attempts of one session: the counts add, the most lines is the larger. */
    operator fun plus(o: BleDelivery) =
        BleDelivery(notifications + o.notifications, lines + o.lines, maxOf(mostLinesInOne, o.mostLinesInOne))
    companion object { val NONE = BleDelivery(0, 0, 0) }
}

/**
 * One attempt's Bluetooth delivery counts (#421), written by [BleGattCore.onNotify] on the GATT callback thread and read
 * once at export: plain volatile fields with one writer, no lock per notification (about 180 a second).
 */
class BleDeliveryCounts {
    @Volatile private var notifications = 0L
    @Volatile private var lines = 0L
    @Volatile private var mostLines = 0

    /** One notification of the notify characteristic, which completed [lines] SLCAN lines. */
    fun notified(lines: Int) {
        notifications += 1
        this.lines += lines
        if (lines > mostLines) mostLines = lines
    }

    fun read(): BleDelivery = BleDelivery(notifications, lines, mostLines)
}
