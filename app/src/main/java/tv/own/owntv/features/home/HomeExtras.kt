package tv.own.owntv.features.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.home.AvailableTitle
import tv.own.owntv.core.home.HomeRecommendations
import tv.own.owntv.core.jellyfin.JellyfinClient
import tv.own.owntv.core.jellyfin.JellyfinItem
import tv.own.owntv.core.jellyfin.JellyfinKind
import tv.own.owntv.core.jellyfin.JellyfinStore
import tv.own.owntv.core.launcher.LauncherContinuationItem
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.trakt.TraktStore

/** What OK on a Home card does (DPTV v2.9.1). */
sealed interface HomeCardTarget {
    data class VodMovie(val movieId: Long, val positionMs: Long) : HomeCardTarget
    data class VodEpisode(val episodeId: Long, val positionMs: Long) : HomeCardTarget
    data class OpenMovie(val movie: MovieEntity) : HomeCardTarget
    data class OpenSeries(val series: SeriesEntity) : HomeCardTarget
    /** Plays at once, from [startMs]: Continue watching. */
    data class JellyfinPlay(val item: JellyfinItem, val startMs: Long) : HomeCardTarget
    /** Opens the title's page in the Jellyfin section. */
    data class JellyfinOpen(val item: JellyfinItem) : HomeCardTarget
}

/** One card on the rows DPTV v2.9.1 added, whatever it comes from. */
@Immutable
data class HomeCard(
    val key: String,
    val title: String,
    val posterUrl: String?,
    /** How far it was watched, 0–1; null for none. */
    val progress: Float? = null,
    /** When it was last watched, for Continue watching's order. */
    val lastWatchedMs: Long = 0,
    val target: HomeCardTarget,
)

/** The rows' Jellyfin and recommendation cards. */
@Immutable
data class HomeExtras(
    val jellyfinContinueMovies: List<HomeCard> = emptyList(),
    val jellyfinContinueShows: List<HomeCard> = emptyList(),
    val jellyfinLatestMovies: List<HomeCard> = emptyList(),
    val jellyfinLatestShows: List<HomeCard> = emptyList(),
    val recommendedMovies: List<HomeCard> = emptyList(),
    val recommendedShows: List<HomeCard> = emptyList(),
    val watchlist: List<HomeCard> = emptyList(),
)

/** The pure part: VOD and Jellyfin in one Continue watching row, most recent first. */
object HomeExtrasRules {
    fun continueCards(vod: List<LauncherContinuationItem>, episodes: Boolean, jellyfin: List<HomeCard>): List<HomeCard> =
        merge(vod.map { vodCard(it.stableKey, it.title, it.posterUrl, it.positionMs, it.durationMs, it.lastEngagementAt, it.sourceItemId, it.targetItemId, episodes) }, jellyfin)

    fun vodCard(
        stableKey: String, title: String, posterUrl: String?, positionMs: Long, durationMs: Long, lastAt: Long,
        movieId: Long, episodeId: Long, episodes: Boolean,
    ) = HomeCard(
        key = "vod:$stableKey",
        title = title,
        posterUrl = posterUrl,
        progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null,
        lastWatchedMs = lastAt,
        target = if (episodes) HomeCardTarget.VodEpisode(episodeId, positionMs) else HomeCardTarget.VodMovie(movieId, positionMs),
    )

    /** One row of both, the most recent first; without Jellyfin cards DPTV's own order stays. */
    fun merge(own: List<HomeCard>, jellyfin: List<HomeCard>): List<HomeCard> =
        if (jellyfin.isEmpty()) own else (own + jellyfin).sortedByDescending { it.lastWatchedMs }

    /** "Series · S1 · E4" style title for an episode card; a film keeps its own name. */
    fun cardTitle(item: JellyfinItem): String = if (item.kind == JellyfinKind.EPISODE) item.seriesName ?: item.name else item.name
}

/**
 * Feeds Home's Jellyfin, Recommended and Watchlist rows, and Jellyfin's half of Continue watching
 * (DPTV v2.9.1). Only the rows on screen are fetched; each refresh replaces what is shown.
 */
