package tv.own.owntv.features.sport

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.R

/**
 * World Rugby's own results feed (DPTV v2.9.20), the preferred source for rugby: the federation's
 * fixtures, live scores, tables and team sheets for internationals and the big club competitions.
 * Keyless JSON, mapped into the very models [EspnApi] produces. It carries no crests and no
 * broadcasters; [SportsApi] lays ESPN over it for those ([WorldRugbyRules.merge]).
 *
 * A competition is one event per season ("Six Nations 2026", "URC 2027"); a league names its
 * competition without the season ([SportLeague.worldRugby]) and [WorldRugbyRules.pickEvent] takes the
 * season on now, else the one about to start, else the last one played.
 */
class WorldRugbyApi(private val client: OkHttpClient, context: Context) {

    /** One competition season. */
    data class Event(val id: String, val label: String, val sport: String, val startMs: Long, val endMs: Long)

    /** The words around a fixture's score that come from resources. */
    data class Labels(val fullTime: String, val halfTime: String, val venueSeparator: String, val minute: (Int) -> String)

    private val appContext = context.applicationContext
    private val labels: Labels by lazy {
        Labels(
            appContext.getString(R.string.sport_status_full_time),
            appContext.getString(R.string.sport_status_half_time),
            appContext.getString(R.string.sport_venue_separator),
        ) { appContext.getString(R.string.sport_status_minute, it) }
    }

    private val eventLock = Mutex()
    private var eventCache: List<Event> = emptyList()
    private var eventsAt = 0L

    private val matchLock = Mutex()
    private val matchCache = HashMap<String, Pair<Long, List<JSONObject>>>()

    private val lineupLock = Mutex()
    private val lineupCache = HashMap<String, Pair<Long, Lineups>>()

    /** The league's current season, or null when World Rugby has none of that name. */
    suspend fun eventFor(league: SportLeague): Event? =
        WorldRugbyRules.pickEvent(events().filter { WorldRugbyRules.isCompetition(it, league) }, System.currentTimeMillis())

    /**
     * The season's fixtures: those from four weeks back to six weeks ahead, or, between seasons,
     * the whole season (the last results and the first games). Null when there is no season.
     */
    suspend fun fixtures(league: SportLeague): List<Fixture>? = withContext(Dispatchers.IO) {
        val event = eventFor(league) ?: return@withContext null
        val now = System.currentTimeMillis()
        val window = matches(event.id, now - WindowBackMs, now + WindowAheadMs)
        val raw = window.ifEmpty { matches(event.id, null, null) }
        parse(league, raw)
    }

