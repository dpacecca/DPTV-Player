package tv.own.owntv.features.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.features.live.edgeScrollSpec
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StageSearchField
import tv.own.owntv.ui.stage.StageStepper
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSwitch
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.StageRadii
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/*
 * Stage Settings (P10, references P9-01 … P9-13): a group is one page. The band (crumb as the title,
 * the count, "Search all settings" on the same line), the rows on the left (x 50–1110 from y 210) and
 * the context panel on the right (x 1160–1856 from y 240) explaining whatever row has focus.
 */

/** What a settings row shows on its right (`.srow .val`). */
sealed interface SettingValue {
    /** `.sw2`: a switch; OK flips it. */
    class Switch(val on: Boolean) : SettingValue
    /** A choice made in a picker, written in accent with ▾. */
    class Choice(val text: String) : SettingValue
    /** Opens a screen of its own: accent text (may be empty) and ›. The only place a chevron appears. */
    class Opens(val text: String?) : SettingValue
    /** `.stp`: "− 85% +"; ◀ ▶ change it in place. */
    class Stepper(val text: String) : SettingValue
    /** `.seg2`: the options side by side, the chosen one lit; ◀ ▶ (or OK) move the choice. */
    class Segmented(val options: List<String>, val selected: Int) : SettingValue
    /** "19 saved · Reset". With nothing saved, just the "None saved" text. */
    class Saved(val text: String, val any: Boolean) : SettingValue
    /** A plain action word in full text colour ("Forget", "Check now"). */
    class Action(val text: String) : SettingValue
    /** Anything else the mockup draws there (accent swatches, profile tags). */
    class Custom(val content: @Composable () -> Unit) : SettingValue
}

/**
 * The context panel's text for one row: [title] as its heading, the long [text], and — for a row whose
 * value is picked from a list — the [choices] with the current one ([chosen]) and the [recommended] one.
 */
@Stable
data class SettingHelp(
    val title: String,
    val text: String,
    val choices: List<String> = emptyList(),
    val chosen: Int = -1,
    val recommended: Int = -1,
    val hints: List<Pair<String, String>> = emptyList(),
    /** Drawn under the text: Playlists lists the playlists themselves (P9-03). */
    val extra: (@Composable () -> Unit)? = null,
)

/** The focused row's help, published by each row and drawn by the page's panel. */
@Stable
class SettingsPanelState {
    var help by mutableStateOf<SettingHelp?>(null)
}

val LocalSettingsPanel = staticCompositionLocalOf<SettingsPanelState?> { null }

/** True inside a Stage settings page: [Row2] then draws [StageSettingRow]. Other screens keep their rows. */
val LocalStageRows = staticCompositionLocalOf { false }

/**
 * The panel text for the row [key]: its long explanation from [SETTING_HELP] (falling back to the
 * row's own line), the choices, and key hints from what the row does.
 */
@Composable
fun settingHelp(
    key: String?,
    title: String,
    desc: String?,
    value: SettingValue?,
    choices: List<String> = emptyList(),
    chosen: Int = -1,
    recommended: Int = -1,
    pinnable: Boolean = true,
): SettingHelp {
    val text = key?.let { SETTING_HELP[it] }?.let { stringResource(it, *NO_ARGS) } ?: desc.orEmpty()
    // A switch lists On and Off as its choices (P9-09, P9-11).
    if (value is SettingValue.Switch && choices.isEmpty()) {
        val rec = key?.let { SWITCH_RECOMMENDED[it] }?.let { if (it) 0 else 1 } ?: -1
        return SettingHelp(
            title, text,
            listOf(stringResource(R.string.common_on), stringResource(R.string.common_off)),
            if (value.on) 0 else 1, rec, settingHints(value, pinnable),
        )
    }
    // A choice row that was handed no index finds its current value among the choices by its label.
    val current = if (chosen >= 0) chosen else when (value) {
        is SettingValue.Choice -> choices.indexOf(value.text)
        is SettingValue.Segmented -> value.selected
        else -> -1
    }
    return SettingHelp(title, text, choices, current, recommended, settingHints(value, pinnable))
}

/** A row's name and short line on a Stage page: the page's own wording where [SETTING_TITLE] / [SETTING_LINE] have one. */
class SettingWords(val title: String, val line: String?)

@Composable
fun settingWords(key: String?, title: String, desc: String?): SettingWords = SettingWords(
    key?.let { SETTING_TITLE[it] }?.let { stringResource(it) } ?: title,
    key?.let { SETTING_LINE[it] }?.let { stringResource(it, *NO_ARGS) } ?: desc,
)

