package tv.own.owntv.features.recordings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.recording.RemoteRecordings
import kotlinx.coroutines.launch
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.rememberBestDateFormatter
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.stage.stageFocusLook
import tv.own.owntv.ui.stage.stageStillBack
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** How the DVR list is ordered: newest first (the default), or by title. */
internal enum class DvrSort { DATE, TITLE }

/** (v2.9.9) What DVR lists: what has been recorded, or what is set to record. */
internal enum class DvrView { RECORDED, SCHEDULED }

/** The finished recordings in [sort] order. */
internal fun dvrRows(recordings: List<RecordingEntity>, sort: DvrSort): List<RecordingEntity> {
    // v2.1.2: what is recording now is listed too (first), and plays as it records.
    val done = recordings.filter { it.status == RecordingStatus.COMPLETED || (it.status == RecordingStatus.RECORDING && it.filePath != null) }
    return when (sort) {
        DvrSort.DATE -> done.sortedWith(
            compareByDescending<RecordingEntity> { it.status == RecordingStatus.RECORDING }.thenByDescending { it.programmeStartMs },
        )
        DvrSort.TITLE -> done.sortedWith(compareBy<RecordingEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenByDescending { it.programmeStartMs })
    }
}

/**
 * DVR (v2.1; two panels since v2.10.0). On the left, the list: what has been recorded (newest first or
 * A–Z) or, with Show › Scheduled, what is set to record. On the right, the highlighted line: its still,
 * title, when and where, the guide's full description, and its actions — Play (or Resume from / Start
 * over) and Delete for a recording; Cancel recording for one set for later or a Sport game waiting for
 * its channel; Play and Stop recording for one under way. OK or ▶ on a line moves into its actions; ◀
 * or Back comes back to the line. Delete asks first, and a server that will not delete says so.
 */
