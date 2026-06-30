package tv.own.owntv.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.work.EpgSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncState
import tv.own.owntv.features.settings.data.SettingsRepository

/** Manage standalone EPG (XMLTV) sources: list, add (auto-sync), edit, re-sync, delete. */
class EpgSourcesViewModel(
    private val store: EpgSourceStore,
    private val epgRepository: EpgRepository,
    private val sourceRepository: SourceRepository,
    private val settings: SettingsRepository,
    private val epgDao: tv.own.owntv.core.database.dao.EpgDao,
    private val channelDao: tv.own.owntv.core.database.dao.ChannelDao,
    private val epgSyncScheduler: EpgSyncScheduler,
) : ViewModel() {

    /** An existing playlist's EPG feed, offered as a one-tap "fill from playlist" option. */
    data class PlaylistEpg(val name: String, val url: String)

    val sources: StateFlow<List<EpgSource>> =
        store.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, url: String, userAgent: String? = null) {
        viewModelScope.launch {
            val source = store.add(name, url, userAgent)
            epgSyncScheduler.enqueueSync(source.id, "add")
        }
    }

    fun resync(source: EpgSource) {
        epgSyncScheduler.enqueueSync(source.id, "manual_resync")
    }

    fun update(source: EpgSource, name: String, url: String, userAgent: String?) {
        viewModelScope.launch {
            val updated = source.copy(name = name.trim().ifBlank { source.name }, url = url.trim(), userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() })
            store.update(updated)
            epgSyncScheduler.enqueueSync(updated.id, "update")
        }
    }

    fun delete(source: EpgSource) {
        viewModelScope.launch {
            epgSyncScheduler.cancelSync(source.id)
            store.remove(source.id)
            epgRepository.clear(source.id)
        }
    }

    fun observeSync(sourceId: Long): Flow<EpgSyncState> = epgSyncScheduler.observeSync(sourceId)

    fun cancelSync(source: EpgSource) {
        epgSyncScheduler.cancelSync(source.id)
    }

    /** A source's status line: guide channels, stored programmes, and how many of the profile's
     *  channels advertise catch-up (the last is playlist-wide, shown so it's visible alongside the EPG). */
    suspend fun counts(id: Long): Triple<Int, Int, Int> {
        val channels = epgDao.countGuideChannels(listOf(id))
        val programmes = epgDao.countForSources(listOf(id))
        val pid = settings.activeProfileId.first()
        val playlistIds = if (pid < 0) emptyList() else sourceRepository.observeSources(pid).first().map { it.id }
        val catchup = if (playlistIds.isEmpty()) 0 else channelDao.countCatchup(playlistIds)
        return Triple(channels, programmes, catchup)
    }

    /** Playlists whose own EPG can pre-fill the add form (Xtream xmltv.php / M3U url-tvg). */
    suspend fun playlistEpgOptions(): List<PlaylistEpg> {
        val pid = settings.activeProfileId.first()
        if (pid < 0) return emptyList()
        return sourceRepository.observeSources(pid).first()
            .mapNotNull { src -> epgRepository.guideUrl(src)?.let { PlaylistEpg(src.name, it) } }
    }
}
