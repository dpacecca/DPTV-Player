package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.PopupFontTheme
import tv.own.owntv.ui.components.roundedPanel

/**
 * Languages the OpenSubtitles search can be restricted to (ISO 639-1, the codes their API expects).
 *
 * Its own list on purpose: VideoPlayerSettingsScreen's LANGUAGES covers embedded-track matching with
 * 3-letter codes and only 15 entries (no Greek, among many), which is far too narrow for a subtitle
 * library that carries ~60 languages. Blank is not offered — the filter row is hidden when the toggle
 * is off, and "off" is what means "all languages".
 */
private val SUB_SEARCH_LANGUAGE_CODES = listOf(
    "ar", "bg", "zh-cn", "zh-tw", "hr", "cs", "da", "nl", "en", "et", "fi", "fr",
    "de", "el", "he", "hi", "hu", "id", "it", "ja", "ko", "lv", "lt", "ms", "no",
    "fa", "pl", "pt-br", "pt-pt", "ro", "ru", "sr", "sk", "sl", "es", "sv", "th",
    "tr", "uk", "vi",
)

@Composable
private fun subSearchLanguages(): List<Pair<String, String>> {
    val displayLocale = LocalConfiguration.current.locales[0]
    return remember(displayLocale) {
        SUB_SEARCH_LANGUAGE_CODES.map { code ->
            code to java.util.Locale.forLanguageTag(code).getDisplayName(displayLocale)
        }
    }
}

/** Device language if OpenSubtitles carries it, else English — the seed when the filter is first turned on. */
private fun defaultSearchLang(): String {
    val locale = java.util.Locale.getDefault()
    val tag = "${locale.language}-${locale.country}".lowercase()
    return SUB_SEARCH_LANGUAGE_CODES.firstOrNull { it == tag }
        ?: SUB_SEARCH_LANGUAGE_CODES.firstOrNull { it == locale.language.lowercase() }
        ?: "en"
}

/**
 * Settings → Video Player → Subtitles → OpenSubtitles account (subtitle plan §5.2/§5.3).
 * The connection is per OwnTV profile; users sign in with their own free OpenSubtitles account.
 */
