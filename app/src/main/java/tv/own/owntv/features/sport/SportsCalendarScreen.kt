package tv.own.owntv.features.sport

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The Sport calendar (v2.6.2, OwnTV's calendar data with DPTV's own page): the month on the left, the
 * day on the right. Moving between dates shows each day's games at once; OK on a day goes into its
 * games, and Back goes back to the dates (Back on the dates returns to the hub). ◀ on a month's first
 * day and ▶ on its last turn the month. OK on a game opens its match page; Hold OK records it (now, or
 * once the guide carries it) or follows either side.
 */
@Composable
internal fun SportsCalendarScreen(
    vm: SportsViewModel,
    onOpenMatch: (Fixture) -> Unit,
    onBack: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val state by vm.calendar.collectAsStateWithLifecycle()
    val month by vm.calendarMonth.collectAsStateWithLifecycle()
    val day by vm.calendarDay.collectAsStateWithLifecycle()
    val activeLeagues by vm.activeLeagues.collectAsStateWithLifecycle()
    val sportOrder by vm.sportOrder.collectAsStateWithLifecycle()
    val rank by vm.leagueRank.collectAsStateWithLifecycle()
    val followedTeams by vm.followedTeams.collectAsStateWithLifecycle()
    val followedPlayers by vm.followedPlayers.collectAsStateWithLifecycle()
    val plans by vm.recordPlans.collectAsStateWithLifecycle()
    var inList by remember { mutableStateOf(vm.calendarInList) }
    fun setList(on: Boolean) { inList = on; vm.calendarInList = on }
    var filter by rememberSaveable(stateSaver = CalendarFilterSaver) { mutableStateOf(SportsHub.Filter()) }
    var menuFor by remember { mutableStateOf<Fixture?>(null) }
    // Counted asks to put focus on the day's cell (a new month, Back from the games) or the game row.
    var dayFocusAsk by remember { mutableIntStateOf(if (vm.calendarInList) 0 else 1) }
    var rowFocusAsk by remember { mutableIntStateOf(if (vm.calendarInList) 1 else 0) }

    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val mine: (Fixture) -> Boolean = remember(followedTeams, followedPlayers) { { vm.isMine(it) } }
    val content = state as? SportsViewModel.CalendarState.Content
    val days = remember(content, filter, followedTeams, followedPlayers) {
        val shown = content?.fixtures.orEmpty().filter { SportsHub.passes(it, filter, mine) }
        SportsCalendar.byDay(shown, zone)
    }
    val dayGames = days[day].orEmpty()
    val chipSports = remember(activeLeagues, sportOrder) { SportsHub.chipSports(activeLeagues, sportOrder) }

    val cellFocus = remember { HashMap<LocalDate, FocusRequester>() }
    fun focusOfDay(d: LocalDate) = cellFocus.getOrPut(d) { FocusRequester() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    fun focusOfRow(id: String) = rowFocus.getOrPut(id) { FocusRequester() }
    val barFocus = remember { FocusRequester() }

    LaunchedEffect(dayFocusAsk) {
        if (dayFocusAsk == 0) return@LaunchedEffect
        for (attempt in 0 until 12) {
            withFrameNanos { }
            if (runCatching { focusOfDay(vm.calendarDay.value).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
        runCatching { barFocus.requestFocus() }
    }
    LaunchedEffect(rowFocusAsk, dayGames.isNotEmpty()) {
        if (rowFocusAsk == 0 || dayGames.isEmpty()) return@LaunchedEffect
        val target = vm.calendarReturnId?.takeIf { id -> dayGames.any { it.id == id } } ?: dayGames.first().id
        for (attempt in 0 until 12) {
            withFrameNanos { }
            if (runCatching { focusOfRow(target).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    BackHandler(enabled = active && menuFor == null) {
        if (inList) {
            setList(false)
            dayFocusAsk++
        } else {
            onBack()
        }
    }

    val locale = LocalConfiguration.current.locales[0]
    val monthTitle = remember(month, locale) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, MonthYearSkeleton), locale).format(month)
    }
    val dayTitle = remember(day, locale) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, DaySkeleton), locale).format(day)
    }
    val sep = stringResource(R.string.sport_detail_separator)

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = contentStart, end = 60.mpx, top = 39.mpx)) {
            Text(stringResource(R.string.sport_calendar), style = stageText(48, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(stringResource(R.string.sport_title), style = stageText(21, 600), color = stageAccent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sep + stringResource(R.string.sport_calendar_subtitle), style = stageText(21, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // The month and the filters.
            Row(
                Modifier.padding(top = 24.mpx).focusRestorer().focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.mpx),
            ) {
                SportsChip("", false, Modifier.focusRequester(barFocus), icon = OwnTVIcon.PAGE_TOWARD_FIRST) {
                    vm.showCalendarMonth(month.minusMonths(1))
                }
                Text(monthTitle, style = stageText(26, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.mpx))
                SportsChip("", false, icon = OwnTVIcon.PAGE_TOWARD_LAST) { vm.showCalendarMonth(month.plusMonths(1)) }
                SportsChip(stringResource(R.string.content_epg_today), false) {
                    vm.showCalendarMonth(YearMonth.from(today), today)
                    setList(false)
                    dayFocusAsk++
                }
                Spacer(Modifier.weight(1f))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
                    item { SportsChip(stringResource(R.string.content_epg_all), filter.isAll) { filter = SportsHub.Filter() } }
                    item { SportsChip(stringResource(R.string.sport_my_teams), filter.myTeams, icon = OwnTVIcon.STAR) { filter = filter.copy(myTeams = !filter.myTeams) } }
                    items(chipSports, key = { it.name }) { s ->
                        SportsChip(sportEmoji(s) + " " + sportLabel(s), s in filter.sports) {
                            filter = if (s in filter.sports) filter.copy(sports = filter.sports - s) else filter.copy(sports = filter.sports + s)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxSize().padding(top = 24.mpx), horizontalArrangement = Arrangement.spacedBy(42.mpx)) {
                // The month.
                Column(Modifier.width(930.mpx)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.mpx), horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                        SportsCalendar.weeks(month).first().indices.forEach { i ->
                            val dow = java.time.DayOfWeek.MONDAY.plus(i.toLong())
                            Text(
                                dow.getDisplayName(TextStyle.SHORT, locale), style = stageText(17, 700), color = StageColors.Dim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.mpx),
                            )
                        }
                    }
                    Column(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(8.mpx)) {
                        SportsCalendar.weeks(month).forEach { week ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                                week.forEach { date ->
                                    if (date == null) {
                                        Spacer(Modifier.weight(1f).height(CellHeight))
                                    } else {
                                        val games = days[date].orEmpty()
                                        DayCell(
                                            date = date,
                                            count = games.size,
                                            mine = games.any(mine),
                                            today = date == today,
                                            past = date.isBefore(today),
                                            selected = date == day,
                                            focusable = !inList,
                                            onFocused = { vm.setCalendarDay(date) },
                                            onClick = { vm.setCalendarDay(date); vm.calendarReturnId = null; setList(true); rowFocusAsk++ },
                                            onKey = { key ->
                                                when {
                                                    key == Key.DirectionLeft && date.dayOfMonth == 1 -> {
                                                        val prev = month.minusMonths(1)
                                                        vm.showCalendarMonth(prev, prev.atEndOfMonth()); dayFocusAsk++; true
                                                    }
                                                    key == Key.DirectionRight && date == month.atEndOfMonth() -> {
                                                        val next = month.plusMonths(1)
                                                        vm.showCalendarMonth(next, next.atDay(1)); dayFocusAsk++; true
                                                    }
                                                    else -> false
                                                }
                                            },
                                            modifier = Modifier.weight(1f).focusRequester(focusOfDay(date)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // The day.
                Column(Modifier.weight(1f)) {
                    Text(dayTitle, style = stageText(30, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = when {
                        content == null -> stringResource(R.string.sport_loading)
                        dayGames.isEmpty() -> stringResource(R.string.sport_calendar_no_games)
                        else -> pluralStringResource(R.plurals.sport_hub_matches, dayGames.size, dayGames.size)
                    }
                    Text(line, style = stageText(19, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.mpx, bottom = 8.mpx))
                    content?.failed?.takeIf { it.isNotEmpty() }?.let { failed ->
                        val names = failed.mapNotNull { sportsLeagueById(it) }.map { stringResource(it.labelRes) }.joinToString(sep)
                        Text(stringResource(R.string.sport_calendar_unreachable, names), style = stageText(17, 500), color = StageColors.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val grouped = remember(dayGames, rank) {
                        dayGames.groupBy { it.leagueId }.entries.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
                    }
                    LazyColumn(Modifier.fillMaxSize().focusGroup(), contentPadding = PaddingValues(bottom = 90.mpx)) {
                        grouped.forEach { (leagueId, games) ->
                            item(key = "h:$leagueId") {
                                val group = sportsLeagueById(leagueId)?.sportGroup
                                Text(
                                    listOfNotNull(group?.let { sportEmoji(it) }, leagueLabel(leagueId)).joinToString(" "),
                                    style = stageText(19, 800), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 18.mpx, top = 16.mpx, bottom = 6.mpx),
                                )
                            }
                            items(games, key = { it.id }) { f ->
                                CalendarGameRow(
                                    f = f,
                                    mine = mine(f),
                                    plan = plans[f.id],
                                    focusable = inList,
                                    onClick = { vm.calendarReturnId = f.id; onOpenMatch(f) },
                                    onHold = { vm.calendarReturnId = f.id; menuFor = f },
                                    modifier = Modifier.focusRequester(focusOfRow(f.id)),
                                )
                            }
                        }
                    }
                }
            }
        }
        StageKeyHints(
            if (inList) {
                listOf(
                    stringResource(R.string.common_ok) to stringResource(R.string.sport_hint_match_page),
                    stringResource(R.string.content_key_hold_ok) to stringResource(R.string.sport_record),
                    stringResource(R.string.common_back) to stringResource(R.string.sport_calendar_back_to_dates),
                )
            } else {
                listOf(
                    stringResource(R.string.common_ok) to stringResource(R.string.sport_calendar_choose_day),
                    stringResource(R.string.common_back) to stringResource(R.string.sport_title),
                )
            },
            Modifier.align(Alignment.BottomStart).padding(start = contentStart, bottom = 27.mpx),
            textSize = 18,
        )
    }

    menuFor?.let { f ->
        CalendarGameMenu(
            fixture = f,
            plan = plans[f.id],
            followedTeams = followedTeams,
            onRecord = { vm.toggleRecord(f) },
            onFollowTeam = { team -> vm.toggleTeamFollowed(f.leagueId, team.abbreviation, team.name) },
            onDismiss = {
                menuFor = null
                rowFocusAsk++
            },
        )
    }
}

/** One day: its number, a dot per game (up to four), the count, and a star when a followed side plays. */
@Composable
private fun DayCell(
    date: LocalDate,
    count: Int,
    mine: Boolean,
    today: Boolean,
    past: Boolean,
    selected: Boolean,
    focusable: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onKey: (Key) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val a = stageAccent
    val r = 14.mpx
    StageSurface(
        onClick = onClick,
        radius = r,
        contentAlignment = Alignment.TopStart,
        highlighted = selected && !focusable,
        modifier = modifier
            .height(CellHeight)
            .alpha(if (past && !selected) 0.55f else 1f)
            .focusProperties { canFocus = focusable }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && onKey(e.key) },
        idle = Modifier
            .background(StageColors.Ink.copy(alpha = 0.06f), RoundedCornerShape(r))
            .drawBehind { if (today) drawInnerRing(a.accent, 2.mpx.toPx(), r.toPx()) },
    ) { focused ->
        val text = if (focused || (selected && !focusable)) a.onAccent else if (today) a.accent else StageColors.Text
        Column(Modifier.fillMaxSize().padding(horizontal = 12.mpx, vertical = 10.mpx)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date.dayOfMonth.toString(), style = stageText(26, 800), color = text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (mine) OwnTVIcon(OwnTVIcon.STAR, SportsStar, Modifier.size(20.mpx))
            }
            Row(Modifier.padding(top = 10.mpx), horizontalArrangement = Arrangement.spacedBy(5.mpx)) {
                repeat(minOf(count, 4)) { Box(Modifier.size(9.mpx).background(if (focused) a.onAccent else a.accent, CircleShape)) }
            }
            Spacer(Modifier.weight(1f))
            if (count > 0) {
                Text(
                    pluralStringResource(R.plurals.sport_hub_matches, count, count),
                    style = stageText(15, 600), color = if (focused) a.onAccent else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A game of the day: kick-off (or LIVE, or the result), the sides, then the recording or the broadcasters. */
@Composable
private fun CalendarGameRow(
    f: Fixture,
    mine: Boolean,
    plan: RecordPlan?,
    focusable: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val dash = stringResource(R.string.sport_pair_dash)
    val sep = stringResource(R.string.sport_detail_separator)
    StageRow(
        onClick = onClick,
        onLongClick = onHold,
        height = 84.mpx,
        modifier = modifier.fillMaxWidth().padding(bottom = 6.mpx).focusProperties { canFocus = focusable },
        horizontalPadding = 18.mpx,
    ) { focused ->
        val live = SportsSchedule.isLiveish(f, now)
        Box(Modifier.width(96.mpx)) {
            when {
                live -> LiveMark(stringResource(R.string.sport_guide_live))
                else -> Text(
                    if (f.state == FixtureState.FINISHED) f.statusLine else timeText(f.dateMs),
                    style = stageText(21, 800), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            val home = f.home
            val away = f.away
            val title = if (home != null && away != null && home.score != null && away.score != null && f.state != FixtureState.UPCOMING) {
                teamLabel(home) + " " + stringResource(R.string.sport_score, home.score, away.score) + " " + teamLabel(away)
            } else {
                pairText(f, dash)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                if (mine) OwnTVIcon(OwnTVIcon.STAR, SportsStar, Modifier.size(20.mpx))
                Text(title, style = stageText(21, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val detail = when (plan?.status) {
                RecordPlan.Status.WAITING -> stringResource(R.string.sport_record_waiting)
                RecordPlan.Status.SCHEDULED -> stringResource(R.string.sport_record_scheduled, plan.channelName.orEmpty())
                RecordPlan.Status.NO_CHANNEL -> stringResource(R.string.sport_record_no_channel)
                null -> listOfNotNull(f.broadcasts.take(3).joinToString(sep).ifEmpty { null }, stageLine(f)).joinToString(sep)
            }
            val color = when (plan?.status) {
                RecordPlan.Status.NO_CHANNEL -> StageColors.Warn
                null -> StageColors.Muted
                else -> SportsLive
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                if (plan != null && plan.status != RecordPlan.Status.NO_CHANNEL) Box(Modifier.size(12.mpx).background(SportsLive, CircleShape))
                Text(detail, style = stageText(17, 500), color = if (focused && plan == null) StageColors.Body else color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Hold OK on a game: record it (or not), and follow either side. */
@Composable
private fun CalendarGameMenu(
    fixture: Fixture,
    plan: RecordPlan?,
    followedTeams: Set<String>,
    onRecord: () -> Unit,
    onFollowTeam: (SportTeam) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    BackHandler { onDismiss() }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim).longPressMenuGuard(), contentAlignment = Alignment.Center) {
            StageMenu(Modifier.width(600.mpx).trapAllFocusExit().focusGroup()) {
                StageMenuHeader(pairText(fixture, stringResource(R.string.sport_pair_dash)), leagueLabel(fixture.leagueId))
                val (label, value) = when (plan?.status) {
                    null -> stringResource(R.string.sport_record) to null
                    RecordPlan.Status.NO_CHANNEL -> stringResource(R.string.sport_record_try_again) to stringResource(R.string.sport_record_no_channel)
                    RecordPlan.Status.WAITING -> stringResource(R.string.sport_record_stop) to stringResource(R.string.sport_record_waiting)
                    // Set: the recording is an ordinary one now, changed or cancelled in Recordings.
                    RecordPlan.Status.SCHEDULED -> stringResource(R.string.sport_record_scheduled, plan.channelName.orEmpty()) to null
                }
                StageMenuItem(
                    text = label,
                    icon = OwnTVIcon.LIVE_DOT,
                    value = value,
                    onClick = { onRecord(); onDismiss() },
                    modifier = Modifier.focusRequester(first),
                )
                listOfNotNull(fixture.home, fixture.away).forEach { team ->
                    val following = SportsPrefs.teamKey(fixture.leagueId, team.abbreviation) in followedTeams
                    StageMenuItem(
                        text = teamLabel(team),
                        icon = OwnTVIcon.STAR,
                        iconFilled = following,
                        value = stringResource(if (following) R.string.sport_following else R.string.sport_follow),
                        onClick = { onFollowTeam(team); onDismiss() },
                    )
                }
            }
        }
    }
}

private val CellHeight = 118.mpx

private const val MonthYearSkeleton = "MMMMy"
private const val DaySkeleton = "EEEEdMMMM"

/** The calendar's chips across configuration changes: sports by name. */
private val CalendarFilterSaver = androidx.compose.runtime.saveable.Saver<SportsHub.Filter, List<String>>(
    save = { f -> listOf(f.myTeams.toString(), f.sports.joinToString(FilterSep) { it.name }) },
    restore = { saved ->
        val sports = saved.getOrNull(1).orEmpty().split(FilterSep).mapNotNull { n -> SportGroup.entries.firstOrNull { it.name == n } }.toSet()
        SportsHub.Filter(sports, saved.firstOrNull() == true.toString())
    },
)

private const val FilterSep = ","
