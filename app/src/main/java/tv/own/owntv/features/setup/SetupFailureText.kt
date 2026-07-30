package tv.own.owntv.features.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.ui.components.displayText

/** Words a semantic onboarding failure at the Compose boundary. */
@Composable
fun SetupViewModel.SetupFailure.displayText(): String = when (this) {
    SetupViewModel.SetupFailure.InvalidMac -> stringResource(R.string.setup_invalid_mac)
    SetupViewModel.SetupFailure.BackupRead -> stringResource(R.string.setup_backup_read_failed)
    SetupViewModel.SetupFailure.Restore -> stringResource(R.string.setup_restore_failed)
    is SetupViewModel.SetupFailure.Sync -> failure.displayText()
}
