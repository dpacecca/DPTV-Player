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
import org.json.JSONObject
import tv.own.owntv.R

/**
 * SofaScore fixtures, live scores and standings (v2.5.0) for the few competitions ESPN lacks
 * ([LeagueSource.SOFASCORE]: the sevens series, rugby league beyond the NRL). Keyless JSON, mapped
 * into the very models [EspnApi] produces, so the rail, board, details, matcher and Home row treat a
 * SofaScore league like any other.
 *
 * A league's board is its unique tournament's newest season: the last page of results and the next
 * page of fixtures, with SofaScore's rugby live list laid over it (one shared request) so a game in
 * progress shows its live score. Everything is optional: a 404 is "nothing there", any other failure
 * logs one line and throws, and [SportsViewModel] shows the league empty until the next refresh.
 */
class SofaScoreApi(private val client: OkHttpClient, context: Context) {

    private val appContext = context.applicationContext
    private val venueSeparator: String by lazy { appContext.getString(R.string.sport_venue_separator) }

    /** Season ids per unique tournament, newest first, kept for [SeasonCacheMs]. */
    private val seasonLock = Mutex()
    private val seasonCache = HashMap<String, Pair<Long, List<Long>>>()

    /** The rugby live list, shared by every SofaScore league for [LiveCacheMs]. */
    private val liveLock = Mutex()
    private var liveCache: List<JSONObject> = emptyList()
    private var liveCachedAt = 0L

    suspend fun scoreboard(league: SportLeague): EspnApi.Scoreboard = withContext(Dispatchers.IO) {
        logged(league) {
            val seasons = seasons(league.espnLeague)
            var events = seasons.firstOrNull()?.let { seasonEvents(league.espnLeague, it) }.orEmpty()
            // A season listed before its first fixture: show the previous one's results meanwhile.
            if (events.isEmpty() && seasons.size > 1) events = seasonEvents(league.espnLeague, seasons[1])
            val byId = LinkedHashMap<String, JSONObject>()
            events.forEach { byId[it.optString(IdKey)] = it }
            // A game in progress: the live list's copy carries the current score and period.
            liveEvents().filter { tournamentIdOf(it) == league.espnLeague }.forEach { byId[it.optString(IdKey)] = it }
            val zone = ZoneId.systemDefault()
            val fixtures = byId.values.mapNotNull { runCatching { fixtureFrom(league, it, venueSeparator, zone) }.getOrNull() }
            EspnApi.Scoreboard(appContext.getString(league.labelRes), fixtures)
        }
    }

    suspend fun standings(league: SportLeague): List<LeagueTable> = withContext(Dispatchers.IO) {
        logged(league) {
            val season = seasons(league.espnLeague).firstOrNull() ?: return@logged emptyList()
            val root = getOrNull(Base + TournamentPath + league.espnLeague + SeasonPath + season + StandingsPath)
                ?: return@logged emptyList()
            val columns = listOf(
                appContext.getString(R.string.sport_table_played),
                appContext.getString(R.string.sport_table_difference),
                appContext.getString(R.string.sport_table_points),
            )
            tablesFrom(league, root, columns)
        }
    }

    /** The league's teams (phone team picker), from the teams on its current board. */
    suspend fun teams(league: SportLeague): List<TeamRef> =
        scoreboard(league).fixtures.flatMap { listOfNotNull(it.home, it.away) }
            .distinctBy { it.id }
            .map { TeamRef(it.id, it.abbreviation, it.name, it.logoUrl) }
            .sortedBy { it.name.lowercase() }

