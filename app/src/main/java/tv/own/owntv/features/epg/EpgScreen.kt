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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
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
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.live.LiveCategories
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.live.ProviderTags
import tv.own.owntv.features.live.edgeScrollSpec
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
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

@Composable
private fun epgMessageText(message: EpgMessage): String = when (message) {
    EpgMessage.CreateProfile -> stringResource(R.string.content_epg_create_profile)
    EpgMessage.AddPlaylist -> stringResource(R.string.content_epg_add_playlist)
    is EpgMessage.NoChannelsForQuery -> stringResource(R.string.content_epg_no_channels_query, message.query)
    EpgMessage.MismatchedIds -> stringResource(R.string.content_epg_mismatched_ids)
}

@Composable
private fun epgMatchSummaryText(summary: EpgMatchSummary): String = when (summary) {
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
 * is on now, and ◀ on what is on now opens the categories (◀ again: the menu with Settings). Holding ◀
 * jumps back to now, or — already at now — steps into the programmes that have been on.
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

    // The video plays the channel the user picked with OK — moving through the grid no longer retunes it.
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
    fun pxAt(t: Long): Float = (t - state.windowStart) / 60_000f * ppm
    fun maxScroll(): Int = (pxAt(state.windowEnd) - timelinePx).toInt().coerceAtLeast(0)
    fun timeAt(px: Float): Long = state.windowStart + (px / ppm * 60_000f).toLong()
    // A time is shown a quarter of the way across: what is on now keeps a little of what was before it in
    // view, and most of the width goes to what is coming.
    fun showTime(t: Long) {
        scrollPx = (pxAt(t) - timelinePx / 4f).toInt().coerceIn(0, maxScroll())
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

    fun backToNow() {
        val now = System.currentTimeMillis()
        pastMode = false
        cursorTime = now
        showTime(now)
    }
    // Back while looking at what has been on: back to now first.
    BackHandler(enabled = pastMode && gridHasFocus) { backToNow() }

    // ▲ ▼ by row number (playbook 2): scroll just enough, wait a frame, focus. A held key keeps counting
    // from the target, and the newest press cancels the step still in flight.
    var stepTarget by remember { mutableStateOf<Int?>(null) }
    var stepJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun stepTo(i: Int) {
        stepTarget = i
        stepJob?.cancel()
        stepJob = scope.launch {
            val info = rowListState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.index == i }
            val step = ((info.visibleItemsInfo.firstOrNull()?.size ?: 0) + info.mainAxisItemSpacing).toFloat()
            val delta = when {
                row == null -> if (i > (info.visibleItemsInfo.lastOrNull()?.index ?: 0)) step else -step
                row.offset - step < info.viewportStartOffset -> row.offset - step - info.viewportStartOffset
                row.offset + row.size + step > info.viewportEndOffset -> row.offset + row.size + step - info.viewportEndOffset
                else -> 0f
            }
            if (delta != 0f) runCatching { rowListState.scrollBy(delta) }
            if (rowListState.layoutInfo.visibleItemsInfo.none { it.index == i }) runCatching { rowListState.scrollToItem(i) }
            withFrameNanos { }
            runCatching { rowFocus(i).requestFocus() }
            stepTarget = null
        }
    }
    // Back to the grid at the row focus was on (after a menu, the sheet, a jump).
    fun focusGrid(row: Int = focusedRow) {
        val i = row.coerceIn(0, (state.channels.size - 1).coerceAtLeast(0))
        scope.launch {
            val visible = rowListState.layoutInfo.visibleItemsInfo.any { it.index == i }
            if (!visible) runCatching { rowListState.scrollToItem(i) }
            withFrameNanos { }
            if (!runCatching { rowFocus(i).requestFocus() }.getOrDefault(false)) {
                withFrameNanos { }
                runCatching { rowFocus(i).requestFocus() }
            }
        }
    }
    // The cursor stays in view: scroll only when it reaches an edge, by one slot of room.
    LaunchedEffect(cursorTime, timelinePx) {
        if (!gridHasFocus || timelinePx <= 0 || cursorTime <= 0L) return@LaunchedEffect
        val x = pxAt(cursorTime)
        val margin = GuideGridDefaults.SlotMin * ppm
        when {
            x < scrollPx + margin -> scrollPx = (x - margin).toInt().coerceIn(0, maxScroll())
            x > scrollPx + timelinePx - margin -> scrollPx = (x - timelinePx + margin).toInt().coerceIn(0, maxScroll())
        }
    }

    // Back from the player (the shell notes the channel that was playing, a zap included) or from Settings:
    // the row the guide was on.
    LaunchedEffect(restoreFocus, state.loading, state.channels.size) {
        if (!restoreFocus || state.loading || state.channels.isEmpty()) return@LaunchedEffect
        val idx = listOfNotNull(vm.lastFocusedChannelId, previewChannel?.id, vm.lastTunedChannelId)
            .firstNotNullOfOrNull { id -> state.channels.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
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
        runCatching { rowListState.scrollToItem((idx - 2).coerceAtLeast(0)) }
    }
    // A reload (a playlist or guide refresh) can add, drop or reorder rows: the cursor follows its channel,
    // not its old row number, and focus goes back to it when the refresh took it away from the grid.
    var gridLostAt by remember { mutableStateOf(0L) }
    LaunchedEffect(gridHasFocus) { if (!gridHasFocus) gridLostAt = android.os.SystemClock.uptimeMillis() }
    LaunchedEffect(state.channels) {
        if (state.loading || state.channels.isEmpty()) return@LaunchedEffect
        val id = vm.lastFocusedChannelId ?: return@LaunchedEffect
        val idx = state.channels.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@LaunchedEffect
        val refocus = gridHasFocus || android.os.SystemClock.uptimeMillis() - gridLostAt < 1_500L
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
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetHadFocus by remember { mutableStateOf(false) }
    var sheetEditing by remember { mutableStateOf(false) }
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
    val watch: (ChannelEntity) -> Unit = { ch -> vm.noteChannelTuned(ch); onPlayChannel(ch, channelsNow) }
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
        when {
            p != null && p.startMs > now -> openDetails(ch, p)
            p != null && p.stopMs <= now -> if (vm.canCatchup(ch, p, now)) playFromStart(ch, p) else openDetails(ch, p)
            effectivePreview && !inPreview -> liveVm.onChannelFocused(ch)
            else -> watch(ch)
        }
    }

    // ◀ / hold ◀ / ▶ in a row. [progs] is that row's guide.
    val openSheet: () -> Unit = { returnTo = null; sheetOpen = true }
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

    val dayTool = remember { FocusRequester() }
    val orderTool = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val sheetFocus = remember { FocusRequester() }
    val catListState = rememberLazyListState()

    // ▶ from the rail (or opening Live TV from it): the categories when they are out, else the channel row.
    // Rows that are not there yet (still loading, not laid out) are focused once they are — never "Today".
    var pendingEntry by remember { mutableStateOf(false) }
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke {
            when {
                sheetOpen -> runCatching { sheetFocus.requestFocus() }.getOrDefault(false)
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
    // ◀ from the categories went to the rail: they stay out beside it until ▶ comes back or focus goes elsewhere.
    var sheetToRail by remember { mutableStateOf(false) }

    BackHandler(enabled = sheetOpen) { sheetOpen = false; focusGrid() }
    LaunchedEffect(sheetOpen) {
        if (sheetOpen) { withFrameNanos { }; runCatching { sheetFocus.requestFocus() } } else sheetHadFocus = false
    }
    LaunchedEffect(searchOpen) { if (searchOpen) { withFrameNanos { }; runCatching { searchFocus.requestFocus() } } }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Entering from the rail lands in the grid, on the row focus was on.
            .focusProperties {
                onEnter = {
                    val ok = state.channels.isNotEmpty() &&
                        runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() }.getOrDefault(false)
                    if (!ok) pendingEntry = true
                }
            }
            .focusGroup(),
    ) {
        val screenW = maxWidth
        // Phones (and anything this narrow): the video full width on top, the guide below it.
        val compact = maxWidth < 600.dp
        fun fx(px: Int) = screenW * (px / 1920f)
        // TV: the categories come out at the left and the guide moves over for them (and for the open rail
        // after them); a phone keeps them as a sheet over the guide.
        val pushPanel = !compact
        LaunchedEffect(sheetOpen, pushPanel) { onPanelOpen(sheetOpen && pushPanel) }
        val panelW = fx(450)
        val panelStart by androidx.compose.animation.core.animateDpAsState(
            if (pushPanel) panelInset else fx(24), androidx.compose.animation.core.tween(160),
        )
        val guideShift by androidx.compose.animation.core.animateDpAsState(
            if (pushPanel && sheetOpen) panelInset + panelW else 0.dp, androidx.compose.animation.core.tween(160),
        )
        val rowStart: Dp = if (compact) 4.dp else fx(48)
        val rowEnd: Dp = if (compact) 4.dp else fx(64)
        val labelW: Dp = when {
            compact -> 136.dp
            else -> guideWidthShares?.let { (screenW - fx(112)) * (it.channels / 100f) } ?: (screenW * GuideGridDefaults.ChannelColShare)
        }
        val timelineW = screenW - rowStart - labelW - rowEnd
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
        // The shell's surface draws over everything, so it steps back while the categories are open over
        // the video. Nothing is reported until the box has been measured: an early "none" right after Back
        // would drop the very surface this is meant to keep.
        var videoRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val videoRadius = if (compact) 0.dp else 20.mpx
        LaunchedEffect(videoRect, previewWanted, sheetOpen, sharedSurface, pushPanel) {
            if (!sharedSurface) return@LaunchedEffect
            when {
                !previewWanted || (sheetOpen && !pushPanel) -> onVideoBounds?.invoke(null, 0.dp)
                videoRect != null -> onVideoBounds?.invoke(videoRect, videoRadius)
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
                mod.onGloballyPositioned { videoRect = it.boundsInRoot() }
            } else mod,
        )

        @Composable
        fun TopInfo(mod: Modifier) {
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
                recording = shown != null && recordingRows.any {
                    it.channelId == topChannel.id && it.programmeStartMs == shown.startMs && it.status == RecordingStatus.RECORDING
                },
            )
        }

        Column(Modifier.fillMaxSize().offset { androidx.compose.ui.unit.IntOffset(guideShift.roundToPx(), 0) }) {
            if (compact) {
                TopVideo(Modifier.fillMaxWidth())
                TopInfo(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp))
            } else {
                // Top (32 → 329): the video (528 wide), and the programme beside it.
                Row(Modifier.padding(start = fx(64), end = fx(64), top = 32.mpx).fillMaxWidth().height(297.mpx)) {
                    TopVideo(Modifier.width(528.mpx))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        TopInfo(Modifier.padding(start = 40.mpx).widthIn(max = 1100.mpx))
                        matchSummary?.takeIf { review.isEmpty() }?.let {
                            Text(
                                epgMatchSummaryText(it), style = stageText(16, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomEnd).widthIn(max = 620.mpx), textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }

            // The header line: the day and the guide's tools over the channel column, the ruler over the programmes.
            val viewTime = timeAt(scrollPx + timelinePx / 4f)
            val viewDay = localMidnight(viewTime)
            Row(
                Modifier.padding(top = if (compact) 6.dp else 18.mpx).fillMaxWidth().height(48.mpx),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.padding(start = rowStart).width(labelW),
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
                            text = null, value = guideDayLabel(viewDay), valueAccent = true, trailingIcon = OwnTVIcon.CHEVRON_DOWN,
                            onClick = { returnTo = { runCatching { dayTool.requestFocus() } }; dayOpen = true },
                            modifier = Modifier.focusRequester(dayTool).weight(1f, fill = false),
                        )
                        StageTool(
                            text = null, icon = OwnTVIcon.SORT,
                            onClick = { returnTo = { runCatching { orderTool.requestFocus() } }; orderOpen = true },
                            modifier = Modifier.focusRequester(orderTool),
                        )
                        StageTool(text = null, icon = OwnTVIcon.SEARCH, onClick = { searchOpen = true })
                        if (matching) {
                            Box(Modifier.padding(horizontal = 12.mpx)) { OwnTVSpinner(sizeDp = 18) }
                        } else {
                            StageTool(text = null, icon = OwnTVIcon.SPARKLE, onClick = vm::autoMatchEpg)
                        }
                    }
                }
                if (state.channels.isNotEmpty()) {
                    GuideRuler(
                        windowStart = state.windowStart, windowEnd = state.windowEnd, now = liveNow, scrollPx = scrollPx,
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
                        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides edgeScrollSpec) {
                            LazyColumn(
                                state = rowListState,
                                modifier = Modifier
                                    .padding(start = rowStart, end = rowEnd)
                                    .fillMaxSize()
                                    .onFocusChanged { gridHasFocus = it.hasFocus }
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
                                        scrollPx = scrollPx,
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
                                                to < 0 -> false // ▲ from the first row: up to the header
                                                to >= state.channels.size -> true
                                                else -> { stepTo(to); true }
                                            }
                                        },
                                        onLeft = onLeft,
                                        onHoldLeft = onHoldLeft,
                                        onRight = onRight,
                                        onOk = { p -> onOk(channel, p) },
                                        onMenu = { p -> openDetails(channel, p) },
                                        onScrollBy = { dx -> scrollPx = (scrollPx - dx).toInt().coerceIn(0, maxScroll()) },
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

        // ◀ on what is on now: the categories, beside the app's menu (◀ again opens it, with Settings).
        if (sheetOpen) {
            val railCounts by liveVm.railCounts.collectAsStateWithLifecycle()
            val (sheetEntries, _) = liveCategoryEntries(railItems, railCounts)
            LiveCategories(
                searchQuery = vm.categoryQuery.collectAsStateWithLifecycle().value,
                onSearchQueryChange = vm::setCategoryQuery,
                onSearchEditingChange = { sheetEditing = it },
                entries = sheetEntries,
                selectedIndex = railItems.indexOfFirst { it.key == (categoryKey ?: LiveKey.All) }.coerceAtLeast(0),
                groupsHeading = groupsHeading,
                sheet = true,
                listState = catListState,
                onSelect = { idx ->
                    railItems.getOrNull(idx)?.key?.let { key -> if (liveMode) liveVm.select(key) else vm.setCategoryFilter(key) }
                    sheetOpen = false
                    focusedRow = 0
                    scope.launch { runCatching { rowListState.scrollToItem(0) } }
                    focusGrid(0)
                },
                onLongSelect = {},
                onNavigateRight = { sheetOpen = false; focusGrid() },
                focusRequester = sheetFocus,
                sheetTitle = stringResource(R.string.sport_guide_categories),
                sheetHint = stringResource(R.string.common_nav_live_tv),
                modifier = Modifier
                    .padding(start = if (compact) 8.dp else panelStart, top = 24.mpx, bottom = 24.mpx)
                    .width(if (compact) screenW * 0.86f else panelW)
                    .fillMaxHeight()
                    .onPreviewKeyEvent { e ->
                        // ◀ out of the categories: the app's menu (Settings), unless the search is being typed in.
                        // On a TV they stay out beside it; ▶ from the menu comes back to them.
                        if (e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown && !sheetEditing) {
                            if (pushPanel) sheetToRail = true else sheetOpen = false
                            onOpenRail()
                            true
                        } else false
                    }
                    .onFocusChanged {
                        // Closes when focus leaves it — not on the "unfocused" report every node gets when it
                        // attaches, and not for the rail beside it.
                        if (it.hasFocus) { sheetHadFocus = true; sheetToRail = false }
                        else if (sheetHadFocus && sheetOpen && !sheetToRail) sheetOpen = false
                    },
            )
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
    onLeft: (List<EpgProgrammeEntity>) -> Unit,
    onHoldLeft: (List<EpgProgrammeEntity>) -> Unit,
    onRight: (List<EpgProgrammeEntity>) -> Unit,
    onOk: (EpgProgrammeEntity?) -> Unit,
    onMenu: (EpgProgrammeEntity?) -> Unit,
    /** Touch: a sideways drag on the programmes moves the shared timeline by this many pixels. */
    onScrollBy: (Float) -> Unit,
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
    Row(Modifier.fillMaxWidth().height(GuideGridDefaults.RowHeight), verticalAlignment = Alignment.CenterVertically) {
        GuideChannelLabel(
            channel, name, dot, selected = selectedRow, numberWidth = numberWidth,
            modifier = Modifier.width(labelWidth).pointerInput(Unit) {
                detectTapGestures(onTap = { onLabelTap() }, onLongPress = { onLabelLongPress() })
            },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset -> onStripTap(offset.x) },
                        onLongPress = { offset -> onStripLongPress(offset.x) },
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dx -> change.consume(); onStripDrag(dx) }
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused() else { okDown = false; leftDown = false; leftHeld = false }
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
                        Key.DirectionUp -> if (down) onStep(-1) else true
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
