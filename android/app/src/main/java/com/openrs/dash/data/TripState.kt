package com.openrs.dash.data

/** Type of notable peak event — used to drop map markers during recording. */
enum class PeakType(val label: String) {
    RPM("Peak RPM"),
    BOOST("Peak Boost"),
    LATERAL_G("Peak Lat-G"),
    SPEED("Peak Speed")
}

/** A notable peak moment, stored so the map can drop a pin at its GPS coordinate. */
data class PeakEvent(
    val type: PeakType,
    val value: Double,
    val lat: Double,
    val lng: Double,
    val timestamp: Long
)
