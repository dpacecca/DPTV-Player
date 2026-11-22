package tv.own.owntv.features.shell.components

import tv.own.owntv.core.metadata.CastMember
import tv.own.owntv.core.parser.XtTitleDetails

/**
 * (DPTV v2.9.26) The provider's own details as the fallback for what TMDB did not fill: a backdrop,
 * the year, genres, running time, the synopsis and the cast. Whatever is already there is kept, so
 * TMDB (or the provider's list entry) still wins where it has something.
 */
internal fun VodTitleInfo.withProvider(p: XtTitleDetails?): VodTitleInfo = if (p == null) this else copy(
    backdropUrl = backdropUrl ?: p.backdropUrl,
    year = year ?: p.year,
    genres = genres.ifEmpty { p.genres },
    runtimeSecs = runtimeSecs ?: p.runtimeSecs,
    plot = plot?.takeIf { it.isNotBlank() } ?: p.plot,
    cast = cast.ifEmpty { p.cast.map { CastMember(it) } },
)

/** The details window's counterpart of [VodTitleInfo.withProvider]. */
internal fun MediaDetailsUi.withProvider(p: XtTitleDetails?): MediaDetailsUi = if (p == null) this else copy(
    backdropUrl = backdropUrl ?: p.backdropUrl,
    genres = genres.ifEmpty { p.genres },
    plot = plot?.takeIf { it.isNotBlank() } ?: p.plot,
    cast = cast.ifEmpty { p.cast.map { CastMember(it) } },
)

/** How long focus must rest on a title before its provider details are asked for (never while scrolling). */
internal const val PROVIDER_DETAILS_DWELL_MS = 700L
