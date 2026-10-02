package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.settings.SettingsRepository.NavHideAfter
import tv.own.owntv.core.settings.SettingsRepository.NavLength
import tv.own.owntv.core.settings.SettingsRepository.NavMenuMode
import tv.own.owntv.core.settings.SettingsRepository.NavSize
import tv.own.owntv.core.settings.SettingsRepository.NavStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StagePill
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › Layout › Navigation (P1-06, P1-18, P1-19): Floating or Docked as radio rows, then the rail's
 * Size and Length (Docked + Compact adds Widen on focus, owner 2026-10-02), Menu items (Dynamic / Static; Static lists the sections as toggles) and, for Floating
 * only, the hide delay. OK on a value row steps to its next choice.
 */
@Composable
fun NavigationSettingsPopup(
    style: NavStyle,
    onStyle: (NavStyle) -> Unit,
    size: NavSize,
    onSize: (NavSize) -> Unit,
    length: NavLength,
    onLength: (NavLength) -> Unit,
    widen: NavSize?,
    onWiden: (NavSize?) -> Unit,
    hideAfterMs: Int,
    onHideAfterMs: (Int) -> Unit,
    menuMode: NavMenuMode,
    onMenuMode: (NavMenuMode) -> Unit,
    hiddenSections: Set<MainSection>,
    onSectionHidden: (MainSection, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler { onDismiss() }
    val locale = LocalConfiguration.current.locales[0]
    val eyebrow = stringResource(
        R.string.settings_breadcrumb_eyebrow,
        stringResource(R.string.settings_title),
        stringResource(R.string.settings_group_layout),
    ).uppercase(locale)
    val styles = listOf(
        NavStyle.FLOATING to (R.string.settings_nav_floating to R.string.settings_nav_floating_desc),
        NavStyle.DOCKED to (R.string.settings_nav_docked to R.string.settings_nav_docked_desc),
    )

    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(
            Modifier.fillMaxSize().background(Color(2, 5, 6).copy(alpha = 0.55f)).trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(880.mpx)
                    .stageGlass(30.mpx, overContent = true)
                    // Static's section toggles make it tall: at large zooms it scrolls instead of clipping.
                    .verticalScroll(rememberScrollState())
                    .padding(30.mpx),
            ) {
                Text(eyebrow, style = stageText(15, 800, 0.12.em), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.settings_navigation),
                    style = stageText(38, 800),
                    color = StageColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.mpx, bottom = 22.mpx),
                )
                styles.forEachIndexed { i, (value, text) ->
                    NavOption(
                        title = stringResource(text.first),
                        subtitle = stringResource(text.second),
                        onClick = { onStyle(value) },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        leading = { focused -> Radio(on = value == style, focused = focused) },
                    )
                }
                Box(Modifier.padding(vertical = 18.mpx).fillMaxWidth().height(1.mpx).background(Color.White.copy(alpha = 0.10f)))
                NavOption(
                    title = stringResource(R.string.settings_size),
                    subtitle = stringResource(R.string.settings_nav_size_desc),
                    value = stringResource(size.labelRes),
                    onClick = { onSize(NavSize.entries[(size.ordinal + 1) % NavSize.entries.size]) },
                    leading = { OwnTVIcon(OwnTVIcon.EXPAND, StageColors.Text, Modifier.size(21.mpx)) },
                )
                NavOption(
                    title = stringResource(R.string.settings_nav_length),
                    subtitle = stringResource(R.string.settings_nav_length_desc),
                    value = stringResource(if (length == NavLength.FIT) R.string.settings_nav_length_fit else R.string.settings_nav_length_full),
                    onClick = { onLength(if (length == NavLength.FIT) NavLength.FULL else NavLength.FIT) },
                    leading = { OwnTVIcon(OwnTVIcon.SORT, StageColors.Text, Modifier.size(21.mpx)) },
                )
                // Docked + Compact: the size the rail opens to, over the content, while it has focus.
                if (style == NavStyle.DOCKED && size == NavSize.COMPACT) {
                    val widths = listOf(null, NavSize.NORMAL, NavSize.WIDE, NavSize.EXTRA_WIDE)
                    NavOption(
                        title = stringResource(R.string.settings_nav_widen),
                        subtitle = stringResource(R.string.settings_nav_widen_desc),
                        value = widen?.let { stringResource(it.labelRes) } ?: stringResource(R.string.common_off),
                        onClick = { onWiden(widths[(widths.indexOf(widen) + 1) % widths.size]) },
                        leading = { OwnTVIcon(OwnTVIcon.EXPAND, StageColors.Text, Modifier.size(21.mpx)) },
                    )
                }
                val static = menuMode == NavMenuMode.STATIC
                NavOption(
                    title = stringResource(R.string.settings_nav_menu_items),
                    subtitle = stringResource(R.string.settings_nav_menu_items_desc),
                    value = stringResource(if (static) R.string.settings_static else R.string.settings_dynamic),
                    onClick = { onMenuMode(if (static) NavMenuMode.DYNAMIC else NavMenuMode.STATIC) },
                    leading = { OwnTVIcon(OwnTVIcon.GRID, StageColors.Text, Modifier.size(21.mpx)) },
                )
                // Static: the browse sections as toggles, in rail order; Search and More can never be hidden.
                if (static) {
                    FlowRow(
                        Modifier.padding(start = 61.mpx, end = 22.mpx, top = 6.mpx, bottom = 14.mpx),
                        horizontalArrangement = Arrangement.spacedBy(12.mpx),
                        verticalArrangement = Arrangement.spacedBy(12.mpx),
                    ) {
                        MenuSections.forEach { (section, icon) ->
                            val shown = section !in hiddenSections
                            StagePill(
                                text = stringResource(section.labelRes),
                                onClick = { onSectionHidden(section, shown) },
                                icon = icon,
                                trailingIcon = if (shown) OwnTVIcon.CHECK else OwnTVIcon.EYE_OFF,
                                dimmed = !shown,
                            )
                        }
                    }
                }
                if (style == NavStyle.FLOATING) {
                    NavOption(
                        title = stringResource(R.string.settings_nav_hide_after),
                        subtitle = stringResource(R.string.settings_nav_hide_after_desc),
                        value = if (hideAfterMs < 1000) {
                            stringResource(R.string.settings_nav_hide_after_half)
                        } else {
                            pluralStringResource(R.plurals.settings_nav_hide_after_seconds, hideAfterMs / 1000, hideAfterMs / 1000)
                        },
                        onClick = {
                            val choices = NavHideAfter.CHOICES_MS
                            onHideAfterMs(choices[(choices.indexOf(hideAfterMs) + 1) % choices.size])
                        },
                        leading = { OwnTVIcon(OwnTVIcon.CLOCK, StageColors.Text, Modifier.size(21.mpx)) },
                    )
                }
            }
        }
    }
}

