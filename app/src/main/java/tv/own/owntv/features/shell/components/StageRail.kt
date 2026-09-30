package tv.own.owntv.features.shell.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.ui.components.BrandMark
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.rememberAppliedIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.drawBoxShadow
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.ownTvTween
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText
import java.text.NumberFormat

/** What the Stage rail shows right now: the Navigation setting plus where focus is (see OwnTVShell). */
enum class RailState {
    /** A 7 px accent glow at the screen edge; the items stay focusable so ◀ can still reach them. */
    HIDDEN,
    /** The 84 px icon capsule. */
    CAPSULE,
    /** Labels, counts and the profile, over a scrim: the rail while it holds focus. */
    OPEN,
    /** Open, without the scrim: Navigation = Docked with labels. */
    PINNED,
}

/** The mockup's rail order (`screens.js` `cRail`): Search first, the Guide right after Live TV, More last. */
private val StageRailOrder = listOf(
    MainSection.SEARCH, MainSection.HOME, MainSection.LIVE_TV, MainSection.EPG,
    MainSection.MOVIES, MainSection.SERIES, MainSection.DOWNLOADS,
)

/** The space a docked rail takes from the content, from the screen edge: 28 + 84 capsule, 24 + 340 open. */
val RailCapsuleReserve = (28 + 84 + 16).mpx
val RailPinnedReserve = (24 + 340 + 16).mpx

/**
 * Where content starts below the top-right cluster (34 + its 68 height + a gap) on screens that have not
 * been redrawn for Stage yet. Each screen's own phase takes this away as it gets the full canvas.
 */
val StageContentTop = 110.mpx

/**
 * The Stage navigation rail (P1-01 … P1-05): a floating glass capsule, the open rail over a scrim,
 * or only the accent glow at the edge. [count] is the number beside an item in the open rail, null
 * for none. Entering the rail from the content always lands on the selected section, and ▶ out of it
 * returns to exactly where the content was ([contentFocusRequester] restores it).
 */
@Composable
fun StageRail(
    state: RailState,
    selected: MainSection,
    visibleSections: Set<MainSection>,
    onSelect: (MainSection) -> Unit,
    count: (MainSection) -> Int?,
    profileName: String,
    profileLine: String,
    onSwitchProfile: () -> Unit,
    onPickAvatar: () -> Unit,
    selectedItemFocusRequester: FocusRequester,
    contentFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    nowPlaying: NowPlayingRail?,
    onNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = state == RailState.OPEN || state == RailState.PINNED
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    var hasFocus by remember { mutableStateOf(false) }
    // Search, Settings and More all land on a rail item: Search has its own, Settings lives behind More.
    val focusSection = when {
        selected == MainSection.SETTINGS || selected == MainSection.MORE -> MainSection.MORE
        selected == MainSection.SEARCH || selected in visibleSections -> selected
        else -> StageRailOrder.firstOrNull { it in visibleSections } ?: MainSection.MORE
    }
    val items = StageRailOrder.filter { it == MainSection.SEARCH || it in visibleSections } + MainSection.MORE

    // Slide in from the edge (320 ms, or a snap with animations off): the capsule when it leaves
    // HIDDEN, the open rail each time it opens.
    val slide = remember { Animatable(0f) }
    val slideSpec: androidx.compose.animation.core.AnimationSpec<Float> = ownTvTween(320)
    LaunchedEffect(open, state == RailState.HIDDEN) {
        val target = if (state == RailState.HIDDEN) -1f else 0f
        if (open) slide.snapTo(-1f)
        slide.animateTo(target, slideSpec)
    }

    Box(modifier.fillMaxSize()) {
        if (state == RailState.OPEN) {
            Box(Modifier.fillMaxSize().background(Color(2, 5, 6).copy(alpha = 0.55f)))
        }
        if (state == RailState.HIDDEN) RailEdgeGlow(Modifier.align(Alignment.CenterStart))

        val width = if (open) 340.mpx else 84.mpx
        val edge = if (open) 24.mpx else 28.mpx
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = edge)
                .width(width)
                .graphicsLayer {
                    val travel = (width + edge).toPx()
                    translationX = slide.value * travel * (if (rtl) -1f else 1f)
                }
                // Fades as it slides out, so its shadow never lingers at the edge once hidden.
                .alpha(if (state == RailState.HIDDEN) (1f + slide.value).coerceIn(0f, 1f) else 1f)
                .onFocusChanged {
                    // Every entry from the content lands on the selected section, not on whatever item
                    // happens to line up; moves inside the rail do not re-trigger this.
                    val entered = it.hasFocus && !hasFocus
                    hasFocus = it.hasFocus
                    if (it.hasFocus) onFocused()
                    if (entered) scope.launch { runCatching { selectedItemFocusRequester.requestFocus() } }
                }
                .focusProperties {
                    onExit = {
                        val out = if (rtl) FocusDirection.Left else FocusDirection.Right
                        if (requestedFocusDirection == out && runCatching { contentFocusRequester.requestFocus() }.getOrDefault(false)) {
                            cancelFocusChange()
                        }
                    }
                }
                .focusGroup()
                .stageGlass(if (open) 34.mpx else 42.mpx, overContent = true)
                .padding(
                    horizontal = if (open) 16.mpx else 0.mpx,
                    vertical = if (open) 22.mpx else 16.mpx,
                ),
            horizontalAlignment = if (open) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.mpx),
        ) {
            RailBrand(open)
            if (nowPlaying != null) {
                RailItem(
                    icon = if (nowPlaying.audioMode) OwnTVIcon.EQ else OwnTVIcon.PLAY,
                    label = stringResource(R.string.shell_now_playing),
                    trailing = if (nowPlaying.audioMode) stringResource(R.string.shell_now_playing_audio) else null,
                    open = open,
                    active = false,
                    accentIcon = true,
                    onClick = onNowPlaying,
                )
                RailSeparator(open)
            }
            items.forEach { section ->
                RailItem(
                    icon = section.stageIcon,
                    label = stringResource(section.labelRes),
                    trailing = count(section)?.let { NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(it) },
                    open = open,
                    active = section == selected ||
                        (section == MainSection.MORE && selected == MainSection.SETTINGS),
                    onClick = { onSelect(section) },
                    modifier = if (section == focusSection) Modifier.focusRequester(selectedItemFocusRequester) else Modifier,
                )
            }
            RailSeparator(open)
            RailProfile(open, profileName, profileLine, onSwitchProfile, onPickAvatar)
        }
    }
}