/** Key hints for a row, from what pressing it does. [pinnable] adds "Hold OK · Pin to Quick". */
@Composable
fun settingHints(value: SettingValue?, pinnable: Boolean = true): List<Pair<String, String>> {
    val ok = stringResource(R.string.common_ok)
    val change = stringResource(R.string.settings_key_change)
    val keys = when (value) {
        is SettingValue.Stepper -> listOf(ok to stringResource(R.string.settings_key_open), "◀ ▶" to change)
        is SettingValue.Segmented -> listOf("◀ ▶" to change)
        is SettingValue.Switch -> listOf(ok to stringResource(R.string.settings_key_switch))
        is SettingValue.Opens, null -> listOf(ok to stringResource(R.string.settings_key_open))
        else -> listOf(ok to change)
    }
    return keys + listOfNotNull(
        if (pinnable) stringResource(R.string.content_key_hold_ok) to stringResource(R.string.settings_row_menu_pin) else null,
        stringResource(R.string.common_back) to stringResource(R.string.common_nav_settings),
    )
}

/**
 * The whole page: band, rows and panel. [rows] is the scrolling column; [panelTop] is drawn above the
 * focused row's explanation (Appearance's live preview).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StageSettingsPage(
    group: String,
    count: String,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    searchFocus: FocusRequester = remember { FocusRequester() },
    /** On the rows column: requesting it puts focus on the page's first row. */
    rowsFocus: FocusRequester = remember { FocusRequester() },
    panel: SettingsPanelState = remember { SettingsPanelState() },
    /** A short title with no "Settings ›" before it (the search results page). */
    crumb: Boolean = true,
    panelTop: (@Composable ColumnScope.() -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    val searching = searchQuery.isNotBlank()
    androidx.compose.runtime.LaunchedEffect(searching) { if (searching) panel.help = null }
    CompositionLocalProvider(LocalSettingsPanel provides panel) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            // The mockup's 1920 px frame as fractions of the real width, so other zooms reflow.
            val w = maxWidth / 1920f
            Row(
                Modifier.fillMaxWidth().padding(start = w * 64, end = w * 64, top = 128.mpx).height(60.mpx),
                horizontalArrangement = Arrangement.spacedBy(16.mpx),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.mpx),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (crumb) {
                        Text(stringResource(R.string.common_nav_settings), style = stageText(42, 800, (-1f / 46f).em), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OwnTVIcon(OwnTVIcon.CHEVRON, StageColors.Dim, Modifier.size(30.mpx))
                    }
                    Text(group, style = stageText(42, 800, (-1f / 46f).em), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(count, style = stageText(17, 700), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.mpx))
                Spacer(Modifier.weight(1f))
                StageSearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQuery,
                    placeholder = stringResource(R.string.more_settings_search),
                    modifier = Modifier.widthIn(max = 520.mpx).focusRequester(searchFocus),
                    height = 52.mpx,
                    radius = 18.mpx,
                    horizontalPadding = 20.mpx,
                )
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides edgeScrollSpec) {
                Column(
                    Modifier
                        .padding(start = w * 50, top = 210.mpx)
                        .width(w * 1060)
                        .fillMaxHeight()
                        .focusRequester(rowsFocus)
                        .focusGroup()
                        .verticalScroll(scroll)
                        .padding(bottom = 40.mpx),
                    verticalArrangement = Arrangement.spacedBy(6.mpx),
                    content = rows,
                )
            }
            val help = panel.help
            if (help != null || panelTop != null) {
                Column(
                    Modifier
                        .padding(start = w * 1160, top = 240.mpx)
                        .width(w * 696)
                        .stageGlass(30.mpx)
                        .padding(26.mpx),
                ) {
                    if (panelTop != null) panelTop()
                    if (help != null) SettingPanelBody(help)
                }
            }
        }
    }
}

/** `.helpc h5`: 13/800 caps, +0.13em, dim. */
@Composable
fun SettingPanelHeading(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = stageText(13, 800, 0.13.em), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(bottom = 8.mpx))
}

