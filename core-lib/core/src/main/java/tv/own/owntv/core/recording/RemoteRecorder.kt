package tv.own.owntv.core.recording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.settings.SettingsRepository
import java.io.IOException

/**
 * A DPTV-Recorder server that records on this app's behalf, so a recording happens whether the
 * television is on, asleep or unplugged.
 *
 * The server is a separate, always-on program (Docker on the user's NAS) with the same Xtream login.
 * It records with ffmpeg into its own folder and does catch-up from the provider's archive. This
 * class is only its client: schedule, list, cancel and get a playback link, authenticated with an
 * API key the user generates in the server's Settings → Account → API keys.
 *
 * Configured when both the address and the key are set; otherwise every recording stays on the
 * device exactly as before.
 */
class RemoteRecorder(
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
) {
    /** Where the server is and the key it accepts. [baseUrl] has no trailing slash. */
    data class Config(val baseUrl: String, val apiKey: String)

    /** The server's settings, or null when recording on a server is not set up. */
    suspend fun config(): Config? {
        val url = RemoteRecordings.normalizeBaseUrl(settings.currentRecorderServerUrl()) ?: return null
        val key = settings.currentRecorderApiKey().trim()
        return if (key.isEmpty()) null else Config(url, key)
    }

    /** Check the address and key: the number of recordings the server lists for this key's user. */
    suspend fun test(config: Config): Int = list(config).size

    /**
     * Ask the server to record a programme. [channelStreamId] is the Xtream `stream_id`, which is the
     * server's channel id too. Times are the programme's own; the server adds its own padding, and a
     * programme that has already ended is fetched from the provider's archive (catch-up).
     */
    suspend fun schedule(
        config: Config,
        channelStreamId: String,
        channelName: String,
        title: String,
        programmeStartMs: Long,
        programmeStopMs: Long,
    ): RemoteRecording {
        val body = JSONObject()
            .put("channelId", channelStreamId)
            .put("channelName", channelName)
            .put("programTitle", title)
            .put("programStart", programmeStartMs)
            .put("programStop", programmeStopMs)
            .toString()
            .toRequestBody(JSON)
        val json = call(config, "/api/recordings") { post(body) }
        return runCatching { RemoteRecordings.parse(JSONObject(json)) }.getOrNull()
            ?: throw RemoteRecorderException(RemoteRecorderException.Kind.BAD_RESPONSE)
    }

    /** This key's recordings on the server, newest first (the server lists up to 200). */
    suspend fun list(config: Config): List<RemoteRecording> {
        val body = call(config, "/api/recordings") { get() }
        return runCatching { RemoteRecordings.parseList(body) }.getOrNull()
            ?: throw RemoteRecorderException(RemoteRecorderException.Kind.BAD_RESPONSE)
    }

    /**
     * Cancel a scheduled or running recording, or delete a finished one. The server removes the file
     * in both cases.
     */
    suspend fun remove(config: Config, remoteId: Long, status: RecordingStatus) {
        val finished = status == RecordingStatus.COMPLETED || status == RecordingStatus.FAILED ||
            status == RecordingStatus.CANCELLED || status == RecordingStatus.MISSED
        val query = if (finished) "?status=completed" else ""
        call(config, "/api/recordings/$remoteId$query") { delete() }
    }

    /**
     * A link the player can open directly: short-lived, with no key in it, and seekable. The server
     * makes one per request, so ask again rather than storing it.
     */
    suspend fun playbackUrl(config: Config, remoteId: Long): String {
        val body = call(config, "/api/recordings/$remoteId/stream") { get() }
        val path = runCatching { JSONObject(body).optString("url") }.getOrNull()?.takeIf { it.startsWith("/") }
            ?: throw RemoteRecorderException(RemoteRecorderException.Kind.BAD_RESPONSE)
        return config.baseUrl + path
    }

    /** One request. Network failures surface as [RemoteRecorderException] too, kind UNREACHABLE. */
    private suspend fun call(config: Config, path: String, configure: Request.Builder.() -> Request.Builder): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(config.baseUrl + path)
                .header("Authorization", "Bearer " + config.apiKey)
                .header("Accept", "application/json")
            val request = configure(builder).build()
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) throw RemoteRecordings.failure(response.code, text)
                    text
                }
            } catch (e: RemoteRecorderException) {
                throw e
            } catch (e: IOException) {
                throw RemoteRecorderException(RemoteRecorderException.Kind.UNREACHABLE, e.message)
            }
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** One recording as the server describes it. Times are epoch milliseconds. */
data class RemoteRecording(
    val id: Long,
    val channelId: String,
    val channelName: String,
    val title: String,
    val programmeStartMs: Long,
    val programmeStopMs: Long,
    val startMs: Long,
    val stopMs: Long,
    /** The server's word: scheduled, recording, completed, failed or cancelled. */
    val status: String,
    /** "live", or "catchup" for a programme fetched from the provider's archive. */
    val kind: String,
    val error: String?,
)

