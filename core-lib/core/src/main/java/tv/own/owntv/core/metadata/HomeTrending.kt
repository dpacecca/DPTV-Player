package tv.own.owntv.core.metadata

import org.json.JSONObject
import java.util.Locale

/** Where Home's Trending movies / Trending shows rows come from (v2.4.0). Stored by name. */
enum class HomeTrendingSource {
    /** TMDB discover: the most popular titles on subscription streaming in the chosen country. */
    STREAMING,

    /** TMDB's trending this week, worldwide. */
    WEEKLY;

    companion object {
        fun fromName(name: String?): HomeTrendingSource = entries.firstOrNull { it.name == name } ?: STREAMING
    }
}

/**
 * The "Trending on Home" settings in one read (v2.4.0). A blank [country] means the default
 * ([HomeTrendingCountry.resolve]); no [providerIds] means every service.
 */
data class HomeTrendingConfig(
    val source: HomeTrendingSource = HomeTrendingSource.STREAMING,
    val country: String = "",
    val providerIds: Set<Int> = emptySet(),
    val englishOnly: Boolean = true,
) {
    /** What one feed request asks TMDB, with the country resolved. Providers only count when streaming. */
    fun query(deviceCountry: String = HomeTrendingCountry.device()): HomeTrendingQuery = HomeTrendingQuery(
        source = source,
        region = HomeTrendingCountry.resolve(country, deviceCountry),
        providerIds = if (source == HomeTrendingSource.STREAMING) providerIds.toSortedSet() else emptySet(),
        englishOnly = englishOnly,
    )
}

/** One resolved Home trending request (v2.4.0); also the feed's cache identity. */
data class HomeTrendingQuery(
    val source: HomeTrendingSource,
    val region: String,
    val providerIds: Set<Int> = emptySet(),
    val englishOnly: Boolean = true,
)

/** The country Home's streaming feed uses (v2.4.0). The owner is in Australia, hence the fallback. */
object HomeTrendingCountry {
    const val FALLBACK = "AU"

    /** The picker's short list; the device's and the stored country are added when missing. */
    val CURATED: List<String> = listOf("AU", "NZ", "US", "GB", "CA", "IE", "DE", "FR", "ES", "IT", "NL")

    private val iso: Set<String> by lazy { Locale.getISOCountries().toSet() }

    fun isValid(code: String?): Boolean = code != null && code.length == 2 && code.uppercase(Locale.ROOT) in iso

    /**
     * The device's own region: the system locale's, not the app's (the app may run in another UI
     * language), else the process default. Blank when neither names one.
     */
    fun device(): String =
        runCatching { android.content.res.Resources.getSystem().configuration.locales[0].country }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country

    /** The stored country if valid, else the device's if valid, else [FALLBACK]. */
    fun resolve(stored: String?, deviceCountry: String?): String =
        listOf(stored?.trim(), deviceCountry?.trim()).firstOrNull { isValid(it) }?.uppercase(Locale.ROOT) ?: FALLBACK

    /** The picker's codes: [CURATED] plus [extra] valid codes, without duplicates. */
    fun choices(vararg extra: String?): List<String> =
        (CURATED + extra.filter { isValid(it) }.map { it!!.trim().uppercase(Locale.ROOT) }).distinct()
}

/** A streaming service as TMDB lists it for a country (v2.4.0). Lower [priority] = shown first. */
data class WatchProvider(val id: Int, val name: String, val priority: Int)

/** Pure parser for TMDB's `/watch/providers/{movie|tv}` lists (v2.4.0). */
internal object TmdbWatchProviderParser {

    /** How many services the picker offers: TMDB lists dozens of niche ones per country. */
    const val PICKER_LIMIT = 25

    /** The services in [body]; each ranked by its [region] priority when TMDB sends one. Null when unreadable. */
    fun parse(body: String, region: String): List<WatchProvider>? = runCatching {
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val out = ArrayList<WatchProvider>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val id = o.optInt("provider_id", 0).takeIf { it > 0 } ?: continue
            val name = o.optString("provider_name").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val regional = o.optJSONObject("display_priorities")?.takeIf { it.has(region) }?.optInt(region)
            out += WatchProvider(id, name.trim(), regional ?: o.optInt("display_priority", Int.MAX_VALUE))
        }
        out
    }.getOrNull()

    /** The movie and TV lists as one: once per id (best priority wins), by priority, at most [limit]. */
    fun merge(lists: List<List<WatchProvider>>, limit: Int = PICKER_LIMIT): List<WatchProvider> =
        lists.flatten()
            .groupBy { it.id }
            .map { (_, same) -> same.minBy { it.priority } }
            .sortedWith(compareBy<WatchProvider> { it.priority }.thenBy { it.name.lowercase(Locale.ROOT) })
            .take(limit)
}
