package tv.own.owntv.core.trakt

import org.json.JSONArray
import org.json.JSONObject

/**
 * Trakt (DPTV v2.9.1): the user's own Trakt app (client id and secret from trakt.tv/oauth/applications)
 * and the session it signed in, kept on this TV only.
 */
data class TraktAuth(
    val clientId: String,
    val clientSecret: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** When [accessToken] stops working, epoch ms. */
    val expiresAtMs: Long = 0,
    val userName: String? = null,
) {
    val signedIn: Boolean get() = !accessToken.isNullOrBlank()
}

/** The code shown on the TV and the page to type it into (Trakt's device sign-in). */
data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSec: Int,
    val intervalSec: Int,
)

data class TraktTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

data class TraktIds(val trakt: Int? = null, val tmdb: Int? = null, val imdb: String? = null, val tvdb: Int? = null)

/** A film on the watchlist, or a show. */
data class TraktListItem(
    val isShow: Boolean,
    val title: String,
    val year: Int?,
    val ids: TraktIds,
    val listedAtMs: Long?,
)

/** What Trakt says was watched: films by TMDB id, and each show's episodes as (season, episode). */
data class TraktWatched(
    val movieTmdbIds: Set<Int> = emptySet(),
    val movieImdbIds: Set<String> = emptySet(),
    /** Show TMDB id → its watched (season, episode) pairs. */
    val showEpisodes: Map<Int, Set<Pair<Int, Int>>> = emptyMap(),
    /** Show TVDB id → the same, for shows Jellyfin only knows by TVDB. */
    val showEpisodesByTvdb: Map<Int, Set<Pair<Int, Int>>> = emptyMap(),
)

/** Trakt's JSON, as pure functions so each is tested. Nothing here throws. */
object TraktParse {

    fun deviceCode(body: String): TraktDeviceCode? = runCatching {
        val o = JSONObject(body)
        TraktDeviceCode(
            deviceCode = o.getString("device_code"),
            userCode = o.getString("user_code"),
            verificationUrl = o.optString("verification_url").ifBlank { DEFAULT_ACTIVATE },
            expiresInSec = o.optInt("expires_in", 600),
            intervalSec = o.optInt("interval", 5).coerceAtLeast(1),
        )
    }.getOrNull()

    fun tokens(body: String, nowMs: Long): TraktTokens? = runCatching {
        val o = JSONObject(body)
        val access = o.getString("access_token").ifBlank { return null }
        val created = o.optLong("created_at", nowMs / 1000L)
        TraktTokens(access, o.optString("refresh_token"), (created + o.optLong("expires_in", 0L)) * 1000L)
    }.getOrNull()

    fun userName(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("user")?.optString("username")?.ifBlank { null }
    }.getOrNull()

    fun ids(o: JSONObject?): TraktIds {
        if (o == null) return TraktIds()
        return TraktIds(
            trakt = o.optInt("trakt", 0).takeIf { it > 0 },
            tmdb = o.optInt("tmdb", 0).takeIf { it > 0 },
            imdb = o.optString("imdb").takeIf { it.startsWith("tt") },
            tvdb = o.optInt("tvdb", 0).takeIf { it > 0 },
        )
    }

    /** `/sync/watched/movies` and `/sync/watched/shows`, together. */
    fun watched(moviesBody: String, showsBody: String): TraktWatched {
        val movies = runCatching { JSONArray(moviesBody) }.getOrNull()
        val shows = runCatching { JSONArray(showsBody) }.getOrNull()
        val tmdb = HashSet<Int>()
        val imdb = HashSet<String>()
        movies?.let { a ->
            for (i in 0 until a.length()) {
                val ids = ids(a.optJSONObject(i)?.optJSONObject("movie")?.optJSONObject("ids"))
                ids.tmdb?.let(tmdb::add)
                ids.imdb?.let(imdb::add)
            }
        }
        val byTmdb = HashMap<Int, MutableSet<Pair<Int, Int>>>()
        val byTvdb = HashMap<Int, MutableSet<Pair<Int, Int>>>()
        shows?.let { a ->
            for (i in 0 until a.length()) {
                val entry = a.optJSONObject(i) ?: continue
                val ids = ids(entry.optJSONObject("show")?.optJSONObject("ids"))
                val eps = HashSet<Pair<Int, Int>>()
                val seasons = entry.optJSONArray("seasons") ?: continue
                for (s in 0 until seasons.length()) {
                    val season = seasons.optJSONObject(s) ?: continue
                    val n = season.optInt("number", -1).takeIf { it >= 0 } ?: continue
                    val episodes = season.optJSONArray("episodes") ?: continue
                    for (e in 0 until episodes.length()) {
                        val num = episodes.optJSONObject(e)?.optInt("number", -1) ?: -1
                        if (num >= 0) eps += n to num
                    }
                }
                ids.tmdb?.let { byTmdb.getOrPut(it) { HashSet() } += eps }
                ids.tvdb?.let { byTvdb.getOrPut(it) { HashSet() } += eps }
            }
        }
        return TraktWatched(tmdb, imdb, byTmdb, byTvdb)
    }

