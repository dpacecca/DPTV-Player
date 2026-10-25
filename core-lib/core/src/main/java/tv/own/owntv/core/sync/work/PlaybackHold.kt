package tv.own.owntv.core.sync.work

import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.live.WatchSession

/**
 * "Hold back while something is playing" for an automatic sync (v2.5.3). With no deadline it waits
 * as long as playback lasts, as before; with one, it gives up at [holdUntilMs] and the run is
 * skipped until the next slot. True when the sync may go ahead.
 */
internal suspend fun awaitPlaybackIdle(watchSession: WatchSession, holdUntilMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (watchSession.watching.value.isEmpty()) return true
    if (holdUntilMs <= 0L) {
        watchSession.awaitIdle()
        return true
    }
    val left = holdUntilMs - nowMs
    if (left <= 0L) return false
    return withTimeoutOrNull(left) { watchSession.awaitIdle() } != null
}
