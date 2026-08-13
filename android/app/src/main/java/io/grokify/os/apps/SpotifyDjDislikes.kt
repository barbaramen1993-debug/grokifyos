package io.grokify.os.apps

import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable Live DJ blocks (song / artist / tired) plus matching helpers.
 *
 * Shared process memory so the booth service and UI see the same counts /
 * timestamps. Matching is deliberately looser than raw URI equality: remasters,
 * "Artist & Guest" vs "Artist, Guest", and leading "The " should still hit.
 */

data class DjBlockedTrack(
    val uri: String,
    val name: String = "",
    val artists: String = "",
    val reasons: Set<String> = setOf(DjDislikeReason.SONG),
    val count: Int = 1,
    val firstTs: Long = 0L,
    val lastTs: Long = 0L,
    val titleKey: String = "",
)

data class DjBlockedArtist(
    val key: String,
    val name: String,
    val count: Int = 1,
    val firstTs: Long = 0L,
    val lastTs: Long = 0L,
    val aliases: Set<String> = emptySet(),
)

data class DjTiredTrack(
    val uri: String,
    val name: String = "",
    val artists: String = "",
    val until: Long,
    val count: Int = 1,
    val lastTs: Long = 0L,
    val titleKey: String = "",
)

internal object DjDislikeMemory {
    val lock = Any()
    val tracks = LinkedHashMap<String, DjBlockedTrack>()
    val artists = LinkedHashMap<String, DjBlockedArtist>()
    val tired = LinkedHashMap<String, DjTiredTrack>()
    @Volatile var loaded = false
}

fun djNormalizeArtistName(raw: String): String {
    var s = raw.lowercase().trim()
    if (s.isEmpty()) return ""
    s = s.replace(Regex("[“”\"'`]"), "")
    s = s.replace(Regex("\\s+"), " ").trim()
    if (s.startsWith("the ") && s.length > 6) {
        s = s.removePrefix("the ").trim()
    }
    return s
}

fun djArtistNameTokens(artists: String): List<String> {
    if (artists.isBlank()) return emptyList()
    return artists
        .split(
            Regex(
                "\\s*(?:,|&|/|\\+|\\sx\\s|\\sfeat\\.?\\s|\\sft\\.?\\s|\\sfeaturing\\s|\\swith\\s|\\sand\\s)\\s*",
                RegexOption.IGNORE_CASE,
            ),
        )
        .map { djNormalizeArtistName(it) }
        .filter { it.length >= 2 }
        .distinct()
}

fun djCleanTrackTitle(name: String): String {
    var t = name.lowercase().trim()
    if (t.isEmpty()) return ""
    t = t.replace(
        Regex(
            """\s*[\(\[]\s*(?:feat\.?|featuring|with|ft\.?).+?[\)\]]""",
            RegexOption.IGNORE_CASE,
        ),
        "",
    )
    t = t.replace(
        Regex(
            """\s*[\(\[]\s*(?:remaster(?:ed)?(?:\s+\d{4})?|radio edit|album version|""" +
                """single version|explicit|clean version|deluxe(?: edition)?|""" +
                """live|acoustic|remix|bonus track)\s*[\)\]]""",
            RegexOption.IGNORE_CASE,
        ),
        "",
    )
    t = t.replace(Regex("[^a-z0-9]+"), " ").trim()
    return t.replace(Regex("\\s+"), " ").trim()
}

fun djTrackTitleKey(name: String, artists: String): String {
    val title = djCleanTrackTitle(name)
    if (title.isBlank()) return ""
    val artist = djArtistNameTokens(artists).firstOrNull().orEmpty()
    return if (artist.isBlank()) title else "$title|$artist"
}

fun djSpotifyArtistId(raw: String?): String? {
    val u = raw?.trim().orEmpty()
    if (u.isBlank()) return null
    val id = when {
        u.startsWith("spotify:artist:") -> u.removePrefix("spotify:artist:")
        u.contains("/artist/") -> u.substringAfterLast('/').substringBefore('?')
        !u.contains(':') && !u.contains('/') && u.length in 8..32 -> u
        else -> ""
    }.trim()
    return id.takeIf { it.isNotBlank() && !it.contains(':') && !it.contains('/') }
}

fun djSpotifyTrackId(raw: String?): String? {
    val u = raw?.trim().orEmpty()
    if (u.isBlank()) return null
    val id = when {
        u.startsWith("spotify:track:") -> u.removePrefix("spotify:track:").substringBefore('?')
        u.contains("open.spotify.com/track/") ->
            u.substringAfter("open.spotify.com/track/").substringBefore('?').substringBefore('/')
        u.matches(Regex("^[A-Za-z0-9]{22}$")) -> u
        else -> ""
    }.trim()
    return id.takeIf { it.isNotBlank() && !it.contains(':') && !it.contains('/') }
}

