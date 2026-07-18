package tv.own.owntv.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import tv.own.owntv.features.customize.CustomizeViewModel
import tv.own.owntv.features.downloads.DownloadsViewModel
import tv.own.owntv.features.epg.EpgViewModel
import tv.own.owntv.features.home.HomeViewModel
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.movies.MovieViewModel
import tv.own.owntv.features.profiles.ProfilesViewModel
import tv.own.owntv.features.search.SearchViewModel
import tv.own.owntv.features.series.SeriesViewModel
import tv.own.owntv.features.settings.BackupViewModel
import tv.own.owntv.features.settings.DeleteSubtitlesViewModel
import tv.own.owntv.features.settings.HomeSettingsViewModel
import tv.own.owntv.features.settings.OpenSubtitlesViewModel
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
    // Remote (companion) add-source LAN server — one shared instance for Setup + Settings.
    single { tv.own.owntv.core.companion.CompanionController(androidContext()) }
    // Merged (v4.0.0 + PR#31 Home/launcher). Koin resolves each get() by type, so only the count must match
    // each ViewModel's merged constructor.
    viewModel { ShellViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SetupViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { LiveViewModel(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { MovieViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SeriesViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ProfilesViewModel(get(), get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { HomeSettingsViewModel(get()) }
    // settings, openSubtitlesAccountManager — OpenSubtitles account screen (subtitle plan Phase 1)
    viewModel { OpenSubtitlesViewModel(get(), get()) }
    // controller — Delete subtitles screen (subtitle plan §11)
    viewModel { DeleteSubtitlesViewModel(get()) }
    // controller — OpenSubtitles search overlay opened from the player HUD (subtitle plan Phase 2)
    viewModel { tv.own.owntv.features.subtitles.SubtitleSearchViewModel(get()) }
    viewModel { DownloadsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { EpgViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // settings, sourceDao, categoryDao, customizationStore
    viewModel { CustomizeViewModel(get(), get(), get(), get()) }
    // backupManager
    viewModel { BackupViewModel(get(), get(), get()) }
    // store, epgRepository, sourceRepository, settings, epgDao, channelDao, scheduler
    viewModel { tv.own.owntv.features.settings.EpgSourcesViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
