package tv.own.owntv.core.home

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.metadata.TraktKind
import tv.own.owntv.core.metadata.TraktTitle
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.trending.TrendingMatcher

/** Matching Trakt titles to playlist entries (v2.4.0). */
class TraktLibraryMatcherTest {

    private data class Item(val id: Long, val name: String)

    private fun title(name: String, year: Int? = null, tmdb: Int? = null) = TraktTitle(name, year, null, null, null, tmdb)

    @Test
    fun `local keys give the source and the item key`() {
        assertEquals(12L to "tt-remote", TraktLibraryMatcher.parseLocalKey("movie:12:tt-remote"))
        assertEquals(12L to "tt-remote", TraktLibraryMatcher.parseLocalKey("movie:12:tt-remote:kids"))
        assertEquals(3L to "Name: With Colon", TraktLibraryMatcher.parseLocalKey("tv:3:Name: With Colon"))
        assertNull(TraktLibraryMatcher.parseLocalKey("movie:x:remote"))
        assertNull(TraktLibraryMatcher.parseLocalKey("movie:12"))
        assertNull(TraktLibraryMatcher.parseLocalKey("movie:12:"))
    }

    @Test
    fun `Trakt order is kept and titles not in the playlists drop out`() {
        val titles = listOf(title("A", tmdb = 1), title("B", tmdb = 2), title("C"), title("D"))
        val out = TraktLibraryMatcher.assemble(
            titles,
            byTmdbId = mapOf(2 to Item(20, "B")),
            byIndex = mapOf(0 to Item(10, "A"), 3 to Item(40, "D")),
            key = Item::id,
        )
        assertEquals(listOf("A", "B", "D"), out.map { it.first.title })
        assertEquals(listOf(10L, 20L, 40L), out.map { it.second.id })
    }

    @Test
    fun `an id match wins over a title match, and an entry is used once`() {
        val titles = listOf(title("Dune", 2021, tmdb = 438631), title("Dune", 1984, tmdb = 841))
        val out = TraktLibraryMatcher.assemble(
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
            candidates = TraktLibraryMatcher.candidates(titles, TraktKind.MOVIES),
            mediaType = MediaType.MOVIE,
            preferredLanguage = "en",
            limit = titles.size,
            exactLookup = { signatures -> rows.filter { it.titleSignature in signatures } },
            ftsLookup = { _, _ -> emptyList() },
        )
        val byIndex = result.selections.associate { (it.candidate.trendingRank - 1) to it.variant.item.id }
        assertEquals(mapOf(0 to 1L, 1 to 3L), byIndex)
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
