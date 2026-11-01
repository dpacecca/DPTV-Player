package tv.own.owntv.core.jellyfin

import org.json.JSONArray
import org.json.JSONObject

/**
 * Jellyfin (DPTV v2.9.0): the signed-in account and the library items DPTV shows from it. The
 * library is read live from the Jellyfin server and never copied into DPTV's database; Jellyfin
 * itself keeps the progress and the watched marks, and DPTV writes them back as you watch.
 */
data class JellyfinAccount(
    /** The server's address as typed, without a trailing slash: "http://192.168.1.10:8096". */
    val server: String,
    val userId: String,
    val userName: String,
    val token: String,
    /** This TV's id towards Jellyfin, made once and kept, so the server lists it as one device. */
    val deviceId: String,
)

enum class JellyfinKind { MOVIE, SERIES, SEASON, EPISODE, OTHER }

data class JellyfinItem(
    val id: String,
    val name: String,
    val kind: JellyfinKind,
    val year: Int? = null,
    val overview: String? = null,
    val runtimeMs: Long? = null,
    /** Where Jellyfin has you up to; 0 when not started. */
    val positionMs: Long = 0,
    val played: Boolean = false,
    /** Episodes left to watch, for a series (Jellyfin's UnplayedItemCount). */
    val unplayed: Int? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    /** Season number of an episode (Jellyfin's ParentIndexNumber), or a season's own number. */
    val seasonNumber: Int? = null,
    /** Episode number (Jellyfin's IndexNumber). */
    val episodeNumber: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    /** Image tags: Jellyfin serves an image by item id, the tag makes the URL change when it does. */
    val primaryTag: String? = null,
    val backdropTag: String? = null,
    /** An episode's or season's own art is often missing; then the series' is used. */
    val parentBackdropItemId: String? = null,
    val parentBackdropTag: String? = null,
    val seriesPrimaryTag: String? = null,
    val premiereDateMs: Long? = null,
    /** When it was added to Jellyfin, for "latest". */
    val dateCreatedMs: Long? = null,
    /** (v2.9.1) TMDB's id from Jellyfin's ProviderIds: how Trakt, TMDB recommendations and the playlists find it. */
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    /** (v2.9.1) When it was last watched, for Continue watching's order. */
    val lastPlayedMs: Long? = null,
) {
    val resumable: Boolean get() = positionMs > 0 && !played
}

/** One page of a library listing. */
data class JellyfinPage(val items: List<JellyfinItem>, val total: Int)

/**
 * Jellyfin's JSON, as pure functions so each is tested. Ticks are 100 ns: a millisecond is 10 000.
 * Every field is optional in practice (a series has no runtime, a movie no series), so nothing here throws.
 */
object JellyfinParse {

    const val TICKS_PER_MS = 10_000L

    fun account(server: String, deviceId: String, body: String): JellyfinAccount? = runCatching {
        val o = JSONObject(body)
        val token = o.optString("AccessToken").ifBlank { return null }
        val user = o.optJSONObject("User") ?: return null
        JellyfinAccount(
            server = normalizeServer(server),
            userId = user.optString("Id").ifBlank { return null },
            userName = user.optString("Name"),
            token = token,
            deviceId = deviceId,
        )
    }.getOrNull()

    fun page(body: String): JellyfinPage = runCatching {
        val o = JSONObject(body)
        val items = items(o.optJSONArray("Items"))
        JellyfinPage(items, o.optInt("TotalRecordCount", items.size))
    }.getOrDefault(JellyfinPage(emptyList(), 0))

    /** "Latest" answers a bare array rather than a page. */
    fun array(body: String): List<JellyfinItem> = runCatching { items(JSONArray(body)) }.getOrDefault(emptyList())

    fun item(body: String): JellyfinItem? = runCatching { item(JSONObject(body)) }.getOrNull()

    private fun items(arr: JSONArray?): List<JellyfinItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::item) }
    }

    fun item(o: JSONObject): JellyfinItem? {
        val id = o.optString("Id").ifBlank { return null }
        val user = o.optJSONObject("UserData")
        val tags = o.optJSONObject("ImageTags")
        val backdrops = o.optJSONArray("BackdropImageTags")
        val parentBackdrops = o.optJSONArray("ParentBackdropImageTags")
        return JellyfinItem(
            id = id,
            name = o.optString("Name"),
            kind = when (o.optString("Type")) {
                "Movie" -> JellyfinKind.MOVIE
                "Series" -> JellyfinKind.SERIES
                "Season" -> JellyfinKind.SEASON
                "Episode" -> JellyfinKind.EPISODE
                else -> JellyfinKind.OTHER
            },
            year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
            overview = o.optString("Overview").ifBlank { null },
            runtimeMs = o.optLong("RunTimeTicks", 0L).takeIf { it > 0 }?.div(TICKS_PER_MS),
            positionMs = (user?.optLong("PlaybackPositionTicks", 0L) ?: 0L) / TICKS_PER_MS,
            played = user?.optBoolean("Played", false) == true,
            unplayed = user?.optInt("UnplayedItemCount", -1)?.takeIf { it >= 0 },
            seriesId = o.optString("SeriesId").ifBlank { null },
            seriesName = o.optString("SeriesName").ifBlank { null },
            seasonId = o.optString("SeasonId").ifBlank { null },
            seasonNumber = when {
                o.has("ParentIndexNumber") -> o.optInt("ParentIndexNumber")
                o.optString("Type") == "Season" && o.has("IndexNumber") -> o.optInt("IndexNumber")
                else -> null
            },
            episodeNumber = if (o.optString("Type") == "Episode" && o.has("IndexNumber")) o.optInt("IndexNumber") else null,
            rating = o.optDouble("CommunityRating").takeIf { !it.isNaN() && it > 0 },
            genres = o.optJSONArray("Genres")?.let { g -> (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
            primaryTag = tags?.optString("Primary")?.ifBlank { null },
            backdropTag = backdrops?.optString(0)?.ifBlank { null },
            parentBackdropItemId = o.optString("ParentBackdropItemId").ifBlank { null },
            parentBackdropTag = parentBackdrops?.optString(0)?.ifBlank { null },
            seriesPrimaryTag = o.optString("SeriesPrimaryImageTag").ifBlank { null },
            premiereDateMs = isoMs(o.optString("PremiereDate")),
            dateCreatedMs = isoMs(o.optString("DateCreated")),
            tmdbId = providerId(o, "Tmdb")?.toIntOrNull(),
            imdbId = providerId(o, "Imdb"),
            tvdbId = providerId(o, "Tvdb")?.toIntOrNull(),
            lastPlayedMs = isoMs(user?.optString("LastPlayedDate")),
        )
    }

    /** Jellyfin writes provider keys as "Tmdb" but some plugins as "tmdb": either is read. */
    private fun providerId(o: JSONObject, key: String): String? {
        val ids = o.optJSONObject("ProviderIds") ?: return null
        val name = ids.keys().asSequence().firstOrNull { it.equals(key, ignoreCase = true) } ?: return null
        return ids.optString(name).trim().ifBlank { null }
    }

    /** "http://host:8096/" and "host:8096" both become "http://host:8096". */
    fun normalizeServer(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) trimmed
        else "http://$trimmed"
    }

    private fun isoMs(raw: String?): Long? = raw?.takeIf { it.isNotBlank() }?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    }
}
