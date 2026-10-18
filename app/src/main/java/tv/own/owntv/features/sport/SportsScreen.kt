package tv.own.owntv.features.sport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.companion.CompanionSelectItem
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.epg.GuideVideo
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.StageCompanionStatus
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StageSearchField
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.core.theme.DptvPalette
import tv.own.owntv.core.theme.teamColor
import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Sports hub (Stage): league rail, a Live / Upcoming / Past tabbed fixture list, and a detail pane
 * with venue, broadcasts, standings and the matched user channels — one tap
 * on a channel starts playback through the shared LiveViewModel zap path.
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
    contentStart: Dp = 150.mpx,
    modifier: Modifier = Modifier,
) {
    val vm: SportsViewModel = koinViewModel()
    val liveVm: LiveViewModel = koinViewModel()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val activeLeagues by vm.activeLeagues.collectAsStateWithLifecycle()
    val selectedLeagueId by vm.selectedLeagueId.collectAsStateWithLifecycle()
    val selectedFixture by vm.selectedFixture.collectAsStateWithLifecycle()
    val matchesState by vm.matches.collectAsStateWithLifecycle()
    val standings by vm.standings.collectAsStateWithLifecycle()
    val standingsLoading by vm.standingsLoading.collectAsStateWithLifecycle()
    val myTeams by vm.myTeamFixtures.collectAsStateWithLifecycle()
    val followedTeams by vm.followedTeams.collectAsStateWithLifecycle()
    val followedPlayers by vm.followedPlayers.collectAsStateWithLifecycle()
    val livePreviewSetting by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    val previewArmed by liveVm.previewArmed.collectAsStateWithLifecycle()
    val effectivePreview = previewEnabled && livePreviewSetting
    val preferredLanguages by vm.preferredLanguages.collectAsStateWithLifecycle()
    val hiddenLanguages by vm.hiddenLanguages.collectAsStateWithLifecycle()
    val languageAsked by vm.languageAsked.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    val remoteState by vm.remoteState.collectAsStateWithLifecycle()
    val sportOrder by vm.sportOrder.collectAsStateWithLifecycle()
    val strictChannels by vm.strictChannels.collectAsStateWithLifecycle()

    // Live scores refresh while the hub is on screen (Sport settings › Channels › Live refresh).
    DisposableEffect(vm) {
        vm.setWatching(SportsViewModel.Watcher.HUB, true)
        onDispose { vm.setWatching(SportsViewModel.Watcher.HUB, false) }
    }

    // Hold OK on a league in the rail: move mode (v5.2 port). Up/Down move it, OK or Back saves. The
    // order is kept locally while moving and written once on save, so the rail does not wait on
    // DataStore for every step; it lets go of the local copy once the saved order comes back.
    var movingLeagueId by remember { mutableStateOf<String?>(null) }
    var movingOrder by remember { mutableStateOf<List<String>?>(null) }
    val railLeagues = movingOrder?.let { order -> order.mapNotNull { id -> leagues.firstOrNull { it.league.id == id } } } ?: leagues
    LaunchedEffect(leagues) { if (movingLeagueId == null) movingOrder = null }
    fun commitLeagueMove() {
        val order = movingOrder
        movingLeagueId = null
        if (order != null) vm.saveLeagueOrder(order)
    }
    BackHandler(enabled = movingLeagueId != null) { commitLeagueMove() }

    val favouritesSelected = selectedLeagueId == SportsViewModel.FavouritesId
    val fixtures by vm.currentFixtures.collectAsStateWithLifecycle()
    val boardError by vm.currentError.collectAsStateWithLifecycle()
    val boardLoading by vm.currentLoading.collectAsStateWithLifecycle()

    // Live / Demnächst / Vergangen filter for the fixtures list.
    val tab by vm.tab.collectAsStateWithLifecycle()
    val visibleFixtures = remember(fixtures, tab) { fixturesFor(fixtures, tab) }

    // Phone picker payload: labels/sport groups resolved here, where stringResource is available.
    // The phone page orders its groups by first appearance, so the items go in sport order.
    val phoneTitle = stringResource(R.string.sport_phone_title)
    val phoneTeamGroup = stringResource(R.string.sport_phone_teams)
    val phonePlayerGroup = stringResource(R.string.sport_phone_players)
    val phoneLeagueItems = SportsLeagues.sortedBy { it.sportGroup.ordinal }.map { league ->
        CompanionSelectItem(
            id = league.id,
            label = stringResource(league.labelRes),
            group = stringResource(league.sportGroup.labelRes),
            selected = league.id in activeLeagues,
        )
    }
    val phoneLeagueLabels = SportsLeagues.associate { it.id to stringResource(it.labelRes) }

    val leagueFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    fun leagueRequester(id: String) = leagueFocus.getOrPut(id) { FocusRequester() }
    val gameFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    fun gameRequester(id: String) = gameFocus.getOrPut(id) { FocusRequester() }
    val matchFocus = remember { mutableStateMapOf<Long, FocusRequester>() }
    fun matchRequester(id: Long) = matchFocus.getOrPut(id) { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val addLeaguesFocus = remember { FocusRequester() }
    val favouritesFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val railState = rememberLazyListState()

    // While moving, keep the moved league focused and on screen.
    LaunchedEffect(movingOrder) {
        val id = movingLeagueId ?: return@LaunchedEffect
        val index = railLeagues.indexOfFirst { it.league.id == id }
        val info = railState.layoutInfo
        val shown = info.visibleItemsInfo.any { it.index == index && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
        if (index >= 0 && !shown) runCatching { railState.animateScrollToItem((index - 1).coerceAtLeast(0)) }
        withFrameNanos { }
        runCatching { leagueRequester(id).requestFocus() }
    }

    suspend fun focusGame(id: String) {
        if (runCatching { gameRequester(id).requestFocus() }.isSuccess) return
        val index = visibleFixtures.indexOfFirst { it.id == id }
        if (index >= 0) runCatching { listState.scrollToItem(index) }
        withFrameNanos { }
        runCatching { gameRequester(id).requestFocus() }
    }

    val entry: () -> Boolean = {
        val firstFixture = visibleFixtures.firstOrNull() ?: fixtures.firstOrNull()
        if (firstFixture != null) runCatching { gameRequester(firstFixture.id).requestFocus() }.isSuccess
        else runCatching { leagueRequester(leagues.firstOrNull()?.league?.id.orEmpty()).requestFocus() }.isSuccess
    }
    DisposableEffect(onEntryHook, fixtures) {
        onEntryHook?.invoke(entry)
        onDispose { onEntryHook?.invoke(null) }
    }

    // Detail always populated: pick the live game first, else the next upcoming one.
    LaunchedEffect(fixtures) {
        if (selectedFixture == null && fixtures.isNotEmpty()) {
            val pick = fixtures.firstOrNull { it.state == FixtureState.LIVE }
                ?: fixtures.filter { it.state == FixtureState.UPCOMING }.minByOrNull { it.dateMs }
                ?: fixtures.maxByOrNull { it.dateMs }
            vm.selectFixture(pick)
        }
    }

    LaunchedEffect(restoreFocus) {
        if (!restoreFocus) return@LaunchedEffect
        withFrameNanos { }
        val id = selectedFixture?.id
        if (id != null) focusGame(id) else entry()
        onRestored()
    }

    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 } }
    LaunchedEffect(scrolled) { onContentScrolled(scrolled) }

    // A different (or no) game selected: drop the previous game's in-pane preview stream. Tracked as a
    // change, NOT a dispose handler, on purpose — going full-screen hides the whole browse UI and disposes
    // this screen, and stopping here would abort the very channel that was just started (the trap
    // LiveScreen documents). The null sentinel keeps a fresh composition (first entry, or back from the
    // full-screen/mini player) from stopping the stream that is still playing.
    var previewedFixtureId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedFixture?.id) {
        val id = selectedFixture?.id
        if (previewedFixtureId != null && previewedFixtureId != id) liveVm.stopPreview()
        previewedFixtureId = id
    }

    // The first time a fixture's channels are ready, ask which languages to show — before the list
    // can be read. Choosing (or dismissing) records the answer, so this runs at most once.
    LaunchedEffect(matchesState, languageAsked) {
        val content = matchesState as? SportsViewModel.MatchesState.Content ?: return@LaunchedEffect
        if (!languageAsked && content.matches.isNotEmpty()) showLanguage = true
    }

    BoxWithConstraints(modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onChildFocused() }) {
        val screenW = maxWidth
        fun fx(px: Int) = screenW * (px / 1920f)
        Row(
            Modifier
                .padding(start = contentStart + 20.mpx, top = 52.mpx, end = 64.mpx)
                .fillMaxSize(),
        ) {
            // League rail.
            Column(Modifier.width(fx(330)).fillMaxHeight()) {
                Text(stringResource(R.string.sport_title), style = stageText(46, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Favourites aggregates every active league plus the user's followed teams.
                StageSurface(
                    onClick = { vm.selectFavourites() },
                    radius = 18.mpx,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.mpx).focusRequester(favouritesFocus),
                    idle = if (favouritesSelected) Modifier.background(stageAccent.accent.copy(alpha = 0.14f), RoundedCornerShape(18.mpx)) else Modifier,
                ) { focused ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.mpx, vertical = 12.mpx),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.mpx),
                    ) {
                        OwnTVIcon(
                            OwnTVIcon.STAR,
                            if (focused) stageAccent.onAccent else if (favouritesSelected) stageAccent.accent else StageColors.NavText,
                            Modifier.size(24.mpx),
                        )
                        Text(
                            stringResource(R.string.sport_favourites),
                            style = stageText(20, if (favouritesSelected) 700 else 500),
                            color = if (focused) stageAccent.onAccent else StageColors.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.mpx, bottom = 10.mpx),
                ) {
                    Text(stringResource(R.string.sport_leagues), style = stageText(20, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // Discoverability for the move mode: the rail's own key hint.
                    Text(stringResource(R.string.sport_order_hint), style = stageText(14, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                LazyColumn(state = railState, verticalArrangement = Arrangement.spacedBy(8.mpx), modifier = Modifier.fillMaxHeight()) {
                    items(railLeagues, key = { it.league.id }) { league ->
                        val selected = league.league.id == selectedLeagueId
                        val moving = league.league.id == movingLeagueId
                        StageSurface(
                            onClick = { if (moving) commitLeagueMove() else if (movingLeagueId == null) vm.selectLeague(league.league.id) },
                            onLongClick = {
                                if (movingLeagueId == null) {
                                    movingOrder = railLeagues.map { it.league.id }
                                    movingLeagueId = league.league.id
                                }
                            },
                            radius = 18.mpx,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(leagueRequester(league.league.id))
                                .onPreviewKeyEvent { e ->
                                    if (!moving) return@onPreviewKeyEvent false
                                    val up = when (e.key) {
                                        Key.DirectionUp -> true
                                        Key.DirectionDown -> false
                                        // Left/Right would carry the league out of the rail mid-move.
                                        else -> return@onPreviewKeyEvent e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                                    }
                                    if (e.type == KeyEventType.KeyDown) {
                                        movingOrder = SportsOrder.move(movingOrder ?: railLeagues.map { it.league.id }, league.league.id, up)
                                    }
                                    true
                                },
                            idle = when {
                                moving -> Modifier.background(stageAccent.accent.copy(alpha = 0.30f), RoundedCornerShape(18.mpx))
                                selected -> Modifier.background(stageAccent.accent.copy(alpha = 0.14f), RoundedCornerShape(18.mpx))
                                else -> Modifier
                            },
                        ) { focused ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.mpx, vertical = 12.mpx),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.mpx),
                            ) {
                                OwnTVIcon(
                                    if (moving) OwnTVIcon.MOVE else OwnTVIcon.SPORT,
                                    if (focused) stageAccent.onAccent else if (selected || moving) stageAccent.accent else StageColors.NavText,
                                    Modifier.size(24.mpx),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(league.league.labelRes), style = stageText(20, if (selected) 700 else 500), color = if (focused) stageAccent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (moving) {
                                        Text(stringResource(R.string.sport_move_hint), style = stageText(14, 500), color = if (focused) stageAccent.onAccent.copy(alpha = 0.8f) else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (league.liveCount > 0) {
                                    LiveDot(league.liveCount.toString())
                                } else if (league.loading) {
                                    Text(stringResource(R.string.sport_loading), style = stageText(14, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    item(key = "add-leagues") {
                        StageSurface(
                            onClick = { showSettings = true },
                            radius = 18.mpx,
                            modifier = Modifier.fillMaxWidth().focusRequester(addLeaguesFocus),
                        ) { focused ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.mpx, vertical = 12.mpx),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.mpx),
                            ) {
                                OwnTVIcon(
                                    OwnTVIcon.ADD,
                                    if (focused) stageAccent.onAccent else StageColors.NavText,
                                    Modifier.size(24.mpx),
                                )
                                Text(
                                    stringResource(R.string.sport_add_leagues),
                                    style = stageText(20, 500),
                                    color = if (focused) stageAccent.onAccent else StageColors.Muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            // Fixtures (extra top padding like the detail pane: shell clock/weather overlays the top).
            Column(Modifier.weight(1.35f).fillMaxHeight().padding(start = 20.mpx, end = 20.mpx, top = 64.mpx)) {
                val leagueName = if (favouritesSelected) {
                    stringResource(R.string.sport_favourites)
                } else {
                    leagues.firstOrNull { it.league.id == selectedLeagueId }
                        ?.let { stringResource(it.league.labelRes) }.orEmpty()
                }
                Text(leagueName, style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.mpx), modifier = Modifier.fillMaxWidth().padding(top = 10.mpx)) {
                    StageSurface(
                        onClick = { showSettings = true },
                        radius = 16.mpx,
                        modifier = Modifier.focusRequester(settingsFocus),
                    ) { focused ->
                        Row(Modifier.padding(horizontal = 14.mpx, vertical = 8.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                            OwnTVIcon(OwnTVIcon.SETTINGS, if (focused) stageAccent.onAccent else StageColors.Text, Modifier.size(20.mpx))
                            Text(stringResource(R.string.sport_settings), style = stageText(17, 600), color = if (focused) stageAccent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    StageSurface(
                        onClick = { vm.refreshSelected() },
                        radius = 16.mpx,
                        modifier = Modifier.focusRequester(refreshFocus),
                    ) { focused ->
                        Row(Modifier.padding(horizontal = 14.mpx, vertical = 8.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                            OwnTVIcon(OwnTVIcon.REFRESH, if (focused) stageAccent.onAccent else StageColors.Text, Modifier.size(20.mpx))
                            Text(stringResource(R.string.sport_retry), style = stageText(17, 600), color = if (focused) stageAccent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (favouritesSelected && myTeams.isNotEmpty()) {
                    Text(stringResource(R.string.sport_my_teams), style = stageText(20, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.mpx, bottom = 8.mpx))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                        items(myTeams, key = { it.id }) { fixture ->
                            StageSurface(
                                onClick = { vm.selectFixture(fixture) },
                                radius = 14.mpx,
                                focusStyle = StageFocus.FX,
                                modifier = Modifier.focusRequester(gameRequester(fixture.id)),
                            ) { focused ->
                                FixtureChip(fixture, followedTeams, focused)
                            }
                        }
                    }
                }
                // Live / Demnächst / Vergangen — the tabs filter the list right below them.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.mpx),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.mpx),
                ) {
                    FixtureTabPill(stringResource(R.string.sport_tab_live), tab == SportsViewModel.FixtureTab.LIVE) {
                        vm.setTab(SportsViewModel.FixtureTab.LIVE)
                    }
                    FixtureTabPill(stringResource(R.string.sport_tab_upcoming), tab == SportsViewModel.FixtureTab.UPCOMING) {
                        vm.setTab(SportsViewModel.FixtureTab.UPCOMING)
                    }
                    FixtureTabPill(stringResource(R.string.sport_tab_past), tab == SportsViewModel.FixtureTab.PAST) {
                        vm.setTab(SportsViewModel.FixtureTab.PAST)
                    }
                }
                when {
                    boardLoading && fixtures.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(top = 60.mpx), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.sport_loading), style = stageText(22, 500), color = StageColors.Muted)
                        }
                    }
                    boardError && fixtures.isEmpty() -> {
                        Column(Modifier.fillMaxWidth().padding(top = 60.mpx), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.mpx)) {
                            Text(stringResource(R.string.sport_load_failed), style = stageText(22, 500), color = StageColors.Muted)
                            StageSurface(onClick = { vm.refreshSelected() }, radius = 16.mpx) { focused ->
                                Text(stringResource(R.string.sport_retry), style = stageText(19, 600), color = if (focused) stageAccent.onAccent else StageColors.Text, modifier = Modifier.padding(horizontal = 22.mpx, vertical = 10.mpx))
                            }
                        }
                    }
                    fixtures.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(top = 60.mpx), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.sport_no_games), style = stageText(22, 500), color = StageColors.Muted)
                        }
                    }
                    visibleFixtures.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(top = 60.mpx), contentAlignment = Alignment.Center) {
                            Text(stringResource(emptyTabLabel(tab)), style = stageText(22, 500), color = StageColors.Muted)
                        }
                    }
                    else -> {
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxHeight().padding(top = 12.mpx)) {
                            items(visibleFixtures, key = { it.id }) { fixture ->
                                val selected = fixture.id == selectedFixture?.id
                                StageSurface(
                                    onClick = { vm.selectFixture(fixture) },
                                    radius = 18.mpx,
                                    modifier = Modifier.fillMaxWidth().focusRequester(gameRequester(fixture.id)),
                                    idle = if (selected) Modifier.background(stageAccent.accent.copy(alpha = 0.14f), RoundedCornerShape(18.mpx)) else Modifier,
                                ) { focused ->
                                    FixtureCard(fixture, followedTeams, focused, selected)
                                }
                            }
                            item { Spacer(Modifier.height(40.mpx)) }
                        }
                    }
                }
            }
            // Detail pane (extra top padding: shell clock/weather overlays the top-right).
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 64.mpx)) {
                val fixture = selectedFixture
                if (fixture == null) {
                    Text(stringResource(R.string.sport_details), style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.mpx))
                    Text(stringResource(R.string.sport_no_games), style = stageText(20, 500), color = StageColors.Muted)
                } else {
                    FixtureDetail(
                        fixture = fixture,
                        followedTeams = followedTeams,
                        followedPlayers = followedPlayers,
                        matchesState = matchesState,
                        preferredLanguages = preferredLanguages,
                        strictChannels = strictChannels,
                        standings = standings,
                        standingsLoading = standingsLoading,
                        matchRequester = ::matchRequester,
                        onPlayMatch = { match, list -> vm.playMatch(match, list, onPlayChannel) },
                        onToggleTeam = { leagueId, abbrev -> vm.toggleTeamFollowed(leagueId, abbrev) },
                        onTogglePlayer = { leagueId, playerId -> vm.togglePlayerFollowed(leagueId, playerId) },
                        sportsVm = vm,
                        liveVm = liveVm,
                        effectivePreview = effectivePreview,
                        previewArmed = previewArmed,
                    )
                }
            }
        }
        if (showSettings) {
            SportsSettingsDialog(
                leagues = SportsLeagues,
                active = activeLeagues,
                onToggleLeague = { vm.toggleLeagueFollowed(it) },
                onToggleSport = { vm.toggleSport(it) },
                sportOrder = sportOrder,
                onSaveSportOrder = { vm.saveSportOrder(it) },
                channelsContent = { SportsChannelOptions(vm) },
                preferred = preferredLanguages,
                onPreferredChange = { vm.setPreferredLanguages(it) },
                hidden = hiddenLanguages,
                onToggleHidden = { vm.toggleLanguageHidden(it) },
                onManageOnPhone = {
                    showSettings = false
                    showPhone = true
                    vm.startPhonePicker(
                        CompanionLink.DEFAULT_PORT,
                        phoneTitle,
                        phoneLeagueItems,
                        phoneLeagueLabels,
                        phoneTeamGroup,
                        phonePlayerGroup,
                    )
                },
                onDismiss = { showSettings = false },
            )
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
        if (showPhone) {
            SportsPhoneDialog(
                state = remoteState,
                onDismiss = {
                    showPhone = false
                    vm.stopPhonePicker()
                },
            )
        }
    }
}

/** The detail pane's two lists. */
private enum class DetailTab { CHANNELS, BROADCASTS }

/**
 * The round/stage line: "Week 6", ESPN's round ("Second Round") and its headline note ("NFL London
 * Games"), joined; null when ESPN gives none of them (a plain league matchday).
 */
@Composable
private fun stageLine(fixture: Fixture): String? {
    val parts = listOfNotNull(
        fixture.week?.let { stringResource(R.string.sport_round_week, it) },
        fixture.round,
        fixture.note,
    )
    return if (parts.isEmpty()) null else parts.joinToString(stringResource(R.string.sport_detail_separator))
}

/** The fixtures shown for [tab]: Live / Upcoming (soonest first) / Past (most recent first, bounded). */
private fun fixturesFor(fixtures: List<Fixture>, tab: SportsViewModel.FixtureTab): List<Fixture> = when (tab) {
    SportsViewModel.FixtureTab.LIVE -> fixtures.filter { it.state == FixtureState.LIVE }.sortedBy { it.dateMs }
    SportsViewModel.FixtureTab.UPCOMING -> fixtures.filter { it.state == FixtureState.UPCOMING }.sortedBy { it.dateMs }
    SportsViewModel.FixtureTab.PAST -> fixtures.filter { it.state == FixtureState.FINISHED }.sortedByDescending { it.dateMs }.take(30)
}

/** Empty-state text for [tab]. */
private fun emptyTabLabel(tab: SportsViewModel.FixtureTab): Int = when (tab) {
    SportsViewModel.FixtureTab.LIVE -> R.string.sport_no_live_games
    SportsViewModel.FixtureTab.UPCOMING -> R.string.sport_no_upcoming_games
    SportsViewModel.FixtureTab.PAST -> R.string.sport_no_past_games
}

/** One tab in the fixtures column (Live / Demnächst / Vergangen). */
@Composable
private fun FixtureTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = stageAccent
    StageSurface(
        onClick = onClick,
        radius = 14.mpx,
        idle = if (selected) Modifier.background(accent.accent.copy(alpha = 0.14f), RoundedCornerShape(14.mpx)) else Modifier,
    ) { focused ->
        Text(
            label,
            style = stageText(18, if (selected) 700 else 500),
            color = if (focused) accent.onAccent else if (selected) accent.accent else StageColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.mpx, vertical = 9.mpx),
        )
    }
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

@Composable
private fun LiveDot(count: String) {
    Box(
        Modifier
            .background(stageAccent.accent, RoundedCornerShape(12.mpx))
            .padding(horizontal = 10.mpx, vertical = 4.mpx),
        contentAlignment = Alignment.Center,
    ) {
        Text(count, style = stageText(15, 700), color = stageAccent.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TeamLogo(url: String?, size: Dp) {
    if (url.isNullOrBlank()) {
        Spacer(Modifier.size(size))
    } else {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
    }
}

/**
 * A club's 6-px bar in a theme-safe tone: ESPN's raw `colorHex` can be near-black and would vanish
 * on the dark page, so the hue is bucketed into [DptvPalette.Team] and the theme-correct tone
 * of the pair is used (brand guide §5.6). Team colours never leak into buttons, focus or text.
 */
@Composable
private fun teamColor(hex: String?): Color? {
    val clean = hex?.takeIf { it.isNotBlank() }?.removePrefix(HashPrefix) ?: return null
    val argb = runCatching { (ColorPrefix + clean).toLong(16) }.getOrNull()?.toInt() ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    val (hue, sat, value) = Triple(hsv[0], hsv[1], hsv[2])
    val pair = if (sat < 0.18f || value < 0.12f || (sat < 0.3f && value > 0.85f)) {
        DptvPalette.Team.Slate
    } else when (hue) {
        in 12f..38f -> DptvPalette.Team.Orange
        in 38f..66f -> DptvPalette.Team.Amber
        in 66f..150f -> DptvPalette.Team.Green
        in 150f..185f -> DptvPalette.Team.Sky
        in 185f..235f -> DptvPalette.Team.Blue
        in 235f..265f -> DptvPalette.Team.Indigo
        in 265f..300f -> DptvPalette.Team.Violet
        in 300f..345f -> DptvPalette.Team.Crimson
        else -> DptvPalette.Team.Crimson
    }
    return Color(teamColor(pair, !LocalStagePalette.current.isLight))
}

@Composable
private fun FixtureCard(fixture: Fixture, followedTeams: Set<String>, focused: Boolean, selected: Boolean) {
    val accent = stageAccent
    val titleColor = if (focused) accent.onAccent else StageColors.Text
    val dimColor = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim
    val today = stringResource(R.string.sport_today_word)
    val tomorrow = stringResource(R.string.sport_tomorrow_word)
    val yesterday = stringResource(R.string.sport_yesterday_word)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.mpx, vertical = 12.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.mpx),
    ) {
        Box(Modifier.width(5.mpx).height(52.mpx).background(teamColor(fixture.home?.colorHex) ?: Color.Transparent, RoundedCornerShape(3.mpx)))
        Column(Modifier.weight(1f)) {
            val home = fixture.home
            val away = fixture.away
            if (home != null && away != null) {
                listOf(home, away).forEach { team ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                        TeamLogo(team.logoUrl, 32.mpx)
                        Text(team.abbreviation, style = stageText(22, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            team.score ?: fixture.statusLine,
                            style = stageText(22, 700),
                            color = if (fixture.state == FixtureState.LIVE) accent.accent else titleColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (fixture.format == FixtureFormat.VERSUS) {
                // A duel (tennis/MMA): both athletes on an equal footing, same colour; the winner is
                // bold with a check, never a different colour (names are not scores).
                fixture.entries.take(2).forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                        TeamLogo(entry.logoUrl, 32.mpx)
                        Text(
                            entry.name,
                            style = stageText(22, if (entry.winner) 700 else 500),
                            color = titleColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        entry.detail?.let { detail ->
                            Text(detail, style = stageText(22, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (entry.winner) OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(18.mpx))
                    }
                }
            } else if (fixture.format == FixtureFormat.EVENT) {
                // A single occasion (F1 Grand Prix): the race name + circuit, no participants at all.
                Text(
                    fixture.title ?: fixture.statusLine,
                    style = stageText(22, 700), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                fixture.venue?.let { venue ->
                    Text(venue, style = stageText(16, 500), color = dimColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                // A leaderboard (golf): the leader large, the next chasers small.
                Text(
                    fixture.entries.firstOrNull()?.name ?: fixture.statusLine,
                    style = stageText(22, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                fixture.entries.drop(1).take(2).forEach { entry ->
                    Text(entry.name, style = stageText(17, 500), color = dimColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val day = if (fixture.state == FixtureState.UPCOMING) sportDayLabel(fixture.dateMs, tomorrow, yesterday) else null
            val subLine = day ?: fixture.statusLine.takeIf { fixture.state == FixtureState.FINISHED }
            if (subLine != null) {
                Text(
                    subLine,
                    style = stageText(15, 500),
                    color = dimColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.mpx),
                )
            }
        }
        if (fixture.state == FixtureState.LIVE) {
            LiveDot(fixture.statusLine)
        } else if (selected) {
            OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(22.mpx))
        }
    }
}

@Composable
private fun FixtureChip(fixture: Fixture, followedTeams: Set<String>, focused: Boolean) {
    val accent = stageAccent
    val home = fixture.home
    val away = fixture.away
    val titleColor = if (focused) accent.onAccent else StageColors.Text
    val scoreColor = if (fixture.state == FixtureState.LIVE) accent.accent else if (focused) accent.onAccent else StageColors.Dim
    Row(
        Modifier.padding(horizontal = 14.mpx, vertical = 9.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.mpx),
    ) {
        if (home != null && away != null) {
            TeamLogo(home.logoUrl, 24.mpx)
            Text(home.abbreviation, style = stageText(17, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fixtureScoreLine(fixture), style = stageText(17, 600), color = scoreColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TeamLogo(away.logoUrl, 24.mpx)
            Text(away.abbreviation, style = stageText(17, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            // A duel/field entry (tennis/MMA/golf): the chip names the athletes, then the status.
            val names = fixture.entries.take(2).joinToString(" ${stringResource(R.string.sport_versus)} ") { it.name }
            Text(names, style = stageText(17, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fixture.statusLine, style = stageText(17, 600), color = scoreColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun fixtureScoreLine(fixture: Fixture): String {
    val home = fixture.home?.score
    val away = fixture.away?.score
    return if (home != null && away != null) stringResource(R.string.sport_score, home, away) else fixture.statusLine
}

@Composable
private fun FixtureDetail(
    fixture: Fixture,
    followedTeams: Set<String>,
    followedPlayers: Set<String>,
    matchesState: SportsViewModel.MatchesState,
    preferredLanguages: Set<SportLanguage>,
    strictChannels: Boolean,
    standings: List<LeagueTable>,
    standingsLoading: Boolean,
    matchRequester: (Long) -> FocusRequester,
    onPlayMatch: (ChannelMatch, List<ChannelMatch>) -> Unit,
    onToggleTeam: (String, String) -> Unit,
    onTogglePlayer: (String, String) -> Unit,
    sportsVm: SportsViewModel,
    liveVm: LiveViewModel,
    effectivePreview: Boolean,
    previewArmed: Boolean,
) {
    val accent = stageAccent
    // Kept across games: a viewer reading broadcasts reads them for the next game too.
    var detailTab by remember { mutableStateOf(DetailTab.CHANNELS) }
    // The matcher already filtered by the preferred languages (before its cap) — show them as-is.
    val matches = (matchesState as? SportsViewModel.MatchesState.Content)?.matches.orEmpty()
    var entities by remember(fixture.id) { mutableStateOf(emptyList<ChannelEntity>()) }
    // v2.1.0: nothing plays until a channel is picked with OK (the guide's rule); OK on it again is full
    // screen. Held here, not in the shared previewArmed, which stays set once Live TV has played anything.
    var previewId by remember(fixture.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(fixture.id, matches) {
        entities = if (matches.isEmpty()) emptyList() else sportsVm.entitiesFor(matches)
    }
    val previewEntity = entities.firstOrNull { it.id == previewId } ?: entities.firstOrNull()
    val previewEpg = matches.firstOrNull { it.channelId == previewEntity?.id }?.epgTitle
    val showVideo = effectivePreview && previewArmed && previewId != null
    LaunchedEffect(previewId, effectivePreview) {
        val entity = entities.firstOrNull { it.id == previewId } ?: return@LaunchedEffect
        if (!effectivePreview) return@LaunchedEffect
        delay(150)
        liveVm.playPreview(entity)
    }
    Column(modifier = Modifier.fillMaxHeight()) {
        if (previewEntity != null) {
            GuideVideo(
                channel = previewEntity,
                channelLine = previewEntity.name,
                nowTitle = previewEpg,
                previewEngine = liveVm.previewEngine,
                showVideo = showVideo,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.mpx),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.weight(1f)) {
        item(key = "detail" + fixture.id) {
            Column(verticalArrangement = Arrangement.spacedBy(8.mpx)) {
                Text(stringResource(R.string.sport_details), style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val homeTeam = fixture.home
                val awayTeam = fixture.away
                if (homeTeam != null && awayTeam != null) {
                    TeamScoreRow(homeTeam, fixture.state)
                    TeamScoreRow(awayTeam, fixture.state)
                } else if (fixture.format == FixtureFormat.VERSUS) {
                    // A duel (tennis/MMA): the two athletes as equal sides, same colour; the winner is
                    // bold with a check.
                    fixture.entries.take(2).forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            TeamLogo(entry.logoUrl, 40.mpx)
                            Text(
                                entry.name,
                                style = stageText(22, if (entry.winner) 700 else 600),
                                color = StageColors.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            entry.detail?.let { detail ->
                                Text(detail, style = stageText(20, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (entry.winner) OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(20.mpx))
                        }
                    }
                } else if (fixture.format == FixtureFormat.EVENT) {
                    // A single occasion (F1 Grand Prix): the race name; the location follows below.
                    Text(
                        fixture.title ?: fixture.statusLine,
                        style = stageText(24, 700), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // A leaderboard (golf): rank · athlete · value.
                    fixture.entries.take(10).forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            Text(entry.order?.toString().orEmpty(), style = stageText(16, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(30.mpx))
                            TeamLogo(entry.logoUrl, 26.mpx)
                            Text(
                                entry.name,
                                style = stageText(19, if (entry.winner) 700 else 500),
                                color = if (entry.winner) accent.accent else StageColors.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            entry.detail?.let { detail ->
                                Text(detail, style = stageText(18, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (entry.winner) OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(18.mpx))
                        }
                    }
                }
                if (fixture.state != FixtureState.UPCOMING || fixture.statusLine.isNotBlank()) {
                    Text(fixture.statusLine, style = stageText(18, 600), color = if (fixture.state == FixtureState.LIVE) accent.accent else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Round/stage and venue on every match page (v5.2 port), whichever ESPN fills.
                val stage = stageLine(fixture)
                if (stage != null) {
                    Text(stringResource(R.string.sport_round), style = stageText(15, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stage, style = stageText(18, 500), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                fixture.venue?.let { venue ->
                    Text(stringResource(R.string.sport_venue), style = stageText(15, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(venue, style = stageText(18, 500), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (homeTeam != null && awayTeam != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                        FollowButton(fixture.leagueId, homeTeam.abbreviation, followedTeams.contains(SportsPrefs.teamKey(fixture.leagueId, homeTeam.abbreviation)), Modifier.weight(1f)) {
                            onToggleTeam(fixture.leagueId, homeTeam.abbreviation)
                        }
                        FollowButton(fixture.leagueId, awayTeam.abbreviation, followedTeams.contains(SportsPrefs.teamKey(fixture.leagueId, awayTeam.abbreviation)), Modifier.weight(1f)) {
                            onToggleTeam(fixture.leagueId, awayTeam.abbreviation)
                        }
                    }
                } else if (fixture.format == FixtureFormat.VERSUS) {
                    // A duel (tennis / MMA): follow the athlete. Deliberately NOT a leaderboard
                    // (golf/F1/racing): following a single driver or a golfer makes no sense.
                    val duelists = fixture.entries.filter { it.id != null }.take(2)
                    if (duelists.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            duelists.forEach { entry ->
                                val playerId = entry.id ?: return@forEach
                                FollowButton(
                                    fixture.leagueId,
                                    entry.name,
                                    followedPlayers.contains(SportsPrefs.playerKey(fixture.leagueId, playerId)),
                                    Modifier.weight(1f),
                                ) {
                                    onTogglePlayer(fixture.leagueId, playerId)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Channels (the matched user channels) | Broadcasts (ESPN's list, one per row) — v5.2 port.
        item(key = "detail-tabs") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth().padding(top = 8.mpx)) {
                FixtureTabPill(stringResource(R.string.sport_channels), detailTab == DetailTab.CHANNELS) { detailTab = DetailTab.CHANNELS }
                FixtureTabPill(
                    stringResource(R.string.sport_broadcasts_count, fixture.broadcasts.size),
                    detailTab == DetailTab.BROADCASTS,
                ) { detailTab = DetailTab.BROADCASTS }
            }
        }
        if (detailTab == DetailTab.BROADCASTS) {
            if (fixture.broadcasts.isEmpty()) {
                item(key = "broadcasts-empty" + fixture.id) {
                    Text(stringResource(R.string.sport_no_broadcasts), style = stageText(17, 500), color = StageColors.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // One focusable row per broadcaster, so the remote scrolls a long list; OK does nothing —
            // it is a list to read (the Channels tab is where a broadcaster's channel plays).
            itemsIndexed(fixture.broadcasts, key = { index, _ -> "broadcast" + fixture.id + index }) { _, name ->
                StageSurface(onClick = {}, radius = 14.mpx, focusStyle = StageFocus.FX, modifier = Modifier.fillMaxWidth()) { focused ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.mpx, vertical = 10.mpx),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.mpx),
                    ) {
                        OwnTVIcon(OwnTVIcon.LIVE_TV, if (focused) accent.onAccent else StageColors.Dim, Modifier.size(22.mpx))
                        Text(name, style = stageText(19, 600), color = if (focused) accent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (detailTab == DetailTab.CHANNELS) item(key = "channels" + fixture.id) {
            Column(verticalArrangement = Arrangement.spacedBy(8.mpx), modifier = Modifier.padding(top = 8.mpx)) {
                if (strictChannels) {
                    Text(stringResource(R.string.sport_strict_active), style = stageText(14, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (preferredLanguages.isNotEmpty()) {
                    val languageLabels = SportLanguages.order
                        .filter { it in preferredLanguages }
                        .map { stringResource(it.labelRes) }
                    Text(
                        stringResource(R.string.sport_language_filter, languageLabels.joinToString()),
                        style = stageText(14, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                when (matchesState) {
                    SportsViewModel.MatchesState.Idle, SportsViewModel.MatchesState.Loading -> {
                        Text(stringResource(R.string.sport_loading), style = stageText(17, 500), color = StageColors.Dim)
                    }
                    is SportsViewModel.MatchesState.Content -> {
                        val list = matches
                        if (list.isEmpty()) {
                            Text(stringResource(R.string.sport_no_channel), style = stageText(17, 500), color = StageColors.Dim)
                        } else {
                            list.forEach { match ->
                                StageSurface(
                                    onClick = {
                                        val entity = entities.firstOrNull { it.id == match.channelId }
                                        if (effectivePreview && entity != null && previewId != match.channelId) {
                                            previewId = match.channelId
                                            liveVm.onChannelFocused(entity)
                                        } else onPlayMatch(match, list)
                                    },
                                    radius = 14.mpx,
                                    modifier = Modifier.fillMaxWidth().focusRequester(matchRequester(match.channelId)),
                                ) { focused ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.mpx, vertical = 10.mpx),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.mpx),
                                    ) {
                                        OwnTVIcon(OwnTVIcon.PLAY, if (focused) accent.onAccent else accent.accent, Modifier.size(22.mpx))
                                        Column(Modifier.weight(1f)) {
                                            Text(match.channelName, style = stageText(19, 600), color = if (focused) accent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            match.epgTitle?.let { title ->
                                                Text(title, style = stageText(14, 500), color = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        Text(stringResource(R.string.sport_play), style = stageText(15, 600), color = if (focused) accent.onAccent else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (standingsLoading) {
            item(key = "table-loading") {
                Text(stringResource(R.string.sport_loading), style = stageText(17, 500), color = StageColors.Dim, modifier = Modifier.padding(top = 8.mpx))
            }
        }
        standings.forEach { table ->
            item(key = "table" + fixture.leagueId + (table.groupName.orEmpty())) {
                Column(verticalArrangement = Arrangement.spacedBy(6.mpx), modifier = Modifier.padding(top = 8.mpx)) {
                    Text(stringResource(R.string.sport_standings), style = stageText(22, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    table.groupName?.let { name ->
                        Text(name, style = stageText(15, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    table.rows.take(10).forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            Text(row.rank, style = stageText(16, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(30.mpx))
                            TeamLogo(row.logoUrl, 26.mpx)
                            Text(row.teamAbbrev.ifBlank { row.teamName }, style = stageText(17, 600), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            row.stats.take(3).forEach { stat ->
                                Text(stat, style = stageText(16, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.width(52.mpx))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(60.mpx)) }
        }
    }
}

/**
 * League selection for the Sports hub: which of the catalogue's leagues are active (shown in the
 * rail and loaded). TV-local checklist; the QR/phone web page is the next step (companion page).
 */
private enum class SportsSettingsTab(val labelRes: Int) {
    SPORTS(R.string.sport_settings_tab_sports),
    LEAGUES(R.string.sport_settings_tab_leagues),
    LANGUAGES(R.string.sport_settings_tab_languages),
    CHANNELS(R.string.sport_settings_tab_channels),
}

/**
 * The Sport settings (v2.2.0), in four tabs: whole sports on or off (hold OK to put them in your own
 * order), every league with a search, the channel languages — the ones to show, and the ones never to
 * show — and the channel options of v5.2 ([SportsChannelOptions]).
 */
@Composable
private fun SportsSettingsDialog(
    leagues: List<SportLeague>,
    active: Set<String>,
    onToggleLeague: (String) -> Unit,
    onToggleSport: (SportGroup) -> Unit,
    sportOrder: List<SportGroup>,
    onSaveSportOrder: (List<SportGroup>) -> Unit,
    channelsContent: @Composable ColumnScope.() -> Unit,
    preferred: Set<SportLanguage>,
    onPreferredChange: (Set<SportLanguage>) -> Unit,
    hidden: Set<SportLanguage>,
    onToggleHidden: (SportLanguage) -> Unit,
    onManageOnPhone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(SportsSettingsTab.SPORTS) }
    var query by remember { mutableStateOf("") }
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { tabFocus.requestFocus() }
    }
    // Hold OK on a sport: move mode, like the league rail. Up/Down move it, OK or Back saves.
    var movingSport by remember { mutableStateOf<SportGroup?>(null) }
    var localSportOrder by remember { mutableStateOf<List<SportGroup>?>(null) }
    val sports = localSportOrder ?: sportOrder
    LaunchedEffect(sportOrder) { if (movingSport == null) localSportOrder = null }
    fun commitSportMove() {
        val order = localSportOrder
        movingSport = null
        if (order != null) onSaveSportOrder(order)
    }
    val sportBring = remember { mutableStateMapOf<SportGroup, BringIntoViewRequester>() }
    LaunchedEffect(localSportOrder) {
        val group = movingSport ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { sportBring[group]?.bringIntoView() }
    }
    BackHandler { onDismiss() }
    // Registered after the dialog's own Back, so it wins while a sport is being moved.
    BackHandler(enabled = movingSport != null) { commitSportMove() }
    OwnTVPopup(onDismissRequest = { if (movingSport != null) commitSportMove() else onDismiss() }, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 96.mpx, bottom = 24.mpx)
                    .width(640.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.padding(bottom = 12.mpx)) {
                    SportsSettingsTab.entries.forEach { t ->
                        Box(if (t == tab) Modifier.focusRequester(tabFocus) else Modifier) {
                            FixtureTabPill(stringResource(t.labelRes), selected = t == tab, onClick = { tab = t })
                        }
                    }
                }
                StageMenu(Modifier.verticalScroll(rememberScrollState())) {
                    when (tab) {
                        SportsSettingsTab.SPORTS -> {
                            StageMenuHeader(
                                title = stringResource(R.string.sport_settings_tab_sports),
                                subtitle = stringResource(R.string.sport_settings_sports_subtitle),
                            )
                            StageMenuItem(text = stringResource(R.string.sport_manage_on_phone), icon = OwnTVIcon.PHONE, onClick = onManageOnPhone)
                            Text(
                                stringResource(R.string.sport_order_hint),
                                style = stageText(15, 500), color = StageColors.Dim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, top = 8.mpx, bottom = 4.mpx),
                            )
                            val shownSports = sports.filter { group -> leagues.any { it.sportGroup == group } }
                            shownSports.forEach { group ->
                                key(group) {
                                    val ids = leagues.filter { it.sportGroup == group }.map { it.id }
                                    val on = ids.count { it in active }
                                    val moving = movingSport == group
                                    val bring = remember(group) { BringIntoViewRequester() }
                                    DisposableEffect(group, bring) {
                                        sportBring[group] = bring
                                        onDispose { sportBring.remove(group) }
                                    }
                                    StageMenuItem(
                                        text = stringResource(group.labelRes),
                                        icon = if (moving) OwnTVIcon.MOVE else OwnTVIcon.SPORT,
                                        value = if (moving) {
                                            stringResource(R.string.sport_move_hint)
                                        } else {
                                            stringResource(R.string.sport_settings_leagues_count, on, ids.size)
                                        },
                                        checked = on > 0 && !moving,
                                        onClick = { if (moving) commitSportMove() else if (movingSport == null) onToggleSport(group) },
                                        onLongClick = {
                                            if (movingSport == null) {
                                                localSportOrder = sports
                                                movingSport = group
                                            }
                                        },
                                        modifier = Modifier
                                            .bringIntoViewRequester(bring)
                                            .onPreviewKeyEvent { e ->
                                                if (!moving) return@onPreviewKeyEvent false
                                                val up = when (e.key) {
                                                    Key.DirectionUp -> true
                                                    Key.DirectionDown -> false
                                                    else -> return@onPreviewKeyEvent e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                                                }
                                                if (e.type == KeyEventType.KeyDown) {
                                                    // Swap with the neighbour on screen (sports without
                                                    // leagues are not listed, but keep their place).
                                                    val target = shownSports.getOrNull(shownSports.indexOf(group) + if (up) -1 else 1)
                                                    if (target != null) {
                                                        val order = (localSportOrder ?: sports).toMutableList()
                                                        val a = order.indexOf(group)
                                                        val b = order.indexOf(target)
                                                        order[a] = target
                                                        order[b] = group
                                                        localSportOrder = order
                                                    }
                                                }
                                                true
                                            },
                                    )
                                }
                            }
                        }
                        SportsSettingsTab.LEAGUES -> {
                            StageMenuHeader(
                                title = stringResource(R.string.sport_picker_title),
                                subtitle = stringResource(R.string.sport_picker_subtitle),
                            )
                            StageSearchField(
                                query = query, onQueryChange = { query = it },
                                placeholder = stringResource(R.string.sport_settings_search_leagues),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.mpx),
                            )
                            val q = query.trim()
                            sports.forEach { group ->
                                val groupLabel = stringResource(group.labelRes)
                                val groupLeagues = leagues.filter { it.sportGroup == group }
                                    .map { it to stringResource(it.labelRes) }
                                    .filter { (_, label) -> q.isEmpty() || label.contains(q, ignoreCase = true) || groupLabel.contains(q, ignoreCase = true) }
                                if (groupLeagues.isEmpty()) return@forEach
                                Text(
                                    groupLabel,
                                    style = stageText(16, 700),
                                    color = StageColors.Muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 18.mpx, top = 16.mpx, bottom = 4.mpx),
                                )
                                groupLeagues.forEach { (league, label) ->
                                    StageMenuItem(
                                        text = label,
                                        icon = OwnTVIcon.SPORT,
                                        checked = league.id in active,
                                        onClick = { onToggleLeague(league.id) },
                                    )
                                }
                            }
                        }
                        SportsSettingsTab.LANGUAGES -> {
                            StageMenuHeader(
                                title = stringResource(R.string.sport_language),
                                subtitle = stringResource(R.string.sport_language_subtitle),
                            )
                            StageMenuItem(
                                text = stringResource(R.string.sport_language_all),
                                icon = OwnTVIcon.LANGUAGE,
                                checked = preferred.isEmpty(),
                                onClick = { onPreferredChange(emptySet()) },
                            )
                            SportLanguages.order.filter { it !in hidden }.forEach { language ->
                                StageMenuItem(
                                    text = stringResource(language.labelRes),
                                    icon = OwnTVIcon.LANGUAGE,
                                    checked = language in preferred,
                                    onClick = { onPreferredChange(toggleLanguage(preferred, language)) },
                                )
                            }
                            Text(
                                stringResource(R.string.sport_language_hide),
                                style = stageText(16, 700),
                                color = StageColors.Muted,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, top = 16.mpx, bottom = 4.mpx),
                            )
                            SportLanguages.order.forEach { language ->
                                StageMenuItem(
                                    text = stringResource(language.labelRes),
                                    icon = OwnTVIcon.LANGUAGE,
                                    checked = language in hidden,
                                    onClick = { onToggleHidden(language) },
                                )
                            }
                        }
                        SportsSettingsTab.CHANNELS -> channelsContent()
                    }
                }
            }
        }
    }
}

/**
 * Sport settings › Channels (v5.2 port of OwnTV's Sports settings): how often live scores refresh,
 * the channel-list auto rebuild (on a schedule and after a playlist/guide sync) with a manual
 * "Rebuild now", the strict channel filter and which playlists Sport may match against. Every row
 * applies at once; the open game's channel list is matched again where it matters.
 */
@Composable
private fun ColumnScope.SportsChannelOptions(vm: SportsViewModel) {
    val liveSeconds by vm.liveRefreshSeconds.collectAsStateWithLifecycle()
    val rebuildMinutes by vm.autoRebuildMinutes.collectAsStateWithLifecycle()
    val strict by vm.strictChannels.collectAsStateWithLifecycle()
    val chosen by vm.channelPlaylists.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var rebuilt by remember { mutableStateOf(false) }
    StageMenuHeader(
        title = stringResource(R.string.sport_settings_tab_channels),
        subtitle = stringResource(R.string.sport_channels_subtitle),
    )
    StageMenuItem(
        text = stringResource(R.string.sport_live_refresh),
        icon = OwnTVIcon.CLOCK,
        value = when {
            liveSeconds <= 0 -> stringResource(R.string.sport_option_off)
            liveSeconds >= 120 && liveSeconds % 60 == 0 -> stringResource(R.string.sport_option_minutes, liveSeconds / 60)
            else -> stringResource(R.string.sport_option_seconds, liveSeconds)
        },
        onClick = { vm.cycleLiveRefresh() },
    )
    StageMenuItem(
        text = stringResource(R.string.sport_auto_rebuild),
        icon = OwnTVIcon.REFRESH,
        value = if (rebuildMinutes <= 0) stringResource(R.string.sport_option_off) else stringResource(R.string.sport_option_minutes, rebuildMinutes),
        onClick = { vm.cycleAutoRebuild() },
    )
    OptionNote(stringResource(R.string.sport_auto_rebuild_description))
    StageMenuItem(
        text = stringResource(R.string.sport_rebuild_now),
        icon = OwnTVIcon.REFRESH,
        value = if (rebuilt) stringResource(R.string.sport_rebuild_done) else null,
        onClick = {
            vm.rebuildNow()
            rebuilt = true
        },
    )
    StageMenuItem(
        text = stringResource(R.string.sport_strict_filter),
        icon = OwnTVIcon.SORT,
        checked = strict,
        onClick = { vm.toggleStrictChannels() },
    )
    OptionNote(stringResource(R.string.sport_strict_description))
    Text(
        stringResource(R.string.sport_playlists_heading),
        style = stageText(16, 700),
        color = StageColors.Muted,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 18.mpx, top = 16.mpx, bottom = 4.mpx),
    )
    StageMenuItem(
        text = stringResource(R.string.sport_playlists_all),
        icon = OwnTVIcon.PLAYLIST,
        checked = chosen.isEmpty(),
        onClick = { vm.clearPlaylistFilter() },
    )
    playlists.forEach { playlist ->
        StageMenuItem(
            text = playlist.name,
            icon = OwnTVIcon.PLAYLIST,
            checked = playlist.id in chosen,
            onClick = { vm.togglePlaylist(playlist.id) },
        )
    }
}

/** A dim explanation under a Sport settings row. */
@Composable
private fun OptionNote(text: String) {
    Text(
        text,
        style = stageText(14, 500),
        color = StageColors.Dim,
        maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 18.mpx, end = 12.mpx, bottom = 6.mpx),
    )
}

/**
 * Language selection for the matched channels: pick one or more languages (or "all"). Reached from
 * the Sport settings and shown automatically the first time channels are matched. The choice filters
 * the channel list by the country tag in a channel's name — see [SportLanguages].
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
private fun toggleLanguage(selected: Set<SportLanguage>, language: SportLanguage): Set<SportLanguage> = when {
    selected.isEmpty() -> setOf(language)
    language in selected -> selected - language
    else -> selected + language
}

/**
 * "Manage on phone": shows the companion QR + PIN while the pick list is served. Closes itself once
 * the selection was applied (the server stops a moment later) or on Back.
 */
@Composable
private fun SportsPhoneDialog(
    state: CompanionServerState,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    var sawListening by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            is CompanionServerState.Listening -> sawListening = true
            CompanionServerState.Idle -> if (sawListening) onDismiss()
            else -> Unit
        }
    }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 150.mpx, bottom = 24.mpx)
                    .width(720.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(20.mpx),
            ) {
                Text(
                    stringResource(R.string.sport_manage_on_phone),
                    style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                StageCompanionStatus(state)
            }
        }
    }
}

@Composable
private fun TeamScoreRow(team: SportTeam, state: FixtureState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.mpx), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(5.mpx).height(40.mpx).background(teamColor(team.colorHex) ?: Color.Transparent, RoundedCornerShape(3.mpx)))
        TeamLogo(team.logoUrl, 44.mpx)
        Text(team.name, style = stageText(22, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        team.score?.let { score ->
            Text(score, style = stageText(30, 800), color = if (state == FixtureState.LIVE) stageAccent.accent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FollowButton(leagueId: String, abbrev: String, followed: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val accent = stageAccent
    StageSurface(
        onClick = onToggle,
        radius = 14.mpx,
        focusStyle = StageFocus.FX,
        modifier = modifier,
        idle = if (followed) Modifier.background(accent.accent.copy(alpha = 0.14f), RoundedCornerShape(14.mpx)) else Modifier,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.mpx, vertical = 9.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.mpx),
        ) {
            OwnTVIcon(OwnTVIcon.STAR, if (focused) accent.onAccent else if (followed) accent.accent else StageColors.Dim, Modifier.size(20.mpx))
            Column(Modifier.weight(1f)) {
                Text(abbrev, style = stageText(17, 700), color = if (focused) accent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (followed) R.string.sport_unfollow else R.string.sport_follow),
                    style = stageText(13, 500),
                    color = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val DayPattern = "EEE d.M."
private const val ColorPrefix = "FF"
private const val HashPrefix = "#"

private val SportGroup.labelRes: Int
    get() = when (this) {
        SportGroup.SOCCER -> R.string.sport_group_soccer
        SportGroup.RUGBY_UNION -> R.string.sport_group_rugby_union
        SportGroup.RUGBY_LEAGUE -> R.string.sport_group_rugby_league
        SportGroup.AUSTRALIAN_FOOTBALL -> R.string.sport_group_australian_football
        SportGroup.CRICKET -> R.string.sport_group_cricket
        SportGroup.AMERICAN_FOOTBALL -> R.string.sport_group_american_football
        SportGroup.BASKETBALL -> R.string.sport_group_basketball
        SportGroup.BASEBALL -> R.string.sport_group_baseball
        SportGroup.ICE_HOCKEY -> R.string.sport_group_ice_hockey
        SportGroup.VOLLEYBALL -> R.string.sport_group_volleyball
        SportGroup.TENNIS -> R.string.sport_group_tennis
        SportGroup.GOLF -> R.string.sport_group_golf
        SportGroup.MOTORSPORT -> R.string.sport_group_motorsport
        SportGroup.MMA -> R.string.sport_group_mma
        SportGroup.OTHER -> R.string.sport_group_other
    }
