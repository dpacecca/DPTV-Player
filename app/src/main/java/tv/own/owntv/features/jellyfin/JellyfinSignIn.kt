package tv.own.owntv.features.jellyfin

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** Settings › Playlists › Jellyfin's line: who is signed in, or how to start. */
@Composable
fun jellyfinSettingsLine(): String {
    val vm: JellyfinViewModel = koinViewModel()
    val account by vm.account.collectAsStateWithLifecycle()
    return account?.let { stringResource(R.string.sport_jf_signed_in_as, it.userName) }
        ?: stringResource(R.string.sport_jf_settings_desc)
}

/**
 * Sign in to a Jellyfin server (DPTV v2.9.0): the address, user name and password. The password goes
 * to Jellyfin once and is not kept; Jellyfin's own access token is, on this TV only.
 */
@Composable
fun JellyfinSignInDialog(onClose: () -> Unit) {
    val vm: JellyfinViewModel = koinViewModel()
    val account by vm.account.collectAsStateWithLifecycle()
    val state by vm.signIn.collectAsStateWithLifecycle()
    val lastServer by vm.lastServer.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(account == null) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }

    val signedIn = account
    if (signedIn != null) {
        StagePopup(
            onDismiss = onClose,
            title = stringResource(R.string.common_nav_jellyfin),
            body = stringResource(R.string.sport_jf_signed_in_body, signedIn.userName, signedIn.server),
            width = 880.mpx,
        ) {
            StagePopupOption(
                title = stringResource(R.string.sport_jf_sign_out), danger = true,
                onClick = { vm.signOut() },
                modifier = Modifier.focusRequester(first),
            )
            StagePopupOption(title = stringResource(R.string.sport_jf_done), onClick = onClose)
        }
        return
    }

    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(lastServer) { if (server.isBlank()) lastServer?.let { server = it } }
    val working = state == JellyfinViewModel.SignIn.Working
    val canSend = server.isNotBlank() && user.isNotBlank() && !working
    StagePopup(
        onDismiss = onClose,
        title = stringResource(R.string.common_nav_jellyfin),
        body = stringResource(R.string.sport_jf_sign_in_hint),
        width = 880.mpx,
        buttons = {
            StageButton(
                text = stringResource(if (working) R.string.sport_jf_signing_in else R.string.sport_jf_sign_in),
                icon = OwnTVIcon.CHECK, tinted = true,
                onClick = { if (canSend) vm.signIn(server, user, password) },
            )
            StageButton(text = stringResource(R.string.common_cancel), onClick = onClose)
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.mpx)) {
            OwnTVTextField(
                value = server, onValueChange = { server = it },
                label = stringResource(R.string.sport_jf_server),
                placeholder = stringResource(R.string.sport_jf_server_example),
                keyboardType = KeyboardType.Uri,
                focusRequester = first,
            )
            OwnTVTextField(value = user, onValueChange = { user = it }, label = stringResource(R.string.sport_jf_user))
            OwnTVTextField(
                value = password, onValueChange = { password = it },
                label = stringResource(R.string.sport_jf_password), isPassword = true,
                keyboardType = KeyboardType.Password,
            )
            (state as? JellyfinViewModel.SignIn.Failed)?.let { f ->
                Text(
                    when (f.code) {
                        401, 403 -> stringResource(R.string.sport_jf_error_login)
                        0 -> stringResource(R.string.sport_jf_error_server)
                        else -> stringResource(R.string.sport_jf_error_other, f.code)
                    },
                    style = stageText(19, 600), color = StageColors.Danger, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