    /** The season's fixtures in one month (the calendar). Null when there is no season. */
    suspend fun month(league: SportLeague, month: java.time.YearMonth): List<Fixture>? = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val events = events().filter { WorldRugbyRules.isCompetition(it, league) && it.startMs <= to && it.endMs + DayMs >= from }
        if (events.isEmpty()) return@withContext if (eventFor(league) == null) null else emptyList()
        events.flatMap { parse(league, matches(it.id, from - DayMs, to + DayMs)) }.filter { it.dateMs in from..to }
    }

    /** The season's tables, one per pool; empty when it has none (a knockout, a test window). */
    suspend fun standings(league: SportLeague): List<LeagueTable> = withContext(Dispatchers.IO) {
        val event = eventFor(league) ?: return@withContext emptyList()
        val root = get(Base + EventPath + event.id + StandingsPath)
        tablesFrom(
            league, root,
            columns = listOf(
                appContext.getString(R.string.sport_table_played),
                appContext.getString(R.string.sport_table_won),
                appContext.getString(R.string.sport_table_drawn),
                appContext.getString(R.string.sport_table_lost),
                appContext.getString(R.string.sport_table_difference),
                appContext.getString(R.string.sport_table_bonus),
                appContext.getString(R.string.sport_table_points),
            ),
            groupName = { appContext.getString(R.string.sport_group_name, it) },
        )
    }

    /** A match's team sheets; empty sides until they are named. Kept for a few minutes. */
    suspend fun lineups(matchId: String): Lineups = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        lineupLock.withLock { lineupCache[matchId]?.takeIf { now - it.first < LineupCacheMs }?.second }
            ?.let { return@withContext it }
        val sheets = lineupsFrom(get(Base + MatchPath + matchId + SummaryPath))
        lineupLock.withLock { lineupCache[matchId] = now to sheets }
        sheets
    }

    private fun parse(league: SportLeague, raw: List<JSONObject>): List<Fixture> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        return raw.mapNotNull { runCatching { fixtureFrom(league, it, labels, zone, now) }.getOrNull() }
            .distinctBy { it.id }
            .sortedBy { it.dateMs }
    }

    /** Every season from a year back to eight months ahead, kept for [EventCacheMs]. */
    private suspend fun events(): List<Event> = eventLock.withLock {
        val now = System.currentTimeMillis()
        if (eventCache.isNotEmpty() && now - eventsAt < EventCacheMs) return@withLock eventCache
        val from = day(now - EventsBackMs)
        val to = day(now + EventsAheadMs)
        val out = ArrayList<Event>()
        var page = 0
        do {
            val root = get(Base + EventListPath + StartQuery + from + EndQuery + to + PageQuery + page)
            root.optJSONArray(ContentKey)?.let { out.addAll(eventsFrom(it)) }
            val pages = root.optJSONObject(PageInfoKey)?.optInt(NumPagesKey, 1) ?: 1
            page++
        } while (page < pages && page < MaxPages)
        eventCache = out
        eventsAt = now
        out
    }

    /** One season's matches between [fromMs] and [toMs] (both null: all of it), kept for [MatchCacheMs]. */
    private suspend fun matches(eventId: String, fromMs: Long?, toMs: Long?): List<JSONObject> {
        val range = if (fromMs != null && toMs != null) StartQuery + day(fromMs) + EndQuery + day(toMs) else ""
        val url = Base + MatchListPath + EventsQuery + eventId + range + SortQuery
        val now = System.currentTimeMillis()
        matchLock.withLock { matchCache[url]?.takeIf { now - it.first < MatchCacheMs }?.second }?.let { return it }
        val out = ArrayList<JSONObject>()
        var page = 0
        do {
            val root = get(url + PageQuery + page)
            root.optJSONArray(ContentKey)?.let { list -> for (i in 0 until list.length()) list.optJSONObject(i)?.let(out::add) }
            val pages = root.optJSONObject(PageInfoKey)?.optInt(NumPagesKey, 1) ?: 1
            page++
        } while (page < pages && page < MaxPages)
        matchLock.withLock {
            if (matchCache.size > MaxCachedQueries) matchCache.clear()
            matchCache[url] = now to out
        }
        return out
    }

    private fun get(url: String): JSONObject {
        val request = Request.Builder().url(url).header(AcceptHeader, JsonMime).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException(HttpFailed + response.code)
            return JSONObject(response.body.string())
        }
    }

    companion object {
        /**
         * One World Rugby match as a [Fixture] (checked against live answers, 2026-11-14):
         *  - `teams[0]` is home, `teams[1]` away; `scores` in the same order, shown once started;
         *  - `status` "U" is to come, "C" complete, any "L…" in progress ("LHT" half time); the
         *    clock's seconds give the minute;
         *  - `outcome` "A" a home win, "B" an away win;
         *  - the round is `eventPhase` ("Quarter-Finals"), never "League" or the "Match 12" numbering.
         */
        internal fun fixtureFrom(league: SportLeague, match: JSONObject, labels: Labels, zone: ZoneId, now: Long): Fixture? {
            val id = match.optStringOrNull(MatchIdKey) ?: return null
            val dateMs = match.optJSONObject(TimeKey)?.optLong(MillisKey, 0L) ?: 0L
            val teams = match.optJSONArray(TeamsKey) ?: return null
            if (dateMs <= 0L || teams.length() < 2) return null
            val status = match.optString(StatusKey)
            val state = stateOf(status, dateMs, now)
            val clockSecs = match.optJSONObject(ClockKey)?.optInt(SecsKey, 0) ?: 0
            val statusLine = when {
                state == FixtureState.UPCOMING -> DateTimeFormatter.ofPattern(TimePattern).withZone(zone).format(Instant.ofEpochMilli(dateMs))
                state == FixtureState.FINISHED -> labels.fullTime
                status == HalfTimeStatus -> labels.halfTime
                else -> labels.minute(clockSecs / 60)
            }
            val scores = match.optJSONArray(ScoresKey)
            val started = state != FixtureState.UPCOMING
            val outcome = match.optString(OutcomeKey)
            val home = teamOf(teams.optJSONObject(0), scores?.optScore(0)?.takeIf { started }, outcome == HomeWin, HomeValue) ?: return null
            val away = teamOf(teams.optJSONObject(1), scores?.optScore(1)?.takeIf { started }, outcome == AwayWin, AwayValue) ?: return null
            return Fixture(
                id = league.id + WrIdPart + id,
                leagueId = league.id,
                dateMs = dateMs,
                state = state,
                statusLine = statusLine,
                format = FixtureFormat.VERSUS,
                home = home,
                away = away,
                broadcasts = emptyList(),
                venue = venueOf(match.optJSONObject(VenueKey), labels.venueSeparator),
                round = match.optStringOrNull(PhaseKey)?.takeUnless { it.equals(LeaguePhase, ignoreCase = true) },
                worldRugbyMatchId = id,
            )
        }

        internal fun stateOf(status: String, dateMs: Long, now: Long): FixtureState = when {
            status == CompleteStatus -> FixtureState.FINISHED
            status == UpcomingStatus -> FixtureState.UPCOMING
            status.startsWith(LiveStatusPrefix) -> FixtureState.LIVE
            dateMs > now -> FixtureState.UPCOMING
            else -> FixtureState.FINISHED
        }

        private fun teamOf(team: JSONObject?, score: String?, winner: Boolean, homeAway: String): SportTeam? {
            val name = team?.optStringOrNull(NameKey) ?: return null
            return SportTeam(
                id = WrTeamPrefix + (team.optStringOrNull(IdKey) ?: name),
                name = name,
                abbreviation = team.optStringOrNull(AbbreviationKey) ?: name,
                logoUrl = null,
                colorHex = null,
                score = score,
                winner = winner,
                homeAway = homeAway,
            )
        }

        private fun JSONArray.optScore(i: Int): String? = opt(i)?.takeIf { it is Number }?.toString()

        private fun venueOf(venue: JSONObject?, separator: String): String? {
            val name = venue?.optStringOrNull(NameKey)?.trim() ?: return null
            val city = venue.optStringOrNull(CityKey)?.trim()
            return if (city != null && city != name) name + separator + city else name
        }

        internal fun eventsFrom(list: JSONArray): List<Event> = (0 until list.length()).mapNotNull { i ->
            val e = list.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optStringOrNull(IdKey) ?: return@mapNotNull null
            val label = e.optStringOrNull(LabelKey) ?: return@mapNotNull null
            Event(
                id = id,
                label = label,
                sport = e.optString(SportKey).lowercase(java.util.Locale.ROOT),
                startMs = e.optJSONObject(StartKey)?.optLong(MillisKey, 0L) ?: 0L,
                endMs = e.optJSONObject(EndKey)?.optLong(MillisKey, 0L) ?: 0L,
            )
        }

        /** Tables in order, one per pool; stats as [columns]: P W D L +/− BP Pts. */
        internal fun tablesFrom(league: SportLeague, root: JSONObject, columns: List<String>, groupName: (String) -> String): List<LeagueTable> {
            val tables = root.optJSONArray(TablesKey) ?: return emptyList()
            val out = ArrayList<LeagueTable>()
            for (t in 0 until tables.length()) {
                val table = tables.optJSONObject(t) ?: continue
                val entries = table.optJSONArray(EntriesKey) ?: continue
                val rows = ArrayList<StandingRow>()
                for (e in 0 until entries.length()) {
                    val entry = entries.optJSONObject(e) ?: continue
                    val team = entry.optJSONObject(TeamKey) ?: continue
                    val name = team.optStringOrNull(NameKey) ?: continue
                    val difference = entry.optInt(PointsForKey, 0) - entry.optInt(PointsAgainstKey, 0)
                    rows.add(
                        StandingRow(
                            rank = entry.optStringOrNull(PositionLabelKey) ?: (rows.size + 1).toString(),
                            teamName = name,
                            teamAbbrev = team.optStringOrNull(AbbreviationKey) ?: name,
                            logoUrl = null,
                            stats = listOf(
                                entry.optInt(PlayedKey, 0).toString(),
                                entry.optInt(WonKey, 0).toString(),
                                entry.optInt(DrawnKey, 0).toString(),
                                entry.optInt(LostKey, 0).toString(),
                                (if (difference > 0) Plus else "") + difference.toString(),
                                entry.optInt(BonusKey, 0).toString(),
                                entry.optInt(PointsKey, 0).toString(),
                            ),
                        ),
                    )
                }
                if (rows.isEmpty()) continue
                val label = table.optStringOrNull(LabelKey)
                val pool = table.optStringOrNull(ShortLabelKey)
                val name = label ?: pool?.takeIf { tables.length() > 1 }?.let(groupName)
                out.add(LeagueTable(league.id, name, columns, rows))
            }
            return out
        }

        /**
         * The two team sheets of a match summary: `teams[i].teamList.list`, numbers 1–15 starting and
         * 16 up on the bench, the captain by `captainIds`, and the head coach among the numberless.
         */
        internal fun lineupsFrom(root: JSONObject): Lineups {
            val teams = root.optJSONArray(TeamsKey)
            return Lineups(sheetOf(teams?.optJSONObject(0)), sheetOf(teams?.optJSONObject(1)))
        }

        private fun sheetOf(team: JSONObject?): TeamLineup {
            val list = team?.optJSONObject(TeamListKey) ?: return TeamLineup(emptyList(), emptyList())
            val captains = HashSet<String>()
            list.optJSONArray(CaptainIdsKey)?.let { ids -> for (i in 0 until ids.length()) captains.add(ids.optString(i)) }
            val players = list.optJSONArray(ListKey) ?: return TeamLineup(emptyList(), emptyList())
            val numbered = ArrayList<LineupPlayer>()
            var coach: String? = null
            for (i in 0 until players.length()) {
                val entry = players.optJSONObject(i) ?: continue
                val player = entry.optJSONObject(PlayerKey) ?: continue
                val name = player.optJSONObject(NameKey)?.optStringOrNull(DisplayKey) ?: continue
                val number = entry.optStringOrNull(NumberKey)?.toIntOrNull()
                if (number == null) {
                    if (coach == null && entry.optStringOrNull(PositionLabelKey)?.equals(HeadCoach, ignoreCase = true) == true) coach = name
                    continue
                }
                numbered.add(
                    LineupPlayer(
                        number = number,
                        name = name,
                        position = entry.optStringOrNull(PositionLabelKey)?.takeIf { number <= Starters },
                        captain = player.optString(IdKey) in captains,
                        caps = player.opt(CapsKey)?.takeIf { it is Number }?.let { (it as Number).toInt() },
                    ),
                )
            }
            numbered.sortBy { it.number }
            return TeamLineup(
                starters = numbered.filter { (it.number ?: 0) <= Starters },
                replacements = numbered.filter { (it.number ?: 0) > Starters },
                coach = coach,
            )
        }

        /** `optString`, but null for a missing value or JSON null (org.json returns "null" for the latter). */
        private fun JSONObject.optStringOrNull(key: String): String? =
            optString(key).takeIf { it.isNotBlank() && it != NullText }

        private fun day(ms: Long): String = DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))

        private const val Base = "https://api.wr-rims-prod.pulselive.com/rugby/v3"
        private const val EventListPath = "/event?pageSize=100"
        private const val MatchListPath = "/match?pageSize=100"
        private const val EventPath = "/event/"
        private const val MatchPath = "/match/"
        private const val StandingsPath = "/standings"
        private const val SummaryPath = "/summary"
        private const val StartQuery = "&startDate="
        private const val EndQuery = "&endDate="
        private const val PageQuery = "&page="
        private const val EventsQuery = "&events="
        private const val SortQuery = "&sort=asc"
        private const val AcceptHeader = "Accept"
        private const val JsonMime = "application/json"
        private const val HttpFailed = "World Rugby HTTP "
        private const val TimePattern = "HH:mm"
        private const val NullText = "null"
        private const val Plus = "+"
        private const val WrIdPart = "/wr/"
        private const val WrTeamPrefix = "wr"
        private const val HomeValue = "home"
        private const val AwayValue = "away"
        private const val HomeWin = "A"
        private const val AwayWin = "B"
        private const val CompleteStatus = "C"
        private const val UpcomingStatus = "U"
        private const val HalfTimeStatus = "LHT"
        private const val LiveStatusPrefix = "L"
        private const val LeaguePhase = "League"
        private const val HeadCoach = "Head Coach"
        private const val Starters = 15

        private const val DayMs = 24L * 60L * 60L * 1000L
        private const val WindowBackMs = 28L * DayMs
        private const val WindowAheadMs = 42L * DayMs
        private const val EventsBackMs = 400L * DayMs
        private const val EventsAheadMs = 240L * DayMs
        private const val EventCacheMs = 6L * 60L * 60L * 1000L
        private const val MatchCacheMs = 20_000L
        private const val LineupCacheMs = 3L * 60L * 1000L
        private const val MaxPages = 6
        private const val MaxCachedQueries = 64

        private const val ContentKey = "content"
        private const val PageInfoKey = "pageInfo"
        private const val NumPagesKey = "numPages"
        private const val MatchIdKey = "matchId"
        private const val TimeKey = "time"
        private const val MillisKey = "millis"
        private const val TeamsKey = "teams"
        private const val StatusKey = "status"
        private const val ClockKey = "clock"
        private const val SecsKey = "secs"
        private const val ScoresKey = "scores"
        private const val OutcomeKey = "outcome"
        private const val VenueKey = "venue"
        private const val CityKey = "city"
        private const val PhaseKey = "eventPhase"
        private const val IdKey = "id"
        private const val NameKey = "name"
        private const val AbbreviationKey = "abbreviation"
        private const val LabelKey = "label"
        private const val ShortLabelKey = "shortLabel"
        private const val SportKey = "sport"
        private const val StartKey = "start"
        private const val EndKey = "end"
        private const val TablesKey = "tables"
        private const val EntriesKey = "entries"
        private const val TeamKey = "team"
        private const val PositionLabelKey = "positionLabel"
        private const val PlayedKey = "played"
        private const val WonKey = "won"
        private const val DrawnKey = "drawn"
        private const val LostKey = "lost"
        private const val PointsForKey = "pointsFor"
        private const val PointsAgainstKey = "pointsAgainst"
        private const val BonusKey = "bonusPoints"
        private const val PointsKey = "points"
        private const val TeamListKey = "teamList"
        private const val CaptainIdsKey = "captainIds"
        private const val ListKey = "list"
        private const val PlayerKey = "player"
        private const val DisplayKey = "display"
        private const val NumberKey = "number"
        private const val CapsKey = "caps"
    }
}

