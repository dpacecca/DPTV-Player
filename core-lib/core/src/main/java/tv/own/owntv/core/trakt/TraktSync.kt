package tv.own.owntv.core.trakt

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.home.TrendingLibraryMatcher
import tv.own.owntv.core.jellyfin.JellyfinClient
import tv.own.owntv.core.jellyfin.JellyfinItem
import tv.own.owntv.core.jellyfin.JellyfinStore
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository

/** The pure half of the Trakt sync (DPTV v2.9.1), kept apart so it is tested. */
object TraktSyncRules {

    /** A VOD title counts as watched from here (DPTV's own "Mark watched" writes 1 of 1). */
    const val WatchedFraction = 0.9

    fun isWatched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs.toDouble() / durationMs >= WatchedFraction

    fun movieKey(tmdb: Int) = "m:$tmdb"
    fun episodeKey(showTmdb: Int, season: Int, episode: Int) = "e:$showTmdb:$season:$episode"

    /** The episodes Trakt has for a show, found by its TMDB id or else its TVDB id. */
    fun showEpisodes(w: TraktWatched, tmdb: Int?, tvdb: Int?): Set<Pair<Int, Int>> =
        (tmdb?.let { w.showEpisodes[it] } ?: tvdb?.let { w.showEpisodesByTvdb[it] }).orEmpty()

    fun movieWatched(w: TraktWatched, tmdb: Int?, imdb: String?): Boolean =
        (tmdb != null && tmdb in w.movieTmdbIds) || (imdb != null && imdb in w.movieImdbIds)

    /** TMDB ids of everything Trakt has as watched, "m:<id>" and "s:<id>", for Home's recommendations. */
    fun watchedTmdbKeys(w: TraktWatched): Set<String> =
        w.movieTmdbIds.map { "m:$it" }.toSet() + w.showEpisodes.keys.map { "s:$it" }

    /** At most this many marks are written per sync, so a first sync of a big library spreads out. */
    const val MaxMarksPerSync = 400
}

/**
 * Trakt's watched history and watchlist, both ways (DPTV v2.9.1).
 *
 * - Jellyfin: a film or episode Trakt has as watched is marked played on the Jellyfin server, and one
 *   played on Jellyfin goes to Trakt's history.
 * - DPTV's own Movies and TV Shows: a title watched past 90% goes to Trakt; one Trakt has as watched is
 *   marked watched here, when DPTV already knows the title's TMDB id (it has shown its details).
 * - The watchlist is read for Home's Watchlist row.
 *
 * Marks only ever add: un-watching on one side is not copied to the other. Runs when the app opens and
 * goes to the background, every 3 hours, and after Jellyfin playback. Never logs a token or a title.
 */
