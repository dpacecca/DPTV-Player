package tv.own.owntv.core.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient

/**
 * (DPTV v2.9.31) DPTV-Server's movie categories — Trending this week, New releases, one per genre and
 * one per decade — with their films resolved to this device's movies.
 *
 * Asked of every Xtream playlist in use; a server that is not DPTV-Server refuses the action and simply
 * contributes nothing. Kept in memory for half an hour per set of playlists.
 */
class SmartCategoriesRepository(
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val xtream: XtreamClient,
) {
    /**
     * A film of a category, with the release date ("YYYY-MM-DD" or "YYYY") and rating to sort by.
     * [movie] carries the server's "Title (year)" as its name; (v2.10.1) [sources] is every copy of the
     * film on this device, this one first, under the provider's own names, for "which source?".
     */
    data class Film(
        val movie: MovieEntity,
        val release: String?,
        val rating: Double?,
        val sources: List<MovieEntity> = listOf(movie),
    )

    data class Category(val id: String, val name: String, val kind: String, val films: List<Film>)

    private var cached: Pair<List<Long>, Pair<Long, List<Category>>>? = null

    suspend fun categories(sourceIds: List<Long>, force: Boolean = false): List<Category> = withContext(Dispatchers.IO) {
        val key = sourceIds.sorted()
        cached?.takeIf { !force && it.first == key && System.currentTimeMillis() - it.second.first < TtlMs }
            ?.let { return@withContext it.second.second }
        val out = ArrayList<Category>()
        for (sourceId in key) {
            val source = sourceDao.getById(sourceId)?.takeIf { it.type == SourceType.XTREAM } ?: continue
            val catalog = runCatching { xtream.getSmartCategories(source) }
                .onFailure { Log.d(TAG, "no movie categories from source $sourceId: ${it.javaClass.simpleName}") }
                .getOrNull() ?: continue
            val wanted = (catalog.categories.flatMap { it.streamIds } + catalog.films.values.flatMap { it.others }).distinct()
            val byRemote = HashMap<String, MovieEntity>(wanted.size)
            wanted.chunked(500).forEach { chunk ->
                movieDao.findByRemoteIds(sourceId, chunk).forEach { m -> m.remoteId?.let { byRemote[it] = m } }
            }
            catalog.categories.mapNotNullTo(out) { c ->
                val films = c.streamIds.mapNotNull { id ->
                    byRemote[id]?.let { m ->
                        val f = catalog.films[id]
                        Film(
                            movie = f?.title?.let { m.copy(name = it) } ?: m,
                            release = f?.release,
                            rating = f?.rating ?: m.rating?.takeIf { it > 0 },
                            sources = listOf(m) + f?.others.orEmpty().mapNotNull(byRemote::get),
                        )
                    }
                }
                if (films.isEmpty()) null else Category("$sourceId:${c.id}", c.name, c.kind, films)
            }
        }
        cached = key to (System.currentTimeMillis() to out)
        out
    }

    companion object {
        private const val TAG = "SmartCategories"
        const val TtlMs = 30L * 60L * 1000L
    }
}
