package com.openrs.dash.data

/**
 * Current weather snapshot fetched from OpenWeatherMap at trip start and every 15 minutes.
 * [iconCode] is the OWM icon code (e.g. "01d", "10n"). [emoji] maps it to a Unicode character
 * for display in the map overlay card without requiring network image loading.
 */
data class WeatherData(
    val tempC: Double,
    val feelsLikeC: Double,
    val description: String,
    val iconCode: String,
    val windMps: Double,
    val humidity: Int,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    val emoji: String get() = when {
        iconCode.startsWith("01") -> "☀"
        iconCode.startsWith("02") -> "⛅"
        iconCode.startsWith("03") || iconCode.startsWith("04") -> "☁"
        iconCode.startsWith("09") || iconCode.startsWith("10") -> "🌧"
        iconCode.startsWith("11") -> "⛈"
        iconCode.startsWith("13") -> "❄"
        iconCode.startsWith("50") -> "🌫"
        else -> "—"
    }
}
