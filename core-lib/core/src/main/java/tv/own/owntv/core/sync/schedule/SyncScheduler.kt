package tv.own.owntv.core.sync.schedule

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.AutoRefresh
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncScheduler

/**
 * The fixed sync times (v2.5.3). One WorkManager alarm per [ScheduledKind], each a unique one-time
 * work delayed to that kind's next local time and re-armed after every run, so a DST shift or a
 * new time zone is picked up at the next arm. WorkManager keeps the alarms across reboots; the app
 * re-arms them on every cold start and on a time or time-zone change.
 *
 * A run starts the per-source syncs as [AutoRefresh.REASON] jobs, so they still hold back while
 * something plays — but only until [SyncTimes.HOLD_WINDOW] has passed; then that slot is skipped.
 *
 * A playlist or guide whose own setting is Off is left out, as before.
 */
class SyncScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val epgSourceStore: EpgSourceStore,
    private val epgDao: EpgDao,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val epgSyncScheduler: EpgSyncScheduler,
    private val importFinalizer: ImportFinalizer,
    private val state: SyncStateStore,
) {
    suspend fun times(kind: ScheduledKind): List<Int> = settings.syncTimes(kind).first()

    /** (Re-)arms every kind's alarm from the current clock and zone. A run in progress is left to re-arm itself. */
    suspend fun armAll() {
        ScheduledKind.entries.forEach { kind -> arm(kind, ExistingWorkPolicy.REPLACE) }
    }

    private suspend fun arm(kind: ScheduledKind, policy: ExistingWorkPolicy) {
        val wm = WorkManager.getInstance(context)
        val name = workName(kind)
        val delay = SyncTimes.delayToNext(times(kind), ZonedDateTime.now())
        if (delay == null) {
            wm.cancelUniqueWork(name)
            Log.i(TAG, "alarm cleared kind=$kind (no times)")
            return
        }
        if (policy == ExistingWorkPolicy.REPLACE) {
            // Replacing a running alarm would cancel the very run that is starting the syncs.
            val running = runCatching { wm.getWorkInfosForUniqueWorkFlow(name).first() }.getOrNull()
                .orEmpty().any { it.state == WorkInfo.State.RUNNING }
            if (running) return
        }
        val request = OneTimeWorkRequestBuilder<ScheduledSyncWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ScheduledSyncWorker.KEY_KIND to kind.name))
            .addTag(TAG_ALARM)
            .build()
        wm.enqueueUniqueWork(name, policy, request)
        Log.i(TAG, "alarm armed kind=$kind inMin=${delay / 60_000} policy=$policy")
    }

    /**
     * The alarm fired (or a cold start asks for a catch-up): start the syncs if the latest slot is
     * still owed. [fromAlarm] re-arms behind the running work instead of replacing it.
     */
    suspend fun runIfDue(kind: ScheduledKind, fromAlarm: Boolean, now: ZonedDateTime = ZonedDateTime.now()) {
        try {
            val slot = SyncTimes.lastSlot(times(kind), now)?.toInstant() ?: return
            val handled = state.handledSlotAt(kind)?.let(Instant::ofEpochMilli)
            val ok = state.lastSuccessAt(kind)?.let(Instant::ofEpochMilli)
            if (!SyncTimes.isDue(slot, handled, ok)) {
                Log.i(TAG, "slot not due kind=$kind slot=$slot handled=$handled lastOk=$ok fromAlarm=$fromAlarm")
                return
            }
            state.markHandled(kind, slot.toEpochMilli())
            // The playback window counts from when the run actually starts: a catch-up after a
            // night switched off gets the same three hours as an on-time run.
            val holdUntil = now.toInstant().plus(SyncTimes.HOLD_WINDOW).toEpochMilli()
            val started = startSyncs(kind, null, holdUntil, if (fromAlarm) "alarm" else "catch_up")
            Log.i(TAG, "slot run kind=$kind slot=$slot fromAlarm=$fromAlarm syncs=$started")
            SyncCheckLog.event("sync-check: ${kind.name.lowercase()} ${if (fromAlarm) "scheduled" else "catch-up"} run, $started source(s)")
        } finally {
            if (fromAlarm) arm(kind, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    /** One catch-up per kind, on a cold start. */
    suspend fun catchUpAll() {
        seedFromHistory()
        ScheduledKind.entries.forEach { runIfDue(it, fromAlarm = false) }
    }

    /**
     * First start on v2.5.3: nothing is recorded yet, so the last full sync (every earlier version
     * synced everything) stands in for it. Without this, the upgrade's first start would catch up
     * all three kinds at once.
     */
    private suspend fun seedFromHistory() {
        val lastPlaylist = sourceDao.getAllOnce().mapNotNull { it.lastSyncAt }.maxOrNull()
        val lastGuide = epgSourceStore.getAll().mapNotNull { it.lastSyncAt }.maxOrNull()
        ScheduledKind.entries.forEach { kind ->
            if (state.lastSuccessAt(kind) != null || state.handledSlotAt(kind) != null) return@forEach
            val at = (if (kind == ScheduledKind.EPG) lastGuide else lastPlaylist) ?: return@forEach
            state.markSuccess(kind, at)
        }
    }

    /**
     * Starts [kind]'s syncs for every eligible source, or only [onlyIds]. Returns how many were
     * enqueued. A playlist that never finished its first import is skipped — its own import owns it.
     */
    suspend fun startSyncs(kind: ScheduledKind, onlyIds: Set<Long>?, holdUntilMs: Long, why: String): Int {
        var count = 0
        if (kind == ScheduledKind.EPG) {
            val modes = settings.epgAutoRefresh.first()
            epgSourceStore.getAll().forEach { src ->
                if (onlyIds != null && src.id !in onlyIds) return@forEach
                if (!epgIncluded(modes[src.id])) return@forEach
                epgSyncScheduler.enqueueSync(
                    src.id,
                    reason = AutoRefresh.REASON,
                    baseProgrammes = epgDao.countForSources(listOf(src.id)),
                    policy = ExistingWorkPolicy.KEEP,
                    holdUntilMs = holdUntilMs,
                )
                count++
            }
            return count
        }
        val modes = settings.playlistAutoRefresh.first()
        val wanted = kind.contentTypes ?: return 0
        sourceDao.getAllOnce().forEach { source ->
            if (onlyIds != null && source.id !in onlyIds) return@forEach
            if (source.type == SourceType.LOCAL_BACKUP || source.lastSyncAt == null) return@forEach
            if (!playlistIncluded(modes[source.id])) return@forEach
            val types = wanted.intersect(SyncContentTypes.enabledFor(source))
            if (!types.hasAny) return@forEach
            val counts = importFinalizer.contentCounts(source.id)
            catalogSyncScheduler.enqueueSync(
                source.id,
                reason = AutoRefresh.REASON,
                contentTypes = types,
                baseItemCount = counts.channels + counts.movies + counts.series,
                // A live pass never queues behind a running sync of the same playlist; a films pass
                // waits for it instead of being dropped, so the daily run is not lost to a 06:30 overlap.
                policy = if (kind == ScheduledKind.VOD) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                holdUntilMs = holdUntilMs,
            )
            Log.i(TAG, "sync enqueued kind=$kind sourceId=${source.id} types=$types why=$why")
            count++
        }
        return count
    }

    companion object {
        private const val TAG = "SyncScheduler"
        const val TAG_ALARM = "sync-times-alarm"

        fun workName(kind: ScheduledKind) = "sync-times-${kind.name.lowercase()}"

        /** Off stays out of every automatic run; every other old mode now means "at the sync times". */
        fun playlistIncluded(mode: PlaylistRefresh?): Boolean = (mode ?: PlaylistRefresh.DEFAULT).mode != PlaylistAutoRefresh.OFF

        fun epgIncluded(mode: EpgRefresh?): Boolean = (mode ?: EpgRefresh.DEFAULT).mode != EpgAutoRefresh.OFF
    }
}

/** A fixed-time alarm for one [ScheduledKind] (v2.5.3). Short: it only decides and enqueues. */
class ScheduledSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND)?.let { runCatching { ScheduledKind.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        scheduler.runIfDue(kind, fromAlarm = true)
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
    }
}

/** "Needs a network" — the change check's constraint. */
internal fun connectedConstraints(): Constraints =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