fun djLooksLikeSpotifyId(raw: String): Boolean =
    raw.trim().matches(Regex("^[A-Za-z0-9]{22}$"))

/** True when [raw] is a real song/artist label, not a URI or Spotify id. */
fun djIsUsableLabel(raw: String): Boolean {
    val s = raw.trim()
    if (s.isEmpty()) return false
    if (s.startsWith("spotify:", ignoreCase = true)) return false
    if (s.contains("open.spotify.com", ignoreCase = true)) return false
    if (s.startsWith("title:") || s.startsWith("name:")) return false
    if (djLooksLikeSpotifyId(s)) return false
    return true
}

fun djTitleCaseWords(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    return s.split(Regex("\\s+")).joinToString(" ") { part ->
        if (part.isEmpty()) part
        else part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
    }
}

fun djFormatTitleKey(titleKey: String): String {
    val raw = titleKey.trim()
    if (raw.isEmpty()) return ""
    val title = djTitleCaseWords(raw.substringBefore('|'))
    val artist = raw.substringAfter('|', "").trim()
    return if (artist.isBlank()) title else "$title — ${djTitleCaseWords(artist)}"
}

fun djBlockedTrackTitle(track: DjBlockedTrack): String {
    if (djIsUsableLabel(track.name)) return track.name.trim()
    if (track.titleKey.isNotBlank()) {
        val pretty = djFormatTitleKey(track.titleKey)
        if (pretty.isNotBlank()) return pretty.substringBefore(" — ").ifBlank { pretty }
    }
    return "Unknown song"
}

fun djBlockedTrackArtists(track: DjBlockedTrack): String {
    if (djIsUsableLabel(track.artists)) return track.artists.trim()
    val fromKey = track.titleKey.substringAfter('|', "").trim()
    return if (fromKey.isNotBlank()) djTitleCaseWords(fromKey) else ""
}

fun djBlockedTrackNeedsLabel(track: DjBlockedTrack): Boolean =
    !djIsUsableLabel(track.name) || !djIsUsableLabel(track.artists)

fun djBlockedArtistTitle(artist: DjBlockedArtist): String {
    if (djIsUsableLabel(artist.name)) return artist.name.trim()
    artist.aliases.firstOrNull { djIsUsableLabel(it) && !djLooksLikeSpotifyId(it) }?.let {
        return djTitleCaseWords(it)
    }
    if (artist.key.startsWith("name:")) {
        val fromKey = artist.key.removePrefix("name:").trim()
        if (fromKey.isNotBlank()) return djTitleCaseWords(fromKey)
    }
    return "Unknown artist"
}

fun djBlockedArtistNeedsLabel(artist: DjBlockedArtist): Boolean =
    !djIsUsableLabel(artist.name)

fun djTiredTrackTitle(track: DjTiredTrack): String {
    if (djIsUsableLabel(track.name)) return track.name.trim()
    if (track.titleKey.isNotBlank()) {
        val pretty = djFormatTitleKey(track.titleKey)
        if (pretty.isNotBlank()) return pretty.substringBefore(" — ").ifBlank { pretty }
    }
    return "Unknown song"
}

fun djTiredTrackArtists(track: DjTiredTrack): String {
    if (djIsUsableLabel(track.artists)) return track.artists.trim()
    val fromKey = track.titleKey.substringAfter('|', "").trim()
    return if (fromKey.isNotBlank()) djTitleCaseWords(fromKey) else ""
}

fun djTiredTrackNeedsLabel(track: DjTiredTrack): Boolean =
    !djIsUsableLabel(track.name) || !djIsUsableLabel(track.artists)

fun djArtistsJoinedFromJson(track: JSONObject): String {
    val arr = track.optJSONArray("artists") ?: return ""
    val names = ArrayList<String>()
    for (i in 0 until arr.length()) {
        val n = arr.optJSONObject(i)?.optString("name", "").orEmpty().trim()
        if (n.isNotBlank()) names.add(n)
    }
    return names.joinToString(", ")
}

/** Parse Spotify `GET /v1/tracks?ids=` body → uri, name, artists. */
fun djParseSpotifyTracksObject(body: JSONObject): List<Triple<String, String, String>> {
    val arr = body.optJSONArray("tracks") ?: return emptyList()
    val out = ArrayList<Triple<String, String, String>>()
    for (i in 0 until arr.length()) {
        val t = arr.optJSONObject(i) ?: continue
        val id = t.optString("id", "").trim()
        val uri = t.optString("uri", "").ifBlank {
            if (id.isNotBlank()) "spotify:track:$id" else ""
        }
        val name = t.optString("name", "")
        val artists = djArtistsJoinedFromJson(t)
        if (uri.isBlank() || !djIsUsableLabel(name)) continue
        out.add(Triple(uri, name, artists))
    }
    return out
}

