package tv.own.owntv.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import tv.own.owntv.player.HeroPreviewEngine
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.OwnTVPlayer

/** App-wide libmpv player. */
val playerModule = module {
    // Tails own-process logcat for MediaCodec/AudioTrack errors the engines can't expose.
    single { tv.own.owntv.player.PlayerDiagnostics() }
    // context, settings, connectivity, okHttpClient (ExoPlayer image-sub handoff), diagnostics, proxyHolder
    single { OwnTVPlayer(androidContext(), get(), get(), get(), get(), get()) }
    // ExoPlayer engine for the fast Live preview pane (mpv stays the full/fullscreen player).
    single { LivePreviewEngine(androidContext(), get(), get()) }
    // Muted ExoPlayer engine for the Home hero preview.
    single { HeroPreviewEngine(androidContext(), get()) }
}
