package tv.own.owntv.core.metadata

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import tv.own.owntv.core.settings.SettingsRepository
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Which half of Trakt a row reads (v2.4.0): the API path segment and the item's object key. */
enum class TraktKind(val path: String, val itemKey: String) {
    MOVIES("movies", "movie"),
    SHOWS("shows", "show"),
}

/** One title from Trakt, with the ids Trakt sends. No artwork: Trakt has none (v2.4.0). */
data class TraktTitle(
    val title: String,
    val year: Int?,
    val traktId: Long?,
    val slug: String?,
    val imdbId: String?,
    val tmdbId: Int?,
)

/** The Trakt settings in one read (v2.4.0). Blank list = Trakt's own trending. */
data class TraktConfig(
    val clientId: String = "",
    val moviesList: String = "",
    val showsList: String = "",
) {
    fun listFor(kind: TraktKind): String = when (kind) {
        TraktKind.MOVIES -> moviesList
        TraktKind.SHOWS -> showsList
    }
}

/**
 * A Trakt list the user typed (v2.4.0): a full list URL, `user/slug`, or an official list
 * (`trakt.tv/lists/official/<slug>`, `trakt.tv/lists/<id>`). [parse] returns null for anything else.
 */
sealed interface TraktListRef {
    /** The API path of the list's items of [kind], without the leading slash. */
    fun itemsPath(kind: TraktKind): String

    data class User(val user: String, val slug: String) : TraktListRef {
        override fun itemsPath(kind: TraktKind): String = "users/$user/lists/$slug/items/${kind.path}"
    }

    /** A list addressed by its Trakt id or slug alone — what official lists are. */
    data class Single(val id: String) : TraktListRef {
        override fun itemsPath(kind: TraktKind): String = "lists/$id/items/${kind.path}"
    }

    companion object {
        private val SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://""")
        private val HOST = Regex("""^(?:www\.|app\.)?trakt\.tv(?:/|$)""", RegexOption.IGNORE_CASE)
        private val SEGMENT = Regex("""^[A-Za-z0-9._~%-]+$""")

        fun parse(raw: String): TraktListRef? {
            var s = raw.trim().substringBefore('?').substringBefore('#')
            if (s.isEmpty()) return null
            val hadScheme = SCHEME.containsMatchIn(s)
            s = s.replace(SCHEME, "")
            val hadHost = HOST.containsMatchIn(s)
            // A URL on some other site is not a Trakt list.
            if (hadScheme && !hadHost) return null
            s = s.replace(HOST, "")
            val parts = s.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty() || parts.any { !SEGMENT.matches(it) }) return null
            return when {
                parts.size >= 4 && parts[0] == "users" && parts[2] == "lists" -> User(parts[1], parts[3])
                parts.size >= 3 && parts[0] == "lists" && parts[1] == "official" -> Single(parts[2])
                parts.size >= 2 && parts[0] == "lists" -> Single(parts[1])
                // The short "user/slug" form only when typed bare, never as a path on trakt.tv.
                parts.size == 2 && !hadHost && parts[0] != "users" -> User(parts[0], parts[1])
                else -> null
            }
        }
    }
}

/**
 * Pure parser for Trakt's item arrays (v2.4.0). Trending items (`{watchers, movie}`) and list items
 * (`{rank, type, movie}`) carry the same object under `movie` / `show`; anything else in a mixed list
 * (people, seasons, the other kind) is skipped.
 */
internal object TraktParser {

