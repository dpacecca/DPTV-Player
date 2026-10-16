package tv.own.owntv.features.epg

import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.ui.theme.StageColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.PopupFontTheme
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.stage.drawBoxShadow
import tv.own.owntv.ui.components.drawStageGlyph
import tv.own.owntv.ui.theme.glass

internal object GuideGridDefaults {
    /** The channel column when the guide widths are not customised (v2.1: number, logo and a two-line name, 520 of 1920). */
    const val ChannelColShare = 520f / 1920f
    /** v2 rows: 84 high, so a long title takes two lines; v2.1.0 (DPTV Recorder): no gap, a 1 px line between. */
    val RowHeight = 84.mpx
    val RowGap = 0.mpx
    /** 3.5 hours across the mockup's 1512 px timeline. */
    val PxPerMin = 7.2.mpx
    const val SlotMin = 30
}

/**
 * One channel's programmes as Stage cells (`.gcell`, P4-01): white 4.5%, the one on now 8.5%, past ones
 * at half opacity, radius 14, 3 px apart; title 18/700, then the recording ● / reminder bell / catch-up
 * ↺ icons and the time in 14 dim. The cell at [focusTime] is the focused one: accent fill, 2 px focus
 * ring and glow. One Canvas per row rather than a node per programme, so a week of guide scrolls light.
 */
