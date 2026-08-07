package io.grokify.os.wear.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lightweight free weather via Open-Meteo (no API key).
 * Call off the main thread.
 */
object WeatherFetcher {
    data class Result(val tempC: Float, val label: String)

    fun fetch(lat: Double, lon: Double): Result? {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${"%.4f".format(Locale.US, lat)}" +
                "&longitude=${"%.4f".format(Locale.US, lon)}" +
                "&current=temperature_2m,weather_code" +
                "&timezone=auto",
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).optJSONObject("current") ?: return null
            val temp = current.optDouble("temperature_2m", Double.NaN)
            if (temp.isNaN()) return null
            val code = current.optInt("weather_code", -1)
            Result(tempC = temp.toFloat(), label = weatherLabel(code))
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun weatherLabel(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2, 3 -> "Clouds"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        95, 96, 99 -> "Storm"
        else -> "Weather"
    }

    fun formatTemp(tempC: Float): String {
        val t = tempC.roundToInt()
        return "${t}°C"
    }
}
