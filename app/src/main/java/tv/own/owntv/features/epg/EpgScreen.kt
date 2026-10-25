package tv.own.owntv.features.epg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.live.LiveCategories
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.features.live.centreScrollSpec
import tv.own.owntv.features.live.liveCategoryEntries
import tv.own.owntv.features.live.liveEpgShiftLabel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.shownColor
import tv.own.owntv.ui.stage.StageSearchField
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.GUIDE_SLIDE_MS
import tv.own.owntv.ui.theme.guideSlideSpec
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

@Composable
private fun epgMessageText(message: EpgMessage): String = when (message) {
    EpgMessage.CreateProfile -> stringResource(R.string.content_epg_create_profile)
    EpgMessage.AddPlaylist -> stringResource(R.string.content_epg_add_playlist)
    is EpgMessage.NoChannelsForQuery -> stringResource(R.string.content_epg_no_channels_query, message.query)
    EpgMessage.MismatchedIds -> stringResource(R.string.content_epg_mismatched_ids)
}

/** The one line an auto-match outcome reads as — beside the Guide's button, and in the shell's toast off it. */
@Composable
internal fun epgMatchSummaryText(summary: EpgMatchSummary): String = when (summary) {
    EpgMatchSummary.CatchupUnavailable -> stringResource(R.string.content_epg_catchup_unavailable)
    EpgMatchSummary.MatchedNoProgrammes -> stringResource(R.string.content_epg_matched_no_programmes)
    EpgMatchSummary.AddPlaylist -> stringResource(R.string.content_epg_add_playlist_first)
    EpgMatchSummary.NoData -> stringResource(R.string.content_epg_no_match_data)
    EpgMatchSummary.AllMatched -> stringResource(R.string.content_epg_all_matched)
    is EpgMatchSummary.NoMatch -> stringResource(R.string.content_epg_no_match, summary.channelName)
    is EpgMatchSummary.AutoMatched -> if (summary.review > 0) {
        stringResource(
            R.string.content_epg_auto_matched,
            pluralStringResource(R.plurals.content_epg_auto_matched_applied, summary.applied, summary.applied),
            pluralStringResource(R.plurals.content_epg_auto_matched_review, summary.review, summary.review),
        )
    } else {
        pluralStringResource(R.plurals.content_epg_auto_matched_no_review, summary.applied, summary.applied)
    }
}

/** The programme on at [time] in a row, or null in a stretch with nothing on. */
private fun programmeAt(progs: List<EpgProgrammeEntity>?, time: Long): EpgProgrammeEntity? =
    progs?.firstOrNull { time in it.startMs until it.stopMs }

/** Across a gap the cursor moves by half an hour, rather than leaping hours to the next programme. */
private const val GAP_STEP_MS = 30L * 60 * 1000

/** How the guide's timeline moves (v2.1.8): short enough to keep up with a held key. The rows centre
 *  through [tv.own.owntv.features.live.centreScrollSpec] (v2.6.0). */
private val GuideScrollIntSpec = androidx.compose.animation.core.tween<Int>(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)

/** The guide starts on the half hour (v2.1.8). */
private const val HALF_HOUR_MS = 30L * 60 * 1000

/** The width of the clock with its date and weather line, which the guide's title wraps short of. */
private val CLOCK_CLEARANCE = 380.mpx

/** Where ◀ ([dir] -1) or ▶ (+1) takes the cursor: programme by programme, half an hour at a time across gaps. */
private fun cursorStep(progs: List<EpgProgrammeEntity>, cursor: Long, dir: Int, windowStart: Long, windowEnd: Long): Long {
    val cur = programmeAt(progs, cursor)
    val target = if (dir > 0) {
        val from = cur?.stopMs ?: cursor
        val next = progs.firstOrNull { it.startMs >= from && it.startMs > cursor }
        when {
            cur != null && (next == null || next.startMs - cur.stopMs > GAP_STEP_MS) -> cur.stopMs
            cur != null -> next!!.startMs
            next != null && next.startMs - cursor <= GAP_STEP_MS -> next.startMs
            else -> cursor + GAP_STEP_MS
        }
    } else {
        val until = cur?.startMs ?: cursor
        val prev = progs.lastOrNull { it.stopMs <= until }
        when {
            cur != null && (prev == null || cur.startMs - prev.stopMs > GAP_STEP_MS) -> cur.startMs - GAP_STEP_MS
            cur != null -> prev!!.startMs
            prev != null && cursor - prev.stopMs <= GAP_STEP_MS -> prev.startMs
            else -> cursor - GAP_STEP_MS
        }
    }
    return target.coerceIn(windowStart, windowEnd - 1)
}

/** What OK on a programme does (v2.6.0, OwnTV v5.2.1 #300): see [guideOkAction]. */
internal enum class GuideOk { DETAILS, CATCHUP, PREVIEW, WATCH }

/**
 * OK in the guide (v2.6.0, after OwnTV v5.2.1 #300): moving through the guide never tunes the preview. On
 * what is on now, OK brings the channel into the preview and OK again on the channel already there watches
 * it full screen (straight to full screen with the preview off). As OwnTV (owner's choice), OK on any
 * programme of a channel not in the preview brings it into the preview first; once it is there, a programme
 * still to come opens its details (remind, record) and one that has been on plays from the archive when it
 * can, else its details. A row with no programme counts as now.
 */
internal fun guideOkAction(
    startMs: Long?,
    stopMs: Long?,
    now: Long,
    canCatchup: Boolean,
    previewOn: Boolean,
    inPreview: Boolean,
): GuideOk = when {
    // OwnTV's rule (owner, v2.6.0): a channel not in the preview comes into it first, whatever the programme.
    previewOn && !inPreview -> GuideOk.PREVIEW
    startMs != null && startMs > now -> GuideOk.DETAILS
    stopMs != null && stopMs <= now -> if (canCatchup) GuideOk.CATCHUP else GuideOk.DETAILS
    else -> GuideOk.WATCH
}

/** Where "now" starts in a row: the start of the programme on air, or now itself in a gap. */
private fun nowAnchor(progs: List<EpgProgrammeEntity>, now: Long): Long = programmeAt(progs, now)?.startMs ?: now

/** Whether the cursor is on what is on air now (the programme, or the gap around now). */
private fun cursorAtNow(progs: List<EpgProgrammeEntity>, cursor: Long, now: Long): Boolean {
    val onNow = programmeAt(progs, now)
    return if (onNow != null) programmeAt(progs, cursor) == onNow else kotlin.math.abs(cursor - now) < GAP_STEP_MS
}

/** The stream facts the info shows for a playing preview: resolution ("1080p", "4K"), frame rate and audio. */
internal fun guideStreamChips(chips: List<String>): List<String> = chips.filter { c ->
    c == "4K" || (c.endsWith("p") && c.dropLast(1).isNotEmpty() && c.dropLast(1).all { it.isDigit() }) ||
        c.endsWith(" FPS") || c == "MONO" || c == "STEREO" || c == "5.1" || c == "7.1"
}

