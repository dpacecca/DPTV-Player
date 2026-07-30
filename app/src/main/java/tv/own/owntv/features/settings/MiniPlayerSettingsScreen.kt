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
import tv.own.owntv.player.MiniPlayerPosition
import tv.own.owntv.player.MiniPlayerSize
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

private enum class MiniPlayerDialog { NONE, SIZE, POSITION }

/**
 * Settings → Playback → Mini-player: size (% of screen width) and screen position for the docked
 * mini-player. Both are also adjustable on the fly from the mini-player's own controls (resize / move).
 */
@Composable
fun MiniPlayerSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = koinViewModel()
    val sizePct by vm.miniPlayerSizePct.collectAsStateWithLifecycle()
    val position by vm.miniPlayerPosition.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    val firstFocus = remember { FocusRequester() }
    val positionFocus = remember { FocusRequester() } // "Position" row — restore target after its picker closes
    var dialog by remember { mutableStateOf(MiniPlayerDialog.NONE) }

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    // Restore focus to the row that opened the dialog when it closes (dialog → NONE), instead of
    // always landing on the Size row. We track the opener here because by the time the effect runs,
    // `dialog` is already NONE.
    var dialogReturn by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(dialog) {
        if (dialog != MiniPlayerDialog.NONE) return@LaunchedEffect
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
            // onEnter handles only external directional entry — targets the Size row (firstFocus).
            // Dialog-close restore is owned by the LaunchedEffect above.
            .focusProperties { onEnter = { runCatching { firstFocus.requestFocus() } } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Header(title = stringResource(R.string.settings_mini_player), onBack = onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_mini_player_description),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row2(
            icon = OwnTVIcon.ZOOM,
            title = stringResource(R.string.settings_size),
            desc = stringResource(R.string.settings_mini_player_size_description, MiniPlayerSize.MIN, MiniPlayerSize.MAX),
            chip = stringResource(R.string.common_percent, sizePct),
            primaryChip = false,
            chevron = true,
            onClick = { dialogReturn = firstFocus; dialog = MiniPlayerDialog.SIZE },
            modifier = Modifier.focusRequester(firstFocus),
        )
        Row2(
            icon = OwnTVIcon.PIP,
            title = stringResource(R.string.settings_position),
            desc = stringResource(R.string.settings_mini_player_position_description),
            chip = stringResource(position.labelRes),
            primaryChip = false,
            chevron = true,
            onClick = { dialogReturn = positionFocus; dialog = MiniPlayerDialog.POSITION },
            modifier = Modifier.focusRequester(positionFocus),
        )
    }

    when (dialog) {
        MiniPlayerDialog.SIZE -> StepperDialog(
            title = stringResource(R.string.settings_mini_player_size),
            value = sizePct,
            step = MiniPlayerSize.STEP,
            min = MiniPlayerSize.MIN,
            max = MiniPlayerSize.MAX,
            format = { stringResource(R.string.common_percent, it) },
            onSet = { vm.setMiniPlayerSize(it) },
            onReset = { vm.setMiniPlayerSize(MiniPlayerSize.DEFAULT) },
            onDismiss = { dialog = MiniPlayerDialog.NONE },
        )
        MiniPlayerDialog.POSITION -> PickerDialog(
            title = stringResource(R.string.settings_mini_player_position),
            options = MiniPlayerPosition.entries.map { it.name to stringResource(it.labelRes) },
            selected = position.name,
            onSelect = { v -> vm.setMiniPlayerPosition(MiniPlayerPosition.fromName(v)); dialog = MiniPlayerDialog.NONE },
            onDismiss = { dialog = MiniPlayerDialog.NONE },
        )
        MiniPlayerDialog.NONE -> {}
    }
}
