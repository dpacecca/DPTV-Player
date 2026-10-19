package tv.own.owntv.core.metadata

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's Trending movies / Trending shows from TMDB (v2.4.0): URLs, parsing, country, feed and cache. */
class HomeTrendingTest {

    private val base = "https://api.example"

    // --- URLs ---

    @Test
    fun `on streaming asks discover for flatrate titles in the country by popularity`() {
        val url = TmdbProvider.buildHomeTrendingUrl(
            base, apiKey = "k", language = "en-AU", type = MetadataType.MOVIE,
            query = HomeTrendingQuery(HomeTrendingSource.STREAMING, "AU", englishOnly = false), page = 2,
        )
        assertTrue(url, url.startsWith("$base/3/discover/movie?page=2"))
        assertTrue(url, "&sort_by=popularity.desc" in url)
        assertTrue(url, "&watch_region=AU" in url)
        assertTrue(url, "&with_watch_monetization_types=flatrate" in url)
        assertFalse(url, "with_watch_providers" in url)
        assertFalse(url, "with_original_language" in url)
        assertTrue(url, url.endsWith("&language=en-AU&api_key=k"))
    }

    @Test
    fun `chosen services are joined with a pipe, English only asks for English originals`() {
        val url = TmdbProvider.buildHomeTrendingUrl(
            base, apiKey = null, language = "", type = MetadataType.TV,
            query = HomeTrendingQuery(HomeTrendingSource.STREAMING, "NZ", providerIds = setOf(337, 8), englishOnly = true), page = 1,
        )
        assertTrue(url, url.startsWith("$base/3/discover/tv?page=1"))
        assertTrue(url, "&with_watch_providers=8%7C337" in url)
        assertTrue(url, "&with_original_language=en" in url)
        assertFalse(url, "api_key" in url)
        assertFalse(url, "language=&" in url)
    }

    @Test
    fun `trending this week asks the weekly feed without discover filters`() {
        val url = TmdbProvider.buildHomeTrendingUrl(
            "$base/", apiKey = null, language = "", type = MetadataType.TV,
            query = HomeTrendingQuery(HomeTrendingSource.WEEKLY, "AU", providerIds = setOf(8), englishOnly = true), page = 3,
        )
        assertEquals("$base/3/trending/tv/week?page=3", url)
    }

    @Test
    fun `watch providers are asked per type and country`() {
        assertEquals(
            "$base/3/watch/providers/movie?watch_region=AU&api_key=k",
            TmdbProvider.buildWatchProvidersUrl(base, "k", "", MetadataType.MOVIE, "AU"),
        )
        assertEquals(
            "$base/3/watch/providers/tv?watch_region=GB&language=en-GB",
            TmdbProvider.buildWatchProvidersUrl(base, null, "en-GB", MetadataType.TV, "GB"),
        )
    }

    // --- Parsing ---

    @Test
    fun `discover and trending pages keep the original language`() {
        val body = page(1, 1, listOf(movie(1, "Alpha", "en"), movie(2, "Beta", "ko")))
        val parsed = TmdbTrendingParser.parsePage(MetadataType.MOVIE, 1, body)!!.candidates
        assertEquals(listOf("en", "ko"), parsed.map { it.originalLanguage })
        assertEquals(listOf(1, 2), parsed.map { it.trendingRank })
    }

    @Test
    fun `watch providers parse with the country's priority, dedupe and sort`() {
        val movies = TmdbWatchProviderParser.parse(
            providers(
                provider(8, "Netflix", 5, "AU" to 1),
                provider(337, "Disney Plus", 2),
                provider(0, "Broken", 1),
            ),
            "AU",
        )!!
        assertEquals(listOf(WatchProvider(8, "Netflix", 1), WatchProvider(337, "Disney Plus", 2)), movies)
        val tv = TmdbWatchProviderParser.parse(providers(provider(337, "Disney Plus", 4), provider(119, "Prime Video", 3)), "AU")!!
        val merged = TmdbWatchProviderParser.merge(listOf(movies, tv))
        assertEquals(listOf(8, 337, 119), merged.map { it.id })
        assertNull(TmdbWatchProviderParser.parse("[]", "AU"))
    }

    @Test
    fun `the picker keeps the top services only`() {
        val many = (1..40).map { WatchProvider(it, "P$it", it) }
        assertEquals(TmdbWatchProviderParser.PICKER_LIMIT, TmdbWatchProviderParser.merge(listOf(many)).size)
    }

    // --- Country and config ---

