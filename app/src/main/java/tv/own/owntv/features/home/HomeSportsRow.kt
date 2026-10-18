package tv.own.owntv.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.own.owntv.R
import tv.own.owntv.features.epg.GuideLiveRed
import tv.own.owntv.features.epg.GuideTag
import tv.own.owntv.features.sport.Fixture
import tv.own.owntv.features.sport.FixtureFormat
import tv.own.owntv.features.sport.FixtureState
import tv.own.owntv.features.sport.SportsViewModel
import tv.own.owntv.features.sport.sportDayLabel
import tv.own.owntv.features.sport.sportsLeagueById
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.StageRadii
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Home's Sports row (v5.2 port of OwnTV's "Sports row on Home with a LIVE mark"): live games and
 * games kicking off within three hours from the user's leagues, teams and athletes
 * ([SportsViewModel.homeRow]), each card with the guide's red LIVE tag while it is live. OK opens the
 * Sport section on that game. Draws nothing when there is nothing to show — the shell only asks for
 * the row while the Sport hub is visible. Home keeps the hub's live refresh running
 * ([SportsViewModel.Watcher.HOME]), so a score on Home moves without opening Sport.
 */
@Composable
fun HomeSportsRow(
    fixtures: List<Fixture>,
    onOpen: (Fixture) -> Unit,
    onFocus: () -> Unit,
    start: Dp,
    track: (FocusRequester) -> Unit,
    firstItemFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    if (fixtures.isEmpty()) return
    Column(modifier) {
        HomeRowHeader(stringResource(R.string.sport_home_row_title), small = null, start = start)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx),
            modifier = Modifier.focusRestorer().focusGroup(),
        ) {
            itemsIndexed(fixtures, key = { _, f -> f.id }) { index, fixture ->
                SportCard(
                    fixture = fixture,
                    onClick = { onOpen(fixture) },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .tracked(track = track)
                        .onFocusChanged { if (it.hasFocus) onFocus() },
                )
            }
        }
    }
}

/** One game, the size of Home's `.oncard` (336 × 132): status line, then the two sides or the event. */
@Composable
private fun SportCard(fixture: Fixture, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a = stageAccent
    val live = fixture.state == FixtureState.LIVE
    StageSurface(
        onClick = onClick,
        radius = StageRadii.Row,
        modifier = modifier.size(336.mpx, 132.mpx),
        focusStyle = StageFocus.FX,
        idle = Modifier.background(StageColors.Ink.copy(alpha = 0.05f), RoundedCornerShape(StageRadii.Row)),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.mpx, vertical = 14.mpx)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                if (live) GuideTag(stringResource(R.string.sport_guide_live), GuideLiveRed, filled = true, size = 13)
                val status = if (live) {
                    fixture.statusLine
                } else {
                    val day = sportDayLabel(
                        fixture.dateMs,
                        stringResource(R.string.sport_tomorrow_word),
                        stringResource(R.string.sport_yesterday_word),
                    )
                    listOfNotNull(day, fixture.statusLine).joinToString(stringResource(R.string.sport_detail_separator))
                }
                Text(status, style = stageText(15, 600), color = if (live) a.accent else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                sportsLeagueById(fixture.leagueId)?.let { league ->
                    Text(stringResource(league.labelRes), style = stageText(13, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            Spacer(Modifier.height(8.mpx))
            val home = fixture.home
            val away = fixture.away
            when {
                home != null && away != null -> {
                    SideLine(home.logoUrl, home.name, home.score, live)
                    SideLine(away.logoUrl, away.name, away.score, live)
                }
                fixture.format == FixtureFormat.VERSUS && fixture.entries.isNotEmpty() -> {
                    fixture.entries.take(2).forEach { SideLine(it.logoUrl, it.name, it.detail, live) }
                }
                else -> {
                    // An event (Grand Prix) or a leaderboard (golf): its name and where, or the leader.
                    Text(
                        fixture.title ?: fixture.entries.firstOrNull()?.name ?: fixture.statusLine,
                        style = stageText(19, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    fixture.venue?.let { venue ->
                        Text(venue, style = stageText(15, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** One side of a game: logo, name, score (accent while live). */
@Composable
private fun SideLine(logoUrl: String?, name: String, score: String?, live: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.mpx),
    ) {
        if (logoUrl.isNullOrBlank()) {
            Spacer(Modifier.size(26.mpx))
        } else {
            AsyncImage(model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(26.mpx))
        }
        Text(name, style = stageText(18, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (!score.isNullOrBlank()) {
            Text(score, style = stageText(18, 800), color = if (live) stageAccent.accent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
