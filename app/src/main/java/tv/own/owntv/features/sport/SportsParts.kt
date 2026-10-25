package tv.own.owntv.features.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.drawBoxShadow
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/*
 * The Sport section's shared parts (v2.6.0), ported from OwnTV's Sports Hub (SportsParts, SportsTable)
 * onto DPTV's own models: crest, LIVE mark, chip, the table, labels and day words.
 */

/** The live red: the LIVE dot, a running minute. */
internal val SportsLive = Color(0xFFFF4D4D)

/** A followed side's star. */
internal val SportsStar = Color(0xFFE7C56A)

/** `.cr`: a team's crest in a circle, or its short code when there is none. */
@Composable
internal fun Crest(logoUrl: String?, code: String, size: Int) {
    Box(
        Modifier.size(size.mpx).clip(CircleShape).background(StageColors.Ink.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (logoUrl.isNullOrBlank()) {
            Text(initials(code), style = stageText(size * 0.3f, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            AsyncImage(model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding((size * 0.1f).mpx))
        }
    }
}

/** "J. Sinner" → "JS"; a short code stays as it is. */
private fun initials(name: String): String {
    if (name.length <= 4) return name.uppercase()
    val words = name.split(' ', '.', '-').filter { it.isNotBlank() }
    return (words.firstOrNull()?.take(1).orEmpty() + words.drop(1).lastOrNull()?.take(1).orEmpty()).uppercase()
}

/** `.pill-live`: the red dot and a white caps label ("LIVE", "LIVE · 63'"). */
@Composable
internal fun LiveMark(text: String, textSize: Float = 16.5f) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.mpx), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.mpx).drawBehind {
                drawBoxShadow(SportsLive.copy(alpha = 0.25f), 4.mpx.toPx(), size.minDimension / 2f)
                drawCircle(SportsLive)
            },
        )
        if (text.isNotEmpty()) Text(text, style = stageText(textSize, 800, 0.06.em), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.chip`: 51 high, pill-round; the chosen one accent 22% with a 1.5 px accent ring. Focused = filled. */
@Composable
internal fun SportsChip(
    text: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    icon: OwnTVIcon? = null,
    dim: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val a = stageAccent
    val r = 26.mpx
    StageSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        radius = r,
        modifier = modifier.height(51.mpx),
        idle = if (on) {
            Modifier.background(a.accent.copy(alpha = 0.22f), RoundedCornerShape(r))
                .drawBehind { drawInnerRing(a.accent.copy(alpha = 0.7f), 1.5f * 1.mpx.toPx(), r.toPx()) }
        } else {
            Modifier.background(StageColors.ControlFill, RoundedCornerShape(r))
        },
    ) { focused ->
        val color = when {
            focused -> a.onAccent
            dim -> StageColors.Muted
            on -> StageColors.Text
            else -> StageColors.Body
        }
        Row(
            Modifier.padding(horizontal = 21.mpx),
            horizontalArrangement = Arrangement.spacedBy(10.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) OwnTVIcon(icon, color, Modifier.size(20.mpx))
            if (text.isNotEmpty()) Text(text, style = stageText(19.5f, 600), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A sport's name. */
internal val SportGroup.labelRes: Int
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

@Composable
@ReadOnlyComposable
internal fun sportLabel(group: SportGroup): String = stringResource(group.labelRes)

/** The chip emoji per sport (OwnTV's). */
internal fun sportEmoji(group: SportGroup): String = when (group) {
    SportGroup.SOCCER -> "⚽"
    SportGroup.BASKETBALL -> "🏀"
    SportGroup.TENNIS -> "🎾"
    SportGroup.CRICKET -> "🏏"
    SportGroup.MOTORSPORT -> "🏎"
    SportGroup.ICE_HOCKEY -> "🏒"
    SportGroup.AMERICAN_FOOTBALL -> "🏈"
    SportGroup.MMA -> "🥊"
    SportGroup.BASEBALL -> "⚾"
    SportGroup.GOLF -> "⛳"
    SportGroup.RUGBY_UNION, SportGroup.RUGBY_LEAGUE, SportGroup.AUSTRALIAN_FOOTBALL -> "🏉"
    SportGroup.VOLLEYBALL -> "🏐"
    SportGroup.OTHER -> "🏅"
}

/** A league's name from the catalogue. */
@Composable
@ReadOnlyComposable
internal fun leagueLabel(leagueId: String): String =
    sportsLeagueById(leagueId)?.let { stringResource(it.labelRes) }.orEmpty()

/** "13:30" in the device's clock format, as the rest of the app shows times. */
@Composable
internal fun timeText(ms: Long): String = tv.own.owntv.ui.format.rememberSystemTimeFormatter()(ms)

/** The day a game falls on, when it is not today: "Sat" within a week, "Fri 23" beyond it. Null for today. */
internal fun dayOf(ms: Long, locale: Locale, today: LocalDate = LocalDate.now()): String? {
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    if (date == today) return null
    val pattern = if (date.isBefore(today.plusDays(7)) && date.isAfter(today.minusDays(7))) {
        WeekdayPattern
    } else {
        android.text.format.DateFormat.getBestDateTimePattern(locale, WeekdayDayPattern)
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(date)
}

private const val WeekdayPattern = "EEE"
private const val WeekdayDayPattern = "EEEd"

/** A side's name on a card: the full name, or its code when the source gives nothing longer. */
internal fun teamLabel(team: SportTeam): String = team.name.ifBlank { team.abbreviation }

/** The two sides of a game in words, "Australia – Fiji" (or the event's name, or the duel's athletes). */
internal fun pairText(fixture: Fixture, dash: String): String {
    val home = fixture.home
    val away = fixture.away
    return when {
        home != null && away != null -> teamLabel(home) + dash + teamLabel(away)
        fixture.format == FixtureFormat.EVENT -> fixture.title ?: fixture.statusLine
        else -> fixture.entries.take(2).joinToString(dash) { it.name }
    }
}

/** The number columns of a league table: whatever the source reports, each 63 wide. */
private val NumberColumn = 63.mpx

/** `# Team …columns`, 16.5 px dim. */
@Composable
internal fun StandingsHeader(columns: List<String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.mpx, vertical = 6.mpx), verticalAlignment = Alignment.CenterVertically) {
        Text(Hash, style = stageText(16.5f, 700), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(42.mpx))
        Text(stringResource(R.string.sport_table_team), style = stageText(16.5f, 700), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        columns.take(TableColumns).forEach {
            Text(it, style = stageText(16.5f, 700), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.width(NumberColumn))
        }
    }
}