    @Test
    fun `the country is the stored one, else the device's, else AU`() {
        assertEquals("NZ", HomeTrendingCountry.resolve("nz", "DE"))
        assertEquals("DE", HomeTrendingCountry.resolve("", "DE"))
        assertEquals("AU", HomeTrendingCountry.resolve("", ""))
        assertEquals("AU", HomeTrendingCountry.resolve("XX", "419"))
        assertEquals(listOf("AU", "NZ"), HomeTrendingCountry.choices("JP").take(2))
        assertTrue("JP" in HomeTrendingCountry.choices("JP"))
        assertEquals(HomeTrendingCountry.CURATED, HomeTrendingCountry.choices("AU", "zz", null))
    }

    @Test
    fun `services only count on streaming`() {
        val config = HomeTrendingConfig(HomeTrendingSource.WEEKLY, country = "", providerIds = setOf(8), englishOnly = false)
        assertEquals(HomeTrendingQuery(HomeTrendingSource.WEEKLY, "US", emptySet(), false), config.query("US"))
        assertEquals(setOf(8), config.copy(source = HomeTrendingSource.STREAMING).query("US").providerIds)
        assertEquals(HomeTrendingSource.STREAMING, HomeTrendingSource.fromName("nonsense"))
    }

    // --- Feed ---

    private class FakeProvider(
        /** (type, page) → candidates, or null for a failure. */
        val pages: (MetadataType, HomeTrendingQuery, Int) -> TrendingFeedPage?,
    ) : MetadataProvider {
        val calls = ArrayList<Triple<MetadataType, HomeTrendingQuery, Int>>()
        var providerCalls = 0
        override suspend fun homeTrendingPage(type: MetadataType, query: HomeTrendingQuery, page: Int): TrendingFeedPage? {
            calls += Triple(type, query, page)
            return pages(type, query, page)
        }
        override suspend fun watchProviders(type: MetadataType, region: String): List<WatchProvider>? {
            providerCalls++
            return listOf(WatchProvider(if (type == MetadataType.MOVIE) 8 else 9, "S$type", 1))
        }
        override suspend fun trendingPage(type: MetadataType, page: Int): TrendingFeedPage? = null
        override suspend fun searchMovie(title: String, year: Int?, includeAdult: Boolean): List<MetadataSearchResult>? = null
        override suspend fun searchTv(title: String, year: Int?, includeAdult: Boolean): List<MetadataSearchResult>? = null
        override suspend fun movieDetails(tmdbId: Int): MovieDetails? = null
        override suspend fun tvDetails(tmdbId: Int): MovieDetails? = null
        override suspend fun tvSeasonBundle(tvId: Int, seasons: List<Int>): List<SeasonEpisode>? = null
    }

    /** 20 titles per page, every [koreanEvery]th not English. */
    private fun feedPage(type: MetadataType, page: Int, totalPages: Int = 10, koreanEvery: Int = 0) = TrendingFeedPage(
        page = page,
        totalPages = totalPages,
        candidates = (1..20).map { i ->
            val rank = (page - 1) * 20 + i
            candidate(rank, type, if (koreanEvery > 0 && rank % koreanEvery == 0) "ko" else "en")
        },
    )

    private var clock = 0L

    private fun feed(
        provider: MetadataProvider,
        metadata: MetadataConfig = MetadataConfig(),
        trending: () -> HomeTrendingConfig = { HomeTrendingConfig() },
    ) = HomeTrendingFeed(provider, { metadata }, { trending() }, deviceCountry = { "AU" }, now = { clock })

    @Test
    fun `on streaming reads two pages and holds them for thirty minutes`() = runBlocking {
        val fake = FakeProvider { type, _, page -> feedPage(type, page) }
        val feed = feed(fake)
        assertEquals(40, feed.titles(MetadataType.MOVIE).size)
        assertEquals(listOf(1, 2), fake.calls.map { it.third })
        assertEquals(HomeTrendingQuery(HomeTrendingSource.STREAMING, "AU", emptySet(), true), fake.calls.first().second)
        clock += HomeTrendingFeed.CACHE_TTL_MS - 1
        feed.titles(MetadataType.MOVIE)
        assertEquals(2, fake.calls.size)
        clock += 1
        feed.titles(MetadataType.MOVIE)
        assertEquals(4, fake.calls.size)
    }

    @Test
    fun `a changed setting misses the cache`() = runBlocking {
        val fake = FakeProvider { type, _, page -> feedPage(type, page) }
        var config = HomeTrendingConfig()
        val feed = feed(fake, trending = { config })
        feed.titles(MetadataType.TV)
        config = config.copy(country = "NZ")
        feed.titles(MetadataType.TV)
        config = config.copy(providerIds = setOf(8))
        feed.titles(MetadataType.TV)
        config = config.copy(englishOnly = false)
        feed.titles(MetadataType.TV)
        config = config.copy(source = HomeTrendingSource.WEEKLY)
        feed.titles(MetadataType.TV)
        assertEquals(10, fake.calls.size)
        assertEquals(listOf("AU", "NZ", "NZ", "NZ", "NZ"), fake.calls.filter { it.third == 1 }.map { it.second.region })
    }

