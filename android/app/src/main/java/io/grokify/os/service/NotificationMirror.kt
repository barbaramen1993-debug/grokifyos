package io.grokify.os.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import io.grokify.os.BuildConfig
import io.grokify.os.GrokifyApp
import io.grokify.os.data.GrokifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of active status-bar notifications, synced to the GrokifyOS server
 * so Grok can pull them via the devices API / prompt notes.
 */
object NotificationMirror {
    private const val TAG = "NotificationMirror"
    private const val MAX_ITEMS = 60
    private const val UPLOAD_DEBOUNCE_MS = 1_200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val items = ConcurrentHashMap<String, NotifItem>()
    private val mutex = Mutex()
    private var uploadJob: Job? = null
    @Volatile private var shareEnabled: Boolean = true
    @Volatile private var listenerBound: Boolean = false

    data class NotifItem(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val subText: String,
        val category: String,
        val isOngoing: Boolean,
        val postTime: Long,
    )

    fun setShareEnabled(enabled: Boolean) {
        shareEnabled = enabled
        if (!enabled) {
            uploadJob?.cancel()
        } else {
            scheduleUpload()
        }
    }

    fun isShareEnabled(): Boolean = shareEnabled

    fun setListenerBound(bound: Boolean) {
        listenerBound = bound
    }

    fun isListenerBound(): Boolean = listenerBound

    fun snapshot(): List<NotifItem> =
        items.values.sortedByDescending { it.postTime }.take(MAX_ITEMS)

    fun noteLines(maxLines: Int = 40): List<String> {
        val list = snapshot()
        if (list.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        out += "Active phone notifications (${list.size}):"
        for (n in list) {
            val app = n.appLabel.ifBlank { n.packageName }.ifBlank { "app" }
            val body = when {
                n.title.isNotBlank() && n.text.isNotBlank() && n.text != n.title ->
                    "${n.title}: ${n.text}"
                n.title.isNotBlank() -> n.title
                n.text.isNotBlank() -> n.text
                else -> continue
            }
            out += "[$app] $body"
            if (out.size >= maxLines) {
                out += "…(truncated)"
                break
            }
        }
        return out
    }

    fun upsertFromSbn(context: Context, sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == context.packageName) return
        val item = fromSbn(context, sbn) ?: return
        items[item.key] = item
        trim()
        scheduleUpload()
    }

    fun removeFromSbn(sbn: StatusBarNotification?) {
        if (sbn == null) return
        items.remove(sbn.key)
        scheduleUpload()
    }

    fun replaceAll(context: Context, active: Array<StatusBarNotification>?) {
        items.clear()
        if (active != null) {
            for (sbn in active) {
                if (sbn.packageName == context.packageName) continue
                val item = fromSbn(context, sbn) ?: continue
                items[item.key] = item
            }
        }
        trim()
        scheduleUpload()
    }

    fun clear() {
        items.clear()
        scheduleUpload()
    }

    /**
     * Whether the system has this app's NotificationListenerService enabled.
     * Matches package + class robustly (debug suffix, OEM flatten formats).
     */
    fun isNotificationAccessEnabled(context: Context): Boolean {
        val cn = ComponentName(context, GrokifyNotificationListener::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val wantFlat = cn.flattenToString()
        val wantShort = "${cn.packageName}/${cn.shortClassName}"
        return flat.split(':').any { raw ->
            val part = raw.trim()
            if (part.isEmpty()) return@any false
            if (part.equals(wantFlat, ignoreCase = true)) return@any true
            if (part.equals(wantShort, ignoreCase = true)) return@any true
            // Package-only match for Grokify listener (some OEMs alter class path)
            if (part.startsWith(cn.packageName) && part.contains("GrokifyNotificationListener", ignoreCase = true)) {
                return@any true
            }
            val parsed = ComponentName.unflattenFromString(part) ?: return@any false
            if (parsed.packageName != cn.packageName) return@any false
            parsed.className == cn.className ||
                parsed.className.endsWith(".GrokifyNotificationListener") ||
                parsed.shortClassName.endsWith("GrokifyNotificationListener")
        }
    }

    /** Ask the system to (re)bind our listener after the user grants access. */
    fun requestRebind(context: Context) {
        try {
            val cn = ComponentName(context, GrokifyNotificationListener::class.java)
            NotificationListenerService.requestRebind(cn)
            Log.i(TAG, "requestRebind issued for ${cn.flattenToString()}")
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind: ${e.message}")
        }
    }

    private fun trim() {
        if (items.size <= MAX_ITEMS) return
        val drop = items.values.sortedBy { it.postTime }.take(items.size - MAX_ITEMS)
        for (d in drop) items.remove(d.key)
    }

    private fun scheduleUpload() {
        if (!shareEnabled) return
        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(UPLOAD_DEBOUNCE_MS)
            uploadNow()
        }
    }

