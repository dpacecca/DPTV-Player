package tv.own.owntv.core.jellyfin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Jellyfin's REST API, the part DPTV uses (DPTV v2.9.0): sign in, browse movies and shows, and tell
 * Jellyfin what was watched and where it stopped. Never logs a token, a password or a full URL.
 */
class JellyfinClient(
    private val client: OkHttpClient,
    private val store: JellyfinStore,
    private val appVersion: () -> String = { "" },
) {
    class JellyfinException(val code: Int, message: String) : java.io.IOException(message)

    enum class Shelf { MOVIES, SHOWS }

    /** Signs in and saves the session; the password is used once and not kept. */
    suspend fun signIn(server: String, user: String, password: String): JellyfinAccount = withContext(Dispatchers.IO) {
        val base = JellyfinParse.normalizeServer(server)
        val deviceId = store.deviceId()
        val body = JSONObject().put("Username", user).put("Pw", password).toString()
        val request = Request.Builder()
            .url("$base/Users/AuthenticateByName")
            .header(AUTH_HEADER, authorization(deviceId, null))
            .post(body.toRequestBody(JSON))
            .build()
        val answer = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw JellyfinException(r.code, "sign-in refused (HTTP ${r.code})")
            r.body.string()
        }
        val account = JellyfinParse.account(base, deviceId, answer) ?: throw JellyfinException(0, "sign-in answer not understood")
        store.save(account)
        account
    }

    suspend fun signOut() {
        val account = store.current()
        if (account != null) runCatching { post(account, "/Sessions/Logout", null) }
        store.clear()
    }

    /** One page of a shelf, A–Z. */
    suspend fun library(shelf: Shelf, start: Int, limit: Int, sortBy: String = "SortName", sortOrder: String = "Ascending"): JellyfinPage {
        val a = account()
        return JellyfinParse.page(
            get(a, "/Users/${a.userId}/Items", mapOf(
                "IncludeItemTypes" to typeOf(shelf), "Recursive" to "true", "SortBy" to sortBy, "SortOrder" to sortOrder,
                "StartIndex" to start.toString(), "Limit" to limit.toString(), "Fields" to FIELDS, "EnableImageTypes" to IMAGES,
            )),
        )
    }

    /** What was added last; for shows, the series with new episodes. */
    suspend fun latest(shelf: Shelf, limit: Int = 24): List<JellyfinItem> {
        val a = account()
        return JellyfinParse.array(
            get(a, "/Users/${a.userId}/Items/Latest", mapOf(
                "IncludeItemTypes" to (if (shelf == Shelf.MOVIES) "Movie" else "Episode"),
                "Limit" to limit.toString(), "Fields" to FIELDS, "GroupItems" to "true",
            )),
        )
    }

    /** Films and episodes started and not finished, newest first. */
    suspend fun resume(shelf: Shelf, limit: Int = 24): List<JellyfinItem> {
        val a = account()
        return JellyfinParse.page(
            get(a, "/Users/${a.userId}/Items/Resume", mapOf(
                "IncludeItemTypes" to (if (shelf == Shelf.MOVIES) "Movie" else "Episode"),
                "Limit" to limit.toString(), "Fields" to FIELDS, "MediaTypes" to "Video",
            )),
        ).items
    }

    /** The next episode of each show in progress. */
    suspend fun nextUp(limit: Int = 24): List<JellyfinItem> {
        val a = account()
        return JellyfinParse.page(get(a, "/Shows/NextUp", mapOf("UserId" to a.userId, "Limit" to limit.toString(), "Fields" to FIELDS))).items
    }

    suspend fun item(id: String): JellyfinItem? {
        val a = account()
        return JellyfinParse.item(get(a, "/Users/${a.userId}/Items/$id", mapOf("Fields" to FIELDS)))
    }

    suspend fun seasons(seriesId: String): List<JellyfinItem> {
        val a = account()
        return JellyfinParse.page(get(a, "/Shows/$seriesId/Seasons", mapOf("UserId" to a.userId, "Fields" to FIELDS))).items
    }

    suspend fun episodes(seriesId: String, seasonId: String?): List<JellyfinItem> {
        val a = account()
        val q = buildMap {
            put("UserId", a.userId); put("Fields", FIELDS)
            if (seasonId != null) put("SeasonId", seasonId)
        }
        return JellyfinParse.page(get(a, "/Shows/$seriesId/Episodes", q)).items
    }

    /**
     * (v2.9.1) The whole movie or show library with only what matching needs (ids, names, years, provider
     * ids and the watched mark), for Trakt and for finding a recommended title on this server.
     */
    suspend fun everything(shelf: Shelf, played: Boolean? = null): List<JellyfinItem> {
        val a = account()
        val q = buildMap {
            put("IncludeItemTypes", typeOf(shelf)); put("Recursive", "true")
            put("Fields", "ProviderIds,ProductionYear"); put("EnableImageTypes", "Primary")
            if (played != null) put("IsPlayed", played.toString())
        }
        return JellyfinParse.page(get(a, "/Users/${a.userId}/Items", q)).items
    }

    /** (v2.9.1) Every episode with its season and episode numbers and watched mark, for Trakt. */
    suspend fun allEpisodes(played: Boolean? = null): List<JellyfinItem> {
        val a = account()
        val q = buildMap {
            put("IncludeItemTypes", "Episode"); put("Recursive", "true")
            put("Fields", "ProviderIds"); put("EnableImages", "false")
            if (played != null) put("IsPlayed", played.toString())
        }
        return JellyfinParse.page(get(a, "/Users/${a.userId}/Items", q)).items
    }

    /** (v2.9.1) Films or episodes last watched, newest first: what recommendations start from. */
    suspend fun recentlyPlayed(shelf: Shelf, limit: Int = 10): List<JellyfinItem> {
        val a = account()
        return JellyfinParse.page(
            get(a, "/Users/${a.userId}/Items", mapOf(
                "IncludeItemTypes" to (if (shelf == Shelf.MOVIES) "Movie" else "Episode"), "Recursive" to "true",
                "IsPlayed" to "true", "SortBy" to "DatePlayed", "SortOrder" to "Descending",
                "Limit" to limit.toString(), "Fields" to "ProviderIds,SeriesId",
            )),
        ).items
    }

    /** Searches movies and shows by name. */
    suspend fun search(term: String, limit: Int = 40): List<JellyfinItem> {
        val a = account()
        return JellyfinParse.page(
            get(a, "/Users/${a.userId}/Items", mapOf(
                "SearchTerm" to term, "IncludeItemTypes" to "Movie,Series", "Recursive" to "true",
                "Limit" to limit.toString(), "Fields" to FIELDS,
            )),
        ).items
    }

    /** The file as Jellyfin stores it (direct play): DPTV's player handles the container and codecs itself. */
    fun streamUrl(a: JellyfinAccount, item: JellyfinItem): String =
        "${a.server}/Videos/${item.id}/stream?static=true&MediaSourceId=${item.id}&DeviceId=${a.deviceId}"

    /** The sign-in rides in a request header, never in the stream's URL (DPTV keeps URLs out of the logs, not tokens in them). */
    fun streamHeaders(a: JellyfinAccount): String = "$TOKEN_HEADER: ${a.token}"

    /** Poster or backdrop; null when Jellyfin has none. */
    fun imageUrl(a: JellyfinAccount, itemId: String, type: String, tag: String?, maxHeight: Int): String? =
        tag?.let { "${a.server}/Items/$itemId/Images/$type?tag=$it&maxHeight=$maxHeight&quality=90" }

    fun posterUrl(a: JellyfinAccount, item: JellyfinItem, maxHeight: Int = 450): String? =
        imageUrl(a, item.id, "Primary", item.primaryTag, maxHeight)
            ?: item.seriesId?.let { imageUrl(a, it, "Primary", item.seriesPrimaryTag, maxHeight) }

    fun backdropUrl(a: JellyfinAccount, item: JellyfinItem, maxHeight: Int = 720): String? =
        imageUrl(a, item.id, "Backdrop", item.backdropTag, maxHeight)
            ?: item.parentBackdropItemId?.let { imageUrl(a, it, "Backdrop", item.parentBackdropTag, maxHeight) }

    // --- what was watched ------------------------------------------------------------------------

    suspend fun reportStart(itemId: String, positionMs: Long) = report("/Sessions/Playing", itemId, positionMs, paused = false)

    suspend fun reportProgress(itemId: String, positionMs: Long, paused: Boolean) =
        report("/Sessions/Playing/Progress", itemId, positionMs, paused)

    /** Jellyfin decides from this whether the item is now watched (past its own threshold). */
    suspend fun reportStopped(itemId: String, positionMs: Long) = report("/Sessions/Playing/Stopped", itemId, positionMs, paused = false)

    suspend fun setPlayed(itemId: String, played: Boolean) {
        val a = account()
        if (played) post(a, "/Users/${a.userId}/PlayedItems/$itemId", null) else delete(a, "/Users/${a.userId}/PlayedItems/$itemId")
    }

    private suspend fun report(path: String, itemId: String, positionMs: Long, paused: Boolean) {
        val a = store.current() ?: return
        runCatching {
            post(
                a, path,
                JSONObject()
                    .put("ItemId", itemId).put("MediaSourceId", itemId)
                    .put("PositionTicks", positionMs * JellyfinParse.TICKS_PER_MS)
                    .put("IsPaused", paused).put("PlayMethod", "DirectPlay").put("CanSeek", true)
                    .toString(),
            )
        }.onFailure { Log.w(TAG, "report failed: ${it.javaClass.simpleName}") }
    }

    // --- HTTP ------------------------------------------------------------------------------------

    private suspend fun account(): JellyfinAccount = store.current() ?: throw JellyfinException(401, "not signed in")

    private suspend fun get(a: JellyfinAccount, path: String, query: Map<String, String>): String = withContext(Dispatchers.IO) {
        val url = ("${a.server}$path").toHttpUrlOrNull()?.newBuilder()?.apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }?.build()
            ?: throw JellyfinException(0, "bad server address")
        val request = Request.Builder().url(url).header(AUTH_HEADER, authorization(a.deviceId, a.token)).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw JellyfinException(r.code, "HTTP ${r.code} for $path")
            r.body.string()
        }
    }

    private suspend fun post(a: JellyfinAccount, path: String, json: String?) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${a.server}$path")
            .header(AUTH_HEADER, authorization(a.deviceId, a.token))
            .post((json ?: "{}").toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { r -> if (!r.isSuccessful) throw JellyfinException(r.code, "HTTP ${r.code} for $path") }
    }

    private suspend fun delete(a: JellyfinAccount, path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${a.server}$path").header(AUTH_HEADER, authorization(a.deviceId, a.token)).delete().build()
        client.newCall(request).execute().use { r -> if (!r.isSuccessful) throw JellyfinException(r.code, "HTTP ${r.code} for $path") }
    }

    private fun authorization(deviceId: String, token: String?): String = buildString {
        append("MediaBrowser Client=\"DPTV-Player\", Device=\"")
        append(android.os.Build.MODEL.replace("\"", "'"))
        append("\", DeviceId=\"").append(deviceId)
        append("\", Version=\"").append(appVersion().ifBlank { "1" }).append('"')
        if (token != null) append(", Token=\"").append(token).append('"')
    }

    private fun typeOf(shelf: Shelf) = if (shelf == Shelf.MOVIES) "Movie" else "Series"

    private companion object {
        const val TAG = "Jellyfin"
        const val AUTH_HEADER = "Authorization"
        const val TOKEN_HEADER = "X-Emby-Token"
        const val FIELDS = "Overview,Genres,ProductionYear,PremiereDate,DateCreated,ParentId,ProviderIds"
        const val IMAGES = "Primary,Backdrop"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