    /** `/sync/watchlist/movies` or `/sync/watchlist/shows`, newest first as Trakt sends them by rank. */
    fun watchlist(body: String): List<TraktListItem> {
        val a = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val isShow = o.optString("type") == "show"
            val media = o.optJSONObject(if (isShow) "show" else "movie") ?: return@mapNotNull null
            TraktListItem(
                isShow = isShow,
                title = media.optString("title").ifBlank { return@mapNotNull null },
                year = media.optInt("year", 0).takeIf { it > 0 },
                ids = ids(media.optJSONObject("ids")),
                listedAtMs = isoMs(o.optString("listed_at")),
            )
        }
    }

    /** The watchlist as it is kept on the TV between syncs. */
    fun encodeList(items: List<TraktListItem>): String = JSONArray().apply {
        items.forEach { t ->
            put(
                JSONObject()
                    .put("type", if (t.isShow) "show" else "movie")
                    .put("listed_at", t.listedAtMs?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
                    .put(
                        if (t.isShow) "show" else "movie",
                        JSONObject().put("title", t.title).put("year", t.year ?: JSONObject.NULL).put("ids", idsJson(t.ids)),
                    ),
            )
        }
    }.toString()

    fun idsJson(ids: TraktIds): JSONObject = JSONObject().apply {
        ids.trakt?.let { put("trakt", it) }
        ids.tmdb?.let { put("tmdb", it) }
        ids.imdb?.let { put("imdb", it) }
        ids.tvdb?.let { put("tvdb", it) }
    }

    /** A `/sync/history` body: films by id, episodes grouped by show and season. */
    fun historyBody(movies: List<TraktIds>, episodes: List<TraktEpisodeRef>): String {
        val root = JSONObject()
        if (movies.isNotEmpty()) root.put("movies", JSONArray().apply { movies.forEach { put(JSONObject().put("ids", idsJson(it))) } })
        if (episodes.isNotEmpty()) {
            root.put(
                "shows",
                JSONArray().apply {
                    episodes.groupBy { it.show }.forEach { (show, eps) ->
                        put(
                            JSONObject().put("ids", idsJson(show)).put(
                                "seasons",
                                JSONArray().apply {
                                    eps.groupBy { it.season }.toSortedMap().forEach { (season, inSeason) ->
                                        put(
                                            JSONObject().put("number", season).put(
                                                "episodes",
                                                JSONArray().apply { inSeason.map { it.episode }.distinct().sorted().forEach { put(JSONObject().put("number", it)) } },
                                            ),
                                        )
                                    }
                                },
                            ),
                        )
                    }
                },
            )
        }
        return root.toString()
    }

    /** A watchlist add/remove body for one film or show. */
    fun listBody(isShow: Boolean, ids: TraktIds): String =
        JSONObject().put(if (isShow) "shows" else "movies", JSONArray().put(JSONObject().put("ids", idsJson(ids)))).toString()

    private fun isoMs(raw: String?): Long? = raw?.takeIf { it.isNotBlank() && it != "null" }?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }

    const val DEFAULT_ACTIVATE = "https://trakt.tv/activate"
}

/** One watched episode to tell Trakt about: its show's ids and its numbers. */
data class TraktEpisodeRef(val show: TraktIds, val season: Int, val episode: Int)
