package tv.own.owntv.core.home

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.jellyfin.JellyfinClient
import tv.own.owntv.core.jellyfin.JellyfinItem
import tv.own.owntv.core.jellyfin.JellyfinStore
import tv.own.owntv.core.metadata.MetadataConfig
import tv.own.owntv.core.metadata.MetadataImages
import tv.own.owntv.core.metadata.MetadataProvider
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TitleNormalizer
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.trakt.TraktStore

/**
 * A title for Home's Recommended and Watchlist rows (DPTV v2.9.1), with where it can be played: the
 * user's Jellyfin server, a playlist, or both (Jellyfin is opened first).
 */
@Immutable
data class AvailableTitle(
    val title: TrendingCandidate,
    val jellyfin: JellyfinItem? = null,
    val movie: MovieEntity? = null,
    val series: SeriesEntity? = null,
    val posterUrl: String? = null,
) {
    val key: String get() = (if (title.type == MetadataType.TV) "tv:" else "movie:") + title.tmdbId
}

/** The pure half of the recommendations (DPTV v2.9.1), kept apart so it is tested. */
object RecommendationRules {

    /**
     * Joins TMDB's lists for several watched titles into one: a title scores more the higher it sits in a
     * list and the more lists it is in; [exclude] (watched, or a seed itself) is left out.
     */
    fun rank(lists: List<List<TrendingCandidate>>, exclude: Set<Int>, limit: Int = 60): List<TrendingCandidate> {
        val score = HashMap<Int, Double>()
        val first = LinkedHashMap<Int, TrendingCandidate>()
        lists.forEach { list ->
            list.forEachIndexed { i, c ->
                if (c.tmdbId in exclude) return@forEachIndexed
                score[c.tmdbId] = (score[c.tmdbId] ?: 0.0) + (PerList - i).coerceAtLeast(1)
                first.putIfAbsent(c.tmdbId, c)
            }
        }
        return first.values.sortedWith(compareByDescending<TrendingCandidate> { score[it.tmdbId] }.thenByDescending { it.popularity }).take(limit)
    }

    /** A Jellyfin title by TMDB id, else by the same normalised name and a year within one. */
    fun findInJellyfin(t: TrendingCandidate, byTmdb: Map<Int, JellyfinItem>, byName: Map<String, List<JellyfinItem>>): JellyfinItem? =
        byTmdb[t.tmdbId] ?: listOfNotNull(t.localizedTitle, t.originalTitle).asSequence()
            .flatMap { byName[nameKey(it)].orEmpty().asSequence() }
            .firstOrNull { j -> t.year == null || j.year == null || kotlin.math.abs(j.year - t.year) <= 1 }

    fun nameKey(name: String): String =
        TitleNormalizer.normalize(name).query.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    /** How many watched titles the recommendations start from, per kind. */
    const val Seeds = 6
    private const val PerList = 21
}

/**
 * Home's Recommended movies / Recommended shows and the Trakt Watchlist (DPTV v2.9.1). Recommended starts
 * from the last films or shows watched — on Jellyfin and in DPTV's own Movies and TV Shows — asks TMDB what
 * people who liked them watch, leaves out what was already watched (here, on Jellyfin, or on Trakt), and
 * keeps only what the Jellyfin server or the playlists have.
 *
 * TMDB is asked at most once per watched title per [CacheMs], so it costs a handful of calls a day.
 */