@Composable
internal fun ProgrammeStripCanvas(
    programmes: List<EpgProgrammeEntity>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    focusTime: Long?,
    catchupIds: Set<Long>,
    recordingStarts: Set<Long>,
    reminderStarts: Set<Long>,
    /** How far the timeline is scrolled, shared by every row and the ruler. */
    scrollPx: Int,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val a = tv.own.owntv.ui.theme.stageAccent
    val px = with(density) { 1.mpx.toPx() }
    val pxPerMin = with(density) { GuideGridDefaults.PxPerMin.toPx() }
    val titleStyle = tv.own.owntv.ui.theme.stageText(19, 600).copy(lineHeight = 23.mpxSp).copy(textDirection = TextDirection.Content)
    // DPTV Recorder's small line: the start and the length in mono ("1:00 pm · 90m").
    val timeStyle = guideMono(14).copy(textDirection = TextDirection.Content)
    val formatTime = rememberSystemTimeFormatter()
    val minutesTemplate = stringResource(R.string.sport_guide_minutes_short)
    val labels = remember(programmes, formatTime, minutesTemplate) {
        programmes.map {
            formatTime(it.startMs) + " · " + String.format(java.util.Locale.getDefault(), minutesTemplate, ((it.stopMs - it.startMs) / 60_000L).toInt())
        }
    }
    val recRed = GuideLiveRed
    val border = GuideColors.Border
    val textColor = tv.own.owntv.ui.theme.StageColors.Text
    val dimColor = tv.own.owntv.ui.theme.StageColors.Dim
    val ink = tv.own.owntv.ui.theme.StageColors.Ink
    // A row with nothing in the window says so, rather than looking broken.
    val emptyText = stringResource(R.string.content_epg_no_programme)
    // Clipped at the timeline's left edge only, so a focused cell's glow still spills up and down.
    Canvas(Modifier.fillMaxSize()) { clipRect(left = 0f, top = -size.height, right = size.width + 40 * px, bottom = size.height * 2) {
        val viewW = size.width
        val h = size.height
        val r = CornerRadius(10 * px)
        // Feeds sometimes carry two programmes for the same time. Each one is drawn from where the one
        // before it ends, so they never sit on top of each other; one hidden completely is left out.
        var shownUntil = windowStart
        var drawnAny = false
        // The cursor on a stretch with nothing on (a feed that stops): that gap is the focused cell.
        if (focusTime != null && programmes.none { focusTime in it.startMs until it.stopMs }) {
            val gapStart = (programmes.lastOrNull { it.stopMs <= focusTime }?.stopMs ?: windowStart).coerceAtLeast(windowStart)
            val gapEnd = (programmes.firstOrNull { it.startMs > focusTime }?.startMs ?: windowEnd).coerceAtMost(windowEnd)
            val gx0 = (((gapStart - windowStart) / 60_000f) * pxPerMin - scrollPx + 3 * px).coerceAtLeast(0f)
            val gx1 = (((gapEnd - windowStart) / 60_000f) * pxPerMin - scrollPx - 3 * px).coerceAtMost(viewW)
            if (gx1 > gx0) {
                drawRoundRect(ink.copy(alpha = 0.10f), Offset(gx0, 0f), Size(gx1 - gx0, h), r)
                val ring = 3.5f * px
                drawRoundRect(a.accent, Offset(gx0 + ring / 2, ring / 2), Size(gx1 - gx0 - ring, h - ring), CornerRadius(r.x - ring / 2), style = Stroke(ring))
                val label = measurer.measure(emptyText, timeStyle.copy(color = dimColor), maxLines = 1, softWrap = false)
                drawText(label, topLeft = Offset(gx0 + 14 * px, (h - label.size.height) / 2f))
                drawnAny = true
            }
        }
        programmes.forEachIndexed { i, p ->
            val s0 = p.startMs.coerceIn(windowStart, windowEnd).coerceAtLeast(shownUntil)
            val e0 = p.stopMs.coerceIn(windowStart, windowEnd)
            if (e0 <= s0) return@forEachIndexed
            shownUntil = e0
            val x = ((s0 - windowStart) / 60_000f) * pxPerMin - scrollPx.toFloat() + 3 * px
            val w = ((e0 - s0) / 60_000f) * pxPerMin - 6 * px
            if (w <= 0f || x + w <= 0f || x >= viewW) return@forEachIndexed
            drawnAny = true
            val focused = focusTime != null && focusTime in p.startMs until p.stopMs
            val isNow = now in p.startMs until p.stopMs
            val fade = if (!focused && p.stopMs <= now) 0.5f else 1f
            val box = androidx.compose.ui.geometry.Rect(x, 0f, x + w, h)
            // DPTV Recorder's cells: a lifted fill with a 1 px line; the focused one filled in the accent with
            // its glow. What is on now is not marked here (v2.1.1): LIVE is on the info beside the video.
            if (focused) {
                // v2.1.3: the focused cell keeps its ordinary fill and gets a thick accent border, so its
                // title stays as easy to read as the rest (a full accent fill made white-on-green hard).
                drawRoundRect(ink.copy(alpha = 0.10f), Offset(x, 0f), Size(w, h), r)
                val ring = 3.5f * px
                drawRoundRect(a.accent, Offset(x + ring / 2, ring / 2), Size(w - ring, h - ring), CornerRadius(r.x - ring / 2), style = Stroke(ring))
            } else {
                drawRoundRect(ink.copy(alpha = (if (isNow) 0.075f else 0.05f) * fade), Offset(x, 0f), Size(w, h), r)
                val line = 1.2f * px
                drawRoundRect(
                    border.copy(alpha = border.alpha * fade),
                    Offset(x + line / 2, line / 2), Size(w - line, h - line), CornerRadius(r.x - line / 2), style = Stroke(line),
                )
            }
            // Text starts at the visible part of a cell that began before the timeline's left edge.
            val tx = maxOf(x, 0f)
            val textW = (x + w - tx - 28 * px).toInt()
            if (textW <= 4) return@forEachIndexed
            val titleColor = textColor.copy(alpha = fade)
            val smallColor = dimColor.copy(alpha = fade)
            val iconColor = a.accent.copy(alpha = fade)
            // The title takes two lines when it needs them (v2); the small line (icons, time) only fits under
            // a one-line title, so with two the icons sit at the cell's end and the time is left to the info on top.
            val title = measurer.measure(p.title, titleStyle.copy(color = titleColor), overflow = TextOverflow.Ellipsis, maxLines = 2, softWrap = true, constraints = Constraints(maxWidth = textW))
            val twoLines = title.lineCount > 1
            val icon = 14 * px
            if (twoLines) {
                drawText(title, topLeft = Offset(tx + 14 * px, (h - title.size.height) / 2f))
                var ix = x + w - 12 * px - icon
                fun endGlyph(g: OwnTVIcon, c: Color, filled: Boolean = false) {
                    if (ix < tx + 14 * px + title.size.width) return
                    drawStageGlyph(g, c, Offset(ix, h - icon - 8 * px), icon, filled)
                    ix -= icon + 6 * px
                }
                if (p.startMs in recordingStarts) endGlyph(OwnTVIcon.REC, recRed.copy(alpha = fade), filled = true)
                if (p.startMs in reminderStarts) endGlyph(OwnTVIcon.BELL, iconColor)
                if (p.id in catchupIds) endGlyph(OwnTVIcon.REWIND, iconColor)
                return@forEachIndexed
            }
            val blockH = title.size.height + 3 * px + 18 * px
            val top = (h - blockH) / 2f
            drawText(title, topLeft = Offset(tx + 14 * px, top))
            // The small line: icons first, 8 apart, then the time.
            var cx = tx + 14 * px
            val lineTop = top + title.size.height + 3 * px
            fun glyph(g: OwnTVIcon, c: Color, filled: Boolean = false) {
                if (cx + icon > x + w - 14 * px) return
                drawStageGlyph(g, c, Offset(cx, lineTop + 2 * px), icon, filled)
                cx += icon + 8 * px
            }
            if (p.startMs in recordingStarts) glyph(OwnTVIcon.REC, recRed.copy(alpha = fade), filled = true)
            if (p.startMs in reminderStarts) glyph(OwnTVIcon.BELL, iconColor)
            if (p.id in catchupIds) glyph(OwnTVIcon.REWIND, iconColor)
            val timeW = (x + w - 14 * px - cx).toInt()
            if (timeW > 4) {
                val time = measurer.measure(labels[i], timeStyle.copy(color = smallColor), overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = timeW))
                drawText(time, topLeft = Offset(cx, lineTop))
            }
        }
        // Nothing on anywhere in view: say so, rather than a row that looks broken.
        if (!drawnAny) {
            drawRoundRect(ink.copy(alpha = 0.03f), Offset(3 * px, 0f), Size(viewW - 6 * px, h), r)
            val label = measurer.measure(emptyText, timeStyle.copy(color = dimColor), maxLines = 1, softWrap = false)
            drawText(label, topLeft = Offset(17 * px, (h - label.size.height) / 2f))
        }
    } }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ProgrammeDetailDialog(
    channelName: String,
    programme: EpgProgrammeEntity,
    loadDescription: suspend (Long) -> String?,
    canCatchup: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onWatch: () -> Unit,
    onPlayCatchup: () -> Unit,
    onDismiss: () -> Unit,
    // Where "Watch from start" sends the archive. ASK shows a chooser popup on top of this dialog;
    // INTERNAL/EXTERNAL go straight there. Defaulted so non-catch-up callers can ignore it.
    catchupPlayer: SettingsRepository.CatchupPlayer = SettingsRepository.CatchupPlayer.INTERNAL,
    onPlayCatchupExternal: () -> Unit = {},
    // Denser variant for the Live TV catch-up picker, which opens this on top of an already-small
    // popup chain — full-size chrome dwarfed the picker it came from. Guide keeps the roomy layout.
    compact: Boolean = false,
    // --- Recording (Plan D, Feature A). All defaulted, so the Live TV catch-up picker — which opens
    // this dialog on top of the player and has no business scheduling anything — is unchanged.
    /** Whether Record can be offered at all: still to come, or on a catch-up channel and already aired. */
    canRecord: Boolean = false,
    /** The recording already covering this programme, which decides what the button says. */
    recording: RecordingEntity? = null,
    /** The title of a recording this one would contend with, shown as a warning before committing (D10). */
    clashWith: String? = null,
    onRecord: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    /** Non-null when a standing "record every showing" rule already covers this title (D7). */
    seriesRuleActive: Boolean = false,
    onRecordSeries: () -> Unit = {},
    onStopSeries: () -> Unit = {},
) {
    val colors = OwnTVTheme.colors
    val formatTime = rememberSystemTimeFormatter()
    // The grid load drops `description` to stay under the CursorWindow limit, so fetch it on demand
    // here (fall back to the row's own value when it was loaded by the lazy per-row path).
    val description by produceState(programme.description, programme.id) {
        value = programme.description ?: loadDescription(programme.id)
    }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    // "Always ask" → a second, small popup asking which player takes the archive.
    var showPlayerChooser by remember { mutableStateOf(false) }
    if (showPlayerChooser) {
        CatchupPlayerChooser(
            onInternal = { showPlayerChooser = false; onPlayCatchup() },
            onExternal = { showPlayerChooser = false; onPlayCatchupExternal() },
            onDismiss = { showPlayerChooser = false },
        )
    }
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        eyebrow = channelName.uppercase(),
        eyebrowAccent = true,
        title = programme.title,
        body = stringResource(R.string.content_epg_time_range, formatTime(programme.startMs), formatTime(programme.stopMs)),
        width = 1008.mpx,
        modifier = Modifier.longPressMenuGuard(),
    ) {
        BackHandler { onDismiss() }
                if (!description.isNullOrBlank()) {
                    Text(description.orEmpty(), style = stageText(18, 400), color = StageColors.Muted)
                }
                // The clash, said before the user commits to anything. A live programme cannot wait
                // its turn — "start when the other finishes" would mean "start half-way through"
                // (D10) — so this is a warning at the moment of choosing, not a failure afterwards.
                if (canRecord && clashWith != null && recording == null) {
                    Spacer(Modifier.height(14.mpx))
                    Text(
                        stringResource(R.string.recording_clash_with, clashWith),
                        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = StageColors.Danger,
                    )
                }
                Spacer(Modifier.height(24.mpx))
                // FlowRow so the actions wrap to a second line on narrower screens instead of the last
                // button being clipped off the dialog edge (4 buttons don't fit one row when catch-up adds
                // "Watch from start" + "Watch channel").
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.mpx, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(14.mpx),
                ) {
                    // Catch-up channels: replay this programme from its start (seekable archive playback).
                    if (canCatchup) {
                        val startCatchup = {
                            when (catchupPlayer) {
                                SettingsRepository.CatchupPlayer.ASK -> showPlayerChooser = true
                                SettingsRepository.CatchupPlayer.INTERNAL -> onPlayCatchup()
                                SettingsRepository.CatchupPlayer.EXTERNAL -> onPlayCatchupExternal()
                            }
                        }
                        OwnTVButton(stringResource(R.string.content_epg_watch_start), onClick = startCatchup, icon = OwnTVIcon.PLAY, compact = compact, modifier = Modifier.focusRequester(fr))
                        OwnTVButton(stringResource(R.string.content_epg_watch_channel), onClick = onWatch, style = OwnTVButtonStyle.SECONDARY, compact = compact)
                    } else {
                        OwnTVButton(stringResource(R.string.content_epg_watch_channel), onClick = onWatch, icon = OwnTVIcon.PLAY, compact = compact, modifier = Modifier.focusRequester(fr))
                    }
                    // Record. What it offers depends on what is already true of this programme, so
                    // the button never lies: nothing yet → Record (or "from catch-up" when the
                    // programme has already been on); running → Stop; scheduled → Cancel.
                    if (canRecord) {
                        when (recording?.status) {
                            RecordingStatus.RECORDING -> OwnTVButton(
                                stringResource(R.string.recording_stop),
                                onClick = onStopRecording,
                                style = OwnTVButtonStyle.SECONDARY,
                                compact = compact,
                            )
                            RecordingStatus.SCHEDULED -> OwnTVButton(
                                stringResource(R.string.common_cancel),
                                onClick = onCancelRecording,
                                style = OwnTVButtonStyle.SECONDARY,
                                compact = compact,
                            )
                            // Already recorded, or failed and worth another go: Record again is the
                            // honest offer, and the Recordings screen is where the result lives.
                            else -> OwnTVButton(
                                stringResource(
                                    if (programme.stopMs <= System.currentTimeMillis()) {
                                        R.string.recording_from_archive
                                    } else {
                                        R.string.recording_record
                                    },
                                ),
                                onClick = onRecord,
                                style = OwnTVButtonStyle.SECONDARY,
                                compact = compact,
                            )
                        }
                    }
                    // "Every showing" only for a programme still to come — a rule is a standing
                    // instruction about the future, and offering it on last night's repeat would
                    // promise something it cannot do.
                    if (canRecord && programme.stopMs > System.currentTimeMillis()) {
                        OwnTVButton(
                            stringResource(
                                if (seriesRuleActive) R.string.recording_stop_series
                                else R.string.recording_record_series,
                            ),
                            onClick = if (seriesRuleActive) onStopSeries else onRecordSeries,
                            style = OwnTVButtonStyle.SECONDARY,
                            compact = compact,
                        )
                    }
                    // Favourite the channel without leaving the guide; the label flips in place.
                    OwnTVButton(
                        stringResource(if (isFavorite) R.string.content_epg_unfavourite else R.string.content_epg_favourite),
                        onClick = onToggleFavorite,
                        style = OwnTVButtonStyle.SECONDARY,
                        icon = OwnTVIcon.FAVORITE,
                        compact = compact,
                    )
                    OwnTVButton(stringResource(R.string.settings_close), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY, compact = compact)
                }
    }
}