/** One table row; [highlight] (a side of the open game, a followed team) on accent 14%. */
@Composable
internal fun StandingsLine(row: StandingRow, highlight: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (highlight) Modifier.background(stageAccent.accent.copy(alpha = 0.14f), RoundedCornerShape(10.mpx)) else Modifier)
            .padding(horizontal = 12.mpx, vertical = 7.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.rank, style = stageText(19, 700), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(42.mpx))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalAlignment = Alignment.CenterVertically) {
            Crest(row.logoUrl, row.teamAbbrev.ifBlank { row.teamName }, 30)
            Text(row.teamName, style = stageText(19, if (highlight) 800 else 600), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val shown = row.stats.take(TableColumns)
        shown.forEachIndexed { i, v ->
            val last = i == shown.lastIndex
            Text(v, style = stageText(19, if (last) 800 else 500), color = if (last) StageColors.Text else StageColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.width(NumberColumn))
        }
    }
}

/** True when [row] is the side [team] (by code or name). */
internal fun StandingRow.isTeam(team: SportTeam?): Boolean =
    team != null && ((teamAbbrev.isNotBlank() && teamAbbrev == team.abbreviation) || teamName.equals(team.name, ignoreCase = true))

/** Table columns shown at most (the source's first ones). */
private const val TableColumns = 8
private const val Hash = "#"
