package tv.own.owntv.core.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtSeasonInfo
import tv.own.owntv.core.parser.XtTitleDetails
import tv.own.owntv.core.parser.XtreamClient

/**
 * (DPTV v2.9.26) What an Xtream provider says about a film or show beyond its list entry: genres,
 * cast, director, backdrop, trailer, running time and year (`get_vod_info` / `get_series_info` `info`),
 * and a show's seasons (name, synopsis, cover). The fallback for what TMDB does not know, or all there
 * is when TMDB is off.
 *
 * Fetched when a title's page or details open, never while browsing, and kept in memory only (for
 * [TtlMs], at most [MaxEntries] titles): nothing is written to the database, so no catalogue schema
 * changes and a sync never has to keep it fresh. A failed fetch reads as nothing.
 */
class ProviderDetailsRepository(
    private val sourceDao: SourceDao,
    private val xtream: XtreamClient,
) {
    /** A show's provider details and its seasons by number. */
    data class Series(val details: XtTitleDetails?, val seasons: Map<Int, XtSeasonInfo>)

    private class Entry<T>(val at: Long, val value: T)

    private val movies = LinkedHashMap<Long, Entry<XtTitleDetails?>>(MaxEntries, 0.75f, true)
    private val shows = LinkedHashMap<Long, Entry<Series?>>(MaxEntries, 0.75f, true)

    suspend fun movie(movie: MovieEntity): XtTitleDetails? {
        cached(movies, movie.id)?.let { return it.value }
        val remoteId = movie.remoteId?.takeIf { it.isNotBlank() } ?: return null
        val value = fetch(movie.sourceId) { source -> xtream.getVodInfo(source, remoteId) }
        put(movies, movie.id, value)
        return value
    }

    suspend fun series(series: SeriesEntity): Series? {
        cached(shows, series.id)?.let { return it.value }
        val remoteId = series.remoteId?.takeIf { it.isNotBlank() } ?: return null
        val value = fetch(series.sourceId) { source ->
            val info = xtream.getSeriesInfo(source, remoteId)
            Series(info.details, info.seasons.associateBy { it.number })
        }
        put(shows, series.id, value)
        return value
    }

    /** [block] against an Xtream source by id, on IO; null for any other source or on failure. */
    private suspend fun <T> fetch(sourceId: Long, block: suspend (SourceEntity) -> T?): T? = withContext(Dispatchers.IO) {
        try {
            val source = sourceDao.getById(sourceId)?.takeIf { it.type == SourceType.XTREAM }
            source?.let { block(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "provider details failed sourceId=$sourceId: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun <T> cached(map: LinkedHashMap<Long, Entry<T>>, id: Long): Entry<T>? = synchronized(map) {
        map[id]?.takeIf { System.currentTimeMillis() - it.at < TtlMs }
    }

    private fun <T> put(map: LinkedHashMap<Long, Entry<T>>, id: Long, value: T) = synchronized(map) {
        map[id] = Entry(System.currentTimeMillis(), value)
        while (map.size > MaxEntries) map.remove(map.keys.first())
    }

    private companion object {
        const val TAG = "ProviderDetails"
        const val TtlMs = 6L * 60L * 60L * 1000L
        const val MaxEntries = 64
    }
}