@Composable
fun DvrScreen(
    onFullscreen: () -> Unit,
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    /** The content's left edge: beside the resting capsule (150), or 64 inside a docked rail's reserve. */
    contentStart: Dp = 150.mpx,
    modifier: Modifier = Modifier,
) {
    val vm: RecordingsViewModel = koinViewModel()
    // Opening DVR asks the recording server at once, not at the next 30-second pass.
    LaunchedEffect(Unit) { vm.syncNow() }
    val recordings by vm.rows.collectAsStateWithLifecycle()
    val externalOn by vm.externalPlayerOn.collectAsStateWithLifecycle()
    val lastPlayedId by vm.lastPlayedId.collectAsStateWithLifecycle()
    val sportPlans by vm.sportPlans.collectAsStateWithLifecycle()
    val progressRevision by vm.progressRevision.collectAsStateWithLifecycle()
    val deleteFailed by vm.deleteFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var sortName by rememberSaveable { mutableStateOf(DvrSort.DATE.name) }
    val sort = DvrSort.valueOf(sortName)
    var viewName by rememberSaveable { mutableStateOf(DvrView.RECORDED.name) }
    val view = DvrView.valueOf(viewName)
    val rows = remember(recordings, sort) { dvrRows(recordings, sort) }
    val items: List<DvrPlanned> = remember(view, rows, recordings, sportPlans) {
        if (view == DvrView.RECORDED) rows.map { DvrPlanned.Recording(it) } else dvrPlanned(recordings, sportPlans)
    }
    val recordingNow = recordings.count { it.status == RecordingStatus.RECORDING }
    val scheduled = recordings.count { it.status == RecordingStatus.SCHEDULED }

    // The highlighted line: the one the right panel shows. It stays put while focus is in the actions.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = items.firstOrNull { it.key == selectedKey } ?: items.firstOrNull()
    var inActions by remember { mutableStateOf(false) }

    val rowFocus = remember { HashMap<String, FocusRequester>() }
    fun focusOf(key: String) = rowFocus.getOrPut(key) { FocusRequester() }
    val viewFocus = remember { FocusRequester() }
    val sortFocus = remember { FocusRequester() }
    val firstAction = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Delete asks first, with Cancel focused: a recording is gone for good once the server deletes it.
    var confirmDeleteFor by remember { mutableStateOf<RecordingEntity?>(null) }

    suspend fun focusRow(key: String?) {
        if (key == null || items.none { it.key == key }) { runCatching { viewFocus.requestFocus() }; return }
        selectedKey = key
        if (runCatching { focusOf(key).requestFocus() }.getOrDefault(false)) return
        runCatching { listState.scrollToItem(items.indexOfFirst { it.key == key }.coerceAtLeast(0)) }
        withFrameNanos { }
        runCatching { focusOf(key).requestFocus() }
    }
    val openActions: (DvrPlanned) -> Unit = { item ->
        selectedKey = item.key
        scope.launch { withFrameNanos { }; runCatching { firstAction.requestFocus() } }
    }
    val backToRow: () -> Unit = { scope.launch { focusRow(selected?.key) } }
    // After a line goes (deleted, cancelled), focus moves on to the next one, or the one before.
    fun neighbourOf(key: String): String? {
        val i = items.indexOfFirst { it.key == key }
        return (items.getOrNull(i + 1) ?: items.getOrNull(i - 1))?.key
    }
    val start: (RecordingEntity, Boolean) -> Unit = { r, resume ->
        vm.play(r, resume = resume)
        if (!externalOn) onFullscreen()
    }

    // The rail's ▶ lands on the highlighted line (the first one), or on the Recorded / Scheduled switch.
    val entry: () -> Boolean = {
        val key = selected?.key
        (key != null && runCatching { focusOf(key).requestFocus() }.getOrDefault(false)) ||
            runCatching { viewFocus.requestFocus() }.getOrDefault(false)
    }
    DisposableEffect(onEntryHook, selected?.key, view) {
        onEntryHook?.invoke(entry)
        onDispose { onEntryHook?.invoke(null) }
    }
    // Back from the player: on the recording that was played.
    LaunchedEffect(restoreFocus, items.size) {
        if (!restoreFocus || items.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val key = lastPlayedId?.let { DvrPlanned.keyOf(it) }?.takeIf { k -> items.any { it.key == k } }
        if (key != null) focusRow(key) else entry()
        onRestored()
    }
    androidx.activity.compose.BackHandler(enabled = inActions) { backToRow() }

    Column(modifier.fillMaxSize().padding(start = contentStart, end = 64.mpx, top = 44.mpx)) {
        // The switches sit after the counts, clear of the clock and pills in the top-right corner.
        Row(Modifier.padding(end = 640.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.mpx)) {
            Text(stringResource(R.string.common_nav_dvr), style = stageText(46, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.sport_dvr_count, rows.size, rows.size),
                    recordingNow.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.sport_dvr_recording_now, it, it) },
                    scheduled.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.sport_dvr_scheduled, it, it) },
                ).joinToString(stringResource(R.string.content_epg_bits_separator)),
                style = stageText(19, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            StageTool(
                text = stringResource(R.string.sport_dvr_show), icon = OwnTVIcon.LIST,
                value = stringResource(if (view == DvrView.RECORDED) R.string.sport_dvr_view_recorded else R.string.sport_dvr_view_scheduled),
                valueAccent = true,
                onClick = { viewName = (if (view == DvrView.RECORDED) DvrView.SCHEDULED else DvrView.RECORDED).name; selectedKey = null },
                modifier = Modifier.focusRequester(viewFocus),
            )
            if (view == DvrView.RECORDED) {
                StageTool(
                    text = stringResource(R.string.sport_dvr_sort), icon = OwnTVIcon.SORT,
                    value = stringResource(if (sort == DvrSort.DATE) R.string.sport_dvr_sort_date else R.string.sport_dvr_sort_az),
                    valueAccent = true,
                    onClick = { sortName = (if (sort == DvrSort.DATE) DvrSort.TITLE else DvrSort.DATE).name },
                    modifier = Modifier.focusRequester(sortFocus),
                )
            }
        }
        if (items.isNotEmpty()) {
            Text(
                stringResource(if (view == DvrView.RECORDED) R.string.sport_dvr_hint else R.string.sport_dvr_scheduled_hint),
                style = stageText(17, 600), color = StageColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.mpx),
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 120.mpx), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (view == DvrView.RECORDED) R.string.sport_dvr_empty else R.string.sport_dvr_scheduled_empty),
                    style = stageText(22, 600), color = StageColors.Muted,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.mpx),
                )
            }
        } else {
            Row(Modifier.fillMaxSize().padding(top = 20.mpx, bottom = 30.mpx), horizontalArrangement = Arrangement.spacedBy(40.mpx)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.width(760.mpx).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 40.mpx),
                    verticalArrangement = Arrangement.spacedBy(8.mpx),
                ) {
                    items(items, key = { it.key }) { item ->
                        DvrListRow(
                            item = item,
                            selected = item.key == selected?.key,
                            focus = focusOf(item.key),
                            watched = (item as? DvrPlanned.Recording)?.let { r ->
                                remember(r.recording.id, progressRevision) { vm.progressFraction(r.recording) }
                            },
                            onFocused = { selectedKey = item.key; inActions = false },
                            onOpen = { openActions(item) },
                        )
                    }
                }
                selected?.let { item ->
                    DvrDetails(
                        item = item,
                        watched = (item as? DvrPlanned.Recording)?.let { r ->
                            remember(r.recording.id, progressRevision) { vm.progressFraction(r.recording) }
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .onFocusChanged { inActions = it.hasFocus }
                            // ◀ from the actions goes back to the line they belong to.
                            .onPreviewKeyEvent { e ->
                                if (e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown) { backToRow(); true } else false
                            },
                    ) {
                        DvrActions(
                            item = item,
                            firstFocus = firstAction,
                            resumeAt = (item as? DvrPlanned.Recording)?.recording?.let { vm.resumePositionMs(it) },
                            onPlay = { r, resume -> start(r, resume) },
                            onDelete = { r -> confirmDeleteFor = r },
                            onCancel = { r ->
                                val next = if (r.status == RecordingStatus.RECORDING) item.key else neighbourOf(item.key)
                                vm.cancelOrStop(r)
                                scope.launch { withFrameNanos { }; focusRow(next) }
                            },
                            onCancelGame = { p ->
                                val next = neighbourOf(item.key)
                                vm.cancelSportPlan(p)
                                scope.launch { withFrameNanos { }; focusRow(next) }
                            },
                        )
                    }
                }
            }
        }
    }

    confirmDeleteFor?.let { r ->
        val onServer = RemoteRecordings.remoteIdOf(r.filePath) != null
        tv.own.owntv.ui.stage.StageConfirm(
            title = stringResource(R.string.content_downloads_delete_title, r.title),
            body = stringResource(if (onServer) R.string.sport_dvr_delete_server_message else R.string.recording_delete_message),
            confirm = stringResource(R.string.common_delete),
            focusCancel = true,
            onConfirm = {
                confirmDeleteFor = null
                val key = DvrPlanned.keyOf(r.id)
                val next = neighbourOf(key)
                vm.delete(r) {
                    DvrThumbnails.delete(context, r.id)
                    scope.launch { withFrameNanos { }; focusRow(next) }
                }
            },
            onCancel = {
                confirmDeleteFor = null
                scope.launch { withFrameNanos { }; runCatching { firstAction.requestFocus() } }
            },
        )
    }

    deleteFailed?.let { r ->
        tv.own.owntv.ui.stage.StageNotice(
            title = stringResource(R.string.sport_dvr_delete_failed_title),
            body = stringResource(R.string.sport_dvr_delete_failed_message, r.title),
            onDismiss = {
                vm.clearDeleteFailed()
                scope.launch { withFrameNanos { }; focusRow(DvrPlanned.keyOf(r.id)) }
            },
        )
    }
}

