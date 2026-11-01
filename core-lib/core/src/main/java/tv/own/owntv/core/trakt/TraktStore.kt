package tv.own.owntv.core.trakt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.traktStore: DataStore<Preferences> by preferencesDataStore(name = "dptv_trakt")

/**
 * The Trakt sign-in and what the last sync learnt (DPTV v2.9.1). On this TV only: not in backups or the
 * shared profile, like the Jellyfin sign-in.
 */
class TraktStore(private val context: Context) {

    val auth: Flow<TraktAuth?> = context.traktStore.data.map { p ->
        val id = p[ClientId] ?: return@map null
        TraktAuth(id, p[ClientSecret].orEmpty(), p[Access], p[Refresh], p[Expires] ?: 0L, p[User])
    }

    suspend fun current(): TraktAuth? = auth.first()

    suspend fun saveApp(clientId: String, clientSecret: String) {
        context.traktStore.edit {
            if (it[ClientId] != clientId) { it.remove(Access); it.remove(Refresh); it.remove(User) }
            it[ClientId] = clientId
            it[ClientSecret] = clientSecret
        }
    }

    suspend fun saveTokens(tokens: TraktTokens, user: String?) {
        context.traktStore.edit {
            it[Access] = tokens.accessToken
            it[Refresh] = tokens.refreshToken
            it[Expires] = tokens.expiresAtMs
            if (user != null) it[User] = user
        }
    }

    /** Signs out; the app's client id and secret stay, to sign in again. */
    suspend fun signOut() {
        context.traktStore.edit {
            it.remove(Access); it.remove(Refresh); it.remove(Expires); it.remove(User)
            it.remove(Pushed); it.remove(Watchlist); it.remove(LastSync); it.remove(Watched)
        }
    }

    /** When the last sync finished, epoch ms; null before the first. */
    val lastSync: Flow<Long?> = context.traktStore.data.map { it[LastSync] }

    /** The watchlist as of the last sync, for Home. */
    val watchlist: Flow<List<TraktListItem>> = context.traktStore.data.map { p ->
        p[Watchlist]?.let { TraktParse.watchlist(it) }.orEmpty()
    }

    /** TMDB ids of films and shows Trakt has as watched, so Home's recommendations leave them out. */
    val watchedTmdb: Flow<Set<String>> = context.traktStore.data.map { it[Watched].orEmpty() }

    /** Keys already sent to Trakt's history (see [TraktSyncRules.movieKey]), so nothing goes twice. */
    suspend fun pushed(): Set<String> = context.traktStore.data.first()[Pushed].orEmpty()

    suspend fun afterSync(pushed: Set<String>, watchlist: List<TraktListItem>, watchedTmdb: Set<String>, at: Long) {
        context.traktStore.edit {
            it[Pushed] = pushed
            it[Watchlist] = TraktParse.encodeList(watchlist)
            it[Watched] = watchedTmdb
            it[LastSync] = at
        }
    }

    suspend fun setWatchlist(items: List<TraktListItem>) {
        context.traktStore.edit { it[Watchlist] = TraktParse.encodeList(items) }
    }

    private companion object {
        val ClientId = stringPreferencesKey("client_id")
        val ClientSecret = stringPreferencesKey("client_secret")
        val Access = stringPreferencesKey("access_token")
        val Refresh = stringPreferencesKey("refresh_token")
        val Expires = longPreferencesKey("expires_at")
        val User = stringPreferencesKey("user_name")
        val LastSync = longPreferencesKey("last_sync")
        val Pushed = stringSetPreferencesKey("pushed")
        val Watchlist = stringPreferencesKey("watchlist")
        val Watched = stringSetPreferencesKey("watched_tmdb")
    }
}
