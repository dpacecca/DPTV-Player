package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.features.home.displayLabel
import tv.own.owntv.features.home.displayTitle
import tv.own.owntv.features.home.settingsDescription
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › Layout › Home screen (P10B-01): the Home rows (shown or hidden, in Home's order; the focused
 * row's Hide / Move / Mode in the panel, Hold OK = Move), then Android TV home. Now trending and Keep
 * watching are gone from Home since v2.4.0; Trakt's rows are set up under Content & metadata.
 */
@Composable
fun HomeSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: HomeSettingsViewModel = koinViewModel()
    val settingsVm: SettingsViewModel = koinViewModel()
    val config by vm.config.collectAsStateWithLifecycle()
    val androidTvHomeEnabled by settingsVm.androidTvHomeEnabled.collectAsStateWithLifecycle()
    val tvHomeRefresh by settingsVm.tvHomeRefresh.collectAsStateWithLifecycle()

    val rowsFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }
    val rowFocus = remember { HomeRow.entries.associateWith { FocusRequester() } }
    // The row being moved with ▲ ▼ (Hold OK, or Move in the panel); OK or Back ends it.
    var moving by remember { mutableStateOf<HomeRow?>(null) }
    // The row whose actions the panel shows while focus is in them.
    var acting by remember { mutableStateOf<HomeRow?>(null) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { rowsFocus.requestFocus() } }

    val ok = stringResource(R.string.common_ok)
    val back = stringResource(R.string.common_back)
    val layout = stringResource(R.string.settings_group_layout)
    val rowCount = config.settingsRows.size
    StageFullPage(
        parents = listOf(layout),
        title = stringResource(R.string.settings_home_screen),
        count = pluralStringResource(R.plurals.settings_home_row_count, rowCount, rowCount),
        onBack = onBack,
        modifier = modifier,
        rowsFocus = rowsFocus,
        handleBack = moving == null,
    ) {
        // Now trending and Keep watching left Home in v2.4.0 (Trakt's rows replace the first), and their
        // settings with them.
        StageSettingsHeading(stringResource(R.string.settings_sections), config.settingsRows.size, first = true)
        val hiddenNote = stringResource(R.string.settings_hidden_sections)
        config.settingsRows.forEach { row ->
            key(row) {
                val hidden = row in config.hidden
                val liveMode = when (row) {
                    HomeRow.RECENT_CHANNELS -> config.recentLiveMode
                    HomeRow.FAVORITE_CHANNELS -> config.favoriteLiveMode
                    else -> null
                }
                val focus = rowFocus.getValue(row)
                val startMove: () -> Unit = { moving = row; acting = null; runCatching { focus.requestFocus() } }
                val actions = listOfNotNull(
                    StageAction(
                        if (hidden) OwnTVIcon.PLAY_CIRCLE else OwnTVIcon.EYE_OFF,
                        stringResource(if (hidden) R.string.settings_home_show_row else R.string.settings_home_hide_row),
                        { vm.setRowHidden(row, !hidden) },
                    ),
                    StageAction(OwnTVIcon.MOVE, stringResource(R.string.settings_home_move_row), startMove),
                    liveMode?.let { mode -> StageAction(OwnTVIcon.GRID, mode.displayLabel(), { vm.setLiveRowMode(row, mode.toggled()) }) },
                )
                val isMoving = moving == row
                val title = row.displayTitle()
                val hiddenWord = stringResource(R.string.settings_hidden)
                StageSettingRow(
                    icon = row.icon,
                    title = title,
                    desc = if (hidden) listOfNotNull(hiddenWord, liveMode?.displayLabel()).joinToString(dotSeparator())
                        else row.settingsDescription(),
                    value = SettingValue.Custom {
                        Text(
                            if (hidden) hiddenWord else stringResource(R.string.settings_home_shown),
                            style = stageText(18, 700),
                            color = if (hidden) StageColors.Dim else stageAccent.accent,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { if (isMoving) moving = null else { acting = row; runCatching { actionsFocus.requestFocus() } } },
                    onLongClick = startMove,
                    marked = isMoving,
                    keepPanel = acting == row,
                    help = SettingHelp(
                        title = title,
                        text = row.settingsDescription() + "\n" + hiddenNote,
                        hints = if (isMoving) {
                            listOf("▲ ▼" to stringResource(R.string.content_move), ok to stringResource(R.string.settings_done))
                        } else {
                            listOf(
                                "▶" to stringResource(R.string.settings_key_actions),
                                stringResource(R.string.content_key_hold_ok) to stringResource(R.string.content_move),
                                back to layout,
                            )
                        },
                        extra = { if (!isMoving) StageActionColumn(actions, focus, actionsFocus) },
                    ),
                    modifier = Modifier
                        .focusRequester(focus)
                        .focusProperties { if (!isMoving) right = actionsFocus }
                        .onFocusChanged { if (it.isFocused) acting = null }
                        .onPreviewKeyEvent { e ->
                            if (!isMoving) return@onPreviewKeyEvent false
                            val up = when (e.key) {
                                Key.DirectionUp -> true
                                Key.DirectionDown -> false
                                else -> return@onPreviewKeyEvent e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                            }
                            if (e.type == KeyEventType.KeyDown) vm.move(row, up)
                            true
                        },
                )
            }
        }

        StageSettingsHeading(stringResource(R.string.settings_android_tv_home), if (androidTvHomeEnabled) 2 else 1)
        HomeSwitchRow(OwnTVIcon.HISTORY, R.string.settings_android_tv_home, R.string.settings_android_tv_home_description, androidTvHomeEnabled) {
            settingsVm.setAndroidTvHomeEnabled(!androidTvHomeEnabled)
        }
        if (androidTvHomeEnabled) {
            val refreshTitle = stringResource(R.string.settings_refresh_now)
            val refreshValue = when (tvHomeRefresh) {
                SettingsViewModel.TvHomeRefresh.REFRESHING -> SettingValue.Action(stringResource(R.string.settings_rebuilding))
                SettingsViewModel.TvHomeRefresh.DONE -> SettingValue.Action(stringResource(R.string.settings_done_check))
                else -> null
            }
            val refreshDesc = stringResource(R.string.settings_refresh_description)
            StageSettingRow(
                icon = OwnTVIcon.REFRESH,
                title = refreshTitle,
                desc = refreshDesc,
                value = refreshValue,
                onClick = { if (tvHomeRefresh == SettingsViewModel.TvHomeRefresh.IDLE) settingsVm.refreshAndroidTvHome() },
                help = SettingHelp(refreshTitle, refreshDesc, hints = settingHints(null, pinnable = false)),
            )
        }
    }
    BackHandler(enabled = moving != null) { moving = null }
}

@Composable
private fun HomeSwitchRow(icon: OwnTVIcon, titleRes: Int, descRes: Int, on: Boolean, onClick: () -> Unit) {
    val title = stringResource(titleRes)
    val desc = stringResource(descRes)
    val value = SettingValue.Switch(on)
    StageSettingRow(
        icon = icon,
        title = title,
        desc = desc,
        value = value,
        onClick = onClick,
        help = settingHelp(null, title, desc, value, pinnable = false),
    )
}

private val HomeRow.icon: OwnTVIcon get() = when (this) {
    HomeRow.TRENDING -> OwnTVIcon.STAR
    HomeRow.HERO -> OwnTVIcon.PLAY_CIRCLE
    HomeRow.RECENT_CHANNELS -> OwnTVIcon.LIVE_TV
    HomeRow.FAVORITE_CHANNELS -> OwnTVIcon.FAVORITE
    HomeRow.FAVORITE_MOVIES -> OwnTVIcon.MOVIES
    HomeRow.FAVORITE_SERIES -> OwnTVIcon.SERIES
    HomeRow.CONTINUE_MOVIES -> OwnTVIcon.MOVIES
    HomeRow.CONTINUE_SERIES -> OwnTVIcon.SERIES
    HomeRow.TRAKT_MOVIES -> OwnTVIcon.MOVIES
    HomeRow.TRAKT_SHOWS -> OwnTVIcon.SERIES
}
