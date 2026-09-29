package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.trapVerticalFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * Maintainer-only tools, opened from More › Developer (as on the phone). Reached only when
 * BuildConfig.DEV_TOOLS is set, so R8 removes this screen from every published APK; English by the
 * same rule.
 */
@Composable
fun DeveloperScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: HomeSettingsViewModel = koinViewModel()
    val devRebuild by vm.devRebuild.collectAsStateWithLifecycle()
    var showStageSpecimen by remember { mutableStateOf(false) }
    if (showStageSpecimen) {
        StageSpecimenScreen(onBack = { showStageSpecimen = false }, modifier = modifier)
        return
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .focusProperties { onEnter = { runCatching { firstFocus.requestFocus() } } }
            .focusGroup()
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Text("Developer", style = MaterialTheme.typography.headlineLarge, color = OwnTVTheme.colors.onSurface)
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().trapVerticalFocusExit(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row2(
                    icon = OwnTVIcon.SHARE,
                    title = "Rebuild Now Trending",
                    desc = "Forces a fresh TMDB trending download for every playlist, ignoring the multi-day fetch timer.",
                    chip = when (devRebuild) {
                        HomeSettingsViewModel.DevRebuildState.STARTED -> stringResource(R.string.settings_rebuilding)
                        else -> null
                    },
                    onClick = { vm.rebuildTrendingNow() },
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            item {
                Row2(
                    icon = OwnTVIcon.PALETTE,
                    title = "Stage specimen",
                    desc = "Every Stage component on one page, for checking against the mockup references.",
                    chevron = true,
                    onClick = { showStageSpecimen = true },
                )
            }
        }
    }
}