class TraktSync(
    private val store: TraktStore,
    private val trakt: TraktClient,
    private val jellyfinStore: JellyfinStore,
    private val jellyfin: JellyfinClient,
    private val settings: SettingsRepository,
    private val progressDao: ProgressDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val metadataDao: MetadataDao,
) {
    enum class Outcome { OFF, SYNCED, SIGNED_OUT, FAILED }

    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    suspend fun sync(why: String): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            _running.value = true
            try {
                syncLocked().also { Log.i(TAG, "trakt sync why=$why outcome=$it") }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "trakt sync failed why=$why: ${e.javaClass.simpleName}")
                Outcome.FAILED
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun syncLocked(): Outcome {
        val auth = store.current()?.takeIf { it.signedIn } ?: return Outcome.OFF
        if (!trakt.ensureFresh()) return Outcome.SIGNED_OUT
        val watched = trakt.watched()
        val pushed = store.pushed().toMutableSet()
        val outMovies = ArrayList<TraktIds>()
        val outEpisodes = ArrayList<TraktEpisodeRef>()
        val queued = HashSet<String>()
        var marks = 0

        fun queueMovie(ids: TraktIds) {
            val tmdb = ids.tmdb ?: return
            val key = TraktSyncRules.movieKey(tmdb)
            if (key in pushed || !queued.add(key)) return
            outMovies += ids
        }
        fun queueEpisode(show: TraktIds, season: Int, episode: Int) {
            val tmdb = show.tmdb ?: return
            val key = TraktSyncRules.episodeKey(tmdb, season, episode)
            if (key in pushed || !queued.add(key)) return
            outEpisodes += TraktEpisodeRef(show, season, episode)
        }

        // Jellyfin, both ways.
        if (jellyfinStore.current() != null) {
            runCatching {
                for (m in jellyfin.everything(JellyfinClient.Shelf.MOVIES)) {
                    val onTrakt = TraktSyncRules.movieWatched(watched, m.tmdbId, m.imdbId)
                    if (onTrakt && !m.played && marks < TraktSyncRules.MaxMarksPerSync) {
                        if (runCatching { jellyfin.setPlayed(m.id, true) }.isSuccess) marks++
                    } else if (m.played && !onTrakt) {
                        queueMovie(TraktIds(tmdb = m.tmdbId, imdb = m.imdbId))
                    }
                }
                val shows = jellyfin.everything(JellyfinClient.Shelf.SHOWS).associateBy { it.id }
                for (e in jellyfin.allEpisodes()) {
                    val show = e.seriesId?.let { shows[it] } ?: continue
                    val season = e.seasonNumber ?: continue
                    val number = e.episodeNumber ?: continue
                    val onTrakt = (season to number) in TraktSyncRules.showEpisodes(watched, show.tmdbId, show.tvdbId)
                    if (onTrakt && !e.played && marks < TraktSyncRules.MaxMarksPerSync) {
                        if (runCatching { jellyfin.setPlayed(e.id, true) }.isSuccess) marks++
                    } else if (e.played && !onTrakt) {
                        queueEpisode(showIds(show), season, number)
                    }
                }
            }.onFailure { Log.w(TAG, "jellyfin side skipped: ${it.javaClass.simpleName}") }
        }

        // DPTV's own Movies and TV Shows.
        val pid = settings.activeProfileId.first()
        if (pid >= 0) {
            runCatching { syncVod(pid, watched, ::queueMovie, ::queueEpisode, marksLeft = TraktSyncRules.MaxMarksPerSync - marks) }
                .onFailure { Log.w(TAG, "vod side skipped: ${it.javaClass.simpleName}") }
        }

        // Send in parts Trakt takes comfortably.
        outMovies.chunked(PUSH_CHUNK).forEach { part ->
            trakt.addHistory(part, emptyList())
            part.forEach { ids -> ids.tmdb?.let { pushed += TraktSyncRules.movieKey(it) } }
        }
        outEpisodes.chunked(PUSH_CHUNK).forEach { part ->
            trakt.addHistory(emptyList(), part)
            part.forEach { r -> r.show.tmdb?.let { pushed += TraktSyncRules.episodeKey(it, r.season, r.episode) } }
        }

        val list = runCatching { trakt.watchlist() }.getOrElse { store.watchlist.first() }
        store.afterSync(pushed, list, TraktSyncRules.watchedTmdbKeys(watched), System.currentTimeMillis())
        return Outcome.SYNCED
    }

    private suspend fun syncVod(
        pid: Long,
        watched: TraktWatched,
        queueMovie: (TraktIds) -> Unit,
        queueEpisode: (TraktIds, Int, Int) -> Unit,
        marksLeft: Int,
    ) {
        val progress = progressDao.getForProfile(pid)
        val have = progress.map { it.mediaType to it.itemId }.toSet()
        // Up: what was watched here.
        for (p in progress) {
            if (!TraktSyncRules.isWatched(p.positionMs, p.durationMs)) continue
            when (p.mediaType) {
                MediaType.MOVIE -> {
                    val movie = movieDao.getById(p.itemId) ?: continue
                    val tmdb = tmdbOf(MetadataRepository.movieLocalKey(movie)) ?: continue
                    if (tmdb !in watched.movieTmdbIds) queueMovie(TraktIds(tmdb = tmdb))
                }
                MediaType.EPISODE -> {
                    val ep = seriesDao.getEpisodeById(p.itemId) ?: continue
                    val series = seriesDao.getSeriesById(ep.seriesId) ?: continue
                    val tmdb = tmdbOf(MetadataRepository.seriesLocalKey(series)) ?: continue
                    if ((ep.seasonNumber to ep.episodeNumber) !in watched.showEpisodes[tmdb].orEmpty()) {
                        queueEpisode(TraktIds(tmdb = tmdb), ep.seasonNumber, ep.episodeNumber)
                    }
                }
                else -> Unit
            }
        }
        // Down: what Trakt has, for titles DPTV already knows the TMDB id of.
        var left = marksLeft
        if (left <= 0) return
        if (watched.movieTmdbIds.isNotEmpty()) {
            for (match in metadataDao.matchesForTmdbIds(MetadataRepository.TYPE_MOVIE, watched.movieTmdbIds.toList())) {
                val (sourceId, key) = TrendingLibraryMatcher.parseLocalKey(match.localKey) ?: continue
                for (m in movieDao.findByRemoteIds(sourceId, listOf(key))) {
                    if ((MediaType.MOVIE to m.id) in have || left <= 0) continue
                    progressDao.insertIfAbsent(PlaybackProgressEntity(profileId = pid, mediaType = MediaType.MOVIE, itemId = m.id, positionMs = 1L, durationMs = 1L))
                    left--
                }
            }
        }
        if (watched.showEpisodes.isNotEmpty() && left > 0) {
            for (match in metadataDao.matchesForTmdbIds(MetadataRepository.TYPE_TV, watched.showEpisodes.keys.toList())) {
                val tmdb = match.tmdbId ?: continue
                val (sourceId, key) = TrendingLibraryMatcher.parseLocalKey(match.localKey) ?: continue
                for (s in seriesDao.findSeriesByRemoteIds(sourceId, listOf(key))) {
                    for ((season, number) in watched.showEpisodes[tmdb].orEmpty()) {
                        if (left <= 0) return
                        val ep = seriesDao.findEpisodeByNumber(s.id, season, number) ?: continue
                        if ((MediaType.EPISODE to ep.id) in have) continue
                        progressDao.insertIfAbsent(PlaybackProgressEntity(profileId = pid, mediaType = MediaType.EPISODE, itemId = ep.id, positionMs = 1L, durationMs = 1L))
                        left--
                    }
                }
            }
        }
    }

    /** The TMDB id the metadata layer matched this item to, the adult or the kids variant. */
    private suspend fun tmdbOf(localKey: String): Int? =
        metadataDao.getMatches(listOf(localKey, MetadataRepository.maturityKey(localKey))).firstNotNullOfOrNull { it.tmdbId }

    private fun showIds(show: JellyfinItem) = TraktIds(tmdb = show.tmdbId, imdb = show.imdbId, tvdb = show.tvdbId)

    companion object {
        private const val TAG = "TraktSync"
        private const val PUSH_CHUNK = 200
        private const val WORK_NAME = "trakt-sync"
        private const val PERIODIC = "trakt-sync-periodic"
        internal const val KEY_WHY = "why"

        /** Queue a sync, after any already queued, when online. */
        fun enqueue(context: Context, why: String) {
            runCatching {
                val wm = WorkManager.getInstance(context)
                wm.enqueueUniqueWork(
                    WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<TraktSyncWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInputData(androidx.work.workDataOf(KEY_WHY to why))
                        .build(),
                )
                wm.enqueueUniquePeriodicWork(
                    PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<TraktSyncWorker>(3, TimeUnit.HOURS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInputData(androidx.work.workDataOf(KEY_WHY to "periodic"))
                        .build(),
                )
            }
        }
    }
}

/** Runs [TraktSync.sync] for WorkManager. */
class TraktSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val sync: TraktSync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        sync.sync(inputData.getString(TraktSync.KEY_WHY) ?: "work")
        return Result.success()
    }
}