/** Parse Spotify `GET /v1/artists?ids=` body → id, name. */
fun djParseSpotifyArtistsObject(body: JSONObject): List<Pair<String, String>> {
    val arr = body.optJSONArray("artists") ?: return emptyList()
    val out = ArrayList<Pair<String, String>>()
    for (i in 0 until arr.length()) {
        val a = arr.optJSONObject(i) ?: continue
        val id = a.optString("id", "").trim()
        val name = a.optString("name", "").trim()
        if (id.isBlank() || !djIsUsableLabel(name)) continue
        out.add(id to name)
    }
    return out
}

fun djTiredRemainingLabel(untilMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val left = untilMs - nowMs
    if (left <= 0L) return "expired"
    val dayMs = 24L * 60 * 60 * 1000
    val hourMs = 60L * 60 * 1000
    val days = left / dayMs
    val hours = (left % dayMs) / hourMs
    return when {
        days > 0L -> "${days}d ${hours}h left"
        hours > 0L -> "${hours}h left"
        else -> "<1h left"
    }
}

fun encodeBlockedTracks(list: Collection<DjBlockedTrack>): String {
    val arr = JSONArray()
    list.forEach { t ->
        if (t.uri.isBlank()) return@forEach
        val reasons = JSONArray()
        t.reasons.forEach { reasons.put(it) }
        arr.put(
            JSONObject()
                .put("uri", t.uri)
                .put("name", t.name)
                .put("artists", t.artists)
                .put("reasons", reasons)
                .put("count", t.count)
                .put("firstTs", t.firstTs)
                .put("lastTs", t.lastTs)
                .put("ts", t.lastTs)
                .put("titleKey", t.titleKey),
        )
    }
    return arr.toString()
}

fun decodeBlockedTracks(raw: String?): List<DjBlockedTrack> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uri = o.optString("uri", "").trim()
                if (uri.isBlank()) continue
                val reasons = LinkedHashSet<String>()
                val r = o.optJSONArray("reasons")
                if (r != null) {
                    for (j in 0 until r.length()) {
                        val s = r.optString(j, "").trim()
                        if (s.isNotBlank()) reasons.add(s)
                    }
                }
                if (reasons.isEmpty()) reasons.add(DjDislikeReason.SONG)
                val nameRaw = o.optString("name", "")
                val artistsRaw = o.optString("artists", "")
                val name = if (djIsUsableLabel(nameRaw)) nameRaw.trim() else ""
                val artists = if (djIsUsableLabel(artistsRaw)) artistsRaw.trim() else ""
                val last = o.optLong("lastTs", o.optLong("ts", System.currentTimeMillis()))
                val first = o.optLong("firstTs", last)
                add(
                    DjBlockedTrack(
                        uri = uri,
                        name = name,
                        artists = artists,
                        reasons = reasons,
                        count = o.optInt("count", 1).coerceAtLeast(1),
                        firstTs = first,
                        lastTs = last,
                        titleKey = o.optString("titleKey", "").ifBlank {
                            djTrackTitleKey(name, artists)
                        },
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }
}

fun encodeBlockedArtists(list: Collection<DjBlockedArtist>): String {
    val arr = JSONArray()
    list.forEach { a ->
        if (a.key.isBlank()) return@forEach
        val aliases = JSONArray()
        a.aliases.forEach { aliases.put(it) }
        val id = if (a.key.startsWith("name:")) "" else a.key
        arr.put(
            JSONObject()
                .put("id", id)
                .put("key", a.key)
                .put("name", a.name)
                .put("count", a.count)
                .put("firstTs", a.firstTs)
                .put("lastTs", a.lastTs)
                .put("aliases", aliases),
        )
    }
    return arr.toString()
}

fun decodeBlockedArtists(raw: String?): List<DjBlockedArtist> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val nameRaw = o.optString("name", "")
                val id = o.optString("id", "").trim()
                val key = o.optString("key", "").ifBlank {
                    id.ifBlank {
                        nameRaw.trim().lowercase().takeIf { it.isNotBlank() && djIsUsableLabel(it) }
                            ?.let { "name:$it" }.orEmpty()
                    }
                }
                if (key.isBlank()) continue
                val aliases = LinkedHashSet<String>()
                val al = o.optJSONArray("aliases")
                if (al != null) {
                    for (j in 0 until al.length()) {
                        val s = al.optString(j, "").trim()
                        if (s.isNotBlank()) aliases.add(s)
                    }
                }
                val last = o.optLong("lastTs", o.optLong("ts", System.currentTimeMillis()))
                add(
                    DjBlockedArtist(
                        key = key,
                        name = when {
                            djIsUsableLabel(nameRaw) -> nameRaw.trim()
                            key.startsWith("name:") ->
                                key.removePrefix("name:").trim().takeIf { djIsUsableLabel(it) }.orEmpty()
                            else -> ""
                        },
                        count = o.optInt("count", 1).coerceAtLeast(1),
                        firstTs = o.optLong("firstTs", last),
                        lastTs = last,
                        aliases = aliases,
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }
}