/**
 * One line of the left list: a small still (or the channel's logo, or a Sport mark for a game waiting
 * for its channel), the title, and when and where. OK and ▶ move into the line's actions on the right.
 * OK acts on its release, and takes the press as well, so neither half reaches the action it lands on.
 */
@Composable
private fun DvrListRow(
    item: DvrPlanned,
    selected: Boolean,
    focus: FocusRequester,
    watched: Float?,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val sep = stringResource(R.string.content_epg_bits_separator)
    val (title, line) = dvrHeadline(item, sep, withYear = false)
    Row(
        Modifier
            .fillMaxWidth()
            .height(124.mpx)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                        if (e.type == KeyEventType.KeyUp) onOpen()
                        true
                    }
                    Key.DirectionRight -> { if (e.type == KeyEventType.KeyDown) onOpen(); true }
                    else -> false
                }
            }
            .focusable()
            .then(
                when {
                    focused -> Modifier.stageFocusLook(StageFocus.FX, 18.mpx)
                    selected -> Modifier.clip(RoundedCornerShape(18.mpx)).background(stageAccent.accent.copy(alpha = 0.10f))
                    else -> Modifier
                },
            )
            .padding(horizontal = 14.mpx, vertical = 10.mpx),
        horizontalArrangement = Arrangement.spacedBy(20.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(176.mpx, 99.mpx).clip(RoundedCornerShape(10.mpx)).background(stageStillBack)) {
            DvrPicture(item, markSize = 44.mpx)
            DvrWatchedBar(watched, 5.mpx)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.mpx)) {
            if ((item as? DvrPlanned.Recording)?.recording?.status == RecordingStatus.RECORDING) {
                tv.own.owntv.features.epg.GuideTag(
                    stringResource(R.string.sport_guide_recording), tv.own.owntv.features.epg.GuideLiveRed, filled = false, size = 13, dot = true,
                )
            }
            Text(
                title, style = stageText(22, 800).copy(lineHeight = 28.mpxSp),
                color = if (focused) StageColors.FxText else StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(line, style = stageText(16, 600), color = if (focused) stageAccent.accent else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The right panel: the still beside the title and when and where, the guide's whole description under
 * them, and the line's [actions] under that. The description gives way first when space runs out.
 */
@Composable
private fun DvrDetails(
    item: DvrPlanned,
    watched: Float?,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val sep = stringResource(R.string.content_epg_bits_separator)
    val (title, line) = dvrHeadline(item, sep, withYear = true)
    val description = (item as? DvrPlanned.Recording)?.recording?.description?.takeIf { it.isNotBlank() }
    Column(
        modifier
            .clip(RoundedCornerShape(28.mpx))
            .background(StageColors.Dim.copy(alpha = 0.10f))
            .padding(28.mpx),
        verticalArrangement = Arrangement.spacedBy(18.mpx),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.mpx), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(420.mpx, 236.mpx).clip(RoundedCornerShape(16.mpx)).background(stageStillBack)) {
                DvrPicture(item, markSize = 96.mpx)
                DvrWatchedBar(watched, 7.mpx)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.mpx)) {
                Text(title, style = stageText(32, 800).copy(lineHeight = 38.mpxSp), color = StageColors.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(line, style = stageText(18, 600), color = stageAccent.accent, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (description != null) {
            Text(
                description, style = stageText(20, 400).copy(lineHeight = 29.mpxSp), color = StageColors.Body,
                maxLines = Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        actions()
    }
}

/**
 * What can be done with the highlighted line, as the old OK menu offered it. [firstFocus] goes on the
 * first action, where OK or ▶ on the line lands.
 */
@Composable
private fun DvrActions(
    item: DvrPlanned,
    firstFocus: FocusRequester,
    resumeAt: Long?,
    onPlay: (RecordingEntity, Boolean) -> Unit,
    onDelete: (RecordingEntity) -> Unit,
    onCancel: (RecordingEntity) -> Unit,
    onCancelGame: (tv.own.owntv.features.sport.RecordPlan) -> Unit,
) {
    val actions = ArrayList<Triple<String, OwnTVIcon, () -> Unit>>()
    when (item) {
        is DvrPlanned.Game -> actions += Triple(stringResource(R.string.sport_dvr_cancel_recording), OwnTVIcon.CLOSE) { onCancelGame(item.plan) }
        is DvrPlanned.Recording -> {
            val r = item.recording
            val playable = r.status == RecordingStatus.COMPLETED || (r.status == RecordingStatus.RECORDING && r.filePath != null)
            if (playable) {
                if (resumeAt != null) {
                    actions += Triple(stringResource(R.string.sport_dvr_resume, dvrClock(resumeAt)), OwnTVIcon.PLAY) { onPlay(r, true) }
                    actions += Triple(stringResource(R.string.sport_dvr_start_over), OwnTVIcon.REWIND) { onPlay(r, false) }
                } else {
                    actions += Triple(stringResource(R.string.content_action_play), OwnTVIcon.PLAY) { onPlay(r, false) }
                }
            }
            when (r.status) {
                RecordingStatus.SCHEDULED -> actions += Triple(stringResource(R.string.sport_dvr_cancel_recording), OwnTVIcon.CLOSE) { onCancel(r) }
                RecordingStatus.RECORDING -> actions += Triple(stringResource(R.string.sport_dvr_stop_recording), OwnTVIcon.CLOSE) { onCancel(r) }
                else -> Unit
            }
            if (r.status == RecordingStatus.COMPLETED || r.status == RecordingStatus.RECORDING) {
                actions += Triple(stringResource(R.string.common_delete), OwnTVIcon.TRASH) { onDelete(r) }
            }
        }
    }
    Column(Modifier.width(560.mpx).focusGroup(), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
        actions.forEachIndexed { i, (text, icon, onClick) ->
            StageMenuItem(
                text = text, icon = icon, onClick = onClick,
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

/** A line's title, and its when-and-where: date, time span, channel and length; a game's kick-off and state. */
@Composable
private fun dvrHeadline(item: DvrPlanned, sep: String, withYear: Boolean): Pair<String, String> {
    val formatDate = rememberBestDateFormatter(if (withYear) "EEEdMMMyyyy" else "EEEdMMM")
    val formatTime = rememberSystemTimeFormatter()
    return when (item) {
        is DvrPlanned.Recording -> {
            val r = item.recording
            val minutes = ((r.programmeStopMs - r.programmeStartMs) / 60_000L).toInt().coerceAtLeast(1)
            r.title to listOf(
                formatDate(r.programmeStartMs),
                stringResource(R.string.content_live_time_range_plain, formatTime(r.programmeStartMs), formatTime(r.programmeStopMs)),
                r.channelName,
                stringResource(R.string.player_duration_minutes, minutes),
            ).joinToString(sep)
        }
        is DvrPlanned.Game -> {
            val p = item.plan
            val status = stringResource(
                if (p.status == tv.own.owntv.features.sport.RecordPlan.Status.NO_CHANNEL) R.string.sport_dvr_game_no_channel
                else R.string.sport_dvr_game_waiting,
            )
            p.title to listOf(formatDate(p.kickoffMs), formatTime(p.kickoffMs), status).joinToString(sep)
        }
    }
}

/** A recording's still (or channel logo), or a Sport mark for a game still waiting for its channel. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DvrPicture(item: DvrPlanned, markSize: Dp) {
    when (item) {
        is DvrPlanned.Recording -> DvrStill(item.recording)
        is DvrPlanned.Game -> Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            tv.own.owntv.ui.components.OwnTVIcon(OwnTVIcon.SPORT, StageColors.Muted, Modifier.size(markSize))
        }
    }
}

/** How far a recording was watched, along the bottom of its picture. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DvrWatchedBar(watched: Float?, height: Dp) {
    if (watched == null) return
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(height).background(StageColors.Dim.copy(alpha = 0.6f))) {
        Box(Modifier.fillMaxWidth(watched).fillMaxHeight().background(stageAccent.accent))
    }
}

/** "1:05:09" or "42:17": where a recording was left. */
internal fun dvrClock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
    else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
}

/** The recording's still, or — while it is being made, or when none can be — the channel's logo. */
@Composable
private fun DvrStill(recording: RecordingEntity) {
    val context = LocalContext.current
    val still by produceState<ImageBitmap?>(null, recording.id) {
        value = DvrThumbnails.load(context, recording)?.asImageBitmap()
    }
    val image = still
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().padding(18.mpx), contentAlignment = Alignment.Center) {
            tv.own.owntv.features.live.LivePlate(recording.channelIconUrl, 200.mpx, 98.mpx)
        }
    }
}
