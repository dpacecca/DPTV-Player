package tv.own.owntv.features.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.i18n.SupportedLocale
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.ui.stage.StageTag
import androidx.compose.ui.res.pluralStringResource

/**
 * Setup's Language row (P10B-W1): the current language with ▾, OK opens the list in a Stage popup (there
 * is no settings panel in the wizard).
 */
@Composable
fun FirstRunLanguageSelector(modifier: Modifier = Modifier) {
    val viewModel: LanguageSettingsViewModel = koinViewModel()
    val currentTag by viewModel.currentTag.collectAsStateWithLifecycle()
    val selectedLocale = remember(currentTag, viewModel.pickerRows) {
        viewModel.pickerRows.firstOrNull { it.languageTag == currentTag }
    }
    var showPicker by remember { mutableStateOf(false) }
    var wasOpen by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    // Back on the row when the list closes, not wherever the window's focus search lands.
    LaunchedEffect(showPicker) {
        if (showPicker) wasOpen = true
        else if (wasOpen) { wasOpen = false; kotlinx.coroutines.delay(80); runCatching { rowFocus.requestFocus() } }
    }
    val rows = viewModel.pickerRows.size
    StageSettingRow(
        icon = OwnTVIcon.LANGUAGE,
        title = stringResource(R.string.settings_language),
        desc = pluralStringResource(R.plurals.more_about_languages, rows, rows),
        value = SettingValue.Choice(selectedLocale?.endonym ?: stringResource(R.string.settings_language_system_default)),
        onClick = { showPicker = true },
        modifier = modifier.focusRequester(rowFocus),
    )
    if (showPicker) {
        FirstRunLanguagePopup(
            viewModel = viewModel,
            currentTag = currentTag,
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun FirstRunLanguagePopup(
    viewModel: LanguageSettingsViewModel,
    currentTag: String,
    onDismiss: () -> Unit,
) {
    val selectedIndex = remember(currentTag, viewModel.pickerRows) {
        if (currentTag.isEmpty()) 0
        else (viewModel.pickerRows.indexOfFirst { it.languageTag == currentTag } + 1).coerceAtLeast(0)
    }
    val selectedFocus = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)

    fun choose(tag: String) {
        viewModel.setLocale(tag)
        onDismiss()
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { selectedFocus.requestFocus() }
    }
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = stringResource(R.string.settings_language), width = 760.mpx, scroll = false) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.mpx),
        ) {
            item(key = SupportedLocales.SYSTEM_DEFAULT_TAG) {
                LanguageRow(
                    endonym = stringResource(R.string.settings_language_system_default),
                    englishName = stringResource(R.string.settings_language_system_default_description),
                    coverage = null,
                    selected = currentTag.isEmpty(),
                    onClick = { choose(SupportedLocales.SYSTEM_DEFAULT_TAG) },
                    modifier = if (currentTag.isEmpty()) Modifier.focusRequester(selectedFocus) else Modifier,
                )
            }
            items(viewModel.pickerRows, key = { it.languageTag }) { locale ->
                val selected = locale.languageTag == currentTag
                LanguageRow(
                    endonym = locale.endonym,
                    englishName = locale.englishName,
                    coverage = null,
                    selected = selected,
                    onClick = { choose(locale.languageTag) },
                    modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier,
                )
            }
        }
    }
}

/**
 * Settings › App › Language (P10B-21): System default, then every language A–Z by English name, each
 * with a radio; OK uses it. Same-script switches recompose instantly via
 * [tv.own.owntv.core.i18n.LocalizedContent]; cross-script switches trigger one
 * [android.app.Activity.recreate]. The panel explains the focused choice and carries the translation
 * project's QR code, link and language-request note.
 */
@Composable
fun LanguageSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: LanguageSettingsViewModel = koinViewModel()
    val currentTag by viewModel.currentTag.collectAsStateWithLifecycle()
    val rows = viewModel.pickerRows

    val selectedFocus = remember { FocusRequester() }
    val rowsFocus = remember { FocusRequester() }
    // Changing locale replaces the localized Compose subtree, and a cross-script change also recreates
    // the Activity. Wait for the newly selected row to own selectedFocus, then restore focus there
    // instead of letting Compose's fallback search land on the rail.
    LaunchedEffect(currentTag) {
        kotlinx.coroutines.delay(80)
        if (!selectedFocus.requestFocus()) rowsFocus.requestFocus()
    }

    val systemName = remember {
        val system = android.content.res.Resources.getSystem().configuration.locales[0]
        system.getDisplayName(system).replaceFirstChar { it.titlecase(system) }
    }
    val hints = listOf(
        stringResource(R.string.common_ok) to stringResource(R.string.settings_key_use),
        stringResource(R.string.common_back) to stringResource(R.string.settings_group_app),
    )
    val translate: @Composable () -> Unit = { TranslateBlock() }
    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_app)),
        title = stringResource(R.string.settings_language),
        count = pluralStringResource(R.plurals.more_about_languages, rows.size, rows.size),
        onBack = onBack,
        modifier = modifier,
        rowsFocus = rowsFocus,
    ) {
        val systemLabel = stringResource(R.string.settings_language_system_default)
        LanguageStageRow(
            icon = OwnTVIcon.CHECK,
            title = systemLabel,
            line = stringResource(R.string.settings_language_system_default_description),
            coverage = null,
            selected = currentTag.isEmpty(),
            help = SettingHelp(systemLabel, stringResource(R.string.settings_language_system_help, systemName), hints = hints, extra = translate),
            onClick = { viewModel.setLocale(SupportedLocales.SYSTEM_DEFAULT_TAG) },
            modifier = if (currentTag.isEmpty()) Modifier.focusRequester(selectedFocus) else Modifier,
        )
        rows.forEach { locale ->
            val selected = locale.languageTag == currentTag
            LanguageStageRow(
                icon = OwnTVIcon.LANGUAGE,
                title = locale.endonym,
                line = locale.englishName,
                coverage = coverageBadgePercent(locale),
                selected = selected,
                help = SettingHelp(locale.endonym, stringResource(R.string.settings_language_pick_help, locale.endonym), hints = hints, extra = translate),
                onClick = { viewModel.setLocale(locale.languageTag) },
                modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }
    }
}

