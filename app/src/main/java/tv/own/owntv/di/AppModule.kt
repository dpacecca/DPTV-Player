package tv.own.owntv.di

import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import tv.own.owntv.features.customize.CustomizeViewModel
import tv.own.owntv.features.downloads.DownloadsViewModel
import tv.own.owntv.features.epg.EpgViewModel
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.movies.MovieViewModel
import tv.own.owntv.features.profiles.ProfilesViewModel
import tv.own.owntv.features.search.SearchViewModel
import tv.own.owntv.features.series.SeriesViewModel
import tv.own.owntv.features.settings.BackupViewModel
import tv.own.owntv.features.settings.SettingsViewModel
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.features.setup.SetupViewModel
import tv.own.owntv.features.shell.ShellViewModel

/**
 * Root Koin module. Each feature will contribute its own bindings as the app grows;
 * for now this wires settings persistence and the shell view model.
 */
val appModule = module {
    single { SettingsRepository(androidContext()) }
    // settings, sourceRepository, profileDao, connectivity, tvHomeRepository, epgMigration
    viewModel { ShellViewModel(get(), get(), get(), get(), get(), get()) }
    // profileDao, sourceDao, sourceRepository, backup, settings, connectivity, importFinalizer, tvHomeRepository
    viewModel { SetupViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    // channelDao, categoryDao, favoriteDao, historyDao, profileDao, sourceDao, settings, xtreamClient, customize, tvHomeRepository, epgDao, epgSourceStore, player, previewEngine
    viewModel { LiveViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // movieDao, categoryDao, favoriteDao, historyDao, progressDao, profileDao, sourceDao, settings, customize, player, downloadManager, tvHomeRepository
    viewModel { MovieViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // seriesDao, categoryDao, favoriteDao, historyDao, progressDao, profileDao, sourceDao, seriesRepository, settings, customize, player, downloadManager, tvHomeRepository
    viewModel { SeriesViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // channelDao, movieDao, seriesDao, historyDao, profileDao, sourceDao, settings, customize, player
    viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // profileDao, sourceDao, settings, tvHomeRepository
    viewModel { ProfilesViewModel(get(), get(), get(), get()) }
    // profileDao, sourceDao, sourceRepository, settings, connectivity, epgDao, importFinalizer, channelDao, tvHomeRepository
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // downloadDao, settings, downloadManager, player
    viewModel { DownloadsViewModel(get(), get(), get(), get()) }
    // settings, sourceRepository, channelDao, epgDao, profileDao, epgRepository, epgSourceStore, connectivity, customize, historyDao, sourceDao, xtream, player
    viewModel { EpgViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // settings, sourceDao, categoryDao, customizationStore
    viewModel { CustomizeViewModel(get(), get(), get(), get()) }
    // backupManager
    viewModel { BackupViewModel(get()) }
    // store, epgRepository, sourceRepository, settings, connectivity, epgDao
    viewModel { tv.own.owntv.features.settings.EpgSourcesViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
