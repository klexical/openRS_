package com.openrs.dash.service

import com.openrs.dash.data.WeatherData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fetches current weather from OpenWeatherMap's `/data/2.5/weather` endpoint.
 * Returns null silently on any network or parse failure so the trip continues unaffected.
 *
 * [apiKey] is read from BuildConfig.OPENWEATHER_API_KEY (injected from local.properties).
 */
class WeatherRepository(private val apiKey: String) {

    private val client = OkHttpClient()

    suspend fun fetchWeather(lat: Double, lon: Double): WeatherData? {
        if (apiKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.openweathermap.org/data/2.5/weather" +
                    "?lat=$lat&lon=$lon&appid=$apiKey&units=metric"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val json    = JSONObject(body)
                    val main    = json.getJSONObject("main")
                    val weather = json.getJSONArray("weather").getJSONObject(0)
                    val wind    = json.getJSONObject("wind")
                    WeatherData(
                        tempC       = main.getDouble("temp"),
                        feelsLikeC  = main.getDouble("feels_like"),
                        description = weather.getString("description")
                            .replaceFirstChar { it.uppercase() },
                        iconCode    = weather.getString("icon"),
                        windMps     = wind.getDouble("speed"),
                        humidity    = main.getInt("humidity")
                    )
                }
            } catch (_: Exception) { null }
        }
    }
}
