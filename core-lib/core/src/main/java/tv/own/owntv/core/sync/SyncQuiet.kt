package tv.own.owntv.core.sync

import android.os.Process
import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * Keeps the menus responsive while a playlist syncs (v2.5.3).
 *
 * A re-sync commits its rows a chunk at a time, and every commit invalidates Room's observers: each
 * open list re-ran its page query, and every count and category flow re-ran its GROUP BY, once per
 * chunk — on a 2 GB, four-slow-core TV that is what made the menus stutter. While a sync runs these
 * observers are throttled to one refresh per [CALM_MS]; the first change still shows at once, and
 * the last one always lands, because Room's invalidations conflate while a refresh waits.
 */
object SyncQuiet {
    /** At most one list/count refresh per this long while a sync runs. */
    const val CALM_MS = 1_500L

    private val running = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val syncing: Boolean get() = running.get() > 0

    fun begin() {
        running.incrementAndGet()
    }

    fun end() {
        running.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    internal fun later(action: () -> Unit) {
        scope.launch {
            delay(CALM_MS)
            action()
        }
    }
}

/** A Room flow that refreshes at most once per [SyncQuiet.CALM_MS] while a sync runs (v2.5.3). */
fun <T> Flow<T>.calmDuringSync(): Flow<T> = transform { value ->
    emit(value)
    // The upstream waits here, so invalidations that arrive meanwhile collapse into one re-query.
    if (SyncQuiet.syncing) delay(SyncQuiet.CALM_MS)
}

/** A list's paging source whose invalidations are coalesced while a sync runs (v2.5.3). */
fun <K : Any, V : Any> PagingSource<K, V>.calmDuringSync(): PagingSource<K, V> = CalmPagingSource(this)

private class CalmPagingSource<K : Any, V : Any>(private val delegate: PagingSource<K, V>) : PagingSource<K, V>() {
    private val pending = AtomicBoolean(false)

    init {
        delegate.registerInvalidatedCallback {
            if (!SyncQuiet.syncing) {
                invalidate()
            } else if (pending.compareAndSet(false, true)) {
                // The list keeps what it shows; a scroll that needs a new page still refreshes at
                // once, because the stale source answers it with Invalid.
                SyncQuiet.later { invalidate() }
            }
        }
        registerInvalidatedCallback { delegate.invalidate() }
    }

    override val jumpingSupported: Boolean get() = delegate.jumpingSupported
    override val keyReuseSupported: Boolean get() = delegate.keyReuseSupported

    override fun getRefreshKey(state: PagingState<K, V>): K? = delegate.getRefreshKey(state)

    override suspend fun load(params: LoadParams<K>): LoadResult<K, V> = delegate.load(params)
}

/**
 * Runs a sync's threads at background priority while the sync's coroutines are on them, and puts
 * each thread back as it leaves (v2.5.3). Sync work hops between Dispatchers.IO, Default and Room's
 * executors, all shared with the UI's own work, so a dedicated pool would not reach the parsing
 * inside an HTTP read; a context element follows the coroutine wherever it runs.
 */
internal class BackgroundThreadPriority : AbstractCoroutineContextElement(Key), ThreadContextElement<Int> {
    companion object Key : CoroutineContext.Key<BackgroundThreadPriority>

    override fun updateThreadContext(context: CoroutineContext): Int {
        val old = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        if (old < Process.THREAD_PRIORITY_BACKGROUND) runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        return old
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int) {
        if (oldState < Process.THREAD_PRIORITY_BACKGROUND) runCatching { Process.setThreadPriority(oldState) }
    }
}
