package io.grokify.os.wear.data

import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject

/**
 * Last-known telemetry for the Carina tile (and cold starts).
 * [SensorHub] writes on each tick; the tile reads when the app process may be dead.
 */
object WearTelemetryCache {
    private const val PREFS = "wear_telemetry_cache"
    private const val KEY_JSON = "snapshot_json"

    fun write(context: Context, snap: WearSnapshot) {
        val json = JSONObject()
            .put("t", snap.timeMillis)
            .put("hr", snap.heartRateBpm?.toDouble() ?: JSONObject.NULL)
            .put("steps", snap.stepsToday ?: JSONObject.NULL)
            .put("bat", snap.batteryPct ?: JSONObject.NULL)
            .put("heading", snap.headingDeg?.toDouble() ?: JSONObject.NULL)
            .put("wx", snap.weatherTempC?.toDouble() ?: JSONObject.NULL)
            .put("wxLabel", snap.weatherLabel)
            .put("media", snap.mediaTitle)
            .put("msg", snap.lastNotification)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, json.toString())
            .apply()
    }

    fun read(context: Context): WearSnapshot {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
            ?: return WearSnapshot(batteryPct = readBattery(context))
        return try {
            val o = JSONObject(raw)
            WearSnapshot(
                timeMillis = o.optLong("t", System.currentTimeMillis()),
                heartRateBpm = o.optDoubleOrNull("hr")?.toFloat(),
                stepsToday = o.optLongOrNull("steps"),
                batteryPct = o.optIntOrNull("bat") ?: readBattery(context),
                headingDeg = o.optDoubleOrNull("heading")?.toFloat(),
                weatherTempC = o.optDoubleOrNull("wx")?.toFloat(),
                weatherLabel = o.optString("wxLabel", null)?.takeIf { it.isNotBlank() && it != "null" },
                mediaTitle = o.optString("media", null)?.takeIf { it.isNotBlank() && it != "null" },
                lastNotification = o.optString("msg", null)?.takeIf { it.isNotBlank() && it != "null" },
            )
        } catch (_: Exception) {
            WearSnapshot(batteryPct = readBattery(context))
        }
    }

    /** Live battery even when the hub hasn't run. */
    fun readBattery(context: Context): Int? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) pct else null
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }
}