/** (DPTV v2.9.20) Which World Rugby season a league shows, and how ESPN is laid over it. */
internal object WorldRugbyRules {

    /** [event] is a season of [league]'s competition: its name without the season, same sport. */
    fun isCompetition(event: WorldRugbyApi.Event, league: SportLeague): Boolean {
        if (league.worldRugby.isEmpty() || event.sport != league.worldRugbySport) return false
        val name = competitionKey(event.label)
        return league.worldRugby.any { competitionKey(it) == name }
    }

    /** "2026 Six Nations", "Rugby Europe Trophy Men's 2026-27" → the name alone, normalised. */
    fun competitionKey(label: String): String =
        SportNameMatch.normalize(seasonPattern.replace(label, " ")).trim()

    /**
     * The season on now (the latest to start, if two overlap); else the next one starting within
     * [SoonMs]; else the last one played; else the next one at all.
     */
    fun pickEvent(events: List<WorldRugbyApi.Event>, now: Long): WorldRugbyApi.Event? {
        val current = events.filter { it.startMs <= now && now <= it.endMs + DayMs }
        if (current.isNotEmpty()) return current.maxBy { it.startMs }
        val next = events.filter { it.startMs > now }.minByOrNull { it.startMs }
        if (next != null && next.startMs - now <= SoonMs) return next
        return events.filter { it.endMs < now }.maxByOrNull { it.endMs } ?: next
    }