@Composable
private fun SettingPanelBody(help: SettingHelp) {
    SettingPanelHeading(help.title)
    Text(help.text, style = stageText(17, 500).copy(lineHeight = 27.mpxSpLine()), color = PanelText)
    help.extra?.invoke()
    if (help.choices.isNotEmpty()) {
        Box(Modifier.padding(top = 22.mpx, bottom = 16.mpx).fillMaxWidth().height(1.mpx).background(Color.White.copy(alpha = 0.1f)))
        SettingPanelHeading(stringResource(R.string.settings_panel_choices))
        val a = stageAccent
        help.choices.forEachIndexed { i, label ->
            val on = i == help.chosen
            Row(Modifier.height(44.mpx), horizontalArrangement = Arrangement.spacedBy(12.mpx), verticalAlignment = Alignment.CenterVertically) {
                // `.radio`: a 2 px dim ring; the chosen one a 7 px accent ring.
                Box(
                    Modifier.size(22.mpx).drawBehind {
                        val r = size.minDimension / 2f
                        val ring = (if (on) 7 else 2) * 1.mpx.toPx()
                        drawCircle(if (on) a.accent else StageColors.Dim, radius = r - ring / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(ring))
                    },
                )
                Text(label, style = stageText(18, 600), color = if (on) StageColors.Text else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (i == help.recommended) {
                    Box(Modifier.padding(start = 6.mpx)) { StageTag(stringResource(R.string.settings_panel_recommended).uppercase()) }
                }
            }
        }
    }
    if (help.hints.isNotEmpty()) StageKeyHints(help.hints, Modifier.padding(top = 20.mpx), textSize = 15)
}

private val PanelText = Color(0xFFD3DCD8)

@Composable
private fun Int.mpxSpLine() = with(androidx.compose.ui.platform.LocalDensity.current) { this@mpxSpLine.mpx.toSp() }

/**
 * `.sHead`: "PLAYER 8" above a group's part — 13/800 caps +0.13em in dim, the count darker.
 * [first] = the page's first heading (2 px above instead of 18).
 */
@Composable
fun StageSettingsHeading(text: String, count: Int, first: Boolean = false) {
    Row(
        Modifier.padding(start = 22.mpx, top = if (first) 2.mpx else 18.mpx, bottom = 8.mpx),
        horizontalArrangement = Arrangement.spacedBy(10.mpx),
    ) {
        Text(text.uppercase(), style = stageText(13, 800, 0.13.em), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count.toString(), style = stageText(13, 800, 0.13.em), color = HeadingCount, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val HeadingCount = Color(0xFF4D5955)

/**
 * `.srow`: 84 high, radius 20, padding 0 22, gap 18 — a 24 px muted icon, the title 21/700 over a
 * 15.5 px muted line, and the value on the right. Idle = nothing, focused = FX. A held OK calls
 * [onLongClick] (pin to Quick) without the release also firing [onClick]. [onStep] receives −1 / +1
 * for ◀ ▶ on a stepper or segmented value.
 */
@Composable
fun StageSettingRow(
    icon: OwnTVIcon,
    title: String,
    desc: String?,
    value: SettingValue?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    help: SettingHelp? = null,
    onLongClick: (() -> Unit)? = null,
    onStep: ((Int) -> Unit)? = null,
    /** The small accent dot after the title: this row is also pinned to Quick. */
    pinned: Boolean = false,
    enabled: Boolean = true,
) {
    val a = stageAccent
    val panel = LocalSettingsPanel.current
    var longAt by remember { mutableLongStateOf(0L) }
    StageSurface(
        onClick = { if (android.os.SystemClock.uptimeMillis() - longAt > 800) onClick() },
        radius = StageRadii.Row,
        focusStyle = StageFocus.FX,
        enabled = enabled,
        onLongClick = onLongClick?.let { l -> { longAt = android.os.SystemClock.uptimeMillis(); l() } },
        modifier = modifier
            .fillMaxWidth()
            .height(84.mpx)
            .then(
                if (onStep != null) {
                    Modifier.onPreviewKeyEvent { e ->
                        val step = when (e.key) {
                            Key.DirectionLeft -> -1
                            Key.DirectionRight -> 1
                            else -> 0
                        }
                        if (step != 0 && e.type == KeyEventType.KeyDown) onStep(step)
                        step != 0
                    }
                } else Modifier,
            ),
    ) { focused ->
        // While focused the panel follows this row, value changes included.
        if (focused && panel != null) androidx.compose.runtime.SideEffect { panel.help = help }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.mpx),
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(icon, StageColors.Muted, Modifier.size(24.mpx))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = stageText(21, 700), color = if (enabled) StageColors.Text else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (pinned) Box(Modifier.size(7.mpx).background(a.accent, CircleShape))
                }
                if (!desc.isNullOrBlank()) {
                    Text(
                        desc, style = stageText(15.5f, 500),
                        color = if (focused) FocusedDesc else StageColors.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.mpx),
                    )
                }
            }
            if (value != null) SettingValueView(value)
        }
    }
}

private val FocusedDesc = Color(0xFFD9E6E1)

