package tv.own.owntv.core.trakt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Trakt's API, the part DPTV uses (DPTV v2.9.1): device sign-in with the user's own Trakt app, the
 * watched history both ways, and the watchlist. Never logs a token, a secret or a full URL.
 */
class TraktClient(
    private val client: OkHttpClient,
    private val store: TraktStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class TraktException(val code: Int, message: String) : java.io.IOException(message)

    /** Step 1 of signing in: the code to show and the page to type it into. */
    suspend fun startSignIn(clientId: String, clientSecret: String): TraktDeviceCode {
        store.saveApp(clientId.trim(), clientSecret.trim())
        val body = JSONObject().put("client_id", clientId.trim()).toString()
        val (code, text) = call(Request.Builder().url("$API/oauth/device/code").post(body.toRequestBody(JSON)), null)
        if (code !in 200..299) throw TraktException(code, "device code refused (HTTP $code)")
        return TraktParse.deviceCode(text) ?: throw TraktException(0, "device code not understood")
    }

    enum class Poll { DONE, WAITING, SLOW_DOWN, EXPIRED, DENIED, FAILED }

    /** Step 2, every [TraktDeviceCode.intervalSec]: DONE once the user has approved on trakt.tv. */
    suspend fun pollSignIn(device: TraktDeviceCode): Poll {
        val auth = store.current() ?: return Poll.FAILED
        val body = JSONObject().put("code", device.deviceCode).put("client_id", auth.clientId).put("client_secret", auth.clientSecret).toString()
        val (code, text) = runCatching { call(Request.Builder().url("$API/oauth/device/token").post(body.toRequestBody(JSON)), null) }
            .getOrElse { return Poll.WAITING }
        return when (code) {
            200 -> {
                val tokens = TraktParse.tokens(text, now()) ?: return Poll.FAILED
                store.saveTokens(tokens, null)
                val user = runCatching { TraktParse.userName(get("/users/settings")) }.getOrNull()
                store.saveTokens(tokens, user)
                Poll.DONE
            }
            400 -> Poll.WAITING
            429 -> Poll.SLOW_DOWN
            404, 409, 410 -> Poll.EXPIRED
            418 -> Poll.DENIED
            else -> Poll.FAILED
        }
    }

    /** Renews the session a day before it ends; false when Trakt refused (sign in again). */
    suspend fun ensureFresh(): Boolean {
        val auth = store.current() ?: return false
        if (!auth.signedIn) return false
        if (auth.expiresAtMs - now() > DAY_MS) return true
        val body = JSONObject()
            .put("refresh_token", auth.refreshToken).put("client_id", auth.clientId).put("client_secret", auth.clientSecret)
            .put("redirect_uri", OOB).put("grant_type", "refresh_token").toString()
        val (code, text) = call(Request.Builder().url("$API/oauth/token").post(body.toRequestBody(JSON)), null)
        if (code !in 200..299) return code !in 400..499
        val tokens = TraktParse.tokens(text, now()) ?: return false
        store.saveTokens(tokens, auth.userName)
        return true
    }

    suspend fun watched(): TraktWatched = TraktParse.watched(get("/sync/watched/movies"), get("/sync/watched/shows"))

    suspend fun watchlist(): List<TraktListItem> =
        TraktParse.watchlist(get("/sync/watchlist/movies")) + TraktParse.watchlist(get("/sync/watchlist/shows"))

    /** Adds films and episodes to the watched history. */
    suspend fun addHistory(movies: List<TraktIds>, episodes: List<TraktEpisodeRef>) {
        if (movies.isEmpty() && episodes.isEmpty()) return
        post("/sync/history", TraktParse.historyBody(movies, episodes))
    }

    suspend fun addToWatchlist(isShow: Boolean, ids: TraktIds) = post("/sync/watchlist", TraktParse.listBody(isShow, ids))

    suspend fun removeFromWatchlist(isShow: Boolean, ids: TraktIds) = post("/sync/watchlist/remove", TraktParse.listBody(isShow, ids))

    // --- HTTP ------------------------------------------------------------------------------------

    private suspend fun get(path: String): String {
        val auth = store.current()?.takeIf { it.signedIn } ?: throw TraktException(401, "not signed in")
        val (code, text) = call(Request.Builder().url("$API$path").get(), auth)
        if (code !in 200..299) throw TraktException(code, "HTTP $code for $path")
        return text
    }

    private suspend fun post(path: String, json: String) {
        val auth = store.current()?.takeIf { it.signedIn } ?: throw TraktException(401, "not signed in")
        val (code, _) = call(Request.Builder().url("$API$path").post(json.toRequestBody(JSON)), auth)
        if (code !in 200..299) throw TraktException(code, "HTTP $code for $path")
    }

    private suspend fun call(builder: Request.Builder, auth: TraktAuth?): Pair<Int, String> = withContext(Dispatchers.IO) {
        builder.header("Content-Type", "application/json").header(VERSION_HEADER, "2")
        val clientId = auth?.clientId ?: store.current()?.clientId
        if (clientId != null) builder.header(KEY_HEADER, clientId)
        auth?.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { r -> r.code to r.body.string() }
    }

    private companion object {
        const val API = "https://api.trakt.tv"
        const val OOB = "urn:ietf:wg:oauth:2.0:oob"
        const val VERSION_HEADER = "trakt-api-version"
        const val KEY_HEADER = "trakt-api-key"
        const val DAY_MS = 24L * 60L * 60L * 1000L
        val JSON = "application/json".toMediaType()
    }
}
