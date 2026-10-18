package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncScheduler

/**
 * The playlist and guide "Auto refresh" settings, for both apps. It used to live in the TV app's
 * shell, so the phone stored the setting and never acted on it.
 *
 * - `includeStartup = true` (cold start): runs once per process, refreshes STARTUP sources
 *   unconditionally and interval sources whose data is at least as old as their threshold. Never
 *   time-throttled, so STARTUP always fires on a real cold start.
 * - `includeStartup = false` (app back in the foreground): skips STARTUP sources and refreshes
 *   interval sources past their threshold. Throttled to once per [RESUME_THROTTLE_MS].
 *
 * Enqueues use [ExistingWorkPolicy.KEEP], so a source already syncing or queued is left alone.
 * Manual re-syncs use REPLACE at their own call sites. A job enqueued here carries
 * [REASON], and the workers hold such a job back while something is playing ([tv.own.owntv.core.live.WatchSession]).
 *
 * A singleton, so the once-per-process rule holds even when the Activity is rebuilt.
 */
class AutoRefresh(
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
    private val profileDao: ProfileDao,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val epgSyncScheduler: EpgSyncScheduler,
    private val epgSourceStore: EpgSourceStore,
    private val epgDao: EpgDao,
    private val importFinalizer: ImportFinalizer,
) {
    private var coldStartCheckDone = false
    private var lastResumeCheckAtElapsed = 0L

    suspend fun check(includeStartup: Boolean) {
        synchronized(this) {
            if (includeStartup) {
                if (coldStartCheckDone) return
                coldStartCheckDone = true
            } else {
                val now = SystemClock.elapsedRealtime()
                if (now - lastResumeCheckAtElapsed < RESUME_THROTTLE_MS) return
                lastResumeCheckAtElapsed = now
            }
        }
        val nowMs = System.currentTimeMillis()
        val pid = profileDao.resolveExistingProfileId(settings.activeProfileId.first()) ?: return
        if (includeStartup) defaultToStartupOnce(pid)
        if (includeStartup) restoreVodScopeOnce()
        // --- Playlist sources ---
        val playlistModes = settings.playlistAutoRefresh.first()
        run {
            sourceRepository.observeSources(pid).first().forEach { source ->
                val mode = playlistModes[source.id] ?: PlaylistRefresh.DEFAULT
                if (shouldRefresh(mode, source.lastSyncAt, nowMs, includeStartup)) {
                    val counts = importFinalizer.contentCounts(source.id)
                    Log.d(TAG, "checkAutoRefresh playlist sourceId=${source.id} mode=$mode — enqueuing")
                    catalogSyncScheduler.enqueueSync(
                        source.id,
                        reason = REASON,
                        contentTypes = SyncContentTypes.enabledOf(source),
                        baseItemCount = counts.channels + counts.movies + counts.series,
                        policy = ExistingWorkPolicy.KEEP,
                    )
                }
            }
        }
        // --- EPG sources ---
        val epgModes = settings.epgAutoRefresh.first()
        run {
            epgSourceStore.getAll().forEach { src ->
                val mode = epgModes[src.id] ?: EpgRefresh.DEFAULT
                if (shouldRefreshEpg(mode, src.lastSyncAt, nowMs, includeStartup)) {
                    val base = epgDao.countForSources(listOf(src.id))
                    Log.d(TAG, "checkAutoRefresh epg sourceId=${src.id} mode=$mode — enqueuing")
                    epgSyncScheduler.enqueueSync(
                        src.id,
                        reason = REASON,
                        baseProgrammes = base,
                        policy = ExistingWorkPolicy.KEEP,
                    )
                }
            }
        }
        if (includeStartup) refillGuideEmptiedByMigration()
    }

    /**
     * DPTV-Player v2.1.0: the playlists and guides are refreshed on start. Once per install, every playlist
     * and guide still set to Off (the old default every add path wrote) is switched to On startup; a schedule
     * the user picked is kept, and Off chosen again afterwards stays Off.
     */
    private suspend fun defaultToStartupOnce(pid: Long) {
        if (settings.startupSyncDefaulted.first()) return
        runCatching {
            val playlistModes = settings.playlistAutoRefresh.first()
            sourceRepository.observeSources(pid).first().forEach { source ->
                if ((playlistModes[source.id] ?: PlaylistRefresh.OFF).mode == PlaylistAutoRefresh.OFF) {
                    settings.setPlaylistAutoRefresh(source.id, PlaylistRefresh.STARTUP)
                }
            }
            val epgModes = settings.epgAutoRefresh.first()
            epgSourceStore.getAll().forEach { src ->
                if ((epgModes[src.id] ?: EpgRefresh.OFF).mode == EpgAutoRefresh.OFF) {
                    settings.setEpgAutoRefresh(src.id, EpgRefresh.STARTUP)
                }
            }
        }.onFailure { Log.w(TAG, "startup sync default failed", it) }
        settings.markStartupSyncDefaulted()
    }

    /**
     * DPTV-Player: Movies and Series are back. While they were hidden, the add-playlist form saved every
     * Xtream and Stalker playlist with Movies/Series switched off, and M3U playlists were synced live-only
     * whatever their switches said. Once per install, every playlist (on every profile) gets Movies and
     * Series switched on, and each one that can now bring in more is re-synced so the films and series
     * arrive without a manual refresh. Off chosen again afterwards stays Off. A local backup source is
     * live-only and is left alone.
     */
    private suspend fun restoreVodScopeOnce() {
        if (settings.vodScopeRestored.first()) return
        runCatching {
            val seen = HashSet<Long>()
            profileDao.getAllOnce().forEach { profile ->
                sourceRepository.observeSources(profile.id).first().forEach source@{ source ->
                    if (!seen.add(source.id) || source.type == SourceType.LOCAL_BACKUP) return@source
                    val updated = source.copy(syncMovies = true, syncSeries = true)
                    val flipped = updated != source
                    if (flipped) sourceRepository.updateSource(updated)
                    // M3U was synced live-only before, so it needs a pass even when its switches were on.
                    if (!flipped && source.type != SourceType.M3U) return@source
                    val counts = importFinalizer.contentCounts(source.id)
                    Log.i(TAG, "vodRestore sourceId=${source.id} type=${source.type} flipped=$flipped — enqueuing")
                    catalogSyncScheduler.enqueueSync(
                        source.id,
                        reason = REASON,
                        contentTypes = SyncContentTypes.enabledFor(updated),
                        baseItemCount = counts.channels + counts.movies + counts.series,
                        policy = ExistingWorkPolicy.KEEP,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "vod scope restore failed", it) }
        // Marked regardless, like the other one-shots: a failure must not retry on every launch.
        settings.markVodScopeRestored()
    }

    /**
     * Audit D4 — refill a guide that `MIGRATION_8_9` emptied.
     *
     * That migration deletes every `epg_programmes` row and nothing schedules a re-fetch, so an
     * upgrading user's Guide is simply blank until they think to re-sync EPG by hand. Runs **once per
     * install** (so it also catches users who passed through 8→9 in an earlier version) and only for
     * sources that had previously synced successfully but now hold zero programmes — that is the
     * exact signature of the wipe.
     *
     * EPG is opt-in by design, and this respects that: adding an EPG source *is* the opt-in, and a
     * source the user has never synced is left alone rather than silently downloaded. The enqueue is
     * an ordinary [EpgSyncScheduler] job, so it shows the standard EPG-syncing pill.
     *
     * The one-shot flag is read first and the DB is touched only when it is unset, so this adds no
     * work to a normal cold start.
     */
    private suspend fun refillGuideEmptiedByMigration() {
        if (settings.epgRefillChecked.first()) return
        runCatching {
            val sources = epgSourceStore.getAll().filter { (it.lastSyncAt ?: 0L) > 0L }
            for (src in sources) {
                if (epgDao.countForSources(listOf(src.id)) > 0) continue
                Log.i(TAG, "epgRefill sourceId=${src.id} — synced before but guide is empty, re-fetching")
                epgSyncScheduler.enqueueSync(
                    src.id,
                    reason = "migration_refill",
                    baseProgrammes = 0,
                    policy = ExistingWorkPolicy.KEEP,
                )
            }
        }.onFailure { Log.w(TAG, "epgRefill check failed", it) }
        // Marked regardless: a failed check must not retry on every launch forever, and a failed
        // *sync* is already retried by the scheduler's own policy.
        settings.markEpgRefillChecked()
    }

    /**
     * Whether a playlist source should auto-refresh now. OFF never; STARTUP only on cold start
     * ([includeStartup]); interval modes when `now - lastSyncAt >= threshold` (a null lastSyncAt — never
     * successfully synced — counts as infinitely stale so recovery happens).
     */
    private fun shouldRefresh(
        refresh: PlaylistRefresh,
        lastSyncAt: Long?,
        now: Long,
        includeStartup: Boolean,
    ): Boolean = when (refresh.mode) {
        PlaylistAutoRefresh.OFF -> false
        PlaylistAutoRefresh.STARTUP -> includeStartup
        else -> (now - (lastSyncAt ?: 0L)) >= (refresh.thresholdMs ?: Long.MAX_VALUE)
    }

    /** EPG equivalent of [shouldRefresh]. */
    private fun shouldRefreshEpg(
        refresh: EpgRefresh,
        lastSyncAt: Long?,
        now: Long,
        includeStartup: Boolean,
    ): Boolean = when (refresh.mode) {
        EpgAutoRefresh.OFF -> false
        EpgAutoRefresh.STARTUP -> includeStartup
        // MANUAL's threshold is its day count; every other mode carries its own. Identical shape to
        // [shouldRefresh], which is the point of the parity.
        else -> (now - (lastSyncAt ?: 0L)) >= (refresh.thresholdMs ?: Long.MAX_VALUE)
    }

    companion object {
        private const val TAG = "OwnTVHome"
        /** The work reason the sync workers recognise as automatic, and hold back during playback. */
        const val REASON = "auto_refresh"
        /** Minimum gap between resume-triggered checks (rotation, rapid background/foreground). */
        private const val RESUME_THROTTLE_MS = 60_000L
    }
}
