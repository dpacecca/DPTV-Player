package tv.own.owntv.features.jellyfin

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.own.owntv.core.jellyfin.JellyfinAccount
import tv.own.owntv.core.jellyfin.JellyfinClient
import tv.own.owntv.core.jellyfin.JellyfinItem
import tv.own.owntv.core.jellyfin.JellyfinKind
import tv.own.owntv.core.jellyfin.JellyfinStore
import tv.own.owntv.player.OwnTVPlayer

/**
 * The Jellyfin section (DPTV v2.9.0): sign-in, the movie and show shelves, a title's page, and
 * playback through DPTV's own player with the progress told back to Jellyfin as you watch.
 */
class JellyfinViewModel(
    private val client: JellyfinClient,
    store: JellyfinStore,
    private val player: OwnTVPlayer,
) : ViewModel() {

    val account: StateFlow<JellyfinAccount?> = store.account.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val lastServer: StateFlow<String?> = store.lastServer.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    sealed interface SignIn {
        data object Idle : SignIn
        data object Working : SignIn
        /** HTTP code, 0 when the server could not be reached. */
        data class Failed(val code: Int) : SignIn
    }

    private val _signIn = MutableStateFlow<SignIn>(SignIn.Idle)
    val signIn: StateFlow<SignIn> = _signIn.asStateFlow()

    fun signIn(server: String, user: String, password: String) {
        if (_signIn.value == SignIn.Working) return
        _signIn.value = SignIn.Working
        viewModelScope.launch {
            _signIn.value = try {
                client.signIn(server, user, password)
                refresh()
                SignIn.Idle
            } catch (e: JellyfinClient.JellyfinException) {
                SignIn.Failed(e.code)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SignIn.Failed(0)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            client.signOut()
            _shelves.value = Shelves()
        }
    }

    // --- shelves ---------------------------------------------------------------------------------

    enum class Tab { MOVIES, SHOWS }

    data class Shelves(
        val loading: Boolean = false,
        val failed: Boolean = false,
        val continueMovies: List<JellyfinItem> = emptyList(),
        val latestMovies: List<JellyfinItem> = emptyList(),
        val allMovies: List<JellyfinItem> = emptyList(),
        val moviesTotal: Int = 0,
        val nextUp: List<JellyfinItem> = emptyList(),
        val continueEpisodes: List<JellyfinItem> = emptyList(),
        val latestShows: List<JellyfinItem> = emptyList(),
        val allShows: List<JellyfinItem> = emptyList(),
        val showsTotal: Int = 0,
    )

    private val _shelves = MutableStateFlow(Shelves())
    val shelves: StateFlow<Shelves> = _shelves.asStateFlow()

    private var loadJob: Job? = null

    /** Everything on the section's front page, fetched in parallel. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _shelves.value = _shelves.value.copy(loading = true, failed = false)
            try {
                val cm = async { client.resume(JellyfinClient.Shelf.MOVIES) }
                val lm = async { client.latest(JellyfinClient.Shelf.MOVIES) }
                val am = async { client.library(JellyfinClient.Shelf.MOVIES, 0, PAGE) }
                val nu = async { client.nextUp() }
                val ce = async { client.resume(JellyfinClient.Shelf.SHOWS) }
                val ls = async { client.latest(JellyfinClient.Shelf.SHOWS) }
                val asr = async { client.library(JellyfinClient.Shelf.SHOWS, 0, PAGE) }
                val movies = am.await()
                val shows = asr.await()
                _shelves.value = Shelves(
                    continueMovies = cm.await(), latestMovies = lm.await(),
                    allMovies = movies.items, moviesTotal = movies.total,
                    nextUp = nu.await(), continueEpisodes = ce.await(), latestShows = ls.await(),
                    allShows = shows.items, showsTotal = shows.total,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "shelves failed: ${e.javaClass.simpleName}")
                _shelves.value = _shelves.value.copy(loading = false, failed = true)
            }
        }
    }

    private var pageJob: Job? = null

    /** The next page of the A–Z grid, when the grid scrolls near its end. */
    fun loadMore(tab: Tab) {
        if (pageJob?.isActive == true) return
        val s = _shelves.value
        val have = if (tab == Tab.MOVIES) s.allMovies.size else s.allShows.size
        val total = if (tab == Tab.MOVIES) s.moviesTotal else s.showsTotal
        if (have >= total) return
        pageJob = viewModelScope.launch {
            runCatching {
                client.library(if (tab == Tab.MOVIES) JellyfinClient.Shelf.MOVIES else JellyfinClient.Shelf.SHOWS, have, PAGE)
            }.onSuccess { page ->
                val now = _shelves.value
                _shelves.value = if (tab == Tab.MOVIES) now.copy(allMovies = (now.allMovies + page.items).distinctBy { it.id })
                else now.copy(allShows = (now.allShows + page.items).distinctBy { it.id })
            }
        }
    }

    // --- a title's page --------------------------------------------------------------------------

    data class Detail(
        val item: JellyfinItem,
        val seasons: List<JellyfinItem> = emptyList(),
        val seasonId: String? = null,
        val episodes: List<JellyfinItem> = emptyList(),
        val loading: Boolean = false,
    )

    private val _detail = MutableStateFlow<Detail?>(null)
    val detail: StateFlow<Detail?> = _detail.asStateFlow()

    /** Opens a title: a film as it is, a series with its seasons and the first unfinished season's episodes. */
    fun open(item: JellyfinItem) {
        val series = item.takeIf { it.kind == JellyfinKind.SERIES }
        _detail.value = Detail(item, loading = series != null || item.kind == JellyfinKind.EPISODE)
        viewModelScope.launch {
            val seriesId = series?.id ?: item.seriesId
            if (seriesId == null) {
                client.runCatching { item(item.id) }.getOrNull()?.let { fresh -> _detail.value = Detail(fresh) }
                return@launch
            }
            val head = series ?: client.runCatching { item(seriesId) }.getOrNull() ?: item
            val seasons = client.runCatching { seasons(seriesId) }.getOrDefault(emptyList())
            val pick = item.seasonId?.takeIf { id -> seasons.any { it.id == id } }
                ?: seasons.firstOrNull { (it.unplayed ?: 0) > 0 }?.id
                ?: seasons.firstOrNull()?.id
            val episodes = client.runCatching { episodes(seriesId, pick) }.getOrDefault(emptyList())
            _detail.value = Detail(head, seasons, pick, episodes)
        }
    }

    fun pickSeason(seasonId: String) {
        val d = _detail.value ?: return
        _detail.value = d.copy(seasonId = seasonId, loading = true)
        viewModelScope.launch {
            val episodes = client.runCatching { episodes(d.item.id, seasonId) }.getOrDefault(emptyList())
            if (_detail.value?.seasonId == seasonId) _detail.value = _detail.value?.copy(episodes = episodes, loading = false)
        }
    }

    fun close() {
        _detail.value = null
    }

    fun setPlayed(item: JellyfinItem, played: Boolean) {
        viewModelScope.launch {
            runCatching { client.setPlayed(item.id, played) }
            refreshDetail()
            refresh()
        }
    }

    private suspend fun refreshDetail() {
        val d = _detail.value ?: return
        val seriesId = if (d.item.kind == JellyfinKind.SERIES) d.item.id else null
        if (seriesId != null) {
            val episodes = client.runCatching { episodes(seriesId, d.seasonId) }.getOrDefault(d.episodes)
            val seasons = client.runCatching { seasons(seriesId) }.getOrDefault(d.seasons)
            _detail.value = _detail.value?.copy(episodes = episodes, seasons = seasons)
        } else {
            client.runCatching { item(d.item.id) }.getOrNull()?.let { fresh -> _detail.value = _detail.value?.copy(item = fresh) }
        }
    }

    // --- playback --------------------------------------------------------------------------------

    private var playing: JellyfinItem? = null
    private var reporter: Job? = null

    /** Plays [item] from [startMs] in DPTV's player, named [title], and starts telling Jellyfin where it is. False when not signed in. */
    fun play(item: JellyfinItem, startMs: Long, title: String): Boolean {
        val a = account.value ?: return false
        val url = client.streamUrl(a, item)
        player.play(
            url,
            title = title,
            year = item.year?.toString(),
            isLive = false,
            startPositionMs = startMs,
            httpHeaders = client.streamHeaders(a),
            contentKey = CONTENT_KEY_PREFIX + item.id,
        )
        playing = item
        reporter?.cancel()
        reporter = viewModelScope.launch {
            client.reportStart(item.id, startMs)
            var last = startMs
            while (isActive) {
                delay(REPORT_EVERY_MS)
                if (!playerIsOn(item)) {
                    // Played to the end, or something else started: Jellyfin hears where it stopped.
                    if (playing?.id == item.id) {
                        playing = null
                        client.reportStopped(item.id, last)
                        refresh()
                    }
                    break
                }
                last = player.position.value
                client.reportProgress(item.id, last, paused = !player.isPlaying.value)
            }
        }
        return true
    }

    /** The shell calls this before it stops the player: Jellyfin keeps the position and decides "watched". */
    fun saveProgressNow() {
        val item = playing ?: return
        if (!playerIsOn(item)) return
        val pos = player.position.value
        playing = null
        reporter?.cancel()
        viewModelScope.launch {
            client.reportStopped(item.id, pos)
            refreshDetail()
            refresh()
        }
    }

    private fun playerIsOn(item: JellyfinItem): Boolean = player.currentMediaContentKey == CONTENT_KEY_PREFIX + item.id

    companion object {
        private const val TAG = "JellyfinVM"
        private const val PAGE = 120
        private const val REPORT_EVERY_MS = 10_000L
        private const val CONTENT_KEY_PREFIX = "jellyfin:"

    }
}
