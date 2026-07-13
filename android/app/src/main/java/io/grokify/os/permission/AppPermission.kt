package io.grokify.os.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Logical permission groups exposed to Settings toggles and AI-driven chat cards.
 * Each group may map to one or more Android runtime permissions depending on API level.
 */
enum class AppPermissionId(
    val id: String,
    val title: String,
    val description: String,
) {
    CAMERA(
        id = "camera",
        title = "Camera",
        description = "Take photos and capture video for the assistant",
    ),
    MICROPHONE(
        id = "microphone",
        title = "Microphone",
        description = "Record audio and voice for the assistant",
    ),
    LOCATION(
        id = "location",
        title = "Location",
        description = "Approximate and precise location while using the app",
    ),
    NOTIFICATIONS(
        id = "notifications",
        title = "Post notifications",
        description = "Show assistant alerts and the ongoing status notification",
    ),
    MEDIA(
        id = "media",
        title = "Photos & media",
        description = "Read images, video, and audio from your library",
    ),
    NEARBY_WIFI(
        id = "nearby_wifi",
        title = "Nearby Wi‑Fi devices",
        description = "Discover and connect to nearby Wi‑Fi devices",
    ),
    BLUETOOTH(
        id = "bluetooth",
        title = "Bluetooth",
        description = "Scan and connect to Bluetooth devices",
    );

    companion object {
        fun fromId(raw: String): AppPermissionId? {
            val key = raw.trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.id == key }
                ?: when (key) {
                    "mic", "audio", "record_audio" -> MICROPHONE
                    "gps", "fine_location", "coarse_location" -> LOCATION
                    "notification", "post_notifications" -> NOTIFICATIONS
                    "photos", "images", "storage", "gallery" -> MEDIA
                    "wifi", "nearby", "nearby_devices" -> NEARBY_WIFI
                    "bt" -> BLUETOOTH
                    else -> null
                }
        }

        /** Groups that exist on this device / API level. */
        fun available(): List<AppPermissionId> = entries.filter { it.manifestPermissions().isNotEmpty() }
    }

    /**
     * Android permissions required for this group on the current API.
     * Empty means the group is not applicable (e.g. NEARBY_WIFI below API 33).
     */
    fun manifestPermissions(): Array<String> = when (this) {
        CAMERA -> arrayOf(Manifest.permission.CAMERA)
        MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        LOCATION -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        NOTIFICATIONS -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
        }
        MEDIA -> {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                )
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        NEARBY_WIFI -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                emptyArray()
            }
        }
        BLUETOOTH -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                )
            } else {
                // Pre-S: BLUETOOTH/ADMIN are install-time, not runtime dialogs
                emptyArray()
            }
        }
    }
}

data class PermissionStatus(
    val id: AppPermissionId,
    val granted: Boolean,
    /** True when this API level exposes a runtime dialog for the group. */
    val requestable: Boolean,
)

object PermissionHelper {
    fun status(context: Context, id: AppPermissionId): PermissionStatus {
        val perms = id.manifestPermissions()
        if (perms.isEmpty()) {
            // Not a runtime group on this API — treat as granted/not requestable
            return PermissionStatus(id = id, granted = true, requestable = false)
        }
        val granted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        return PermissionStatus(id = id, granted = granted, requestable = true)
    }

    fun snapshot(context: Context): List<PermissionStatus> =
        AppPermissionId.available().map { status(context, it) }

    /** Permissions still missing for [id], ready for RequestMultiplePermissions. */
    fun missing(context: Context, id: AppPermissionId): Array<String> {
        return id.manifestPermissions()
            .filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            .toTypedArray()
    }

    fun isGranted(context: Context, id: AppPermissionId): Boolean = status(context, id).granted

    /** Compact lines for agent notes so Grok knows what it can use. */
    fun noteLines(context: Context): List<String> {
        val lines = mutableListOf(
            "[Device permissions — Android]",
        )
        for (s in snapshot(context)) {
            val state = when {
                !s.requestable -> "n/a"
                s.granted -> "granted"
                else -> "denied"
            }
            lines += "${s.id.id}: $state"
        }
        lines +=
            "To ask the user to enable a permission, include this marker in your reply " +
                "(it becomes an Allow / Not now card on the phone):"
        lines += "[[permission_request:camera|optional short reason]]"
        lines +=
            "Valid ids: " + AppPermissionId.available().joinToString(", ") { it.id }
        return lines
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) { /* ignore */ }
        }
    }

    /**
     * Parse AI markers: `[[permission_request:camera]]` or
     * `[[permission_request:camera|Need this for photos]]`.
     */
    fun parseRequestMarkers(text: String): List<Pair<AppPermissionId, String>> {
        if (text.isEmpty()) return emptyList()
        val re = Regex(
            """\[\[\s*permission_request\s*:\s*([a-zA-Z0-9_-]+)\s*(?:\|([^\]]+))?\]\]""",
            RegexOption.IGNORE_CASE,
        )
        val out = mutableListOf<Pair<AppPermissionId, String>>()
        val seen = mutableSetOf<AppPermissionId>()
        for (m in re.findAll(text)) {
            val id = AppPermissionId.fromId(m.groupValues[1]) ?: continue
            if (!seen.add(id)) continue
            val reason = m.groupValues.getOrNull(2)?.trim().orEmpty()
            out += id to reason
        }
        return out
    }

    /** Remove markers so they do not clutter the assistant bubble. */
    fun stripRequestMarkers(text: String): String {
        if (text.isEmpty()) return text
        val re = Regex(
            """\[\[\s*permission_request\s*:\s*[a-zA-Z0-9_-]+\s*(?:\|[^\]]+)?\]\]""",
            RegexOption.IGNORE_CASE,
        )
        return text
            .replace(re, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
