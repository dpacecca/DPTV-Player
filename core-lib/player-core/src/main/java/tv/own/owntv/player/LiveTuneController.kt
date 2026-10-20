package tv.own.owntv.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.playStreamUrl
import tv.own.owntv.core.database.entity.resolveStreamUrl
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.player.ForceMpvStore
import tv.own.owntv.core.player.enginePinKey
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.settings.LiveLatency
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SourceOverrides
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.timeshift.TimeshiftDownloader
import tv.own.owntv.core.timeshift.TimeshiftManager
import tv.own.owntv.core.timeshift.TimeshiftServer
import tv.own.owntv.core.timeshift.TimeshiftBuffer
import tv.own.owntv.core.timeshift.TimeshiftSession
import tv.own.owntv.core.recording.ServerTimeshift

/**
 * One live channel from "tune" to "playing" or "gave up", for both apps.
 *
 * This is the orchestration the television's `LiveViewModel` and the phone's `LiveTuner` each carried
 * in their own ~600 lines, and which had drifted apart: engine routing ([LiveRouting]), the fallback
 * [LiveLadder] and its "Give up after" alarm, the ExoPlayer ⇄ mpv handover with its decoder-release
 * wait, the Prefer-HLS `.ts` rung, HLS-redirect learning, the Stalker reconnect link and the
 * per-playlist overrides. Each app now keeps only presentation — which channel is on screen, the HUD,
 * history, casting.
 *
 * **Every tune is one job, and a newer one cancels it** ([launch]). Two quick picks can no longer
 * finish in the wrong order, and a catch-up started while a live tune is still resolving is not then
 * overtaken by it.
 *
 * Everything is main-thread: [scope] must be a Main-dispatched scope, as the engines require.
 */