class HomeExtrasViewModel(
    private val jellyfinStore: JellyfinStore,
    private val jellyfin: JellyfinClient,
    private val recommendations: HomeRecommendations,
    private val traktStore: TraktStore,
    private val settings: tv.own.owntv.core.settings.SettingsRepository,
) : ViewModel() {

    private val _extras = MutableStateFlow(HomeExtras())
    val extras: StateFlow<HomeExtras> = _extras.asStateFlow()

    private var job: Job? = null

    fun refresh(config: HomeConfig) {
        val rows = config.visibleOrder.toSet()
        job?.cancel()
        job = viewModelScope.launch {
            val profileId = settings.activeProfileId.first().takeIf { it >= 0 } ?: return@launch
            val account = jellyfinStore.current()
            fun want(row: HomeRow) = row in rows
            val cm = async { if (account != null && want(HomeRow.CONTINUE_MOVIES)) jfCards { jellyfin.resume(JellyfinClient.Shelf.MOVIES) } else emptyList() }
            val cs = async {
                if (account != null && want(HomeRow.CONTINUE_SERIES)) {
                    val resume = jfCards { jellyfin.resume(JellyfinClient.Shelf.SHOWS) }
                    val next = jfCards { jellyfin.nextUp() }
                    (resume + next).distinctBy { it.key }
                } else emptyList()
            }
            val lm = async { if (account != null && want(HomeRow.JELLYFIN_MOVIES)) jfCards(open = true) { jellyfin.latest(JellyfinClient.Shelf.MOVIES) } else emptyList() }
            val ls = async { if (account != null && want(HomeRow.JELLYFIN_SHOWS)) jfCards(open = true) { jellyfin.latest(JellyfinClient.Shelf.SHOWS) } else emptyList() }
            val rm = async { if (want(HomeRow.RECOMMENDED_MOVIES)) titleCards { recommendations.recommended(profileId, MetadataType.MOVIE) } else emptyList() }
            val rs = async { if (want(HomeRow.RECOMMENDED_SHOWS)) titleCards { recommendations.recommended(profileId, MetadataType.TV) } else emptyList() }
            val traktSignedIn = traktStore.current()?.signedIn == true
            val wl = async { if (want(HomeRow.TRAKT_WATCHLIST) && traktSignedIn) titleCards { recommendations.watchlist(profileId) } else emptyList() }
            _extras.value = HomeExtras(cm.await(), cs.await(), lm.await(), ls.await(), rm.await(), rs.await(), wl.await())
        }
    }

    private suspend fun jfCards(open: Boolean = false, load: suspend () -> List<JellyfinItem>): List<HomeCard> {
        val a = jellyfinStore.current() ?: return emptyList()
        val items = runCatching { load() }.getOrDefault(emptyList())
        return items.map { item ->
            val runtime = item.runtimeMs ?: 0L
            HomeCard(
                key = "jf:" + item.id,
                title = HomeExtrasRules.cardTitle(item),
                posterUrl = if (item.kind == JellyfinKind.EPISODE && item.seriesId != null) {
                    jellyfin.imageUrl(a, item.seriesId!!, "Primary", item.seriesPrimaryTag, 450) ?: jellyfin.posterUrl(a, item)
                } else jellyfin.posterUrl(a, item),
                progress = if (item.positionMs > 0 && runtime > 0) (item.positionMs.toFloat() / runtime).coerceIn(0f, 1f) else null,
                lastWatchedMs = item.lastPlayedMs ?: 0L,
                target = if (open || item.kind == JellyfinKind.SERIES) HomeCardTarget.JellyfinOpen(item) else HomeCardTarget.JellyfinPlay(item, item.positionMs),
            )
        }
    }

    private suspend fun titleCards(load: suspend () -> List<AvailableTitle>): List<HomeCard> =
        runCatching { load() }.getOrDefault(emptyList()).mapNotNull { t ->
            val target = when {
                t.jellyfin != null -> HomeCardTarget.JellyfinOpen(t.jellyfin!!)
                t.movie != null -> HomeCardTarget.OpenMovie(t.movie!!)
                t.series != null -> HomeCardTarget.OpenSeries(t.series!!)
                else -> return@mapNotNull null
            }
            HomeCard(key = t.key, title = t.title.localizedTitle, posterUrl = t.posterUrl, target = target)
        }
}
