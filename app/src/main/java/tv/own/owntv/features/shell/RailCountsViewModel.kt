package tv.own.owntv.features.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The numbers beside Live TV, Movies, Series and Downloads in the open Stage rail, for the playlists
 * being shown ("All playlists" or the one picked). Every query already exists on the DAOs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RailCountsViewModel(
    channelDao: ChannelDao,
    movieDao: MovieDao,
    seriesDao: SeriesDao,
    downloadDao: DownloadDao,
    settings: SettingsRepository,
    sourceDao: SourceDao,
) : ViewModel() {

    val counts: StateFlow<Map<MainSection, Int>> = activeProfileSources(settings, sourceDao)
        .flatMapLatest { c ->
            if (c.profileId < 0) return@flatMapLatest flowOf(emptyMap())
            // An empty id list would make the IN clause meaningless; -1 matches nothing.
            fun ids(type: MediaType) = c.sourceIdsFor(type).ifEmpty { listOf(-1L) }
            combine(
                channelDao.countAll(ids(MediaType.LIVE)),
                movieDao.countAll(ids(MediaType.MOVIE)),
                seriesDao.countAll(ids(MediaType.SERIES)),
                downloadDao.count(c.profileId),
            ) { live, movies, series, downloads ->
                mapOf(
                    MainSection.LIVE_TV to live,
                    MainSection.MOVIES to movies,
                    MainSection.SERIES to series,
                    MainSection.DOWNLOADS to downloads,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
