package tv.own.owntv.features.trakt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.jellyfin.JellyfinItem
import tv.own.owntv.core.jellyfin.JellyfinKind
import tv.own.owntv.core.trakt.TraktAuth
import tv.own.owntv.core.trakt.TraktClient
import tv.own.owntv.core.trakt.TraktDeviceCode
import tv.own.owntv.core.trakt.TraktIds
import tv.own.owntv.core.trakt.TraktListItem
import tv.own.owntv.core.trakt.TraktStore
import tv.own.owntv.core.trakt.TraktSync

/** Settings › Playlists › Trakt, and the watchlist button on a Jellyfin title (DPTV v2.9.1). */
class TraktViewModel(
    app: Application,
    private val store: TraktStore,
    private val client: TraktClient,
    private val sync: TraktSync,
) : AndroidViewModel(app) {

    val auth: StateFlow<TraktAuth?> = store.auth.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val lastSync: StateFlow<Long?> = store.lastSync.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val syncing: StateFlow<Boolean> = sync.running
    val watchlist: StateFlow<List<TraktListItem>> = store.watchlist.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    sealed interface SignIn {
        data object Idle : SignIn
        data object Starting : SignIn
        data class Code(val code: TraktDeviceCode) : SignIn
        /** HTTP code when Trakt refused the app; 0 when it could not be reached. */
        data class Failed(val code: Int) : SignIn
        data object Expired : SignIn
        data object Denied : SignIn
    }

    private val _signIn = MutableStateFlow<SignIn>(SignIn.Idle)
    val signIn: StateFlow<SignIn> = _signIn.asStateFlow()
    private var polling: Job? = null

    fun start(clientId: String, clientSecret: String) {
        if (clientId.isBlank() || clientSecret.isBlank()) return
        polling?.cancel()
        _signIn.value = SignIn.Starting
        polling = viewModelScope.launch {
            val device = try {
                client.startSignIn(clientId, clientSecret)
            } catch (e: TraktClient.TraktException) {
                _signIn.value = SignIn.Failed(e.code); return@launch
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _signIn.value = SignIn.Failed(0); return@launch
            }
            _signIn.value = SignIn.Code(device)
            var wait = device.intervalSec * 1000L
            val until = System.currentTimeMillis() + device.expiresInSec * 1000L
            while (System.currentTimeMillis() < until) {
                delay(wait)
                when (client.pollSignIn(device)) {
                    TraktClient.Poll.DONE -> {
                        _signIn.value = SignIn.Idle
                        TraktSync.enqueue(getApplication(), "signed-in")
                        return@launch
                    }
                    TraktClient.Poll.SLOW_DOWN -> wait += 1000L
                    TraktClient.Poll.EXPIRED -> { _signIn.value = SignIn.Expired; return@launch }
                    TraktClient.Poll.DENIED -> { _signIn.value = SignIn.Denied; return@launch }
                    TraktClient.Poll.FAILED -> { _signIn.value = SignIn.Failed(0); return@launch }
                    TraktClient.Poll.WAITING -> Unit
                }
            }
            _signIn.value = SignIn.Expired
        }
    }

    fun cancel() {
        polling?.cancel()
        _signIn.value = SignIn.Idle
    }

    fun syncNow() {
        viewModelScope.launch { sync.sync("settings") }
    }

    fun signOut() {
        viewModelScope.launch { store.signOut() }
    }

    // --- the watchlist from a Jellyfin title -----------------------------------------------------

    fun onWatchlist(item: JellyfinItem): Boolean {
        val tmdb = item.tmdbId ?: return false
        val show = item.kind == JellyfinKind.SERIES
        return watchlist.value.any { it.isShow == show && it.ids.tmdb == tmdb }
    }

    /** Adds [item] to the Trakt watchlist, or takes it off; the row on Home follows at once. */
    fun toggleWatchlist(item: JellyfinItem) {
        val tmdb = item.tmdbId ?: return
        val show = item.kind == JellyfinKind.SERIES
        val ids = TraktIds(tmdb = tmdb, imdb = item.imdbId, tvdb = item.tvdbId)
        val on = onWatchlist(item)
        viewModelScope.launch {
            runCatching {
                if (on) client.removeFromWatchlist(show, ids) else client.addToWatchlist(show, ids)
            }.onSuccess {
                val now = watchlist.value
                store.setWatchlist(
                    if (on) now.filterNot { it.isShow == show && it.ids.tmdb == tmdb }
                    else listOf(TraktListItem(show, item.name, item.year, ids, System.currentTimeMillis())) + now,
                )
            }
        }
    }

    val signedIn: StateFlow<Boolean> = store.auth.map { it?.signedIn == true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
