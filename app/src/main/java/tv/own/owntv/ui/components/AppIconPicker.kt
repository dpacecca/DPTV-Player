package tv.own.owntv.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.ui.theme.mpx

/**
 * "Restart DPTV-Player?" on its own, for a restore that brought in a different icon than the launcher's.
 * Restart now switches the icon and reopens the app through it; Later leaves it to [AppIconSwitcher],
 * which applies it the next time the app is in the background.
 *
 * DPTV-Player ships a single mark, so a restored choice always matches the launcher and this dialog
 * stays hidden — it remains only for restores written by older multi-colour builds.
 */
@Composable
fun AppIconRestartDialog(icon: AppIcon, onDismiss: () -> Unit) {
    AppIconPanel(onDismiss, width = 520) { RestartContent(icon, onDismiss) }
}

@Composable
private fun AppIconPanel(onDismiss: () -> Unit, width: Int, content: @Composable () -> Unit) {
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = null, eyebrow = null, width = (width * 1.8f).mpx) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun RestartContent(icon: AppIcon, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val restartFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { restartFocus.requestFocus() } }
    BrandMark(icon, 72.dp, followAccent = false)
    Text(
        stringResource(R.string.app_icon_restart_title), style = tv.own.owntv.ui.theme.stageText(34, 800), color = tv.own.owntv.ui.theme.StageColors.Text,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.mpx),
    )
    Text(
        stringResource(R.string.app_icon_restart_message), style = tv.own.owntv.ui.theme.stageText(18, 400), color = tv.own.owntv.ui.theme.StageColors.Muted,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.mpx),
    )
    Row(Modifier.padding(top = 28.mpx), horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
        tv.own.owntv.ui.stage.StageButton(stringResource(R.string.update_later), onClick = onDismiss, height = 56.mpx, textSize = 19)
        tv.own.owntv.ui.stage.StageButton(
            stringResource(R.string.app_icon_restart_now),
            onClick = { context.findActivity()?.let { AppIconSwitcher.restartWith(it, icon) } },
            height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(restartFocus),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
