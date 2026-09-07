package tv.own.owntv.features.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.features.settings.BackupScreen
import tv.own.owntv.features.settings.GroupLabel
import tv.own.owntv.features.settings.LocalSyncScreen
import tv.own.owntv.features.settings.Row2
import tv.own.owntv.features.shell.components.AboutDialog
import tv.own.owntv.features.shell.components.LocalSettingsRowTone
import tv.own.owntv.features.shell.components.PlaybackErrorLogDialog
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.features.shell.components.TileTone
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

/** Which of More's own pages is on screen. [ROOT] is the list of rows itself. */
private enum class MorePage { ROOT, FAVORITES, HISTORY, BACKUP, LOCAL_SYNC }

/**
 * The hub the rail's last item opens — everything that is neither a channel nor a preference.
 *
 * It exists because Settings was the only door: Backup, Local sync, the error log and About are a
 * place, a place, a log and a page of facts, and none of them is a setting. They are rows here, and
 * Settings is a row here too.
 *
 * **Focus lands on Settings the moment this opens**, so the most-used destination is rail → More →
 * OK: one extra press over what it used to be, and never a hunt.
 *
 * There is deliberately no Downloads row and no Profiles row. Both already have a rail slot —
 * Downloads in `browseOrder`, the profile card at the rail's foot — and one door each is the rule
 * this screen exists to enforce.
 */
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    onFullscreen: () -> Unit,
    onChildFocused: () -> Unit,
    modifier: Modifier = Modifier,
    counts: MoreCountsViewModel = koinViewModel(),
) {
    var page by rememberSaveable { mutableStateOf(MorePage.ROOT) }
    var showAbout by remember { mutableStateOf(false) }
    var showErrorLog by remember { mutableStateOf(false) }
    val favorites by counts.favorites.collectAsStateWithLifecycle()
    val history by counts.history.collectAsStateWithLifecycle()

    // Each page and each dialog is left by Back, and the row it was opened from is where focus has
    // to land — otherwise leaving Backup drops the user at the top of the list every time.
    val rowFocus = remember { MoreRow.entries.associateWith { FocusRequester() } }
    var returnTo by remember { mutableStateOf(MoreRow.SETTINGS) }
    val focusRow: (MoreRow) -> Unit = { returnTo = it }

    when (page) {
        MorePage.FAVORITES -> {
            FavoritesScreen(
                onFullscreen = onFullscreen,
                onChildFocused = onChildFocused,
                onBack = { page = MorePage.ROOT },
                modifier = modifier,
            )
            return
        }
        MorePage.HISTORY -> {
            HistoryScreen(
                onFullscreen = onFullscreen,
                onChildFocused = onChildFocused,
                onBack = { page = MorePage.ROOT },
                modifier = modifier,
            )
            return
        }
        MorePage.BACKUP -> {
            Toned(TileTone.TERTIARY) { BackupScreen(onBack = { page = MorePage.ROOT }, modifier = modifier) }
            return
        }
        MorePage.LOCAL_SYNC -> {
            Toned(TileTone.TERTIARY) { LocalSyncScreen(onBack = { page = MorePage.ROOT }, modifier = modifier) }
            return
        }
        MorePage.ROOT -> Unit
    }

    val dialogOpen = showAbout || showErrorLog
    LaunchedEffect(dialogOpen) {
        if (!dialogOpen) runCatching { rowFocus.getValue(returnTo).requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.common_nav_more),
            style = MaterialTheme.typography.headlineLarge,
            color = OwnTVTheme.colors.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        Row2(
            icon = OwnTVIcon.SETTINGS,
            title = stringResource(R.string.common_nav_settings),
            chevron = true,
            modifier = Modifier.focusRequester(rowFocus.getValue(MoreRow.SETTINGS)),
            onClick = { focusRow(MoreRow.SETTINGS); onOpenSettings() },
        )

        Spacer(Modifier.height(12.dp))
        GroupLabel(stringResource(R.string.settings_group_data))
        // The two screens the television never had. Their badge is the whole point of the row: it
        // says how much there is before you go and look.
        Row2(
            icon = OwnTVIcon.FAVORITE,
            title = stringResource(R.string.content_category_favorites),
            chip = favorites.total.toString(),
            chevron = true,
            modifier = Modifier.focusRequester(rowFocus.getValue(MoreRow.FAVORITES)),
            onClick = { focusRow(MoreRow.FAVORITES); page = MorePage.FAVORITES },
        )
        Row2(
            icon = OwnTVIcon.HISTORY,
            title = stringResource(R.string.content_category_history),
            chip = history.total.toString(),
            chevron = true,
            modifier = Modifier.focusRequester(rowFocus.getValue(MoreRow.HISTORY)),
            onClick = { focusRow(MoreRow.HISTORY); page = MorePage.HISTORY },
        )
        Row2(
            icon = OwnTVIcon.BACKUP,
            title = stringResource(R.string.settings_backup_restore),
            desc = stringResource(R.string.settings_backup_restore_description),
            chevron = true,
            modifier = Modifier.focusRequester(rowFocus.getValue(MoreRow.BACKUP)),
            onClick = { focusRow(MoreRow.BACKUP); page = MorePage.BACKUP },
        )
        Row2(
            icon = OwnTVIcon.REFRESH,
            title = stringResource(R.string.local_sync_title),
            desc = stringResource(R.string.local_sync_description),
            chevron = true,
            modifier = Modifier.focusRequester(rowFocus.getValue(MoreRow.LOCAL_SYNC)),
            onClick = { focusRow(MoreRow.LOCAL_SYNC); page = MorePage.LOCAL_SYNC },
        )

        Spacer(Modifier.height(12.dp))
        GroupLabel(stringResource(R.string.settings_app_group))
        Row2(
            icon = OwnTVIcon.WARNING,
            title = stringResource(R.string.settings_playback_error_log),
            desc = stringResource(R.string.settings_playback_error_description),
            chevron = true,
            modifier = Modifier.focusRequester(rowFocus.getValue(MoreRow.ERROR_LOG)),
            onClick = { focusRow(MoreRow.ERROR_LOG); showErrorLog = true },
        )
        Row2(
            icon = OwnTVIcon.INFO,
            title = stringResource(R.string.settings_about),
            desc = stringResource(R.string.settings_about_description),
            chevron = true,
            modifier = Modifier.focusRequester(rowFocus.getValue(MoreRow.ABOUT)),
            onClick = { focusRow(MoreRow.ABOUT); showAbout = true },
        )
    }

    // The same two dialogs Settings used to open, unchanged — only their door moved.
    if (showAbout) {
        OwnTVPopup(onDismissRequest = { showAbout = false }) {
            AboutDialog(onDismiss = { showAbout = false })
        }
    }
    if (showErrorLog) {
        OwnTVPopup(onDismissRequest = { showErrorLog = false }) {
            PlaybackErrorLogDialog(onDismiss = { showErrorLog = false })
        }
    }
}

/** The rows, only so focus can be given back to the one a page or dialog was opened from. */
private enum class MoreRow { SETTINGS, FAVORITES, HISTORY, BACKUP, LOCAL_SYNC, ERROR_LOG, ABOUT }

/** Settings' own wrapper, so a page opened from here has the tone its row had. */
@Composable
private fun Toned(tone: TileTone, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalSettingsRowTone provides tone, content = content)