private val MainSection.stageIcon: OwnTVIcon
    get() = when (this) {
        MainSection.SEARCH -> OwnTVIcon.SEARCH
        MainSection.HOME -> OwnTVIcon.HOME
        MainSection.LIVE_TV -> OwnTVIcon.LIVE_TV
        MainSection.EPG -> OwnTVIcon.EPG
        MainSection.MOVIES -> OwnTVIcon.MOVIES
        MainSection.SERIES -> OwnTVIcon.SERIES
        MainSection.DOWNLOADS -> OwnTVIcon.DOWNLOADS
        MainSection.MORE, MainSection.SETTINGS -> OwnTVIcon.TILES
    }

/** `.edge`: 7 × 150 at the screen edge, accent, glowing 22 px at 80% and 60 px at 35%, 90% opaque. */
@Composable
private fun RailEdgeGlow(modifier: Modifier) {
    val accent = stageAccent.accent
    Box(
        modifier
            .size(7.mpx, 150.mpx)
            .alpha(0.9f)
            .drawBehind {
                val r = 7.mpx.toPx()
                drawBoxShadow(accent.copy(alpha = 0.35f), 60.mpx.toPx(), r)
                drawBoxShadow(accent.copy(alpha = 0.8f), 22.mpx.toPx(), r)
                // border-radius: 0 7 7 0 — square against the screen edge, round on the inside.
                drawRoundRect(accent, cornerRadius = CornerRadius(r))
                drawRect(accent, size = size.copy(width = size.width / 2f))
            },
    )
}

