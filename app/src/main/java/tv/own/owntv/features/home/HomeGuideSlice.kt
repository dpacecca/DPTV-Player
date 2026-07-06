package tv.own.owntv.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.features.epg.GuideGridDefaults
import tv.own.owntv.features.epg.ProgrammeDetailDialog
import tv.own.owntv.features.epg.ProgrammeStripCanvas
import tv.own.owntv.features.epg.clock
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.OwnTVTheme

@Composable
fun HomeGuideSlice(
    slice: GuideSliceState,
    onTuneChannel: (ChannelEntity) -> Unit,
    onOpenFullGuide: () -> Unit,
    onFocus: () -> Unit,
    firstItemFocusRequester: FocusRequester?,
    loadDescription: suspend (Long) -> String?,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    val rows = remember(slice.channels, slice.programmes) {
        slice.channels.filter { slice.programmes[it.id].orEmpty().isNotEmpty() }
    }
    val cursorTimes = remember(slice.now, rows.map { it.id }) {
        mutableStateMapOf<Long, Long>().apply {
            rows.forEach { put(it.id, slice.now) }
        }
    }
    val labelSelected = remember(slice.now, rows.map { it.id }) {
        mutableStateMapOf<Long, Boolean>().apply {
            rows.forEach { put(it.id, false) }
        }
    }
    val windowWidth = GuideGridDefaults.PxPerMin * (((slice.windowEnd - slice.windowStart).coerceAtLeast(0L) / 60_000f))
    val detailFocusRow = remember { mutableStateMapOf<Long, FocusRequester>() }
    var focusedChannelId by remember { mutableStateOf<Long?>(null) }

    if (rows.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.HomeRowPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ON NOW",
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            OwnTVButton(
                label = "View full guide",
                onClick = onOpenFullGuide,
                icon = OwnTVIcon.EPG,
                style = OwnTVButtonStyle.SECONDARY,
                modifier = Modifier.onFocusChanged {
                    if (it.hasFocus) {
                        focusedChannelId = null
                        onFocus()
                    }
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        GuideLiteTimeAxis(
            windowStart = slice.windowStart,
            windowEnd = slice.windowEnd,
            width = windowWidth,
        )
        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { index, channel ->
                val rowFocus = detailFocusRow.getOrPut(channel.id) { FocusRequester() }
                val rowCursor = cursorTimes[channel.id] ?: slice.now
                val rowLabelSelected = labelSelected[channel.id] ?: false
                val programmes = slice.programmes[channel.id].orEmpty()
                val selectedProgramme = currentProgramme(programmes, rowCursor)
                val highlightTime = if (focusedChannelId == channel.id && !rowLabelSelected) {
                    selectedProgramme?.startMs ?: rowCursor
                } else {
                    null
                }
                GuideLiteRow(
                    channel = channel,
                    programmes = programmes,
                    windowStart = slice.windowStart,
                    windowEnd = slice.windowEnd,
                    now = slice.now,
                    cursorTime = rowCursor,
                    highlightTime = highlightTime,
                    labelSelected = rowLabelSelected,
                    focusRequester = if (index == 0 && firstItemFocusRequester != null) firstItemFocusRequester else rowFocus,
                    onCursorTimeChange = { cursorTimes[channel.id] = it },
                    onLabelSelectedChange = { labelSelected[channel.id] = it },
                    onTuneChannel = onTuneChannel,
                    onFocus = {
                        focusedChannelId = channel.id
                        onFocus()
                    },
                    loadDescription = loadDescription,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun GuideLiteTimeAxis(
    windowStart: Long,
    windowEnd: Long,
    width: androidx.compose.ui.unit.Dp,
) {
    val colors = OwnTVTheme.colors
    val slotWidth = GuideGridDefaults.PxPerMin * GuideGridDefaults.SlotMin.toFloat()
    val slots = (((windowEnd - windowStart).coerceAtLeast(0L) / 60_000L) / GuideGridDefaults.SlotMin).toInt()
        .coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.HomeRowPaddingH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(GuideGridDefaults.ChannelCol))
        Row(
            modifier = Modifier.width(width),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            repeat(slots) { index ->
                val slotStart = windowStart + index * GuideGridDefaults.SlotMin * 60_000L
                Text(
                    text = clock(slotStart),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.width(slotWidth).padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideLiteRow(
    channel: ChannelEntity,
    programmes: List<EpgProgrammeEntity>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    cursorTime: Long,
    highlightTime: Long?,
    labelSelected: Boolean,
    focusRequester: FocusRequester,
    onCursorTimeChange: (Long) -> Unit,
    onLabelSelectedChange: (Boolean) -> Unit,
    onTuneChannel: (ChannelEntity) -> Unit,
    onFocus: () -> Unit,
    loadDescription: suspend (Long) -> String?,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    var detail by remember { mutableStateOf<EpgProgrammeEntity?>(null) }
    val useFocusRequester = focusRequester
    var hadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(detail) {
        if (detail != null) {
            hadDialog = true
            return@LaunchedEffect
        }
        if (hadDialog) {
            hadDialog = false
            kotlinx.coroutines.delay(40)
            runCatching { useFocusRequester.requestFocus() }
        }
    }

    val selectedIndex = currentProgrammeIndex(programmes, cursorTime)
    val selectedProgramme = programmes.getOrNull(selectedIndex)

    FocusableSurface(
        onClick = {
            if (labelSelected) {
                onTuneChannel(channel)
            } else {
                val programme = selectedProgramme ?: programmes.firstOrNull()
                if (programme != null) {
                    detail = programme
                } else {
                    onTuneChannel(channel)
                }
            }
        },
        modifier = modifier
            .height(GuideGridDefaults.RowHeight)
            .focusRequester(useFocusRequester)
            .onFocusChanged {
                if (it.hasFocus) {
                    onFocus()
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || programmes.isEmpty()) return@onKeyEvent false
                val current = currentProgrammeIndex(programmes, cursorTime)
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (labelSelected) {
                            false
                        } else if (current <= 0) {
                            onLabelSelectedChange(true)
                            true
                        } else {
                            onCursorTimeChange(programmes[current - 1].startMs.coerceAtLeast(windowStart))
                            true
                        }
                    }
                    Key.DirectionRight -> {
                        if (labelSelected) {
                            onLabelSelectedChange(false)
                            onCursorTimeChange(programmes.firstOrNull()?.startMs ?: now)
                        } else if (current < programmes.lastIndex) {
                            onCursorTimeChange(programmes[current + 1].startMs.coerceAtLeast(windowStart))
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (labelSelected) {
                            onTuneChannel(channel)
                        } else {
                            val programme = selectedProgramme ?: programmes.firstOrNull()
                            if (programme != null) detail = programme else onTuneChannel(channel)
                        }
                        true
                    }
                    else -> false
                }
            },
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1f,
        glowElevation = 0,
        focusedContainerColor = colors.surfaceContainerHigh,
        unfocusedContainerColor = colors.surfaceContainerHigh,
        selectedContainerColor = colors.surfaceContainerHigh,
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.HomeRowPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(GuideGridDefaults.ChannelCol)
                    .height(GuideGridDefaults.RowHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (labelSelected) colors.primaryContainer else colors.surfaceContainerLowest),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = channel.number?.let { "$it  ${channel.name}" } ?: channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (labelSelected) colors.onPrimaryContainer else colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Box(
                modifier = Modifier
                    .width(windowWidth(windowStart, windowEnd))
                    .height(GuideGridDefaults.RowHeight),
            ) {
                ProgrammeStripCanvas(
                    programmes = programmes,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    highlightTime = highlightTime,
                    hScroll = rememberScrollState(),
                )
            }
        }
    }

    detail?.let { programme ->
        ProgrammeDetailDialog(
            channelName = channel.name,
            programme = programme,
            loadDescription = loadDescription,
            canCatchup = false,
            onWatch = {
                detail = null
                onTuneChannel(channel)
            },
            onPlayCatchup = { detail = null },
            onDismiss = { detail = null },
        )
    }
}

private fun currentProgrammeIndex(programmes: List<EpgProgrammeEntity>, cursorTime: Long): Int {
    if (programmes.isEmpty()) return -1
    val index = programmes.indexOfLast { it.startMs <= cursorTime }
    return if (index < 0) 0 else index
}

private fun currentProgramme(programmes: List<EpgProgrammeEntity>, cursorTime: Long): EpgProgrammeEntity? {
    if (programmes.isEmpty()) return null
    val index = currentProgrammeIndex(programmes, cursorTime)
    return programmes.getOrNull(index)
}

private fun windowWidth(windowStart: Long, windowEnd: Long): androidx.compose.ui.unit.Dp {
    val minutes = ((windowEnd - windowStart).coerceAtLeast(0L) / 60_000f)
    return GuideGridDefaults.PxPerMin * minutes
}
