package tv.own.owntv.core.home

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.database.entity.MetadataMatchEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.HomeTrendingFeed
import tv.own.owntv.core.metadata.MetadataImages
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.trending.TrendingMatcher

/** One TMDB title found in the playlists: the entry OK opens, and the poster the card shows (v2.4.0). */
@Immutable
data class TrendingPick<T>(
    val item: T,
    val title: TrendingCandidate,
    val posterUrl: String?,
)

/** Home's Trending movies / Trending shows rows, each in TMDB's order and holding only what the playlists have (v2.4.0). */
@Immutable
data class TrendingHomeRows(
    val movies: List<TrendingPick<MovieEntity>> = emptyList(),
    val shows: List<TrendingPick<SeriesEntity>> = emptyList(),
)

/**
 * The pure half of matching TMDB's trending titles to the playlists (v2.4.0), kept apart so it is unit-testable.
 * An id match (the TMDB id a playlist entry was already matched to) wins over a title match; each entry
 * is used once, and the result keeps TMDB's order.
 */
object TrendingLibraryMatcher {

    /**
     * `movie:12:tt-remote` (or with `:kids`, the kids-profile variant) → source 12, item key `tt-remote`.
     * The key is the provider's remote id, or the name where the provider sends none.
     */
    fun parseLocalKey(localKey: String): Pair<Long, String>? {
        val parts = localKey.split(':', limit = 3)
        if (parts.size < 3) return null
        val sourceId = parts[1].toLongOrNull() ?: return null
        val tail = parts[2].removeSuffix(KIDS_SUFFIX).takeIf { it.isNotEmpty() } ?: return null
        return sourceId to tail
    }

    /** [titles] as [TrendingMatcher] candidates; the rank becomes the title's index plus one. */
    fun candidates(titles: List<TrendingCandidate>): List<TrendingCandidate> =
        titles.mapIndexed { index, t -> t.copy(trendingRank = index + 1) }

    /**
     * Walks [titles] in order: the entry matched by TMDB id ([byTmdbId]), else the one matched by title
     * and year ([byIndex], keyed by the title's index). An entry already used is not used again.
     */
    fun <T> assemble(
        titles: List<TrendingCandidate>,
        byTmdbId: Map<Int, T>,
        byIndex: Map<Int, T>,
        key: (T) -> Long,
    ): List<Pair<TrendingCandidate, T>> {
        val used = HashSet<Long>()
        return titles.mapIndexedNotNull { index, title ->
            val item = byTmdbId[title.tmdbId]?.takeIf { key(it) !in used }
                ?: byIndex[index]?.takeIf { key(it) !in used }
                ?: return@mapIndexedNotNull null
            used += key(item)
            title to item
        }
    }

    private const val KIDS_SUFFIX = ":kids"
}

/**
 * Builds Home's Trending movies / Trending shows rows (v2.4.0): asks [HomeTrendingFeed] for TMDB's titles
 * (on streaming in the chosen country, or trending this week), finds each in the profile's active
 * playlists, and gives each a poster: TMDB's from the same response, else what the metadata cache holds,
 * else the playlist's own. No TMDB call beyond the feed itself (cached 30 min).
 */
