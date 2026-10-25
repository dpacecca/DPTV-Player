package tv.own.owntv.features.sport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx

/**
 * The Sport section (v2.6.0, OwnTV's Sports Hub): the hub, the match page opened from it (Back
 * returns to the hub, on the card it came from) and a league's page. Playback stays in
 * `LiveViewModel` — a channel is played through the shared zap path. Every setting is in
 * Settings › Sport; the hub's ⚙ chip opens it ([onOpenSettings]).
 */
@Composable
fun SportsScreen(
    onPlayChannel: (Long, List<ChannelEntity>) -> Unit,
    onChildFocused: () -> Unit,
    previewEnabled: Boolean = true,
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    onContentScrolled: (Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    contentStart: Dp = 150.mpx,
    modifier: Modifier = Modifier,
    /** (v2.6.1) False while the player is full screen over the section: its Back is the player's. */
    active: Boolean = true,
    /** (v2.6.1) A match or league page is open: the shell hides its menu and keeps Back for the page. */
    onPageOpen: (Boolean) -> Unit = {},
) {
    val vm: SportsViewModel = koinViewModel()
    val liveVm: LiveViewModel = koinViewModel()
    val fixture by vm.selectedFixture.collectAsStateWithLifecycle()
    val leagueId by vm.openLeagueId.collectAsStateWithLifecycle()
    val matchesState by vm.matches.collectAsStateWithLifecycle()
    val preferredLanguages by vm.preferredLanguages.collectAsStateWithLifecycle()
    val languageAsked by vm.languageAsked.collectAsStateWithLifecycle()
    val livePreviewSetting by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    val effectivePreview = previewEnabled && livePreviewSetting
    var showLanguage by remember { mutableStateOf(false) }
    // The league page's way back: to the match it was opened from, or the hub.
    var leagueFromMatch by remember { mutableStateOf<Fixture?>(null) }
    // (v2.6.1) The match page's way back: to the league it was opened from, or the hub.
    var matchFromLeague by remember { mutableStateOf<String?>(null) }
    // (v2.6.1) The game a league page puts focus back on (Back from a match opened from it).
    var leagueRestore by remember { mutableStateOf<String?>(null) }
    // (v2.6.1) Counted asks for the hub to put focus back on [SportsViewModel.hubReturnKey] (Back from a page,
    // or from Settings › Sport); a fresh visit from the menu leaves focus on the menu.
    var hubFocusAsk by remember { mutableIntStateOf(if (restoreFocus && vm.hubReturnKey != null) 1 else 0) }
    // (v2.6.1) The hub keeps its filters and scroll while a page is over it, so the card is there to go back to.
    val hubState = rememberSaveableStateHolder()

    // Live scores refresh while Sport is on screen (Settings › Sport › Live refresh).
    DisposableEffect(vm) {
        vm.setWatching(SportsViewModel.Watcher.HUB, true)
        onDispose { vm.setWatching(SportsViewModel.Watcher.HUB, false) }
    }
    // The hub never scrolls the shell's top bar away; the pages are fixed.
    LaunchedEffect(Unit) { onContentScrolled(false) }
    LaunchedEffect(restoreFocus) {
        if (!restoreFocus) return@LaunchedEffect
        delay(60)
        onRestored()
    }

    // A different (or no) game: drop the previous game's in-page preview. Tracked as a change, NOT a
    // dispose handler — going full screen disposes this screen, and stopping here would abort the very
    // channel that was just started. The null sentinel keeps a fresh composition from stopping it.
    var previewedFixtureId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(fixture?.id) {
        val id = fixture?.id
        if (previewedFixtureId != null && previewedFixtureId != id) liveVm.stopPreview()
        previewedFixtureId = id
    }
    // The first time a game's channels are ready, ask which languages to show; at most once.
    LaunchedEffect(matchesState, languageAsked) {
        val content = matchesState as? SportsViewModel.MatchesState.Content ?: return@LaunchedEffect
        if (!languageAsked && content.matches.isNotEmpty()) showLanguage = true
    }

    val current = fixture
    val openLeague = leagueId
    val page = sportsPage(fixtureOpen = current != null, leagueOpen = openLeague != null)
    // (v2.6.1) The shell hides the menu over a page and leaves its Back to it (see [railBackAsksToExit]).
    LaunchedEffect(page) { onPageOpen(page != SportsPage.HUB) }
    DisposableEffect(Unit) { onDispose { onPageOpen(false) } }
    val toHub = { hubFocusAsk++ }
    val pageActive = active && !showLanguage
    // (v2.6.1) Focus anywhere in Sport (the hub or a page) is the content's, never the menu's: a page that
    // did not say so left the shell thinking the menu had it, so Back asked to leave the app.
    Box(modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onChildFocused() }) {
        when {
            openLeague != null -> SportsLeagueScreen(
                vm = vm,
                leagueId = openLeague,
                onOpenMatch = { f -> leagueFromMatch = null; leagueRestore = null; matchFromLeague = openLeague; vm.openFixture(f) },
                onBack = {
                    val back = sportsBack(SportsPage.LEAGUE, matchFromLeague = false, leagueFromMatch = leagueFromMatch != null)
                    val match = leagueFromMatch
                    leagueFromMatch = null
                    leagueRestore = null
                    vm.openLeague(null)
                    if (back == SportsPage.MATCH && match != null) vm.openFixture(match) else toHub()
                },
                contentStart = contentStart,
                active = pageActive,
                restoreMatchId = leagueRestore,
            )
            current != null -> SportsMatchScreen(
                vm = vm,
                liveVm = liveVm,
                fixture = current,
                effectivePreview = effectivePreview,
                onPlayChannel = onPlayChannel,
                onOpenLeague = { id -> leagueFromMatch = current; vm.selectFixture(null); vm.openLeague(id) },
                onBack = {
                    val from = matchFromLeague
                    matchFromLeague = null
                    if (sportsBack(SportsPage.MATCH, matchFromLeague = from != null, leagueFromMatch = false) == SportsPage.LEAGUE && from != null) {
                        // Back to the league, on the game's row.
                        leagueRestore = current.id
                        vm.selectFixture(null)
                        vm.openLeague(from)
                    } else {
                        vm.selectFixture(null)
                        toHub()
                    }
                },
                contentStart = contentStart,
                active = pageActive,
            )
            else -> hubState.SaveableStateProvider(HubStateKey) {
                SportsHubScreen(
                    vm = vm,
                    onOpenMatch = { f -> vm.hubReturnKey = f.id; leagueFromMatch = null; matchFromLeague = null; vm.openFixture(f) },
                    onOpenLeague = { id, fromKey -> vm.hubReturnKey = fromKey; leagueFromMatch = null; vm.openLeague(id) },
                    onOpenSettings = { vm.hubReturnKey = HubSettingsKey; onOpenSettings() },
                    onChildFocused = onChildFocused,
                    onEntryHook = onEntryHook,
                    contentStart = contentStart,
                    restoreKey = vm.hubReturnKey,
                    restoreAsk = hubFocusAsk,
                )
            }
        }
        if (showLanguage) {
            SportLanguageDialog(
                selected = preferredLanguages,
                onChange = { vm.setPreferredLanguages(it) },
                onDismiss = {
                    showLanguage = false
                    vm.markLanguageAsked()
                },
            )
        }
    }
}

