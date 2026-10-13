package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * Settings › Layout › Browsing lists as a Stage page (owner, P12: two switches are not a popup): what
 * Live TV remembers — its last category, then its last item — one switch per part.
 */
@Composable
fun BrowsingListsSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = koinViewModel()
    val catLive by vm.rememberCategoryLive.collectAsStateWithLifecycle()
    val itemLive by vm.rememberLastLive.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_layout)),
        title = stringResource(R.string.settings_browsing_lists),
        count = pluralStringResource(R.plurals.settings_setting_count, 2, 2),
        onBack = onBack,
        modifier = modifier,
    ) {
        val live = stringResource(R.string.settings_history_live)
        val cat = stringResource(R.string.settings_browsing_last_category)
        val catHelp = stringResource(R.string.settings_browsing_last_category_description)
        StageSettingsHeading(cat, 1, first = true)
        BrowsingSwitch(OwnTVIcon.LIVE_TV, live, cat, catHelp, catLive, Modifier.focusRequester(first)) { vm.setRememberCategoryLive(!catLive) }
        val item = stringResource(R.string.settings_browsing_last_item)
        val itemHelp = stringResource(R.string.settings_browsing_last_item_description)
        StageSettingsHeading(item, 1)
        BrowsingSwitch(OwnTVIcon.LIVE_TV, live, item, itemHelp, itemLive) { vm.setRememberLastLive(!itemLive) }
    }
}

@Composable
private fun BrowsingSwitch(icon: OwnTVIcon, title: String, part: String, help: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val value = SettingValue.Switch(on)
    StageSettingRow(
        icon = icon,
        title = title,
        desc = null,
        value = value,
        onClick = onClick,
        // The panel names the part too: "Remember last category · Live TV".
        help = settingHelp(null, stringResource(R.string.settings_breadcrumb_eyebrow, part, title), help, value, pinnable = false),
        modifier = modifier,
    )
}
