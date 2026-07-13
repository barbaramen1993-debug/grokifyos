package io.grokify.os.apps

import android.content.Context
import android.util.Log
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.SpotifyOAuth
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * AI-assisted playlist research, create, and edit for the native Spotify host module.
 * Text uses host Grok Build ([HostAiClient.complete]); Spotify mutations via [SpotifyOAuth.api].
 */
object SpotifyPlaylistAi {
    private const val TAG = "SpotifyPlaylistAi"

    data class TrackProposal(
        val query: String,
        val reason: String = "",
    )

    data class ResearchResult(
        val title: String,
        val description: String,
        val rationale: String,
        val banter: String,
        val tracks: List<TrackProposal>,
        val rawText: String = "",
    )

    data class PlaylistRef(
        val id: String,
        val name: String,
        val uri: String,
        val trackCount: Int = 0,
        val owner: String = "",
    )

    data class PlaylistTrack(
        val uri: String,
        val name: String,
        val artists: String,
    )

    data class EditPlan(
        val notes: String,
        val removeUris: List<String>,
        val addTracks: List<TrackProposal>,
        val newDescription: String? = null,
        val newName: String? = null,
        val rawText: String = "",
    )

    data class ApiOutcome(
        val ok: Boolean,
        val message: String,
        val playlistId: String? = null,
        val playlistUri: String? = null,
        val trackCount: Int = 0,
    )

    private data class SpotRes(
        val ok: Boolean,
        val status: Int,
        val json: JSONObject?,
        val body: String,
        val error: String?,
    )

    // ── Research (new set) ──────────────────────────────────────────────────