@Composable
fun OpenSubtitlesAccountScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    val vm: OpenSubtitlesViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    // Search-language filter lives in the shared settings VM (plain DataStore prefs, not account state).
    val settingsVm: SettingsViewModel = koinViewModel()
    val filterEnabled by settingsVm.subSearchFilterEnabled.collectAsStateWithLifecycle()
    val searchLang by settingsVm.subSearchLanguages.collectAsStateWithLifecycle()
    val searchLanguages = subSearchLanguages()
    val searchLanguageName = searchLanguages.firstOrNull { it.first == searchLang }?.second
        ?: searchLang.ifBlank { stringResource(R.string.player_subtitles_language_not_set) }

    var showSignIn by remember { mutableStateOf(false) }
    var showDeleteSubs by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }
    var langPickerWasOpen by remember { mutableStateOf(false) }
    val langRowFocus = remember { FocusRequester() }
    // Returning from the Delete-subtitles screen should land back on the row that opened it,
    // not the first row (Sign out / Sign in).
    var returnedFromDelete by remember { mutableStateOf(false) }
    if (showDeleteSubs) {
        DeleteSubtitlesScreen(
            onBack = { showDeleteSubs = false; returnedFromDelete = true },
            modifier = modifier,
        )
        return
    }
    val firstFocus = remember { FocusRequester() }
    val deleteFocus = remember { FocusRequester() }
    // Entry focus — keyed on Unit (NOT state). Keying on `state` stole focus on every state change,
    // e.g. yanking it off the "Refresh" button back to "Sign out" once a refresh completed. We only
    // want to set entry focus once, on first composition.
    LaunchedEffect(Unit) {
        // During Busy, firstFocus is not attached to any node (it lives on the SignedIn/Out rows);
        // fall back to deleteFocus (the always-composed "Delete subtitles" row) so focus doesn't
        // escape to the sidebar while the screen is contacting OpenSubtitles.
        val target = if (state is OpenSubtitlesViewModel.UiState.Busy) deleteFocus else firstFocus
        kotlinx.coroutines.delay(60)
        runCatching { target.requestFocus() }
    }
    // Returning from Delete-subtitles lands back on the row that opened it. Decoupled from `state`
    // (the previous version only consumed the latch inside LaunchedEffect(state), so if state didn't
    // change during the visit, focus never came back here).
    LaunchedEffect(showDeleteSubs) {
        if (!showDeleteSubs && returnedFromDelete) {
            returnedFromDelete = false
            kotlinx.coroutines.delay(60)
            runCatching { deleteFocus.requestFocus() }
        }
    }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            // Safety net: any focus that escapes (e.g. when the SignedIn↔SignedOut swap disposes the
            // focused "Sign out"/"Refresh" nodes) is recaptured onto a still-composed row whenever
            // directional focus re-enters the group. firstFocus during SignedIn/Out, deleteFocus during Busy.
            .focusProperties {
                onEnter = {
                    val target = if (state is OpenSubtitlesViewModel.UiState.Busy) deleteFocus else firstFocus
                    runCatching { target.requestFocus() }
                }
            }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Header(stringResource(R.string.settings_open_subtitles), onBack) }
            if (state is OpenSubtitlesViewModel.UiState.SignedIn) {
                OwnTVButton(stringResource(R.string.player_subtitles_refresh), onClick = { vm.refresh() }, style = OwnTVButtonStyle.SECONDARY)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.player_subtitles_free_description_full),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))

        when (val s = state) {
            is OpenSubtitlesViewModel.UiState.SignedIn -> {
                GroupLabel(stringResource(R.string.player_subtitles_account))
                val session = s.session
                InfoRow(stringResource(R.string.player_subtitles_connected_as), session.username)
                InfoRow(stringResource(R.string.player_subtitles_account), listOfNotNull(session.level, stringResource(R.string.player_subtitles_vip).takeIf { session.vip }).joinToString(stringResource(R.string.player_subtitles_tags_separator)).ifBlank { stringResource(R.string.player_subtitles_free_account) })
                // Provider-reported values only (§5.3): remaining-only unless a total was returned.
                val remaining = session.remainingDownloads
                if (remaining != null) {
                    val total = session.allowedDownloads
                    InfoRow(
                        stringResource(R.string.player_subtitles_downloads),
                        if (total != null) pluralStringResource(R.plurals.player_subtitles_remaining, remaining, remaining, total) else pluralStringResource(R.plurals.player_subtitles_remaining_short, remaining, remaining),
                    )
                }
                session.resetTime?.let { InfoRow(stringResource(R.string.player_subtitles_resets), stringResource(R.string.player_subtitles_in, it)) }
                Spacer(Modifier.height(14.dp))
                Row2(
                    icon = OwnTVIcon.SUBTITLE, title = stringResource(R.string.player_subtitles_sign_out),
                    desc = stringResource(R.string.player_subtitles_delete_login_message),
                    modifier = Modifier.focusRequester(firstFocus),
                    onClick = { vm.signOut() },
                )
            }
            OpenSubtitlesViewModel.UiState.Busy -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.player_subtitles_contacting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            OpenSubtitlesViewModel.UiState.SignedOut -> {
                Row2(
                    icon = OwnTVIcon.SUBTITLE, title = stringResource(R.string.player_subtitles_sign_in),
                    desc = stringResource(R.string.player_subtitles_connect_description),
                    chevron = true,
                    modifier = Modifier.focusRequester(firstFocus),
                    onClick = { showSignIn = true },
                )
            }
        }

        // Search language filter (available regardless of sign-in state — it's a search preference).
        Spacer(Modifier.height(14.dp))
        GroupLabel(stringResource(R.string.player_subtitles_search))
        Row2(
            icon = OwnTVIcon.SUBTITLE, title = stringResource(R.string.player_subtitles_filter_title),
            desc = stringResource(R.string.player_subtitles_filter_description),
            chip = stringResource(if (filterEnabled) R.string.common_on else R.string.common_off), primaryChip = filterEnabled,
            onClick = {
                // Turning the filter on with nothing chosen yet would silently behave like "off"
                // (no codes = no filter), so seed it from the device language, falling back to English.
                if (!filterEnabled && searchLang.isBlank()) settingsVm.setSubSearchLanguages(defaultSearchLang())
                settingsVm.setSubSearchFilterEnabled(!filterEnabled)
            },
        )
        if (filterEnabled) {
            Spacer(Modifier.height(6.dp))
            Row2(
                icon = OwnTVIcon.SUBTITLE, title = stringResource(R.string.player_subtitles_search_language),
                desc = stringResource(R.string.player_subtitles_search_language_description),
                chip = searchLanguageName, chevron = true,
                modifier = Modifier.focusRequester(langRowFocus),
                onClick = { showLangPicker = true },
            )
        }

        // Delete downloaded subtitles (available regardless of sign-in state — cached files are local).
        Spacer(Modifier.height(14.dp))
        GroupLabel(stringResource(R.string.player_subtitles_downloads))
        Row2(
            icon = OwnTVIcon.SUBTITLE, title = stringResource(R.string.player_subtitles_delete_action),
            desc = stringResource(R.string.player_subtitles_delete_description),
            chevron = true,
            modifier = Modifier.focusRequester(deleteFocus),
            onClick = { showDeleteSubs = true },
        )

        // Push the credit block clearly below the actions, toward the bottom of the panel.
        // (Can't use weight() here — the column is verticalScroll'ed, so height is unbounded.)
        Spacer(Modifier.height(64.dp))
        // OpenSubtitles attribution — logo + line, mirroring the TMDB credit in Metadata settings.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(tv.own.owntv.R.drawable.ic_opensubtitles_logo),
            contentDescription = stringResource(R.string.settings_open_subtitles),
            modifier = Modifier.padding(start = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.player_subtitles_api_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp),
        )
    }

    if (showSignIn) {
        OpenSubtitlesSignInDialog(
            onSubmit = { user, pass, stay ->
                showSignIn = false
                vm.signIn(user, pass, stay)
            },
            onDismiss = { showSignIn = false },
        )
    }

    if (showLangPicker) {
        // Searchable — the list is long enough that D-pad scrolling to e.g. Ukrainian is tedious.
        PickerDialog(
            title = stringResource(R.string.player_subtitles_search_language),
            options = searchLanguages,
            selected = searchLang,
            searchable = true,
            onSelect = {
                if (it != searchLang) settingsVm.setSubSearchLanguages(it)
                showLangPicker = false
            },
            onDismiss = { showLangPicker = false },
        )
    }
    // Return focus to the language row after the dialog closes instead of letting it fall to the first
    // row (same pattern as MetadataSettingsScreen). Gated so it can't steal entry focus on first compose.
    LaunchedEffect(showLangPicker) {
        if (showLangPicker) {
            langPickerWasOpen = true
        } else if (langPickerWasOpen) {
            langPickerWasOpen = false
            kotlinx.coroutines.delay(80)
            runCatching { langRowFocus.requestFocus() }
        }
    }

    error?.let { kind ->
        val messageRes = when (kind) {
            OpenSubtitlesViewModel.ErrorKind.EMPTY_CREDENTIALS -> R.string.player_subtitles_enter_credentials
            OpenSubtitlesViewModel.ErrorKind.INVALID_CREDENTIALS -> R.string.player_subtitles_invalid_credentials
            OpenSubtitlesViewModel.ErrorKind.NETWORK -> R.string.player_subtitles_sign_in_network_error
            OpenSubtitlesViewModel.ErrorKind.REFRESH_NETWORK -> R.string.player_subtitles_refresh_network_error
        }
        ErrorDialog(message = stringResource(messageRes), onDismiss = { vm.dismissError() })
    }
}