    suspend fun uploadNow() {
        if (!shareEnabled) return
        mutex.withLock {
            try {
                val app = GrokifyApp.instance
                val token = app.tokenStore.tokenFlow.first()?.trim().orEmpty()
                if (token.isBlank()) return
                val api = GrokifyApi { token }
                val arr = JSONArray()
                for (n in snapshot()) {
                    arr.put(
                        JSONObject()
                            .put("key", n.key)
                            .put("package", n.packageName)
                            .put("app_label", n.appLabel)
                            .put("title", n.title)
                            .put("text", n.text)
                            .put("sub_text", n.subText)
                            .put("category", n.category)
                            .put("is_ongoing", n.isOngoing)
                            .put("post_time", n.postTime),
                    )
                }
                val accessGranted = isNotificationAccessEnabled(app)
                val res = api.uploadNotifications(
                    notifications = arr,
                    versionCode = BuildConfig.VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME,
                    accessGranted = accessGranted,
                    listenerBound = listenerBound,
                )
                if (!res.optBoolean("ok", false)) {
                    Log.w(TAG, "upload failed: ${res.optString("error")}")
                } else {
                    Log.i(
                        TAG,
                        "upload ok count=${arr.length()} access=$accessGranted bound=$listenerBound",
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "upload error: ${e.message}")
            }
        }
    }

    private fun charSeq(extras: android.os.Bundle, key: String): String =
        extras.getCharSequence(key)?.toString()?.trim().orEmpty()

    private fun firstNonBlank(vararg values: String): String {
        for (v in values) {
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun fromSbn(context: Context, sbn: StatusBarNotification): NotifItem? {
        val n = sbn.notification ?: return null
        val extras = n.extras

        var title = ""
        var body = ""
        var sub = ""

        if (extras != null) {
            title = firstNonBlank(
                charSeq(extras, Notification.EXTRA_TITLE),
                charSeq(extras, Notification.EXTRA_TITLE_BIG),
                charSeq(extras, Notification.EXTRA_CONVERSATION_TITLE),
            )
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
                ?.joinToString(" · ")
                .orEmpty()
            body = firstNonBlank(
                charSeq(extras, Notification.EXTRA_BIG_TEXT),
                charSeq(extras, Notification.EXTRA_TEXT),
                lines,
                charSeq(extras, Notification.EXTRA_INFO_TEXT),
                charSeq(extras, Notification.EXTRA_SUMMARY_TEXT),
            )
            sub = charSeq(extras, Notification.EXTRA_SUB_TEXT)
        }

        // MessagingStyle (SMS / chat apps) via AndroidX for broader OEM support
        try {
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            if (style != null) {
                if (title.isBlank()) {
                    title = style.conversationTitle?.toString()?.trim().orEmpty()
                    if (title.isBlank()) {
                        title = style.user?.name?.toString()?.trim().orEmpty()
                    }
                }
                if (body.isBlank()) {
                    val msgs = style.messages
                    if (msgs.isNotEmpty()) {
                        body = msgs.takeLast(3).joinToString(" · ") { m ->
                            val who = m.person?.name?.toString()?.trim()
                                ?: m.sender?.toString()?.trim().orEmpty()
                            val text = m.text?.toString()?.trim().orEmpty()
                            when {
                                who.isNotBlank() && text.isNotBlank() -> "$who: $text"
                                text.isNotBlank() -> text
                                else -> who
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // ignore style parse failures
        }

        if (title.isBlank() && body.isBlank()) {
            body = n.tickerText?.toString()?.trim().orEmpty()
        }

        // Skip pure-media / empty shells with no readable content
        if (title.isBlank() && body.isBlank()) return null

        val label = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString().orEmpty()
        } catch (_: Exception) {
            sbn.packageName
        }

        return NotifItem(
            key = sbn.key,
            packageName = sbn.packageName.orEmpty(),
            appLabel = label.take(128),
            title = title.take(512),
            text = body.take(2000),
            subText = sub.take(512),
            category = n.category.orEmpty().take(64),
            isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            postTime = sbn.postTime,
        )
    }
}
