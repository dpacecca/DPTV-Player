package tv.own.owntv.core.home

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.database.entity.MetadataMatchEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataConfig
import tv.own.owntv.core.metadata.MetadataImages
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TraktClient
import tv.own.owntv.core.metadata.TraktKind
import tv.own.owntv.core.metadata.TraktTitle
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.trending.TrendingMatcher

/** One Trakt title found in the playlists: the entry OK opens, and the poster the card shows (v2.4.0). */
@Immutable
data class TraktPick<T>(
    val item: T,
    val title: TraktTitle,
    val posterUrl: String?,
)

/** Home's two Trakt rows, each in Trakt's order and holding only what the playlists have (v2.4.0). */
@Immutable
data class TraktHomeRows(
    val movies: List<TraktPick<MovieEntity>> = emptyList(),
    val shows: List<TraktPick<SeriesEntity>> = emptyList(),
)

/**
 * The pure half of matching Trakt titles to the playlists (v2.4.0), kept apart so it is unit-testable.
 * An id match (the TMDB id a playlist entry was already matched to) wins over a title match; each entry
 * is used once, and the result keeps Trakt's order.
 */
object TraktLibraryMatcher {

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

    /** Trakt titles as [TrendingMatcher] candidates; the rank is the title's index plus one. */
    fun candidates(titles: List<TraktTitle>, kind: TraktKind): List<TrendingCandidate> =
        titles.mapIndexed { index, t ->
            TrendingCandidate(
                tmdbId = t.tmdbId ?: 0,
                type = if (kind == TraktKind.MOVIES) MetadataType.MOVIE else MetadataType.TV,
                localizedTitle = t.title,
                originalTitle = null,
                year = t.year,
                overview = null,
                posterPath = null,
                backdropPath = null,
                rating = null,
                popularity = 0.0,
                trendingRank = index + 1,
            )
        }

    /**
     * Walks [titles] in order: the entry matched by TMDB id ([byTmdbId]), else the one matched by title
     * and year ([byIndex], keyed by the title's index). An entry already used is not used again.
     */
    fun <T> assemble(
        titles: List<TraktTitle>,
        byTmdbId: Map<Int, T>,
        byIndex: Map<Int, T>,
        key: (T) -> Long,
    ): List<Pair<TraktTitle, T>> {
        val used = HashSet<Long>()
        return titles.mapIndexedNotNull { index, title ->
            val item = title.tmdbId?.let(byTmdbId::get)?.takeIf { key(it) !in used }
                ?: byIndex[index]?.takeIf { key(it) !in used }
                ?: return@mapIndexedNotNull null
            used += key(item)
            title to item
        }
    }

    private const val KIDS_SUFFIX = ":kids"
}

/**
 * Builds Home's Trakt rows (v2.4.0): asks [TraktClient] for the titles, finds each in the profile's
 * active playlists, and gives each a poster. Trakt sends no artwork, so the poster is TMDB's for the
 * title's TMDB id — fetched only with the user's own TMDB key or server ([withTmdbPosters]), otherwise
 * what the metadata cache already holds — and the playlist's own poster when there is neither.
 */
class TraktHomeReader(
    private val trakt: TraktClient,
    private val feed: HomeFeedReader,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val metadataDao: MetadataDao,
    private val metadata: MetadataRepository,
    private val settings: SettingsRepository,
) {
    /** The rows for [profileId] with cache-only posters: no network beyond Trakt itself (cached 30 min). */
    suspend fun load(profileId: Long): TraktHomeRows = withContext(Dispatchers.IO) {
        val config = settings.traktConfig()
        if (config.clientId.isBlank()) return@withContext TraktHomeRows()
        coroutineScope {
            val movieTitles = async { trakt.titles(TraktKind.MOVIES, config) }
            val showTitles = async { trakt.titles(TraktKind.SHOWS, config) }
            val visibility = feed.vodVisibility(profileId)
            val language = settings.metadataConfig().resolvedLanguage
            val movies = match(
                titles = movieTitles.await(),
                kind = TraktKind.MOVIES,
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
                kind = TraktKind.SHOWS,
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
            TraktHomeRows(
                movies = movies.map { (t, m) -> TraktPick(m, t, moviePosters[m.id] ?: m.posterUrl) },
                shows = shows.map { (t, s) -> TraktPick(s, t, showPosters[s.id] ?: s.posterUrl) },
            )
        }
    }

    /**
     * [rows] with TMDB posters by each title's TMDB id, fetched when the user has their own TMDB key or
     * server (the shared default service is rationed, and Home is not worth its allowance). Returns
     * [rows] unchanged otherwise. Details land in the metadata cache, so the next pass costs nothing.
     */
    suspend fun withTmdbPosters(rows: TraktHomeRows): TraktHomeRows = withContext(Dispatchers.IO) {
        val config = settings.metadataConfig()
        if (!config.enabled || config.tier == MetadataConfig.Tier.DEFAULT_WORKER) return@withContext rows
        TraktHomeRows(
            movies = rows.movies.map { pick ->
                val tmdbId = pick.title.tmdbId ?: return@map pick
                val path = runCatching { metadata.resolveKnownMovie(pick.item, tmdbId)?.posterPath }.getOrNull()
                MetadataImages.poster(path)?.let { pick.copy(posterUrl = it) } ?: pick
            },
            shows = rows.shows.map { pick ->
                val tmdbId = pick.title.tmdbId ?: return@map pick
                val path = runCatching { metadata.resolveKnownSeries(pick.item, tmdbId)?.posterPath }.getOrNull()
                MetadataImages.poster(path)?.let { pick.copy(posterUrl = it) } ?: pick
            },
        )
    }

    /** Matches [titles] to entries of [sourceIds]: by TMDB id first, then by normalised title and year. */
    private suspend fun <T> match(
        titles: List<TraktTitle>,
        kind: TraktKind,
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
    ): List<Pair<TraktTitle, T>> {
        if (titles.isEmpty() || sourceIds.isEmpty()) return emptyList()

        // 1. By id: entries the metadata layer already matched to the title's TMDB id.
        val tmdbIds = titles.mapNotNull { it.tmdbId }.distinct()
        val matches = if (tmdbIds.isEmpty()) emptyList() else metadataDao.matchesForTmdbIds(type, tmdbIds)
        val byTmdbId = idMatches(matches, sourceIds, byRemoteIds, allowed, remoteId)

        // 2. By title: the rest through the matcher TMDB's Trending uses (exact title signature, then
        //    FTS; a film's year must agree, a show's within one).
        val open = titles.indices.filter { i -> titles[i].tmdbId.let { it == null || it !in byTmdbId } }.toSet()
        val selections = if (open.isEmpty()) emptyList() else TrendingMatcher.matchMedia(
            candidates = TraktLibraryMatcher.candidates(titles, kind).filter { (it.trendingRank - 1) in open },
            mediaType = if (kind == TraktKind.MOVIES) MediaType.MOVIE else MediaType.SERIES,
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

        return TraktLibraryMatcher.assemble(titles, byTmdbId, byIndex, id)
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
            TraktLibraryMatcher.parseLocalKey(m.localKey)?.let { it to tmdbId }
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
