package tv.own.owntv.core.sync

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
import tv.own.owntv.core.sync.schedule.ChangeChecker
import tv.own.owntv.core.sync.schedule.SyncScheduler

/**
 * What the app refreshes on its own, run once per process on a cold start (v2.5.3).
 *
 * Playlists and guides no longer refresh on every start or resume, nor on per-source intervals. They
 * follow fixed device-local times instead ([SyncScheduler]: live channels, the guide, and Movies &
 * TV Shows each have their own list), plus the quarter-hourly [ChangeChecker], which syncs soon
 * after the server refreshes. A cold start here only re-arms those, runs at most one catch-up per
 * kind for a slot the device slept through, and runs the one-shot repairs.
 *
 * Manual syncs, adding a playlist and the first import are untouched: they sync every enabled type
 * at once, at their own call sites. A job enqueued by the schedule carries [REASON], and the
 * workers hold such a job back while something is playing ([tv.own.owntv.core.live.WatchSession]).
 *
 * A singleton, so the once-per-process rule holds even when the Activity is rebuilt.
 */
class AutoRefresh(
    private val context: android.content.Context,
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
    private val profileDao: ProfileDao,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val epgSyncScheduler: EpgSyncScheduler,
    private val epgSourceStore: EpgSourceStore,
    private val epgDao: EpgDao,
    private val importFinalizer: ImportFinalizer,
    private val syncScheduler: SyncScheduler,
) {
    private var coldStartCheckDone = false

    /**
     * [includeStartup] true on a cold start; a resume ([includeStartup] false) no longer refreshes
     * anything (v2.5.3) — the alarms and the change check run without the app in front.
     */
    suspend fun check(includeStartup: Boolean) {
        if (!includeStartup) return
        synchronized(this) {
            if (coldStartCheckDone) return
            coldStartCheckDone = true
        }
        val pid = profileDao.resolveExistingProfileId(settings.activeProfileId.first()) ?: return
        defaultToStartupOnce(pid)
        restoreVodScopeOnce()
        refillGuideEmptiedByMigration()
        runCatching {
            syncScheduler.armAll()
            syncScheduler.catchUpAll()
        }.onFailure { Log.w(TAG, "sync times arm/catch-up failed", it) }
        runCatching { ChangeChecker.schedule(context) }
            .onFailure { Log.w(TAG, "change check schedule failed", it) }
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

    companion object {
        private const val TAG = "OwnTVHome"
        /** The work reason the sync workers recognise as automatic, and hold back during playback. */
        const val REASON = "auto_refresh"
    }
}
