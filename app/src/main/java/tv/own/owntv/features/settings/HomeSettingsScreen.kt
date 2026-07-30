package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.features.home.HeroKind
import tv.own.owntv.features.home.HomeLiveRowMode
import tv.own.owntv.features.home.HomeRow
import tv.own.owntv.features.home.displayTitle
import tv.own.owntv.features.home.displayLabel
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.trapVerticalFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme

@Composable
fun HomeSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: HomeSettingsViewModel = koinViewModel()
    val settingsVm: SettingsViewModel = koinViewModel()
    val config by vm.config.collectAsStateWithLifecycle()
    val androidTvHomeEnabled by settingsVm.androidTvHomeEnabled.collectAsStateWithLifecycle()
    val tvHomeRefresh by settingsVm.tvHomeRefresh.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    val firstFocus = remember { FocusRequester() }
    // onEnter alone can miss when entering this screen: the first row lives inside a LazyColumn and may
    // not be composed/attached the instant focus crosses in, so focus falls back to the sidebar. Request
    // it once after first layout (matches VideoPlayerSettingsScreen); onEnter still covers dialog returns.
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }

    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .focusProperties {
                onEnter = { runCatching { firstFocus.requestFocus() } }
            }
            .focusGroup()
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Text(stringResource(R.string.settings_home_screen), style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_home_description),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            // Pin vertical focus inside the section list — a held Up/Down that outruns composition
            // would otherwise escape to the header / sidebar (every other browse list traps this).
            modifier = Modifier.weight(1f).fillMaxWidth().trapVerticalFocusExit(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(stringResource(R.string.settings_sections), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_hidden_sections),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }

            itemsIndexed(config.settingsRows, key = { _, row -> row.name }) { index, row ->
                HomeRowCard(
                    row = row,
                    hidden = row in config.hidden,
                    canMoveUp = index > 0,
                    canMoveDown = index < config.settingsRows.lastIndex,
                    onMoveUp = { vm.move(row, up = true) },
                    onMoveDown = { vm.move(row, up = false) },
                    onMoveTop = { vm.moveToEdge(row, top = true) },
                    onMoveBottom = { vm.moveToEdge(row, top = false) },
                    onToggleHidden = { vm.setRowHidden(row, row !in config.hidden) },
                    liveMode = when (row) {
                        HomeRow.RECENT_CHANNELS -> config.recentLiveMode
                        HomeRow.FAVORITE_CHANNELS -> config.favoriteLiveMode
                        else -> null
                    },
                    onToggleLiveMode = { mode -> vm.setLiveRowMode(row, mode.toggled()) },
                    firstButtonModifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }

            item {
                Spacer(Modifier.height(14.dp))
                GroupLabel(stringResource(R.string.settings_keep_watching))
            }

            item {
                Row2(
                    icon = OwnTVIcon.LIVE_TV,
                    title = stringResource(R.string.settings_live_keep_watching),
                    desc = stringResource(R.string.settings_live_keep_watching_description),
                    chip = if (config.heroIncludeLive) stringResource(R.string.common_on) else stringResource(R.string.common_off),
                    primaryChip = config.heroIncludeLive,
                    onClick = { vm.setHeroInclude(HeroKind.LIVE, !config.heroIncludeLive) },
                )
            }
            item {
                Row2(
                    icon = OwnTVIcon.MOVIES,
                    title = stringResource(R.string.settings_movies_keep_watching),
                    desc = stringResource(R.string.settings_movies_keep_watching_description),
                    chip = if (config.heroIncludeMovies) stringResource(R.string.common_on) else stringResource(R.string.common_off),
                    primaryChip = config.heroIncludeMovies,
                    onClick = { vm.setHeroInclude(HeroKind.MOVIES, !config.heroIncludeMovies) },
                )
            }
            item {
                Row2(
                    icon = OwnTVIcon.SERIES,
                    title = stringResource(R.string.settings_series_keep_watching),
                    desc = stringResource(R.string.settings_series_keep_watching_description),
                    chip = if (config.heroIncludeSeries) stringResource(R.string.common_on) else stringResource(R.string.common_off),
                    primaryChip = config.heroIncludeSeries,
                    onClick = { vm.setHeroInclude(HeroKind.SERIES, !config.heroIncludeSeries) },
                )
            }

            item {
                Spacer(Modifier.height(6.dp))
                GroupLabel(stringResource(R.string.settings_android_tv_home))
            }
            item {
                Row2(
                    icon = OwnTVIcon.HISTORY,
                    title = stringResource(R.string.settings_android_tv_home),
                    desc = stringResource(R.string.settings_android_tv_home_description),
                    chip = if (androidTvHomeEnabled) stringResource(R.string.common_on) else stringResource(R.string.common_off),
                    primaryChip = androidTvHomeEnabled,
                    onClick = { settingsVm.setAndroidTvHomeEnabled(!androidTvHomeEnabled) },
                )
            }
            if (androidTvHomeEnabled) {
                item {
                    Row2(
                        icon = OwnTVIcon.SHARE,
                        title = stringResource(R.string.settings_refresh_now),
                        desc = stringResource(R.string.settings_refresh_description),
                        chip = when (tvHomeRefresh) {
                            SettingsViewModel.TvHomeRefresh.REFRESHING -> stringResource(R.string.settings_rebuilding)
                            SettingsViewModel.TvHomeRefresh.DONE -> stringResource(R.string.settings_done_check)
                            else -> null
                        },
                        onClick = {
                            if (tvHomeRefresh == SettingsViewModel.TvHomeRefresh.IDLE) {
                                settingsVm.refreshAndroidTvHome()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeRowCard(
    row: HomeRow,
    hidden: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveTop: () -> Unit,
    onMoveBottom: () -> Unit,
    onToggleHidden: () -> Unit,
    liveMode: HomeLiveRowMode?,
    onToggleLiveMode: (HomeLiveRowMode) -> Unit,
    firstButtonModifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.displayTitle(),
                style = MaterialTheme.typography.titleSmall,
                color = if (hidden) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
            )
            if (hidden) {
                Text(
                    stringResource(R.string.settings_hidden),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (liveMode != null) {
            OwnTVButton(
                label = stringResource(R.string.settings_mode, liveMode.displayLabel()),
                onClick = { onToggleLiveMode(liveMode) },
                style = OwnTVButtonStyle.SECONDARY,
            )
            Spacer(Modifier.width(6.dp))
        }
        OwnTVButton("⤒", onClick = onMoveTop, style = OwnTVButtonStyle.SECONDARY, enabled = canMoveUp)
        Spacer(Modifier.width(6.dp))
        OwnTVButton("↑", onClick = onMoveUp, style = OwnTVButtonStyle.SECONDARY, enabled = canMoveUp)
        Spacer(Modifier.width(6.dp))
        OwnTVButton("↓", onClick = onMoveDown, style = OwnTVButtonStyle.SECONDARY, enabled = canMoveDown)
        Spacer(Modifier.width(6.dp))
        OwnTVButton("⤓", onClick = onMoveBottom, style = OwnTVButtonStyle.SECONDARY, enabled = canMoveDown)
        Spacer(Modifier.width(6.dp))
        OwnTVButton(
            label = stringResource(if (hidden) R.string.common_show else R.string.common_hide),
            onClick = onToggleHidden,
            modifier = firstButtonModifier,
            style = OwnTVButtonStyle.SECONDARY,
        )
    }
}