/** The mark (50), with the wordmark beside it when the rail is open (`.brandrow`, 0 10 18 padding). */
@Composable
private fun ColumnScope.RailBrand(open: Boolean) {
    val icon = rememberAppliedIcon()
    if (!open) {
        BrandMark(icon, 50.mpx, Modifier.padding(bottom = 12.mpx))
        return
    }
    val colors = OwnTVTheme.colors
    Row(
        Modifier.padding(start = 10.mpx, end = 10.mpx, bottom = 18.mpx),
        horizontalArrangement = Arrangement.spacedBy(14.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(icon, 50.mpx)
        // Interim wordmark (the live-text lockup) until the Brand phase brings the #227 one (P11, D5).
        Text(
            buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = colors.textPrimary)) { append(stringResource(R.string.brand_own)) }
                withStyle(androidx.compose.ui.text.SpanStyle(color = Color(if (colors.isDark) icon.accent else icon.accentOnLight))) {
                    append(stringResource(R.string.brand_tv))
                }
            },
            style = stageText(36, 800, (-0.025).em),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RailSeparator(open: Boolean) {
    Box(
        Modifier
            .then(if (open) Modifier.fillMaxWidth().padding(horizontal = 14.mpx, vertical = 10.mpx) else Modifier.padding(vertical = 8.mpx).width(34.mpx))
            .height(1.mpx)
            .background(Color.White.copy(alpha = 0.12f)),
    )
}

/**
 * `.frail .it`: a 58 × 58 icon tile in the capsule, a 60 high row with its label and count when open.
 * Active = accent icon on accent 14%, plus the glowing dot outside the capsule; focused = FILLED.
 */
@Composable
private fun RailItem(
    icon: OwnTVIcon,
    label: String,
    trailing: String?,
    open: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentIcon: Boolean = false,
) {
    val a = stageAccent
    val r = 20.mpx
    StageSurface(
        onClick = onClick,
        radius = r,
        modifier = modifier.then(if (open) Modifier.fillMaxWidth().height(60.mpx) else Modifier.size(58.mpx)),
        idle = if (active) Modifier.background(a.accent.copy(alpha = 0.14f), RoundedCornerShape(r)) else Modifier,
        contentAlignment = if (open) Alignment.CenterStart else Alignment.Center,
    ) { focused ->
        val iconTint = when {
            focused -> a.onAccent
            active || accentIcon -> a.accent
            else -> Color(0xFFC4CFCA)
        }
        if (!open) {
            OwnTVIcon(icon, iconTint, Modifier.size(27.mpx))
            if (active && !focused) RailActiveDot(Modifier.align(Alignment.CenterEnd).offset(x = 9.mpx))
            return@StageSurface
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.mpx),
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(icon, iconTint, Modifier.size(26.mpx))
            Text(
                label,
                style = stageText(21, 600),
                color = if (focused) a.onAccent else if (active) a.accent else StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(trailing, style = stageText(16, 600), color = if (focused) a.onAccent else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `.it.on::after`: 6 px, 9 px outside the capsule's item, glowing 10 px. */
@Composable
private fun RailActiveDot(modifier: Modifier) {
    val accent = stageAccent.accent
    Box(
        modifier
            .size(6.mpx)
            .drawBehind {
                drawBoxShadow(accent, 10.mpx.toPx(), size.minDimension / 2f)
                drawCircle(accent)
            },
    )
}

/**
 * The profile: the 46 px avatar alone in the capsule, avatar + name + "All playlists · switch ›" when
 * open. OK switches profile; a long press still changes the avatar picture.
 */
@Composable
private fun RailProfile(open: Boolean, name: String, line: String, onSwitchProfile: () -> Unit, onPickAvatar: () -> Unit) {
    val a = stageAccent
    val initial = name.trim().take(1).uppercase().ifEmpty { "?" }
    StageSurface(
        onClick = onSwitchProfile,
        onLongClick = onPickAvatar,
        radius = if (open) 20.mpx else 23.mpx,
        modifier = if (open) Modifier.fillMaxWidth() else Modifier.padding(top = 4.mpx).size(46.mpx),
        contentAlignment = if (open) Alignment.CenterStart else Alignment.Center,
    ) { focused ->
        if (!open) {
            RailAvatar(initial)
            return@StageSurface
        }
        Row(
            Modifier.padding(start = 12.mpx, end = 12.mpx, top = 10.mpx, bottom = 6.mpx),
            horizontalArrangement = Arrangement.spacedBy(14.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailAvatar(initial)
            Column {
                Text(name, style = stageText(19, 700), color = if (focused) a.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$line ›", style = stageText(15, 400), color = if (focused) a.onAccent else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `.frail .av`: the profile initial on the orange gradient, 20/800 in #2A0D12. */
@Composable
private fun RailAvatar(initial: String) {
    Box(
        Modifier
            .size(46.mpx)
            .background(Brush.linearGradient(listOf(Color(0xFFFFB35C), Color(0xFFFF5F7A))), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = stageText(20, 800), color = Color(0xFF2A0D12), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
