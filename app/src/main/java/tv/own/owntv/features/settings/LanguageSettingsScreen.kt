package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.SearchBar
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * In-app language picker. System default is pinned first; remaining rows are A–Z by endonym.
 * Same-script switches recompose instantly via [tv.own.owntv.core.i18n.LocalizedContent];
 * cross-script switches trigger one [android.app.Activity.recreate].
 */
@Composable
fun LanguageSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: LanguageSettingsViewModel = koinViewModel()
    val currentTag by viewModel.currentTag.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val colors = OwnTVTheme.colors

    val systemLabel = stringResource(R.string.settings_language_system_default)
    val systemDesc = stringResource(R.string.settings_language_system_default_description)
    val filtered = remember(query, viewModel.pickerRows) {
        val q = query.trim()
        if (q.isEmpty()) {
            viewModel.pickerRows
        } else {
            viewModel.pickerRows.filter { locale ->
                locale.endonym.contains(q, ignoreCase = true) ||
                    locale.englishName.contains(q, ignoreCase = true) ||
                    locale.languageTag.contains(q, ignoreCase = true)
            }
        }
    }
    val showSystemDefault = remember(query, systemLabel, systemDesc) {
        val q = query.trim()
        q.isEmpty() ||
            systemLabel.contains(q, ignoreCase = true) ||
            systemDesc.contains(q, ignoreCase = true)
    }

    val selectedFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    // Land on the currently selected row once when the screen opens; fall back to search if the
    // row was filtered out. requestFocus() reports failure via Boolean, not exceptions.
    fun requestPreferredFocus() {
        if (!selectedFocus.requestFocus()) {
            searchFocus.requestFocus()
        }
    }
    LaunchedEffect(Unit) { requestPreferredFocus() }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .focusProperties {
                onEnter = { requestPreferredFocus() }
            }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Header(title = stringResource(R.string.settings_language), onBack = onBack)
        Spacer(Modifier.height(12.dp))

        SearchBar(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.settings_language_search_hint),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus),
            surface = GlassSurface.CARDS,
        )
        Spacer(Modifier.height(12.dp))

        if (showSystemDefault) {
            LanguageRow(
                endonym = stringResource(R.string.settings_language_system_default),
                englishName = stringResource(R.string.settings_language_system_default_description),
                coverage = null,
                selected = currentTag.isEmpty(),
                onClick = { viewModel.setLocale(SupportedLocales.SYSTEM_DEFAULT_TAG) },
                modifier = if (currentTag.isEmpty()) {
                    Modifier.focusRequester(selectedFocus)
                } else {
                    Modifier
                },
            )
            if (filtered.isNotEmpty()) {
                Divider()
            }
        }

        filtered.forEach { locale ->
            val selected = locale.languageTag == currentTag
            LanguageRow(
                endonym = locale.endonym,
                englishName = locale.englishName,
                coverage = locale.coverage,
                selected = selected,
                onClick = { viewModel.setLocale(locale.languageTag) },
                modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }
    }
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
