package tv.own.owntv.core.metadata

import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * TMDB titles for Home's Trending movies / Trending shows rows (v2.4.0), through the app's own
 * [MetadataProvider] — so the same tiers, and on the shared default service the same per-install
 * allowance ([MetadataBudget]), as every other TMDB call. TMDB is asked only while metadata is on
 * ([MetadataConfig.enabled]), the one test the old Now trending download made too.
 *
 * Results are held in memory for [CACHE_TTL_MS] per (kind, source, country, services, English only,
 * language), so Home's frequent refreshes cost nothing: at most 2 calls per row per 30 minutes (4 for
 * "Trending this week" with English only). A failure returns an empty list, which hides the row, and is
 * remembered for [FAILURE_TTL_MS]; the provider has already logged its one line.
 */
class HomeTrendingFeed(
    private val provider: MetadataProvider,
    private val metadataConfig: suspend () -> MetadataConfig,
    private val trendingConfig: suspend () -> HomeTrendingConfig,
    private val deviceCountry: () -> String = HomeTrendingCountry::device,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class TitlesKey(val type: MetadataType, val query: HomeTrendingQuery, val language: String)
    private data class ProvidersKey(val region: String, val language: String)
    private class Entry<T>(val at: Long, val ttl: Long, val value: T)

    private val titles = ConcurrentHashMap<TitlesKey, Entry<List<TrendingCandidate>>>()
    private val providers = ConcurrentHashMap<ProvidersKey, Entry<List<WatchProvider>>>()

    /** The titles for [type] (MOVIE or TV) under the current settings, in TMDB's order; empty when off or failing. */
    suspend fun titles(type: MetadataType): List<TrendingCandidate> {
        require(type == MetadataType.MOVIE || type == MetadataType.TV)
        val config = metadataConfig()
        if (!config.enabled) return emptyList()
        val query = trendingConfig().query(deviceCountry())
        val key = TitlesKey(type, query, config.resolvedLanguage)
        val at = now()
        titles[key]?.takeIf { at - it.at < it.ttl }?.let { return it.value }

        val pages = ArrayList<TrendingFeedPage>(pagesFor(query))
        for (number in 1..pagesFor(query)) {
            val page = provider.homeTrendingPage(type, query, number) ?: break
            pages += page
            if (page.page >= page.totalPages || select(pages, query).size >= TITLE_LIMIT) break
        }
        // A later page failing still leaves a row; only a dead first page is a failure.
        val result = if (pages.isEmpty()) null else select(pages, query)
        titles[key] = Entry(at, if (result == null) FAILURE_TTL_MS else CACHE_TTL_MS, result.orEmpty())
        return result.orEmpty()
    }

    /**
     * The streaming services for [region], movie and TV lists merged, deduped by id and in TMDB's display
     * order, at most [TmdbWatchProviderParser.PICKER_LIMIT]. Empty when metadata is off or TMDB fails.
     */
    suspend fun watchProviders(region: String): List<WatchProvider> {
        val config = metadataConfig()
        if (!config.enabled) return emptyList()
        val key = ProvidersKey(region, config.resolvedLanguage)
        val at = now()
        providers[key]?.takeIf { at - it.at < it.ttl }?.let { return it.value }
        val lists = coroutineScope {
            val movie = async { provider.watchProviders(MetadataType.MOVIE, region) }
            val tv = async { provider.watchProviders(MetadataType.TV, region) }
            listOfNotNull(movie.await(), tv.await())
        }
        val result = if (lists.isEmpty()) null else TmdbWatchProviderParser.merge(lists)
        providers[key] = Entry(at, if (result == null) FAILURE_TTL_MS else CACHE_TTL_MS, result.orEmpty())
        return result.orEmpty()
    }

    /** Drops what is held, so the next read asks TMDB again (a changed setting already misses the cache). */
    fun clear() {
        titles.clear()
        providers.clear()
    }

    companion object {
        /** About two TMDB pages: the row then still fills after the titles the playlists lack drop out. */
        const val TITLE_LIMIT = 40

        /** "Trending this week" has no language filter, so English only reads further to keep ~40. */
        const val WEEKLY_ENGLISH_PAGES = 4
        const val PAGES = 2
        const val ENGLISH = "en"
        const val CACHE_TTL_MS = 30L * 60 * 1000
        const val FAILURE_TTL_MS = 5L * 60 * 1000

        fun pagesFor(query: HomeTrendingQuery): Int =
            if (query.source == HomeTrendingSource.WEEKLY && query.englishOnly) WEEKLY_ENGLISH_PAGES else PAGES

        /**
         * [pages] as one list in TMDB's order, once per title, at most [TITLE_LIMIT]. With English only,
         * "Trending this week" keeps English originals here; discover already asked TMDB for those.
         */
        fun select(pages: List<TrendingFeedPage>, query: HomeTrendingQuery): List<TrendingCandidate> {
            val merged = TrendingFeedPage.merge(pages, limit = Int.MAX_VALUE)
            val kept = if (query.source == HomeTrendingSource.WEEKLY && query.englishOnly) {
                merged.filter { it.originalLanguage.equals(ENGLISH, ignoreCase = true) }
            } else merged
            return kept.take(TITLE_LIMIT)
        }
    }
}