/** Label left, value right. Shared with the Metadata screen so both account panels read identically. */
@Composable
internal fun InfoRow(label: String, value: String) {
    val colors = OwnTVTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}

/** Username + password + "Stay signed in" (review R5). TV keyboard comes from OwnTVTextField. */
@Composable
internal fun OpenSubtitlesSignInDialog(onSubmit: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var staySignedIn by remember { mutableStateOf(true) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    BackHandler { onDismiss() }
    PopupFontTheme {
        Box(
            Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.dialogPanel(width = 480.dp, padding = 28.dp)) {
                Text(stringResource(R.string.player_subtitles_sign_in_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.player_subtitles_sign_in_to_use),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OwnTVTextField(
                    value = username, onValueChange = { username = it },
                    label = stringResource(R.string.player_subtitles_username), modifier = Modifier.fillMaxWidth(), focusRequester = fieldFocus,
                )
                Spacer(Modifier.height(10.dp))
                OwnTVTextField(
                    value = password, onValueChange = { password = it },
                    label = stringResource(R.string.player_subtitles_password), isPassword = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row2(
                    icon = OwnTVIcon.SUBTITLE, title = stringResource(R.string.player_subtitles_stay_signed_in),
                    desc = stringResource(R.string.player_subtitles_session),
                    chip = if (staySignedIn) stringResource(R.string.common_on) else stringResource(R.string.common_off), primaryChip = staySignedIn,
                    onClick = { staySignedIn = !staySignedIn },
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY)
                    Spacer(Modifier.weight(1f))
                    OwnTVButton(stringResource(R.string.player_subtitles_sign_in), onClick = { onSubmit(username.trim(), password, staySignedIn) })
                }
            }
        }
    }
}

@Composable
private fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDismiss() }
    PopupFontTheme {
        Box(
            Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.dialogPanel(width = 420.dp, padding = 24.dp)) {
                Text(stringResource(R.string.settings_open_subtitles), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Spacer(Modifier.height(10.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OwnTVButton(stringResource(R.string.settings_close), onClick = onDismiss, modifier = Modifier.focusRequester(focus))
                }
            }
        }
    }
}
