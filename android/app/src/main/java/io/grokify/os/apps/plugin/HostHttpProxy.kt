package io.grokify.os.apps.plugin

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sandboxed HTTP for WebView plugins. Only allowlisted hosts.
 * Scripts never open raw sockets; host injects secrets via dedicated APIs.
 */
object HostHttpProxy {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val allowedHosts = setOf(
        "api.spotify.com",
        "accounts.spotify.com",
        "api.x.ai",
    )

    fun isAllowed(url: String): Boolean {
        return try {
            val u = java.net.URI(url)
            val host = u.host?.lowercase().orEmpty()
            val scheme = u.scheme?.lowercase().orEmpty()
            scheme == "https" && host in allowedHosts
        } catch (_: Exception) {
            false
        }
    }

    /**
     * @param headersJson JSON object of string headers
     * @return JSON: { ok, status, body, error? }
     */
    fun request(
        method: String,
        url: String,
        headersJson: String?,
        body: String?,
    ): String {
        val m = method.trim().uppercase().ifBlank { "GET" }
        if (!isAllowed(url)) {
            return JSONObject()
                .put("ok", false)
                .put("status", 0)
                .put("error", "host_not_allowed")
                .put("body", "")
                .toString()
        }
        return try {
            val builder = Request.Builder().url(url)
            val headers = runCatching {
                if (headersJson.isNullOrBlank()) JSONObject() else JSONObject(headersJson)
            }.getOrElse { JSONObject() }
            val keys = headers.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = headers.optString(k, "")
                if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v)
            }
            val media = "application/json; charset=utf-8".toMediaType()
            when (m) {
                "GET", "HEAD" -> builder.method(m, null)
                "DELETE" -> {
                    if (body.isNullOrBlank()) builder.delete()
                    else builder.delete(body.toRequestBody(media))
                }
                else -> {
                    val rb = (body ?: "").toRequestBody(media)
                    builder.method(m, rb)
                }
            }
            client.newCall(builder.build()).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                val errMsg = if (resp.isSuccessful) {
                    null
                } else {
                    extractApiError(respBody) ?: "http_${resp.code}"
                }
                // Spotify (and others) send Retry-After as seconds or an HTTP-date.
                val retryAfterSec = parseRetryAfterSeconds(resp.header("Retry-After"))
                JSONObject()
                    .put("ok", resp.isSuccessful)
                    .put("status", resp.code)
                    .put("body", respBody)
                    .put("error", errMsg ?: JSONObject.NULL)
                    .put(
                        "retryAfter",
                        if (retryAfterSec != null) retryAfterSec else JSONObject.NULL,
                    )
                    .toString()
            }
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("status", 0)
                .put("body", "")
                .put("error", e.message ?: "request_failed")
                .toString()
        }
    }

    /**
     * Parse Retry-After into whole seconds, or null if missing/unparseable.
     * Accepts integer seconds (Spotify's usual form) or HTTP-date.
     */
    private fun parseRetryAfterSeconds(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        t.toIntOrNull()?.let { return it.coerceIn(1, 600) }
        return try {
            val date = java.time.ZonedDateTime.parse(
                t,
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            )
            val sec = java.time.Duration.between(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC),
                date,
            ).seconds
            sec.toInt().coerceIn(1, 600)
        } catch (_: Exception) {
            null
        }
    }

    /** Spotify / xAI style: { "error": { "message": "…" } } or { "error": "…" }. */
    private fun extractApiError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val j = JSONObject(body)
            when (val err = j.opt("error")) {
                is JSONObject -> {
                    val msg = err.optString("message", "")
                    val reason = err.optString("reason", "")
                    when {
                        msg.isNotBlank() && reason.isNotBlank() -> "$msg ($reason)"
                        msg.isNotBlank() -> msg
                        reason.isNotBlank() -> reason
                        else -> null
                    }
                }
                is String -> if (err.isNotBlank()) {
                    val desc = j.optString("error_description", "")
                    if (desc.isNotBlank()) "$err: $desc" else err
                } else null
                else -> j.optString("message", "").ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }
}