internal fun coverageBadgePercent(locale: SupportedLocale): Int? =
    SupportedLocales.coverageBadgePercent(locale)

/** A language as a settings row: SansSerif so CJK / Arabic / Hebrew endonyms get the platform fallbacks. */
@Composable
private fun LanguageStageRow(
    icon: OwnTVIcon,
    title: String,
    line: String,
    coverage: Int?,
    selected: Boolean,
    help: SettingHelp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StageSettingRow(
        icon = icon,
        title = title,
        desc = line,
        value = SettingValue.Custom {
            Row(horizontalArrangement = Arrangement.spacedBy(12.mpx), verticalAlignment = Alignment.CenterVertically) {
                if (coverage != null) StageTag(stringResource(R.string.settings_language_coverage, coverage))
                StageRadio(selected)
            }
        },
        onClick = onClick,
        modifier = modifier,
        help = help,
    )
}

/** The panel's HELP TRANSLATE block (P10B-21, owner): QR and link to the project, then the request note. */
@Composable
private fun TranslateBlock() {
    val url = SupportedLocales.CONTRIBUTION_PROJECT_URL
    val qr = remember(url) { CompanionLink.renderQr(url) }
    Box(Modifier.padding(top = 22.mpx, bottom = 16.mpx).fillMaxWidth().height(1.mpx).background(Color.White.copy(alpha = 0.1f)))
    SettingPanelHeading(stringResource(R.string.settings_language_help_translate))
    Row(horizontalArrangement = Arrangement.spacedBy(20.mpx), verticalAlignment = Alignment.CenterVertically) {
        if (qr != null) {
            Box(
                Modifier.size(150.mpx).clip(RoundedCornerShape(16.mpx)).background(Color.White).padding(8.mpx),
            ) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = stringResource(R.string.settings_language_contribution_qr_description),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_language_contribution_description), style = stageText(16, 500), color = StageColors.Muted)
            Text(url.removePrefix("https://"), style = stageText(18, 700), color = StageColors.Text, modifier = Modifier.padding(top = 4.mpx))
        }
    }
    Text(
        stringResource(R.string.settings_language_request_workflow),
        style = stageText(15, 500), color = StageColors.Muted,
        modifier = Modifier.padding(top = 14.mpx),
    )
}

@Composable
private fun LanguageRow(
    endonym: String,
    englishName: String,
    coverage: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        selected = selected,
        shape = RoundedCornerShape(16.dp),
        selectedContainerColor = colors.primaryContainer,
        surface = GlassSurface.CARDS,
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioIndicator(selected = selected)
            Column(modifier = Modifier.weight(1f)) {
                // SansSerif so CJK / Arabic / Hebrew endonyms get platform Noto fallbacks (Lora has none).
                Text(
                    endonym,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.SansSerif),
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                )
                Text(
                    englishName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                )
            }
            if (coverage != null) {
                Text(
                    stringResource(R.string.settings_language_coverage, coverage),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.onPrimaryContainer else colors.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) colors.primary.copy(alpha = 0.25f) else colors.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RadioIndicator(selected: Boolean) {
    val colors = OwnTVTheme.colors
    Box(
        modifier = Modifier
            .size(24.dp)
            .then(
                if (selected) {
                    Modifier.background(colors.primary, CircleShape)
                } else {
                    Modifier.border(2.dp, colors.outline, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Canvas(modifier = Modifier.size(14.dp)) {
                val stroke = Stroke(width = size.minDimension * 0.18f, cap = StrokeCap.Round)
                val checkColor = colors.onPrimary
                drawLine(
                    color = checkColor,
                    start = Offset(size.width * 0.18f, size.height * 0.52f),
                    end = Offset(size.width * 0.42f, size.height * 0.75f),
                    strokeWidth = stroke.width,
                    cap = stroke.cap,
                )
                drawLine(
                    color = checkColor,
                    start = Offset(size.width * 0.42f, size.height * 0.75f),
                    end = Offset(size.width * 0.82f, size.height * 0.28f),
                    strokeWidth = stroke.width,
                    cap = stroke.cap,
                )
            }
        }
    }
}
