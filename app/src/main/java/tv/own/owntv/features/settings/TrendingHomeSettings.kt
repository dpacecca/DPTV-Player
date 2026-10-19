package tv.own.owntv.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.metadata.HomeTrendingConfig
import tv.own.owntv.core.metadata.HomeTrendingCountry
import tv.own.owntv.core.metadata.HomeTrendingSource
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** Which of the group's pickers is open (v2.4.0). */
private enum class TrendingPicker { SOURCE, COUNTRY, SERVICES }

/** The rows the "Trending on Home" group adds to Content & metadata (v2.4.0). */
const val TRENDING_HOME_SETTING_ROWS = 4

@Composable
private fun sourceLabel(source: HomeTrendingSource): String = stringResource(
    when (source) {
        HomeTrendingSource.STREAMING -> R.string.sport_settings_trending_source_streaming
        HomeTrendingSource.WEEKLY -> R.string.sport_settings_trending_source_weekly
    },
)

/** A country's name in the app's language, with its code: "Australia (AU)". */
@Composable
private fun countryLabel(code: String): String {
    val display = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val name = runCatching { java.util.Locale.Builder().setRegion(code).build().getDisplayCountry(display) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: code
    return stringResource(R.string.sport_settings_trending_country_name, name, code)
}

/**
 * Settings → Content & metadata → Trending on Home (v2.4.0): where Home's Trending movies / Trending
 * shows come from (TMDB on streaming in a country, or this week's trending), the country, the streaming
 * services (none ticked = all; only used On streaming) and English titles only. Every choice applies at
 * once; Home picks it up on its next refresh (the feed's cache is keyed by these settings).
 *
 * Drawn inside the page's rows; the pickers it opens are drawn by [TrendingHomeDialogs] after the page,
 * as the other pickers of this screen are.
 */
@Composable
fun TrendingHomeSettingsGroup(vm: SettingsViewModel, onOpen: (String) -> Unit) {
    val config by vm.homeTrending.collectAsStateWithLifecycle()
    val back = stringResource(R.string.common_back)
    val content = stringResource(R.string.settings_group_content_metadata)
    StageSettingsHeading(stringResource(R.string.sport_settings_trending_home), TRENDING_HOME_SETTING_ROWS)

    val sourceTitle = stringResource(R.string.sport_settings_trending_source)
    val sourceValue = SettingValue.Choice(sourceLabel(config.source))
    StageSettingRow(
        icon = OwnTVIcon.MOVIES,
        title = sourceTitle,
        desc = null,
        value = sourceValue,
        onClick = { onOpen(TrendingPicker.SOURCE.name) },
        help = settingHelp(
            null, sourceTitle, stringResource(R.string.sport_settings_trending_source_description), sourceValue,
            choices = HomeTrendingSource.entries.map { sourceLabel(it) }, pinnable = false,
        ).let { h -> h.copy(hints = listOf(stringResource(R.string.common_ok) to stringResource(R.string.settings_key_change), back to content)) },
    )

    val countryTitle = stringResource(R.string.sport_settings_trending_country)
    val resolved = config.query().region
    val countryValue = SettingValue.Choice(
        if (config.country.isBlank()) stringResource(R.string.sport_settings_trending_country_auto, countryLabel(resolved)) else countryLabel(resolved),
    )
    StageSettingRow(
        icon = OwnTVIcon.LANGUAGE,
        title = countryTitle,
        desc = null,
        value = countryValue,
        onClick = { onOpen(TrendingPicker.COUNTRY.name) },
        help = SettingHelp(
            countryTitle, stringResource(R.string.sport_settings_trending_country_description),
            hints = listOf(stringResource(R.string.common_ok) to stringResource(R.string.settings_key_change), back to content),
        ),
    )

    val servicesTitle = stringResource(R.string.sport_settings_trending_services)
    val streaming = config.source == HomeTrendingSource.STREAMING
    val servicesValue = SettingValue.Opens(
        if (config.providerIds.isEmpty()) stringResource(R.string.sport_settings_trending_services_all)
        else pluralStringResource(R.plurals.sport_settings_trending_services_count, config.providerIds.size, config.providerIds.size),
    )
    StageSettingRow(
        icon = OwnTVIcon.PLAYLIST,
        title = servicesTitle,
        desc = null,
        value = servicesValue,
        enabled = streaming,
        onClick = { if (streaming) onOpen(TrendingPicker.SERVICES.name) },
        help = SettingHelp(
            servicesTitle, stringResource(R.string.sport_settings_trending_services_description),
            hints = listOf(stringResource(R.string.common_ok) to stringResource(R.string.settings_key_open), back to content),
        ),
    )

    val englishTitle = stringResource(R.string.sport_settings_trending_english)
    val englishValue = SettingValue.Switch(config.englishOnly)
    StageSettingRow(
        icon = OwnTVIcon.LANGUAGE,
        title = englishTitle,
        desc = null,
        value = englishValue,
        onClick = { vm.setHomeTrendingEnglishOnly(!config.englishOnly) },
        help = settingHelp(null, englishTitle, stringResource(R.string.sport_settings_trending_english_description), englishValue, pinnable = false),
    )
}

/** The pickers [TrendingHomeSettingsGroup] opens; [open] is the picker's name from its onOpen, or null. */
@Composable
fun TrendingHomeDialogs(vm: SettingsViewModel, open: String?, onClose: () -> Unit) {
    val config by vm.homeTrending.collectAsStateWithLifecycle()
    when (open) {
        TrendingPicker.SOURCE.name -> PickerDialog(
            title = stringResource(R.string.sport_settings_trending_source),
            options = HomeTrendingSource.entries.map { it.name to sourceLabel(it) },
            selected = config.source.name,
            onSelect = { picked ->
                val source = HomeTrendingSource.fromName(picked)
                if (source != config.source) vm.setHomeTrendingSource(source)
                onClose()
            },
            onDismiss = onClose,
        )
        TrendingPicker.COUNTRY.name -> {
            val device = HomeTrendingCountry.device()
            val automatic = HomeTrendingConfig().query(device).region
            val codes = HomeTrendingCountry.choices(device, config.country)
            PickerDialog(
                title = stringResource(R.string.sport_settings_trending_country),
                options = listOf(AUTO_COUNTRY to stringResource(R.string.sport_settings_trending_country_auto, countryLabel(automatic))) +
                    codes.map { it to countryLabel(it) },
                selected = config.country,
                onSelect = { picked ->
                    if (picked != config.country) vm.setHomeTrendingCountry(picked)
                    onClose()
                },
                onDismiss = onClose,
            )
        }
        TrendingPicker.SERVICES.name -> {
            val region = config.query().region
            LaunchedEffect(region) { vm.loadWatchProviders(region) }
            val list by vm.watchProviders.collectAsStateWithLifecycle()
            TrendingServicesDialog(
                list = list.takeIf { it.region == region } ?: SettingsViewModel.WatchProviderList(region, loading = true),
                selected = config.providerIds,
                onChange = vm::setHomeTrendingProviders,
                onDismiss = onClose,
            )
        }
    }
}

/**
 * The streaming services as a multi-select (v2.4.0): "All services" first (ticked while none is chosen),
 * then TMDB's top services for the country in its display order. OK ticks or unticks; Back or Done closes.
 */
@Composable
private fun TrendingServicesDialog(
    list: SettingsViewModel.WatchProviderList,
    selected: Set<Int>,
    onChange: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val body: @Composable ColumnScope.() -> Unit = { TrendingServicesList(list, selected, onChange, onDismiss) }
    if (panelEditor(onDismiss) { body() }) return
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        title = stringResource(R.string.sport_settings_trending_services),
        body = stringResource(R.string.sport_settings_trending_services_description),
        scroll = false,
    ) { body() }
}