fun encodeTiredTracks(list: Collection<DjTiredTrack>, nowMs: Long = System.currentTimeMillis()): String {
    val arr = JSONArray()
    list.filter { it.uri.isNotBlank() && it.until > nowMs }.forEach { t ->
        arr.put(
            JSONObject()
                .put("uri", t.uri)
                .put("name", t.name)
                .put("artists", t.artists)
                .put("until", t.until)
                .put("count", t.count)
                .put("lastTs", t.lastTs)
                .put("titleKey", t.titleKey),
        )
    }
    return arr.toString()
}

fun decodeTiredTracks(raw: String?, nowMs: Long = System.currentTimeMillis()): List<DjTiredTrack> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uri = o.optString("uri", "").trim()
                val until = o.optLong("until", 0L)
                if (uri.isBlank() || until <= nowMs) continue
                val nameRaw = o.optString("name", "")
                val artistsRaw = o.optString("artists", "")
                val name = if (djIsUsableLabel(nameRaw)) nameRaw.trim() else ""
                val artists = if (djIsUsableLabel(artistsRaw)) artistsRaw.trim() else ""
                add(
                    DjTiredTrack(
                        uri = uri,
                        name = name,
                        artists = artists,
                        until = until,
                        count = o.optInt("count", 1).coerceAtLeast(1),
                        lastTs = o.optLong("lastTs", nowMs),
                        titleKey = o.optString("titleKey", "").ifBlank {
                            djTrackTitleKey(name, artists)
                        },
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }
}

fun djArtistIsBlocked(
    artistIds: List<String>,
    artists: String,
    artistUri: String,
    blocked: Map<String, DjBlockedArtist>,
): Boolean {
    if (blocked.isEmpty()) return false
    djSpotifyArtistId(artistUri)?.let { if (blocked.containsKey(it)) return true }
    if (artistIds.any { id ->
            val clean = djSpotifyArtistId(id) ?: id.trim()
            clean.isNotBlank() && blocked.containsKey(clean)
        }
    ) {
        return true
    }
    val tokens = djArtistNameTokens(artists)
    if (tokens.isEmpty()) return false
    return blocked.values.any { a ->
        val names = buildSet {
            add(djNormalizeArtistName(a.name))
            if (a.key.startsWith("name:")) add(djNormalizeArtistName(a.key.removePrefix("name:")))
            a.aliases.forEach { add(djNormalizeArtistName(it)) }
        }.filter { it.length >= 2 }
        tokens.any { tok -> names.any { n -> n == tok } }
    }
}

fun djTrackIsBlocked(
    uri: String,
    name: String,
    artists: String,
    blocked: Map<String, DjBlockedTrack>,
): Boolean {
    val u = uri.trim()
    if (u.isNotBlank() && blocked.containsKey(u)) return true
    val key = djTrackTitleKey(name, artists)
    if (key.isBlank()) return false
    return blocked.values.any { it.titleKey.isNotBlank() && it.titleKey == key }
}

fun djTrackIsTired(
    uri: String,
    name: String,
    artists: String,
    tired: Map<String, DjTiredTrack>,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    val u = uri.trim()
    if (u.isNotBlank()) {
        val hit = tired[u]
        if (hit != null) return hit.until > nowMs
    }
    val key = djTrackTitleKey(name, artists)
    if (key.isBlank()) return false
    return tired.values.any { it.until > nowMs && it.titleKey.isNotBlank() && it.titleKey == key }
}

fun djIsDisliked(
    uri: String,
    artistIds: List<String> = emptyList(),
    artists: String = "",
    artistUri: String = "",
    name: String = "",
    blockedTracks: Map<String, DjBlockedTrack>,
    blockedArtists: Map<String, DjBlockedArtist>,
    tiredTracks: Map<String, DjTiredTrack>,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (djTrackIsBlocked(uri, name, artists, blockedTracks)) return true
    if (djTrackIsTired(uri, name, artists, tiredTracks, nowMs)) return true
    if (djArtistIsBlocked(artistIds, artists, artistUri, blockedArtists)) return true
    return false
}
