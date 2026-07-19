package io.grokify.os.apps.plugin

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import io.grokify.os.BuildConfig
import io.grokify.os.data.ApiKeyIds
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Spotify Authorization Code + PKCE for plugin scripts.
 *
 * Redirect uses HTTPS bounce on the host site (Spotify accepts this reliably),
 * which then deep-links into [APP_REDIRECT] handled by MainActivity.
 *
 * Pending PKCE verifier/state is persisted — the OS often kills the process
 * while the user is in the browser, which used to make every callback look
 * "invalid/expired". Handled codes are also remembered so onResume does not
 * re-process the same deep link and overwrite a successful login.
 */
object SpotifyOAuth {
    private const val TAG = "SpotifyOAuth"
    /** Deep link into the APK (AndroidManifest intent-filter). */
    const val APP_REDIRECT = "grokifyos://spotify-callback"
    /**
     * Value registered in Spotify Developer Dashboard.
     * Must match authorize + token exchange exactly.
     */
    val REDIRECT_URI: String =
        BuildConfig.SITE_URL.trimEnd('/') + "/spotify-callback.php"
    private const val AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val PREFS = "spotify_oauth_pkce"
    private const val KEY_VERIFIER = "code_verifier"
    private const val KEY_STATE = "state"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_HANDLED_CODE = "handled_code"
    /** PKCE login must complete within this window. */
    private const val PENDING_TTL_MS = 15 * 60 * 1000L

    /**
     * Process-wide Web API cool-down shared by Live DJ, widgets, Control UI, and
     * library calls. Without this, each caller backed off independently while the
     * others kept hammering player endpoints — 429 never cleared for hours.
     */
    @Volatile private var rateLimitedUntilMs: Long = 0L
    @Volatile private var rateLimitBackoffMs: Long = 0L
    private const val RATE_BACKOFF_MIN_MS = 15_000L
    private const val RATE_BACKOFF_MAX_MS = 300_000L // 5 min ceiling

    /** True while any recent Spotify Web API 429 cool-down is active. */
    fun isRateLimited(now: Long = System.currentTimeMillis()): Boolean =
        now < rateLimitedUntilMs

    /** Milliseconds left on the global cool-down (0 if clear). */
    fun rateLimitRemainingMs(now: Long = System.currentTimeMillis()): Long =
        (rateLimitedUntilMs - now).coerceAtLeast(0L)

    /**
     * Record a 429 / rate-limit response. Prefer Spotify's Retry-After when present.
     * Exponential backoff grows to [RATE_BACKOFF_MAX_MS] so deep holes recover.
     */
    fun noteHttpRateLimit(retryAfterSec: Int? = null) {
        val fromHeader = retryAfterSec?.takeIf { it > 0 }?.times(1000L)
        val next = when {
            fromHeader != null -> fromHeader.coerceIn(5_000L, RATE_BACKOFF_MAX_MS)
            rateLimitBackoffMs <= 0L -> RATE_BACKOFF_MIN_MS
            else -> (rateLimitBackoffMs * 2).coerceAtMost(RATE_BACKOFF_MAX_MS)
        }
        rateLimitBackoffMs = next
        rateLimitedUntilMs = System.currentTimeMillis() + next
        Log.w(TAG, "global Spotify rate-limit cooldown ${next}ms (retryAfter=$retryAfterSec)")
    }

    /**
     * Soft clear after a successful API call once the window has elapsed.
     * Decays rather than zero so a second 429 still backs off harder.
     */
    fun clearRateLimitSoft() {
        if (rateLimitedUntilMs <= 0L && rateLimitBackoffMs <= 0L) return
        val now = System.currentTimeMillis()
        if (now >= rateLimitedUntilMs) {
            rateLimitedUntilMs = 0L
            rateLimitBackoffMs = (rateLimitBackoffMs / 2).coerceAtMost(RATE_BACKOFF_MIN_MS)
            if (rateLimitBackoffMs < 5_000L) rateLimitBackoffMs = 0L
        }
    }