    /**
     * World Rugby's fixtures with ESPN's laid over them. A World Rugby game and an ESPN game are the
     * same game when they kick off within [SameGameMs] and a side matches ([sameTeam]); the game then
     * keeps ESPN's id (so follows, recordings and the matcher see what they saw before) and takes its
     * broadcasters, and both sides take ESPN's team (id, code, name, crest, colour) with World Rugby's
     * score. A side ESPN never showed takes the crest of the same team in [crests] (ESPN's rugby teams
     * from other competitions), else keeps World Rugby's name without a crest.
     */
    fun merge(wr: List<Fixture>, espn: List<Fixture>, crests: List<TeamRef>): List<Fixture> {
        val unused = espn.filter { it.home != null && it.away != null }.toMutableList()
        val teamMap = HashMap<String, SportTeam>()
        val paired = wr.map { w ->
            val match = unused.filter { kotlin.math.abs(it.dateMs - w.dateMs) <= SameGameMs && orientation(w, it) != null }
                .minByOrNull { kotlin.math.abs(it.dateMs - w.dateMs) }
            if (match != null) {
                unused.remove(match)
                val crossed = orientation(w, match) == false
                val eHome = if (crossed) match.away!! else match.home!!
                val eAway = if (crossed) match.home!! else match.away!!
                w.home?.let { teamMap[it.id] = eHome }
                w.away?.let { teamMap[it.id] = eAway }
            }
            w to match
        }
        return paired.map { (w, e) ->
            val home = w.home?.let { adopt(it, teamMap[it.id] ?: crestFor(it, crests)) }
            val away = w.away?.let { adopt(it, teamMap[it.id] ?: crestFor(it, crests)) }
            if (e == null) {
                w.copy(home = home, away = away)
            } else {
                w.copy(
                    id = e.id,
                    home = home,
                    away = away,
                    broadcasts = e.broadcasts,
                    channelHints = e.channelHints,
                    venue = w.venue ?: e.venue,
                    round = w.round ?: e.round,
                    week = e.week,
                    note = e.note,
                )
            }
        }
    }

