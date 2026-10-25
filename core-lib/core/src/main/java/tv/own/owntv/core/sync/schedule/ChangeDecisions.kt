package tv.own.owntv.core.sync.schedule

import org.json.JSONObject

/**
 * Which signal a server gave the change check (v2.5.3), best first. Shown on the playlist and guide
 * rows so the owner can see on the TV that the server really reports its refreshes.
 */
enum class ChangeSignal {
    /** DPTV-Server's own `dptv_sync_status` revisions. */
    DPTV,
    ETAG,
    LAST_MODIFIED,

    /** No change headers: the small lists (or the size) are compared instead. */
    CONTENT_HASH,

    /** Nothing usable; the fixed sync times still apply. */
    NONE,
    FAILED,
}

/** What one request answered. [code] 0 means the request never got a response. */
data class ProbeResult(
    val code: Int,
    val etag: String? = null,
    val lastModified: String? = null,
    val contentLength: Long? = null,
    /** Hash of the streamed body, or null when the body was not read (HEAD, 304, too large). */
    val bodyHash: String? = null,
)

/** The validators stored for one endpoint between checks. */
data class Validators(
    val etag: String? = null,
    val lastModified: String? = null,
    val hash: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        etag?.let { put("e", it) }
        lastModified?.let { put("m", it) }
        hash?.let { put("h", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?): Validators? = o?.let {
            Validators(
                etag = it.optString("e").takeIf { s -> s.isNotEmpty() },
                lastModified = it.optString("m").takeIf { s -> s.isNotEmpty() },
                hash = it.optString("h").takeIf { s -> s.isNotEmpty() },
            )
        }
    }
}

enum class Verdict {
    CHANGED,
    UNCHANGED,

    /** First answer for this endpoint: stored, not acted on. */
    BASELINE,
    NO_SIGNAL,
    FAILED,
}

data class ProbeDecision(val verdict: Verdict, val signal: ChangeSignal, val store: Validators?)

/** DPTV-Server's per-type revision. */
data class DptvRev(val rev: String?, val updatedAt: Long?)

/** The `dptv_sync_status` answer. Times are Unix seconds, as the server sends them. */
data class DptvSyncStatus(
    val version: String?,
    val live: DptvRev?,
    val vod: DptvRev?,
    val series: DptvRev?,
    val epg: DptvRev?,
    val lastSourceSyncAt: Long?,
    val lastEpgRefreshAt: Long?,
) {
    fun revs(): Map<String, String> = buildMap {
        live?.rev?.let { put(KEY_LIVE, it) }
        vod?.rev?.let { put(KEY_VOD, it) }
        series?.rev?.let { put(KEY_SERIES, it) }
        epg?.rev?.let { put(KEY_EPG, it) }
    }

    companion object {
        const val SERVER_NAME = "DPTV-Server"
        const val KEY_LIVE = "live"
        const val KEY_VOD = "vod"
        const val KEY_SERIES = "series"
        const val KEY_EPG = "epg"
    }
}

/**
 * The change check's decisions, free of networking and storage so they can be unit tested.
 */
object ChangeDecisions {
    /** Movies and TV Shows re-sync on a change at most this often; the 03:00 run is on top. */
    const val VOD_CHANGE_THROTTLE_MS = 6 * 3_600_000L

    /** How long "this server is / is not DPTV-Server" is trusted before asking again. */
    const val DPTV_DETECTION_TTL_MS = 24 * 3_600_000L

    /** How often the check runs (WorkManager's minimum period). */
    const val CHECK_PERIOD_MINUTES = 15L

    /** A live stream list larger than this is not downloaded just to hash it. */
    const val MAX_HASH_BYTES = 4L * 1024 * 1024

    /**
     * One endpoint's answer against what was stored. A 304 is "unchanged" outright; otherwise the
     * ETag is compared, then Last-Modified, then the body's hash.
     */
    fun decide(stored: Validators?, probe: ProbeResult): ProbeDecision {
        if (probe.code == 304) {
            val signal = when {
                stored?.etag != null -> ChangeSignal.ETAG
                stored?.lastModified != null -> ChangeSignal.LAST_MODIFIED
                else -> ChangeSignal.ETAG
            }
            return ProbeDecision(Verdict.UNCHANGED, signal, stored)
        }
        if (probe.code !in 200..299) return ProbeDecision(Verdict.FAILED, ChangeSignal.FAILED, stored)
        val etag = probe.etag?.takeIf { it.isNotBlank() }
        val modified = probe.lastModified?.takeIf { it.isNotBlank() }
        return when {
            etag != null -> compare(stored?.etag, etag, ChangeSignal.ETAG, Validators(etag = etag, lastModified = modified))
            modified != null -> compare(stored?.lastModified, modified, ChangeSignal.LAST_MODIFIED, Validators(lastModified = modified))
            probe.bodyHash != null -> compare(stored?.hash, probe.bodyHash, ChangeSignal.CONTENT_HASH, Validators(hash = probe.bodyHash))
            else -> ProbeDecision(Verdict.NO_SIGNAL, ChangeSignal.NONE, stored)
        }
    }

