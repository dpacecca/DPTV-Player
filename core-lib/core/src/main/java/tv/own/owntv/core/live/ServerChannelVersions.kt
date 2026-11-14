package tv.own.owntv.core.live

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.sync.schedule.SyncStateStore

/**
 * (DPTV v2.9.18) One version of a channel as DPTV-Server keeps it (DPTV-Server 1.0.15): another copy of
 * the channel from a backup playlist, with the server's last check of it.
 */
data class ServerVersion(
    val streamId: String,
    val name: String,
    val height: Int?,
    val fps: Double?,
    /** True passed, false failed (black, frozen, silent, stalled), null not checked yet. */
    val ok: Boolean?,
    val reason: String?,
    val active: Boolean,
    val main: Boolean,
)

/** Reading DPTV-Server's versions of a channel and playing one of them: no Android, testable. */
object ServerVersionRules {
    /** Ids the versions play under on this device: never a real channel's id, so each is tuned afresh. */
    private const val SYNTHETIC_BASE = -1_000_000_000L

    fun syntheticId(streamId: String): Long =
        SYNTHETIC_BASE - (streamId.toLongOrNull() ?: streamId.hashCode().toLong().and(0x7fffffff))

    fun isSynthetic(id: Long): Boolean = id <= SYNTHETIC_BASE

    fun parse(json: String): List<ServerVersion> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            ServerVersion(
                streamId = id,
                name = o.optString("name", ""),
                height = if (o.isNull("height")) null else o.optInt("height"),
                fps = if (o.isNull("fps")) null else o.optDouble("fps"),
                ok = if (o.isNull("ok")) null else o.optBoolean("ok"),
                reason = if (o.isNull("reason")) null else o.optString("reason"),
                active = o.optBoolean("active", false),
                main = o.optBoolean("main", false),
            )
        }
    }.getOrDefault(emptyList())

    /** [streamUrl] with its stream id (the last path part's number) swapped for [streamId]. */
    fun urlFor(streamUrl: String, streamId: String): String? {
        val m = Regex("/(\\d+)(\\.[A-Za-z0-9]+)?(\\?.*)?$").find(streamUrl) ?: return null
        return streamUrl.substring(0, m.range.first) + "/" + streamId + (m.groupValues[2]) + (m.groupValues[3])
    }

    /**
     * [main] played from [v]: the same channel (name, guide, logo, number) on the version's stream. The
     * channel's own entry is [main] itself (the server already sends it to its chosen version).
     */
    fun entityFor(main: ChannelEntity, v: ServerVersion): ChannelEntity? {
        if (v.main || v.streamId == main.remoteId) return main
        val url = urlFor(main.streamUrl, v.streamId) ?: return null
        return main.copy(id = syntheticId(v.streamId), streamUrl = url, remoteId = v.streamId)
    }

    /** What the Sources list says about a version: its picture and the server's last check. */
    fun note(v: ServerVersion): String? = v.reason?.takeIf { it.isNotBlank() }

    /** The version to move to when the one playing keeps stalling: the next that passed (or wasn't checked), not one tried. */
    fun nextAfterStall(versions: List<ServerVersion>, tried: Set<String>): ServerVersion? =
        versions.filter { it.ok != false && it.streamId !in tried }
            .sortedWith(compareBy<ServerVersion> { if (it.ok == true) 0 else 1 }.thenByDescending { it.height ?: 0 })
            .firstOrNull()
}

/**
 * The versions DPTV-Server keeps of a channel from a DPTV-Server playlist (`dptv_versions`), as Sources
 * entries the player tunes like any channel. Empty for other playlists, a server without them, or a
 * channel without backups. Never logs a URL or a login.
 */
class ServerChannelVersions(
    private val http: HttpClient,
    private val xtream: XtreamClient,
    private val sourceDao: SourceDao,
    private val checks: SyncStateStore,
) {
    /** The channel a version entry stands for, so asking from a version still lists the same channel's. */
    private val mainOf = java.util.concurrent.ConcurrentHashMap<Long, ChannelEntity>()

    fun mainChannel(channel: ChannelEntity): ChannelEntity = mainOf[channel.id] ?: channel

    suspend fun versions(channel: ChannelEntity): List<ServerVersion> = withContext(Dispatchers.IO) {
        val main = mainChannel(channel)
        val source = sourceDao.getById(main.sourceId) ?: return@withContext emptyList()
        if (!isDptv(source)) return@withContext emptyList()
        val id = main.remoteId?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        runCatching {
            ServerVersionRules.parse(http.getText(xtream.actionUrl(source, "dptv_versions") + "&stream_id=" + id, source.userAgent))
        }.onFailure { Log.w(TAG, "versions not read: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
    }

    /** [versions] as Sources entries (the channel's own one first), each tunable. */
    suspend fun of(channel: ChannelEntity): List<ChannelVersion> {
        val main = mainChannel(channel)
        return versions(channel).mapNotNull { v ->
            val entity = ServerVersionRules.entityFor(main, v) ?: return@mapNotNull null
            if (entity.id != main.id) mainOf[entity.id] = main
            ChannelVersion(
                channel = entity,
                name = v.name.ifBlank { main.name },
                tags = listOfNotNull(if (v.ok == false) "✕" else null),
                qualityRank = v.height ?: 0,
                categoryName = ServerVersionRules.note(v),
                playlistName = SERVER_LABEL,
            )
        }
    }

    /** The entry to move to when [playing] keeps stalling, or null; [tried] grows with each move. */
    suspend fun next(playing: ChannelEntity, tried: MutableSet<String>): ChannelEntity? {
        val main = mainChannel(playing)
        playing.remoteId?.let { tried += it }
        val list = versions(playing)
        if (list.isEmpty()) return null
        // The channel's own entry plays whatever the server chose: count it as that version.
        list.firstOrNull { it.active }?.let { if (playing.id == main.id) tried += it.streamId }
        val v = ServerVersionRules.nextAfterStall(list, tried) ?: return null
        tried += v.streamId
        return ServerVersionRules.entityFor(main, v)?.also { if (it.id != main.id) mainOf[it.id] = main }
    }

    private suspend fun isDptv(source: SourceEntity): Boolean =
        source.type == SourceType.XTREAM && checks.check(source.id)?.isDptv == true

    private companion object {
        const val TAG = "ServerVersions"
        const val SERVER_LABEL = "DPTV-Server"
    }
}
