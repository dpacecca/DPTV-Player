package tv.own.owntv.features.recordings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
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
import tv.own.owntv.features.epg.GuideMenuHost
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.rememberBestDateFormatter
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageMenuHeader
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

/** The finished recordings in [sort] order. */
internal fun dvrRows(recordings: List<RecordingEntity>, sort: DvrSort): List<RecordingEntity> {
    val done = recordings.filter { it.status == RecordingStatus.COMPLETED }
    return when (sort) {
        DvrSort.DATE -> done.sortedByDescending { it.programmeStartMs }
        DvrSort.TITLE -> done.sortedWith(compareBy<RecordingEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenByDescending { it.programmeStartMs })
    }
}

/**
 * DVR (v2.1): the recordings that are finished and can be watched, each with a still from the
 * recording, its title, when and where it was on, and the guide's synopsis. Sorted newest first or
 * A–Z. OK plays it; holding OK (or ☰) offers Play and Delete. What is recording now or scheduled is
 * counted in the header; managing those stays in the guide (Record) and Downloads › Recordings.
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
    val recordings by vm.rows.collectAsStateWithLifecycle()
    val externalOn by vm.externalPlayerOn.collectAsStateWithLifecycle()
    val lastPlayedId by vm.lastPlayedId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var sortName by rememberSaveable { mutableStateOf(DvrSort.DATE.name) }
    val sort = DvrSort.valueOf(sortName)
    val rows = remember(recordings, sort) { dvrRows(recordings, sort) }
    val recordingNow = recordings.count { it.status == RecordingStatus.RECORDING }
    val scheduled = recordings.count { it.status == RecordingStatus.SCHEDULED }

    val rowFocus = remember { HashMap<Long, FocusRequester>() }
    fun focusOf(id: Long) = rowFocus.getOrPut(id) { FocusRequester() }
    val sortFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var menuFor by remember { mutableStateOf<RecordingEntity?>(null) }
    // A recording that was left part-way: Resume (the default) or Start over.
    var resumeFor by remember { mutableStateOf<RecordingEntity?>(null) }
    val progressRevision by vm.progressRevision.collectAsStateWithLifecycle()

    val start: (RecordingEntity, Boolean) -> Unit = { r, resume ->
        vm.play(r, resume = resume)
        if (!externalOn) onFullscreen()
    }
    val play: (RecordingEntity) -> Unit = { r ->
        if (vm.resumePositionMs(r) != null) resumeFor = r else start(r, false)
    }

    suspend fun focusRow(id: Long) {
        if (runCatching { focusOf(id).requestFocus() }.getOrDefault(false)) return
        runCatching { listState.scrollToItem(rows.indexOfFirst { it.id == id }.coerceAtLeast(0)) }
        withFrameNanos { }
        runCatching { focusOf(id).requestFocus() }
    }

    // The rail's ▶ lands on the first recording, or on Sort when there are none.
    val entry: () -> Boolean = {
        val first = rows.firstOrNull()?.id
        (first != null && runCatching { focusOf(first).requestFocus() }.getOrDefault(false)) ||
            runCatching { sortFocus.requestFocus() }.getOrDefault(false)
    }
    DisposableEffect(onEntryHook, rows.firstOrNull()?.id) {
        onEntryHook?.invoke(entry)
        onDispose { onEntryHook?.invoke(null) }
    }
    // Back from the player: on the recording that was played.
    LaunchedEffect(restoreFocus, rows.size) {
        if (!restoreFocus || rows.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val id = lastPlayedId
        if (id != null && rows.any { it.id == id }) focusRow(id) else entry()
        onRestored()
    }

    Column(modifier.fillMaxSize().padding(start = contentStart, end = 64.mpx, top = 44.mpx)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.mpx)) {
            Text(stringResource(R.string.common_nav_dvr), style = stageText(46, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.sport_dvr_count, rows.size, rows.size),
                    recordingNow.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.sport_dvr_recording_now, it, it) },
                    scheduled.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.sport_dvr_scheduled, it, it) },
                ).joinToString(stringResource(R.string.content_epg_bits_separator)),
                style = stageText(19, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StageTool(
                text = stringResource(R.string.sport_dvr_sort), icon = OwnTVIcon.SORT,
                value = stringResource(if (sort == DvrSort.DATE) R.string.sport_dvr_sort_date else R.string.sport_dvr_sort_az),
                valueAccent = true,
                onClick = { sortName = (if (sort == DvrSort.DATE) DvrSort.TITLE else DvrSort.DATE).name },
                modifier = Modifier.focusRequester(sortFocus),
            )
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 120.mpx), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.sport_dvr_empty), style = stageText(22, 600), color = StageColors.Muted,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.mpx),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(top = 24.mpx),
                contentPadding = PaddingValues(bottom = 40.mpx),
                verticalArrangement = Arrangement.spacedBy(10.mpx),
            ) {
                items(rows, key = { it.id }) { r ->
                    DvrRow(
                        recording = r,
                        focus = focusOf(r.id),
                        thumbnail = {
                            DvrStill(r)
                            // How far it was watched, along the bottom of the still.
                            val watched = remember(r.id, progressRevision) { vm.progressFraction(r) }
                            if (watched != null) {
                                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.mpx).background(StageColors.Dim.copy(alpha = 0.6f))) {
                                    Box(Modifier.fillMaxWidth(watched).fillMaxHeight().background(stageAccent.accent))
                                }
                            }
                        },
                        onPlay = { play(r) },
                        onMenu = { menuFor = r },
                    )
                }
            }
        }
    }

    resumeFor?.let { r ->
        val at = vm.resumePositionMs(r) ?: 0L
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        GuideMenuHost(x = 1150f, top = 120.mpx, width = 560.mpx, onDismiss = { resumeFor = null }) {
            StageMenuHeader(title = r.title, subtitle = r.channelName)
            StageMenuItem(
                text = stringResource(R.string.sport_dvr_resume, dvrClock(at)), icon = OwnTVIcon.PLAY,
                onClick = { resumeFor = null; start(r, true) },
                modifier = Modifier.focusRequester(focus),
            )
            StageMenuItem(
                text = stringResource(R.string.sport_dvr_start_over), icon = OwnTVIcon.REWIND,
                onClick = { resumeFor = null; start(r, false) },
            )
        }
    }

    menuFor?.let { r ->
        val formatDate = rememberBestDateFormatter("EEEdMMM")
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        GuideMenuHost(x = 1150f, top = 120.mpx, width = 560.mpx, onDismiss = { menuFor = null }) {
            StageMenuHeader(title = r.title, subtitle = listOf(formatDate(r.programmeStartMs), r.channelName).joinToString(stringResource(R.string.content_epg_bits_separator)))
            StageMenuItem(
                text = stringResource(R.string.content_action_play), icon = OwnTVIcon.PLAY,
                onClick = { menuFor = null; play(r) },
                modifier = Modifier.focusRequester(focus),
            )
            StageMenuItem(
                text = stringResource(R.string.common_delete), icon = OwnTVIcon.TRASH,
                onClick = {
                    menuFor = null
                    DvrThumbnails.delete(context, r.id)
                    vm.delete(r)
                },
            )
        }
    }
}

/** One recording: the still, the title (two lines), when and where, and the synopsis. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DvrRow(
    recording: RecordingEntity,
    focus: FocusRequester,
    thumbnail: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    val formatDate = rememberBestDateFormatter("EEEdMMMyyyy")
    val formatTime = rememberSystemTimeFormatter()
    val minutes = ((recording.programmeStopMs - recording.programmeStartMs) / 60_000L).toInt().coerceAtLeast(1)
    val line = listOf(
        formatDate(recording.programmeStartMs),
        stringResource(R.string.content_live_time_range_plain, formatTime(recording.programmeStartMs), formatTime(recording.programmeStopMs)),
        recording.channelName,
        stringResource(R.string.player_duration_minutes, minutes),
    ).joinToString(stringResource(R.string.content_epg_bits_separator))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 196.mpx)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) okDown = false }
            // Hold OK (or ☰) for Play / Delete; a press plays. Acting on release is what lets a hold differ.
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        val repeat = e.nativeKeyEvent.repeatCount
                        if (down && repeat == 0) okDown = true
                        else if (down && okDown) { okDown = false; onMenu() }
                        else if (!down && okDown) { okDown = false; onPlay() }
                        true
                    }
                    Key.Menu -> { if (down) onMenu(); true }
                    else -> false
                }
            }
            .combinedClickable(interactionSource = null, indication = null, onClick = onPlay, onLongClick = onMenu)
            .focusable()
            .then(if (focused) Modifier.stageFocusLook(StageFocus.FX, 22.mpx) else Modifier)
            .padding(horizontal = 18.mpx, vertical = 14.mpx),
        horizontalArrangement = Arrangement.spacedBy(28.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(300.mpx, 169.mpx).clip(RoundedCornerShape(14.mpx)).background(stageStillBack)) { thumbnail() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
            Text(
                recording.title, style = stageText(30, 800).copy(lineHeight = 36.mpxSp),
                color = if (focused) StageColors.FxText else StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(line, style = stageText(19, 600), color = if (focused) stageAccent.accent else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            recording.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = stageText(21, 400).copy(lineHeight = 29.mpxSp), color = StageColors.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
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
