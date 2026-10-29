package tv.own.owntv.core.epg

import tv.own.owntv.core.live.DEFAULT_CATCHUP_DAYS
import tv.own.owntv.core.parser.XtEpgEntry

/**
 * The rules for adding a catch-up channel's past from the provider's own schedule of it
 * ([tv.own.owntv.core.repository.EpgRepository.fillArchiveGuide]).
 *
 * Many Xtream providers put about a day of past programmes in `xmltv.php` while their archive keeps
 * up to a week: the recordings exist, the guide cannot reach them. The per-channel schedule lists the
 * whole archive. What it adds only ever extends the guide backwards — the feed's rows are never
 * replaced or overlapped.
 */
internal object ArchiveGuidePlan {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A channel's schedule is asked for again after this long… */
    const val REFETCH_MS = 6L * 60 * 60 * 1000

    /** …or after this long when the last attempt failed. */
    const val RETRY_MS = 30L * 60 * 1000

    fun due(lastTryMs: Long?, nowMs: Long): Boolean = lastTryMs == null || nowMs - lastTryMs >= REFETCH_MS

    /** The attempt time to record after a failure, so the next one is due [RETRY_MS] later. */
    fun failedAt(nowMs: Long): Long = nowMs - REFETCH_MS + RETRY_MS

    /**
     * The oldest instant worth storing: the channel's archive depth (the shared default when the
     * playlist does not say), at least one day and never beyond [maxBackMs], the guide's retention.
     */
    fun windowStart(nowMs: Long, catchupDays: Int, maxBackMs: Long): Long {
        val days = (catchupDays.takeIf { it > 0 } ?: DEFAULT_CATCHUP_DAYS).coerceAtLeast(1)
        return nowMs - (days * DAY_MS).coerceAtMost(maxBackMs)
    }

    /**
     * What to add: finished programmes inside the window that end by [guideStartMs], the oldest
     * programme the guide feeds hold for the channel, and are not already stored from an earlier
     * fill ([storedStarts]). Nothing when the feeds hold nothing for the channel: its row then comes
     * from the provider's short EPG, which stored past rows would hide. Duplicates are dropped.
     */
    fun toStore(
        listings: List<XtEpgEntry>,
        guideStartMs: Long?,
        storedStarts: Set<Long>,
        windowStartMs: Long,
        nowMs: Long,
    ): List<XtEpgEntry> {
        if (guideStartMs == null) return emptyList()
        return listings.asSequence()
            .filter { it.startMs in windowStartMs until it.stopMs && it.stopMs <= nowMs && it.stopMs <= guideStartMs }
            .filter { it.title.isNotBlank() && it.startMs !in storedStarts }
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
            .toList()
    }
}