/** The "Always ask" chooser: which player takes this catch-up archive. Deliberately tiny — it sits on
 *  top of the programme dialog, so it only asks the one question and gets out of the way. */
@Composable
internal fun CatchupPlayerChooser(
    onInternal: () -> Unit,
    onExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_catchup_player),
        body = stringResource(R.string.content_epg_player_choice_description),
        width = 700.mpx,
        buttons = { OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY) },
    ) {
        BackHandler { onDismiss() }
        tv.own.owntv.ui.stage.StagePopupOption(
            title = stringResource(R.string.content_epg_own_player), onClick = onInternal,
            modifier = Modifier.focusRequester(fr), leading = { tv.own.owntv.ui.stage.StagePopupIcon(OwnTVIcon.PLAY) },
        )
        tv.own.owntv.ui.stage.StagePopupOption(
            title = stringResource(R.string.content_epg_external_player), onClick = onExternal,
            leading = { tv.own.owntv.ui.stage.StagePopupIcon(OwnTVIcon.EXTERNAL) },
        )
    }
}

/** Applies the shared popup type ramp at a reduced scale only when [compact]; otherwise leaves the
 *  caller's typography untouched, so the Guide's own dialog keeps its existing look. */
@Composable
private fun CompactPopupFont(compact: Boolean, content: @Composable () -> Unit) {
    if (compact) PopupFontTheme(fontScale = 0.7f, content = content) else content()
}
