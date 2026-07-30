package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.player.ExternalSubtitleSource
import tv.own.owntv.player.TrackLabelKind
import tv.own.owntv.player.TrackOption
import java.util.Locale

/**
 * Final player presentation for an engine-owned track row. Engines expose raw labels, language
 * codes and stable ordinals; only this Compose mapper supplies localized fallback and source words.
 */
@Composable
fun TrackOption.displayLabel(): String {
    val configuration = LocalResources.current.configuration
    val locale = if (configuration.locales.isEmpty) Locale.ENGLISH else configuration.locales[0]
    val raw = label.takeIf { it.isNotBlank() }
    val language = lang
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { code ->
            runCatching { Locale.forLanguageTag(code).getDisplayLanguage(locale) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: code.uppercase(locale)
        }
    val fallback = when (labelKind) {
        TrackLabelKind.AUDIO -> stringResource(R.string.player_audio_track_number, displayNumber())
        TrackLabelKind.SUBTITLE -> stringResource(R.string.player_subtitle_track_number, displayNumber())
    }
    val base = raw ?: language ?: fallback
    val source = when (externalSource) {
        ExternalSubtitleSource.LOCAL -> stringResource(R.string.setup_local_file)
        ExternalSubtitleSource.OPENSUBTITLES -> stringResource(R.string.settings_open_subtitles)
        null -> null
    }
    return listOfNotNull(base, source).joinToString(stringResource(R.string.player_metadata_separator))
}

private fun TrackOption.displayNumber(): Int = (typeIndex.takeIf { it >= 0 } ?: mpvId) + 1