    /** Run [block]; on any failure log one line (no stack trace) and rethrow for the caller. */
    private inline fun <T> logged(league: SportLeague, block: () -> T): T =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(LogTag, "sofascore failed " + league.id + ": " + e)
            throw e
        }

    private suspend fun seasons(tournamentId: String): List<Long> = seasonLock.withLock {
        val now = System.currentTimeMillis()
        seasonCache[tournamentId]?.takeIf { now - it.first < SeasonCacheMs }?.let { return@withLock it.second }
        val list = getOrNull(Base + TournamentPath + tournamentId + SeasonsPath)
            ?.optJSONArray(SeasonsKey)
        val ids = ArrayList<Long>()
        if (list != null) {
            for (i in 0 until list.length()) list.optJSONObject(i)?.optLong(IdKey)?.takeIf { it > 0 }?.let(ids::add)
        }
        seasonCache[tournamentId] = now to ids
        ids
    }

    /** The season's latest results and next fixtures (first page of each; a 404 is an empty page). */
    private fun seasonEvents(tournamentId: String, season: Long): List<JSONObject> {
        val base = Base + TournamentPath + tournamentId + SeasonPath + season
        return listOf(LastPath, NextPath).flatMap { path ->
            val events = getOrNull(base + path)?.optJSONArray(EventsKey) ?: return@flatMap emptyList()
            (0 until events.length()).mapNotNull { events.optJSONObject(it) }
        }
    }

    /** Every rugby game in progress; best-effort, a failure just means no live overlay. */
    private suspend fun liveEvents(): List<JSONObject> = liveLock.withLock {
        val now = System.currentTimeMillis()
        if (now - liveCachedAt < LiveCacheMs) return@withLock liveCache
        val events = runCatching { getOrNull(Base + LivePath)?.optJSONArray(EventsKey) }.getOrNull()
        liveCache = if (events == null) emptyList() else (0 until events.length()).mapNotNull { events.optJSONObject(it) }
        liveCachedAt = now
        liveCache
    }

    /** GET [url] as JSON; null for a 404 (SofaScore's "no events"), an exception for anything else. */
    private fun getOrNull(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header(UserAgentHeader, UserAgentValue)
            .header(AcceptHeader, JsonMime)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == NotFound) return null
            if (!response.isSuccessful) throw java.io.IOException("SofaScore HTTP " + response.code)
            return JSONObject(response.body.string())
        }
    }

    companion object {
        private const val LogTag = "SportsHub"

        /**
         * One SofaScore event as a [Fixture] (checked against live answers, 2026-10-19):
         *  - `startTimestamp` is in seconds; no timestamp, no fixture;
         *  - `status.type` "inprogress"/"interrupted" is live, "finished" and the called-off types
         *    ("postponed", "canceled", "abandoned") are past, the rest upcoming; the status line is
         *    SofaScore's own `status.description` ("1st half", "Ended"), or the local kick-off time;
         *  - scores are `homeScore.display` (else `current`), only once the game started;
         *  - the round is `roundInfo.name` ("Final"), else the stage after the comma of
         *    `tournament.name` ("World Cup, Group A" → "Group A"); a plain round number gives none,
         *    like a plain ESPN matchday;
         *  - the venue is `venue.name` and `venue.city.name`, joined by [separator].
         */
        internal fun fixtureFrom(league: SportLeague, event: JSONObject, separator: String, zone: ZoneId): Fixture? {
            val seconds = event.optLong(StartKey, 0L)
            val home = event.optJSONObject(HomeTeamKey)
            val away = event.optJSONObject(AwayTeamKey)
            if (seconds <= 0L || home == null || away == null) return null
            val dateMs = seconds * 1_000L
            val status = event.optJSONObject(StatusKey)
            val state = stateOf(status?.optString(TypeKey).orEmpty())
            val description = status?.optStringOrNull(DescriptionKey)
            val statusLine = if (state == FixtureState.UPCOMING || description == null) {
                DateTimeFormatter.ofPattern(TimePattern).withZone(zone).format(Instant.ofEpochMilli(dateMs))
            } else {
                description
            }
            val winnerCode = event.optInt(WinnerCodeKey, 0)
            val started = state != FixtureState.UPCOMING
            val homeTeam = teamOf(home, event.optJSONObject(HomeScoreKey), started, winnerCode == 1, HomeValue)
            val awayTeam = teamOf(away, event.optJSONObject(AwayScoreKey), started, winnerCode == 2, AwayValue)
            if (homeTeam == null || awayTeam == null) return null
            return Fixture(
                id = league.id + Slash + event.optString(IdKey),
                leagueId = league.id,
                dateMs = dateMs,
                state = state,
                statusLine = statusLine,
                format = FixtureFormat.VERSUS,
                home = homeTeam,
                away = awayTeam,
                broadcasts = emptyList(),
                venue = venueOf(event.optJSONObject(VenueKey), separator),
                round = roundOf(event),
            )
        }

        internal fun stateOf(type: String): FixtureState = when (type) {
            InProgressType, InterruptedType -> FixtureState.LIVE
            FinishedType, PostponedType, CanceledType, AbandonedType -> FixtureState.FINISHED
            else -> FixtureState.UPCOMING
        }

        internal fun roundOf(event: JSONObject): String? =
            event.optJSONObject(RoundInfoKey)?.optStringOrNull(NameKey)
                ?: event.optJSONObject(TournamentKey)?.optStringOrNull(NameKey)
                    ?.takeIf { CommaSpace in it }?.substringAfter(CommaSpace)?.trim()?.ifEmpty { null }

        private fun venueOf(venue: JSONObject?, separator: String): String? {
            val name = venue?.optStringOrNull(NameKey)?.trim() ?: return null
            val city = venue.optJSONObject(CityKey)?.optStringOrNull(NameKey)?.trim()
            return if (city != null && city != name) name + separator + city else name
        }

        private fun teamOf(team: JSONObject, score: JSONObject?, started: Boolean, winner: Boolean, homeAway: String): SportTeam? {
            val name = team.optStringOrNull(NameKey) ?: team.optStringOrNull(ShortNameKey) ?: return null
            val id = team.optLong(IdKey, 0L)
            val points = if (started && score != null) {
                listOf(DisplayKey, CurrentKey).firstNotNullOfOrNull { key -> score.opt(key)?.takeIf { it is Number }?.toString() }
            } else {
                null
            }
            return SportTeam(
                id = if (id > 0) id.toString() else name,
                name = name,
                abbreviation = team.optStringOrNull(NameCodeKey) ?: team.optStringOrNull(ShortNameKey) ?: name,
                logoUrl = if (id > 0) teamLogoUrl(id) else null,
                // SofaScore's own blue stands in for a team without colours: no colour then.
                colorHex = team.optJSONObject(TeamColorsKey)?.optStringOrNull(PrimaryKey)?.removePrefix(Hash)
                    ?.takeUnless { it.equals(PlaceholderColor, ignoreCase = true) },
                score = points,
                winner = winner,
                homeAway = homeAway,
            )
        }

        /** The standings tables (one per group), stats as played / points difference / points. */
        internal fun tablesFrom(league: SportLeague, root: JSONObject, columns: List<String>): List<LeagueTable> {
            val groups = root.optJSONArray(StandingsKey) ?: return emptyList()
            val tables = ArrayList<LeagueTable>()
            for (g in 0 until groups.length()) {
                val group = groups.optJSONObject(g) ?: continue
                val rows = group.optJSONArray(RowsKey) ?: continue
                val out = ArrayList<StandingRow>()
                for (r in 0 until rows.length()) {
                    val row = rows.optJSONObject(r) ?: continue
                    val team = row.optJSONObject(TeamKey) ?: continue
                    val name = team.optStringOrNull(NameKey) ?: continue
                    val id = team.optLong(IdKey, 0L)
                    out.add(
                        StandingRow(
                            rank = row.optInt(PositionKey, out.size + 1).toString(),
                            teamName = name,
                            teamAbbrev = team.optStringOrNull(NameCodeKey) ?: team.optStringOrNull(ShortNameKey).orEmpty(),
                            logoUrl = if (id > 0) teamLogoUrl(id) else null,
                            stats = listOf(
                                row.optInt(MatchesKey, 0).toString(),
                                row.optStringOrNull(ScoreDiffKey)
                                    ?: (row.optInt(ScoresForKey, 0) - row.optInt(ScoresAgainstKey, 0)).toString(),
                                row.optInt(PointsKey, 0).toString(),
                            ),
                        ),
                    )
                }
                if (out.isNotEmpty()) tables.add(LeagueTable(league.id, group.optStringOrNull(NameKey), columns, out))
            }
            return tables
        }

        /** The unique tournament an event belongs to, as the catalogue stores it ([SportLeague.espnLeague]). */
        internal fun tournamentIdOf(event: JSONObject): String? =
            event.optJSONObject(TournamentKey)?.optJSONObject(UniqueTournamentKey)?.optLong(IdKey, 0L)
                ?.takeIf { it > 0 }?.toString()

        internal fun teamLogoUrl(teamId: Long): String = LogoBase + teamId + ImagePath

        /** `optString`, but null for a missing value or JSON null (org.json returns "null" for the latter). */
        private fun JSONObject.optStringOrNull(key: String): String? =
            optString(key).takeIf { it.isNotBlank() && it != NullText }

        private const val Base = "https://api.sofascore.com/api/v1"
        private const val LogoBase = "https://api.sofascore.app/api/v1/team/"
        private const val ImagePath = "/image"
        private const val TournamentPath = "/unique-tournament/"
        private const val SeasonsPath = "/seasons"
        private const val SeasonPath = "/season/"
        private const val LastPath = "/events/last/0"
        private const val NextPath = "/events/next/0"
        private const val StandingsPath = "/standings/total"
        private const val LivePath = "/sport/rugby/events/live"
        private const val SeasonCacheMs = 6L * 60L * 60L * 1000L
        private const val LiveCacheMs = 20_000L
        private const val NotFound = 404
        private const val UserAgentHeader = "User-Agent"
        private const val UserAgentValue = "Mozilla/5.0 (Linux; Android 11; AFTTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        private const val AcceptHeader = "Accept"
        private const val JsonMime = "application/json"
        private const val TimePattern = "HH:mm"
        private const val Slash = "/"
        private const val Hash = "#"
        private const val PlaceholderColor = "374df5"
        private const val CommaSpace = ", "
        private const val NullText = "null"
        private const val HomeValue = "home"
        private const val AwayValue = "away"

        private const val InProgressType = "inprogress"
        private const val InterruptedType = "interrupted"
        private const val FinishedType = "finished"
        private const val PostponedType = "postponed"
        private const val CanceledType = "canceled"
        private const val AbandonedType = "abandoned"

        private const val SeasonsKey = "seasons"
        private const val EventsKey = "events"
        private const val IdKey = "id"
        private const val NameKey = "name"
        private const val ShortNameKey = "shortName"
        private const val NameCodeKey = "nameCode"
        private const val StartKey = "startTimestamp"
        private const val StatusKey = "status"
        private const val TypeKey = "type"
        private const val DescriptionKey = "description"
        private const val HomeTeamKey = "homeTeam"
        private const val AwayTeamKey = "awayTeam"
        private const val HomeScoreKey = "homeScore"
        private const val AwayScoreKey = "awayScore"
        private const val DisplayKey = "display"
        private const val CurrentKey = "current"
        private const val WinnerCodeKey = "winnerCode"
        private const val TeamColorsKey = "teamColors"
        private const val PrimaryKey = "primary"
        private const val VenueKey = "venue"
        private const val CityKey = "city"
        private const val RoundInfoKey = "roundInfo"
        private const val TournamentKey = "tournament"
        private const val UniqueTournamentKey = "uniqueTournament"
        private const val StandingsKey = "standings"
        private const val RowsKey = "rows"
        private const val TeamKey = "team"
        private const val PositionKey = "position"
        private const val MatchesKey = "matches"
        private const val ScoreDiffKey = "scoreDiffFormatted"
        private const val ScoresForKey = "scoresFor"
        private const val ScoresAgainstKey = "scoresAgainst"
        private const val PointsKey = "points"
    }
}
