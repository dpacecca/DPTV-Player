package tv.own.owntv.core.recording

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.timeshift.TimeshiftServer
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** The client of the recording server's live buffer, against a stand-in server on loopback. */
class ServerTimeshiftTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()

    /** The server's clock runs a minute ahead of this device's. */
    private val skewMs = 60_000L
    private val serverNow = System.currentTimeMillis() + skewMs
    private var pieces: List<LongArray> = emptyList()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/timeshift") { ex ->
            val path = ex.requestURI.path
            requests += "${ex.requestMethod} $path${ex.requestURI.query?.let { "?$it" }.orEmpty()} ${ex.requestHeaders.getFirst("Authorization")}"
            val bytes = when {
                path.endsWith("/stream") -> "piece-bytes".toByteArray()
                else -> state().toString().toByteArray()
            }
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        // Three pieces of 2 s ending 4 s ago, the third after a gap of 10 s.
        val t = serverNow - 30_000
        pieces = listOf(longArrayOf(t, 2_000, 0), longArrayOf(t + 2_000, 2_000, 0), longArrayOf(t + 14_000, 2_000, 1))
    }

    @After
    fun stop() = server.stop(0)

    private fun state() = JSONObject()
        .put("id", "abc")
        .put("now", serverNow)
        .put("closed", false)
        .put("pieces", JSONArray().apply { pieces.forEach { p -> put(JSONArray().put(p[0]).put(p[1]).put(p[2])) } })

    private fun timeshift(configured: Boolean = true) = ServerTimeshift(OkHttpClient()) {
        if (configured) RemoteRecorder.Config("http://127.0.0.1:${server.address.port}", "key-1") else null
    }

    @Test
    fun `no server set up keeps the copy on the device`() = runBlocking {
        assertNull(timeshift(configured = false).open("42", "1:7", 60, 2_000))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `pieces are moved onto this device's clock and read back from where they start`() = runBlocking {
        val buffer = timeshift().open("42", "1:7", 60, 5_000) as ServerTimeshift.Buffer
        assertTrue(requests.first().startsWith("POST /api/timeshift Bearer key-1"))
        // The server's clock is ahead; on this device the copy ends in the past, not a minute from now.
        // (The skew is measured from the server's answer, so it is right to within the time a request takes.)
        val first = buffer.oldestWallMs()!!
        assertTrue(kotlin.math.abs(first - (pieces[0][0] - skewMs)) < 1_000)
        assertTrue(buffer.playableEdgeWallMs()!! <= System.currentTimeMillis())
        assertEquals(listOf((first + 4_000) until (first + 14_000)), buffer.gaps())

        // A rewind to the middle of the second piece starts at the second piece, asked for in the server's time.
        val index = buffer.pieceAt(first + 3_000)
        assertEquals(first + 2_000, index)
        val text = buffer.open(index).use { it.readBytes().decodeToString() }
        assertEquals("piece-bytes", text)
        assertEquals(first + 2_000, buffer.readingFromWallMs)
        assertTrue(requests.any { it.startsWith("GET /api/timeshift/abc/stream?from=${pieces[1][0]} ") })

        // The live edge is the piece still being written, which starts where the newest finished one ends.
        buffer.open(null).close()
        assertEquals(first + 16_000, buffer.readingFromWallMs)
        assertTrue(requests.any { it.startsWith("GET /api/timeshift/abc/stream?live=1 ") })

        // Through the loopback server, as the player reads it.
        val url = TimeshiftServer.urlFor(buffer, index)
        assertEquals("piece-bytes", URL(url).openStream().use { it.readBytes().decodeToString() })

        buffer.close()
        assertTrue(buffer.isClosed)
        val deadline = System.currentTimeMillis() + 3_000
        while (requests.none { it.startsWith("DELETE /api/timeshift/abc") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(requests.any { it.startsWith("DELETE /api/timeshift/abc") })
    }

    @Test
    fun `a buffer that never gets a piece is given up and stopped`() = runBlocking {
        pieces = emptyList()
        assertNull(timeshift().open("42", "1:7", 60, 1_200))
        val deadline = System.currentTimeMillis() + 3_000
        while (requests.none { it.startsWith("DELETE") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertNotNull(requests.firstOrNull { it.startsWith("DELETE /api/timeshift/abc") })
    }
}
