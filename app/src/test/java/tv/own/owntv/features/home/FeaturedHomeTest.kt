package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.core.home.TrendingPick
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate

/** (v2.6.0) The featured titles at the top of Home: which titles, in which order, and their "All versions". */
class FeaturedHomeTest {

    @Test
    fun `interleave takes film and show in turn, then the rest of the longer list`() {
        assertEquals(listOf("m1", "s1", "m2", "s2", "m3", "m4"), FeaturedHome.interleave(listOf("m1", "m2", "m3", "m4"), listOf("s1", "s2")))
        assertEquals(listOf("s1", "s2"), FeaturedHome.interleave(emptyList(), listOf("s1", "s2")))
        assertEquals(emptyList<String>(), FeaturedHome.interleave(emptyList<String>(), emptyList()))
    }

    @Test
    fun `interleave stops at the limit`() {
        val movies = (1..8).map { "m$it" }
        val shows = (1..8).map { "s$it" }
        val out = FeaturedHome.interleave(movies, shows)
        assertEquals(FeaturedHome.LIMIT, out.size)
        assertEquals(listOf("m1", "s1", "m2", "s2", "m3", "s3", "m4", "s4", "m5", "s5"), out)
        assertEquals(listOf("m1"), FeaturedHome.interleave(movies, shows, limit = 1))
        assertEquals(emptyList<String>(), FeaturedHome.interleave(movies, shows, limit = 0))
    }

    @Test
    fun `select keeps TMDB's order and rank, and the playlist entry behind each title`() {
        val items = FeaturedHome.select(
            movies = listOf(moviePick(tmdbId = 10, rank = 3, entryId = 100), moviePick(tmdbId = 11, rank = 7, entryId = 101)),
            shows = listOf(showPick(tmdbId = 20, rank = 1, entryId = 200)),
        )
        assertEquals(listOf(100L, 200L, 101L), items.map(FeaturedHome::entryId))
        assertTrue(items[0] is TrendingHomeItem.Movie)
        assertTrue(items[1] is TrendingHomeItem.Series)
        // "#3 in Trending · Movies": the rank in TMDB's own list, not the place in the carousel.
        assertEquals(listOf(3, 1, 7), items.map { it.snapshot.trendingRank })
        assertEquals(listOf("MOVIE:10", "SERIES:20", "MOVIE:11"), items.map { it.stableKey })
        assertEquals(5L, items[0].snapshot.sourceId)
    }

    @Test
    fun `select shows a title or a playlist entry once`() {
        val items = FeaturedHome.select(
            movies = listOf(
                moviePick(tmdbId = 10, rank = 1, entryId = 100),
                moviePick(tmdbId = 10, rank = 2, entryId = 101), // the same title again
                moviePick(tmdbId = 12, rank = 3, entryId = 100), // the same entry again
                moviePick(tmdbId = 13, rank = 4, entryId = 103),
            ),
            shows = emptyList(),
        )
        assertEquals(listOf(100L, 103L), items.map(FeaturedHome::entryId))
    }

    @Test
    fun `a featured title takes TMDB's text and backdrop, the playlist's where TMDB has none`() {
        val tmdb = FeaturedHome.select(listOf(moviePick(10, 1, 100, backdrop = "/b.jpg", overview = "TMDB plot", rating = 8.0)), emptyList()).single()
        assertEquals("/b.jpg", tmdb.snapshot.backdropPath)
        assertEquals("TMDB plot", tmdb.snapshot.overview)
        assertEquals(8.0, tmdb.snapshot.rating!!, 0.0)
        assertNull(tmdb.snapshot.trailerKey)

        val bare = FeaturedHome.select(listOf(moviePick(10, 1, 100, overview = " ", rating = 0.0)), emptyList()).single()
        assertNull(bare.snapshot.backdropPath)
        assertEquals("Playlist plot", bare.snapshot.overview)
        assertEquals(6.5, bare.snapshot.rating!!, 0.0)
    }

    @Test
    fun `all versions are the same title within a year, the featured entry first, then by quality`() {
        val rows = listOf(
            row(id = 1, year = 2026, quality = 2),
            row(id = 2, year = 2026, quality = 4),
            row(id = 3, year = 2025, quality = 1), // a year off: still the same release
            row(id = 4, year = 2019, quality = 5), // another film of that name
            row(id = 5, year = null, quality = 3), // no year: kept
            row(id = 2, year = 2026, quality = 4), // listed twice
        )
        val versions = FeaturedHome.versions(featuredId = 1, year = 2026, rows = rows)
        assertEquals(listOf(1L, 2L, 5L, 3L), versions.map { it.id })
    }

    @Test
    fun `without a year every entry of the title is a version`() {
        val rows = listOf(row(id = 1, year = 2019, quality = 1), row(id = 2, year = 2026, quality = 2))
        assertEquals(listOf(2L, 1L), FeaturedHome.versions(featuredId = 2, year = null, rows = rows).map { it.id })
    }

    @Test
    fun `a version is the featured item pointing at the other entry`() {
        val item = FeaturedHome.select(listOf(moviePick(10, 1, 100)), emptyList()).single() as TrendingHomeItem.Movie
        val other = MovieEntity(id = 300, sourceId = 9, name = "4K-DE - Title", streamUrl = "u")
        val version = FeaturedHome.withMovie(item, other)
        assertEquals(300L, FeaturedHome.entryId(version))
        assertEquals(9L, version.snapshot.sourceId)
        assertEquals("4K-DE - Title", version.snapshot.providerRawName)
        assertEquals(item.stableKey, version.stableKey)
    }

    private fun candidate(tmdbId: Int, rank: Int, type: MetadataType, backdrop: String? = null, overview: String? = null, rating: Double? = null) =
        TrendingCandidate(
            tmdbId = tmdbId, type = type, localizedTitle = "Title $tmdbId", originalTitle = null, year = 2026,
            overview = overview, posterPath = null, backdropPath = backdrop, rating = rating, popularity = 0.0, trendingRank = rank,
        )

    private fun moviePick(tmdbId: Int, rank: Int, entryId: Long, backdrop: String? = null, overview: String? = null, rating: Double? = null) =
        TrendingPick(
            item = MovieEntity(id = entryId, sourceId = 5, name = "EN - Title $tmdbId", streamUrl = "u", plot = "Playlist plot", rating = 6.5),
            title = candidate(tmdbId, rank, MetadataType.MOVIE, backdrop, overview, rating),
            posterUrl = null,
        )

    private fun showPick(tmdbId: Int, rank: Int, entryId: Long) =
        TrendingPick(
            item = SeriesEntity(id = entryId, sourceId = 5, name = "EN - Show $tmdbId"),
            title = candidate(tmdbId, rank, MetadataType.TV),
            posterUrl = null,
        )

    private fun row(id: Long, year: Int?, quality: Int) = TrendingCatalogRow(
        id = id, sourceId = 1, categoryId = null, name = "Title", year = year, remoteId = null, sortOrder = 0,
        canonicalTitle = "title", titleSignature = "title", parsedYear = year, providerLanguage = null,
        qualityRank = quality, advertisedCapabilities = null,
    )
}