class TrendingHomeReader(
    private val trending: HomeTrendingFeed,
    private val feed: HomeFeedReader,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val metadataDao: MetadataDao,
    private val metadata: MetadataRepository,
    private val settings: SettingsRepository,
) {
    /** The rows for [profileId]; empty (hidden) when metadata is off, TMDB fails, or nothing matches. */
    suspend fun load(profileId: Long): TrendingHomeRows = withContext(Dispatchers.IO) {
        val visible = settings.homeConfig(profileId).first().visibleOrder
        val wantMovies = HomeRow.TRENDING_MOVIES in visible
        val wantShows = HomeRow.TRENDING_SHOWS in visible
        if (!wantMovies && !wantShows) return@withContext TrendingHomeRows()
        coroutineScope {
            val movieTitles = async { if (wantMovies) trending.titles(MetadataType.MOVIE) else emptyList() }
            val showTitles = async { if (wantShows) trending.titles(MetadataType.TV) else emptyList() }
            val visibility = feed.vodVisibility(profileId)
            val language = settings.metadataConfig().resolvedLanguage
            val movies = match(
                titles = movieTitles.await(),
                mediaType = MediaType.MOVIE,
                type = MetadataRepository.TYPE_MOVIE,
                sourceIds = visibility.movieSourceIds.toList(),
                language = language,
                byRemoteIds = movieDao::findByRemoteIds,
                byIds = movieDao::getByIds,
                exact = movieDao::trendingExact,
                fts = movieDao::trendingFts,
                allowed = { visibility.allows(it) },
                id = { it.id },
                remoteId = { it.remoteId },
            )
            val shows = match(
                titles = showTitles.await(),
                mediaType = MediaType.SERIES,
                type = MetadataRepository.TYPE_TV,
                sourceIds = visibility.seriesSourceIds.toList(),
                language = language,
                byRemoteIds = seriesDao::findSeriesByRemoteIds,
                byIds = seriesDao::getSeriesByIds,
                exact = seriesDao::trendingExact,
                fts = seriesDao::trendingFts,
                allowed = { visibility.allows(it) },
                id = { it.id },
                remoteId = { it.remoteId },
            )
            val moviePosters = runCatching { metadata.cachedMoviePosters(movies.map { it.second }) }.getOrDefault(emptyMap())
            val showPosters = runCatching { metadata.cachedSeriesPosters(shows.map { it.second }) }.getOrDefault(emptyMap())
            TrendingHomeRows(
                movies = movies.map { (t, m) -> TrendingPick(m, t, MetadataImages.poster(t.posterPath) ?: moviePosters[m.id] ?: m.posterUrl) },
                shows = shows.map { (t, s) -> TrendingPick(s, t, MetadataImages.poster(t.posterPath) ?: showPosters[s.id] ?: s.posterUrl) },
            )
        }
    }

    /** Matches [titles] to entries of [sourceIds]: by TMDB id first, then by normalised title and year. */
    private suspend fun <T> match(
        titles: List<TrendingCandidate>,
        mediaType: MediaType,
        type: String,
        sourceIds: List<Long>,
        language: String,
        byRemoteIds: suspend (Long, List<String>) -> List<T>,
        byIds: suspend (List<Long>) -> List<T>,
        exact: suspend (Long, List<String>) -> List<TrendingCatalogRow>,
        fts: suspend (Long, String, Int) -> List<TrendingCatalogRow>,
        allowed: (T) -> Boolean,
        id: (T) -> Long,
        remoteId: (T) -> String?,
    ): List<Pair<TrendingCandidate, T>> {
        if (titles.isEmpty() || sourceIds.isEmpty()) return emptyList()

        // 1. By id: entries the metadata layer already matched to the title's TMDB id.
        val tmdbIds = titles.map { it.tmdbId }.distinct()
        val matches = if (tmdbIds.isEmpty()) emptyList() else metadataDao.matchesForTmdbIds(type, tmdbIds)
        val byTmdbId = idMatches(matches, sourceIds, byRemoteIds, allowed, remoteId)

        // 2. By title: the rest through the matcher TMDB's Trending uses (exact title signature, then
        //    FTS; a film's year must agree, a show's within one).
        val open = titles.indices.filter { i -> titles[i].tmdbId !in byTmdbId }.toSet()
        val selections = if (open.isEmpty()) emptyList() else TrendingMatcher.matchMedia(
            candidates = TrendingLibraryMatcher.candidates(titles).filter { (it.trendingRank - 1) in open },
            mediaType = mediaType,
            preferredLanguage = language,
            limit = open.size,
            exactLookup = { signatures -> sourceIds.flatMap { exact(it, signatures) } },
            ftsLookup = { query, limit -> sourceIds.flatMap { fts(it, query, limit) } },
        ).selections
        val entities = if (selections.isEmpty()) emptyMap() else {
            byIds(selections.map { it.variant.item.id }.distinct()).associateBy(id)
        }
        val byIndex = selections.mapNotNull { s ->
            entities[s.variant.item.id]?.takeIf(allowed)?.let { (s.candidate.trendingRank - 1) to it }
        }.toMap()

        return TrendingLibraryMatcher.assemble(titles, byTmdbId, byIndex, id)
    }

    /** TMDB id → the first visible entry matched to it, playlists in the profile's order. */
    private suspend fun <T> idMatches(
        matches: List<MetadataMatchEntity>,
        sourceIds: List<Long>,
        byRemoteIds: suspend (Long, List<String>) -> List<T>,
        allowed: (T) -> Boolean,
        remoteId: (T) -> String?,
    ): Map<Int, T> {
        if (matches.isEmpty()) return emptyMap()
        // (source, item key) → TMDB id, from the stored local keys.
        val keyed = matches.mapNotNull { m ->
            val tmdbId = m.tmdbId ?: return@mapNotNull null
            TrendingLibraryMatcher.parseLocalKey(m.localKey)?.let { it to tmdbId }
        }.toMap()
        val out = LinkedHashMap<Int, T>()
        for (sourceId in sourceIds) {
            val tails = keyed.keys.filter { it.first == sourceId }.map { it.second }
            if (tails.isEmpty()) continue
            for (entity in byRemoteIds(sourceId, tails)) {
                if (!allowed(entity)) continue
                val tmdbId = keyed[sourceId to (remoteId(entity) ?: continue)] ?: continue
                out.putIfAbsent(tmdbId, entity)
            }
        }
        return out
    }
}