/**
 * The pure parts of talking to the server, kept apart so they can be tested without a network.
 *
 * A recording the server is doing is still a row in the recordings table, so the screens list it
 * with everything else. What marks it is its [RecordingEntity.filePath]: `dptv-recorder:<server id>`
 * instead of a file. That needs no database change, and the engine and the alarms skip such rows.
 */
object RemoteRecordings {
    const val MARKER = "dptv-recorder:"

    fun markerFor(remoteId: Long): String = MARKER + remoteId

    /** The server's id for a row the server is recording, or null for a recording on this device. */
    fun remoteIdOf(filePath: String?): Long? =
        filePath?.takeIf { it.startsWith(MARKER) }?.removePrefix(MARKER)?.toLongOrNull()

    fun isRemote(row: RecordingEntity): Boolean = remoteIdOf(row.filePath) != null

    /** The server's status word as this app's status. Anything unknown counts as failed. */
    fun statusOf(server: String): RecordingStatus = when (server.lowercase()) {
        "scheduled" -> RecordingStatus.SCHEDULED
        "recording" -> RecordingStatus.RECORDING
        "completed" -> RecordingStatus.COMPLETED
        "cancelled" -> RecordingStatus.CANCELLED
        else -> RecordingStatus.FAILED
    }

    /**
     * `http://nas:3000/` → `http://nas:3000`. A bare host gets `http://`, because a recorder on the
     * home network is usually plain HTTP. Null for blank or unparseable input.
     */
    fun normalizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Add the scheme before trimming slashes, or "http://" would become the host "http".
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val url = withScheme.trimEnd('/').toHttpUrlOrNull() ?: return null
        return url.toString().trimEnd('/')
    }

    fun parse(json: JSONObject): RemoteRecording? {
        val id = json.optLong("id", -1L)
        if (id <= 0) return null
        return RemoteRecording(
            id = id,
            channelId = json.optString("channel_id"),
            channelName = json.optString("channel_name"),
            title = json.optString("program_title"),
            programmeStartMs = json.optLong("program_start"),
            programmeStopMs = json.optLong("program_stop"),
            startMs = json.optLong("rec_start"),
            stopMs = json.optLong("rec_end"),
            status = json.optString("status"),
            kind = json.optString("kind").ifEmpty { "live" },
            error = json.optString("error").takeIf { !json.isNull("error") && it.isNotEmpty() },
        )
    }

    fun parseList(body: String): List<RemoteRecording> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::parse) }
    }

    /** The server said no: why, with its own `error` text as the detail when it sent one. */
    fun failure(code: Int, body: String): RemoteRecorderException {
        val serverSays = runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotEmpty() }
        val kind = if (code == 401) RemoteRecorderException.Kind.UNAUTHORIZED else RemoteRecorderException.Kind.REFUSED
        return RemoteRecorderException(kind, serverSays ?: code.toString())
    }
}

/**
 * Why a call to the recording server failed. [kind] is what the screens turn into a sentence;
 * [detail] is the server's own message (or the HTTP code / network error), shown after it.
 */
class RemoteRecorderException(val kind: Kind, val detail: String? = null) : IOException(detail ?: kind.name) {
    enum class Kind {
        /** No answer: wrong address, server down, or not on the same network. */
        UNREACHABLE,
        /** The API key was not accepted (HTTP 401). */
        UNAUTHORIZED,
        /** The server answered with an error; [detail] says what. */
        REFUSED,
        /** The answer was not what a DPTV-Recorder sends — probably not a DPTV-Recorder. */
        BAD_RESPONSE,
    }
}