    fun parseItems(body: String, kind: TraktKind): List<TraktTitle>? = runCatching {
        val items = JSONArray(body)
        val out = ArrayList<TraktTitle>(items.length())
        for (index in 0 until items.length()) {
            val media = items.optJSONObject(index)?.optJSONObject(kind.itemKey) ?: continue
            val title = media.optString("title").nullUnlessValue() ?: continue
            val ids = media.optJSONObject("ids")
            out += TraktTitle(
                title = title,
                year = media.optInt("year", 0).takeIf { it in 1800..2999 },
                traktId = ids?.optLong("trakt", 0L)?.takeIf { it > 0L },
                slug = ids?.optString("slug")?.nullUnlessValue(),
                imdbId = ids?.optString("imdb")?.nullUnlessValue()?.takeIf { it.startsWith("tt") },
                tmdbId = ids?.optInt("tmdb", 0)?.takeIf { it > 0 },
            )
        }
        // A list can hold the same title twice; the first place is the one that counts.
        out.distinctBy { it.traktId ?: (it.title to it.year) }
    }.getOrNull()

    private fun String.nullUnlessValue(): String? = takeIf { it.isNotBlank() && it != "null" }
}

/**
 * Trakt API v2 client for Home's trending rows (v2.4.0). Reads Trakt's trending movies / shows, or the
 * list the user set for a row, with the user's own client id. Results are held in memory for
 * [CACHE_TTL_MS] per (row, key, list), so Home's frequent refreshes cost nothing; a failure returns an
 * empty list (the row then hides) and is remembered for [FAILURE_TTL_MS], so it logs once, not per refresh.
 */
class TraktClient(
    private val http: OkHttpClient,
    /** Where the key and lists come from: [SettingsRepository.traktConfig] in the app, a fixed value in tests. */
    private val config: suspend () -> TraktConfig,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val baseUrl: String = API_BASE,
) {
    private data class CacheKey(val kind: TraktKind, val clientId: String, val list: String)
    private class Entry(val at: Long, val ttl: Long, val titles: List<TraktTitle>)

    private val cache = ConcurrentHashMap<CacheKey, Entry>()

    /** The titles for [kind] under the current settings, in Trakt's order; empty when off or failing. */
    suspend fun titles(kind: TraktKind): List<TraktTitle> = titles(kind, config())

    suspend fun titles(kind: TraktKind, config: TraktConfig): List<TraktTitle> {
        val clientId = config.clientId.trim()
        if (clientId.isEmpty()) return emptyList()
        val list = config.listFor(kind).trim()
        val key = CacheKey(kind, clientId, list)
        val at = now()
        cache[key]?.takeIf { at - it.at < it.ttl }?.let { return it.titles }

        val path = if (list.isEmpty()) {
            "${kind.path}/trending?limit=$TRENDING_LIMIT"
        } else {
            TraktListRef.parse(list)?.itemsPath(kind)?.let { "$it?limit=$LIST_LIMIT" }
        }
        val titles = if (path == null) {
            Log.w(TAG, "Trakt list not understood kind=${kind.path}")
            null
        } else {
            fetch("$baseUrl/$path", clientId, kind)?.let { body ->
                TraktParser.parseItems(body, kind).also { if (it == null) Log.w(TAG, "Trakt parse failed kind=${kind.path}") }
            }
        }
        cache[key] = Entry(at, if (titles == null) FAILURE_TTL_MS else CACHE_TTL_MS, titles.orEmpty())
        return titles.orEmpty()
    }

    /** Drops what is held, so the next read asks Trakt again (a changed setting already misses the cache). */
    fun clear() = cache.clear()

    private suspend fun fetch(url: String, clientId: String, kind: TraktKind): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .header(HEADER_API_VERSION, API_VERSION)
            .header(HEADER_API_KEY, clientId)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.string()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The key never appears here: it travels in a header, and the URL holds none.
            Log.w(TAG, "Trakt ${kind.path} failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "TraktClient"
        const val API_BASE = "https://api.trakt.tv"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val CONTENT_TYPE_JSON = "application/json"
        private const val HEADER_API_VERSION = "trakt-api-version"
        private const val API_VERSION = "2"
        private const val HEADER_API_KEY = "trakt-api-key"
        const val TRENDING_LIMIT = 40
        const val LIST_LIMIT = 100
        const val CACHE_TTL_MS = 30L * 60 * 1000
        const val FAILURE_TTL_MS = 5L * 60 * 1000
    }
}
