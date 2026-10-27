package tv.own.owntv.features.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** Chips shown before "+N": eight sports. */
private const val SportChips = 8

/**
 * The Sport hub (v2.6.0, OwnTV's Sports Hub): title and today's counts, the chips (All, ★ My teams,
 * the chosen sports, ⟳ refresh, ⚙ Settings › Sport), the chosen sports' leagues, then *Live now* with
 * the featured first card, *Starting soon* and the latest results. OK opens a match; Hold OK follows
 * either side. Hold OK on a league chip opens the league's page. With nothing chosen, a note says
 * where to choose.
 */
@Composable
internal fun SportsHubScreen(
    vm: SportsViewModel,
    onOpenMatch: (Fixture) -> Unit,
    /** A league page, and (v2.6.1) the key of the control it was opened from, for Back. */
    onOpenLeague: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    /** (v2.6.2) The calendar page. */
    onOpenCalendar: () -> Unit,
    onChildFocused: () -> Unit,
    onEntryHook: (((() -> Boolean)?) -> Unit)?,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    /** Back from a match: the card it was opened from takes focus again. */
    restoreKey: String? = null,
    /** (v2.6.1) Counted: each new value puts focus on [restoreKey] again (or the hub's entry when it is gone). */
    restoreAsk: Int = 0,
) {
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val active by vm.activeLeagues.collectAsStateWithLifecycle()
    val sportOrder by vm.sportOrder.collectAsStateWithLifecycle()
    val rank by vm.leagueRank.collectAsStateWithLifecycle()
    val followedTeams by vm.followedTeams.collectAsStateWithLifecycle()
    val followedPlayers by vm.followedPlayers.collectAsStateWithLifecycle()
    val loading by vm.hubLoading.collectAsStateWithLifecycle()
    val failing by vm.failingLeagues.collectAsStateWithLifecycle()
    val cardFocus = remember { HashMap<String, FocusRequester>() }
    fun focusOf(key: String) = cardFocus.getOrPut(key) { FocusRequester() }
    val featuredFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    // (v2.6.1) Back from a page (or Settings › Sport): its card or chip, else the hub's first control, so
    // focus is never left on nothing (where the next key found the menu).
    LaunchedEffect(restoreAsk) {
        if (restoreAsk == 0) return@LaunchedEffect
        val key = restoreKey
        for (attempt in 0 until 10) {
            withFrameNanos { }
            if (key != null && runCatching { focusOf(key).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
        for (attempt in 0 until 10) {
            if (runCatching { featuredFocus.requestFocus() }.getOrDefault(false) ||
                runCatching { chipFocus.requestFocus() }.getOrDefault(false)
            ) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    // Both chip rows are multi-select: OK toggles a chip, nothing chosen = All. Kept while the hub runs.
    var filter by rememberSaveable(stateSaver = HubFilterSaver) { mutableStateOf(SportsHub.Filter()) }
    var showAllSports by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Fixture?>(null) }

    val mine: (Fixture) -> Boolean = remember(followedTeams, followedPlayers) { { vm.isMine(it) } }
    val now = System.currentTimeMillis()
    val rows = remember(favourites, filter, rank, followedTeams, followedPlayers, now / 60_000L) {
        SportsHub.rows(favourites, filter, now, { rank[it] ?: Int.MAX_VALUE }, mine)
    }
    val chipSports = remember(active, sportOrder) { SportsHub.chipSports(active, sportOrder) }
    val chosenLeagues = remember(active, filter, rank) {
        SportsLeagues.filter { it.id in active && it.sportGroup in filter.sports }.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    val entry by rememberUpdatedState(if (rows.live.isNotEmpty()) featuredFocus else chipFocus)
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke { runCatching { entry.requestFocus() }.getOrDefault(false) }
        onDispose { onEntryHook?.invoke(null) }
    }
    val soonTitle = stringResource(R.string.sport_starting_soon)
    val resultsTitle = stringResource(R.string.sport_latest_results)
    Box(modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onChildFocused() }) {
        LazyColumn(
            Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(top = 39.mpx, bottom = 110.mpx),
        ) {
            item(key = "head") {
                Column(Modifier.padding(start = contentStart, end = 360.mpx)) {
                    Text(stringResource(R.string.sport_title), style = stageText(48, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(9.mpx))
                    HubSubtitle(rows.today, now)
                }
            }
            item(key = "chips") {
                val shownSports = if (showAllSports) chipSports else chipSports.take(SportChips)
                LazyRow(
                    // Nothing above the chips takes focus; without this, focus search found the rail.
                    Modifier.padding(top = 24.mpx).focusRestorer().focusProperties { up = FocusRequester.Cancel }.focusGroup(),
                    contentPadding = PaddingValues(start = contentStart, end = 64.mpx),
                    horizontalArrangement = Arrangement.spacedBy(12.mpx),
                ) {
                    item { SportsChip(stringResource(R.string.content_epg_all), filter.isAll, Modifier.focusRequester(chipFocus)) { filter = SportsHub.Filter() } }
                    item { SportsChip(stringResource(R.string.sport_my_teams), filter.myTeams, icon = OwnTVIcon.STAR) { filter = filter.copy(myTeams = !filter.myTeams) } }
                    items(shownSports, key = { it.name }) { s ->
                        SportsChip(sportEmoji(s) + " " + sportLabel(s), s in filter.sports) {
                            filter = if (s in filter.sports) {
                                filter.copy(sports = filter.sports - s, leagues = filter.leagues.filterTo(HashSet()) { sportsLeagueById(it)?.sportGroup != s })
                            } else {
                                filter.copy(sports = filter.sports + s)
                            }
                        }
                    }
                    if (!showAllSports && chipSports.size > SportChips) {
                        item { SportsChip("+" + (chipSports.size - SportChips), false, dim = true) { showAllSports = true } }
                    }
                    // (v2.6.2) The month ahead, a day at a time.
                    item { SportsChip(stringResource(R.string.sport_calendar), false, Modifier.focusRequester(focusOf(HubCalendarKey)), icon = OwnTVIcon.EPG, onClick = onOpenCalendar) }
                    // ⟳ fetches every board again; ⚙ is Settings › Sport (the old in-Sport dialog).
                    item { SportsChip("", false, icon = OwnTVIcon.REFRESH) { vm.refreshAll() } }
                    item { SportsChip(stringResource(R.string.sport_settings), false, Modifier.focusRequester(focusOf(HubSettingsKey)), icon = OwnTVIcon.SETTINGS, onClick = onOpenSettings) }
                }
            }

            if (chosenLeagues.size > 1) {
                item(key = "leagues") {
                    LazyRow(
                        Modifier.padding(top = 12.mpx).focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(start = contentStart, end = 64.mpx),
                        horizontalArrangement = Arrangement.spacedBy(12.mpx),
                    ) {
                        item { SportsChip(stringResource(R.string.content_epg_all), filter.leagues.isEmpty()) { filter = filter.copy(leagues = emptySet()) } }
                        items(chosenLeagues, key = { it.id }) { l ->
                            SportsChip(
                                stringResource(l.labelRes), l.id in filter.leagues,
                                Modifier.focusRequester(focusOf(hubLeagueKey(l.id))),
                                onLongClick = { onOpenLeague(l.id, hubLeagueKey(l.id)) },
                            ) { filter = filter.copy(leagues = if (l.id in filter.leagues) filter.leagues - l.id else filter.leagues + l.id) }
                        }
                    }
                }
            }

            val nothing = rows.live.isEmpty() && rows.soon.isEmpty() && rows.results.isEmpty()
            if (active.isEmpty() && followedTeams.isEmpty() && followedPlayers.isEmpty()) {
                item(key = "none") { NothingFollowedNote(Modifier.padding(start = contentStart, top = 80.mpx, end = 360.mpx)) }
            } else if (nothing) {
                item(key = "empty") {
                    val text = when {
                        loading -> R.string.sport_loading
                        failing.isNotEmpty() && favourites.isEmpty() -> R.string.sport_empty_unreachable
                        else -> R.string.sport_empty_nothing
                    }
                    Text(
                        stringResource(text), style = stageText(22, 600), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = contentStart, top = 80.mpx, end = 360.mpx),
                    )
                }
            }

            if (rows.live.isNotEmpty()) {
                item(key = "live") {
                    Column {
                        RowHeader(contentStart, stringResource(R.string.sport_live_now), null, live = true)
                        LazyRow(
                            Modifier.focusRestorer().focusGroup(),
                            contentPadding = PaddingValues(start = contentStart, end = 64.mpx, top = 6.mpx, bottom = 6.mpx),
                            horizontalArrangement = Arrangement.spacedBy(18.mpx),
                        ) {
                            itemsIndexed(rows.live, key = { _, f -> f.id }) { i, f ->
                                MatchCard(
                                    f, featured = i == 0, mine = mine(f), now = now,
                                    onClick = { onOpenMatch(f) }, onHold = { menuFor = f },
                                    modifier = (if (i == 0) Modifier.focusRequester(featuredFocus) else Modifier).focusRequester(focusOf(f.id)),
                                )
                            }
                        }
                    }
                }
            }
            hubRow("soon", rows.soon, soonTitle, contentStart, now, mine, ::focusOf, onOpenMatch) { menuFor = it }
            hubRow("results", rows.results, resultsTitle, contentStart, now, mine, ::focusOf, onOpenMatch) { menuFor = it }
        }

        StageKeyHints(
            listOf(
                stringResource(R.string.common_ok) to stringResource(R.string.sport_hint_match_page),
                stringResource(R.string.content_key_hold_ok) to stringResource(R.string.sport_follow),
            ),
            Modifier.align(Alignment.BottomEnd).padding(end = 60.mpx, bottom = 27.mpx),
            textSize = 18,
        )
    }

    menuFor?.let { f ->
        FollowMenu(
            fixture = f,
            followedTeams = followedTeams,
            followedPlayers = followedPlayers,
            onFollowTeam = { team -> vm.toggleTeamFollowed(f.leagueId, team.abbreviation, team.name) },
            onFollowPlayer = { id -> vm.togglePlayerFollowed(f.leagueId, id) },
            onOpenLeague = { onOpenLeague(f.leagueId, f.id) },
            onDismiss = { menuFor = null },
        )
    }
}

/** One row of cards under its header ("Starting soon", "Latest results"); nothing when empty. */
private fun androidx.compose.foundation.lazy.LazyListScope.hubRow(
    key: String,
    fixtures: List<Fixture>,
    title: String,
    contentStart: Dp,
    now: Long,
    mine: (Fixture) -> Boolean,
    focusOf: (String) -> FocusRequester,
    onOpenMatch: (Fixture) -> Unit,
    onHold: (Fixture) -> Unit,
) {
    if (fixtures.isEmpty()) return
    item(key = key) {
        Column {
            RowHeader(contentStart, title, null)
            val state = rememberLazyListState()
            // A new first card scrolls the row home; (v2.6.1) not the row coming back from a page, which keeps
            // its place so the card it was left on is there to take focus again.
            val firstId = fixtures.firstOrNull()?.id
            var shownFirst by rememberSaveable { mutableStateOf(firstId) }
            LaunchedEffect(firstId) { if (firstId != shownFirst) { shownFirst = firstId; state.scrollToItem(0) } }
            LazyRow(
                state = state,
                modifier = Modifier.focusRestorer().focusGroup(),
                contentPadding = PaddingValues(start = contentStart, end = 64.mpx, top = 6.mpx, bottom = 6.mpx),
                horizontalArrangement = Arrangement.spacedBy(18.mpx),
            ) {
                items(fixtures, key = { it.id }) { f ->
                    MatchCard(
                        f, featured = false, mine = mine(f), now = now,
                        onClick = { onOpenMatch(f) }, onHold = { onHold(f) },
                        modifier = Modifier.focusRequester(focusOf(f.id)),
                    )
                }
            }
        }
    }
}

/** "**Today** · 6 live · 41 matches · Scores: ESPN" (no channel count — channels are found per match). */
@Composable
private fun HubSubtitle(today: List<Fixture>, now: Long) {
    val accent = stageAccent.accent
    val sep = stringResource(R.string.sport_detail_separator)
    val liveCount = today.count { SportsSchedule.isLiveish(it, now) }
    val todayLabel = stringResource(R.string.content_epg_today)
    val liveText = pluralStringResource(R.plurals.sport_hub_live, liveCount, liveCount)
    val matchesText = pluralStringResource(R.plurals.sport_hub_matches, today.size, today.size)
    val credit = stringResource(R.string.sport_scores_credit)
    val dim = StageColors.Dim
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(todayLabel) }
            append(sep); append(liveText); append(sep); append(matchesText)
            append(sep)
            withStyle(SpanStyle(color = dim)) { append(credit) }
        },
        style = stageText(21, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** The hub with nothing chosen: what to do, where, and why to choose with care. */
@Composable
private fun NothingFollowedNote(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.mpx)) {
        Text(stringResource(R.string.sport_empty_nothing_followed), style = stageText(30, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.sport_empty_choose_in), style = stageText(22, 600), color = StageColors.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.sport_load_time_warning), style = stageText(19, 400), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** `.rowh`: a 25.5/800 title (with the LIVE mark on Live now) and a muted 18 px hint on its baseline. */
@Composable
private fun RowHeader(start: Dp, title: String, hint: String?, live: Boolean = false) {
    Row(
        Modifier.padding(start = start, top = 27.mpx, bottom = 10.mpx),
        horizontalArrangement = Arrangement.spacedBy(15.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) LiveMark(stringResource(R.string.sport_guide_live), 18f)
        Text(title, style = stageText(25.5f, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hint != null) Text(hint, style = stageText(18, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * `.mc`: a match card, 324 wide (the featured first card 648, with the big score). League line and
 * the minute or kick-off on top, both sides with crest and score, then the venue and the star of a
 * followed side. No channels: they are found on the match page.
 */
@Composable
internal fun MatchCard(
    f: Fixture,
    featured: Boolean,
    mine: Boolean,
    now: Long,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val r = 20.mpx
    val locale = LocalConfiguration.current.locales[0]
    val sep = stringResource(R.string.sport_detail_separator)
    val live = SportsSchedule.isLiveish(f, now)
    StageSurface(
        onClick = onClick,
        onLongClick = onHold,
        radius = r,
        focusStyle = StageFocus.FX,
        contentAlignment = Alignment.TopStart,
        modifier = modifier
            .width((if (featured) 648 else 324).mpx)
            .height(if (featured) 216.mpx else 204.mpx)
            .onPreviewKeyEvent { e ->
                // The Play key opens the match too, as on OwnTV.
                val playKey = e.key == Key.MediaPlay || e.key == Key.MediaPlayPause
                if (playKey && e.type == KeyEventType.KeyUp) onClick()
                playKey
            },
        idle = Modifier
            .background(StageColors.Ink.copy(alpha = 0.045f), RoundedCornerShape(r))
            .drawBehind { drawInnerRing(Color.White.copy(alpha = 0.05f), 1.mpx.toPx(), r.toPx()) },
    ) { _ ->
        Column(Modifier.fillMaxSize().padding(horizontal = 20.mpx, vertical = 16.mpx)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val day = if (live) null else dayOf(f.dateMs, locale)
                val stage = if (featured) stageLine(f) else null
                val league = listOfNotNull(leagueLabel(f.leagueId).ifEmpty { null }, stage, day).joinToString(sep)
                Text(league, style = stageText(16.5f, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (live) {
                    LiveMark(stringResource(R.string.sport_guide_live) + sep + f.statusLine, 17f)
                } else {
                    val right = if (f.state == FixtureState.FINISHED) f.statusLine else timeText(f.dateMs)
                    Text(right, style = stageText(17, 800), maxLines = 1, overflow = TextOverflow.Ellipsis, color = StageColors.Muted)
                }
            }
            if (featured && f.home != null && f.away != null) FeaturedScore(f) else Sides(f)
            Spacer(Modifier.weight(1f))
            CardFooter(f.venue, mine)
        }
    }
}

/** Two sides, each a 42 px crest, the name and the score; the losing side in muted grey. */
@Composable
private fun Sides(f: Fixture) {
    val home = f.home
    val away = f.away
    if (home == null || away == null) {
        // A duel, a field or one event: the athletes, or the event and where.
        if (f.format == FixtureFormat.VERSUS && f.entries.size >= 2) {
            f.entries.take(2).forEach { e ->
                Spacer(Modifier.height(12.mpx))
                Row(horizontalArrangement = Arrangement.spacedBy(13.mpx), verticalAlignment = Alignment.CenterVertically) {
                    Crest(e.logoUrl, e.name, 42)
                    Text(e.name, style = stageText(21, if (e.winner) 800 else 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    e.detail?.let { Text(it, style = stageText(21, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        } else {
            Spacer(Modifier.height(12.mpx))
            Text(f.title ?: f.entries.firstOrNull()?.name ?: f.statusLine, style = stageText(21, 700), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            f.entries.drop(1).take(2).forEach { Text(it.name, style = stageText(18, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        return
    }
    listOf(home, away).forEach { t ->
        Spacer(Modifier.height(12.mpx))
        val lost = f.state == FixtureState.FINISHED && !t.winner && (home.winner || away.winner)
        Row(horizontalArrangement = Arrangement.spacedBy(13.mpx), verticalAlignment = Alignment.CenterVertically) {
            Crest(t.logoUrl, t.abbreviation, 42)
            Text(teamLabel(t), style = stageText(21, 700), color = if (lost) StageColors.Muted else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val s = t.score
            if (f.state != FixtureState.UPCOMING && s != null) {
                Text(s, style = stageText(if (s.length > 3) 21f else 27f, 800), color = if (lost) StageColors.Muted else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The featured card's middle: crest and name each side, the score 51 px in between. */
@Composable
private fun FeaturedScore(f: Fixture) {
    val home = f.home ?: return
    val away = f.away ?: return
    Row(Modifier.fillMaxWidth().padding(top = 12.mpx), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(13.mpx), verticalAlignment = Alignment.CenterVertically) {
            Crest(home.logoUrl, home.abbreviation, 60)
            Text(teamLabel(home), style = stageText(21, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val score = if (home.score != null && away.score != null) {
            stringResource(R.string.sport_score, home.score, away.score)
        } else {
            stringResource(R.string.sport_versus)
        }
        Text(score, style = stageText(if (score.length > 7) 36 else 51, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.mpx))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(13.mpx, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text(teamLabel(away), style = stageText(21, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Crest(away.logoUrl, away.abbreviation, 60)
        }
    }
}

/** `.ft`: the card's foot — the venue, then the star of a followed side. */
@Composable
private fun CardFooter(venue: String?, followed: Boolean) {
    Box(Modifier.fillMaxWidth().height(1.mpx).background(Color.White.copy(alpha = 0.07f)))
    Spacer(Modifier.height(12.mpx))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
        Text(venue.orEmpty(), style = stageText(16, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (followed) OwnTVIcon(OwnTVIcon.STAR, SportsStar, Modifier.size(20.mpx))
    }
}

/**
 * Hold OK on a card: follow either side (or stop following) — a team, or a duel's athlete — and the
 * league's page.
 */
@Composable
internal fun FollowMenu(
    fixture: Fixture,
    followedTeams: Set<String>,
    followedPlayers: Set<String>,
    onFollowTeam: (SportTeam) -> Unit,
    onFollowPlayer: (String) -> Unit,
    onOpenLeague: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler { onDismiss() }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim).longPressMenuGuard(), contentAlignment = Alignment.Center) {
            StageMenu(Modifier.width(560.mpx).trapAllFocusExit().focusGroup()) {
                StageMenuHeader(pairText(fixture, stringResource(R.string.sport_pair_dash)), leagueLabel(fixture.leagueId))
                var firstSet = false
                fun firstMod(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                listOfNotNull(fixture.home, fixture.away).forEach { team ->
                    val following = SportsPrefs.teamKey(fixture.leagueId, team.abbreviation) in followedTeams
                    StageMenuItem(
                        text = teamLabel(team),
                        icon = OwnTVIcon.STAR,
                        iconFilled = following,
                        value = stringResource(if (following) R.string.sport_following else R.string.sport_follow),
                        onClick = { onFollowTeam(team); onDismiss() },
                        modifier = firstMod(),
                    )
                }
                if (fixture.home == null && fixture.format == FixtureFormat.VERSUS) {
                    fixture.entries.filter { it.id != null && !it.id.startsWith("-") }.take(2).forEach { e ->
                        val id = e.id ?: return@forEach
                        val following = SportsPrefs.playerKey(fixture.leagueId, id) in followedPlayers
                        StageMenuItem(
                            text = e.name,
                            icon = OwnTVIcon.STAR,
                            iconFilled = following,
                            value = stringResource(if (following) R.string.sport_following else R.string.sport_follow),
                            onClick = { onFollowPlayer(id); onDismiss() },
                            modifier = firstMod(),
                        )
                    }
                }
                StageMenuItem(
                    text = stringResource(R.string.sport_open_league),
                    icon = OwnTVIcon.SPORT,
                    value = leagueLabel(fixture.leagueId),
                    onClick = { onDismiss(); onOpenLeague() },
                    modifier = firstMod(),
                )
            }
        }
    }
}

/**
 * The round/stage line: "Week 6", "Round 2", "Group A", the source's round ("Second Round") and
 * ESPN's headline note ("NFL London Games"), joined; null when there is none (a plain matchday).
 */
@Composable
internal fun stageLine(fixture: Fixture): String? {
    val parts = listOfNotNull(
        fixture.week?.let { stringResource(R.string.sport_round_week, it) },
        fixture.roundNumber?.let { stringResource(R.string.sport_round_number, it) },
        fixture.group?.let { stringResource(R.string.sport_group_name, it) },
        fixture.round,
        fixture.note,
    )
    return if (parts.isEmpty()) null else parts.joinToString(stringResource(R.string.sport_detail_separator))
}

/** The hub's chips across configuration changes: sports and leagues by name. */
private val HubFilterSaver = androidx.compose.runtime.saveable.Saver<SportsHub.Filter, List<String>>(
    save = { f -> listOf(f.myTeams.toString(), f.sports.joinToString(ListSep) { it.name }, f.leagues.joinToString(ListSep)) },
    restore = { saved ->
        val sports = saved.getOrNull(1).orEmpty().split(ListSep).mapNotNull { n -> SportGroup.entries.firstOrNull { it.name == n } }.toSet()
        val leagues = saved.getOrNull(2).orEmpty().split(ListSep).filter { it.isNotEmpty() }.toSet()
        SportsHub.Filter(sports, saved.firstOrNull() == true.toString(), leagues)
    },
)

private const val ListSep = ","

/** (v2.6.1) The hub's ⚙ chip, as a key for focus to come back to from Settings › Sport. */
internal const val HubSettingsKey = "settings"

/** (v2.6.2) The hub's calendar chip, as a key for focus to come back to from the calendar. */
internal const val HubCalendarKey = "calendar"

/** (v2.6.1) A league chip's key, for focus to come back to from its page. */
internal fun hubLeagueKey(id: String): String = "league:$id"