    private fun rateLimitedEnvelope(): String {
        val waitSec = (rateLimitRemainingMs() / 1000L).toInt().coerceAtLeast(1)
        return JSONObject()
            .put("ok", false)
            .put("status", 429)
            .put("error", "rate_limited")
            .put("body", "")
            .put("retryAfter", waitSec)
            .toString()
    }

    private fun noteRateLimitFromEnvelope(raw: String) {
        try {
            val o = JSONObject(raw)
            val status = o.optInt("status", 0)
            val err = if (o.isNull("error")) "" else o.optString("error", "")
            val is429 = status == 429 ||
                err.contains("429") ||
                err.contains("rate limit", ignoreCase = true) ||
                err.contains("too many requests", ignoreCase = true)
            if (!is429) {
                if (o.optBoolean("ok", false) || status in listOf(200, 201, 202, 204)) {
                    clearRateLimitSoft()
                }
                return
            }
            val retry = if (o.isNull("retryAfter")) null
            else o.optInt("retryAfter", 0).takeIf { it > 0 }
            noteHttpRateLimit(retry)
        } catch (_: Exception) {
            // ignore parse failures
        }
    }

    private val SCOPES = listOf(
        "user-read-email",
        "user-read-private",
        "user-read-recently-played",
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "playlist-read-private",
        "playlist-read-collaborative",
        "playlist-modify-public",
        "playlist-modify-private",
        "user-library-read",
        "user-library-modify",
        "user-top-read",
    ).joinToString(" ")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class Pending(
        val codeVerifier: String,
        val state: String,
        val startedAt: Long = System.currentTimeMillis(),
    )

    private val pending = AtomicReference<Pending?>(null)
    @Volatile var lastAuthMessage: String? = null