/**
 * Live TV (v2): Live TV and the TV Guide are one screen. The video and the programme under the cursor
 * on top, a slim header (day, order, search, auto-match) with the ruler, then the channel rows.
 *
 * Each row's programmes are one focus target with a time cursor, so a week of guide stays one node per
 * row. ▲ ▼ change channel at the same time; ▶ goes forward through the programmes; ◀ goes back to what
 * is on now, and ◀ on what is on now goes into the categories (◀ again: the menu with Settings). Holding ◀
 * jumps back to now, or — already at now — steps into the programmes that have been on.
 *
 * v2.6.0 (as TiviMate): on a TV ◀ on what is on now slides the categories in from the left beside the menu's
 * icons, ◀ again opens the menu with its names, ▶ ▶ goes back; the guide slides aside with them, kept at its
 * width ([GuideNav] has the moves). Highlighting a category switches the guide to it after a short rest. A
 * phone keeps them as a sheet over the guide.
 *
 * OK on what is on now puts the channel in the video; OK again on that channel goes full screen. OK on
 * a programme that has been on plays it from the archive; on one still to come it opens its details.
 * Holding OK (or ☰) opens the details of any programme, Record included.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EpgScreen(
    modifier: Modifier = Modifier,
    onAddEpg: () -> Unit = {},
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onContentScrolled: (Boolean) -> Unit = {},
    /** Required: every live tune in the app goes through the one shared path in LiveViewModel, so a
     *  channel gets the same Prefer HLS handling, ExoPlayer→mpv ladder, per-channel engine pin and
     *  external-player routing however the user reached it. */
    onPlayChannel: (channel: ChannelEntity, channels: List<ChannelEntity>) -> Unit,
    /** "Watch from start" on a catch-up programme. Required for the same reason, and additionally so the
     *  archive is tracked as catch-up playback (which decides what engine toggle the player HUD offers). */
    onPlayCatchup: (channel: ChannelEntity, programme: EpgProgrammeEntity) -> Unit,
    /** False while the full-screen or mini player owns the video surface. */
    previewEnabled: Boolean = true,
    liveMode: Boolean = false,
    /** How ▶ from the rail enters the guide: at the grid row focus was on (the rail's entry hook, as Home's). */
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    /** ◀ from the categories: the app's menu (Settings and the other sections). */
    onOpenRail: () -> Unit = {},
    /** Where the video box is (in the window) while it shows the preview, null when it does not, and its
     *  corner radius. Given, the shell draws the video there with its one player surface — the same one
     *  full screen uses — and this screen leaves that box to it. */
    onVideoBounds: ((androidx.compose.ui.geometry.Rect?, Dp) -> Unit)? = null,
    /** Whether the categories are out beside the guide, so the shell keeps the rail's capsule beside them. */
    onPanelOpen: (Boolean) -> Unit = {},
    /** Where the categories start from the screen edge: past the rail's capsule, or the open rail. */
    panelInset: Dp = 0.dp,
    /** Where the guide starts while the categories are in (past a floating rail's capsule), which it is laid
     *  out for once; the categories and the open rail slide it over rather than laying it out again (v2.6.0). */
    dockRestInset: Dp = 0.dp,
) {
    val vm: EpgViewModel = koinViewModel()
    // Live TV's view model: its preview player (no second decoder), its category list and playlist marks.
    val liveVm: LiveViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val liveNow by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    val query by vm.query.collectAsStateWithLifecycle()
    val matching by vm.matching.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val matchSummary by vm.matchSummary.collectAsStateWithLifecycle()
    val sortGuide by vm.sortGuide.collectAsStateWithLifecycle()
    val categoryFilter by vm.categoryFilter.collectAsStateWithLifecycle()
    val guideWidthShares by vm.guideWidthShares.collectAsStateWithLifecycle()
    val catchupPlayer by vm.catchupPlayer.collectAsStateWithLifecycle()
    val recordingRows by vm.recordingRows.collectAsStateWithLifecycle()
    val reminderRows by vm.reminderRows.collectAsStateWithLifecycle()
    val leadMinutes by vm.reminderLeadMinutes.collectAsStateWithLifecycle()
    val railItems by liveVm.railItems.collectAsStateWithLifecycle()
    val liveSelected by liveVm.selectedKey.collectAsStateWithLifecycle()
    val playlistMarks by liveVm.playlistMarks.collectAsStateWithLifecycle()
    val livePreviewSetting by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    val previewChannel by liveVm.previewChannel.collectAsStateWithLifecycle()
    val previewArmed by liveVm.previewArmed.collectAsStateWithLifecycle()
    val previewChips by liveVm.previewEngine.streamChips.collectAsStateWithLifecycle()
    val reveal by liveVm.reveal.collectAsStateWithLifecycle()
    val guidePreview by vm.guidePreview.collectAsStateWithLifecycle()

    // Live TV's own category (the guide and Live TV are one screen); the plain guide keeps its own choice.
    val categoryKey: LiveKey? = if (liveMode) liveSelected.takeUnless { it == LiveKey.All } else categoryFilter
    LaunchedEffect(liveMode, liveSelected) { if (liveMode) vm.setCategoryFilter(liveSelected) }
    LaunchedEffect(Unit) { vm.load() } // reload from DB each time the guide is opened

    // The video plays the channel the user picked with OK — moving through the grid, or browsing the
    // categories (v2.6.0), never retunes it ([guideOkAction], OwnTV v5.2.1 #300).
    val effectivePreview = previewEnabled && livePreviewSetting && guidePreview
    // Switching the Guide's own preview off stops what is already playing, not only the next channel.
    LaunchedEffect(guidePreview) { if (!guidePreview && previewEnabled) liveVm.stopPreview() }
    LaunchedEffect(previewChannel?.id, effectivePreview, previewArmed) {
        if (!effectivePreview || !previewArmed) return@LaunchedEffect
        val ch = previewChannel ?: return@LaunchedEffect
        delay(150)
        liveVm.playPreview(ch)
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowListState = rememberLazyListState()
    LaunchedEffect(rowListState.firstVisibleItemIndex > 0) { onContentScrolled(rowListState.firstVisibleItemIndex > 0) }

    // --- The time axis: one scroll position shared by the ruler, the now-line and every row. ----------
    val ppm = with(density) { GuideGridDefaults.PxPerMin.toPx() }
    var timelinePx by remember { mutableIntStateOf(0) }
    var scrollPx by remember { mutableIntStateOf(0) }
    // What the ruler and the rows draw: the scroll position eased into place (v2.1.8), so a move along the
    // timeline slides rather than jumps. Positions are worked out on [scrollPx], the target.
    val shownScrollPx by androidx.compose.animation.core.animateIntAsState(scrollPx, GuideScrollIntSpec)
    fun pxAt(t: Long): Float = (t - state.windowStart) / 60_000f * ppm
    fun maxScroll(): Int = (pxAt(state.windowEnd) - timelinePx).toInt().coerceAtLeast(0)
    fun timeAt(px: Float): Long = state.windowStart + (px / ppm * 60_000f).toLong()
    // A time is shown from the half hour it falls in (v2.1.8): at 9:45 the guide starts at 9:30, at 10:05 at
    // 10:00 — on the local clock, so a zone with a :30 or :45 offset still lands on its own half hours.
    fun halfHourOf(t: Long): Long = t - Math.floorMod(t + java.util.TimeZone.getDefault().getOffset(t), HALF_HOUR_MS)
    fun showTime(t: Long) {
        scrollPx = pxAt(halfHourOf(t)).toInt().coerceIn(0, maxScroll())
    }
    LaunchedEffect(state.windowStart, timelinePx, state.channels.isNotEmpty()) {
        if (timelinePx > 0 && state.channels.isNotEmpty()) showTime(System.currentTimeMillis())
    }

    // --- Rows, focus and the cursor ------------------------------------------------------------------
    val rowRequesters = remember { HashMap<Int, FocusRequester>() }
    fun rowFocus(i: Int): FocusRequester = rowRequesters.getOrPut(i) { FocusRequester() }
    var focusedRow by remember { mutableIntStateOf(0) }
    var gridHasFocus by remember { mutableStateOf(false) }
    var cursorTime by remember { mutableStateOf(0L) }
    // Browsing what has been on: only after holding ◀ at now. Until then ◀ stops at now and opens the menu.
    var pastMode by remember { mutableStateOf(false) }
    // The programme the block on top shows and the buttons act on: the one under the cursor.
    var selected by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity?>?>(null) }
    // v2.6.0: the TV's guide, categories and menu ([GuideNav]); the phone's sheet over the guide instead.
    var nav by remember { mutableStateOf(GuideNav.GUIDE) }
    var sheetOpen by remember { mutableStateOf(false) }

    fun backToNow() {
        val now = System.currentTimeMillis()
        pastMode = false
        cursorTime = now
        showTime(now)
    }
    // Back while looking at what has been on: back to now first.
    BackHandler(enabled = pastMode && gridHasFocus) { backToNow() }

    // ▲ ▼ by row number (playbook 2). v2.6.0: focus moves to the row at once and the list's centring
    // bring-into-view ([centreScrollSpec]) glides it to the middle, so a held key retargets one running
    // scroll instead of queueing a scroll per press. A row not composed yet (the wrap round, a jump) is
    // scrolled near the middle first; a held key keeps counting from that target.
    var stepTarget by remember { mutableStateOf<Int?>(null) }
    var stepJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // The first row to scroll to so row [i] lands about the middle (rows are all one height).
    fun centredFirst(i: Int): Int {
        val info = rowListState.layoutInfo
        val pitch = info.visibleItemsInfo.firstOrNull()?.size?.plus(info.mainAxisItemSpacing) ?: 0
        val half = if (pitch > 0) (info.viewportEndOffset - info.viewportStartOffset) / pitch / 2 else 3
        return (i - half).coerceAtLeast(0)
    }
    fun stepTo(i: Int) {
        stepJob?.cancel()
        if (rowListState.layoutInfo.visibleItemsInfo.any { it.index == i } &&
            runCatching { rowFocus(i).requestFocus() }.getOrDefault(false)
        ) {
            stepTarget = null
            return
        }
        stepTarget = i
        stepJob = scope.launch {
            runCatching { rowListState.scrollToItem(centredFirst(i)) }
            withFrameNanos { }
            runCatching { rowFocus(i).requestFocus() }
            stepTarget = null
        }
    }
    // A category was chosen: the list it replaces, until the new one is in (see the categories' onSelect).
    var categoryFocusFrom by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    // Back to the grid at the row focus was on (after a menu, the sheet, a jump).
    fun focusGrid(row: Int = focusedRow) {
        scope.launch {
            // v2.1.3: the row may not be there yet — a new category reloads the list — so keep trying for a
            // moment. Giving up after two frames left focus to fall to the menu, where ▼ then moved.
            repeat(40) {
                val i = row.coerceIn(0, (state.channels.size - 1).coerceAtLeast(0))
                if (state.channels.isNotEmpty() && !state.loading) {
                    val visible = rowListState.layoutInfo.visibleItemsInfo.any { it.index == i }
                    if (!visible) runCatching { rowListState.scrollToItem(centredFirst(i)) }
                    withFrameNanos { }
                    if (runCatching { rowFocus(i).requestFocus() }.getOrDefault(false)) return@launch
                }
                delay(50)
            }
        }
    }
    LaunchedEffect(categoryFocusFrom, state.channels, state.loading) {
        val from = categoryFocusFrom ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        // A category with the same channels posts no new list: go after a moment anyway.
        if (state.channels === from) delay(1_500)
        categoryFocusFrom = null
        runCatching { rowListState.scrollToItem(0) }
        focusGrid(0)
    }
    // The cursor stays in view: scroll only when it reaches an edge, by one slot of room.
    LaunchedEffect(cursorTime, timelinePx) {
        if (!gridHasFocus || timelinePx <= 0 || cursorTime <= 0L) return@LaunchedEffect
        val x = pxAt(cursorTime)
        val margin = GuideGridDefaults.SlotMin * ppm
        when {
            // Back past the left edge: the half hour the cursor is in starts the guide again (v2.1.8).
            x < scrollPx -> showTime(cursorTime)
            // Forward past the right edge's slot of room: the next half hour that makes room starts the guide.
            x > scrollPx + timelinePx - margin -> {
                val need = timeAt(x - timelinePx + margin)
                val start = halfHourOf(need).let { if (it < need) it + HALF_HOUR_MS else it }
                scrollPx = pxAt(start).toInt().coerceIn(0, maxScroll())
            }
        }
    }

    // Back from the player (the shell notes the channel that was playing, a zap included) or from Settings:
    // the row the guide was on.
    // v2.1.7: the channel playing comes first, and when the categories were changed while it played (Back to
    // the guide, a new category, Back to the picture, Back again) the guide goes back to the category it was
    // watched from — or All — so it opens on that channel, not on the category browsed last.
    var restoreCategoryTried by remember { mutableStateOf(0) }
    LaunchedEffect(restoreFocus, state.loading, state.channels) {
        if (!restoreFocus) { restoreCategoryTried = 0; return@LaunchedEffect }
        if (state.loading || state.channels.isEmpty()) return@LaunchedEffect
        val playing = previewChannel
        if (playing != null && state.channels.none { it.id == playing.id } && restoreCategoryTried < 2) {
            val current = categoryKey ?: LiveKey.All
            val back = (if (restoreCategoryTried == 0) vm.tunedCategory else null)?.takeIf { it != current } ?: LiveKey.All
            restoreCategoryTried++
            if (back != current) {
                if (liveMode) liveVm.select(back) else vm.setCategoryFilter(back)
                return@LaunchedEffect // runs again with the new category's list
            }
        }
        val idx = listOfNotNull(playing?.id, vm.lastFocusedChannelId, vm.lastTunedChannelId)
            .firstNotNullOfOrNull { id -> state.channels.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
        state.channels.getOrNull(idx)?.let { vm.lastFocusedChannelId = it.id }
        focusedRow = idx
        delay(80)
        focusGrid(idx)
        onRestored()
    }
    // Opening Live TV again (from Settings, Sport, the rail): the row focus was on last time, already in
    // view, so ▶ from the rail and the info on top land there — not on the first channel.
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.channels) {
        if (positioned || restoreFocus || reveal != null || state.loading || state.channels.isEmpty()) return@LaunchedEffect
        positioned = true
        val id = vm.lastFocusedChannelId ?: previewChannel?.id ?: return@LaunchedEffect
        val idx = state.channels.indexOfFirst { it.id == id }.takeIf { it > 0 } ?: return@LaunchedEffect
        focusedRow = idx
        runCatching { rowListState.scrollToItem(centredFirst(idx)) }
    }
    // A reload (a playlist or guide refresh) can add, drop or reorder rows: the cursor follows its channel,
    // not its old row number, and focus goes back to it when the refresh took it away from the grid.
    var gridLostAt by remember { mutableStateOf(0L) }
    LaunchedEffect(gridHasFocus) { if (!gridHasFocus) gridLostAt = android.os.SystemClock.uptimeMillis() }
    LaunchedEffect(state.channels) {
        if (state.loading || state.channels.isEmpty()) return@LaunchedEffect
        val id = vm.lastFocusedChannelId ?: return@LaunchedEffect
        val idx = state.channels.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@LaunchedEffect
        // (v2.6.0) Not while the categories are out: browsing them switches the list under them.
        val refocus = (gridHasFocus || android.os.SystemClock.uptimeMillis() - gridLostAt < 1_500L) && nav == GuideNav.GUIDE && !sheetOpen
        if (idx == focusedRow && gridHasFocus) return@LaunchedEffect
        focusedRow = idx
        if (refocus) focusGrid(idx)
    }
    // Search's "Go to channel": its row, once its category is on screen.
    LaunchedEffect(reveal, state.loading, state.channels) {
        val r = reveal ?: return@LaunchedEffect
        if (!liveMode || state.loading) return@LaunchedEffect
        val idx = state.channels.indexOfFirst { it.id == r.id }
        if (idx < 0) return@LaunchedEffect
        focusedRow = idx
        delay(80)
        focusGrid(idx)
        liveVm.revealDone()
    }

    // --- Overlays --------------------------------------------------------------------------------------
    var menuFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var channelMenuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var askPlayerFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var matchingChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var offsetChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var orderOpen by remember { mutableStateOf(false) }
    var dayOpen by remember { mutableStateOf(false) }
    // The phone's category sheet ([sheetOpen]); a TV slides them in instead ([nav]).
    var sheetHadFocus by remember { mutableStateOf(false) }
    var sheetEditing by remember { mutableStateOf(false) }
    // Set from the layout below: a TV slides the categories in beside the guide, a phone opens the sheet.
    var tvLayout by remember { mutableStateOf(true) }
    var catFocused by remember { mutableStateOf(false) }
    // Focus anywhere in the guide (the rows, its tools): back to GUIDE when it got there on its own.
    var guideHasFocus by remember { mutableStateOf(false) }
    var catLeaveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    // The field reports "not focused" when it first attaches; it only closes after it has had focus (playbook 10).
    var searchHadFocus by remember { mutableStateOf(false) }
    // Where focus goes back to when an overlay closes: the control or button that opened it.
    var returnTo by remember { mutableStateOf<(() -> Unit)?>(null) }
    val anyOverlay = menuFor != null || channelMenuFor != null || askPlayerFor != null || matchingChannel != null ||
        offsetChannel != null || orderOpen || dayOpen || review.isNotEmpty()
    var hadOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(anyOverlay) {
        if (anyOverlay) { hadOverlay = true; return@LaunchedEffect }
        if (!hadOverlay) return@LaunchedEffect
        hadOverlay = false
        delay(60)
        (returnTo ?: { focusGrid() }).invoke()
    }
    // The one-line outcome of Auto-match EPG goes after a while (the review, when there is one, says it instead).
    LaunchedEffect(matchSummary, review.isEmpty()) {
        if (matchSummary != null && review.isEmpty()) { delay(8_000); vm.clearSummary() }
    }

    val channelsNow = state.channels
    val watch: (ChannelEntity) -> Unit = { ch -> vm.noteChannelTuned(ch, categoryKey ?: LiveKey.All); onPlayChannel(ch, channelsNow) }
    val playFromStart: (ChannelEntity, EpgProgrammeEntity) -> Unit = { ch, p ->
        when (catchupPlayer) {
            SettingsRepository.CatchupPlayer.ASK -> askPlayerFor = ch to p
            SettingsRepository.CatchupPlayer.INTERNAL -> { vm.noteChannelTuned(ch); onPlayCatchup(ch, p) }
            SettingsRepository.CatchupPlayer.EXTERNAL -> { vm.noteChannelTuned(ch); vm.playCatchupExternal(ch, p) }
        }
    }
    val openDetails: (ChannelEntity, EpgProgrammeEntity?) -> Unit = { ch, p ->
        returnTo = null
        if (p != null) menuFor = ch to p else channelMenuFor = ch
    }
    // OK: what is on now goes into the video first and full screen on the second press; a programme that
    // has been on plays from the archive; one still to come opens its details (remind, record).
    val onOk: (ChannelEntity, EpgProgrammeEntity?) -> Unit = { ch, p ->
        val now = System.currentTimeMillis()
        val inPreview = previewArmed && previewChannel?.id == ch.id
        val catchup = p != null && p.stopMs <= now && vm.canCatchup(ch, p, now)
        when (guideOkAction(p?.startMs, p?.stopMs, now, catchup, effectivePreview, inPreview)) {
            GuideOk.DETAILS -> openDetails(ch, p)
            GuideOk.CATCHUP -> playFromStart(ch, p!!)
            GuideOk.PREVIEW -> liveVm.previewFromGuide(ch) // (v2.5.1) no focus delay
            GuideOk.WATCH -> watch(ch)
        }
    }

    val dayTool = remember { FocusRequester() }
    val orderTool = remember { FocusRequester() }
    val searchTool = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val sheetFocus = remember { FocusRequester() }
    val catListState = rememberLazyListState()

    // v2.5.1: focus goes to its new place BEFORE the categories slide away (v2.6.0), so it never drops to
    // the menu on its own. The row first; a row not composed yet waits on the day chips (or the search)
    // inside the guide while [focusGrid] brings it in.
    fun holdFocusInGuide(): Boolean = runCatching { dayTool.requestFocus() }.getOrDefault(false) ||
        runCatching { searchTool.requestFocus() }.getOrDefault(false)
    fun focusRowNow(): Boolean {
        if (state.channels.isEmpty()) return false
        val i = focusedRow.coerceIn(0, state.channels.size - 1)
        return runCatching { rowFocus(i).requestFocus() }.getOrDefault(false)
    }
    fun gridNow() { if (!focusRowNow()) { holdFocusInGuide(); focusGrid() } }

    // v2.6.0: highlighting a category switches the guide to it once the highlight has rested
    // ([CategorySettle]), so a held ▲/▼ does not filter on every row. The rows on screen stay until the new
    // list is in (refilter swaps it, no spinner), and the video is not retuned: it plays what OK picked.
    val settle = remember { CategorySettle<LiveKey>(CATEGORY_SETTLE_MS) }
    var settleTick by remember { mutableIntStateOf(0) }
    val currentCategory by rememberUpdatedState(categoryKey ?: LiveKey.All)
    // The list a category switch replaces, until the new one is in.
    var switchFrom by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    fun applyCategory(key: LiveKey) {
        if (key == currentCategory) return
        if (switchFrom == null) switchFrom = state.channels
        if (liveMode) liveVm.select(key) else vm.setCategoryFilter(key)
        focusedRow = 0
        vm.lastFocusedChannelId = null
    }
    fun highlightCategory(idx: Int) {
        val key = railItems.getOrNull(idx)?.key ?: return
        settle.highlight(key, currentCategory, android.os.SystemClock.uptimeMillis())
        settleTick++
    }
    LaunchedEffect(settleTick) {
        if (settleTick == 0) return@LaunchedEffect
        delay(CATEGORY_SETTLE_MS)
        settle.due(android.os.SystemClock.uptimeMillis())?.let { applyCategory(it) }
    }
    // A new category's rows start at the top, once they are in (one with the same channels posts no new list).
    LaunchedEffect(switchFrom, state.channels, state.loading) {
        val from = switchFrom ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        if (state.channels === from) delay(1_500)
        switchFrom = null
        runCatching { rowListState.scrollToItem(0) }
    }
    // Back to the rows: the row focus was on, or — after a switch whose list is still coming — its first row
    // once it is in (v2.1.5: focusing the old list's row lost focus when that list was replaced).
    fun toGuideRows() {
        val from = switchFrom
        if (from != null) { holdFocusInGuide(); categoryFocusFrom = from } else gridNow()
    }
    // Asks the categories to take focus (counted, so asking again while they are out still works).
    var catFocusAsk by remember { mutableIntStateOf(0) }
    fun navGo(event: GuideNavEvent) {
        val step = nav.on(event)
        catLeaveJob?.cancel()
        // Back to the guide keeps the highlighted category, even before its rest is up (as TiviMate).
        if (step.nav == GuideNav.GUIDE) settle.flush()?.let { applyCategory(it) }
        when (step.focus) {
            GuideNavFocus.GRID -> toGuideRows()
            GuideNavFocus.GRID_FIRST -> { focusedRow = 0; toGuideRows() }
            GuideNavFocus.RAIL -> onOpenRail()
            // The panel is always composed on a TV: it takes focus at once, before it slides in.
            GuideNavFocus.CATEGORIES -> { nav = step.nav; if (!runCatching { sheetFocus.requestFocus() }.getOrDefault(false)) catFocusAsk++ }
            GuideNavFocus.NONE -> Unit
        }
        nav = step.nav
    }
    // The phone's sheet: the same order, the row first and then the sheet goes.
    fun closeSheetToGrid() {
        settle.flush()?.let { applyCategory(it) }
        toGuideRows()
        sheetOpen = false
    }

    // ◀ / hold ◀ / ▶ in a row. [progs] is that row's guide.
    val openSheet: () -> Unit = { returnTo = null; if (tvLayout) navGo(GuideNavEvent.OPEN) else sheetOpen = true }
    fun moveCursor(progs: List<EpgProgrammeEntity>, t: Long) {
        val now = System.currentTimeMillis()
        if (t >= nowAnchor(progs, now)) { pastMode = false; cursorTime = maxOf(t, now) } else cursorTime = t
    }
    val onLeft: (List<EpgProgrammeEntity>) -> Unit = { progs ->
        val now = System.currentTimeMillis()
        val target = cursorStep(progs, cursorTime, -1, state.windowStart, state.windowEnd)
        when {
            target >= cursorTime -> openSheet() // the start of the guide
            !pastMode && target < nowAnchor(progs, now) -> openSheet() // ◀ on what is on now
            else -> moveCursor(progs, target)
        }
    }
    val onHoldLeft: (List<EpgProgrammeEntity>) -> Unit = { progs ->
        val now = System.currentTimeMillis()
        if (!cursorAtNow(progs, cursorTime, now)) {
            backToNow()
        } else {
            val target = cursorStep(progs, cursorTime, -1, state.windowStart, state.windowEnd)
            if (target < cursorTime) { pastMode = true; cursorTime = target }
        }
    }
    val onRight: (List<EpgProgrammeEntity>) -> Unit = { progs ->
        moveCursor(progs, cursorStep(progs, cursorTime, +1, state.windowStart, state.windowEnd))
    }

    /** What [p] on [ch] can do right now — the programme menu, Record ▾ and the buttons on top share it. */
    @Composable
    fun actionsFor(ch: ChannelEntity, p: EpgProgrammeEntity): GuideProgrammeActions = guideProgrammeActions(
        vm, ch, p, liveNow,
        onWatch = { watch(ch) },
        onPlayFromStart = { playFromStart(ch, p) },
        onPickEpg = { matchingChannel = ch },
        onEpgOffset = { offsetChannel = ch },
    )

    // Channel names as the rows show them: provider tags parsed off, and the category's own name left out
    // when every channel in it starts with it ("Sky Cinema Highlights" under Sky Cinema → "Highlights").
    val (entries, groupsHeading) = liveCategoryEntries(railItems, emptyMap())
    val categoryEntry = entries.firstOrNull { it.item.key == (categoryKey ?: LiveKey.All) }
    val categoryLabel = categoryEntry?.label ?: stringResource(R.string.content_category_all_channels)
    val prefix = categoryLabel.takeIf { categoryKey is LiveKey.Folder || categoryKey is LiveKey.Custom }
    fun rowName(ch: ChannelEntity): String {
        val name = ProviderTags.parse(ch.name).name
        val rest = prefix?.let { p -> name.takeIf { it.startsWith("$p ", ignoreCase = true) }?.substring(p.length)?.trim() }
        return rest?.takeIf { it.isNotEmpty() } ?: name
    }
    val remindersByChannel = remember(reminderRows) { reminderRows.groupBy({ it.channelId }, { it.startMs }).mapValues { it.value.toSet() } }
    val recordingsByChannel = remember(recordingRows) {
        recordingRows.filter { it.status != RecordingStatus.CANCELLED }
            .groupBy({ it.channelId }, { it.programmeStartMs }).mapValues { it.value.toSet() }
    }

    // ▶ from the rail (or opening Live TV from it): the categories when the menu was opened from them (MENU),
    // else the channel row.
    // Rows that are not there yet (still loading, not laid out) are focused once they are — never "Today".
    var pendingEntry by remember { mutableStateOf(false) }
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke {
            when {
                // v2.6.0: ▶ on the menu beside the categories: back into them, the menu folding to its icons.
                tvLayout && nav == GuideNav.MENU -> { navGo(GuideNavEvent.RAIL_RIGHT); true }
                !state.loading && state.channels.isEmpty() -> false
                else -> {
                    val ok = state.channels.isNotEmpty() &&
                        runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() }.getOrDefault(false)
                    if (!ok) pendingEntry = true
                    true
                }
            }
        }
        onDispose { onEntryHook?.invoke(null) }
    }
    LaunchedEffect(pendingEntry, state.loading, state.channels) {
        if (!pendingEntry || state.loading || state.channels.isEmpty()) return@LaunchedEffect
        pendingEntry = false
        focusGrid()
    }
    DisposableEffect(Unit) { onDispose { onPanelOpen(false) } }

    // Back in the categories (or the phone's sheet): back to the guide's row. Only while focus is in them: on
    // the menu this is off and the shell's Back asks to leave the app.
    BackHandler(enabled = sheetOpen || (nav == GuideNav.CATEGORIES && catFocused)) {
        if (tvLayout) navGo(GuideNavEvent.BACK) else closeSheetToGrid()
    }
    LaunchedEffect(sheetOpen) {
        if (sheetOpen) { withFrameNanos { }; runCatching { sheetFocus.requestFocus() } } else sheetHadFocus = false
    }
    // The categories' rows can be a frame late (a first open): focus them then (a few frames, at most).
    LaunchedEffect(catFocusAsk) {
        if (catFocusAsk == 0) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (nav != GuideNav.CATEGORIES) return@LaunchedEffect
            if (runCatching { sheetFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(searchOpen) { if (searchOpen) { withFrameNanos { }; runCatching { searchFocus.requestFocus() } } }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Entering from the rail lands in the grid, on the row focus was on. Not while the search field
            // is opening: the Search button it replaces held focus, so the field's own request enters from
            // outside too, and redirecting it would send focus to the grid instead.
            .focusProperties {
                onEnter = {
                    if (!(searchOpen && !searchHadFocus)) {
                        val ok = state.channels.isNotEmpty() &&
                            runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() }.getOrDefault(false)
                        if (!ok) pendingEntry = true
                    }
                }
            }
            .focusGroup(),
    ) {
        val screenW = maxWidth
        // Phones (and anything this narrow): the video full width on top, the guide below it.
        val compact = maxWidth < 600.dp
        fun fx(px: Int) = screenW * (px / 1920f)
        // TV (v2.6.0, as TiviMate): the guide is laid out once at its full width; the categories slide in from
        // the left beside the menu's icons and push it over, its right part running off screen. Everything
        // moves by placement offsets read inside `offset { }` (no recomposition or relayout per frame), on the
        // one shared [guideSlideSpec]. A phone keeps them as a sheet over the guide.
        val tvHere = !compact
        androidx.compose.runtime.SideEffect { if (tvLayout != tvHere) tvLayout = tvHere }
        val catsOut = tvHere && nav.categoriesOpen
        LaunchedEffect(tvHere) { if (!tvHere) nav = GuideNav.GUIDE else sheetOpen = false }
        LaunchedEffect(catsOut) { onPanelOpen(catsOut) }
        // Wide enough for long category names on two or three lines (v2.1.7).
        val panelW = fx(500)
        val panelGap = fx(16)
        val slide = guideSlideSpec<Float>()
        // 0 = the guide alone, 1 = the categories out. Read only at placement.
        val catsShown = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(catsOut) { catsShown.animateTo(if (catsOut) 1f else 0f, slide) }
        // Past the capsule, or past the open menu: the categories and the guide move over with it.
        val panelStart by androidx.compose.animation.core.animateDpAsState(if (tvHere) panelInset else 0.dp, guideSlideSpec())
        val guideRest: Dp = if (tvHere) dockRestInset else 0.dp
        val guideW: Dp = (screenW - guideRest).coerceAtLeast(0.dp)
        // How far the guide is pushed over while the categories are out (the menu's own width aside).
        val pushAtRest = (panelInset + panelW + panelGap - guideRest).coerceAtLeast(0.dp)
        // The programme text beside the video wraps short of the screen edge while the guide is pushed over:
        // one relayout of that block alone, after the slide out and before the slide back.
        var infoEnd by remember { mutableStateOf(0.dp) }
        val infoTarget = if (catsOut) pushAtRest else 0.dp
        LaunchedEffect(infoTarget) {
            if (infoTarget > infoEnd) delay(GUIDE_SLIDE_MS + 40L)
            infoEnd = infoTarget
        }
        val rowStart: Dp = if (compact) 4.dp else fx(48)
        val rowEnd: Dp = if (compact) 4.dp else fx(64)
        val labelW: Dp = when {
            compact -> 136.dp
            else -> guideWidthShares?.let { (guideW - fx(112)) * (it.channels / 100f) } ?: (guideW * GuideGridDefaults.ChannelColShare)
        }
        val timelineW = guideW - rowStart - labelW - rowEnd
        LaunchedEffect(timelineW) { timelinePx = with(density) { timelineW.roundToPx() } }

        val top = selected ?: state.channels.getOrNull(focusedRow)?.let { ch -> ch to programmeAt(vm.cachedProgrammes(ch), liveNow) }
        val topChannel = top?.first
        val topProgramme = top?.second
        val full by produceState<EpgProgrammeEntity?>(null, topProgramme?.id) {
            value = topProgramme?.id?.takeIf { it > 0 }?.let { vm.programmeFull(it) }
        }
        val shown = full ?: topProgramme
        val videoChannel = if (effectivePreview) previewChannel ?: topChannel else topChannel
        val showVideo = effectivePreview && previewArmed

        // The video, and the programme under the cursor beside it (below it on a phone).
        val sharedSurface = onVideoBounds != null
        // "No video here" only when the preview is switched off — never while full screen has the player:
        // the shell keeps its surface at this box across that, which is the whole point.
        val previewWanted = livePreviewSetting && guidePreview
        // The shell's surface draws over everything, so it steps back while the phone's sheet is over the
        // video. Nothing is reported until the box has been measured: an early "none" right after Back would
        // drop the very surface this is meant to keep.
        // v2.6.0: the box's place goes to the shell straight from its positioning, so the picture slides with
        // the guide frame by frame — no state here, so the guide does not recompose while it moves.
        val lastVideoRect = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
        var videoMeasured by remember { mutableStateOf(false) }
        val videoRadius = if (compact) 0.dp else 20.mpx
        val reportVideo by rememberUpdatedState(sharedSurface && previewWanted && !sheetOpen)
        val videoBoundsTo by rememberUpdatedState(onVideoBounds)
        val videoRadiusNow by rememberUpdatedState(videoRadius)
        LaunchedEffect(videoMeasured, previewWanted, sheetOpen, sharedSurface) {
            if (!sharedSurface) return@LaunchedEffect
            when {
                !previewWanted || sheetOpen -> onVideoBounds?.invoke(null, 0.dp)
                videoMeasured -> lastVideoRect[0]?.let { onVideoBounds?.invoke(it, videoRadius) }
            }
        }
        @Composable
        fun TopVideo(mod: Modifier) = GuideVideo(
            channel = videoChannel,
            channelLine = videoChannel?.let { listOfNotNull(it.number?.toString(), ProviderTags.parse(it.name).name).joinToString(" · ") },
            nowTitle = videoChannel?.let { ch -> programmeAt(vm.cachedProgrammes(ch), liveNow)?.takeIf { liveNow < it.stopMs }?.title },
            previewEngine = liveVm.previewEngine,
            // The shell draws the picture here; this box keeps the logo for while it cannot (categories open).
            showVideo = showVideo && !sharedSurface,
            compact = compact,
            modifier = if (sharedSurface && previewWanted) {
                mod.onGloballyPositioned {
                    val r = it.boundsInRoot()
                    lastVideoRect[0] = r
                    if (!videoMeasured) videoMeasured = true
                    if (reportVideo) videoBoundsTo?.invoke(r, videoRadiusNow)
                }
            } else mod,
        )

        @Composable
        fun TopInfo(mod: Modifier, titleEndInset: androidx.compose.ui.unit.Dp = 0.dp) {
            if (topChannel == null) return
            val chName = ProviderTags.parse(topChannel.name).name
            val inPreview = showVideo && previewChannel?.id == topChannel.id
            GuideInfoBlock(
                programme = shown,
                fallbackTitle = chName,
                channelLine = listOfNotNull(topChannel.number?.toString(), chName).joinToString(" · "),
                chips = if (inPreview) guideStreamChips(previewChips) else emptyList(),
                catchup = topChannel.catchup,
                now = liveNow,
                compact = compact,
                modifier = mod,
                titleEndInset = titleEndInset,
                recording = shown != null && recordingRows.any {
                    it.channelId == topChannel.id && it.programmeStartMs == shown.startMs && it.status == RecordingStatus.RECORDING
                },
            )
        }

        // Pushed over by the categories (read at placement, so the slide moves the guide without recomposing
        // or measuring it again). At rest it starts past a floating rail's capsule.
        Column(
            Modifier.fillMaxHeight().width(guideW)
                .offset {
                    val push = (panelStart + panelW + panelGap - guideRest).coerceAtLeast(0.dp).toPx() * catsShown.value
                    androidx.compose.ui.unit.IntOffset((guideRest.toPx() + push).roundToInt(), 0)
                }
                .onFocusChanged {
                    // Focus reaching the guide by any way (a row, a tool, a tap, a restore) puts the categories away.
                    if (it.hasFocus && !guideHasFocus && nav != GuideNav.GUIDE) navGo(GuideNavEvent.GRID_FOCUSED)
                    guideHasFocus = it.hasFocus
                },
        ) {
            if (compact) {
                TopVideo(Modifier.fillMaxWidth())
                TopInfo(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp))
            } else {
                // Top (32 → 329): the video (528 wide), and the programme beside it.
                Row(Modifier.padding(start = fx(64), end = fx(64) + infoEnd, top = 32.mpx).fillMaxWidth().height(297.mpx)) {
                    TopVideo(Modifier.width(528.mpx))
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        // The clock (and its date and weather line) sits over this box's top-right corner: a long
                        // title wraps short of it instead of running under it.
                        val infoW = minOf(maxWidth - 40.mpx, 1100.mpx)
                        val clockClear = (CLOCK_CLEARANCE - (maxWidth - 40.mpx - infoW)).coerceAtLeast(0.dp)
                        TopInfo(Modifier.padding(start = 40.mpx).widthIn(max = 1100.mpx), titleEndInset = clockClear)
                        matchSummary?.takeIf { review.isEmpty() }?.let {
                            Text(
                                epgMatchSummaryText(it), style = stageText(16, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomEnd).widthIn(max = 620.mpx), textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }

            // The header (v2.1.0, DPTV Recorder's): the search and the guide's tools over the channel column and
            // the days as chips over the programmes; under it "CHANNEL" over the column and the ruler.
            val viewTime = timeAt(scrollPx + timelinePx / 4f)
            val viewDay = localMidnight(viewTime)
            val guideDays = remember(state.windowStart, state.windowEnd) {
                generateSequence(localMidnight(state.windowStart)) { localMidnight(it + 36L * 60 * 60 * 1000) }.takeWhile { it < state.windowEnd }.toList()
            }
            Row(
                Modifier.padding(top = if (compact) 6.dp else 16.mpx).fillMaxWidth().height(if (compact) 48.mpx else 64.mpx),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.padding(start = rowStart, end = 12.mpx).width(labelW),
                    horizontalArrangement = Arrangement.spacedBy(4.mpx),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (searchOpen || query.isNotEmpty()) {
                        StageSearchField(
                            query = query, onQueryChange = vm::setQuery,
                            placeholder = stringResource(R.string.content_epg_search_hint).trimEnd('…'),
                            modifier = Modifier.weight(1f).focusRequester(searchFocus)
                                .onFocusChanged {
                                    if (it.hasFocus) searchHadFocus = true
                                    else if (searchHadFocus && query.isEmpty()) { searchHadFocus = false; searchOpen = false }
                                },
                        )
                    } else {
                        StageTool(
                            text = stringResource(R.string.content_epg_search_hint), icon = OwnTVIcon.SEARCH, boxed = true,
                            onClick = { searchOpen = true }, modifier = Modifier.weight(1f).focusRequester(searchTool),
                        )
                        StageTool(
                            text = null, icon = OwnTVIcon.SORT,
                            onClick = { returnTo = { runCatching { orderTool.requestFocus() } }; orderOpen = true },
                            modifier = Modifier.focusRequester(orderTool),
                        )
                        if (matching) {
                            Box(Modifier.padding(horizontal = 12.mpx)) { OwnTVSpinner(sizeDp = 18) }
                        } else {
                            StageTool(text = null, icon = OwnTVIcon.SPARKLE, onClick = vm::autoMatchEpg)
                        }
                    }
                }
                if (state.channels.isNotEmpty() && guideDays.isNotEmpty()) {
                    GuideDayChips(
                        days = guideDays, current = viewDay,
                        onPick = { day ->
                            val now = System.currentTimeMillis()
                            showTime(day + (now - localMidnight(now)))
                        },
                        focusRequester = dayTool,
                        modifier = Modifier.width(timelineW),
                    )
                }
            }
            Row(
                Modifier.padding(top = 10.mpx).fillMaxWidth().height(40.mpx),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.padding(start = rowStart).width(labelW).fillMaxHeight().background(GuideColors.Panel),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(R.string.sport_guide_channel_header).uppercase(), style = guideMono(13, 1.5.mpxSp), color = StageColors.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.mpx),
                    )
                }
                if (state.channels.isNotEmpty()) {
                    GuideRuler(
                        windowStart = state.windowStart, windowEnd = state.windowEnd, now = liveNow, scrollPx = shownScrollPx,
                        modifier = Modifier.width(timelineW).height(40.mpx),
                    )
                }
            }

            // The grid.
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 6.mpx)) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { OwnTVSpinner(sizeDp = 44) }
                    // No EPG feed added yet → a guide it can't fill. Point the user to EPG Sources.
                    !state.hasEpgSources && state.channels.isEmpty() -> GuideEmpty(
                        title = stringResource(R.string.content_epg_empty),
                        body = stringResource(R.string.content_epg_add_description),
                        action = stringResource(R.string.content_epg_add), onAction = onAddEpg,
                    )
                    state.channels.isEmpty() -> GuideEmpty(title = state.message?.let { epgMessageText(it) } ?: stringResource(R.string.content_epg_no_guide))
                    else -> {
                        val gx = rowStart + labelW
                        // The number column fits the longest channel number (38 = three digits).
                        val numberWidth = maxOf(38f, (state.channels.maxOfOrNull { it.number?.toString()?.length ?: 0 } ?: 0) * 12f).mpx
                        // v2.6.0: the focused row stays in the middle and the rows glide under it.
                        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides centreScrollSpec) {
                            LazyColumn(
                                state = rowListState,
                                modifier = Modifier
                                    .padding(start = rowStart, end = rowEnd)
                                    .fillMaxSize()
                                    .onFocusChanged {
                                        // Focus reaching the guide by any way while the phone's sheet is out closes
                                        // it, so ◀ opens it (and then the menu) again instead of doing nothing.
                                        if (it.hasFocus && !gridHasFocus && sheetOpen && sheetHadFocus) sheetOpen = false
                                        gridHasFocus = it.hasFocus
                                    }
                                    .focusProperties { onEnter = { runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() } } }
                                    .focusGroup(),
                                verticalArrangement = Arrangement.spacedBy(GuideGridDefaults.RowGap),
                                contentPadding = PaddingValues(bottom = 24.mpx),
                            ) {
                                itemsIndexed(state.channels, key = { _, ch -> ch.id }) { index, channel ->
                                    GuideRow(
                                        vm = vm,
                                        channel = channel,
                                        name = rowName(channel),
                                        dot = playlistMarks[channel.sourceId]?.shownColor,
                                        labelWidth = labelW,
                                        numberWidth = numberWidth,
                                        windowStart = state.windowStart,
                                        windowEnd = state.windowEnd,
                                        now = liveNow,
                                        scrollPx = shownScrollPx,
                                        focusRequester = rowFocus(index),
                                        selectedRow = gridHasFocus && index == focusedRow,
                                        cursorTime = cursorTime,
                                        recordingStarts = recordingsByChannel[channel.id].orEmpty(),
                                        reminderStarts = remindersByChannel[channel.id].orEmpty(),
                                        onFocused = {
                                            focusedRow = index
                                            vm.lastFocusedChannelId = channel.id
                                            val now = System.currentTimeMillis()
                                            if (cursorTime !in state.windowStart until state.windowEnd || (!pastMode && cursorTime < now)) cursorTime = now
                                        },
                                        onTouchCursor = { t -> pastMode = t < System.currentTimeMillis(); cursorTime = t },
                                        onShow = { p -> selected = channel to p },
                                        onStep = { delta ->
                                            val from = stepTarget ?: index
                                            val to = from + delta
                                            when {
                                                // ▲ from the first channel: round to the last (v2.1.6; a hold goes to the day chips).
                                                to < 0 -> { stepTo(state.channels.size - 1); true }
                                                // ▼ from the last channel: round to the first (v2.1.3).
                                                to >= state.channels.size -> { stepTo(0); true }
                                                else -> { stepTo(to); true }
                                            }
                                        },
                                        firstRow = index == 0,
                                        onHoldUp = { runCatching { dayTool.requestFocus() } },
                                        onLeft = onLeft,
                                        onHoldLeft = onHoldLeft,
                                        onRight = onRight,
                                        onOk = { p -> onOk(channel, p) },
                                        onMenu = { p -> openDetails(channel, p) },
                                        onScrollBy = { dx -> scrollPx = (scrollPx - dx).toInt().coerceIn(0, maxScroll()) },
                                        onSwipeRight = openSheet,
                                    )
                                }
                            }
                        }
                        // The now-line, 3 px with its glow, over every row.
                        val nowX = with(density) { (pxAt(liveNow) - scrollPx).toDp() }
                        if (liveNow in state.windowStart..state.windowEnd && nowX >= 0.mpx && nowX <= timelineW) {
                            Box(
                                Modifier
                                    .padding(start = gx + nowX - 1.5.mpx, bottom = 24.mpx)
                                    .width(3.mpx)
                                    .fillMaxHeight()
                                    .guideNowLine(stageAccent.accent),
                            )
                        }
                    }
                }
            }
        }

        // ◀ on what is on now: the categories (◀ again: the app's menu, with Settings). A TV slides them in from
        // the left (v2.6.0); a phone opens them as a sheet over the guide.
        val sheetSwipeMin = with(LocalDensity.current) { GUIDE_SWIPE_MIN.toPx() }
        val catSelected = railItems.indexOfFirst { it.key == (categoryKey ?: LiveKey.All) }.coerceAtLeast(0)
        // On a TV the panel stays composed, off screen while the guide is alone, so it slides in without
        // composing its list on the first frame and takes focus before it moves. Focus cannot wander into it
        // while it is away.
        if (tvHere || sheetOpen) {
            val railCounts by liveVm.railCounts.collectAsStateWithLifecycle()
            val (sheetEntries, _) = liveCategoryEntries(railItems, railCounts)
            // ◀ / ▶ / a swipe left out of the categories, each the one way it goes (see [GuideNav]).
            val toRail: () -> Unit = { if (tvHere) navGo(GuideNavEvent.LEFT) else { onOpenRail(); sheetOpen = false } }
            val toGrid: (GuideNavEvent) -> Unit = { ev -> if (tvHere) navGo(ev) else closeSheetToGrid() }
            Box(
                Modifier
                    .then(
                        if (tvHere) Modifier.offset {
                            // In from off screen at the left to past the capsule (or the open menu).
                            val away = (panelW + panelGap).toPx()
                            androidx.compose.ui.unit.IntOffset(((panelStart.toPx() + away) * catsShown.value - away).roundToInt(), 0)
                        } else Modifier.padding(start = 8.dp),
                    )
                    .padding(top = 24.mpx, bottom = 24.mpx)
                    .width(if (tvHere) panelW else screenW * 0.86f)
                    .fillMaxHeight()
                    .focusProperties {
                        // A key search that would land in the panel while it is away (◀ from the guide's
                        // search) goes to the menu instead, as it did before the panel was there.
                        onEnter = {
                            if (tvHere && !nav.categoriesOpen) {
                                cancelFocusChange()
                                scope.launch { onOpenRail() }
                            }
                        }
                    }
                    .focusGroup(),
            ) {
                LiveCategories(
                    searchQuery = vm.categoryQuery.collectAsStateWithLifecycle().value,
                    onSearchQueryChange = vm::setCategoryQuery,
                    onSearchEditingChange = { sheetEditing = it },
                    entries = sheetEntries,
                    selectedIndex = catSelected,
                    groupsHeading = groupsHeading,
                    sheet = true,
                    flat = tvHere,
                    followSelection = false,
                    listState = catListState,
                    // v2.6.0: the highlight switches the guide after a short rest; OK picks it and goes to its first row.
                    onRowFocus = { idx -> highlightCategory(idx) },
                    onSelect = { idx ->
                        settle.cancel()
                        railItems.getOrNull(idx)?.key?.let { applyCategory(it) }
                        focusedRow = 0
                        vm.lastFocusedChannelId = null
                        if (tvHere) navGo(GuideNavEvent.PICKED) else { toGuideRows(); sheetOpen = false }
                    },
                    onLongSelect = {},
                    onNavigateRight = { toGrid(GuideNavEvent.RIGHT) },
                    focusRequester = sheetFocus,
                    sheetTitle = stringResource(R.string.sport_guide_categories),
                    sheetHint = stringResource(R.string.common_nav_live_tv),
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { e ->
                            // ◀ out of the categories: the app's menu (Settings), unless the search is being typed in.
                            // ▶ goes back to the guide's row. Both on the press, before anything below sees it.
                            if (e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown && !sheetEditing) {
                                toRail()
                                true
                            } else if (e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown && !sheetEditing) {
                                toGrid(GuideNavEvent.RIGHT)
                                true
                            } else false
                        }
                        .pointerInput(tvHere) {
                            // Touch (v2.3.2): a swipe left closes the categories, as ▶ does.
                            var dragged = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragEnd = { if (dragged < -sheetSwipeMin) toGrid(GuideNavEvent.SWIPE_LEFT) },
                            ) { change, dx -> change.consume(); dragged += dx }
                        }
                        .onFocusChanged {
                            if (tvHere) {
                                // Focus that left the categories without one of the moves above (a tap on the menu)
                                // is noted after a moment, so a row recycled under focus does not count.
                                catFocused = it.hasFocus
                                if (it.hasFocus) { catLeaveJob?.cancel() } else if (nav == GuideNav.CATEGORIES) {
                                    catLeaveJob?.cancel()
                                    catLeaveJob = scope.launch {
                                        delay(250)
                                        if (!catFocused && !guideHasFocus && nav == GuideNav.CATEGORIES) nav = nav.on(GuideNavEvent.FOCUS_LEFT).nav
                                    }
                                }
                            } else {
                                // The phone's sheet closes when focus leaves it — not on the "unfocused" report
                                // every node gets when it attaches.
                                if (it.hasFocus) sheetHadFocus = true
                                else if (sheetHadFocus && sheetOpen) { settle.flush()?.let { k -> applyCategory(k) }; sheetOpen = false }
                            }
                        },
                )
            }
        }
    }

    menuFor?.let { (ch, p) ->
        val formatTime = tv.own.owntv.ui.format.rememberSystemTimeFormatter()
        val details by produceState<EpgProgrammeEntity?>(null, p.id) { value = p.id.takeIf { it > 0 }?.let { vm.programmeFull(it) } }
        GuideProgrammeMenu(
            title = p.title,
            subtitle = listOf(
                stringResource(R.string.content_live_time_range_plain, formatTime(p.startMs), formatTime(p.stopMs)),
                listOfNotNull(ch.number?.toString(), ProviderTags.parse(ch.name).name).joinToString(" "),
            ).joinToString(" · "),
            description = (details ?: p).description,
            // A programme that has started: its details open on Record.
            recordFirst = p.startMs <= System.currentTimeMillis(),
            actions = actionsFor(ch, p),
            leadMinutes = leadMinutes,
            onDismiss = { menuFor = null },
        )
    }
    askPlayerFor?.let { (ch, p) ->
        GuideCatchupChooser(
            onInternal = { vm.noteChannelTuned(ch); onPlayCatchup(ch, p) },
            onExternal = { vm.noteChannelTuned(ch); vm.playCatchupExternal(ch, p) },
            onDismiss = { askPlayerFor = null },
        )
    }
    if (orderOpen) {
        val showEmpty by vm.showEmpty.collectAsStateWithLifecycle()
        GuideOrderMenu(
            current = sortGuide, onPick = vm::setGuideSort,
            showEmpty = showEmpty, onShowEmpty = vm::setShowEmpty,
            showPreview = guidePreview, onShowPreview = vm::setGuidePreview,
            onDismiss = { orderOpen = false },
        )
    }
    channelMenuFor?.let { ch ->
        GuideChannelMenu(
            channel = ch,
            isFavorite = ch.id in vm.favoriteChannelIds.collectAsStateWithLifecycle().value,
            epgOffsetValue = vm.currentEpgShift(ch)?.let { liveEpgShiftLabel(it) } ?: stringResource(R.string.content_epg_offset_global_short),
            onWatch = { watch(ch) },
            onFavorite = { vm.toggleFavoriteChannel(ch) },
            onAutoMatch = { vm.autoMatchOne(ch) },
            onPickEpg = { matchingChannel = ch },
            onEpgOffset = { offsetChannel = ch },
            onDismiss = { channelMenuFor = null },
        )
    }
    if (dayOpen) {
        val days = remember(state.windowStart, state.windowEnd) {
            generateSequence(localMidnight(state.windowStart)) { localMidnight(it + 36L * 60 * 60 * 1000) }.takeWhile { it < state.windowEnd }.toList()
        }
        GuideDayMenu(
            days = days,
            currentDay = localMidnight(timeAt(scrollPx + timelinePx / 4f)),
            onPick = { day ->
                val now = System.currentTimeMillis()
                showTime(day + (now - localMidnight(now)))
            },
            onDismiss = { dayOpen = false },
        )
    }
    offsetChannel?.let { channel ->
        tv.own.owntv.features.live.EpgOffsetDialog(
            channelName = channel.name,
            currentMinutes = vm.currentEpgShift(channel),
            globalMinutes = vm.globalEpgShift(),
            onSet = { vm.setEpgShift(channel, it) },
            onDismiss = { offsetChannel = null },
        )
    }
    matchingChannel?.let { channel ->
        tv.own.owntv.features.live.EpgMatchDialog(
            channelName = channel.name,
            currentMatch = vm.currentEpgMatch(channel),
            loadChannels = { vm.availableEpgChannels(channel.name, it) },
            onPick = { vm.setEpgMatch(channel, it); matchingChannel = null },
            onClear = { vm.setEpgMatch(channel, null); matchingChannel = null },
            onDismiss = { matchingChannel = null },
        )
    }
    if (review.isNotEmpty()) {
        val includeLogos by vm.includeGuideLogos.collectAsStateWithLifecycle()
        val settingsVm: tv.own.owntv.features.settings.SettingsViewModel = koinViewModel()
        val chNavEnabled by settingsVm.chNavEnabled.collectAsStateWithLifecycle()
        val chNavUpSkip by settingsVm.chNavUpSkip.collectAsStateWithLifecycle()
        val chNavDownSkip by settingsVm.chNavDownSkip.collectAsStateWithLifecycle()
        GuideReviewPopup(
            suggestions = review,
            matchedCount = (matchSummary as? EpgMatchSummary.AutoMatched)?.applied,
            includeLogos = includeLogos,
            onIncludeLogos = vm::setIncludeGuideLogos,
            onAccept = vm::acceptSuggestion,
            onSkip = vm::dismissSuggestion,
            onAcceptAll = vm::acceptAllSuggestions,
            onSkipAll = vm::clearReview,
            onDone = vm::clearReview,
            chNavEnabled = chNavEnabled,
            chNavUpSkip = chNavUpSkip,
            chNavDownSkip = chNavDownSkip,
        )
    }
}