    /** A table's rows with the crests, names and codes the board's sides use. */
    fun adoptRows(tables: List<LeagueTable>, board: List<Fixture>, crests: List<TeamRef>): List<LeagueTable> {
        val sides = board.flatMap { listOfNotNull(it.home, it.away) }
        return tables.map { table ->
            table.copy(
                rows = table.rows.map { row ->
                    val probe = SportTeam(row.teamName, row.teamName, row.teamAbbrev, null, null, null, false, "")
                    val side = bestBy(sides.filter { it.logoUrl != null }, row.teamName) { it.name }
                        ?: crestFor(probe, crests)
                    if (side == null) row else row.copy(teamName = side.name, teamAbbrev = side.abbreviation, logoUrl = side.logoUrl ?: row.logoUrl)
                },
            )
        }
    }

    /** World Rugby's side with [espn]'s identity and crest, keeping World Rugby's score and result. */
    private fun adopt(wr: SportTeam, espn: SportTeam?): SportTeam =
        if (espn == null) wr else wr.copy(id = espn.id, name = espn.name, abbreviation = espn.abbreviation, logoUrl = espn.logoUrl, colorHex = espn.colorHex)

    private fun crestFor(team: SportTeam, crests: List<TeamRef>): SportTeam? =
        bestBy(crests.filter { it.logoUrl != null }, team.name) { it.name }?.let {
            SportTeam(it.id, it.name, it.abbreviation, it.logoUrl, null, null, false, team.homeAway)
        }

