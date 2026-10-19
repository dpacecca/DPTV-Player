package tv.own.owntv.core.metadata

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Trakt parsing, list-URL parsing and the client's headers, cache and failure handling (v2.4.0). */
class TraktClientTest {

    // --- parser ---

    @Test
    fun `trending items parse with their ids`() {
        val body = """
            [
              {"watchers": 21, "movie": {"title": "Dune: Part Two", "year": 2024,
                "ids": {"trakt": 1, "slug": "dune-part-two-2024", "imdb": "tt15239678", "tmdb": 693134}}},
              {"watchers": 9, "movie": {"title": "No Ids", "year": null, "ids": {"trakt": 2, "imdb": null, "tmdb": null}}}
            ]
        """.trimIndent()
        val titles = TraktParser.parseItems(body, TraktKind.MOVIES)!!
        assertEquals(2, titles.size)
        assertEquals(TraktTitle("Dune: Part Two", 2024, 1L, "dune-part-two-2024", "tt15239678", 693134), titles[0])
        assertEquals(TraktTitle("No Ids", null, 2L, null, null, null), titles[1])
    }

    @Test
    fun `list items keep only the row's kind, in order, once each`() {
        val body = """
            [
              {"rank": 1, "type": "show", "show": {"title": "Severance", "year": 2022, "ids": {"trakt": 10, "tmdb": 95396}}},
              {"rank": 2, "type": "movie", "movie": {"title": "A Film", "year": 2020, "ids": {"trakt": 11}}},
              {"rank": 3, "type": "person", "person": {"name": "Somebody"}},
              {"rank": 4, "type": "show", "show": {"title": "The Bear", "year": 2022, "ids": {"trakt": 12, "tmdb": 136315}}},
              {"rank": 5, "type": "show", "show": {"title": "Severance", "year": 2022, "ids": {"trakt": 10, "tmdb": 95396}}}
            ]
        """.trimIndent()
        assertEquals(listOf("Severance", "The Bear"), TraktParser.parseItems(body, TraktKind.SHOWS)!!.map { it.title })
        assertEquals(listOf("A Film"), TraktParser.parseItems(body, TraktKind.MOVIES)!!.map { it.title })
    }

    @Test
    fun `a body that is not an array is a failure`() {
        assertNull(TraktParser.parseItems("""{"error": "invalid api key"}""", TraktKind.MOVIES))
        assertNull(TraktParser.parseItems("<html>", TraktKind.MOVIES))
    }

    // --- list references ---

    @Test
    fun `user list URLs and the short form parse`() {
        val expected = TraktListRef.User("someone", "best-of-2024")
        listOf(
            "https://trakt.tv/users/someone/lists/best-of-2024",
            "https://trakt.tv/users/someone/lists/best-of-2024/",
            "https://trakt.tv/users/someone/lists/best-of-2024?sort=rank,asc",
            "http://www.trakt.tv/users/someone/lists/best-of-2024",
            "https://app.trakt.tv/users/someone/lists/best-of-2024",
            "trakt.tv/users/someone/lists/best-of-2024",
            "  someone/best-of-2024  ",
            "users/someone/lists/best-of-2024",
        ).forEach { assertEquals(it, expected, TraktListRef.parse(it)) }
        assertEquals("users/someone/lists/best-of-2024/items/movies", expected.itemsPath(TraktKind.MOVIES))
    }

    @Test
    fun `official and id lists parse`() {
        assertEquals(TraktListRef.Single("marvel-cinematic-universe"), TraktListRef.parse("https://trakt.tv/lists/official/marvel-cinematic-universe"))
        assertEquals(TraktListRef.Single("1248149"), TraktListRef.parse("https://trakt.tv/lists/1248149"))
        assertEquals("lists/1248149/items/shows", TraktListRef.Single("1248149").itemsPath(TraktKind.SHOWS))
    }

    @Test
    fun `anything else is not a list`() {
        listOf(
            "", "   ", "justone", "https://example.com/users/a/lists/b", "https://trakt.tv/movies/dune-2021",
            "https://trakt.tv/users/a", "a b/c", "trakt.tv/a/b",
        ).forEach { assertNull(it, TraktListRef.parse(it)) }
    }

    // --- client ---

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private var status = 200
    private var body = """[{"watchers": 1, "movie": {"title": "Arrival", "year": 2016, "ids": {"trakt": 5, "tmdb": 329865}}}]"""
    private var clock = 0L

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests += exchange.requestURI.toString() to exchange.requestHeaders.mapValues { it.value.first() }
                    .mapKeys { it.key.lowercase() }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @After
    fun stop() = server.stop(0)

    private fun client(config: TraktConfig) = TraktClient(
        http = OkHttpClient(),
        config = { config },
        now = { clock },
        baseUrl = "http://127.0.0.1:${server.address.port}",
    )

    @Test
    fun `trending is asked with the version and key headers`() = runBlocking {
        val titles = client(TraktConfig(clientId = "dummy-client-id")).titles(TraktKind.MOVIES)
        assertEquals(listOf("Arrival"), titles.map { it.title })
        val (path, headers) = requests.single()
        assertEquals("/movies/trending?limit=40", path)
        assertEquals("2", headers["trakt-api-version"])
        assertEquals("dummy-client-id", headers["trakt-api-key"])
    }

    @Test
    fun `a set list is asked instead of trending`() = runBlocking {
        val config = TraktConfig(clientId = "dummy-client-id", showsList = "https://trakt.tv/users/someone/lists/my-shows")
        client(config).titles(TraktKind.SHOWS)
        assertEquals("/users/someone/lists/my-shows/items/shows?limit=100", requests.single().first)
    }

    @Test
    fun `results are held for thirty minutes`() = runBlocking {
        val trakt = client(TraktConfig(clientId = "dummy-client-id"))
        trakt.titles(TraktKind.MOVIES)
        clock += TraktClient.CACHE_TTL_MS - 1
        trakt.titles(TraktKind.MOVIES)
        assertEquals(1, requests.size)
        clock += 1
        trakt.titles(TraktKind.MOVIES)
        assertEquals(2, requests.size)
    }

    @Test
    fun `no key asks nothing, a failure is empty and not retried at once`() = runBlocking {
        assertTrue(client(TraktConfig()).titles(TraktKind.MOVIES).isEmpty())
        assertTrue(requests.isEmpty())

        status = 401
        val trakt = client(TraktConfig(clientId = "dummy-client-id"))
        assertTrue(trakt.titles(TraktKind.MOVIES).isEmpty())
        assertTrue(trakt.titles(TraktKind.MOVIES).isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun `a list that is not understood asks nothing`() = runBlocking {
        val trakt = client(TraktConfig(clientId = "dummy-client-id", moviesList = "not a list"))
        assertTrue(trakt.titles(TraktKind.MOVIES).isEmpty())
        assertTrue(requests.isEmpty())
    }
}
