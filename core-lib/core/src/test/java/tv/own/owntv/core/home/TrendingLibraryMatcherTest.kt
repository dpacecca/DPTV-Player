package tv.own.owntv.core.home

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.trending.TrendingMatcher

/** Matching TMDB's Home trending titles to playlist entries (v2.4.0). */
class TrendingLibraryMatcherTest {

    private data class Item(val id: Long, val name: String)

    private var nextId = 9000

    private fun title(name: String, year: Int? = null, tmdb: Int? = null) = TrendingCandidate(
        tmdbId = tmdb ?: nextId++, type = MetadataType.MOVIE, localizedTitle = name, originalTitle = null, year = year,
        overview = null, posterPath = null, backdropPath = null, rating = null, popularity = 0.0, trendingRank = 99,
    )

    @Test
    fun `local keys give the source and the item key`() {
        assertEquals(12L to "tt-remote", TrendingLibraryMatcher.parseLocalKey("movie:12:tt-remote"))
        assertEquals(12L to "tt-remote", TrendingLibraryMatcher.parseLocalKey("movie:12:tt-remote:kids"))
        assertEquals(3L to "Name: With Colon", TrendingLibraryMatcher.parseLocalKey("tv:3:Name: With Colon"))
        assertNull(TrendingLibraryMatcher.parseLocalKey("movie:x:remote"))
        assertNull(TrendingLibraryMatcher.parseLocalKey("movie:12"))
        assertNull(TrendingLibraryMatcher.parseLocalKey("movie:12:"))
    }

    @Test
    fun `TMDB order is kept and titles not in the playlists drop out`() {
        val titles = listOf(title("A", tmdb = 1), title("B", tmdb = 2), title("C"), title("D"))
        val out = TrendingLibraryMatcher.assemble(
            titles,
            byTmdbId = mapOf(2 to Item(20, "B")),
            byIndex = mapOf(0 to Item(10, "A"), 3 to Item(40, "D")),
            key = Item::id,
        )
        assertEquals(listOf("A", "B", "D"), out.map { it.first.localizedTitle })
        assertEquals(listOf(10L, 20L, 40L), out.map { it.second.id })
    }

    @Test
    fun `an id match wins over a title match, and an entry is used once`() {
        val titles = listOf(title("Dune", 2021, tmdb = 438631), title("Dune", 1984, tmdb = 841))
        val out = TrendingLibraryMatcher.assemble(
            titles,
            byTmdbId = mapOf(438631 to Item(1, "Dune 2021")),
            // A title match pointing at the entry the id match already took is not used twice.
            byIndex = mapOf(0 to Item(2, "Dune other"), 1 to Item(1, "Dune 2021")),
            key = Item::id,
        )
        assertEquals(listOf(1L), out.map { it.second.id })
    }

    @Test
    fun `title and year matching finds decorated playlist names`() = runBlocking {
        val titles = listOf(title("Arrival", 2016), title("Dune", 2021), title("Not Here", 2020))
        val rows = listOf(row(1, "|EN| Arrival (2016) 4K"), row(2, "DE| Dune (1984)"), row(3, "Dune (2021) FHD"))
        val result = TrendingMatcher.matchMedia(
            candidates = TrendingLibraryMatcher.candidates(titles),
            mediaType = MediaType.MOVIE,
            preferredLanguage = "en",
            limit = titles.size,
            exactLookup = { signatures -> rows.filter { it.titleSignature in signatures } },
            ftsLookup = { _, _ -> emptyList() },
        )
        val byIndex = result.selections.associate { (it.candidate.trendingRank - 1) to it.variant.item.id }
        assertEquals(mapOf(0 to 1L, 1 to 3L), byIndex)
    }

    @Test
    fun `candidates are ranked by their place in the list`() {
        val ranks = TrendingLibraryMatcher.candidates(listOf(title("A"), title("B"), title("C"))).map { it.trendingRank }
        assertEquals(listOf(1, 2, 3), ranks)
    }

    private fun row(id: Long, raw: String): TrendingCatalogRow {
        val parsed = ProviderCatalogMetadataParser.parse(raw, null)
        return TrendingCatalogRow(
            id = id, sourceId = 1, categoryId = null, name = raw, year = null, remoteId = id.toString(), sortOrder = id.toInt(),
            canonicalTitle = parsed.canonicalTitle, titleSignature = parsed.titleSignature, parsedYear = parsed.parsedYear,
            providerLanguage = parsed.language, qualityRank = parsed.qualityRank, advertisedCapabilities = parsed.capabilities,
        )
    }
}