    /** The entry named exactly [name] (once fillers are dropped), else the first that [sameName] accepts. */
    private fun <T> bestBy(items: List<T>, name: String, nameOf: (T) -> String): T? {
        val want = words(name)
        return items.firstOrNull { want.isNotEmpty() && words(nameOf(it)) == want } ?: items.firstOrNull { sameName(name, nameOf(it)) }
    }

    /** true: home is home; false: World Rugby lists the sides the other way round; null: not the same game. */
    private fun orientation(wr: Fixture, espn: Fixture): Boolean? {
        val wh = wr.home ?: return null
        val wa = wr.away ?: return null
        val eh = espn.home ?: return null
        val ea = espn.away ?: return null
        return when {
            sameTeam(wh, eh) || sameTeam(wa, ea) -> true
            sameTeam(wh, ea) || sameTeam(wa, eh) -> false
            else -> null
        }
    }

    /** Within one kick-off, the same code is enough; otherwise the names must agree ([sameName]). */
    fun sameTeam(a: SportTeam, b: SportTeam): Boolean =
        a.abbreviation.equals(b.abbreviation, ignoreCase = true) || sameName(a.name, b.name)

    /**
     * The same team by name: equal once "Women", "Rugby", "RFC" and the like are dropped ("England" and
     * "England Women", aliases such as "USA"), or every word of the shorter name in the longer
     * ("Northampton" in "Northampton Saints").
     */
    fun sameName(a: String, b: String): Boolean {
        val ta = words(a)
        val tb = words(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        if (ta == tb) return true
        val (short, long) = if (ta.size <= tb.size) ta to tb else tb to ta
        return short.any { it.length >= MinWord } && long.containsAll(short)
    }

    private fun words(name: String): Set<String> {
        val key = SportNameMatch.normalize(name).trim()
        val aliased = aliases[key] ?: key
        return aliased.split(' ').filter { it.isNotEmpty() && it !in fillers }.toSet()
    }

    private val seasonPattern = Regex("""\b(19|20)\d{2}(\s*[-/–]\s*\d{2,4})?\b""")
    private val fillers = setOf("women", "womens", "w", "rugby", "rfc", "rc", "club", "union", "fc", "the")
    private val aliases = mapOf(
        "usa" to "united states of america",
        "united states" to "united states of america",
        "us" to "united states of america",
    )

    private const val DayMs = 24L * 60L * 60L * 1000L
    private const val SoonMs = 21L * DayMs
    private const val SameGameMs = 3L * 60L * 60L * 1000L
    private const val MinWord = 4
}
