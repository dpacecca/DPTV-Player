package tv.own.owntv.features.trakt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** Settings › Playlists › Trakt's line. */
@Composable
fun traktSettingsLine(): String {
    val vm: TraktViewModel = koinViewModel()
    val auth by vm.auth.collectAsStateWithLifecycle()
    val name = auth?.takeIf { it.signedIn }?.let { it.userName ?: "" }
    return when {
        name == null -> stringResource(R.string.sport_trakt_settings_desc)
        name.isBlank() -> stringResource(R.string.sport_trakt_signed_in)
        else -> stringResource(R.string.sport_jf_signed_in_as, name)
    }
}

/**
 * Trakt (DPTV v2.9.1): sign in with the user's own Trakt app — its client id and secret from
 * trakt.tv/oauth/applications — by typing the code shown here at trakt.tv/activate. Then: sync now,
 * when it last synced, and sign out.
 */
@Composable
fun TraktDialog(onClose: () -> Unit) {
    val vm: TraktViewModel = koinViewModel()
    val auth by vm.auth.collectAsStateWithLifecycle()
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val signedIn = auth?.signedIn == true
    LaunchedEffect(signedIn, signIn is TraktViewModel.SignIn.Code) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }

    if (signedIn) {
        val time = rememberSystemTimeFormatter()
        val date = tv.own.owntv.ui.format.rememberBestDateFormatter("EEEdMMM")
        StagePopup(
            onDismiss = onClose,
            title = stringResource(R.string.sport_trakt),
            body = stringResource(R.string.sport_trakt_signed_in_body),
            width = 880.mpx,
        ) {
            StagePopupOption(
                title = stringResource(if (syncing) R.string.sport_trakt_syncing else R.string.sport_trakt_sync_now),
                subtitle = lastSync?.let { stringResource(R.string.sport_trakt_last_sync, date(it), time(it)) },
                onClick = { if (!syncing) vm.syncNow() },
                modifier = Modifier.focusRequester(first),
            )
            StagePopupOption(title = stringResource(R.string.sport_jf_sign_out), danger = true, onClick = { vm.signOut() })
            StagePopupOption(title = stringResource(R.string.sport_jf_done), onClick = onClose)
        }
        return
    }

    val code = (signIn as? TraktViewModel.SignIn.Code)?.code
    if (code != null) {
        StagePopup(
            onDismiss = { vm.cancel(); onClose() },
            title = stringResource(R.string.sport_trakt),
            body = stringResource(R.string.sport_trakt_code_hint, code.verificationUrl),
            width = 880.mpx,
            buttons = { StageButton(text = stringResource(R.string.common_cancel), onClick = { vm.cancel() }, modifier = Modifier.focusRequester(first)) },
        ) {
            Text(
                code.userCode, style = stageText(64, 800), color = stageAccent.accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.sport_trakt_waiting), style = stageText(19, 600), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }

    var clientId by rememberSaveable { mutableStateOf(auth?.clientId.orEmpty()) }
    var secret by remember { mutableStateOf(auth?.clientSecret.orEmpty()) }
    LaunchedEffect(auth?.clientId) { if (clientId.isBlank()) auth?.let { clientId = it.clientId; secret = it.clientSecret } }
    val starting = signIn == TraktViewModel.SignIn.Starting
    StagePopup(
        onDismiss = onClose,
        title = stringResource(R.string.sport_trakt),
        body = stringResource(R.string.sport_trakt_app_hint),
        width = 880.mpx,
        buttons = {
            StageButton(
                text = stringResource(if (starting) R.string.sport_jf_signing_in else R.string.sport_jf_sign_in),
                icon = OwnTVIcon.CHECK, tinted = true,
                onClick = { if (!starting) vm.start(clientId, secret) },
            )
            StageButton(text = stringResource(R.string.common_cancel), onClick = onClose)
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.mpx)) {
            OwnTVTextField(value = clientId, onValueChange = { clientId = it.trim() }, label = stringResource(R.string.sport_trakt_client_id), focusRequester = first)
            OwnTVTextField(value = secret, onValueChange = { secret = it.trim() }, label = stringResource(R.string.sport_trakt_client_secret), isPassword = true)
            val message = when (val s = signIn) {
                is TraktViewModel.SignIn.Failed -> if (s.code == 0) stringResource(R.string.sport_trakt_error_reach) else stringResource(R.string.sport_trakt_error_app, s.code)
                TraktViewModel.SignIn.Expired -> stringResource(R.string.sport_trakt_error_expired)
                TraktViewModel.SignIn.Denied -> stringResource(R.string.sport_trakt_error_denied)
                else -> null
            }
            message?.let {
                Text(it, style = stageText(19, 600), color = StageColors.Danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
