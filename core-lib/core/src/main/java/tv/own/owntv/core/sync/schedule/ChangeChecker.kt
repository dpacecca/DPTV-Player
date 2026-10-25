package tv.own.owntv.core.sync.schedule

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient

/**
 * The change check (v2.5.3): every quarter of an hour, and on a cold start, each Xtream playlist and
 * each guide is asked cheaply whether it changed, and only what changed is synced. The fixed sync
 * times stay as the backup.
 *
 * Signals, best first:
 * 1. DPTV-Server's `dptv_sync_status` — one small JSON with a revision per type. Whether a server
 *    is DPTV-Server is remembered for a day, so other panels are not asked every time.
 * 2. A conditional HEAD on `get.php` (ETag / Last-Modified; 304, or the values compared here).
 * 3. Conditional GETs on the small category lists (and the live stream list when it is not huge),
 *    hashed as they stream when the server sends no validators at all.
 *
 * Never logs or shows a URL or a credential: lines name the source by id and the signal only.
 */
class ChangeChecker(
    private val http: HttpClient,
    private val xtream: XtreamClient,
    private val sourceDao: SourceDao,
    private val epgSourceStore: EpgSourceStore,
    private val scheduler: SyncScheduler,
    private val state: SyncStateStore,
) {
    private val mutex = Mutex()

    /** Checks every Xtream playlist, then every guide. */
    suspend fun checkAll(why: String): Unit = mutex.withLock {
        val dptvEpg = HashMap<String, DptvSyncStatus>()
        val epgChangedHosts = HashSet<String>()
        sourceDao.getAllOnce().filter { it.type == SourceType.XTREAM }.distinctBy { it.id }.forEach { source ->
            val outcome = runCatching { checkPlaylist(source) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "playlist check failed sourceId=${source.id}", it) }
                .getOrNull() ?: return@forEach
            hostOf(source.url)?.let { host ->
                outcome.dptv?.let { dptvEpg[host] = it }
                if (outcome.epgChanged) epgChangedHosts += host
            }
        }
        epgSourceStore.getAll().forEach { src ->
            runCatching { checkGuide(src, dptvEpg, epgChangedHosts) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "guide check failed id=${src.id}", it) }
        }
        Log.i(TAG, "check pass done why=$why")
    }

    /** "Check now" for one row: a playlist id, or an EPG source's (negative) id. */
    suspend fun checkOne(id: Long): Unit = mutex.withLock { checkOneLocked(id) }

    private suspend fun checkOneLocked(id: Long) {
        if (id >= 0) {
            val source = sourceDao.getById(id) ?: return
            if (source.type != SourceType.XTREAM) return
            val outcome = checkPlaylist(source)
            // A DPTV-Server's guide revision covers the guides served from the same host.
            val host = hostOf(source.url)
            if (host != null && (outcome.dptv != null || outcome.epgChanged)) {
                epgSourceStore.getAll().filter { hostOf(it.url) == host }.forEach { src ->
                    checkGuide(src, outcome.dptv?.let { mapOf(host to it) }.orEmpty(), if (outcome.epgChanged) setOf(host) else emptySet())
                }
            }
        } else {
            val src = epgSourceStore.getAll().firstOrNull { it.id == id } ?: return
            // Is this guide served by a DPTV-Server playlist? Then its status answers for the guide.
            val host = hostOf(src.url)
            val dptvSource = sourceDao.getAllOnce().firstOrNull {
                it.type == SourceType.XTREAM && hostOf(it.url) == host && state.check(it.id)?.isDptv == true
            }
            if (dptvSource != null) checkOneLocked(dptvSource.id) else checkGuide(src, emptyMap(), emptySet())
        }
    }

    private class PlaylistOutcome(val dptv: DptvSyncStatus?, val epgChanged: Boolean)

    private sealed interface DptvAnswer {
        data class Status(val status: DptvSyncStatus) : DptvAnswer
        data object NotDptv : DptvAnswer
        data class Failed(val code: Int) : DptvAnswer
    }

    private suspend fun askDptv(source: SourceEntity): DptvAnswer = try {
        val body = http.get(xtream.actionUrl(source, ACTION_DPTV_STATUS), source.userAgent) { input ->
            // The real answer is a few hundred bytes; a panel that returns something big is not DPTV-Server.
            val buf = ByteArray(MAX_STATUS_BYTES)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            String(buf, 0, n, Charsets.UTF_8)
        }
        ChangeDecisions.parseDptvStatus(body)?.let { DptvAnswer.Status(it) } ?: DptvAnswer.NotDptv
    } catch (c: CancellationException) {
        throw c
    } catch (e: HttpClient.HttpStatusException) {
        DptvAnswer.Failed(e.code)
    } catch (e: Exception) {
        DptvAnswer.Failed(0)
    }

    private suspend fun checkPlaylist(source: SourceEntity): PlaylistOutcome {
        val now = System.currentTimeMillis()
        var st = state.check(source.id) ?: SourceCheckState(source.id)
        val askDptv = st.isDptv == true || !ChangeDecisions.detectionFresh(st.dptvCheckedAt, now)
        if (askDptv) {
            when (val answer = askDptv(source)) {
                is DptvAnswer.Status -> return applyDptv(source, st, answer.status, now)
                DptvAnswer.NotDptv -> st = st.copy(isDptv = false, dptvCheckedAt = now, dptvRevs = null)
                is DptvAnswer.Failed -> if (st.isDptv == true) {
                    state.putCheck(st.copy(signal = ChangeSignal.FAILED, failureCode = answer.code, lastCheckAt = now))
                    SyncCheckLog.event("sync-check: playlist #${source.id} DPTV-Server status failed (HTTP ${answer.code})")
                    return PlaylistOutcome(null, false)
                } else if (answer.code != 0) {
                    // An error for an unknown action is still an answer: not DPTV-Server.
                    st = st.copy(isDptv = false, dptvCheckedAt = now, dptvRevs = null)
                }
            }
        }
        return checkGeneric(source, st, now)
    }

    private suspend fun applyDptv(source: SourceEntity, old: SourceCheckState, status: DptvSyncStatus, now: Long): PlaylistOutcome {
        val triggers = ChangeDecisions.dptvTriggers(old.dptvRevs, status)
        val refreshAt = status.lastSourceSyncAt?.times(1000)
        state.putCheck(
            old.copy(
                isDptv = true,
                dptvCheckedAt = now,
                dptvRevs = status.revs(),
                signal = ChangeSignal.DPTV,
                lastCheckAt = now,
                lastChangeAt = refreshAt ?: if (triggers.isNotEmpty()) now else old.lastChangeAt,
                failureCode = null,
            ),
        )
        val started = act(source.id, triggers - ScheduledKind.EPG, now)
        SyncCheckLog.event(
            "sync-check: playlist #${source.id} DPTV-Server ${status.version.orEmpty()} " +
                "changed=${triggers.joinToString(",") { it.name.lowercase() }.ifEmpty { "none" }}" +
                (if (started.isNotEmpty()) " → sync ${started.joinToString(",") { it.name.lowercase() }}" else ""),
        )
        return PlaylistOutcome(status, ScheduledKind.EPG in triggers)
    }

    private suspend fun checkGeneric(source: SourceEntity, old: SourceCheckState, now: Long): PlaylistOutcome {
        val validators = old.validators.toMutableMap()
        var lastFailure = 0
        suspend fun probe(key: String, url: String, head: Boolean, maxBytes: Long): ProbeDecision {
            val result = http.probe(url, source.userAgent, head = head, validators = validators[key], hashBody = !head, maxBodyBytes = maxBytes)
            val decision = ChangeDecisions.decide(validators[key], result)
            decision.store?.let { validators[key] = it }
            if (decision.verdict == Verdict.FAILED) lastFailure = result.code
            return decision
        }
        // Live: the playlist's own validators first; the lists when it gives none.
        val head = probe(KEY_M3U, xtream.m3uPlusUrl(source), head = true, maxBytes = 0)
        val liveDecisions = if (head.verdict == Verdict.CHANGED || head.verdict == Verdict.UNCHANGED || head.verdict == Verdict.BASELINE) {
            listOf(head)
        } else {
            listOf(
                probe(KEY_LIVE_CATS, xtream.actionUrl(source, "get_live_categories"), head = false, maxBytes = MAX_CATEGORY_BYTES),
                probe(KEY_LIVE_STREAMS, xtream.actionUrl(source, "get_live_streams"), head = false, maxBytes = ChangeDecisions.MAX_HASH_BYTES),
            )
        }
        val vodDecisions = listOf(
            probe(KEY_VOD_CATS, xtream.actionUrl(source, "get_vod_categories"), head = false, maxBytes = MAX_CATEGORY_BYTES),
            probe(KEY_SERIES_CATS, xtream.actionUrl(source, "get_series_categories"), head = false, maxBytes = MAX_CATEGORY_BYTES),
        )
        val live = ChangeDecisions.combine(liveDecisions)
        val vod = ChangeDecisions.combine(vodDecisions)
        val all = liveDecisions + vodDecisions
        val allFailed = all.all { it.verdict == Verdict.FAILED }
        val triggers = buildSet {
            if (live == Verdict.CHANGED) add(ScheduledKind.LIVE)
            if (vod == Verdict.CHANGED) add(ScheduledKind.VOD)
        }
        val signal = ChangeDecisions.strongest(all)
        state.putCheck(
            old.copy(
                validators = validators,
                signal = if (allFailed) ChangeSignal.FAILED else signal,
                lastCheckAt = now,
                lastChangeAt = if (triggers.isNotEmpty()) now else old.lastChangeAt,
                failureCode = if (allFailed) lastFailure else null,
            ),
        )
        val started = act(source.id, triggers, now)
        SyncCheckLog.event(
            "sync-check: playlist #${source.id} signal=${if (allFailed) "failed(HTTP $lastFailure)" else signal.name.lowercase()} " +
                "live=${live.name.lowercase()} vod=${vod.name.lowercase()}" +
                (if (started.isNotEmpty()) " → sync ${started.joinToString(",") { it.name.lowercase() }}" else ""),
        )
        return PlaylistOutcome(null, false)
    }

    private suspend fun checkGuide(src: EpgSource, dptv: Map<String, DptvSyncStatus>, changedHosts: Set<String>) {
        val now = System.currentTimeMillis()
        val old = state.check(src.id) ?: SourceCheckState(src.id)
        val host = hostOf(src.url)
        val status = host?.let { dptv[it] }
        if (status != null) {
            val changed = host in changedHosts
            state.putCheck(
                old.copy(
                    isDptv = true,
                    signal = ChangeSignal.DPTV,
                    lastCheckAt = now,
                    lastChangeAt = status.lastEpgRefreshAt?.times(1000) ?: if (changed) now else old.lastChangeAt,
                    failureCode = null,
                ),
            )
            val started = if (changed) act(src.id, setOf(ScheduledKind.EPG), now) else emptySet()
            SyncCheckLog.event("sync-check: guide ${src.id} DPTV-Server changed=$changed" + (if (started.isNotEmpty()) " → sync guide" else ""))
            return
        }
        val validators = old.validators.toMutableMap()
        var result = http.probe(src.url, src.userAgent, head = true, validators = validators[KEY_XML])
        if (result.code == 0 || result.code == 405 || result.code == 501) {
            // No HEAD: a conditional GET whose body is never read.
            result = http.probe(src.url, src.userAgent, head = false, validators = validators[KEY_XML], hashBody = false)
        }
        // Size alone is the weakest signal, used only when the server sends no validators.
        val sized = if (result.etag.isNullOrBlank() && result.lastModified.isNullOrBlank() && result.bodyHash == null) {
            result.copy(bodyHash = result.contentLength?.let { "len:$it" })
        } else {
            result
        }
        val decision = ChangeDecisions.decide(validators[KEY_XML], sized)
        decision.store?.let { validators[KEY_XML] = it }
        val changed = decision.verdict == Verdict.CHANGED
        state.putCheck(
            old.copy(
                isDptv = false,
                validators = validators,
                signal = decision.signal,
                lastCheckAt = now,
                lastChangeAt = if (changed) now else old.lastChangeAt,
                failureCode = if (decision.verdict == Verdict.FAILED) result.code else null,
            ),
        )
        val started = if (changed) act(src.id, setOf(ScheduledKind.EPG), now) else emptySet()
        SyncCheckLog.event(
            "sync-check: guide ${src.id} signal=${if (decision.verdict == Verdict.FAILED) "failed(HTTP ${result.code})" else decision.signal.name.lowercase()} " +
                "verdict=${decision.verdict.name.lowercase()}" + (if (started.isNotEmpty()) " → sync guide" else ""),
        )
    }

    /** Starts the syncs a change asks for, within each kind's throttle. Returns the kinds started. */
    private suspend fun act(id: Long, kinds: Set<ScheduledKind>, now: Long): Set<ScheduledKind> {
        val started = HashSet<ScheduledKind>()
        kinds.forEach { kind ->
            if (!ChangeDecisions.changeSyncAllowed(kind, state.lastChangeSyncAt(kind, id), now)) {
                Log.i(TAG, "change sync throttled kind=$kind id=$id")
                return@forEach
            }
            val holdUntil = now + SyncTimes.HOLD_WINDOW.toMillis()
            if (scheduler.startSyncs(kind, setOf(id), holdUntil, "change") > 0) {
                state.markChangeSync(kind, id, now)
                started += kind
            }
        }
        return started
    }

    companion object {
        private const val TAG = "ChangeChecker"
        const val ACTION_DPTV_STATUS = "dptv_sync_status"
        private const val MAX_STATUS_BYTES = 64 * 1024
        private const val MAX_CATEGORY_BYTES = 1024L * 1024
        private const val KEY_M3U = "m3u"
        private const val KEY_LIVE_CATS = "live_cats"
        private const val KEY_LIVE_STREAMS = "live_streams"
        private const val KEY_VOD_CATS = "vod_cats"
        private const val KEY_SERIES_CATS = "series_cats"
        private const val KEY_XML = "xml"
        const val WORK_PERIODIC = "sync-change-check"
        const val WORK_NOW = "sync-change-check-now"

        internal fun hostOf(url: String): String? = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()

        /** The quarter-hourly check (KEEP, so a cold start does not reset its period) plus one run now. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val periodic = PeriodicWorkRequestBuilder<ChangeCheckWorker>(ChangeDecisions.CHECK_PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(connectedConstraints())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
            val now = OneTimeWorkRequestBuilder<ChangeCheckWorker>()
                .setConstraints(connectedConstraints())
                .build()
            wm.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, now)
        }
    }
}

/** Runs [ChangeChecker.checkAll] for WorkManager (v2.5.3). */
class ChangeCheckWorker(
    context: Context,
    params: WorkerParameters,
    private val checker: ChangeChecker,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        checker.checkAll("work")
        return Result.success()
    }
}
