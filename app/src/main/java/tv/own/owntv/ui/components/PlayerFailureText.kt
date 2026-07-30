package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.player.PlayerFailureReason

/** Resolves a semantic playback diagnosis only where it is rendered by Compose. */
@Composable
fun PlayerFailureReason.displayText(): String = when (this) {
    PlayerFailureReason.DECODER_BUSY -> stringResource(R.string.player_reason_decoder_busy)
    PlayerFailureReason.DECODER_TRANSIENT -> stringResource(R.string.player_reason_decoder_transient)
    PlayerFailureReason.UNSUPPORTED_VIDEO -> stringResource(R.string.player_reason_unsupported_video)
    PlayerFailureReason.DECODER_MEMORY -> stringResource(R.string.player_reason_decoder_memory)
    PlayerFailureReason.DRM -> stringResource(R.string.player_reason_drm)
    PlayerFailureReason.HTTP_509 -> stringResource(R.string.player_reason_http_509)
    PlayerFailureReason.HTTP_403 -> stringResource(R.string.player_reason_http_403)
    PlayerFailureReason.HTTP_401 -> stringResource(R.string.player_reason_http_401)
    PlayerFailureReason.HTTP_404 -> stringResource(R.string.player_reason_http_404)
    PlayerFailureReason.HTTP_400 -> stringResource(R.string.player_reason_http_400)
    PlayerFailureReason.SSL -> stringResource(R.string.player_reason_ssl)
    PlayerFailureReason.FORMAT -> stringResource(R.string.player_reason_format)
    PlayerFailureReason.NETWORK -> stringResource(R.string.player_reason_network)
    PlayerFailureReason.AUDIO -> stringResource(R.string.player_reason_audio)
}