    private fun compare(old: String?, new: String, signal: ChangeSignal, store: Validators): ProbeDecision = when {
        old == null -> ProbeDecision(Verdict.BASELINE, signal, store)
        old == new -> ProbeDecision(Verdict.UNCHANGED, signal, store)
        else -> ProbeDecision(Verdict.CHANGED, signal, store)
    }

    /** Several endpoints that answer one question (the live lists): changed if any changed. */
    fun combine(decisions: List<ProbeDecision>): Verdict = when {
        decisions.isEmpty() -> Verdict.NO_SIGNAL
        decisions.any { it.verdict == Verdict.CHANGED } -> Verdict.CHANGED
        decisions.all { it.verdict == Verdict.FAILED } -> Verdict.FAILED
        decisions.any { it.verdict == Verdict.BASELINE } -> Verdict.BASELINE
        decisions.any { it.verdict == Verdict.UNCHANGED } -> Verdict.UNCHANGED
        else -> Verdict.NO_SIGNAL
    }

    /** The strongest signal among several answers, for the status line. */
    fun strongest(decisions: List<ProbeDecision>): ChangeSignal =
        decisions.map { it.signal }.filter { it != ChangeSignal.FAILED && it != ChangeSignal.NONE }.minByOrNull { it.ordinal }
            ?: decisions.firstOrNull { it.signal == ChangeSignal.FAILED }?.signal
            ?: ChangeSignal.NONE

    /**
     * Reads a `dptv_sync_status` body. Null for anything that is not DPTV-Server's answer — other
     * Xtream panels ignore the unknown action and send their usual `user_info` body.
     */
    fun parseDptvStatus(body: String): DptvSyncStatus? = runCatching {
        val o = JSONObject(body.trim())
        if (o.optString("server") != DptvSyncStatus.SERVER_NAME) return null
        fun rev(key: String): DptvRev? = o.optJSONObject(key)?.let {
            DptvRev(
                rev = if (it.isNull("rev")) null else it.optString("rev").takeIf { s -> s.isNotEmpty() },
                updatedAt = it.optLongOrNull("updated_at"),
            )
        }
        DptvSyncStatus(
            version = o.optString("version").takeIf { it.isNotEmpty() },
            live = rev(DptvSyncStatus.KEY_LIVE),
            vod = rev(DptvSyncStatus.KEY_VOD),
            series = rev(DptvSyncStatus.KEY_SERIES),
            epg = rev(DptvSyncStatus.KEY_EPG),
            lastSourceSyncAt = o.optLongOrNull("last_source_sync_at"),
            lastEpgRefreshAt = o.optLongOrNull("last_epg_refresh_at"),
        )
    }.getOrNull()

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    /**
     * What a DPTV-Server status asks for, compared with the revisions stored last time. A type with
     * no stored revision is a baseline, not a change: the first check after install must not start
     * three syncs at once.
     */
    fun dptvTriggers(stored: Map<String, String>?, status: DptvSyncStatus): Set<ScheduledKind> {
        if (stored == null) return emptySet()
        val now = status.revs()
        fun changed(key: String) = now[key] != null && stored[key] != null && now[key] != stored[key]
        return buildSet {
            if (changed(DptvSyncStatus.KEY_LIVE)) add(ScheduledKind.LIVE)
            if (changed(DptvSyncStatus.KEY_VOD) || changed(DptvSyncStatus.KEY_SERIES)) add(ScheduledKind.VOD)
            if (changed(DptvSyncStatus.KEY_EPG)) add(ScheduledKind.EPG)
        }
    }

    /**
     * Live and guide changes start a sync at most once an hour — a panel whose ETag changes on every
     * request would otherwise re-sync every quarter of an hour. Movies & TV Shows at most every six.
     */
    const val LIVE_CHANGE_THROTTLE_MS = 3_600_000L

    fun changeThrottleMs(kind: ScheduledKind): Long = when (kind) {
        ScheduledKind.VOD -> VOD_CHANGE_THROTTLE_MS
        ScheduledKind.LIVE, ScheduledKind.EPG -> LIVE_CHANGE_THROTTLE_MS
    }

    /** Whether a change of [kind] may start a sync now, given when the last change-started one ran. */
    fun changeSyncAllowed(kind: ScheduledKind, lastChangeSyncAt: Long?, nowMs: Long): Boolean =
        lastChangeSyncAt == null || nowMs - lastChangeSyncAt >= changeThrottleMs(kind) || nowMs < lastChangeSyncAt

    /** Whether the cached "DPTV-Server or not" answer is still fresh. */
    fun detectionFresh(detectedAt: Long?, nowMs: Long): Boolean =
        detectedAt != null && nowMs - detectedAt in 0 until DPTV_DETECTION_TTL_MS
}