class LiveTuneController(
    private val scope: CoroutineScope,
    private val engines: LiveEngines,
    private val host: Host,
) {

    /** What the controller needs from outside the engines. [CoreHost] is the one both apps use. */
    interface Host {
        suspend fun sourceOf(sourceId: Long): SourceEntity?
        /** A Stalker playlist stores a portal command, minted into a URL per play. */
        fun needsResolve(source: SourceEntity?): Boolean
        suspend fun resolve(source: SourceEntity, cmd: String): String?
        /** true = pinned to mpv, false = pinned to ExoPlayer, null = follow the setting. */
        suspend fun enginePin(channel: ChannelEntity): Boolean?
        suspend fun pin(channel: ChannelEntity, onMpv: Boolean)
        suspend fun globalPreference(): EnginePreference
        /** Settings → "Give up after", in seconds; 0 is Never. */
        suspend fun globalBudgetSecs(): Int
        fun meta(channel: ChannelEntity): MediaMeta
        /** Mirror a ladder decision into the user-visible playback error log. */
        fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String)
        fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
        /** An engine has just taken the stream (the phone publishes its media session here). */
        fun onEngineStarted() {}
        /** A handover is re-opening the channel at its live edge, so any rewind is over. */
        fun onBackToLiveEdge() {}
        /** N4 — the saved-copy buffers; null where there are none (tests). */
        val timeshift: TimeshiftManager? get() = null
        /** N4 — Settings → the window in minutes while local timeshift is on; null while it is off. */
        suspend fun timeshiftWindowMinutes(): Int? = null
        /** N4 — Settings → what coming back to a kept copy does ("Continue where you left off?"). */
        suspend fun timeshiftResumeMode(): SettingsRepository.ResumeMode = SettingsRepository.ResumeMode.ASK
        /**
         * v2.1.5 — the copy kept by the recording server instead of this device (a Chromecast has no room
         * for it); null when no server is set up or it cannot keep this channel.
         */
        suspend fun openServerTimeshift(channel: ChannelEntity, source: SourceEntity?, windowMinutes: Int, channelKey: String): TimeshiftBuffer? = null
        /**
         * (v2.5.1) Whether [channel]'s copy would be kept by the recording server ([openServerTimeshift]),
         * asked without opening one. Such a copy takes seconds to cut its first piece, so the picture
         * starts on the provider's stream instead (see [preview]).
         */
        suspend fun serverTimeshiftAvailable(channel: ChannelEntity, source: SourceEntity?): Boolean = false
        /** (v2.5.1) The wall clock the saved copies are measured on. */
        fun wallNowMs(): Long = System.currentTimeMillis()
        /** N11 — Settings → Maximum video quality, for the variant a buffer saves; null for none. */
        suspend fun maxVideoHeight(): Int? = null
        /** A download is using the only stream [sourceId] allows, so the preview pane must not take it. */
        fun downloadHoldsOnlyStream(sourceId: Long): Boolean = false
    }

    /**
     * N4 — the channel on screen is playing from its saved copy ([session]: how far back it reaches, its
     * gaps; [timeshiftWatchingWallMs]: what is on screen). [resumeAtWallMs] is set when the user came back
     * to a channel they had left: where they were, for "Resume from buffer / Go live".
     */
    class LocalTimeshift(
        val session: TimeshiftBuffer,
        val resumeAtWallMs: Long?,
    )

    private val _liveOnExo = MutableStateFlow(false)

    /**
     * Whether live is on ExoPlayer right now — false is mpv. The *actual* engine rather than the pin:
     * an automatic handover leaves a channel on mpv while still unpinned.
     */
    val liveOnExo: StateFlow<Boolean> = _liveOnExo.asStateFlow()

    private val _previewBlocked = MutableStateFlow(false)

    /** True while the preview pane is held back because its panel allows one stream and mpv — or a
     *  download — has it. */
    val previewBlockedSingleSession: StateFlow<Boolean> = _previewBlocked.asStateFlow()

    private val recall = ChannelRecall()

    private var ts: TimeshiftBuffer? = null
    /** The piece the engine's stream starts at; null is the live edge. */
    private var tsFromIndex: Long? = null
    private var tsRequest: LiveRequest? = null
    private var tsWindowSec = 0
    /**
     * (v2.5.1) [ts] is kept beside the picture rather than on screen: the engine plays the provider's
     * stream, and moves onto the copy only when the user rewinds, or resumes after a pause.
     */
    private var tsStandby = false
    /** (v2.5.1) The channel whose recording server copy is being made in the background ([scheduleCopy]). */
    private var copyKey: String? = null
    private var copyJob: Job? = null
    private val _localTimeshift = MutableStateFlow<LocalTimeshift?>(null)

    /** N4 — non-null while the channel plays from its saved copy (see [LocalTimeshift]). */
    val localTimeshift: StateFlow<LocalTimeshift?> = _localTimeshift.asStateFlow()

    init {
        host.timeshift?.let { manager ->
            scope.launch {
                manager.gaveUp.collect { (token, why) -> if (ts?.token == token) onTimeshiftLost(why) }
            }
        }
        // (v2.5.1) A pause on the provider's stream with a copy beside it: resuming continues from the copy
        // at the paused moment, as a pause on the copy itself would have.
        engines.exoUserPaused?.let { paused ->
            scope.launch {
                var pausedAt: Long? = null
                paused.collect { now ->
                    if (now) {
                        pausedAt = host.wallNowMs().takeIf { tsStandby && current != null }
                    } else {
                        val at = pausedAt ?: return@collect
                        pausedAt = null
                        resumeOntoCopy(at)
                    }
                }
            }
        }
    }

    /** The channel watched before the one on screen — the "previous channel" key's target (N2). The app
     *  still vets it (profile, playlists, adult filter) before tuning it. */
    val previousChannel: StateFlow<ChannelEntity?> = recall.previous

    /** A channel the user is watching that did not go through [start] — the phone's cast hand-off, where
     *  the receiver plays it. Without this, casting would leave "previous channel" pointing at the past. */
    fun noteWatched(channel: ChannelEntity) = recall.onWatched(channel)

    /** The channel this controller is tuning or playing full-screen; null when it is not driving one. */
    private var current: ChannelEntity? = null

    private val ladder = LiveLadder()
    private var armedBudgetMs: Long = LiveLadder.NO_BUDGET

    /**
     * The channel whose ExoPlayer rung is the explicit `.ts` one. Keyed by channel, not a bare flag: the
     * preview tunes through [exoUrlFor] too, so a flag left set by one channel's TS rung would send every
     * other channel's preview to `.ts` until the next tune.
     */
    private var forceTsFor: String? = null

    /** The Stalker command whose minted URL ExoPlayer holds, so a re-focus or a promote can tell "same
     *  channel" without minting again — the engine's own URL is the minted link, not the command. */
    private var exoStalkerCmd: String? = null

    /** When this controller last stopped ExoPlayer, for [awaitExoRelease]. */
    private var exoStoppedAtMs: Long? = null

    private var tuneJob: Job? = null
    private var previewJob: Job? = null
    private var exoResolveJob: Job? = null
    private var exoWatchJob: Job? = null
    private var mpvOutcomeJob: Job? = null
    private var mpvHandoffJob: Job? = null
    private var deadlineJob: Job? = null

    // --- Entry points -----------------------------------------------------------------------------

    /**
     * Run [block] as the one tune in flight, cancelling whichever was running. Every playback-changing
     * entry point of an app goes through here — a tune, a catch-up, a retune at the live edge — so the
     * last thing asked for is the thing that happens.
     */
    fun launch(block: suspend LiveTuneController.() -> Unit): Job {
        tuneJob?.cancel()
        return scope.launch { block() }.also { tuneJob = it }
    }

    /** Tune [channel] full-screen, as the one tune in flight. */
    fun tune(channel: ChannelEntity): Job = launch { start(channel, host.sourceOf(channel.sourceId)) }

    /**
     * Route [channel], arm its ladder and start the first engine. Called from inside [launch].
     *
     * [resolved] is a URL already minted for a Stalker channel by this tune (the phone mints one before
     * offering the channel to a cast receiver): minting ends the portal's previous session, so the first
     * rung reuses it and only later rungs mint again.
     */
    suspend fun start(channel: ChannelEntity, source: SourceEntity?, resolved: String? = null) {
        previewJob?.cancel()
        cancelLadderJobs()
        current = channel
        recall.onWatched(channel)
        _previewBlocked.value = false
        val setting = source?.liveEnginePreference
            ?.let { name -> EnginePreference.entries.firstOrNull { it.name == name } }
            ?: host.globalPreference()
        val route = LiveRouting.decide(
            setting = setting,
            pin = host.enginePin(channel),
            drmProtected = channel.drmConfig != null,
            panelRefusesSegments = panelRefusesSegments(channel, source),
        )
        engineLog("tune '${channel.name}' -> ${route.why} [${route.preference.name}]")
        if (route.onMpv || !keepCopyBeside(channel, source)) {
            cancelBackgroundCopy()
            openTimeshift(channel, source, resolved)
        }
        arm(channel, source, route.preference)
        when {
            route.onMpv -> startOnMpv(channel, source, route.why, resolved = resolved)
            // The preview already found that ExoPlayer can't open this channel. Promoting it put the
            // preview's error on screen for ~1 s while the ladder moved on to mpv: go there directly.
            previewedChannelId == channel.id && engines.exoFailed -> {
                engineLog("'${channel.name}' — its ExoPlayer preview had already failed; trying the next engine")
                advance(channel, source, "the ExoPlayer preview had already failed", aboutFormat = false)
            }
            else -> startOnExo(channel, source, resolved)
        }
    }

    /** The channel the preview pane last asked ExoPlayer for (see [expectPromotion]). */
    private var previewedChannelId: Long? = null

    /** The pane's last [preview] and its mute, to open it again directly when its saved copy is lost. */
    private var lastPreview: Pair<ChannelEntity, Boolean>? = null

    /**
     * The channel ExoPlayer's stream belongs to, whatever address it ended up on (DPTV-Player v2.1.4).
     *
     * The engine can move to the channel's other form on its own (`…/ch.ts` ⇄ `…/ch.m3u8`, a refreshed
     * link, `direct_source`), after which its URL no longer equals [exoUrlFor]. Comparing URLs then read
     * "a different stream" and re-opened the channel on every OK in the guide and every Back from full
     * screen. The same channel still playing is promoted (or re-muted) instead.
     */
    private var exoChannelId: Long? = null

    /**
     * ExoPlayer already plays [channel], healthy, by whatever address. A saved copy's loopback address
     * counts only while that copy is still open: once it is lost (an outage longer than its reconnect
     * budget) the engine sits on a closed address and the channel must be opened again.
     */
    private fun exoHolds(channel: ChannelEntity): Boolean =
        exoChannelId == channel.id && engines.exoUrl != null && !engines.exoFailed &&
            (!TimeshiftServer.isLocal(engines.exoUrl) || ts?.isClosed == false)

    /**
     * The user is opening [channel] full screen. When ExoPlayer is already previewing it, say so at once,
     * before the full-screen player is first drawn: it then shows ExoPlayer's surface from the start
     * instead of building mpv's and swapping it out when [start] gets there. Measured on a 4K channel:
     * ~4 s of black on opening, with the engine pill reading "MPV" meanwhile. [start] still decides the
     * engine, and turns this off again if that is mpv.
     */
    fun expectPromotion(channel: ChannelEntity) {
        if (previewedChannelId == channel.id && engines.exoUrl != null && !engines.exoFailed) _liveOnExo.value = true
    }

    /**
     * The HUD's engine button: flip the channel on screen between the two engines and pin the choice.
     *
     * For THIS tune the ladder is armed "only" on the engine the user just named — bouncing them off it
     * seconds later makes the control look broken. The next tune of the channel reads the pin and gets
     * the full ladder back. The app refuses first while a catch-up or a rewind is on screen.
     */
    fun toggleEngine() {
        val channel = current ?: return
        // A protected channel has only one engine that can obtain its key.
        if (channel.drmConfig != null) return
        val goToMpv = _liveOnExo.value
        // On a saved copy the other engine continues at the moment on screen, not at the live edge.
        ts?.let { session ->
            if (behindLive()) timeshiftWatchingWallMs()?.let { tsFromIndex = session.pieceAt(it) }
        }
        engineLog("engine toggle '${channel.name}' -> ${if (goToMpv) "mpv" else "exoplayer"}")
        launch {
            host.pin(channel, goToMpv)
            val source = host.sourceOf(channel.sourceId)
            arm(channel, source, EnginePreference.onlyOn(goToMpv))
            if (goToMpv) startOnMpv(channel, source, "user chose compatibility mode")
            else switchToExo(channel, source)
        }
    }

    /**
     * The television's preview pane: play [channel] on ExoPlayer, muted or not, with no ladder.
     *
     * Never while promoted to full-screen — a late preview would re-mute the full-screen stream. On a
     * one-session panel a preview while mpv plays would lock the user's own stream out, so the pane
     * stays silent and [previewBlockedSingleSession] says why.
     */
    fun preview(channel: ChannelEntity, muted: Boolean) {
        lastPreview = channel to muted
        if (_liveOnExo.value) {
            if (current != null) return
            // An [expectPromotion] no tune followed (an external player, a refused channel): the pane is
            // still the pane, and must not stay frozen on the last channel.
            _liveOnExo.value = false
        }
        previewedChannelId = channel.id
        val key = timeshiftKey(channel)
        if (copyKey != key) cancelBackgroundCopy() // (v2.5.1) a copy for the channel left is not wanted
        var leavingServer: ServerTimeshift.Buffer? = null
        ts?.let { session ->
            // The pane already shows this channel, from its saved copy (Back from full screen) or from the
            // provider's stream with the copy beside it (v2.5.1): it keeps saving, because the user has
            // not left the channel.
            if (session.channelKey == key && !engines.exoFailed && (TimeshiftServer.isLocal(engines.exoUrl) || exoHolds(channel))) {
                previewJob?.cancel()
                engines.exoSetMuted(muted)
                return
            }
            // Browsing to another channel is leaving this one. A server's copy is let go of in the
            // background (v2.5.1): the next channel never waits for it, except on a one-stream playlist.
            leavingServer = session as? ServerTimeshift.Buffer
            parkTimeshift()
        }
        previewJob?.cancel()
        previewJob = scope.launch {
            val source = host.sourceOf(channel.sourceId)
            val window = if (engines.mpvHasStream) null else copyWindow(channel)
            // (v2.5.1) The recording server's copy takes seconds to cut its first piece (2–8 s measured
            // with polling): the pane plays the provider's stream at once instead, and the copy is made
            // beside it where the playlist allows a second stream ([scheduleCopy]).
            val onServer = window != null && host.serverTimeshiftAvailable(channel, source)
            // Local timeshift on (DPTV-Player v2.1.5): the pane plays the channel from its saved copy, as
            // full screen does, so OK promotes this very stream. Before, full screen stopped the direct
            // preview and re-opened the channel through the copy — a 3–4 s reload on every OK. A copy on
            // this device has its first bytes as soon as the provider answers, so it costs no more.
            if (window != null && !onServer && openTimeshift(channel, source, resolved = null)) {
                if (_liveOnExo.value) return@launch // promoted while the copy connected: start() plays it
                tsFromIndex = null
                publishTimeshift(resumeAtWallMs = null)
                val local = localUrl()
                val request = tsRequest
                if (local != null && request != null) {
                    exoStalkerCmd = null
                    setReconnect(null, null)
                    engines.exoPlay(local, muted, request)
                    exoChannelId = channel.id
                    return@launch
                }
            }
            val twoStreams = parallelCopyAllowed(channel, source)
            if (onServer && twoStreams) {
                scheduleCopy(channel, source, window!!)
            } else if (!twoStreams) {
                // (v2.5.1) One stream (or an unknown limit): the provider's stream is opened only once the
                // server has let go of the channel left, so the playlist never sees two at once.
                leavingServer?.let { awaitServerRelease(it) }
                if (onServer) engineLog("timeshift: '${channel.name}' previews directly; one stream, so the recording server's copy waits for full screen")
            }
            if (host.needsResolve(source)) {
                if (exoStalkerCmd == channel.streamUrl && !engines.exoFailed) {
                    engines.exoSetMuted(muted)
                    return@launch
                }
                val url = host.resolve(source!!, channel.streamUrl) ?: return@launch
                if (_liveOnExo.value) return@launch // promoted to full-screen while resolving
                if (blockedBySingleSession(url, channel)) return@launch
                exoStalkerCmd = channel.streamUrl
                setReconnect(channel, source)
                engines.exoPlay(url, muted, request(channel, source))
                exoChannelId = channel.id
                return@launch
            }
            val url = exoUrlFor(channel, source)
            if (blockedBySingleSession(url, channel)) return@launch
            if ((engines.exoUrl == url && !engines.exoFailed) || exoHolds(channel)) {
                engines.exoSetMuted(muted) // already previewing it (a re-focus, or Back from full screen) — just re-apply the mute
                return@launch
            }
            exoStalkerCmd = null
            setReconnect(null, null)
            engines.exoPlay(url, muted, request(channel, source))
            exoChannelId = channel.id
        }
    }

    /**
     * Tune [channel] into a Multiview tile's own [engine] — same URL, headers, overrides and Stalker
     * minting as a full-screen tune, so the grid can never drift from it. No ladder: a tile that fails
     * shows its failure.
     */
    suspend fun playTile(engine: LivePreviewEngine, channel: ChannelEntity, muted: Boolean) {
        val source = host.sourceOf(channel.sourceId)
        val url = if (host.needsResolve(source)) {
            host.resolve(source!!, channel.streamUrl) ?: return
        } else {
            exoUrlFor(channel, source)
        }
        engine.play(url, muted, request(channel, source))
    }

    /**
     * Stop driving the channel but leave the stream playing — leaving full-screen for the Live screen,
     * where the pane re-takes ExoPlayer as a preview. Watchers and the alarm stand down: a preview has
     * no ladder.
     */
    fun detach() {
        tuneJob?.cancel()
        cancelLadderJobs()
        current = null
        _liveOnExo.value = false
    }

    /** Stop ExoPlayer (the preview, or a detached stream) without touching mpv. */
    fun stopExo() {
        previewJob?.cancel()
        cancelBackgroundCopy() // (v2.5.1) nothing is left to keep a copy beside
        exoResolveJob?.cancel()
        exoWatchJob?.cancel()
        exoStalkerCmd = null
        setReconnect(null, null)
        stopExoEngine()
    }

    /** Cancel the tune in flight, from outside [launch]. */
    fun cancelTune() {
        tuneJob?.cancel()
    }

    /**
     * Give playback to mpv for something that is not the live stream — a catch-up programme, a rewind
     * into the archive, a film. ExoPlayer lets go of its connection and decoder first, or the live
     * stream plays on underneath with a second sound. The whole ladder goes too: an alarm left armed
     * would stop a replay that plays perfectly well.
     *
     * Does not cancel the tune in flight, because a catch-up calls this from inside its own [launch] —
     * which is what cancels a live tune still resolving, so it cannot start over the archive. A caller
     * outside [launch] uses [cancelTune] first.
     */
    fun releaseForArchive() {
        cancelBackgroundCopy()
        parkTimeshift()
        cancelLadderJobs()
        current = null
        _liveOnExo.value = false
        stopExo()
    }

    /** Stop everything: both engines, every watcher. */
    fun stop() {
        cancelTune()
        releaseForArchive()
        engines.mpvStop()
        host.timeshift?.watching(null)
    }

    // --- Local timeshift (N4) ---------------------------------------------------------------------

    /**
     * Show the saved copy from wall-clock instant [wallMs], or from the live edge when null. The engine
     * on screen re-opens the copy at the piece holding that instant — the same "open the stream at a
     * point" a catch-up rewind does. No-op while the channel is not playing from a copy.
     */
    fun seekTimeshift(wallMs: Long?) {
        val session = ts ?: return
        val request = tsRequest ?: return
        if (current == null) return
        tsFromIndex = if (wallMs == null) null else session.pieceAt(wallMs) ?: return
        publishTimeshift(resumeAtWallMs = null)
        val url = localUrl() ?: return
        if (tsStandby) engineLog("timeshift: '${current?.name}' moves from the provider's stream onto its saved copy")
        tsStandby = false // (v2.5.1) the picture is on the copy from here
        if (_liveOnExo.value) {
            engines.exoPlay(url, muted = false, request)
            exoChannelId = current?.id
        } else {
            engines.mpvPlay(url, request)
        }
    }

    /**
     * (v2.5.1) The user resumes after pausing the provider's stream at [pausedAtWallMs] with a copy beside
     * it: continue from the copy at that moment. A short pause is left to the engine's own buffer.
     */
    private fun resumeOntoCopy(pausedAtWallMs: Long) {
        val channel = current ?: return
        val session = ts ?: return
        if (!tsStandby || session.isClosed || session.channelKey != timeshiftKey(channel) || !isStillExo(channel)) return
        if (host.wallNowMs() - pausedAtWallMs < LIVE_SLACK_MS) return
        // The provider's picture is a little behind the copy's clock (the engine's own buffer).
        seekTimeshift(pausedAtWallMs - DIRECT_BEHIND_COPY_MS)
    }

    /**
     * N4 — the channel's saved copy as a rewind source for [tv.own.owntv.core.live.LiveTimeshift]: the
     * apps' existing rewind bar, counter, "Go back to…" list and "Go to live" work on it unchanged.
     */
    val localRewind: tv.own.owntv.core.live.LiveTimeshift.Local = object : tv.own.owntv.core.live.LiveTimeshift.Local {
        override fun windowSec(ch: ChannelEntity): Int? =
            ts?.takeIf { it.channelKey == timeshiftKey(ch) && current?.id == ch.id }?.let { tsWindowSec }
        override fun depthSec(): Int {
            val session = ts ?: return 0
            val oldest = session.oldestWallMs() ?: return 0
            val edge = session.playableEdgeWallMs() ?: return 0
            return ((edge - oldest) / 1000).toInt().coerceAtLeast(0)
        }
        override fun watchingWallMs(): Long? = if (current == null) null else timeshiftWatchingWallMs()
        override fun liveEdgeWallMs(): Long? = ts?.playableEdgeWallMs()
        override fun seek(wallMs: Long?) = seekTimeshift(wallMs)
    }

    /** The "Resume from buffer / Go live" question has been answered (or ignored). */
    fun dismissResumeOffer() = publishTimeshift(resumeAtWallMs = null)

    /** N4 — the wall-clock spans the saved copy has no picture for (the connection dropped); empty without a copy. */
    fun localGaps(): List<LongRange> = ts?.gaps().orEmpty()

    /** What is on screen on the wall clock, while playing from a copy. */
    fun timeshiftWatchingWallMs(): Long? {
        if (tsStandby) return null // (v2.5.1) the picture is the provider's stream, not the copy
        val session = ts ?: return null
        return session.readingFromWallMs?.plus(engines.positionMs(_liveOnExo.value))
    }

    /**
     * Start (or wake, or keep) [channel]'s saved copy when local timeshift applies: Settings on, a live
     * channel with no provider archive (catch-up channels rewind into that instead), not protected. The
     * buffer is the only connection to the provider from here on, so nothing of ours may still hold the
     * channel when it connects. Returns false — and the channel plays the ordinary way — whenever the
     * copy cannot be made.
     */
    private suspend fun openTimeshift(channel: ChannelEntity, source: SourceEntity?, resolved: String?): Boolean {
        val manager = host.timeshift
        val key = timeshiftKey(channel)
        // The channel being left, parked by the manager with what was on screen — see [TimeshiftManager.open].
        val leaving = ts?.takeIf { it.channelKey != key }
        val leavingWallMs = leaving?.let { timeshiftWatchingWallMs() }
        val window = if (channel.catchup || channel.drmConfig != null) null else host.timeshiftWindowMinutes()
        // The preview already found this channel cannot be saved and plays it directly: keep that
        // stream rather than stop it for a second attempt that would fail the same way.
        if (window == null || (key == tsRefusedKey && exoHolds(channel))) {
            if (leaving != null) parkTimeshift()
            manager?.watching(key)
            clearTimeshift()
            return false
        }
        // The recording server's copy of this channel is still running (a Back, an OK): keep reading it.
        val running = ts
        if (running is ServerTimeshift.Buffer && running.channelKey == key && !running.isClosed) {
            tsStandby = false // (v2.5.1) this order plays the copy itself
            useCopy(channel, source, window)
            return true
        }
        if (engines.exoUrl != null && !TimeshiftServer.isLocal(engines.exoUrl)) stopExoEngine()
        if (engines.mpvHasStream) engines.mpvStopAndAwaitRelease()
        // A server's copy is not kept for a return: the server holds one channel, and the next replaces it.
        if (leaving is ServerTimeshift.Buffer) parkTimeshift()
        host.openServerTimeshift(channel, source, window, key)?.let { buffer ->
            manager?.watching(key) // a copy this device was still downloading lets go of the provider
            (buffer as? ServerTimeshift.Buffer)?.onLost = { why ->
                scope.launch { if (ts?.token == buffer.token) onTimeshiftLost(why) }
            }
            ts = buffer
            tsStandby = false
            tsFromIndex = null
            useCopy(channel, source, window)
            engineLog("timeshift: '${channel.name}' plays from the recording server's copy")
            return true
        }
        if (manager == null) {
            clearTimeshift()
            return false
        }
        val stalker = host.needsResolve(source)
        var minted = resolved
        val headers = StreamHeaders.decode(SourceOverrides.headersWithReferer(channel.httpHeaders, source))
        val target = TimeshiftDownloader.Target(
            url = {
                val first = minted
                when {
                    !stalker -> channel.streamUrl
                    // The tune's own link first; every reconnect mints a fresh one (a link is single-use).
                    first != null -> first.also { minted = null }
                    else -> host.resolve(source!!, channel.streamUrl)
                }
            },
            userAgent = StreamHeaders.userAgentOf(headers)
                ?: source?.userAgent?.takeIf { it.isNotBlank() }
                ?: StalkerClient.DEFAULT_MAG_USER_AGENT.takeIf { stalker }
                ?: HttpClient.FALLBACK_USER_AGENT.takeIf { LiveStreamQuirks.blocksDefaultUserAgent(channel.streamUrl) }
                ?: HttpClient.DEFAULT_USER_AGENT,
            headers = headers,
            maxVideoHeight = host.maxVideoHeight(),
            sourceId = channel.sourceId,
        )
        val opened = manager.open(key, target, window, TIMESHIFT_FIRST_PIECE_MS, leavingWallMs.takeIf { leaving is TimeshiftSession })
        if (opened == null) {
            tsRefusedKey = key
            engineLog("timeshift: '${channel.name}' cannot be saved — playing it directly")
            clearTimeshift()
            return false
        }
        ts = opened.session
        tsStandby = false
        val resumeAt = opened.resumeAtWallMs
        val mode = if (resumeAt == null) SettingsRepository.ResumeMode.ASK else host.timeshiftResumeMode()
        // AUTO opens the copy where the user was; NEVER plays it from the live edge. Neither asks.
        tsFromIndex = if (mode == SettingsRepository.ResumeMode.AUTO && resumeAt != null) opened.session.pieceAt(resumeAt) else null
        tsWindowSec = window * 60
        // The copy is plain TS or fragmented MP4 on loopback: no container hint, no fallback address,
        // no licence, and the provider's headers stay with the downloader.
        tsRequest = request(channel, source).copy(httpHeaders = null, drmConfig = null, manifestType = null, directSource = null)
        publishTimeshift(resumeAtWallMs = resumeAt.takeIf { mode == SettingsRepository.ResumeMode.ASK })
        engineLog("timeshift: '${channel.name}' plays from its saved copy")
        return true
    }

    /** The user left the channel: stop saving it, but keep it for a return (a server's copy just stops). */
    private fun parkTimeshift() {
        when (val session = ts ?: return) {
            is TimeshiftSession -> host.timeshift?.park(session, timeshiftWatchingWallMs())
            is ServerTimeshift.Buffer -> session.close()
        }
        clearTimeshift()
    }

    /** Play from the recording server's copy [ts] (just opened, or still running) from the live edge. */
    private fun useCopy(channel: ChannelEntity, source: SourceEntity?, window: Int) {
        tsWindowSec = window * 60
        tsRequest = request(channel, source).copy(httpHeaders = null, drmConfig = null, manifestType = null, directSource = null)
        publishTimeshift(resumeAtWallMs = null)
    }

    /** (v2.5.1) The rewind window in minutes [channel] would be saved with; null when it is not saved. */
    private suspend fun copyWindow(channel: ChannelEntity): Int? =
        if (channel.catchup || channel.drmConfig != null) null else host.timeshiftWindowMinutes()

    /**
     * (v2.5.1) The playlist is known to allow a second stream, so the recording server's connection to the
     * provider may run beside the picture's own. An unknown limit (0) counts as one: a panel that drops
     * the older session instead of refusing the newer would otherwise flap between the two.
     */
    private fun parallelCopyAllowed(channel: ChannelEntity, source: SourceEntity?): Boolean =
        (source?.maxConnections ?: 0) >= 2 &&
            !LiveStreamQuirks.isSingleSession(channel.streamUrl) &&
            !host.downloadHoldsOnlyStream(channel.sourceId)

    /**
     * (v2.5.1) A full-screen tune that keeps the picture on the provider's stream, with the recording
     * server's copy beside it — kept when it is already running for this channel (OK on the preview, so
     * nothing reloads), started in the background otherwise. False when that order does not apply, and
     * the caller opens the copy first as before.
     */
    private suspend fun keepCopyBeside(channel: ChannelEntity, source: SourceEntity?): Boolean {
        val window = copyWindow(channel) ?: return false
        if (!parallelCopyAllowed(channel, source) || !host.serverTimeshiftAvailable(channel, source)) return false
        val key = timeshiftKey(channel)
        val running = ts
        if (running != null && running.channelKey == key && !running.isClosed) {
            useCopy(channel, source, window)
            return true
        }
        if (running != null) parkTimeshift()
        host.timeshift?.watching(key) // a copy this device was saving lets go of the provider
        scheduleCopy(channel, source, window)
        return true
    }

    /**
     * (v2.5.1) Start [channel]'s copy on the recording server in the background, beside the provider's
     * stream that is already on screen. It waits [COPY_SETTLE_MS] first, so a run of OK presses or zaps
     * opens one copy and not one per channel passed. Once asked, the server's answer is always dealt with
     * (kept, or closed when the user has moved on), so a copy is never left running on the server.
     */
    private fun scheduleCopy(channel: ChannelEntity, source: SourceEntity?, window: Int) {
        val key = timeshiftKey(channel)
        if (copyKey == key) return
        if (ts?.let { it.channelKey == key && !it.isClosed } == true) return
        cancelBackgroundCopy()
        copyKey = key
        copyJob = scope.launch {
            delay(COPY_SETTLE_MS)
            if (copyKey != key) return@launch
            val askedAt = host.nowMs()
            withContext(NonCancellable) {
                val buffer = host.openServerTimeshift(channel, source, window, key)
                when {
                    buffer == null -> if (copyKey == key) {
                        copyKey = null
                        engineLog("timeshift: the recording server could not keep '${channel.name}'; it plays without rewind")
                    }
                    copyKey != key || ts != null -> discard(buffer)
                    else -> {
                        copyKey = null
                        (buffer as? ServerTimeshift.Buffer)?.onLost = { why ->
                            scope.launch { if (ts?.token == buffer.token) onTimeshiftLost(why) }
                        }
                        ts = buffer
                        tsStandby = true
                        tsFromIndex = null
                        useCopy(channel, source, window)
                        engineLog("timeshift: '${channel.name}' — the recording server's copy is ready after ${host.nowMs() - askedAt}ms; the picture stays on the provider's stream until a pause or a rewind")
                    }
                }
            }
        }
    }

    /** (v2.5.1) Forget the copy being made in the background; one the server already answers is closed. */
    private fun cancelBackgroundCopy() {
        copyKey = null
        copyJob?.cancel()
        copyJob = null
    }

    private fun discard(buffer: TimeshiftBuffer) {
        when (buffer) {
            is ServerTimeshift.Buffer -> buffer.close()
            is TimeshiftSession -> host.timeshift?.park(buffer, null)
        }
    }

    /** (v2.5.1) Bounded: a server that does not answer must not hold the next channel for long. */
    private suspend fun awaitServerRelease(buffer: ServerTimeshift.Buffer) {
        val from = host.nowMs()
        val released = buffer.awaitReleased(SERVER_RELEASE_WAIT_MS)
        engineLog("timeshift: one stream — waited ${host.nowMs() - from}ms for the recording server to let go (answered=$released)")
    }

    private fun clearTimeshift() {
        ts = null
        tsStandby = false
        tsRequest = null
        _localTimeshift.value = null
    }

    private fun publishTimeshift(resumeAtWallMs: Long?) {
        val session = ts ?: return
        _localTimeshift.value = LocalTimeshift(session = session, resumeAtWallMs = resumeAtWallMs)
    }

    /** The copy on screen is further behind its live edge than a player keeps buffered (a pause). */
    private fun behindLive(): Boolean {
        val watching = timeshiftWatchingWallMs() ?: return false
        val edge = ts?.playableEdgeWallMs() ?: return false
        return edge - watching > LIVE_SLACK_MS
    }

    private fun localUrl(): String? = ts?.takeIf { !it.isClosed }?.let { TimeshiftServer.urlFor(it, tsFromIndex) }

    /**
     * The copy could not reconnect (an outage longer than its reconnect budget): the channel is opened again
     * instead. Giving up here left the engine on the copy's closed loopback address, and its own "network
     * restored" retry asked that address for ever (404) — "Reconnecting…" that never ended. A fresh tune
     * starts a new copy when the provider answers, or plays the channel directly when it does not, and the
     * live engines' own outage handling takes it from there.
     */
    private fun onTimeshiftLost(why: String) {
        val lostKey = ts?.channelKey
        val channel = current
        val standby = tsStandby
        clearTimeshift()
        if (standby) {
            // (v2.5.1) The copy was only beside the picture: the provider's stream plays on, without rewind.
            engineLog("timeshift: the copy beside '${(channel ?: lastPreview?.first)?.name}' was lost ($why); the picture is unaffected")
            return
        }
        if (channel != null && (isStillExo(channel) || isStillMpv(channel))) {
            engineLog("'${channel.name}' — its saved copy lost the connection ($why); opening it again")
            tune(channel)
            return
        }
        // The pane was showing the copy (Back from full screen keeps it saving): preview it directly.
        val pane = lastPreview?.takeIf { timeshiftKey(it.first) == lostKey && TimeshiftServer.isLocal(engines.exoUrl) } ?: return
        engineLog("'${pane.first.name}' — the pane's saved copy lost the connection ($why); previewing it directly")
        preview(pane.first, pane.second)
    }

    /** The last channel whose copy could not be made (see [openTimeshift]). */
    private var tsRefusedKey: String? = null

    private fun timeshiftKey(channel: ChannelEntity): String = "${channel.sourceId}:${channel.id}"

    // --- Engines ----------------------------------------------------------------------------------

    /**
     * Open [channel] on ExoPlayer and watch it. If ExoPlayer already holds this channel — the preview
     * the user just pressed OK on — it is promoted (unmuted) instead of rebuilt.
     */
    private suspend fun startOnExo(channel: ChannelEntity, source: SourceEntity?, resolved: String? = null) {
        mpvOutcomeJob?.cancel() // ExoPlayer owns the channel now
        _liveOnExo.value = true
        if (engines.mpvHasStream && singleSessionPanel(channel, source)) {
            // mpv's stop is asynchronous: on a one-connection panel ExoPlayer opening while mpv still holds
            // the previous channel's socket is the app competing with itself for the only session (458).
            // Wait for mpv to let go first; bounded by mpv's release timeout, and only on such a panel.
            engines.mpvStopAndAwaitRelease()
            if (!isCurrent(channel)) return // zapped on while mpv released
        } else {
            engines.mpvStop() // mpv lets go of the connection and the decoder before ExoPlayer claims either
        }
        // (v2.5.1) A copy beside the picture stays beside it: the provider's stream is promoted instead.
        val local = localUrl().takeUnless { tsStandby }
        if (local != null) {
            // The same address is not the same picture after a pause: "live" then means re-opening it.
            if (engines.exoUrl == local && !behindLive()) {
                engines.exoSetMuted(false)
            } else {
                engines.exoReleaseUhdDecoder()
                exoStalkerCmd = null
                setReconnect(null, null)
                engines.exoPlay(local, muted = false, tsRequest ?: request(channel, source))
                exoChannelId = channel.id
            }
            host.onEngineStarted()
            watchExo(channel, source)
            return
        }
        if (host.needsResolve(source)) {
            startOnExoStalker(channel, source!!, resolved)
            return
        }
        val url = exoUrlFor(channel, source)
        if (engines.exoUrl == url || exoHolds(channel)) {
            engines.exoSetMuted(false) // promote — instant if already playing, otherwise keeps loading
        } else {
            // Leaving a UHD channel: release its 4K decoder before the rebuild (no-op for SD/HD).
            engines.exoReleaseUhdDecoder()
            exoStalkerCmd = null
            setReconnect(null, null)
            engines.exoPlay(url, muted = false, request(channel, source))
            exoChannelId = channel.id
        }
        host.onEngineStarted()
        watchExo(channel, source)
    }

    private suspend fun startOnExoStalker(channel: ChannelEntity, source: SourceEntity, resolved: String?) {
        if (exoStalkerCmd == channel.streamUrl && resolved == null) {
            engines.exoSetMuted(false) // promote the preview that already holds this command
            setReconnect(channel, source)
            host.onEngineStarted()
            watchExo(channel, source)
            return
        }
        exoResolveJob?.cancel()
        engines.exoReleaseUhdDecoder()
        if (resolved != null) {
            // Already minted by this tune: start now, so the caller's next line finds the stream there.
            playExoStalker(channel, source, resolved)
            return
        }
        // Detached: minting is a network call, and a ladder step that got here from a watcher must not
        // die with that watcher when [watchExo] replaces it.
        exoResolveJob = scope.launch {
            val url = host.resolve(source, channel.streamUrl) ?: return@launch
            if (!isCurrent(channel)) return@launch // zapped away while minting
            playExoStalker(channel, source, url)
        }
    }

    private fun playExoStalker(channel: ChannelEntity, source: SourceEntity, url: String) {
        exoStalkerCmd = channel.streamUrl
        setReconnect(channel, source)
        engines.exoPlay(url, muted = false, request(channel, source))
        exoChannelId = channel.id
        host.onEngineStarted()
        watchExo(channel, source)
    }

    /** Put [channel] on ExoPlayer, releasing mpv first when it holds the stream — mpv's stop is
     *  asynchronous, and handing over too early makes the app its own competitor for a session. */
    private suspend fun switchToExo(channel: ChannelEntity, source: SourceEntity?) {
        host.onBackToLiveEdge()
        if (!_liveOnExo.value) {
            engines.mpvStopAndAwaitRelease()
            delay(OwnTVPlayer.SURFACE_HANDOFF_MS)
            if (!isCurrent(channel)) return
        }
        startOnExo(channel, source)
    }

    /**
     * Open [channel] on mpv — a pinned channel, "mpv first", or ExoPlayer having given up.
     *
     * [forceTs] marks the `.ts` rung: "Prefer HLS" must not rewrite the URL. [resolved] is the first
     * rung's already-minted Stalker link; later rungs mint afresh, because a spent link is not reusable.
     */
    private suspend fun startOnMpv(
        channel: ChannelEntity,
        source: SourceEntity?,
        reason: String,
        forceTs: Boolean = false,
        resolved: String? = null,
    ) {
        engineLog("starting mpv for '${channel.name}' — reason=$reason")
        host.onBackToLiveEdge()
        // What ExoPlayer discovered before it let go: some panels redirect their advertised `.ts` to HLS,
        // and handing mpv that misleading URL traps FFmpeg at the manifest EOF. "Discovered" strictly
        // means ExoPlayer asked for something that was NOT HLS and got HLS anyway — an `.m3u8` we
        // requested teaches nothing about the `.ts` endpoint, and recording it branded a whole panel.
        val exoTuned = engines.exoUrl
        val exoDiscoveredHls = _liveOnExo.value && engines.exoIsHls &&
            exoTuned != null && !LiveStreamQuirks.isExplicitHlsUrl(exoTuned)
        exoWatchJob?.cancel() // mpv owns the channel now
        exoResolveJob?.cancel()
        mpvOutcomeJob?.cancel()
        _liveOnExo.value = false
        exoStalkerCmd = null
        stopExoEngine()
        awaitExoRelease()
        if (!isCurrent(channel)) {
            // The only exit that leaves the screen on mpv's surface with nothing loaded. Normal when the
            // user zapped during the release wait; in a support log it tells "abandoned" from "vanished".
            engineLog("mpv handoff for '${channel.name}' abandoned — the channel changed while ExoPlayer released")
            return
        }
        localUrl()?.let { local ->
            tsStandby = false // (v2.5.1) mpv has no pause hook: it plays the copy itself
            setReconnect(null, null)
            engines.mpvPlay(local, tsRequest ?: request(channel, source))
            host.onEngineStarted()
            watchMpv(channel, source)
            return
        }
        val stalker = host.needsResolve(source)
        val raw = when {
            !stalker -> channel.streamUrl
            resolved != null -> resolved
            else -> host.resolve(source!!, channel.streamUrl) ?: return
        }
        // Same "Prefer HLS" opt-out as the ExoPlayer URL, but keyed to mpv's OWN verdict: ExoPlayer
        // failing this channel's `.m3u8` says nothing about whether mpv can play it.
        val preferred = if (forceTs || LiveStreamQuirks.lacksHlsVariantMpv(channel.streamUrl)) raw
            else resolveStreamUrl(raw, source)
        // Against the PANEL: the redirect is the provider's, so every later channel starts out knowing.
        if (exoDiscoveredHls) LiveStreamQuirks.rememberHlsRedirect(preferred)
        val url = if (LiveStreamQuirks.isKnownHlsHost(preferred)) LiveStreamQuirks.toHlsUrl(preferred) else preferred
        if (!isCurrent(channel)) return // zapped away while minting
        setReconnect(if (stalker) channel else null, source)
        engines.mpvPlay(url, request(channel, source))
        host.onEngineStarted()
        watchMpv(channel, source)
    }

    /**
     * Let ExoPlayer's decoder go before mpv initialises. Nothing exposes "the MediaCodec is released",
     * so the only alternative to waiting is guessing, and guessing short reproduces the 0x80001000
     * codec-claim failure. Measured at ≈500 ms on a Realtek box.
     *
     * Only the part of the wait that has not already passed since ExoPlayer was last stopped: a tune
     * that starts on mpv with nothing on ExoPlayer for a while has no decoder to wait for, and half a
     * second of black on each of those would be a handover cost charged to a tune that never handed over.
     */
    private suspend fun awaitExoRelease() {
        val stoppedAt = exoStoppedAtMs ?: return
        val left = OwnTVPlayer.SURFACE_HANDOFF_MS - (host.nowMs() - stoppedAt)
        if (left > 0) delay(left)
    }

    private fun stopExoEngine() {
        if (engines.exoUrl != null) exoStoppedAtMs = host.nowMs()
        exoChannelId = null
        engines.exoStop()
    }

    // --- Watchers ---------------------------------------------------------------------------------

    private fun watchExo(channel: ChannelEntity, source: SourceEntity?) {
        exoWatchJob?.cancel()
        exoWatchJob = scope.launch {
            engines.watchExo(
                channelName = channel.name,
                // A watchdog outlives the tune that armed it; firing after that would stop a stream
                // nobody complained about.
                stillOurs = { isStillExo(channel) },
                // Into the ladder rather than straight to mpv: the ladder decides what "next" means.
                handOver = { reason -> advance(channel, source, reason) },
                onOpened = ::standDownAlarm,
                // A provider back-off is a wait OwnTV agreed to; it is not charged to the budget.
                postponeDeadline = { ladder.postponeDeadline(it) },
                log = ::engineLog,
            )
        }
    }

    /** mpv runs its own retry and format ladder first, so its deadline is looser than ExoPlayer's. */
    private fun watchMpv(channel: ChannelEntity, source: SourceEntity?) {
        mpvOutcomeJob?.cancel()
        mpvOutcomeJob = scope.launch {
            val outcome = engines.awaitMpvOutcome(MPV_OPEN_TIMEOUT_MS)
            if (!isStillMpv(channel)) return@launch
            val reason = when {
                outcome == null -> "mpv never opened it (${MPV_OPEN_TIMEOUT_MS / 1000}s, no picture and no error)"
                outcome.opened -> {
                    engineLog("'${channel.name}' opened on mpv")
                    standDownAlarm()
                    return@launch
                }
                else -> "mpv couldn't play it: ${outcome.error}"
            }
            advance(channel, source, reason)
        }
    }

    // --- The ladder -------------------------------------------------------------------------------

    private suspend fun arm(channel: ChannelEntity, source: SourceEntity?, preference: EnginePreference) {
        forceTsFor = null
        val secs = SourceOverrides.liveTuneTimeoutSecsOf(source) ?: host.globalBudgetSecs()
        armedBudgetMs = if (secs <= 0) LiveLadder.NO_BUDGET else secs * 1000L
        ladder.arm(channel.streamUrl, preference, budgetMs = armedBudgetMs, nowMs = host.nowMs()) {
            // A saved copy is one address; the ladder is only the two engines.
            (ts == null || tsStandby) && hasHlsAlternative(channel, source)
        }
        startAlarm(channel)
    }

    /**
     * The alarm behind "Give up after", so the budget bounds the black screen rather than only the
     * decision to climb another rung: a rung entered at 24 s with a 35 s timeout of its own would
     * otherwise run to 59 s. The deadline is re-read on each pass, so a postponement moves the alarm.
     * A channel that opens stands the alarm down — later stalls belong to the watchdogs.
     */
    private fun startAlarm(channel: ChannelEntity) {
        deadlineJob?.cancel()
        deadlineJob = scope.launch {
            while (ladder.owns(channel.streamUrl)) {
                val left = (ladder.deadlineAt() ?: return@launch) - host.nowMs()
                if (left <= 0) break
                delay(left)
            }
            // Both gates matter: the ladder can still own a channel the user walked away from, and this
            // alarm stops an engine — on mpv's side the shared player, which may be showing a film.
            if (!ladder.owns(channel.streamUrl)) return@launch
            if (!isStillExo(channel) && !isStillMpv(channel)) return@launch
            val detail = "no picture within ${armedBudgetMs / 1000}s of tuning"
            engineLog("'${channel.name}' — giving up: $detail")
            host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_NO_FALLBACK, "'${channel.name}': $detail")
            exoWatchJob?.cancel()
            exoResolveJob?.cancel()
            mpvOutcomeJob?.cancel()
            mpvHandoffJob?.cancel()
            abandon(channel, detail)
        }
    }

    private fun standDownAlarm() {
        deadlineJob?.cancel()
        deadlineJob = null
    }

    /**
     * Move to the next untried rung after a failure, or give up when there is nothing left. The only
     * place the ladder is climbed, from either engine's watcher — which is what makes "each rung at most
     * once" hold, and that finiteness is what stops a channel bouncing between the engines for ever.
     */
    private suspend fun advance(
        channel: ChannelEntity,
        source: SourceEntity?,
        reason: String,
        /** Whether the failure says anything about the stream's format; by default, unless it was a refusal. */
        aboutFormat: Boolean = !isRequestRefusal(reason),
    ) {
        if (!ladder.owns(channel.streamUrl)) return // a newer tune owns the ladder now
        val nowMs = host.nowMs()
        // ExoPlayer played this channel, then found its content unplayable (not a connection problem).
        // The opening budget is long spent by then, which would refuse mpv, the engine most likely to
        // play it: give this handover a fresh one.
        if (_liveOnExo.value && engines.exoContentBroken) ladder.restartDeadline(nowMs, armedBudgetMs)
        val outOfTime = ladder.expired(nowMs)
        // A panel refusing the *request* (a busy 458, a 403, a rate limit) says nothing about the format,
        // so nothing may be learned from it.
        val next = ladder.advance(failureWasAboutFormat = aboutFormat, nowMs = nowMs) ?: run {
            val detail = if (outOfTime) "$reason — gave up after ${armedBudgetMs / 1000}s" else reason
            // Nowhere to go, but ExoPlayer is still reconnecting a channel that played. Abandoning here
            // sets its gaveUp flag, which stops those reconnects: measured on an upstream outage of ~50s
            // with the link still up (so no "network restored" to resume on), the next connect failure
            // left a black screen until the user pressed Retry. The engine reports "Lost connection"
            // itself once its own ladder is spent.
            if (_liveOnExo.value && engines.exoStillReconnecting) {
                engineLog("'${channel.name}' — no fallback left ($detail); leaving ExoPlayer's own reconnects running")
                return
            }
            engineLog("'${channel.name}' — no fallback left ($detail)")
            host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_NO_FALLBACK, "'${channel.name}': $detail")
            abandon(channel, detail)
            return
        }
        val label = ladder.label(next)
        engineLog("'${channel.name}' falling back to $label ($reason)")
        // Before the switch, so the record still names the engine that failed.
        host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_FALLBACK, "'${channel.name}': $label — $reason")
        if (next.onMpv) {
            // Detached on purpose. Every automatic rung is dispatched from inside a watcher job, and the
            // handoff cancels those watchers the moment it takes over — run inline it would cancel itself
            // at its first suspension point: mpv's surface up, mpv never asked to load, a black screen.
            mpvHandoffJob?.cancel()
            mpvHandoffJob = scope.launch { startOnMpv(channel, source, reason, forceTs = !next.isHls) }
        } else {
            forceTsFor = if (next.isHls) null else channel.streamUrl
            switchToExo(channel, source)
        }
    }

    /** Put the failure on screen: a stream that delivers no segment produces no frame AND no error, so
     *  the honest answer is written by whoever decided to stop trying — on the engine the HUD reads. */
    private fun abandon(channel: ChannelEntity, detail: String) {
        val reason = "'${channel.name}': $detail"
        if (_liveOnExo.value) engines.exoAbandon(reason) else engines.mpvAbandon(reason)
    }

    private fun cancelLadderJobs() {
        exoResolveJob?.cancel()
        exoWatchJob?.cancel()
        mpvOutcomeJob?.cancel()
        mpvHandoffJob?.cancel()
        deadlineJob?.cancel()
    }

    // --- Small helpers ----------------------------------------------------------------------------

    private fun isCurrent(channel: ChannelEntity): Boolean = current?.id == channel.id

    private fun isStillExo(channel: ChannelEntity): Boolean = _liveOnExo.value && isCurrent(channel)

    private fun isStillMpv(channel: ChannelEntity): Boolean = !_liveOnExo.value && isCurrent(channel)

    /** The panel allows one stream at a time: it said so at sync (`max_connections = 1`) or was caught
     *  refusing a second session this run. */
    private fun singleSessionPanel(channel: ChannelEntity, source: SourceEntity?): Boolean =
        source?.maxConnections == 1 || LiveStreamQuirks.isSingleSession(channel.streamUrl)

    private fun blockedBySingleSession(url: String, channel: ChannelEntity): Boolean {
        val blocked = (engines.mpvHasStream && LiveStreamQuirks.isSingleSession(url)) ||
            host.downloadHoldsOnlyStream(channel.sourceId)
        _previewBlocked.value = blocked
        return blocked
    }

    /**
     * Which of the channel's two addresses ExoPlayer should ask for: the "Prefer HLS" `.m3u8`, except on
     * the ladder's `.ts` rung or a channel already caught this session having no working `.m3u8`.
     */
    private fun exoUrlFor(channel: ChannelEntity, source: SourceEntity?): String =
        if (forceTsFor == channel.streamUrl || LiveStreamQuirks.lacksHlsVariant(channel.streamUrl)) channel.streamUrl
        else channel.playStreamUrl(source)

    /** Whether "Prefer HLS" rewrites this channel's URL at all; if not, HLS and TS rungs are one
     *  attempt and the ladder drops the HLS ones. A Stalker command is not a URL. */
    private fun hasHlsAlternative(channel: ChannelEntity, source: SourceEntity?): Boolean =
        !host.needsResolve(source) && channel.playStreamUrl(source) != channel.streamUrl

    /** Whether this channel's panel was caught refusing its own signed segment URLs. Stalker is excluded:
     *  its command has no host to key on until minted — a network call routing must not make. */
    private fun panelRefusesSegments(channel: ChannelEntity, source: SourceEntity?): Boolean =
        !host.needsResolve(source) && LiveStreamQuirks.refusesSegments(channel.playStreamUrl(source))

    private fun isRequestRefusal(reason: String): Boolean =
        PlayerErrors.httpStatusIn(reason)?.let { LiveStreamQuirks.isRequestRefusal(it) } == true

    /**
     * Install the Stalker reconnect link on both engines while a Stalker channel plays, so a mid-session
     * stream death mints a fresh `create_link` instead of looping on the expired one; cleared otherwise.
     */
    private fun setReconnect(channel: ChannelEntity?, source: SourceEntity?) {
        if (channel == null || source == null || !host.needsResolve(source)) {
            engines.setReconnectProvider(null)
            return
        }
        engines.setReconnectProvider(
            ReconnectUrlProvider {
                runCatching { host.resolve(source, channel.streamUrl) }
                    .onFailure { android.util.Log.w(ENGINE_TAG, "stalker reconnect re-resolve failed '${channel.name}'", it) }
                    .getOrNull()
            },
        )
    }

    private fun request(channel: ChannelEntity, source: SourceEntity?) = LiveRequest(
        meta = host.meta(channel),
        userAgent = source?.userAgent,
        prerollSecs = source?.livePrerollSecs?.takeIf { it >= 0 },
        liveBuffer = source?.liveLatencyMode?.let { mode ->
            LiveBuffer.Override(LiveBuffer.effectiveSeconds(LiveLatency.fromName(mode), source.liveLatencyCustomSecs))
        },
        httpHeaders = SourceOverrides.headersWithReferer(channel.httpHeaders, source),
        drmConfig = channel.drmConfig,
        manifestType = channel.manifestType,
        directSource = channel.directSource,
    )

    /** Routing decisions go to logcat unconditionally (`adb logcat -s LiveEngine`) and to the
     *  diagnostics ring. Channel names only — never a stream URL. */
    private fun engineLog(message: String) {
        android.util.Log.i(ENGINE_TAG, message)
        LiveDiagnosticsLog.event("engine: $message")
    }

    /**
     * The [Host] both apps use: pins from [ForceMpvStore], the settings store, the playlist row, and
     * Stalker minting through [StreamUrlResolver]. [label] is the line under the channel name (the
     * television's "#123"); [sourceOf] lets the television read its warm playlist map.
     */
    class CoreHost(
        private val context: Context,
        private val settings: SettingsRepository,
        private val sourceDao: SourceDao,
        private val resolver: StreamUrlResolver,
        private val forceMpvStore: ForceMpvStore,
        private val label: (ChannelEntity) -> String? = { null },
        private val sourceLookup: (suspend (Long) -> SourceEntity?)? = null,
        private val engineStarted: () -> Unit = {},
        private val backToLiveEdge: () -> Unit = {},
        /** Core's `DownloadWatchGuard.holdsOnlyStream`; see [Host.downloadHoldsOnlyStream]. */
        private val downloadHolds: (Long) -> Boolean = { false },
    ) : Host {
        override suspend fun sourceOf(sourceId: Long): SourceEntity? =
            sourceLookup?.invoke(sourceId) ?: sourceDao.getById(sourceId)

        override fun needsResolve(source: SourceEntity?): Boolean = resolver.needsResolve(source)

        override fun downloadHoldsOnlyStream(sourceId: Long): Boolean = downloadHolds(sourceId)

        override suspend fun resolve(source: SourceEntity, cmd: String): String? =
            runCatching { resolver.resolve(source, cmd) }
                .onFailure { android.util.Log.w(ENGINE_TAG, "stalker resolve failed", it) }
                .getOrNull()

        override suspend fun enginePin(channel: ChannelEntity): Boolean? =
            forceMpvStore.pinFor(pinKey(channel), channel.streamUrl)

        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) {
            // The stable key, with any legacy URL-keyed entry cleared so an older pin cannot contradict it.
            val key = pinKey(channel)
            forceMpvStore.pin(key ?: channel.streamUrl, onMpv)
            if (key != null) forceMpvStore.forget(channel.streamUrl)
        }

        // Both wait for the settings snapshot (S15), so a tune started at a cold start — Last-channel
        // autoplay — is routed and budgeted by the stored settings, never by defaults.
        override suspend fun globalPreference(): EnginePreference =
            PlaybackSettings.await(settings).liveEnginePreference

        override suspend fun globalBudgetSecs(): Int = PlaybackSettings.await(settings).liveTuneTimeoutSecs

        override fun meta(channel: ChannelEntity): MediaMeta = MediaMeta(
            title = channel.name,
            subtitle = label(channel),
            logoUrl = channel.displayLogoUrl,
            contentKey = pinKey(channel),
        )

        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) =
            PlaybackErrorLog.event(
                context = context,
                engine = if (onExo) "ExoPlayer" else "mpv",
                live = true,
                reason = reason,
                detail = detail,
            )

        override fun onEngineStarted() = engineStarted()
        override fun onBackToLiveEdge() = backToLiveEdge()

        override val timeshift: TimeshiftManager? by lazy {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<TimeshiftManager>()
        }

        override suspend fun timeshiftWindowMinutes(): Int? =
            if (settings.timeshiftEnabled.first()) settings.timeshiftWindowMinutes.first() else null

        override suspend fun timeshiftResumeMode(): SettingsRepository.ResumeMode = settings.timeshiftResumeMode.first()

        // The server knows channels by Xtream stream_id, as it does for recordings.
        override suspend fun openServerTimeshift(
            channel: ChannelEntity,
            source: SourceEntity?,
            windowMinutes: Int,
            channelKey: String,
        ): TimeshiftBuffer? {
            if (source?.type != tv.own.owntv.core.model.SourceType.XTREAM) return null
            val streamId = channel.remoteId ?: return null
            val server = org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<ServerTimeshift>() ?: return null
            return server.open(streamId, channelKey, windowMinutes, SERVER_TIMESHIFT_FIRST_PIECE_MS)
        }

        override suspend fun serverTimeshiftAvailable(channel: ChannelEntity, source: SourceEntity?): Boolean {
            if (source?.type != tv.own.owntv.core.model.SourceType.XTREAM || channel.remoteId == null) return false
            val server = org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<ServerTimeshift>() ?: return false
            return server.isSetUp()
        }

        override suspend fun maxVideoHeight(): Int? =
            PlaybackSettings.await(settings).maxVideoHeight.takeIf { it > 0 }

        private fun pinKey(channel: ChannelEntity): String? =
            enginePinKey(channel.sourceId, tv.own.owntv.core.model.MediaType.LIVE.name, channel.remoteId)
    }

    companion object {
        /** Tag for the engine decisions, so a support log can be filtered to just them. */
        const val ENGINE_TAG = "LiveEngine"

        /**
         * How long mpv gets to produce a picture before the ladder moves on. Looser than ExoPlayer's:
         * mpv walks its own internal retry and format ladder first.
         */
        const val MPV_OPEN_TIMEOUT_MS = 35_000L

        /** How long a new saved copy gets to receive its first bytes before the channel plays directly. */
        const val TIMESHIFT_FIRST_PIECE_MS = 12_000L

        /** The same for the recording server's copy: it connects to the provider and cuts a piece first. */
        const val SERVER_TIMESHIFT_FIRST_PIECE_MS = 20_000L

        /** Behind the copy's edge by more than this is not live any more (the rewind counter's own slack). */
        const val LIVE_SLACK_MS = 8_000L

        /**
         * (v2.5.1) How long the provider's stream must stay on one channel before the recording server is
         * asked for its copy: longer than a run of presses, so browsing opens one copy, not one per channel.
         */
        const val COPY_SETTLE_MS = 1_500L

        /** (v2.5.1) The most a one-stream playlist's next channel waits for the server to let go of the last. */
        const val SERVER_RELEASE_WAIT_MS = 2_000L

        /** (v2.5.1) How far the provider's picture runs behind the copy's clock (the engine's own buffer). */
        const val DIRECT_BEHIND_COPY_MS = 2_000L
    }
}