    fun research(ctx: Context, prompt: String, onStep: ((String) -> Unit)? = null): Pair<ResearchResult?, String?> {
        val p = prompt.trim()
        if (p.isEmpty()) return null to "Describe the set you want"
        if (!SpotifyOAuth.isLoggedIn(ctx)) {
            // Research only needs host AI; Spotify login is required for build/edit.
        }
        onStep?.invoke("Running host Grok Build…")
        val system =
            "You are an expert music supervisor and DJ inside GrokifyOS Spotify. " +
                "Reply with ONLY valid JSON (no markdown fences) of shape: " +
                "{\"title\":\"playlist title\",\"description\":\"short desc\"," +
                "\"rationale\":\"2-4 sentences of research/taste notes\"," +
                "\"banter\":\"1-2 short spoken lines a radio DJ would say introducing this set\"," +
                "\"tracks\":[{\"query\":\"spotify search query artist + track\",\"reason\":\"why\"}]} " +
                "Provide 12-18 tracks. Prefer real, searchable track queries. Match the user vibe precisely."
        val opts = JSONObject()
            .put("system", system)
            .put("session_title", "· Spotify DJ Research")
            .toString()
        val raw = HostAiClient.complete(ctx, p, opts)
        val o = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (!o.optBoolean("ok", false)) {
            val err = o.optString("error", "research_failed")
            val hint = o.optString("hint", "")
            return null to listOf(err, hint).filter { it.isNotBlank() }.joinToString(" — ")
        }
        val text = o.optString("text", "").ifBlank { o.optString("content", "") }
        onStep?.invoke("Parsing research…")
        val json = extractJson(text) ?: return null to "Got text but not JSON — try again"
        val tracks = mutableListOf<TrackProposal>()
        val arr = json.optJSONArray("tracks")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val q = t.optString("query", "").trim()
                if (q.isNotBlank()) {
                    tracks += TrackProposal(q, t.optString("reason", ""))
                }
            }
        }
        if (tracks.isEmpty()) return null to "No tracks in research result"
        return ResearchResult(
            title = json.optString("title", "Grokify DJ Set").ifBlank { "Grokify DJ Set" },
            description = json.optString("description", ""),
            rationale = json.optString("rationale", ""),
            banter = json.optString("banter", ""),
            tracks = tracks,
            rawText = text,
        ) to null
    }

    // ── Build playlist from research ────────────────────────────────────────

    fun build(
        ctx: Context,
        research: ResearchResult,
        onStep: ((String) -> Unit)? = null,
    ): ApiOutcome {
        if (!SpotifyOAuth.isLoggedIn(ctx)) {
            return ApiOutcome(false, "Connect Spotify under Account first")
        }
        onStep?.invoke("Loading profile…")
        val me = spotify(ctx, "GET", "/v1/me")
        val userId = me.json?.optString("id", "").orEmpty()
        if (!me.ok || userId.isBlank()) {
            return ApiOutcome(false, me.error ?: "Could not load Spotify profile")
        }
        onStep?.invoke("Creating “${research.title}”…")
        val desc = (research.description.ifBlank { research.rationale } + " · via GrokifyOS AI DJ")
            .take(300)
        val created = spotify(
            ctx,
            "POST",
            "/v1/users/${enc(userId)}/playlists",
            JSONObject()
                .put("name", research.title.take(100))
                .put("description", desc)
                .put("public", false)
                .toString(),
        )
        val pid = created.json?.optString("id", "").orEmpty()
        val puri = created.json?.optString("uri", "").orEmpty()
        if (!created.ok || pid.isBlank()) {
            return ApiOutcome(false, created.error ?: "Create playlist failed")
        }

        onStep?.invoke("Resolving tracks on Spotify…")
        val uris = resolveTrackUris(ctx, research.tracks) { i, total, q ->
            onStep?.invoke("Resolving $i/$total · $q")
        }
        if (uris.isEmpty()) {
            return ApiOutcome(
                ok = true,
                message = "Playlist created but no tracks resolved",
                playlistId = pid,
                playlistUri = puri,
                trackCount = 0,
            )
        }
        onStep?.invoke("Adding ${uris.size} tracks…")
        // Spotify allows up to 100 uris per request
        uris.chunked(100).forEach { chunk ->
            val arr = JSONArray()
            chunk.forEach { arr.put(it) }
            val add = spotify(
                ctx,
                "POST",
                "/v1/playlists/${enc(pid)}/tracks",
                JSONObject().put("uris", arr).toString(),
            )
            if (!add.ok) {
                return ApiOutcome(
                    ok = true,
                    message = "Playlist created; add tracks failed: ${add.error ?: add.status}",
                    playlistId = pid,
                    playlistUri = puri,
                    trackCount = 0,
                )
            }
        }
        return ApiOutcome(
            ok = true,
            message = "Built “${research.title}” with ${uris.size} tracks",
            playlistId = pid,
            playlistUri = puri,
            trackCount = uris.size,
        )
    }

    // ── List / load playlists ───────────────────────────────────────────────

    fun listPlaylists(ctx: Context): Pair<List<PlaylistRef>, String?> {
        if (!SpotifyOAuth.isLoggedIn(ctx)) return emptyList<PlaylistRef>() to "Not logged in"
        val out = mutableListOf<PlaylistRef>()
        var url: String? = "/v1/me/playlists?limit=50"
        var pages = 0
        while (url != null && pages < 4) {
            pages++
            val res = spotify(ctx, "GET", url)
            if (!res.ok) return out to (res.error ?: "Failed to load playlists")
            val items = res.json?.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val p = items.optJSONObject(i) ?: continue
                    val id = p.optString("id", "")
                    if (id.isBlank()) continue
                    out += PlaylistRef(
                        id = id,
                        name = p.optString("name", "Untitled"),
                        uri = p.optString("uri", "spotify:playlist:$id"),
                        trackCount = p.optJSONObject("tracks")?.optInt("total", 0) ?: 0,
                        owner = p.optJSONObject("owner")?.optString("display_name", "")
                            ?: p.optJSONObject("owner")?.optString("id", "")
                            ?: "",
                    )
                }
            }
            val next = res.json?.optString("next", "")?.trim().orEmpty()
            url = if (next.isNotBlank() && next.startsWith("https://api.spotify.com")) {
                next.removePrefix("https://api.spotify.com")
            } else null
        }
        return out to null
    }

    fun loadTracks(ctx: Context, playlistId: String, limit: Int = 80): Pair<List<PlaylistTrack>, String?> {
        if (playlistId.isBlank()) return emptyList<PlaylistTrack>() to "No playlist"
        val out = mutableListOf<PlaylistTrack>()
        var url: String? = "/v1/playlists/${enc(playlistId)}/tracks?limit=50"
        var pages = 0
        while (url != null && pages < 4 && out.size < limit) {
            pages++
            val res = spotify(ctx, "GET", url)
            if (!res.ok) return out to (res.error ?: "Failed to load tracks")
            val items = res.json?.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    if (out.size >= limit) break
                    val wrap = items.optJSONObject(i) ?: continue
                    val t = wrap.optJSONObject("track") ?: continue
                    val uri = t.optString("uri", "")
                    if (uri.isBlank() || t.optBoolean("is_local", false)) continue
                    out += PlaylistTrack(
                        uri = uri,
                        name = t.optString("name", ""),
                        artists = artistsOf(t),
                    )
                }
            }
            val next = res.json?.optString("next", "")?.trim().orEmpty()
            url = if (next.isNotBlank() && next.startsWith("https://api.spotify.com")) {
                next.removePrefix("https://api.spotify.com")
            } else null
        }
        return out to null
    }

    // ── Edit with prompt ────────────────────────────────────────────────────

    fun researchEdit(
        ctx: Context,
        playlist: PlaylistRef,
        tracks: List<PlaylistTrack>,
        prompt: String,
        onStep: ((String) -> Unit)? = null,
    ): Pair<EditPlan?, String?> {
        val p = prompt.trim()
        if (p.isEmpty()) return null to "Describe how to edit this playlist"
        if (tracks.isEmpty()) return null to "Playlist has no tracks to edit"
        onStep?.invoke("Planning edits with Grok Build…")
        val trackLines = tracks.take(60).mapIndexed { i, t ->
            "${i + 1}. ${t.name} — ${t.artists} [${t.uri}]"
        }.joinToString("\n")
        val system =
            "You are a playlist editor for Spotify inside GrokifyOS. " +
                "Reply with ONLY valid JSON (no markdown) of shape: " +
                "{\"notes\":\"what you changed and why\"," +
                "\"remove_uris\":[\"spotify:track:…\"]," +
                "\"add_tracks\":[{\"query\":\"artist + track search\",\"reason\":\"why\"}]," +
                "\"new_description\":\"optional new desc or omit\"," +
                "\"new_name\":\"optional rename or omit\"} " +
                "Only remove_uris that appear in the current track list. " +
                "Add 0-12 new tracks when useful. Prefer real searchable queries. " +
                "If the user only wants adds, leave remove_uris empty. Stay faithful to the edit brief."
        val user =
            "Playlist: ${playlist.name} (${tracks.size} tracks loaded)\n" +
                "Edit request: $p\n\nCurrent tracks:\n$trackLines"
        val opts = JSONObject()
            .put("system", system)
            .put("session_title", "· Spotify DJ Edit")
            .toString()
        val raw = HostAiClient.complete(ctx, user, opts)
        val o = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (!o.optBoolean("ok", false)) {
            val err = o.optString("error", "edit_research_failed")
            val hint = o.optString("hint", "")
            return null to listOf(err, hint).filter { it.isNotBlank() }.joinToString(" — ")
        }
        val text = o.optString("text", "").ifBlank { o.optString("content", "") }
        onStep?.invoke("Parsing edit plan…")
        val json = extractJson(text) ?: return null to "Got text but not JSON — try again"
        val known = tracks.map { it.uri }.toSet()
        val remove = mutableListOf<String>()
        val remArr = json.optJSONArray("remove_uris")
        if (remArr != null) {
            for (i in 0 until remArr.length()) {
                val u = remArr.optString(i, "").trim()
                if (u.isNotBlank() && (u in known || known.any { it.equals(u, true) })) {
                    remove += u
                }
            }
        }
        val add = mutableListOf<TrackProposal>()
        val addArr = json.optJSONArray("add_tracks")
        if (addArr != null) {
            for (i in 0 until addArr.length()) {
                val t = addArr.optJSONObject(i) ?: continue
                val q = t.optString("query", "").trim()
                if (q.isNotBlank()) add += TrackProposal(q, t.optString("reason", ""))
            }
        }
        if (remove.isEmpty() && add.isEmpty()) {
            return null to "Edit plan has no removals or additions — rephrase the prompt"
        }
        val newDesc = json.optString("new_description", "").trim().ifBlank { null }
        val newName = json.optString("new_name", "").trim().ifBlank { null }
        return EditPlan(
            notes = json.optString("notes", ""),
            removeUris = remove.distinct(),
            addTracks = add,
            newDescription = newDesc,
            newName = newName,
            rawText = text,
        ) to null
    }

    fun applyEdit(
        ctx: Context,
        playlistId: String,
        plan: EditPlan,
        onStep: ((String) -> Unit)? = null,
    ): ApiOutcome {
        if (!SpotifyOAuth.isLoggedIn(ctx)) {
            return ApiOutcome(false, "Connect Spotify under Account first")
        }
        if (playlistId.isBlank()) return ApiOutcome(false, "No playlist selected")

        var removed = 0
        var added = 0

        if (plan.removeUris.isNotEmpty()) {
            onStep?.invoke("Removing ${plan.removeUris.size} tracks…")
            plan.removeUris.chunked(100).forEach { chunk ->
                val tracks = JSONArray()
                chunk.forEach { tracks.put(JSONObject().put("uri", it)) }
                val del = spotify(
                    ctx,
                    "DELETE",
                    "/v1/playlists/${enc(playlistId)}/tracks",
                    JSONObject().put("tracks", tracks).toString(),
                )
                if (del.ok) removed += chunk.size
                else {
                    Log.w(TAG, "remove failed: ${del.error} ${del.status}")
                }
            }
        }

        if (plan.addTracks.isNotEmpty()) {
            onStep?.invoke("Resolving new tracks…")
            val uris = resolveTrackUris(ctx, plan.addTracks) { i, total, q ->
                onStep?.invoke("Resolving add $i/$total · $q")
            }
            if (uris.isNotEmpty()) {
                onStep?.invoke("Adding ${uris.size} tracks…")
                uris.chunked(100).forEach { chunk ->
                    val arr = JSONArray()
                    chunk.forEach { arr.put(it) }
                    val add = spotify(
                        ctx,
                        "POST",
                        "/v1/playlists/${enc(playlistId)}/tracks",
                        JSONObject().put("uris", arr).toString(),
                    )
                    if (add.ok) added += chunk.size
                }
            }
        }

        if (!plan.newName.isNullOrBlank() || !plan.newDescription.isNullOrBlank()) {
            onStep?.invoke("Updating playlist details…")
            val body = JSONObject()
            if (!plan.newName.isNullOrBlank()) body.put("name", plan.newName.take(100))
            if (!plan.newDescription.isNullOrBlank()) {
                body.put("description", plan.newDescription.take(300))
            }
            spotify(ctx, "PUT", "/v1/playlists/${enc(playlistId)}", body.toString())
        }

        if (removed == 0 && added == 0 && plan.newName.isNullOrBlank() && plan.newDescription.isNullOrBlank()) {
            return ApiOutcome(false, "No changes applied (tracks may not have resolved)")
        }
        return ApiOutcome(
            ok = true,
            message = "Edited playlist: −$removed · +$added" +
                if (!plan.notes.isBlank()) " — ${plan.notes.take(120)}" else "",
            playlistId = playlistId,
            trackCount = added,
        )
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun resolveTrackUris(
        ctx: Context,
        proposals: List<TrackProposal>,
        onEach: (index: Int, total: Int, query: String) -> Unit,
    ): List<String> {
        val uris = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        proposals.forEachIndexed { idx, t ->
            val q = t.query.trim()
            if (q.isBlank()) return@forEachIndexed
            onEach(idx + 1, proposals.size, q)
            val s = spotify(
                ctx,
                "GET",
                "/v1/search?type=track&limit=1&q=${enc(q)}",
            )
            val item = s.json
                ?.optJSONObject("tracks")
                ?.optJSONArray("items")
                ?.optJSONObject(0)
            val uri = item?.optString("uri", "").orEmpty()
            if (uri.isNotBlank() && seen.add(uri)) uris += uri
        }
        return uris
    }

    private fun artistsOf(track: JSONObject): String {
        val arr = track.optJSONArray("artists") ?: return ""
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name", "") ?: ""
            if (n.isNotBlank()) names += n
        }
        return names.joinToString(", ")
    }

    private fun extractJson(text: String): JSONObject? {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        runCatching { return JSONObject(cleaned) }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun spotify(ctx: Context, method: String, path: String, body: String? = null): SpotRes {
        val raw = SpotifyOAuth.api(ctx, method, path, body)
        return try {
            val o = JSONObject(raw)
            val status = o.optInt("status", 0)
            val ok = o.optBoolean("ok", false) || status in listOf(200, 201, 202, 204)
            val bodyStr = o.optString("body", "")
            val json = when {
                bodyStr.isBlank() -> null
                bodyStr.trimStart().startsWith("[") -> JSONObject().put("_array", JSONArray(bodyStr))
                else -> runCatching { JSONObject(bodyStr) }.getOrNull()
            }
            val err = if (o.isNull("error")) null else o.optString("error").ifBlank { null }
            SpotRes(ok = ok, status = status, json = json, body = bodyStr, error = err)
        } catch (e: Exception) {
            SpotRes(false, 0, null, "", e.message)
        }
    }
}