class HomeRecommendations(
    private val provider: MetadataProvider,
    private val metadataConfig: suspend () -> MetadataConfig,
    private val jellyfinStore: JellyfinStore,
    private val jellyfin: JellyfinClient,
    private val traktStore: TraktStore,
    private val trending: TrendingHomeReader,
    private val progressDao: ProgressDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val metadataDao: MetadataDao,
) {
    private class Entry<T>(val at: Long, val value: T)

    private val lists = ConcurrentHashMap<String, Entry<List<TrendingCandidate>>>()
    private val library = ConcurrentHashMap<JellyfinClient.Shelf, Entry<List<JellyfinItem>>>()

    suspend fun recommended(profileId: Long, type: MetadataType): List<AvailableTitle> = withContext(Dispatchers.IO) {
        if (!metadataConfig().enabled) return@withContext emptyList()
        val shelf = if (type == MetadataType.MOVIE) JellyfinClient.Shelf.MOVIES else JellyfinClient.Shelf.SHOWS
        val jf = jellyfinLibrary(shelf)
        val seeds = seeds(profileId, type, jf)
        if (seeds.isEmpty()) return@withContext emptyList()
        val fetched = coroutineScope { seeds.map { id -> async { recommendationsFor(type, id) } }.awaitAll() }
        val traktWatched = traktStore.watchedTmdb.first()
        val prefix = if (type == MetadataType.MOVIE) "m:" else "s:"
        val exclude = seeds.toSet() + jf.filter { it.played }.mapNotNull { it.tmdbId } +
            traktWatched.filter { it.startsWith(prefix) }.mapNotNull { it.removePrefix(prefix).toIntOrNull() }
        available(profileId, type, RecommendationRules.rank(fetched, exclude), jf).take(Shown)
    }

    /** The Trakt watchlist's films or shows that can be played here, newest first. */
    suspend fun watchlist(profileId: Long): List<AvailableTitle> = withContext(Dispatchers.IO) {
        val items = traktStore.watchlist.first().sortedByDescending { it.listedAtMs ?: 0L }
        if (items.isEmpty()) return@withContext emptyList()
        fun candidates(show: Boolean) = items.filter { it.isShow == show }.mapIndexedNotNull { i, t ->
            val tmdb = t.ids.tmdb ?: return@mapIndexedNotNull null
            TrendingCandidate(tmdb, if (show) MetadataType.TV else MetadataType.MOVIE, t.title, null, t.year, null, null, null, null, 0.0, i + 1)
        }
        val movies = available(profileId, MetadataType.MOVIE, candidates(false), jellyfinLibrary(JellyfinClient.Shelf.MOVIES))
        val shows = available(profileId, MetadataType.TV, candidates(true), jellyfinLibrary(JellyfinClient.Shelf.SHOWS))
        val order = items.mapIndexedNotNull { i, t -> t.ids.tmdb?.let { (if (t.isShow) "tv:" else "movie:") + it to i } }.toMap()
        (movies + shows).sortedBy { order[it.key] ?: Int.MAX_VALUE }
    }

    /** Forget what was fetched (a new sign-in, or Refresh). */
    fun clear() {
        lists.clear()
        library.clear()
    }

    /** The titles in [ranked] that Jellyfin or the playlists have, in that order. */
    private suspend fun available(profileId: Long, type: MetadataType, ranked: List<TrendingCandidate>, jf: List<JellyfinItem>): List<AvailableTitle> {
        if (ranked.isEmpty()) return emptyList()
        val byTmdb = jf.filter { it.tmdbId != null }.associateBy { it.tmdbId!! }
        val byName = jf.groupBy { RecommendationRules.nameKey(it.name) }
        val inJellyfin = ranked.associate { it.tmdbId to RecommendationRules.findInJellyfin(it, byTmdb, byName) }
        val a = jellyfinStore.current()
        return if (type == MetadataType.MOVIE) {
            val vod = runCatching { trending.findMovies(profileId, ranked) }.getOrDefault(emptyList()).associateBy { it.title.tmdbId }
            ranked.mapNotNull { t ->
                val j = inJellyfin[t.tmdbId]
                val v = vod[t.tmdbId]
                if (j == null && v == null) return@mapNotNull null
                AvailableTitle(t, j, movie = v?.item, posterUrl = MetadataImages.poster(t.posterPath)
                    ?: j?.let { a?.let { acc -> jellyfin.posterUrl(acc, it) } } ?: v?.posterUrl)
            }
        } else {
            val vod = runCatching { trending.findShows(profileId, ranked) }.getOrDefault(emptyList()).associateBy { it.title.tmdbId }
            ranked.mapNotNull { t ->
                val j = inJellyfin[t.tmdbId]
                val v = vod[t.tmdbId]
                if (j == null && v == null) return@mapNotNull null
                AvailableTitle(t, j, series = v?.item, posterUrl = MetadataImages.poster(t.posterPath)
                    ?: j?.let { a?.let { acc -> jellyfin.posterUrl(acc, it) } } ?: v?.posterUrl)
            }
        }
    }

    /** TMDB ids of the last titles watched: Jellyfin's first, then DPTV's own. */
    private suspend fun seeds(profileId: Long, type: MetadataType, jf: List<JellyfinItem>): List<Int> {
        val out = LinkedHashSet<Int>()
        if (jellyfinStore.current() != null) {
            runCatching {
                if (type == MetadataType.MOVIE) {
                    (jellyfin.resume(JellyfinClient.Shelf.MOVIES, 4) + jellyfin.recentlyPlayed(JellyfinClient.Shelf.MOVIES, 8))
                        .sortedByDescending { it.lastPlayedMs ?: 0L }
                        .mapNotNullTo(out) { it.tmdbId }
                } else {
                    val byId = jf.associateBy { it.id }
                    (jellyfin.resume(JellyfinClient.Shelf.SHOWS, 6) + jellyfin.recentlyPlayed(JellyfinClient.Shelf.SHOWS, 20))
                        .sortedByDescending { it.lastPlayedMs ?: 0L }
                        .mapNotNullTo(out) { e -> e.seriesId?.let { byId[it]?.tmdbId } }
                }
            }
        }
        if (out.size < RecommendationRules.Seeds) {
            val wanted = if (type == MetadataType.MOVIE) MediaType.MOVIE else MediaType.EPISODE
            val recent = progressDao.getForProfile(profileId).filter { it.mediaType == wanted }.sortedByDescending { it.updatedAt }.take(20)
            for (p in recent) {
                if (out.size >= RecommendationRules.Seeds) break
                val key = if (type == MetadataType.MOVIE) {
                    movieDao.getById(p.itemId)?.let { MetadataRepository.movieLocalKey(it) }
                } else {
                    seriesDao.getEpisodeById(p.itemId)?.let { seriesDao.getSeriesById(it.seriesId) }?.let { MetadataRepository.seriesLocalKey(it) }
                } ?: continue
                metadataDao.getMatches(listOf(key, MetadataRepository.maturityKey(key))).firstNotNullOfOrNull { it.tmdbId }?.let(out::add)
            }
        }
        return out.take(RecommendationRules.Seeds)
    }

    private suspend fun recommendationsFor(type: MetadataType, tmdbId: Int): List<TrendingCandidate> {
        val key = "$type:$tmdbId"
        val now = SystemClock.elapsedRealtime()
        lists[key]?.takeIf { now - it.at < CacheMs }?.let { return it.value }
        val result = provider.recommendations(type, tmdbId)?.candidates.orEmpty()
        lists[key] = Entry(now, result)
        return result
    }

    private suspend fun jellyfinLibrary(shelf: JellyfinClient.Shelf): List<JellyfinItem> {
        if (jellyfinStore.current() == null) return emptyList()
        val now = SystemClock.elapsedRealtime()
        library[shelf]?.takeIf { now - it.at < LibraryMs }?.let { return it.value }
        val items = runCatching { jellyfin.everything(shelf) }.getOrNull() ?: return library[shelf]?.value.orEmpty()
        library[shelf] = Entry(now, items)
        return items
    }

    private companion object {
        const val CacheMs = 12L * 60L * 60L * 1000L
        const val LibraryMs = 30L * 60L * 1000L
        const val Shown = 30
    }
}