private val MenuSections = listOf(
    MainSection.HOME to OwnTVIcon.HOME,
    MainSection.LIVE_TV to OwnTVIcon.LIVE_TV,
    MainSection.EPG to OwnTVIcon.EPG,
    MainSection.MOVIES to OwnTVIcon.MOVIES,
    MainSection.SERIES to OwnTVIcon.SERIES,
    MainSection.DOWNLOADS to OwnTVIcon.DOWNLOADS,
)

internal val NavSize.labelRes: Int
    get() = when (this) {
        NavSize.COMPACT -> R.string.settings_nav_size_compact
        NavSize.NORMAL -> R.string.settings_nav_size_normal
        NavSize.WIDE -> R.string.settings_nav_size_wide
        NavSize.EXTRA_WIDE -> R.string.settings_nav_size_extra_wide
    }

/** `.opt`: 76 high, a title 21/600 over a 15 px muted line, an optional accent value with ›; focused = FX. */
@Composable
private fun NavOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    leading: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val a = stageAccent
    StageSurface(
        onClick = onClick,
        radius = 18.mpx,
        modifier = modifier.fillMaxWidth().height(76.mpx),
        focusStyle = StageFocus.FX,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.mpx),
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading(focused)
            Column(Modifier.weight(1f)) {
                Text(title, style = stageText(21, 600), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = stageText(15, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (value != null) {
                Text("$value ›", style = stageText(18, 700), color = a.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `.radio`: 24 px, a 2 px dim ring; chosen = a 7 px accent ring (white on the focused row). */
@Composable
private fun Radio(on: Boolean, focused: Boolean) {
    val a = stageAccent
    Box(
        Modifier.size(24.mpx).drawBehind {
            val r = size.minDimension / 2f
            if (on) drawInnerRing(if (focused) Color.White else a.accent, 7.mpx.toPx(), r)
            else drawInnerRing(StageColors.Dim, 2.mpx.toPx(), r)
        },
    )
}
