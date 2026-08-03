package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.features.settings.data.ChNavLimits
import tv.own.owntv.ui.components.NumberInputDialog
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.format.localizedInteger

private enum class ChNavDialog { NONE, ENABLED, UP_SKIP, DOWN_SKIP }

/**
 * CH+- Key Paging — lets the user page the focused panel (category rail or item list/grid) in
 * Live/Movies/Series, and the category list in Settings → Customize, using the remote's CH+ / CH− keys.
 *
 * Behaviour (set here, applied everywhere CH paging is wired):
 *  - Short press CH+ : skip [upSkip] items toward the FIRST item.
 *  - Short press CH− : skip [downSkip] items toward the LAST item.
 *  - Long-press CH+  : jump straight to the FIRST item.
 *  - Long-press CH−  : jump straight to the LAST item.
 *  - Skips are clamped at the ends (a short list reaches the end in one press for free).
 *
 * Counts are advisory-warned above [ChNavLimits.WARN_THRESHOLD] but never hard-blocked;
 * they're clamped to [ChNavLimits.HARD_MAX] on save.
 */
@Composable
fun ChNavSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = koinViewModel()
    val enabled by vm.chNavEnabled.collectAsStateWithLifecycle()
    val upSkip by vm.chNavUpSkip.collectAsStateWithLifecycle()
    val downSkip by vm.chNavDownSkip.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    val firstFocus = remember { FocusRequester() }
    val upSkipFocus = remember { FocusRequester() }
    val downSkipFocus = remember { FocusRequester() }
    var dialog by remember { mutableStateOf(ChNavDialog.NONE) }
    // The row that opened the current dialog — restored when the dialog closes, instead of always
    // landing on the first ("Use CH+- keys") row. Without this the CH+/CH− skip rows had no
    // FocusRequester at all and relied on Compose's implicit restore.
    var dialogReturn by remember { mutableStateOf<FocusRequester?>(null) }

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    LaunchedEffect(dialog) {
        if (dialog != ChNavDialog.NONE) return@LaunchedEffect
        dialogReturn?.let { opener ->
            kotlinx.coroutines.delay(60)
            runCatching { opener.requestFocus() }
        }
        dialogReturn = null
    }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            // onEnter handles only external directional entry — targets the first row. Dialog-close
            // restore is owned by the LaunchedEffect above.
            .focusProperties { onEnter = { runCatching { firstFocus.requestFocus() } } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Header(title = stringResource(R.string.settings_ch_nav_title), onBack = onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_ch_nav_description),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row2(
            icon = OwnTVIcon.MENU,
            title = stringResource(R.string.settings_ch_nav_enabled),
            desc = stringResource(R.string.settings_ch_nav_enabled_description),
            chip = if (enabled) stringResource(R.string.common_on) else stringResource(R.string.common_off),
            primaryChip = enabled,
            chevron = true,
            onClick = { dialogReturn = firstFocus; dialog = ChNavDialog.ENABLED },
            modifier = Modifier.focusRequester(firstFocus),
        )

        Spacer(Modifier.height(10.dp))
        GroupLabel(stringResource(R.string.settings_skip_counts))

        Row2(
            icon = OwnTVIcon.SKIP_PREVIOUS,
            title = stringResource(R.string.settings_ch_nav_up),
            desc = stringResource(R.string.settings_ch_nav_up_description),
            chip = localizedInteger(upSkip, grouping = false),
            chevron = true,
            onClick = { dialogReturn = upSkipFocus; dialog = ChNavDialog.UP_SKIP },
            modifier = Modifier.focusRequester(upSkipFocus),
        )
        Row2(
            icon = OwnTVIcon.SKIP_NEXT,
            title = stringResource(R.string.settings_ch_nav_down),
            desc = stringResource(R.string.settings_ch_nav_down_description),
            chip = localizedInteger(downSkip, grouping = false),
            chevron = true,
            onClick = { dialogReturn = downSkipFocus; dialog = ChNavDialog.DOWN_SKIP },
            modifier = Modifier.focusRequester(downSkipFocus),
        )

        Spacer(Modifier.height(12.dp))
        GroupLabel(stringResource(R.string.settings_how_it_works))
        Text(
            stringResource(R.string.settings_ch_nav_help),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }

    val warnText = stringResource(R.string.settings_large_skips_warning)
    when (dialog) {
        ChNavDialog.ENABLED -> PickerDialog(
            title = stringResource(R.string.settings_ch_nav_picker),
            options = listOf("true" to stringResource(R.string.common_on), "false" to stringResource(R.string.common_off)),
            selected = enabled.toString(),
            onSelect = { v -> vm.setChNavEnabled(v.toBoolean()); dialog = ChNavDialog.NONE },
            onDismiss = { dialog = ChNavDialog.NONE },
        )
        ChNavDialog.UP_SKIP -> NumberInputDialog(
            title = stringResource(R.string.settings_ch_nav_up),
            value = upSkip,
            min = 1,
            max = ChNavLimits.HARD_MAX,
            step = 5,
            warnAbove = ChNavLimits.WARN_THRESHOLD,
            warningText = warnText,
            onSet = { vm.setChNavUpSkip(it) },
            onReset = { vm.setChNavUpSkip(ChNavLimits.DEFAULT_SKIP) },
            onDismiss = { dialog = ChNavDialog.NONE },
        )
        ChNavDialog.DOWN_SKIP -> NumberInputDialog(
            title = stringResource(R.string.settings_ch_nav_down),
            value = downSkip,
            min = 1,
            max = ChNavLimits.HARD_MAX,
            step = 5,
            warnAbove = ChNavLimits.WARN_THRESHOLD,
            warningText = warnText,
            onSet = { vm.setChNavDownSkip(it) },
            onReset = { vm.setChNavDownSkip(ChNavLimits.DEFAULT_SKIP) },
            onDismiss = { dialog = ChNavDialog.NONE },
        )
        ChNavDialog.NONE -> {}
    }
}
