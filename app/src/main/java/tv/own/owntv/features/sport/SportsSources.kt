package tv.own.owntv.features.sport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The ids of the profile's **live** playlists, resolved for [SportsMatcher].
 *
 * This exists as a one-method seam for tests. [SportsMatcher] needs three things: the channel and
 * guide DAOs (already plain interfaces, trivial to fake) and the set of live source ids — but that
 * last one is produced by `SettingsRepository`/`SourceRepository`, which are concrete classes wired
 * to an Android `Context`/`DataStore`. The project carries no MockK/Mockito/Robolectric, so a JVM
 * unit test cannot build those repositories. Pulling the lookup behind this interface removes the
 * Android dependency from the matcher's constructor: production binds
 * [ProfileLiveSourceIdsProvider], a test hands in a lambda. No behaviour changes — this is the same
 * query that used to be a private method on the matcher.
 */
fun interface LiveSourceIdsProvider {
    /** Live playlist ids for the active profile, in the order the provider reports them. */
    suspend fun liveSourceIds(): List<Long>
}

/**
 * The real [LiveSourceIdsProvider]: the active profile's sources, narrowed to the profile's default
 * playlist when one is set, then to those with live sync enabled. Every call is guarded so a cold or
 * half-configured install yields an empty list rather than an exception — an empty result simply
 * means "nothing to match against".
 */
class ProfileLiveSourceIdsProvider(
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
) : LiveSourceIdsProvider {

    override suspend fun liveSourceIds(): List<Long> {
        val pid = settings.activeProfileId.first()
        if (pid < 0) return emptyList()
        val sources = runCatching { sourceRepository.observeSources(pid).first() }.getOrNull().orEmpty()
        val defaultId = runCatching { settings.defaultSourceId.first() }.getOrDefault(-1L)
        val scoped = if (defaultId > 0) sources.filter { it.id == defaultId } else sources
        return scoped.filter { it.syncLive }.map { it.id }
    }
}

/** A live playlist the Sport settings' playlist filter can pick ([SportChannelSources.playlists]). */
data class SportPlaylist(val id: Long, val name: String)

/**
 * What the Sport settings and the auto rebuild need to know about the user's playlists and guides
 * (v5.2 port): the live playlists by name, for the playlist filter, and a fingerprint that changes
 * whenever a playlist or an external guide finished a sync (or one was added/removed) — the
 * "playlists or the guide changed" trigger of the auto rebuild.
 */
interface SportChannelSources {
    val playlists: Flow<List<SportPlaylist>>
    val changes: Flow<Int>
}

/**
 * The real [SportChannelSources]: the active profile's live playlists, narrowed exactly as
 * [ProfileLiveSourceIdsProvider] narrows them (default playlist, live sync on), so the filter never
 * offers a playlist the matcher would not search anyway. The fingerprint hashes every playlist's and
 * external guide's last sync time; a playlist's own (Xtream) guide arrives with its sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSportChannelSources(
    settings: SettingsRepository,
    sourceRepository: SourceRepository,
    epgSourceStore: EpgSourceStore,
) : SportChannelSources {

    private val sources: Flow<List<SourceEntity>> = settings.activeProfileId.flatMapLatest { pid ->
        if (pid < 0) flowOf(emptyList()) else sourceRepository.observeSources(pid)
    }

    override val playlists: Flow<List<SportPlaylist>> =
        combine(sources, settings.defaultSourceId) { all, defaultId ->
            val scoped = if (defaultId > 0) all.filter { it.id == defaultId } else all
            scoped.filter { it.syncLive }.map { SportPlaylist(it.id, it.name) }
        }.distinctUntilChanged()

    override val changes: Flow<Int> =
        combine(sources, epgSourceStore.sources) { playlists, guides ->
            listOf(
                playlists.map { Triple(it.id, it.syncLive, it.lastSyncAt) },
                guides.map { it.id to it.lastSyncAt },
            ).hashCode()
        }.distinctUntilChanged()
}
