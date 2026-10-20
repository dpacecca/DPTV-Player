package tv.own.owntv.core.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import tv.own.owntv.core.timeshift.TimeshiftBuffer
import tv.own.owntv.core.timeshift.TimeshiftServer
import tv.own.owntv.core.timeshift.TimeshiftSession
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Pause and rewind live TV kept by the DPTV-Recorder server (DPTV-Player v2.1.5), for a television
 * with no room of its own — a Chromecast. The server copies the channel into short pieces and keeps
 * the rewind window; the player reads them back through [TimeshiftServer] exactly as it reads a copy
 * kept on the device. The server is then the only connection to the provider.
 *
 * Wall times are moved onto this device's clock once, from the server's own `now`, so "behind live"
 * and the rewind counter never depend on the two clocks agreeing.
 */
class ServerTimeshift(
    client: OkHttpClient,
    /** The recording server's address and key; null while none is set up. */
    private val configOf: suspend () -> RemoteRecorder.Config?,
) {
    constructor(client: OkHttpClient, remote: RemoteRecorder) : this(client, { remote.config() })

    private val http = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    /** (v2.5.1) A recording server is set up, so a channel's copy would be kept there, not on this device. */
    suspend fun isSetUp(): Boolean = configOf() != null

    /**
     * Ask the server to keep the channel with Xtream `stream_id` [channelStreamId] for [windowMinutes],
     * and wait up to [firstPieceTimeoutMs] for its first piece. Null — the device keeps the copy, or
     * the channel plays directly — when no server is set up, it refuses, or nothing arrives in time.
     */
    suspend fun open(channelStreamId: String, channelKey: String, windowMinutes: Int, firstPieceTimeoutMs: Long): TimeshiftBuffer? {
        val config = configOf() ?: return null
        val body = JSONObject().put("channelId", channelStreamId).put("windowMinutes", windowMinutes).toString().toRequestBody(JSON)
        val state = runCatching { call(config, Request.Builder().url(config.baseUrl + "/api/timeshift").post(body)) }
            .onFailure { android.util.Log.w(TAG, "the recording server did not start a buffer: ${it.message}") }
            .getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val id = state.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val buffer = Buffer(id, channelKey, config, state.optLong("now", System.currentTimeMillis()) - System.currentTimeMillis())
        buffer.apply(state)
        TimeshiftServer.register(buffer)
        buffer.startPolling()
        // (v2.5.1) A caller that gave up while the server cut its first piece must not leave it running.
        val ready = try {
            buffer.awaitFirstPiece(firstPieceTimeoutMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            buffer.close()
            throw e
        }
        if (!ready) {
            android.util.Log.w(TAG, "the recording server's buffer had nothing after ${firstPieceTimeoutMs}ms")
            buffer.close()
            return null
        }
        return buffer
    }

    private suspend fun call(config: RemoteRecorder.Config, request: Request.Builder): String = withContext(Dispatchers.IO) {
        http.newCall(request.header("Authorization", "Bearer " + config.apiKey).build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw RemoteRecordings.failure(response.code, text)
            text
        }
    }

    /** One channel the server is keeping. [offsetMs] is the server's clock minus this device's. */
    inner class Buffer internal constructor(
        private val id: String,
        override val channelKey: String,
        private val config: RemoteRecorder.Config,
        private val offsetMs: Long,
    ) : TimeshiftBuffer {
        override val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        override val container = TimeshiftSession.Container.TS

        /** The pieces as [start on this device's clock, duration, gap before (1/0)], oldest first. */
        @Volatile private var pieces: List<LongArray> = emptyList()
        @Volatile private var closed = false
        @Volatile override var readingFromWallMs: Long? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Called once when the server stops keeping the channel, or can no longer be reached. */
        @Volatile var onLost: ((String) -> Unit)? = null

        override val isClosed: Boolean get() = closed

        /** (v2.5.1) Completed once the server has answered the DELETE that [close] sends. */
        private val released = kotlinx.coroutines.CompletableDeferred<Unit>()

        /**
         * (v2.5.1) Wait up to [timeoutMs] for the server to let go of the channel after [close]. Only a
         * one-connection playlist waits for this, so the next stream is not the provider's second one.
         */
        suspend fun awaitReleased(timeoutMs: Long): Boolean =
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { released.await() } != null

        internal fun apply(state: JSONObject) {
            val list = state.optJSONArray("pieces")
            if (list != null) {
                pieces = (0 until list.length()).mapNotNull { i ->
                    list.optJSONArray(i)?.let { p -> longArrayOf(p.optLong(0) - offsetMs, p.optLong(1), p.optLong(2)) }
                }
            }
            if (state.optBoolean("closed")) lose(state.optString("error").ifEmpty { "the recording server stopped keeping the channel" })
        }

        internal fun startPolling() {
            scope.launch {
                var failures = 0
                while (isActive && !closed) {
                    delay(POLL_MS)
                    try {
                        apply(JSONObject(call(config, Request.Builder().url("${config.baseUrl}/api/timeshift/$id").get())))
                        failures = 0
                    } catch (e: RemoteRecorderException) {
                        // The server forgot it (restarted, or it stopped by itself): it is not coming back.
                        if (e.kind == RemoteRecorderException.Kind.REFUSED) lose("the recording server stopped keeping the channel")
                        else if (++failures >= MAX_POLL_FAILURES) lose("the recording server cannot be reached")
                    } catch (e: IOException) {
                        if (++failures >= MAX_POLL_FAILURES) lose("the recording server cannot be reached")
                    } catch (_: org.json.JSONException) {
                        if (++failures >= MAX_POLL_FAILURES) lose("the recording server's answer could not be read")
                    }
                }
            }
        }

        internal suspend fun awaitFirstPiece(timeoutMs: Long): Boolean {
            val until = System.currentTimeMillis() + timeoutMs
            while (!closed && pieces.isEmpty()) {
                if (System.currentTimeMillis() > until) return false
                delay(FIRST_PIECE_POLL_MS)
                runCatching { apply(JSONObject(call(config, Request.Builder().url("${config.baseUrl}/api/timeshift/$id").get()))) }
            }
            return !closed
        }

        private fun lose(why: String) {
            if (closed) return
            android.util.Log.w(TAG, "server buffer lost: $why")
            close()
            onLost?.invoke(why)
        }

        override fun open(fromIndex: Long?): InputStream {
            val list = pieces
            val now = System.currentTimeMillis()
            val query = if (fromIndex == null) {
                // The live edge (v2.1.6): the piece the server is still writing, which starts where the
                // newest finished one ends. Starting at that finished piece instead put the picture a
                // piece or two behind live — pieces end on keyframes, often 5–8 s apart.
                readingFromWallMs = playableEdgeWallMs() ?: now
                "?live=1"
            } else {
                val start = list.lastOrNull { it[0] <= fromIndex }?.get(0) ?: list.firstOrNull()?.get(0)
                readingFromWallMs = start ?: now
                start?.let { "?from=${it + offsetMs}" }.orEmpty()
            }
            val response = http.newCall(
                Request.Builder().url("${config.baseUrl}/api/timeshift/$id/stream$query")
                    .header("Authorization", "Bearer " + config.apiKey)
                    .get().build(),
            ).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("the recording server answered ${response.code}")
            }
            return ClosingStream(response)
        }

        override fun pieceAt(wallMs: Long): Long? {
            val list = pieces
            return list.lastOrNull { it[0] <= wallMs }?.get(0) ?: list.firstOrNull()?.get(0)
        }

        override fun oldestWallMs(): Long? = pieces.firstOrNull()?.get(0)

        override fun playableEdgeWallMs(): Long? =
            pieces.lastOrNull()?.let { it[0] + it[1] }?.coerceAtMost(System.currentTimeMillis())

        override fun gaps(): List<LongRange> = pieces.zipWithNext().mapNotNull { (a, b) ->
            val end = a[0] + a[1]
            if (b[2] == 1L && b[0] > end) end until b[0] else null
        }

        /** Stop: the server lets go of the channel, and readers get end-of-stream. */
        fun close() {
            if (closed) return
            closed = true
            scope.cancel()
            TimeshiftServer.unregister(this)
            Thread {
                runCatching {
                    http.newCall(
                        Request.Builder().url("${config.baseUrl}/api/timeshift/$id")
                            .header("Authorization", "Bearer " + config.apiKey).delete().build(),
                    ).execute().close()
                }
                released.complete(Unit)
            }.apply { isDaemon = true }.start()
        }
    }

    /** The response body, closing the response with it. */
    private class ClosingStream(private val response: Response) : FilterInputStream(response.body.byteStream()) {
        override fun close() {
            runCatching { super.close() }
            response.close()
        }
    }

    private companion object {
        const val TAG = "ServerTimeshift"
        const val POLL_MS = 2_000L
        const val FIRST_PIECE_POLL_MS = 500L
        const val MAX_POLL_FAILURES = 5
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
