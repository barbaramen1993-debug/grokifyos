package io.grokify.os.wear.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * Collects on-watch telemetry for the radial HUD.
 *
 * Uses platform sensors + location (no Samsung Health partner SDK).
 * Sleep / deep Samsung metrics remain placeholders until partner access.
 */
class SensorHub(
    private val context: Context,
    private val scope: CoroutineScope,
) : SensorEventListener, LocationListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _snapshot = MutableStateFlow(WearSnapshot())
    val snapshot: StateFlow<WearSnapshot> = _snapshot.asStateFlow()

    private val hrHistory = CopyOnWriteArrayList<Float>()
    private val prefs = context.getSharedPreferences("sensor_hub", Context.MODE_PRIVATE)
    /**
     * Steps-today uses **deltas** from [Sensor.TYPE_STEP_COUNTER] (since-boot total).
     * Survives app restarts; on counter reboot (total drops) we keep today's sum and
     * continue from the new base. Day rollover zeros the sum.
     */
    private var stepsToday: Long = prefs.getLong(KEY_STEPS_TODAY, 0L).coerceAtLeast(0L)
    private var stepDayKey: String? = prefs.getString(KEY_STEP_DAY, null)
    private var lastStepCounter: Long = prefs.getLong(KEY_LAST_COUNTER, -1L)
    private var lastWeatherAt = 0L
    private var clockJob: Job? = null
    private var running = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val pct = (level * 100f / scale).roundToInt()
                _snapshot.update { it.copy(batteryPct = pct) }
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        // Seed today's steps from disk before first sensor event.
        rolloverStepsIfNeeded()
        _snapshot.update { it.copy(stepsToday = stepsToday) }
        refreshPermissionsHints()
        registerSensors()
        registerLocation()
        registerBattery()
        clockJob = scope.launch {
            while (isActive && running) {
                rolloverStepsIfNeeded()
                _snapshot.update {
                    it.copy(
                        timeMillis = System.currentTimeMillis(),
                        stepsToday = stepsToday,
                        mediaTitle = NotificationBridge.lastMediaTitle,
                        mediaArtist = NotificationBridge.lastMediaArtist,
                        lastNotification = NotificationBridge.lastMessage,
                    )
                }
                WearTelemetryCache.write(context, _snapshot.value)
                maybeRefreshWeather()
                delay(1_000)
            }
        }
    }

    fun stop() {
        running = false
        clockJob?.cancel()
        clockJob = null
        sensorManager.unregisterListener(this)
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
    }

    fun onPermissionsChanged() {
        refreshPermissionsHints()
        registerSensors()
        registerLocation()
    }

    private fun refreshPermissionsHints() {
        val hints = mutableListOf<String>()
        if (!has(Manifest.permission.BODY_SENSORS)) hints += "Heart rate"
        if (!has(Manifest.permission.ACTIVITY_RECOGNITION)) hints += "Steps"
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !has(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            hints += "Location"
        }
        _snapshot.update { it.copy(permissionHints = hints) }
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun registerSensors() {
        sensorManager.unregisterListener(this)
        fun reg(type: Int, us: Int = SensorManager.SENSOR_DELAY_UI) {
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, us)
            }
        }
        if (has(Manifest.permission.BODY_SENSORS)) {
            reg(Sensor.TYPE_HEART_RATE, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (has(Manifest.permission.ACTIVITY_RECOGNITION) || Build.VERSION.SDK_INT < 29) {
            reg(Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL)
            // Fallback incremental ticks when counter is missing / stalled.
            reg(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_NORMAL)
        }
        reg(Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_GAME)
        reg(Sensor.TYPE_ORIENTATION, SensorManager.SENSOR_DELAY_UI)
    }

    private fun registerLocation() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !has(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return
        }
        try {
            val providers = buildList {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    add(LocationManager.GPS_PROVIDER)
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    add(LocationManager.NETWORK_PROVIDER)
                }
                if (Build.VERSION.SDK_INT >= 31 &&
                    locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)
                ) {
                    add(LocationManager.FUSED_PROVIDER)
                }
            }
            for (p in providers) {
                locationManager.requestLocationUpdates(p, 15_000L, 15f, this)
                locationManager.getLastKnownLocation(p)?.let { applyLocation(it) }
            }
        } catch (_: SecurityException) {
        }
    }

    private fun registerBattery() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        val sticky = context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        sticky?.let { batteryReceiver.onReceive(context, it) }
    }

    private suspend fun maybeRefreshWeather() {
        val snap = _snapshot.value
        val lat = snap.latitude ?: return
        val lon = snap.longitude ?: return
        val now = System.currentTimeMillis()
        if (now - lastWeatherAt < 10 * 60_000L && snap.weatherTempC != null) return
        lastWeatherAt = now
        val result = withContext(Dispatchers.IO) {
            WeatherFetcher.fetch(lat, lon)
        } ?: return
        _snapshot.update {
            it.copy(
                weatherTempC = result.tempC,
                weatherLabel = result.label,
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.firstOrNull() ?: return
                if (bpm <= 0f || bpm > 250f) return
                hrHistory.add(bpm)
                while (hrHistory.size > 48) hrHistory.removeAt(0)
                _snapshot.update {
                    it.copy(
                        heartRateBpm = bpm,
                        hrHistory = hrHistory.toList(),
                    )
                }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull()?.toLong() ?: return
                if (total < 0L) return
                applyStepCounter(total)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                // Only use detector when we have never seen a counter (or counter stuck).
                if (lastStepCounter < 0L) {
                    rolloverStepsIfNeeded()
                    stepsToday += 1L
                    persistSteps()
                    _snapshot.update { it.copy(stepsToday = stepsToday) }
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rot = FloatArray(9)
                val orient = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, orient)
                var deg = Math.toDegrees(orient[0].toDouble()).toFloat()
                if (deg < 0) deg += 360f
                _snapshot.update { it.copy(headingDeg = deg) }
            }
            Sensor.TYPE_ORIENTATION -> {
                // Fallback when rotation vector is missing.
                if (_snapshot.value.headingDeg == null) {
                    var deg = event.values[0]
                    if (deg < 0) deg += 360f
                    _snapshot.update { it.copy(headingDeg = deg) }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) = applyLocation(location)

    @Deprecated("Deprecated in Java")
    override fun onLocationChanged(locations: MutableList<Location>) {
        locations.lastOrNull()?.let { applyLocation(it) }
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun applyLocation(location: Location) {
        _snapshot.update {
            it.copy(
                latitude = location.latitude,
                longitude = location.longitude,
                locationAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
            )
        }
    }

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun rolloverStepsIfNeeded() {
        val day = todayKey()
        if (stepDayKey != day) {
            stepsToday = 0L
            stepDayKey = day
            // Keep lastStepCounter so the next counter event only adds the delta after midnight
            // (not the whole since-boot total).
            persistSteps()
        }
    }

    private fun applyStepCounter(total: Long) {
        rolloverStepsIfNeeded()
        if (lastStepCounter < 0L) {
            // First sample of this install / after prefs clear: seed counter without
            // inventing a huge "today" from the since-boot total.
            lastStepCounter = total
            persistSteps()
            _snapshot.update { it.copy(stepsToday = stepsToday) }
            return
        }
        if (total < lastStepCounter) {
            // Device reboot (or sensor reset): counter restarted at a lower value.
            // Keep stepsToday already accumulated and resume from the new base.
            lastStepCounter = total
            persistSteps()
            return
        }
        val delta = total - lastStepCounter
        if (delta > 0L) {
            // Guard against absurd jumps (sensor glitches).
            val add = delta.coerceAtMost(50_000L)
            stepsToday += add
            lastStepCounter = total
            persistSteps()
            _snapshot.update { it.copy(stepsToday = stepsToday) }
        } else {
            // No change — still refresh snapshot so UI isn't stuck on null.
            _snapshot.update { it.copy(stepsToday = stepsToday) }
        }
    }

    private fun persistSteps() {
        prefs.edit()
            .putLong(KEY_STEPS_TODAY, stepsToday)
            .putString(KEY_STEP_DAY, stepDayKey)
            .putLong(KEY_LAST_COUNTER, lastStepCounter)
            // Drop legacy baseline keys if present.
            .remove("step_baseline")
            .remove("step_baseline_day")
            .apply()
    }

    companion object {
        private const val KEY_STEPS_TODAY = "steps_today"
        private const val KEY_STEP_DAY = "step_day"
        private const val KEY_LAST_COUNTER = "step_last_counter"
    }
}
