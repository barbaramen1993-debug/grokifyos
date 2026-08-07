package io.grokify.os.wear.data

/**
 * Live telemetry snapshot for the radial HUD and (later) phone sync.
 * Nulls mean unavailable / permission not granted / sensor not present.
 */
data class WearSnapshot(
    val timeMillis: Long = System.currentTimeMillis(),
    val heartRateBpm: Float? = null,
    val stepsToday: Long? = null,
    val headingDeg: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,
    val weatherTempC: Float? = null,
    val weatherLabel: String? = null,
    val mediaTitle: String? = null,
    val mediaArtist: String? = null,
    val lastNotification: String? = null,
    val sleepHours: Float? = null,
    val batteryPct: Int? = null,
    /** Rolling HR samples for sparkline (oldest → newest). */
    val hrHistory: List<Float> = emptyList(),
    val permissionHints: List<String> = emptyList(),
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}
