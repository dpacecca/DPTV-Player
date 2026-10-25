package tv.own.owntv.features.home

import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.TrendingItemEntity
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.core.home.TrendingPick
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType

/**
 * (v2.6.0) The featured titles at the top of Home: the Trending movies / Trending shows picks (TMDB's
 * titles the playlists have), film and show in turn, drawn by the Trending hero. Pure, so it is unit-tested.
 */
object FeaturedHome {
    /** At most this many titles in the carousel. */
    const val LIMIT = 10

    /** The automatic advance while the hero is not focused. */
    const val INTERVAL_MS = 8_000L

    /** Marks the snapshots made here: they never came from the Now trending table. */
    private const val GENERATION = "home-featured"

    /** a[0], b[0], a[1], b[1] …; once one list ends, the rest of the other; at most [limit]. */
    fun <T> interleave(a: List<T>, b: List<T>, limit: Int = LIMIT): List<T> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<T>(minOf(limit, a.size + b.size))
        var i = 0
        while (out.size < limit && (i < a.size || i < b.size)) {
            if (i < a.size) out += a[i]
            if (out.size < limit && i < b.size) out += b[i]
            i++
        }
        return out
    }

    /**
     * The carousel: films and shows interleaved, each in TMDB's order. A title (type and TMDB id) or a
     * playlist entry the list already has is skipped, so no title shows twice.
     */
    fun select(
        movies: List<TrendingPick<MovieEntity>>,
        shows: List<TrendingPick<SeriesEntity>>,
        limit: Int = LIMIT,
    ): List<TrendingHomeItem> {
        val seenMovies = HashSet<Long>()
        val seenShows = HashSet<Long>()
        val seenTitles = HashSet<Int>()
        val films = movies.filter { seenTitles.add(it.title.tmdbId) && seenMovies.add(it.item.id) }.map(::movieItem)
        seenTitles.clear()
        val series = shows.filter { seenTitles.add(it.title.tmdbId) && seenShows.add(it.item.id) }.map(::seriesItem)
        return interleave(films, series, limit)
    }

    fun movieItem(pick: TrendingPick<MovieEntity>): TrendingHomeItem.Movie {
        val m = pick.item
        return TrendingHomeItem.Movie(
            snapshot(pick.title, MediaType.MOVIE, m.sourceId, m.id, m.remoteId, m.name, m.canonicalTitle,
                m.providerLanguage, m.advertisedCapabilities, m.parsedYear ?: m.year, m.plot, m.rating),
            m,
        )
    }

    fun seriesItem(pick: TrendingPick<SeriesEntity>): TrendingHomeItem.Series {
        val s = pick.item
        return TrendingHomeItem.Series(
            snapshot(pick.title, MediaType.SERIES, s.sourceId, s.id, s.remoteId, s.name, s.canonicalTitle,
                s.providerLanguage, s.advertisedCapabilities, s.parsedYear ?: s.year, s.plot, s.rating),
            s,
        )
    }

    /** The playlist entry behind an item, whichever kind it is. */
    fun entryId(item: TrendingHomeItem): Long = when (item) {
        is TrendingHomeItem.Movie -> item.movie.id
        is TrendingHomeItem.Series -> item.series.id
    }

    /** [item] pointing at another entry of the same title (one of its "All versions"). */
    fun withMovie(item: TrendingHomeItem.Movie, movie: MovieEntity): TrendingHomeItem.Movie =
        item.copy(movie = movie, snapshot = item.snapshot.forEntry(movie.sourceId, movie.id, movie.remoteId, movie.name))

    fun withSeries(item: TrendingHomeItem.Series, series: SeriesEntity): TrendingHomeItem.Series =
        item.copy(series = series, snapshot = item.snapshot.forEntry(series.sourceId, series.id, series.remoteId, series.name))

    /**
     * "All versions": the entries of the same title (the rows share its title signature) whose year agrees
     * within one, or that have none — each once. The featured entry first, then the best quality, then
     * the playlists' own order.
     */
    fun versions(featuredId: Long, year: Int?, rows: List<TrendingCatalogRow>): List<TrendingCatalogRow> {
        val seen = HashSet<Long>()
        return rows
            .filter { row -> year == null || row.parsedYear == null || kotlin.math.abs(row.parsedYear!! - year) <= 1 }
            .filter { seen.add(it.id) }
            .sortedWith(compareBy<TrendingCatalogRow>({ it.id != featuredId }, { -it.qualityRank }))
    }

    private fun TrendingItemEntity.forEntry(sourceId: Long, id: Long, remoteId: String?, name: String) =
        copy(sourceId = sourceId, providerItemId = id, providerRemoteId = remoteId, providerStableKey = remoteId ?: name, providerRawName = name)

    private fun snapshot(
        title: TrendingCandidate,
        type: MediaType,
        sourceId: Long,
        id: Long,
        remoteId: String?,
        name: String,
        canonical: String,
        language: String?,
        capabilities: String?,
        year: Int?,
        plot: String?,
        rating: Double?,
    ) = TrendingItemEntity(
        sourceId = sourceId,
        position = 0,
        tmdbId = title.tmdbId,
        mediaType = type,
        trendingRank = title.trendingRank,
        providerItemId = id,
        providerRemoteId = remoteId,
        providerStableKey = remoteId ?: name,
        providerRawName = name,
        canonicalTitle = canonical.ifBlank { title.localizedTitle },
        providerLanguage = language,
        advertisedQuality = null,
        advertisedCapabilities = capabilities,
        localizedTitle = title.localizedTitle,
        originalTitle = title.originalTitle,
        year = title.year ?: year,
        overview = title.overview?.takeIf { it.isNotBlank() } ?: plot?.takeIf { it.isNotBlank() },
        posterPath = title.posterPath,
        backdropPath = title.backdropPath,
        // TMDB writes 0 for a title nobody has rated yet.
        rating = title.rating?.takeIf { it > 0.0 } ?: rating?.takeIf { it > 0.0 },
        trailerKey = null,
        generationId = GENERATION,
        refreshedAt = 0L,
    )
}