    fun isLoggedIn(ctx: Context): Boolean =
        !HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_ACCESS_TOKEN).isNullOrBlank() ||
            !HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_REFRESH_TOKEN).isNullOrBlank()

    fun authStatusJson(ctx: Context): String {
        val access = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_ACCESS_TOKEN)
        val refresh = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_REFRESH_TOKEN)
        val clientId = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_CLIENT_ID)
        val expiresAt = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_TOKEN_EXPIRES_AT)
            ?.toLongOrNull() ?: 0L
        return JSONObject()
            .put("ok", true)
            .put("hasClientId", !clientId.isNullOrBlank())
            .put("loggedIn", !access.isNullOrBlank() || !refresh.isNullOrBlank())
            .put("hasAccessToken", !access.isNullOrBlank())
            .put("expiresAt", expiresAt)
            .put("redirectUri", REDIRECT_URI)
            .put("appRedirect", APP_REDIRECT)
            .put("lastMessage", lastAuthMessage ?: JSONObject.NULL)
            .toString()
    }

    fun startLogin(ctx: Context): String = startLogin(ctx, reauthorize = false)

    /**
     * Start OAuth even when already connected — required when scopes grow
     * (e.g. Liked Songs / user-library-modify). [show_dialog]=true forces Spotify’s
     * consent UI so new permissions can be granted without logging out first.
     */
    fun reauthorize(ctx: Context): String = startLogin(ctx, reauthorize = true)

    fun startLogin(ctx: Context, reauthorize: Boolean): String {
        val clientId = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_CLIENT_ID)
        if (clientId.isNullOrBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "missing_spotify_client_id")
                .put(
                    "hint",
                    "Add Spotify Client ID in Settings → API keys (or Spotify Account tab).",
                )
                .toString()
        }
        val verifier = randomUrlSafe(64)
        val challenge = sha256Base64Url(verifier)
        val state = randomUrlSafe(16)
        savePending(ctx, Pending(verifier, state))
        lastAuthMessage = if (reauthorize || isLoggedIn(ctx)) {
            "Re-authorize Spotify — approve all permissions (incl. Liked Songs)…"
        } else {
            "Opening Spotify login…"
        }

        val uri = AUTH_URL.toUri().buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            // Always show consent so new scopes are granted (not a silent refresh).
            .appendQueryParameter("show_dialog", "true")
            .build()

        return try {
            // Prefer external browser / Custom Tabs over in-app WebView handlers.
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            ctx.startActivity(intent)
            lastAuthMessage =
                if (reauthorize || isLoggedIn(ctx)) {
                    "Browser opened — approve the updated permissions, then return here."
                } else {
                    "Browser opened for Spotify. After you approve, tap Open GrokifyOS if asked."
                }
            JSONObject()
                .put("ok", true)
                .put("status", "opened")
                .put("reauthorize", reauthorize || isLoggedIn(ctx))
                .put("redirectUri", REDIRECT_URI)
                .toString()
        } catch (e: Exception) {
            clearPending(ctx)
            lastAuthMessage = e.message
            JSONObject().put("ok", false).put("error", e.message ?: "open_failed").toString()
        }
    }

    fun logout(ctx: Context): String {
        HostApiKeyStore.remove(ctx, ApiKeyIds.SPOTIFY_ACCESS_TOKEN)
        HostApiKeyStore.remove(ctx, ApiKeyIds.SPOTIFY_REFRESH_TOKEN)
        HostApiKeyStore.remove(ctx, ApiKeyIds.SPOTIFY_TOKEN_EXPIRES_AT)
        lastAuthMessage = "Logged out of Spotify"
        return JSONObject().put("ok", true).toString()
    }

    /** True for grokifyos://spotify-callback or https://…/spotify-callback.php App Links. */
    fun isSpotifyCallbackUri(data: android.net.Uri?): Boolean {
        if (data == null) return false
        if (data.scheme == "grokifyos" && data.host == "spotify-callback") return true
        if (data.scheme == "https" || data.scheme == "http") {
            val path = data.path.orEmpty()
            if (path == "/spotify-callback.php" || path.endsWith("/spotify-callback.php")) {
                // Accept our host; also tolerate IP/dev hosts that still hit the same path.
                return true
            }
        }
        return false
    }

    /**
     * Handle deep link grokifyos://spotify-callback?code=…&state=…
     * or App Link https://…/spotify-callback.php?code=…&state=…
     * @return true if this intent was a Spotify callback
     */
    fun handleRedirect(ctx: Context, intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (!isSpotifyCallbackUri(data)) return false

        val err = data.getQueryParameter("error")
        if (!err.isNullOrBlank()) {
            val desc = data.getQueryParameter("error_description").orEmpty()
            lastAuthMessage = if (desc.isNotBlank()) "Spotify: $err — $desc" else "Spotify auth error: $err"
            clearPending(ctx)
            consumeIntent(intent)
            Log.w(TAG, "auth error $err $desc")
            return true
        }
        val code = data.getQueryParameter("code").orEmpty()
        val state = data.getQueryParameter("state").orEmpty()

        // Same deep link often hits onNewIntent + onResume (or onCreate + onResume).
        // Authorization codes are single-use — never exchange twice.
        if (code.isNotBlank() && code == lastHandledCode(ctx)) {
            Log.d(TAG, "ignoring already-handled Spotify callback")
            if (isLoggedIn(ctx) && lastAuthMessage.isNullOrBlank()) {
                lastAuthMessage = "Spotify connected"
            }
            consumeIntent(intent)
            return true
        }

        if (code.isBlank()) {
            // App Link can open the bounce URL without query (user bookmarked / refreshed).
            lastAuthMessage = "Spotify callback missing authorization code — tap Connect Spotify again."
            consumeIntent(intent)
            return true
        }

        val p = loadPending(ctx)
        when {
            p == null -> {
                lastAuthMessage =
                    "Login session expired (app was closed during Spotify login). Tap Connect Spotify again."
                Log.w(TAG, "callback with no pending PKCE (process death or never started)")
                consumeIntent(intent)
                return true
            }
            System.currentTimeMillis() - p.startedAt > PENDING_TTL_MS -> {
                lastAuthMessage =
                    "Login session timed out. Tap Connect Spotify again."
                clearPending(ctx)
                Log.w(TAG, "pending PKCE older than TTL")
                consumeIntent(intent)
                return true
            }
            state.isBlank() || state != p.state -> {
                lastAuthMessage =
                    "Spotify state mismatch (stale callback). Tap Connect Spotify again."
                clearPending(ctx)
                Log.w(TAG, "state mismatch expected=${p.state.take(6)}… got=${state.take(6)}…")
                consumeIntent(intent)
                return true
            }
        }

        // Claim pending before network so a concurrent resume cannot double-exchange.
        val verifier = p!!.codeVerifier
        clearPending(ctx)
        markHandledCode(ctx, code)
        consumeIntent(intent)

        val clientId = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_CLIENT_ID).orEmpty()
        if (clientId.isBlank()) {
            lastAuthMessage = "Spotify Client ID missing — re-add it in Settings, then Connect again."
            return true
        }
        val clientSecret = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_CLIENT_SECRET)
        lastAuthMessage = "Finishing Spotify login…"
        // Token exchange on a background thread — never block the UI thread (ANR / frozen return).
        Thread({
            val exchange = exchangeCode(ctx, clientId, clientSecret, code, verifier)
            lastAuthMessage = if (exchange.first) {
                "Spotify connected"
            } else {
                exchange.second ?: "Token exchange failed"
            }
            Log.i(TAG, "token exchange ok=${exchange.first} msg=$lastAuthMessage")
        }, "spotify-token-exchange").start()
        return true
    }

    fun ensureAccessToken(ctx: Context): String? {
        val expiresAt = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_TOKEN_EXPIRES_AT)
            ?.toLongOrNull() ?: 0L
        val access = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_ACCESS_TOKEN)
        val skewMs = 60_000L
        if (!access.isNullOrBlank() && System.currentTimeMillis() < expiresAt - skewMs) {
            return access
        }
        val refresh = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_REFRESH_TOKEN)
        if (refresh.isNullOrBlank()) return access // may still work
        return if (refreshAccessToken(ctx, refresh)) {
            HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_ACCESS_TOKEN)
        } else {
            access
        }
    }

    /**
     * Authenticated Spotify Web API call.
     * @param path e.g. "/v1/me/player/recently-played?limit=20"
     *
     * Honors the process-wide rate-limit cool-down: while cooling, returns a
     * synthetic 429 without hitting the network so UI/widget/DJ stop digging.
     */
    fun api(ctx: Context, method: String, path: String, body: String?): String {
        val m = method.trim().uppercase().ifBlank { "GET" }
        // Block everything during cool-down — play/pause retries also burn the quota.
        if (isRateLimited()) {
            return rateLimitedEnvelope()
        }
        val token = ensureAccessToken(ctx)
        if (token.isNullOrBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("status", 401)
                .put("error", "not_logged_in")
                .put("body", "")
                .toString()
        }
        val p = if (path.startsWith("http")) path else {
            val rel = if (path.startsWith("/")) path else "/$path"
            "https://api.spotify.com$rel"
        }
        if (!HostHttpProxy.isAllowed(p) || !p.startsWith("https://api.spotify.com")) {
            return JSONObject()
                .put("ok", false)
                .put("status", 0)
                .put("error", "path_not_allowed")
                .put("body", "")
                .toString()
        }
        val headers = JSONObject().put("Authorization", "Bearer $token")
        // Spotify player endpoints (pause/play/next) often need an empty JSON object body
        // for PUT/POST so Content-Type is application/json, not a raw empty entity.
        val effectiveBody = when {
            !body.isNullOrBlank() -> body
            m == "PUT" || m == "POST" || m == "PATCH" -> "{}"
            else -> body
        }
        if (!effectiveBody.isNullOrBlank()) {
            headers.put("Content-Type", "application/json")
        }
        val raw = HostHttpProxy.request(m, p, headers.toString(), effectiveBody)
        noteRateLimitFromEnvelope(raw)
        return raw
    }

    /**
     * PKCE token exchange. Prefer pure PKCE (no client_secret).
     * Falls back to secret only if pure-PKCE is rejected as confidential-client.
     */
    private fun exchangeCode(
        ctx: Context,
        clientId: String,
        clientSecret: String?,
        code: String,
        verifier: String,
    ): Pair<Boolean, String?> {
        val pure = postTokenExchange(clientId, null, code, verifier)
        if (pure.first) {
            persistTokens(ctx, pure.third!!)
            return true to null
        }
        // Retry once with secret if present and Spotify complained about client auth.
        val err = pure.second.orEmpty()
        val wantSecret = !clientSecret.isNullOrBlank() &&
            (err.contains("client", ignoreCase = true) ||
                err.contains("invalid_client", ignoreCase = true) ||
                err.contains("unauthorized", ignoreCase = true))
        if (wantSecret) {
            val withSecret = postTokenExchange(clientId, clientSecret, code, verifier)
            if (withSecret.first) {
                persistTokens(ctx, withSecret.third!!)
                return true to null
            }
            Log.w(TAG, "token exchange failed with secret: ${withSecret.second}")
            return false to (withSecret.second ?: pure.second)
        }
        Log.w(TAG, "token exchange failed: $err")
        return false to pure.second
    }

    /** @return Triple(ok, errorMessage?, jsonBody?) */
    private fun postTokenExchange(
        clientId: String,
        clientSecret: String?,
        code: String,
        verifier: String,
    ): Triple<Boolean, String?, JSONObject?> {
        return try {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
            if (!clientSecret.isNullOrBlank()) {
                form.add("client_secret", clientSecret)
            }
            val req = Request.Builder()
                .url(TOKEN_URL)
                .post(form.build())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "token exchange ${resp.code} secret=${!clientSecret.isNullOrBlank()}: $body")
                    val msg = parseOAuthError(body)
                        ?: "Token exchange failed (HTTP ${resp.code})"
                    return Triple(false, msg, null)
                }
                Triple(true, null, JSONObject(body))
            }
        } catch (e: Exception) {
            Log.e(TAG, "exchange failed", e)
            Triple(false, e.message ?: "exchange_failed", null)
        }
    }

    private fun parseOAuthError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val j = JSONObject(body)
            val err = j.optString("error", "")
            val desc = j.optString("error_description", "")
            when {
                err.isNotBlank() && desc.isNotBlank() -> "$err: $desc"
                desc.isNotBlank() -> desc
                err.isNotBlank() -> err
                else -> body.take(180)
            }
        } catch (_: Exception) {
            body.take(180)
        }
    }

    /**
     * Open a Spotify content URI (spotify:track:… or open.spotify.com/…) in the Spotify app.
     * Pass `spotify:open` (or blank) to just launch the Spotify app.
     * Fallback when Web API play has no active device or returns invalid-uri style errors.
     */
    fun openContentUri(ctx: Context, raw: String?): String {
        val s = raw?.trim().orEmpty()
        val launchOnly = s.isEmpty() ||
            s.equals("spotify:open", ignoreCase = true) ||
            s.equals("open", ignoreCase = true)
        val uri = if (launchOnly) "spotify:" else normalizeContentUri(raw)
        if (uri.isNullOrBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "invalid_uri")
                .put("hint", "Expected spotify:track:… or open.spotify.com/…")
                .toString()
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Prefer full Spotify if installed; ignore failures and let the system resolve.
                setPackage("com.spotify.music")
            }
            try {
                ctx.startActivity(intent)
            } catch (_: Exception) {
                // Launch main activity if deep link fails
                try {
                    val launch = ctx.packageManager.getLaunchIntentForPackage("com.spotify.music")
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(launch)
                    } else {
                        intent.setPackage(null)
                        ctx.startActivity(intent)
                    }
                } catch (e2: Exception) {
                    intent.setPackage(null)
                    ctx.startActivity(intent)
                }
            }
            JSONObject().put("ok", true).put("uri", uri).toString()
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: "open_failed")
                .put("uri", uri)
                .toString()
        }
    }

    /** Convert open.spotify.com links / clean spotify: URIs; null if unusable. */
    fun normalizeContentUri(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val open = Regex(
            """https?://open\.spotify\.com/(track|album|playlist|episode|show)/([a-zA-Z0-9]+)""",
            RegexOption.IGNORE_CASE,
        ).find(s)
        if (open != null) {
            return "spotify:${open.groupValues[1].lowercase()}:${open.groupValues[2]}"
        }
        val sp = Regex(
            """^spotify:(track|album|playlist|episode|show):([a-zA-Z0-9]+)$""",
            RegexOption.IGNORE_CASE,
        ).find(s)
        if (sp != null) {
            return "spotify:${sp.groupValues[1].lowercase()}:${sp.groupValues[2]}"
        }
        return null
    }

    private fun refreshAccessToken(ctx: Context, refreshToken: String): Boolean {
        val clientId = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_CLIENT_ID) ?: return false
        val clientSecret = HostApiKeyStore.getValue(ctx, ApiKeyIds.SPOTIFY_CLIENT_SECRET)
        // PKCE refresh first; secret only as fallback.
        if (postRefresh(ctx, clientId, null, refreshToken)) return true
        if (!clientSecret.isNullOrBlank()) {
            return postRefresh(ctx, clientId, clientSecret, refreshToken)
        }
        return false
    }

    private fun postRefresh(
        ctx: Context,
        clientId: String,
        clientSecret: String?,
        refreshToken: String,
    ): Boolean {
        return try {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
            if (!clientSecret.isNullOrBlank()) {
                form.add("client_secret", clientSecret)
            }
            val req = Request.Builder()
                .url(TOKEN_URL)
                .post(form.build())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "refresh ${resp.code} secret=${!clientSecret.isNullOrBlank()}: $body")
                    return false
                }
                val json = JSONObject(body)
                // Spotify may omit refresh_token on refresh
                if (!json.has("refresh_token")) {
                    json.put("refresh_token", refreshToken)
                }
                persistTokens(ctx, json)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "refresh failed", e)
            false
        }
    }

    private fun persistTokens(ctx: Context, json: JSONObject) {
        val access = json.optString("access_token", "")
        val refresh = json.optString("refresh_token", "")
        val expiresIn = json.optLong("expires_in", 3600L)
        if (access.isNotBlank()) {
            HostApiKeyStore.save(ctx, ApiKeyIds.SPOTIFY_ACCESS_TOKEN, access, "Spotify access token", "OAuth")
            HostApiKeyStore.save(
                ctx,
                ApiKeyIds.SPOTIFY_TOKEN_EXPIRES_AT,
                (System.currentTimeMillis() + expiresIn * 1000L).toString(),
                "Spotify token expiry",
                "epoch ms",
            )
        }
        if (refresh.isNotBlank()) {
            HostApiKeyStore.save(ctx, ApiKeyIds.SPOTIFY_REFRESH_TOKEN, refresh, "Spotify refresh token", "OAuth")
        }
    }

    private fun savePending(ctx: Context, p: Pending) {
        pending.set(p)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VERIFIER, p.codeVerifier)
            .putString(KEY_STATE, p.state)
            .putLong(KEY_STARTED_AT, p.startedAt)
            .apply()
    }

    private fun loadPending(ctx: Context): Pending? {
        pending.get()?.let { return it }
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        val state = prefs.getString(KEY_STATE, null)
        val started = prefs.getLong(KEY_STARTED_AT, 0L)
        if (verifier.isNullOrBlank() || state.isNullOrBlank()) return null
        val p = Pending(verifier, state, if (started > 0L) started else System.currentTimeMillis())
        pending.set(p)
        return p
    }

    private fun clearPending(ctx: Context) {
        pending.set(null)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_VERIFIER)
            .remove(KEY_STATE)
            .remove(KEY_STARTED_AT)
            .apply()
    }

    private fun markHandledCode(ctx: Context, code: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HANDLED_CODE, code)
            .apply()
    }

    private fun lastHandledCode(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HANDLED_CODE, null)

    /** Strip deep-link data so later onResume does not re-enter the callback path. */
    private fun consumeIntent(intent: Intent?) {
        try {
            intent?.data = null
        } catch (_: Exception) {
            // Some intent wrappers are immutable; handled-code guard covers that case.
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