/** How far a finger goes sideways before it is a swipe (v2.3.2), not a nudge. */
private val GUIDE_SWIPE_MIN = 64.dp

/**
 * One guide row: the channel label, then the programmes as one focus target with a cursor. ▶ moves the
 * cursor forward programme by programme; ◀ ([onLeft]) back, as far as now; holding ◀ ([onHoldLeft]) jumps
 * to now or into what has been on. ▲ ▼ step rows ([onStep]), OK acts on the programme ([onOk]), holding
 * OK or ☰ opens its details ([onMenu]). Programmes are read when the row comes into view.
 */
@Composable
private fun GuideRow(
    vm: EpgViewModel,
    channel: ChannelEntity,
    name: String,
    dot: Color?,
    labelWidth: Dp,
    numberWidth: Dp,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    scrollPx: Int,
    focusRequester: FocusRequester,
    selectedRow: Boolean,
    cursorTime: Long,
    recordingStarts: Set<Long>,
    reminderStarts: Set<Long>,
    onFocused: () -> Unit,
    onTouchCursor: (Long) -> Unit,
    onShow: (EpgProgrammeEntity?) -> Unit,
    onStep: (Int) -> Boolean,
    /** v2.1.6: this is the first channel, where a press of ▲ goes round to the last and a hold goes up. */
    firstRow: Boolean,
    /** ▲ held on the first channel: up to the day chips. */
    onHoldUp: () -> Unit,
    onLeft: (List<EpgProgrammeEntity>) -> Unit,
    onHoldLeft: (List<EpgProgrammeEntity>) -> Unit,
    onRight: (List<EpgProgrammeEntity>) -> Unit,
    onOk: (EpgProgrammeEntity?) -> Unit,
    onMenu: (EpgProgrammeEntity?) -> Unit,
    /** Touch: a sideways drag on the programmes moves the shared timeline by this many pixels. */
    onScrollBy: (Float) -> Unit,
    /** Touch (v2.3.2): a swipe right on the channel name, or past the start of the timeline — ◀'s categories. */
    onSwipeRight: () -> Unit,
) {
    // Cache peek first, so a row scrolled back into view draws at once; a miss reads this one channel.
    val cacheRevision by vm.cacheRevision.collectAsStateWithLifecycle()
    val programmes by produceState(initialValue = vm.cachedProgrammes(channel), channel.id, windowStart, cacheRevision) {
        value = vm.cachedProgrammes(channel) ?: vm.programmesFor(channel)
        // Still empty: ask again a little later, twice, before the row settles on "No program".
        repeat(2) {
            if (value?.isNotEmpty() == true) return@produceState
            delay(3_000)
            value = vm.programmesFor(channel)
        }
    }
    var focused by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    var leftDown by remember { mutableStateOf(false) }
    var leftHeld by remember { mutableStateOf(false) }
    var upDown by remember { mutableStateOf(false) }
    var upHeld by remember { mutableStateOf(false) }
    LaunchedEffect(focused, cursorTime, programmes) { if (focused) onShow(programmeAt(programmes, cursorTime)) }
    // Touch. On the programmes: the first tap selects the programme under the finger (the block on
    // top shows it, as the cursor would), a tap on the one already selected acts on it as OK does, a long
    // press opens its details, and a sideways drag scrolls the timeline. On the channel name: a tap is OK
    // on what is on now, a long press opens the channel menu. Up and down stay the list's own scroll.
    val pxPerMin = with(LocalDensity.current) { GuideGridDefaults.PxPerMin.toPx() }
    val timeAtX: (Float) -> Long = { x -> windowStart + ((x + scrollPx) / pxPerMin * 60_000f).toLong() }
    val onStripTap by rememberUpdatedState { x: Float ->
        val progs = programmes.orEmpty()
        val time = timeAtX(x)
        val tapped = programmeAt(progs, time)
        if (focused && programmeAt(progs, cursorTime)?.id == tapped?.id) {
            onOk(tapped)
        } else {
            runCatching { focusRequester.requestFocus() }
            onTouchCursor(time)
            onShow(tapped)
        }
    }
    val onStripLongPress by rememberUpdatedState { x: Float -> onMenu(programmeAt(programmes.orEmpty(), timeAtX(x))) }
    val onStripDrag by rememberUpdatedState(onScrollBy)
    val onLabelTap by rememberUpdatedState { onOk(programmeAt(programmes.orEmpty(), now)) }
    val onLabelLongPress by rememberUpdatedState { onMenu(null) }
    val swipeRight by rememberUpdatedState(onSwipeRight)
    val currentScrollPx by rememberUpdatedState(scrollPx)
    val swipeMin = with(LocalDensity.current) { GUIDE_SWIPE_MIN.toPx() }
    val divider = GuideColors.Border
    val slotPx = pxPerMin * GuideGridDefaults.SlotMin
    Row(
        Modifier.fillMaxWidth().height(GuideGridDefaults.RowHeight)
            // DPTV Recorder: a 1 px line under each row.
            .drawBehind { drawRect(divider, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GuideChannelLabel(
            channel, name, dot, selected = selectedRow, numberWidth = numberWidth,
            modifier = Modifier.width(labelWidth).pointerInput(Unit) {
                detectTapGestures(onTap = { onLabelTap() }, onLongPress = { onLabelLongPress() })
            }.pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > swipeMin) swipeRight() },
                ) { change, dx -> change.consume(); dragged += dx }
            },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                // Recorder's faint line at each half hour, behind the cells.
                .drawBehind {
                    val first = ((windowStart + GuideGridDefaults.SlotMin * 60_000L - 1) / (GuideGridDefaults.SlotMin * 60_000L)) * GuideGridDefaults.SlotMin * 60_000L
                    var lx = (first - windowStart) / 60_000f * pxPerMin - scrollPx
                    while (lx < size.width) {
                        if (lx >= 0f) drawRect(divider.copy(alpha = divider.alpha * 0.6f), topLeft = Offset(lx, 0f), size = Size(1.dp.toPx(), size.height))
                        lx += slotPx
                    }
                }
                .padding(vertical = 7.mpx)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset -> onStripTap(offset.x) },
                        onLongPress = { offset -> onStripLongPress(offset.x) },
                    )
                }
                .pointerInput(Unit) {
                    // A drag scrolls the timeline; one that starts with the timeline at its start and goes on
                    // to the right is a swipe for the categories, as ◀ there is.
                    var fromStart = false
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { fromStart = currentScrollPx == 0; dragged = 0f },
                        onDragEnd = { if (fromStart && dragged > swipeMin) swipeRight() },
                    ) { change, dx ->
                        change.consume()
                        dragged += dx
                        onStripDrag(dx)
                    }
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused() else { okDown = false; leftDown = false; leftHeld = false; upDown = false; upHeld = false }
                }
                .onPreviewKeyEvent { e ->
                    val progs = programmes.orEmpty()
                    val down = e.type == KeyEventType.KeyDown
                    val repeat = e.nativeKeyEvent.repeatCount
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (down && repeat == 0) okDown = true
                            else if (down && okDown) { okDown = false; onMenu(programmeAt(progs, cursorTime)) }
                            else if (!down && okDown) { okDown = false; onOk(programmeAt(progs, cursorTime)) }
                            true
                        }
                        Key.Menu -> { if (down) onMenu(programmeAt(progs, cursorTime)); true }
                        // Physical by design: the timeline runs left to right in every language. A press acts on
                        // release, so a hold can mean something else: back to now, or into what has been on.
                        Key.DirectionLeft -> {
                            if (down && repeat == 0) { leftDown = true; leftHeld = false }
                            else if (down && leftDown && !leftHeld) { leftHeld = true; onHoldLeft(progs) }
                            else if (!down && leftDown) { leftDown = false; if (!leftHeld) onLeft(progs) }
                            true
                        }
                        Key.DirectionRight -> { if (down) onRight(progs); true }
                        // On the first channel ▲ acts on release, so a hold can go up to the day chips instead
                        // of round to the last channel. A hold that scrolled up to here just stops.
                        Key.DirectionUp -> when {
                            !firstRow -> if (down) onStep(-1) else true
                            down && repeat == 0 -> { upDown = true; upHeld = false; true }
                            down && upDown && !upHeld -> { upHeld = true; onHoldUp(); true }
                            !down && upDown -> { upDown = false; if (!upHeld) onStep(-1); true }
                            else -> true
                        }
                        Key.DirectionDown -> if (down) onStep(+1) else true
                        else -> false
                    }
                }
                .focusable(),
        ) {
            programmes?.let { progs ->
                val catchupIds = remember(progs, channel, now) { progs.filter { vm.canCatchup(channel, it, now) }.mapTo(HashSet()) { it.id } }
                ProgrammeStripCanvas(
                    programmes = progs,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    focusTime = if (focused) cursorTime else null,
                    catchupIds = catchupIds,
                    recordingStarts = recordingStarts,
                    reminderStarts = reminderStarts,
                    scrollPx = scrollPx,
                )
            }
        }
    }
}

/** An empty guide: what is missing, and — with no EPG added — the way to add one. */
@Composable
private fun GuideEmpty(title: String, body: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(bottom = 80.mpx), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = stageText(24, 700), color = StageColors.Text, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.mpx))
            if (body != null) {
                Text(body, style = stageText(19, 400), color = StageColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.mpx).padding(top = 8.mpx))
            }
            if (action != null) {
                Spacer(Modifier.height(24.mpx))
                StageButton(action, onClick = onAction, icon = OwnTVIcon.ADD, height = 56.mpx, textSize = 19)
            }
        }
    }
}
