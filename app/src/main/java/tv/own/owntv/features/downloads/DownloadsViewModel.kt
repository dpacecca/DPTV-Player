@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.own.owntv.features.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.player.OwnTVPlayer

/** Phase 12 — lists the active profile's downloads and plays completed ones from local storage. */
class DownloadsViewModel(
    private val downloadDao: DownloadDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
    private val downloadManager: DownloadManager,
    val player: OwnTVPlayer,
) : ViewModel() {

    /**
     * The profile's downloads, minus rows whose movie/series the user has hidden — a hidden title
     * disappears everywhere, Downloads included. The file stays on disk and the row reappears on
     * unhide (Settings → Customize Category). Downloads whose source item no longer exists (deleted
     * playlist / re-sync id churn) are kept — they can't be resolved to a hide key.
     */
    val downloads: StateFlow<List<DownloadEntity>> = settings.activeProfileId
        .flatMapLatest { pid ->
            if (pid < 0) {
                flowOf(emptyList())
            } else {
                combine(
                    downloadDao.observeForProfile(pid),
                    customize.observe(pid, MediaType.MOVIE),
                    customize.observe(pid, MediaType.SERIES),
                ) { list, custMovie, custSeries ->
                    if (custMovie.hiddenItems.isEmpty() && custSeries.hiddenItems.isEmpty()) list
                    else list.filterNot { isHidden(it, custMovie, custSeries) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun isHidden(
        d: DownloadEntity,
        custMovie: SectionCustomizations,
        custSeries: SectionCustomizations,
    ): Boolean = when (d.mediaType) {
        MediaType.MOVIE -> movieDao.getById(d.itemId)
            ?.let { CustomizeKeys.movie(it) in custMovie.hiddenItems } ?: false
        MediaType.EPISODE -> seriesDao.getEpisodeById(d.itemId)
            ?.let { ep -> seriesDao.getSeriesById(ep.seriesId)?.let { CustomizeKeys.series(it) in custSeries.hiddenItems } }
            ?: false
        else -> false
    }

    private val _lastPlayedId = MutableStateFlow<Long?>(null)
    val lastPlayedId: StateFlow<Long?> = _lastPlayedId.asStateFlow()

    /** Play a completed download from its local file. */
    fun play(download: DownloadEntity) {
        val path = download.filePath ?: return
        _lastPlayedId.value = download.id
        player.play(path, title = download.title, isLive = false)
    }

    fun retry(download: DownloadEntity) = downloadManager.retry(download)
    fun pause(download: DownloadEntity) = downloadManager.pause(download)
    fun resume(download: DownloadEntity) = downloadManager.resume(download)
    fun delete(download: DownloadEntity) = downloadManager.delete(download)
}
