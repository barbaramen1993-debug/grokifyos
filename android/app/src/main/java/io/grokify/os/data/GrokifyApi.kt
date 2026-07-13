package io.grokify.os.data

import io.grokify.os.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GrokifyApi(
    private val tokenProvider: () -> String?,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun authRequest(path: String): Request.Builder {
        val b = Request.Builder().url(BuildConfig.API_BASE + path)
        tokenProvider()?.takeIf { it.isNotBlank() }?.let {
            b.header("Authorization", "Bearer $it")
        }
        return b
    }

    private fun jsonBody(obj: JSONObject) =
        obj.toString().toRequestBody(jsonMedia)

    fun me(): JSONObject {
        val req = authRequest("/me.php").get().build()
        return executeJson(req)
    }

    fun status(): JSONObject {
        val req = authRequest("/status.php").get().build()
        return executeJson(req)
    }

    fun checkUpdate(versionCode: Int, versionName: String): JSONObject {
        val path = "/update.php?version_code=$versionCode&version_name=${
            java.net.URLEncoder.encode(versionName, "UTF-8")
        }"
        val req = authRequest(path).get().build()
        return executeJson(req)
    }

    /** Marketplace catalog (built-ins + remote script packages). */
    fun pluginCatalog(versionCode: Int, versionName: String): JSONObject {
        val path = "/plugins.php?version_code=$versionCode&version_name=${
            java.net.URLEncoder.encode(versionName, "UTF-8")
        }"
        val req = authRequest(path).get().build()
        return executeJson(req)
    }

    /** Absolute download URL for the latest published APK (auth required on request). */
    fun apkDownloadUrl(): String = BuildConfig.API_BASE.trimEnd('/') + "/apk-download.php"

    fun heartbeat(versionCode: Int, versionName: String): JSONObject {
        val body = JSONObject()
            .put("app_version_code", versionCode)
            .put("app_version_name", versionName)
        val req = authRequest("/devices.php?action=heartbeat")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    /** Upload active notification snapshot so Grok can pull them from the server. */
    fun uploadNotifications(
        notifications: org.json.JSONArray,
        versionCode: Int,
        versionName: String,
        accessGranted: Boolean = false,
        listenerBound: Boolean = false,
    ): JSONObject {
        val body = JSONObject()
            .put("notifications", notifications)
            .put("app_version_code", versionCode)
            .put("app_version_name", versionName)
            .put("access_granted", accessGranted)
            .put("listener_bound", listenerBound)
        val req = authRequest("/devices.php?action=notifications")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    /** Pull stored phone notification snapshots (optionally as note lines). */
    fun listNotifications(asNotes: Boolean = false): JSONObject {
        val q = if (asNotes) "?action=notifications&as_notes=1" else "?action=notifications"
        val req = authRequest("/devices.php$q").get().build()
        return executeJson(req)
    }

    fun createChatSession(title: String = "New Chat"): JSONObject {
        val body = JSONObject().put("title", title)
        val req = authRequest("/admin-system-chat-sessions.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun listChatSessions(): JSONObject {
        val req = authRequest("/admin-system-chat-sessions.php").get().build()
        return executeJson(req)
    }

    fun renameChatSession(id: String, title: String): JSONObject {
        val body = JSONObject()
            .put("action", "rename")
            .put("id", id)
            .put("title", title)
        val req = authRequest("/admin-system-chat-sessions.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun deleteChatSession(id: String): JSONObject {
        val req = authRequest("/admin-system-chat-sessions.php?id=${java.net.URLEncoder.encode(id, "UTF-8")}")
            .delete()
            .build()
        return executeJson(req)
    }

    /**
     * List session messages.
     * @param limit when > 0, returns a window of the newest messages (or older than [beforeId])
     * @param beforeId load messages with server id strictly less than this (older page)
     */
    fun listMessages(sessionId: String, limit: Int = 0, beforeId: Int = 0): JSONObject {
        val enc = java.net.URLEncoder.encode(sessionId, "UTF-8")
        val qs = buildString {
            append("session_id=$enc")
            if (limit > 0) append("&limit=$limit")
            if (beforeId > 0) append("&before_id=$beforeId")
        }
        val req = authRequest("/admin-system-chat-messages.php?$qs").get().build()
        return executeJson(req)
    }

    /** Grok Build / SuperGrok weekly usage pool (same source as CLI `/usage`). */
    fun usage(refresh: Boolean = false): JSONObject {
        val q = if (refresh) "?refresh=1" else ""
        val req = authRequest("/admin-system-chat-usage.php$q").get().build()
        return executeJson(req)
    }

    fun createMessage(sessionId: String, role: String, content: String): JSONObject {
        val body = JSONObject()
            .put("session_id", sessionId)
            .put("role", role)
            .put("content", content)
        val req = authRequest("/admin-system-chat-messages.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun toggleMessageExclude(messageId: Int, excluded: Boolean): JSONObject {
        val body = JSONObject()
            .put("action", "toggle_exclude")
            .put("message_id", messageId)
            .put("excluded", excluded)
        val req = authRequest("/admin-system-chat-messages.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun deleteMessage(messageId: Int): JSONObject {
        val body = JSONObject()
            .put("action", "delete")
            .put("message_id", messageId)
        val req = authRequest("/admin-system-chat-messages.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun editMessage(messageId: Int, content: String): JSONObject {
        val body = JSONObject()
            .put("action", "edit")
            .put("message_id", messageId)
            .put("content", content)
        val req = authRequest("/admin-system-chat-messages.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun models(): JSONObject {
        val req = authRequest("/admin-system-chat-models.php").get().build()
        return executeJson(req)
    }

    fun setModel(model: String): JSONObject {
        val body = JSONObject().put("model", model)
        val req = authRequest("/admin-system-chat-models.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun listNotes(): JSONObject {
        val body = JSONObject().put("action", "list")
        val req = authRequest("/admin-system-chat-notes.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun createNote(text: String): JSONObject {
        val body = JSONObject()
            .put("action", "create")
            .put("note_text", text)
        val req = authRequest("/admin-system-chat-notes.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun toggleNote(noteId: Int, enabled: Boolean): JSONObject {
        val body = JSONObject()
            .put("action", "toggle")
            .put("note_id", noteId)
            .put("enabled", if (enabled) 1 else 0)
        val req = authRequest("/admin-system-chat-notes.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    fun deleteNote(noteId: Int): JSONObject {
        val body = JSONObject()
            .put("action", "delete")
            .put("note_id", noteId)
        val req = authRequest("/admin-system-chat-notes.php")
            .post(jsonBody(body))
            .build()
        return executeJson(req)
    }

    private fun executeJson(req: Request): JSONObject {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(if (text.isBlank()) "{}" else text)
            } catch (_: Exception) {
                JSONObject().put("ok", false).put("error", "invalid_json")
            }
            if (!resp.isSuccessful) {
                if (!json.has("error")) json.put("error", "http_${resp.code}")
                json.put("ok", false)
            }
            return json
        }
    }
}