    @Test
    fun `trending this week with English only keeps English originals and reads up to four pages`() = runBlocking {
        // Every second title is Korean: four pages give 40 English ones.
        val fake = FakeProvider { type, _, page -> feedPage(type, page, koreanEvery = 2) }
        val feed = feed(fake, trending = { HomeTrendingConfig(source = HomeTrendingSource.WEEKLY) })
        val titles = feed.titles(MetadataType.MOVIE)
        assertEquals(40, titles.size)
        assertTrue(titles.all { it.originalLanguage == "en" })
        assertEquals(listOf(1, 2, 3, 4), fake.calls.map { it.third })
    }

    @Test
    fun `trending this week without the filter reads two pages and keeps every language`() = runBlocking {
        val fake = FakeProvider { type, _, page -> feedPage(type, page, koreanEvery = 2) }
        val feed = feed(fake, trending = { HomeTrendingConfig(source = HomeTrendingSource.WEEKLY, englishOnly = false) })
        val titles = feed.titles(MetadataType.MOVIE)
        assertEquals(40, titles.size)
        assertEquals(listOf(1, 2), fake.calls.map { it.third })
        assertTrue(titles.any { it.originalLanguage == "ko" })
    }

    @Test
    fun `English only stops reading once the row is full, and at the last page`() = runBlocking {
        val full = FakeProvider { type, _, page -> feedPage(type, page) }
        feed(full, trending = { HomeTrendingConfig(source = HomeTrendingSource.WEEKLY) }).titles(MetadataType.MOVIE)
        assertEquals(listOf(1, 2), full.calls.map { it.third })
        val short = FakeProvider { type, _, page -> feedPage(type, page, totalPages = 1) }
        feed(short).titles(MetadataType.MOVIE)
        assertEquals(listOf(1), short.calls.map { it.third })
    }

    @Test
    fun `metadata off asks nothing`() = runBlocking {
        val fake = FakeProvider { type, _, page -> feedPage(type, page) }
        val feed = feed(fake, metadata = MetadataConfig(mode = MetadataMode.PROVIDER))
        assertTrue(feed.titles(MetadataType.MOVIE).isEmpty())
        assertTrue(feed.watchProviders("AU").isEmpty())
        assertTrue(fake.calls.isEmpty())
        assertEquals(0, fake.providerCalls)
    }

    @Test
    fun `a failure is empty and not retried at once, a later page failing keeps the first`() = runBlocking {
        val dead = FakeProvider { _, _, _ -> null }
        val feed = feed(dead)
        assertTrue(feed.titles(MetadataType.MOVIE).isEmpty())
        assertTrue(feed.titles(MetadataType.MOVIE).isEmpty())
        assertEquals(1, dead.calls.size)
        clock += HomeTrendingFeed.FAILURE_TTL_MS
        feed.titles(MetadataType.MOVIE)
        assertEquals(2, dead.calls.size)

        val half = FakeProvider { type, _, page -> if (page == 1) feedPage(type, page) else null }
        assertEquals(20, feed(half).titles(MetadataType.TV).size)
    }

    @Test
    fun `watch providers merge both lists and are cached`() = runBlocking {
        val fake = FakeProvider { _, _, _ -> null }
        val feed = feed(fake)
        assertEquals(listOf(8, 9), feed.watchProviders("AU").map { it.id })
        feed.watchProviders("AU")
        assertEquals(2, fake.providerCalls)
    }

    // --- Fixtures ---

    private fun candidate(rank: Int, type: MetadataType, lang: String) = TrendingCandidate(
        tmdbId = rank, type = type, localizedTitle = "T$rank", originalTitle = null, year = 2026, overview = null,
        posterPath = "/p$rank.jpg", backdropPath = null, rating = null, popularity = 0.0, trendingRank = rank,
        originalLanguage = lang,
    )

    private fun movie(id: Int, title: String, lang: String) =
        JSONObject().put("id", id).put("title", title).put("original_language", lang).put("release_date", "2026-01-01")

    private fun page(page: Int, totalPages: Int, items: List<JSONObject>) =
        JSONObject().put("page", page).put("total_pages", totalPages).put("results", JSONArray(items)).toString()

    private fun provider(id: Int, name: String, priority: Int, vararg regional: Pair<String, Int>) =
        JSONObject().put("provider_id", id).put("provider_name", name).put("display_priority", priority).apply {
            if (regional.isNotEmpty()) put("display_priorities", JSONObject().apply { regional.forEach { (k, v) -> put(k, v) } })
        }

    private fun providers(vararg items: JSONObject) = JSONObject().put("results", JSONArray(items.toList())).toString()
}