@Composable
private fun ColumnScope.TrendingServicesList(
    list: SettingsViewModel.WatchProviderList,
    selected: Set<Int>,
    onChange: (Set<Int>) -> Unit,
    onDone: () -> Unit,
) {
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(list.loading) {
        if (focused) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        focused = runCatching { first.requestFocus() }.isSuccess
    }
    val note = when {
        list.loading -> stringResource(R.string.sport_settings_trending_services_loading)
        list.providers.isEmpty() -> stringResource(R.string.sport_settings_trending_services_none)
        else -> null
    }
    if (note != null) {
        Text(note, style = stageText(16, 500), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.mpx))
    }
    Column(Modifier.weight(1f, fill = false)) {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.mpx)) {
            item(key = ALL_KEY) {
                tv.own.owntv.ui.stage.StagePopupOption(
                    title = stringResource(R.string.sport_settings_trending_services_all),
                    onClick = { onChange(emptySet()) },
                    modifier = Modifier.focusRequester(first),
                    leading = { f -> tv.own.owntv.ui.stage.StagePopupCheck(selected.isEmpty(), f) },
                )
            }
            items(list.providers, key = { it.id }) { provider ->
                val on = provider.id in selected
                tv.own.owntv.ui.stage.StagePopupOption(
                    title = provider.name,
                    onClick = { onChange(if (on) selected - provider.id else selected + provider.id) },
                    leading = { f -> tv.own.owntv.ui.stage.StagePopupCheck(on, f) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.mpx), horizontalArrangement = Arrangement.End) {
            tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_done), onClick = onDone, height = 52.mpx, textSize = 18, tinted = true)
        }
    }
}

private const val ALL_KEY = -1

/** The stored country that means "the device's, else AU". */
private const val AUTO_COUNTRY = ""
