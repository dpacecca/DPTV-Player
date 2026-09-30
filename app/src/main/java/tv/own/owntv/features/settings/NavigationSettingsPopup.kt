package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import tv.own.owntv.core.settings.SettingsRepository.NavHideAfter
import tv.own.owntv.core.settings.SettingsRepository.NavStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › Layout › Navigation (P1-06): how the rail behaves. Four styles as radio rows, then the
 * rail's hide delay (OK steps 2 → 4 → 8 s), whether the open rail shows counts (OK toggles), and the
 * existing Sidebar Menu Customization screen ([onOpenMenuItems]).
 */
@Composable
fun NavigationSettingsPopup(
    style: NavStyle,
    onStyle: (NavStyle) -> Unit,
    hideAfterSecs: Int,
    onHideAfterSecs: (Int) -> Unit,
    showCounts: Boolean,
    onShowCounts: (Boolean) -> Unit,
    menuItemsValue: String,
    onOpenMenuItems: () -> Unit,
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
        NavStyle.FLOATING_AUTO_HIDE to (R.string.settings_nav_floating_auto to R.string.settings_nav_floating_auto_desc),
        NavStyle.FLOATING to (R.string.settings_nav_floating to R.string.settings_nav_floating_desc),
        NavStyle.DOCKED_ICONS to (R.string.settings_nav_docked to R.string.settings_nav_docked_desc),
        NavStyle.DOCKED_LABELS to (R.string.settings_nav_docked_labels to R.string.settings_nav_docked_labels_desc),
    )

    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(
            Modifier.fillMaxSize().background(Color(2, 5, 6).copy(alpha = 0.55f)).trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .padding(top = 120.mpx)
                    .width(880.mpx)
                    .stageGlass(30.mpx, overContent = true)
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
                    title = stringResource(R.string.settings_nav_hide_after),
                    subtitle = stringResource(R.string.settings_nav_hide_after_desc),
                    value = pluralStringResource(R.plurals.settings_nav_hide_after_seconds, hideAfterSecs, hideAfterSecs),
                    onClick = {
                        val choices = NavHideAfter.CHOICES
                        onHideAfterSecs(choices[(choices.indexOf(hideAfterSecs) + 1) % choices.size])
                    },
                    leading = { OwnTVIcon(OwnTVIcon.CLOCK, StageColors.Text, Modifier.size(21.mpx)) },
                )
                NavOption(
                    title = stringResource(R.string.settings_nav_show_counts),
                    subtitle = stringResource(R.string.settings_nav_show_counts_desc),
                    value = stringResource(if (showCounts) R.string.common_on else R.string.common_off),
                    onClick = { onShowCounts(!showCounts) },
                    leading = { OwnTVIcon(OwnTVIcon.LIST, StageColors.Text, Modifier.size(21.mpx)) },
                )
                NavOption(
                    title = stringResource(R.string.settings_nav_menu_items),
                    subtitle = stringResource(R.string.settings_nav_menu_items_desc, stringResource(R.string.settings_sidebar_customization)),
                    value = menuItemsValue,
                    onClick = onOpenMenuItems,
                    leading = { OwnTVIcon(OwnTVIcon.GRID, StageColors.Text, Modifier.size(21.mpx)) },
                )
            }
        }
    }
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