/** (v2.6.1) Which Sport page is on screen; a league page shows over a match it was opened from. */
internal enum class SportsPage { HUB, MATCH, LEAGUE }

internal fun sportsPage(fixtureOpen: Boolean, leagueOpen: Boolean): SportsPage = when {
    leagueOpen -> SportsPage.LEAGUE
    fixtureOpen -> SportsPage.MATCH
    else -> SportsPage.HUB
}

/**
 * (v2.6.1) Where Back from [page] goes: a match opened from a league goes back to that league, a league
 * opened from a match back to that match, anything else to the hub (on the card it was opened from).
 */
internal fun sportsBack(page: SportsPage, matchFromLeague: Boolean, leagueFromMatch: Boolean): SportsPage = when (page) {
    SportsPage.MATCH -> if (matchFromLeague) SportsPage.LEAGUE else SportsPage.HUB
    SportsPage.LEAGUE -> if (leagueFromMatch) SportsPage.MATCH else SportsPage.HUB
    SportsPage.HUB -> SportsPage.HUB
}

/** (v2.6.1) The hub's saved state while a page is over it. */
private const val HubStateKey = "hub"

/**
 * Language selection for the matched channels: pick one or more languages (or "all"). Shown the
 * first time channels are matched; Settings › Sport › Channel languages changes it later. The choice
 * filters the channel list by the country tag in a channel's name — see [SportLanguages].
 */
@Composable
private fun SportLanguageDialog(
    selected: Set<SportLanguage>,
    onChange: (Set<SportLanguage>) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 150.mpx, bottom = 24.mpx)
                    .width(640.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
            ) {
                StageMenu(Modifier.verticalScroll(rememberScrollState())) {
                    StageMenuHeader(
                        title = stringResource(R.string.sport_language),
                        subtitle = stringResource(R.string.sport_language_subtitle),
                    )
                    StageMenuItem(
                        text = stringResource(R.string.sport_language_all),
                        icon = OwnTVIcon.LANGUAGE,
                        checked = selected.isEmpty(),
                        onClick = { onChange(emptySet()) },
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    SportLanguages.order.forEach { language ->
                        StageMenuItem(
                            text = stringResource(language.labelRes),
                            icon = OwnTVIcon.LANGUAGE,
                            checked = language in selected,
                            onClick = { onChange(toggleLanguage(selected, language)) },
                        )
                    }
                }
            }
        }
    }
}

/** Multi-select: from "all" the first pick narrows to that one; dropping the last one means all. */
internal fun toggleLanguage(selected: Set<SportLanguage>, language: SportLanguage): Set<SportLanguage> = when {
    selected.isEmpty() -> setOf(language)
    language in selected -> selected - language
    else -> selected + language
}

/** "Tomorrow", "Yesterday" or a short date for a game's day; null for today (the time says enough). */
internal fun sportDayLabel(dateMs: Long, tomorrow: String, yesterday: String): String? {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate()
    val now = LocalDate.now(zone)
    return when (date) {
        now -> null
        now.plusDays(1) -> tomorrow
        now.minusDays(1) -> yesterday
        else -> date.format(DateTimeFormatter.ofPattern(DayPattern))
    }
}

private const val DayPattern = "EEE d.M."
