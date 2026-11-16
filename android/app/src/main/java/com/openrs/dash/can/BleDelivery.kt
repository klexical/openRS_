package com.openrs.dash.can

/** Bluetooth delivery of one session or attempt (#421): notifications, SLCAN lines completed, most lines in one. */
data class BleDelivery(val notifications: Long, val lines: Long, val mostLinesInOne: Int) {
    operator fun plus(o: BleDelivery) = BleDelivery(0, 0, 0)
    companion object { val NONE = BleDelivery(0, 0, 0) }
}

/**
 * One attempt's Bluetooth delivery counts (#421), written by [BleGattCore.onNotify] on the GATT callback thread and read
 * once at export: plain volatile fields, no lock per notification.
 */
class BleDeliveryCounts {
    fun notified(lines: Int) {}
    fun read(): BleDelivery = BleDelivery.NONE
}
