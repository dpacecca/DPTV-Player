package tv.own.owntv.features.sport

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageTab
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** The league page's tabs. */
private enum class LeagueTab { TABLE, FIXTURES, RESULTS }

/**
 * A league (v2.6.0, OwnTV's league page): its name and Follow, the tabs, the table on the left and
 * the games on the right ("Australia 24–22 New Zealand"). OK on a game opens its match page.
 */
@Composable
internal fun SportsLeagueScreen(
    vm: SportsViewModel,
    leagueId: String,
    onOpenMatch: (Fixture) -> Unit,
    onBack: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    androidx.activity.compose.BackHandler(enabled = active) { onBack() }
    val board by vm.openLeagueBoard.collectAsStateWithLifecycle()
    val standings by vm.standings.collectAsStateWithLifecycle()
    val activeLeagues by vm.activeLeagues.collectAsStateWithLifecycle()
    val followedTeams by vm.followedTeams.collectAsStateWithLifecycle()
    val league = sportsLeagueById(leagueId) ?: return
    val tables = standings.filter { it.leagueId == leagueId }
    var tab by remember(leagueId) { mutableStateOf(if (tables.isEmpty()) LeagueTab.FIXTURES else LeagueTab.TABLE) }
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { tabFocus.requestFocus() } }
    val sep = stringResource(R.string.sport_detail_separator)
    val fixtures = (board as? SportsViewModel.BoardState.Content)?.board?.fixtures.orEmpty()
    val now = System.currentTimeMillis()

    Column(modifier.fillMaxSize().padding(start = contentStart, end = 60.mpx, top = 39.mpx)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(21.mpx)) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(stringResource(league.labelRes), style = stageText(48, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    Text(stringResource(R.string.sport_title), style = stageText(21, 600), color = stageAccent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sep + sportLabel(league.sportGroup), style = stageText(21, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val following = leagueId in activeLeagues
            SportsChip(stringResource(if (following) R.string.sport_following else R.string.sport_follow), on = following, icon = OwnTVIcon.STAR) {
                vm.toggleLeagueFollowed(leagueId)
            }
        }
        Row(Modifier.padding(top = 24.mpx).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
            LeagueTab.entries.filter { it != LeagueTab.TABLE || tables.isNotEmpty() }.forEachIndexed { i, t ->
                StageTab(
                    label = stringResource(
                        when (t) {
                            LeagueTab.TABLE -> R.string.sport_tab_table
                            LeagueTab.FIXTURES -> R.string.sport_tab_fixtures
                            LeagueTab.RESULTS -> R.string.sport_tab_results
                        },
                    ),
                    count = "", selected = tab == t, onClick = { tab = t },
                    modifier = if (i == 0) Modifier.focusRequester(tabFocus) else Modifier,
                )
            }
        }
        Row(Modifier.fillMaxSize().padding(top = 21.mpx), horizontalArrangement = Arrangement.spacedBy(42.mpx)) {
            val games = when (tab) {
                LeagueTab.TABLE -> fixtures.filter { SportsSchedule.isLiveish(it, now) } +
                    fixtures.filter { it.state == FixtureState.UPCOMING && !SportsSchedule.isLiveish(it, now) }.sortedBy { it.dateMs }.take(10)
                LeagueTab.FIXTURES -> fixtures.filter { it.state != FixtureState.FINISHED }.sortedBy { it.dateMs }
                LeagueTab.RESULTS -> fixtures.filter { it.state == FixtureState.FINISHED }.sortedByDescending { it.dateMs }
            }
            if (tab == LeagueTab.TABLE) {
                LazyColumn(Modifier.width(960.mpx).fillMaxSize(), contentPadding = PaddingValues(bottom = 60.mpx)) {
                    tables.forEach { t ->
                        item { StandingsHeader(t.columns) }
                        t.groupName?.let { name ->
                            item { Text(name, style = stageText(19, 800), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.mpx)) }
                        }
                        items(t.rows) { r ->
                            StandingsLine(r, highlight = SportsPrefs.teamKey(leagueId, r.teamAbbrev) in followedTeams)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (tab == LeagueTab.RESULTS) R.string.sport_tab_results else R.string.sport_this_matchday),
                    style = stageText(25.5f, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 18.mpx, bottom = 9.mpx),
                )
                when {
                    board == null || board is SportsViewModel.BoardState.Loading ->
                        Text(stringResource(R.string.sport_loading), style = stageText(19, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.mpx))
                    games.isEmpty() -> {
                        val unavailable = (board as? SportsViewModel.BoardState.Content)?.board?.unavailable == true || board is SportsViewModel.BoardState.Error
                        val text = when {
                            unavailable && league.source == LeagueSource.SOFASCORE && league.theSportsDbId == null -> R.string.sport_sevens_unavailable
                            unavailable -> R.string.sport_source_unavailable
                            else -> R.string.sport_no_games
                        }
                        Text(stringResource(text), style = stageText(19, 500), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.mpx))
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().focusGroup(), contentPadding = PaddingValues(bottom = 60.mpx)) {
                        items(games, key = { it.id }) { f -> LeagueMatchRow(f, now) { onOpenMatch(f) } }
                    }
                }
            }
        }
    }
}

/** `.lrow` of a league's games: LIVE or the day, "Australia 24–22 New Zealand", then the status or kick-off. */
@Composable
private fun LeagueMatchRow(f: Fixture, now: Long, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val dash = stringResource(R.string.sport_pair_dash)
    StageRow(onClick = onClick, height = 84.mpx, modifier = Modifier.fillMaxWidth(), horizontalPadding = 18.mpx) { _ ->
        val live = SportsSchedule.isLiveish(f, now)
        Box(Modifier.width(96.mpx)) {
            if (live) LiveMark("") else Text(dayOf(f.dateMs, locale) ?: stringResource(R.string.content_epg_today), style = stageText(19, 700), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f)) {
            val home = f.home
            val away = f.away
            val title = if (home != null && away != null && home.score != null && away.score != null && f.state != FixtureState.UPCOMING) {
                teamLabel(home) + " " + stringResource(R.string.sport_score, home.score, away.score) + " " + teamLabel(away)
            } else {
                pairText(f, dash)
            }
            Text(title, style = stageText(20, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = if (f.state == FixtureState.UPCOMING && !live) timeText(f.dateMs) else f.statusLine
            Text(listOfNotNull(time, stageLine(f)).joinToString(stringResource(R.string.sport_detail_separator)), style = stageText(18, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
