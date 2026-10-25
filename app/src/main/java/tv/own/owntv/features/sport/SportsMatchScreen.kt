package tv.own.owntv.features.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.epg.GuideVideo
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.trapVerticalFocusExit
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.StageTab
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.stage.stageStillBack
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** The match page's tabs: the league table and who broadcasts the game. */
private enum class MatchTab { TABLE, BROADCASTS }

/** The tier colours of the reasons: guide green, the event's own channel blue, likely muted. */
private val TierGuide = Color(0xFF9FE07E)
private val TierEvent = Color(0xFF7FD3FF)

/**
 * A match (v2.6.0, OwnTV's Sports Hub match page). Left: the crumb, the score header (crests, score,
 * LIVE, day, round and venue), Follow for either side, then the Table and Broadcasts tabs. Right:
 * *Watch live* — the user's channels showing it, found by [SportsMatcher] across channel names and
 * guide text, each with why it is listed. OK on a channel previews it above the list (DPTV's rule:
 * nothing plays until picked); OK again plays it full screen.
 */
@Composable
internal fun SportsMatchScreen(
    vm: SportsViewModel,
    liveVm: LiveViewModel,
    fixture: Fixture,
    effectivePreview: Boolean,
    onPlayChannel: (Long, List<ChannelEntity>) -> Unit,
    onOpenLeague: (String) -> Unit,
    onBack: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    // Back ends the preview and returns to the hub; off while the player is full screen over the page.
    androidx.activity.compose.BackHandler(enabled = active) { liveVm.stopPreview(); onBack() }
    val matchesState by vm.matches.collectAsStateWithLifecycle()
    val standings by vm.standings.collectAsStateWithLifecycle()
    val followedTeams by vm.followedTeams.collectAsStateWithLifecycle()
    val followedPlayers by vm.followedPlayers.collectAsStateWithLifecycle()
    val preferred by vm.preferredLanguages.collectAsStateWithLifecycle()
    val strict by vm.strictChannels.collectAsStateWithLifecycle()
    val previewArmed by liveVm.previewArmed.collectAsStateWithLifecycle()
    val sep = stringResource(R.string.sport_detail_separator)

    val table = remember(standings, fixture.id) {
        standings.firstOrNull { t -> t.leagueId == fixture.leagueId && t.rows.any { it.isTeam(fixture.home) || it.isTeam(fixture.away) } }
            ?: standings.firstOrNull { it.leagueId == fixture.leagueId }
    }
    val tabs = MatchTab.entries.filter { it != MatchTab.TABLE || table != null }
    var tab by remember(fixture.id) { mutableStateOf<MatchTab?>(null) }
    val shownTab = tab?.takeIf { it in tabs } ?: tabs.first()

    val matches = (matchesState as? SportsViewModel.MatchesState.Content)?.matches.orEmpty()
    var entities by remember(fixture.id) { mutableStateOf(emptyList<ChannelEntity>()) }
    LaunchedEffect(fixture.id, matches) { entities = if (matches.isEmpty()) emptyList() else vm.entitiesFor(matches) }
    // v2.1.0: nothing plays until a channel is picked with OK; OK on it again is full screen.
    var previewId by remember(fixture.id) { mutableStateOf<Long?>(null) }
    var focusedId by remember(fixture.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(previewId, effectivePreview) {
        val entity = entities.firstOrNull { it.id == previewId } ?: return@LaunchedEffect
        if (!effectivePreview) return@LaunchedEffect
        delay(150)
        liveVm.playPreview(entity)
    }
    val firstRow = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    // Focus follows the search: the tabs while there is no list, the first channel once there is one.
    // (v2.6.1) Asked again for a few frames: the page's first control may not be laid out yet, and focus left
    // on nothing went to the menu on the next key.
    LaunchedEffect(fixture.id, matches.isNotEmpty()) {
        delay(60)
        for (attempt in 0 until 10) {
            if (matches.isNotEmpty() && runCatching { firstRow.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            if ((matches.isEmpty() || attempt >= 3) && runCatching { tabFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
        }
    }

    Box(modifier.fillMaxSize()) {
        // Up and Down stay on this page: a held ▲ must never run out of the page.
        Row(
            Modifier.fillMaxSize().padding(start = contentStart, end = 60.mpx, top = 39.mpx).trapVerticalFocusExit(),
            horizontalArrangement = Arrangement.spacedBy(39.mpx),
        ) {
            // --- Left: the match ------------------------------------------------------------------
            Column(Modifier.width(915.mpx).fillMaxHeight()) {
                Box(Modifier.height(75.mpx).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
                        OwnTVIcon(OwnTVIcon.CHEVRON_LEFT, StageColors.Muted, Modifier.size(21.mpx))
                        Text(stringResource(R.string.sport_title), style = stageText(21, 600), color = stageAccent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sep + leagueLabel(fixture.leagueId), style = stageText(21, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(bottom = 84.mpx).focusGroup(), contentPadding = PaddingValues(bottom = 24.mpx)) {
                    item(key = "header") { ScoreHeader(fixture) }
                    item(key = "tabs") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 21.mpx).focusRestorer().focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.mpx),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            tabs.forEachIndexed { i, t ->
                                StageTab(
                                    label = stringResource(if (t == MatchTab.TABLE) R.string.sport_tab_table else R.string.sport_tab_broadcasts),
                                    count = if (t == MatchTab.BROADCASTS && fixture.broadcasts.isNotEmpty()) fixture.broadcasts.size.toString() else "",
                                    selected = shownTab == t, onClick = { tab = t },
                                    modifier = if (i == 0) Modifier.focusRequester(tabFocus) else Modifier,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            FollowChips(fixture, followedTeams, followedPlayers, vm)
                        }
                    }
                    when (shownTab) {
                        MatchTab.TABLE -> item(key = "table") { TablePreview(table, fixture) { onOpenLeague(fixture.leagueId) } }
                        MatchTab.BROADCASTS -> {
                            if (fixture.broadcasts.isEmpty()) {
                                item(key = "no-broadcasts") {
                                    // SofaScore and TheSportsDB carry no broadcasters, so their leagues say so plainly (v2.5.0).
                                    val none = if (sportsLeagueById(fixture.leagueId)?.source == LeagueSource.SOFASCORE) R.string.sport_no_broadcasts_other else R.string.sport_no_broadcasts
                                    Text(stringResource(none), style = stageText(18, 400), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(18.mpx))
                                }
                            }
                            // One focusable row per broadcaster, so the remote scrolls a long list; OK does nothing.
                            items(fixture.broadcasts.distinct().sortedBy { it.lowercase() }, key = { "b-$it" }) { name ->
                                StageRow(onClick = {}, height = 51.mpx, modifier = Modifier.fillMaxWidth(), horizontalPadding = 12.mpx) { focused ->
                                    OwnTVIcon(OwnTVIcon.LIVE_TV, StageColors.Dim, Modifier.size(20.mpx))
                                    Text(name, style = stageText(18, 600), color = if (focused) StageColors.Text else StageColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // --- Right: Watch live ----------------------------------------------------------------
            // Starts below the top-right status cluster (playlist pill and clock), level with the score box.
            Column(Modifier.weight(1f).fillMaxSize().padding(top = 96.mpx).focusGroup()) {
                when {
                    matchesState !is SportsViewModel.MatchesState.Content -> {
                        Text(stringResource(R.string.sport_watch_live), style = stageText(24, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.sport_finding_channels), style = stageText(19, 600), color = StageColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.mpx))
                    }
                    matches.isEmpty() -> {
                        Text(stringResource(R.string.sport_watch_live), style = stageText(24, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.sport_no_channel), style = stageText(19, 400), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.mpx))
                    }
                    else -> {
                        val video = entities.firstOrNull { it.id == previewId } ?: entities.firstOrNull { it.id == focusedId } ?: entities.firstOrNull()
                        GuideVideo(
                            channel = video,
                            channelLine = video?.name,
                            nowTitle = matches.firstOrNull { it.channelId == video?.id }?.epgTitle,
                            previewEngine = liveVm.previewEngine,
                            showVideo = effectivePreview && previewArmed && previewId != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 18.mpx), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                pluralStringResource(R.plurals.sport_watch_live_count, matches.size, matches.size),
                                style = stageText(24, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (preferred.isNotEmpty()) {
                                val names = SportLanguages.order.filter { it in preferred }.map { stringResource(it.labelRes) }
                                Text(stringResource(R.string.sport_language_filter, names.joinToString()), style = stageText(18, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (strict) Text(stringResource(R.string.sport_strict_active), style = stageText(16, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.mpx))
                        // Ends above the key hints at the bottom of the screen.
                        LazyColumn(Modifier.weight(1f).padding(top = 6.mpx, bottom = 84.mpx).focusRestorer().focusGroup()) {
                            items(matches, key = { it.channelId }) { m ->
                                MatchChannelRow(
                                    m, playing = previewId == m.channelId,
                                    onClick = {
                                        val entity = entities.firstOrNull { it.id == m.channelId }
                                        if (effectivePreview && entity != null && previewId != m.channelId) {
                                            previewId = m.channelId
                                            liveVm.onChannelFocused(entity)
                                        } else {
                                            vm.playMatch(m, matches, onPlayChannel)
                                        }
                                    },
                                    modifier = Modifier
                                        .then(if (m === matches.first()) Modifier.focusRequester(firstRow) else Modifier)
                                        .onFocusChanged { if (it.isFocused) focusedId = m.channelId },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (matches.isNotEmpty()) {
            StageKeyHints(
                listOf(
                    stringResource(R.string.common_ok) to stringResource(R.string.sport_hint_preview_play),
                    stringResource(R.string.common_back) to stringResource(R.string.sport_title),
                ),
                Modifier.align(Alignment.BottomEnd).padding(end = 60.mpx, bottom = 27.mpx),
                textSize = 18,
            )
        }
    }
}

/** Follow either side of the game (a team or a duel's athlete), as a star chip each. */
@Composable
private fun FollowChips(f: Fixture, followedTeams: Set<String>, followedPlayers: Set<String>, vm: SportsViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.mpx)) {
        listOfNotNull(f.home, f.away).forEach { team ->
            val on = SportsPrefs.teamKey(f.leagueId, team.abbreviation) in followedTeams
            SportsChip(team.abbreviation, on = on, icon = OwnTVIcon.STAR) { vm.toggleTeamFollowed(f.leagueId, team.abbreviation, team.name) }
        }
        if (f.home == null && f.format == FixtureFormat.VERSUS) {
            f.entries.filter { it.id != null && !it.id.startsWith("-") }.take(2).forEach { e ->
                val id = e.id ?: return@forEach
                val on = SportsPrefs.playerKey(f.leagueId, id) in followedPlayers
                SportsChip(e.name, on = on, icon = OwnTVIcon.STAR) { vm.togglePlayerFollowed(f.leagueId, id) }
            }
        }
    }
}

/**
 * The score header: crest (93) and name each side, the score 81 px, LIVE · status or the day, and the
 * kick-off, round and venue under it. A duel, field or single event shows its name instead.
 */
@Composable
private fun ScoreHeader(f: Fixture) {
    val r = 24.mpx
    val locale = LocalConfiguration.current.locales[0]
    val sep = stringResource(R.string.sport_detail_separator)
    val today = stringResource(R.string.content_epg_today)
    val now = System.currentTimeMillis()
    val live = SportsSchedule.isLiveish(f, now)
    Column(
        Modifier.fillMaxWidth().padding(top = 21.mpx)
            .background(StageColors.Ink.copy(alpha = 0.04f), RoundedCornerShape(r))
            .drawBehind { drawInnerRing(Color.White.copy(alpha = 0.06f), 1.mpx.toPx(), r.toPx()) }
            .padding(horizontal = 33.mpx, vertical = 27.mpx),
    ) {
        val home = f.home
        val away = f.away
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Side(home?.let { Triple(it.logoUrl, it.abbreviation, teamLabel(it)) } ?: f.entries.getOrNull(0)?.takeIf { f.format == FixtureFormat.VERSUS }?.let { Triple(it.logoUrl, it.name, it.name) }, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val score = when {
                    home != null && away != null && f.state != FixtureState.UPCOMING && home.score != null && away.score != null ->
                        stringResource(R.string.sport_score, home.score, away.score)
                    home != null && away != null -> timeText(f.dateMs)
                    f.format == FixtureFormat.VERSUS && f.entries.size >= 2 ->
                        f.entries.take(2).mapNotNull { it.detail }.joinToString(stringResource(R.string.sport_pair_dash)).ifEmpty { timeText(f.dateMs) }
                    else -> f.title ?: f.entries.firstOrNull()?.name ?: f.statusLine
                }
                val big = home != null || f.format == FixtureFormat.VERSUS
                Text(score, style = stageText(if (!big) 30f else if (score.length > 7) 51f else 81f, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(9.mpx))
                if (live) {
                    LiveMark(stringResource(R.string.sport_guide_live) + sep + f.statusLine)
                } else {
                    // Before kick-off the day in the user's own words; after it the source's status ("FT").
                    Text(
                        if (f.state == FixtureState.UPCOMING) dayOf(f.dateMs, locale) ?: today else f.statusLine,
                        style = stageText(17, 700), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Side(away?.let { Triple(it.logoUrl, it.abbreviation, teamLabel(it)) } ?: f.entries.getOrNull(1)?.takeIf { f.format == FixtureFormat.VERSUS }?.let { Triple(it.logoUrl, it.name, it.name) }, Modifier.weight(1f))
        }
        // Kick-off, round, venue — in the box rather than the crumb.
        val info = listOfNotNull(
            ((dayOf(f.dateMs, locale) ?: today) + " " + timeText(f.dateMs)).takeIf { f.state != FixtureState.UPCOMING },
            stageLine(f),
            f.venue,
        ).joinToString(sep)
        if (info.isNotEmpty()) {
            Text(info, style = stageText(17, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.mpx))
        }
        // A leaderboard (golf): its first places under the header.
        if (f.format == FixtureFormat.LEADERBOARD) {
            f.entries.take(5).forEach { e ->
                Row(Modifier.fillMaxWidth().padding(top = 6.mpx), horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
                    Text(e.order?.toString().orEmpty(), style = stageText(18, 700), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(36.mpx))
                    Text(e.name, style = stageText(18, 600), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    e.detail?.let { Text(it, style = stageText(18, 700), color = StageColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** One side of the header: crest and name. */
@Composable
private fun Side(side: Triple<String?, String, String>?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.mpx)) {
        if (side != null) {
            Crest(side.first, side.second, 93)
            Text(side.third, style = stageText(22.5f, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The table tab: the game's two sides highlighted in the league table; OK opens the league page. */
@Composable
private fun TablePreview(table: LeagueTable?, f: Fixture, onOpenLeague: () -> Unit) {
    table ?: return
    StageSurface(onClick = onOpenLeague, radius = 20.mpx, focusStyle = StageFocus.FX, modifier = Modifier.fillMaxWidth().padding(top = 9.mpx)) { _ ->
        Column(Modifier.fillMaxWidth().padding(18.mpx)) {
            table.groupName?.let { Text(it, style = stageText(19, 800), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.mpx, bottom = 6.mpx)) }
            StandingsHeader(table.columns)
            table.rows.forEach { row -> StandingsLine(row, highlight = row.isTeam(f.home) || row.isTeam(f.away)) }
        }
    }
}

/**
 * One channel under Watch live: logo, name, and why it is listed in the tier's colour — the guide's
 * programme (green), the event's own channel (blue) or a likely one (muted).
 */
@Composable
private fun MatchChannelRow(m: ChannelMatch, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    StageRow(onClick = onClick, height = 90.mpx, modifier = modifier.fillMaxWidth(), horizontalPadding = 18.mpx) { _ ->
        ChannelLogoTile(
            logoUrl = m.logoUrl,
            modifier = Modifier.size(69.mpx, 42.mpx).background(stageStillBack, RoundedCornerShape(10.mpx)),
            fill = Color.Transparent,
        ) {}
        Column(Modifier.weight(1f)) {
            Text(m.channelName, style = stageText(20, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val epg = m.epgTitle
            val (color, icon, text) = when {
                playing -> Triple(StageColors.Muted, null, stringResource(R.string.sport_playing_now))
                epg != null -> Triple(TierGuide, OwnTVIcon.EPG, stringResource(R.string.sport_reason_guide, epg))
                m.isExact -> Triple(TierEvent, OwnTVIcon.LIVE_TV, stringResource(R.string.sport_reason_event))
                else -> Triple(StageColors.Muted, null, stringResource(R.string.sport_reason_likely))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.mpx), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) OwnTVIcon(icon, color, Modifier.size(17.mpx))
                Text(text, style = stageText(17, 600), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (playing) OwnTVIcon(OwnTVIcon.CHECK, stageAccent.accent, Modifier.size(22.mpx))
        else SportLanguages.languageOfChannel(m.channelName)?.let { StageTag(stringResource(it.labelRes)) }
    }
}