@Composable
private fun SettingValueView(value: SettingValue) {
    val a = stageAccent
    val valueStyle = stageText(18, 700)
    Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalAlignment = Alignment.CenterVertically) {
        when (value) {
            is SettingValue.Switch -> StageSwitch(value.on)
            is SettingValue.Choice -> {
                Text(value.text, style = valueStyle, color = a.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                OwnTVIcon(OwnTVIcon.CHEVRON_DOWN, StageColors.Muted, Modifier.size(20.mpx))
            }
            is SettingValue.Opens -> {
                if (!value.text.isNullOrBlank()) {
                    Text(value.text, style = valueStyle, color = a.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                OwnTVIcon(OwnTVIcon.CHEVRON, StageColors.Muted, Modifier.size(20.mpx))
            }
            is SettingValue.Stepper -> StageStepper(value.text)
            is SettingValue.Segmented -> SegmentedDisplay(value.options, value.selected)
            is SettingValue.Saved -> {
                Text(value.text, style = valueStyle, color = a.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (value.any) {
                    Text("·", style = valueStyle, color = a.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.offset(x = (-4).mpx))
                    Text(stringResource(R.string.common_reset), style = valueStyle, color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            is SettingValue.Action -> Text(value.text, style = valueStyle, color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            is SettingValue.Custom -> value.content()
        }
    }
}

/** `.seg2` as a row value: drawn only — the row owns focus, ◀ ▶ move the choice. */
@Composable
private fun SegmentedDisplay(options: List<String>, selected: Int) {
    Row(
        Modifier.background(StageColors.ControlFill, RoundedCornerShape(15.mpx)).padding(4.mpx),
        horizontalArrangement = Arrangement.spacedBy(2.mpx),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .height(40.mpx)
                    .then(if (on) Modifier.background(Color.White.copy(alpha = 0.13f), RoundedCornerShape(11.mpx)) else Modifier)
                    .padding(horizontal = 14.mpx),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = stageText(17, 700), color = if (on) StageColors.Text else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `.swrow`: the accent presets, then the colour in use ringed white (P9-04). */
@Composable
fun AccentSwatches(presets: List<Color>, current: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalAlignment = Alignment.CenterVertically) {
        (presets + current).forEachIndexed { i, c ->
            val on = i == presets.size
            Box(
                Modifier
                    .size(30.mpx)
                    .drawBehind {
                        val px = 1.mpx.toPx()
                        val r = size.minDimension / 2f
                        if (on) {
                            drawCircle(Color.Black.copy(alpha = 0.5f), radius = r + 5 * px)
                            drawCircle(Color.White, radius = r + 3 * px)
                        }
                        drawCircle(c, radius = r)
                        if (!on) drawCircle(Color.Black.copy(alpha = 0.25f), radius = r - px, style = androidx.compose.ui.graphics.drawscope.Stroke(2 * px))
                    },
            )
        }
    }
}

/** A short message in place of rows: a bold line and an optional muted one (no search match, empty Quick). */
@Composable
fun StageSettingsNote(title: String, body: String?) {
    Column(Modifier.padding(horizontal = 22.mpx, vertical = 16.mpx)) {
        Text(title, style = stageText(21, 700), color = StageColors.Text)
        if (body != null) Text(body, style = stageText(17, 500), color = StageColors.Muted, modifier = Modifier.padding(top = 6.mpx))
    }
}

/**
 * A search result (P8-02): where the setting lives in dim above its title, and its value muted with ›.
 * Opening it goes to the setting itself.
 */
@Composable
fun StageSearchResultRow(icon: OwnTVIcon, path: String, title: String, value: String?, onClick: () -> Unit) {
    StageSurface(
        onClick = onClick,
        radius = StageRadii.Row,
        focusStyle = StageFocus.FX,
        modifier = Modifier.fillMaxWidth().height(84.mpx),
    ) { _ ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.mpx),
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(icon, StageColors.Muted, Modifier.size(24.mpx))
            Column(Modifier.weight(1f)) {
                Text(path, style = stageText(15.5f, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 3.mpx))
                Text(title, style = stageText(21, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalAlignment = Alignment.CenterVertically) {
                if (!value.isNullOrBlank()) Text(value, style = stageText(18, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OwnTVIcon(OwnTVIcon.CHEVRON, StageColors.Muted, Modifier.size(20.mpx))
            }
        }
    }
}

/** Playlists' panel (P9-03): each playlist's mark, its name and when it last synced. */
@Composable
fun PlaylistSyncList(sources: List<tv.own.owntv.core.database.entity.SourceEntity>) {
    Column(Modifier.padding(top = 18.mpx), verticalArrangement = Arrangement.spacedBy(10.mpx)) {
        sources.forEachIndexed { i, src ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.mpx), verticalAlignment = Alignment.CenterVertically) {
                tv.own.owntv.ui.stage.StagePlaylistMark(tv.own.owntv.ui.stage.PlaylistMark.of(src.name, i))
                Text(src.name, style = stageText(18, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                src.lastSyncAt?.let {
                    Text(
                        stringResource(R.string.settings_synced_at, tv.own.owntv.features.downloads.recordingWhen(it)),
                        style = stageText(15, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
