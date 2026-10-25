package tv.own.owntv.features.sport

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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
 * TheSportsDB fixtures and results (v2.6.0), the fallback for the rugby league competitions SofaScore
 * served until it began answering every unofficial client with HTTP 403 ([SportLeague.theSportsDbId]).
 * The free key carries no live scores and caps each answer (15 events a season, 3 a day, 1 for
 * next/past), so a board is the season's first page, the last and next game, and the games of
 * yesterday, today and tomorrow, merged by event id with the fresher answers on top. Results arrive
 * after the final whistle; a game past kick-off without one shows as live for [LikelyLengthMs].
 *
 * Mapped into the very models [EspnApi] produces, like [SofaScoreApi], so the rest of Sport stays
 * source-blind. Standings are not on the free key, so a fallback league has no table.
 */
class TheSportsDbApi(private val client: OkHttpClient, context: Context) {

    private val appContext = context.applicationContext
    private val venueSeparator: String by lazy { appContext.getString(R.string.sport_venue_separator) }

    /** Answers per URL with the time fetched, so one refresh does not spend the free key's rate limit. */
    private val lock = Mutex()
    private val cache = HashMap<String, Pair<Long, List<JSONObject>>>()

    suspend fun scoreboard(league: SportLeague): EspnApi.Scoreboard = withContext(Dispatchers.IO) {
        val id = league.theSportsDbId ?: throw IllegalArgumentException("no TheSportsDB id: " + league.id)
        val today = LocalDate.now(ZoneOffset.UTC)
        val failures = ArrayList<String>()
        val next = cached(Base + NextPath + id, ShortCacheMs, failures)
        val past = cached(Base + PastPath + id, ShortCacheMs, failures)
        val season = (next + past).firstNotNullOfOrNull { it.optStringOrNull(SeasonKey) } ?: today.year.toString()
        val seasonEvents = cached(Base + SeasonPath + id + SeasonParam + season, SeasonCacheMs, failures)
        val days = listOf(today.minusDays(1), today, today.plusDays(1)).flatMap { day ->
            cached(Base + DayPath + day + LeagueParam + id, if (day == today) ShortCacheMs else SeasonCacheMs, failures)
        }
        val all = seasonEvents + past + next + days
        // Every request failed with nothing cached: a failure for the caller, not an empty season.
        if (all.isEmpty() && failures.size == RequestsPerBoard) throw java.io.IOException(failures.first())
        val byId = LinkedHashMap<String, JSONObject>()
        all.forEach { byId[it.optString(IdKey)] = it }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val fixtures = byId.values.mapNotNull { runCatching { fixtureFrom(league, it, venueSeparator, zone, now) }.getOrNull() }
        EspnApi.Scoreboard(appContext.getString(league.labelRes), fixtures)
    }

    /** The league's teams (team pick), from the teams on its current board. */
    suspend fun teams(league: SportLeague): List<TeamRef> =
        scoreboard(league).fixtures.flatMap { listOfNotNull(it.home, it.away) }
            .distinctBy { it.id }
            .map { TeamRef(it.id, it.abbreviation, it.name, it.logoUrl) }
            .sortedBy { it.name.lowercase() }

    /**
     * The events at [url], from the cache while younger than [ttlMs]. A failure keeps the old answer
     * and adds its reason to [failures].
     */
    private suspend fun cached(url: String, ttlMs: Long, failures: MutableList<String>): List<JSONObject> = lock.withLock {
        val now = System.currentTimeMillis()
        val hit = cache[url]
        if (hit != null && now - hit.first < ttlMs) return@withLock hit.second
        try {
            val events = fetch(url)
            cache[url] = now to events
            events
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.add(e.toString())
            hit?.second.orEmpty()
        }
    }

    private fun fetch(url: String): List<JSONObject> {
        val request = Request.Builder().url(url).header(AcceptHeader, JsonMime).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("TheSportsDB HTTP " + response.code)
            return eventsOf(JSONObject(response.body.string()))
        }
    }

    companion object {
        /**
         * One TheSportsDB event as a [Fixture] (checked against live answers, 2026-10-25):
         *  - kick-off is `strTimestamp` (UTC, with or without an offset), else `dateEvent` + `strTime`;
         *  - `strStatus` "NS" is upcoming, "FT"/"Match Finished"/"AET" finished, the called-off ones
         *    finished with their own word; a game past kick-off with no result yet is live for
         *    [LikelyLengthMs], then finished — the free key never sends a live score;
         *  - scores are `intHomeScore`/`intAwayScore` once the game started; the higher one wins;
         *  - team names lose the " Rugby League" TheSportsDB puts on national sides ([cleanTeamName]);
         *  - the venue is `strVenue` and `strCity`; the round `intRound`, the group `strGroup`.
         */
        internal fun fixtureFrom(league: SportLeague, event: JSONObject, separator: String, zone: ZoneId, nowMs: Long): Fixture? {
            val dateMs = kickOffMs(event) ?: return null
            val homeName = event.optStringOrNull(HomeTeamKey) ?: return null
            val awayName = event.optStringOrNull(AwayTeamKey) ?: return null
            val homeScore = event.optStringOrNull(HomeScoreKey)?.toIntOrNull()
            val awayScore = event.optStringOrNull(AwayScoreKey)?.toIntOrNull()
            val status = event.optStringOrNull(StatusKey).orEmpty()
            val calledOff = event.optString(PostponedKey).equals(Yes, ignoreCase = true)
            val state = stateOf(status, calledOff, homeScore != null && awayScore != null, dateMs, nowMs)
            val kickOff = DateTimeFormatter.ofPattern(TimePattern).withZone(zone).format(Instant.ofEpochMilli(dateMs))
            val statusLine = when {
                state != FixtureState.FINISHED -> kickOff
                calledOff -> PostponedLine
                status in FinishedStatuses -> FullTime
                status.isNotEmpty() && status !in NotStartedStatuses -> status
                else -> kickOff
            }
            val started = state != FixtureState.UPCOMING
            val home = teamOf(event.optStringOrNull(HomeIdKey), homeName, event.optStringOrNull(HomeBadgeKey),
                if (started) homeScore else null, homeScore != null && awayScore != null && homeScore > awayScore, HomeValue)
            val away = teamOf(event.optStringOrNull(AwayIdKey), awayName, event.optStringOrNull(AwayBadgeKey),
                if (started) awayScore else null, homeScore != null && awayScore != null && awayScore > homeScore, AwayValue)
            return Fixture(
                id = league.id + Slash + event.optString(IdKey),
                leagueId = league.id,
                dateMs = dateMs,
                state = state,
                statusLine = statusLine,
                format = FixtureFormat.VERSUS,
                home = home,
                away = away,
                broadcasts = emptyList(),
                venue = venueOf(event, separator),
                roundNumber = event.optStringOrNull(RoundKey)?.toIntOrNull()?.takeIf { it in 1 until PlayoffRounds },
                group = event.optStringOrNull(GroupKey)?.trim()?.ifEmpty { null },
            )
        }

        /**
         * The state of a game: called off or with a result → finished; a live status → live; past
         * kick-off with no result → live for [LikelyLengthMs], then finished; otherwise upcoming.
         */
        internal fun stateOf(status: String, calledOff: Boolean, hasResult: Boolean, kickOffMs: Long, nowMs: Long): FixtureState = when {
            calledOff || status in CalledOffStatuses -> FixtureState.FINISHED
            status in FinishedStatuses -> FixtureState.FINISHED
            status in LiveStatuses -> FixtureState.LIVE
            hasResult && status !in NotStartedStatuses -> FixtureState.FINISHED
            nowMs < kickOffMs -> FixtureState.UPCOMING
            nowMs < kickOffMs + LikelyLengthMs -> FixtureState.LIVE
            else -> FixtureState.FINISHED
        }

        /** Kick-off in epoch ms: `strTimestamp` (UTC unless it says otherwise), else date + time in UTC. */
        internal fun kickOffMs(event: JSONObject): Long? {
            event.optStringOrNull(TimestampKey)?.let { stamp ->
                runCatching { return OffsetDateTime.parse(stamp).toInstant().toEpochMilli() }
                runCatching { return LocalDateTime.parse(stamp).toInstant(ZoneOffset.UTC).toEpochMilli() }
            }
            val date = event.optStringOrNull(DateKey)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val time = event.optStringOrNull(TimeKey)?.substringBefore(Plus)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                ?: LocalTime.MIDNIGHT
            return date.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli()
        }

        /** "Australia Rugby League" → "Australia"; club names ("Wigan Warriors") stay as they are. */
        internal fun cleanTeamName(name: String): String =
            name.trim().removeSuffix(RugbyLeagueSuffix).removeSuffix(SevensSuffix).trim().ifEmpty { name.trim() }

        /**
         * A short code for a team TheSportsDB gives none for: the nation's code when known, the
         * initials of a three-word name ("Hull Kingston Rovers" → "HKR"), else the first letters
         * ("Warrington Wolves" → "WAR", "St Helens" → "STH"). Also the team's follow key, so it
         * must not change between refreshes — it depends on the name alone.
         */
        internal fun abbreviationOf(name: String): String {
            NationCodes[name]?.let { return it }
            val words = name.split(' ').filter { it.isNotBlank() }
            if (words.size >= 3) return words.take(3).joinToString("") { it.take(1) }.uppercase()
            val first = words.firstOrNull().orEmpty()
            return if (first.length <= 2 && words.size > 1) {
                (first + words[1]).take(3).uppercase()
            } else {
                first.take(3).uppercase()
            }
        }

        private fun teamOf(id: String?, rawName: String, badge: String?, score: Int?, winner: Boolean, homeAway: String): SportTeam {
            val name = cleanTeamName(rawName)
            return SportTeam(
                id = id ?: name,
                name = name,
                abbreviation = abbreviationOf(name),
                logoUrl = badge,
                colorHex = null,
                score = score?.toString(),
                winner = winner,
                homeAway = homeAway,
            )
        }

        private fun venueOf(event: JSONObject, separator: String): String? {
            val name = event.optStringOrNull(VenueKey)?.trim() ?: return null
            val city = event.optStringOrNull(CityKey)?.trim()
            return if (city != null && city != name) name + separator + city else name
        }

        /** The events of one answer ("events", or "results" for a past list); none for a JSON null. */
        internal fun eventsOf(root: JSONObject): List<JSONObject> {
            val list = root.optJSONArray(EventsKey) ?: root.optJSONArray(ResultsKey) ?: return emptyList()
            return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        }

        /** `optString`, but null for a missing value, JSON null or blank. */
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != NullText }

        /** A game with no result this long after kick-off is taken as over. */
        internal const val LikelyLengthMs = 2L * 60L * 60L * 1000L

        private const val Base = "https://www.thesportsdb.com/api/v1/json/123/"
        private const val NextPath = "eventsnextleague.php?id="
        private const val PastPath = "eventspastleague.php?id="
        private const val SeasonPath = "eventsseason.php?id="
        private const val SeasonParam = "&s="
        private const val DayPath = "eventsday.php?d="
        private const val LeagueParam = "&l="
        private const val RequestsPerBoard = 6
        private const val ShortCacheMs = 10L * 60L * 1000L
        private const val SeasonCacheMs = 60L * 60L * 1000L
        private const val AcceptHeader = "Accept"
        private const val JsonMime = "application/json"
        private const val TimePattern = "HH:mm"
        private const val Slash = "/"
        private const val Plus = "+"
        private const val Yes = "yes"
        private const val NullText = "null"
        private const val HomeValue = "home"
        private const val AwayValue = "away"
        private const val FullTime = "FT"
        private const val PostponedLine = "PST"
        private const val RugbyLeagueSuffix = " Rugby League"
        private const val SevensSuffix = " 7s Rugby"

        /** Round numbers from here on are TheSportsDB's codes for finals (125 semi-final, 200 final, …). */
        private const val PlayoffRounds = 100

        private val NotStartedStatuses = setOf("NS", "Not Started", "TBD", "")
        private val FinishedStatuses = setOf("FT", "AET", "AOT", "PEN", "Match Finished", "Finished", "FT_PEN", "AP")
        private val CalledOffStatuses = setOf("PST", "Postponed", "CANC", "Cancelled", "ABD", "Abandoned", "AWD", "WO")
        private val LiveStatuses = setOf("1H", "2H", "HT", "ET", "P", "BT", "LIVE", "In Progress", "Live")

        /** Codes of the rugby league nations, for the short names TheSportsDB leaves out. */
        private val NationCodes = mapOf(
            "Australia" to "AUS", "New Zealand" to "NZL", "England" to "ENG", "France" to "FRA",
            "Fiji" to "FIJ", "Samoa" to "SAM", "Tonga" to "TGA", "Papua New Guinea" to "PNG",
            "Cook Islands" to "COK", "Lebanon" to "LBN", "Wales" to "WAL", "Scotland" to "SCO",
            "Ireland" to "IRL", "Italy" to "ITA", "Jamaica" to "JAM", "Greece" to "GRE",
            "Queensland Maroons" to "QLD", "New South Wales Blues" to "NSW",
        )

        private const val EventsKey = "events"
        private const val ResultsKey = "results"
        private const val IdKey = "idEvent"
        private const val SeasonKey = "strSeason"
        private const val TimestampKey = "strTimestamp"
        private const val DateKey = "dateEvent"
        private const val TimeKey = "strTime"
        private const val StatusKey = "strStatus"
        private const val PostponedKey = "strPostponed"
        private const val HomeTeamKey = "strHomeTeam"
        private const val AwayTeamKey = "strAwayTeam"
        private const val HomeIdKey = "idHomeTeam"
        private const val AwayIdKey = "idAwayTeam"
        private const val HomeScoreKey = "intHomeScore"
        private const val AwayScoreKey = "intAwayScore"
        private const val HomeBadgeKey = "strHomeTeamBadge"
        private const val AwayBadgeKey = "strAwayTeamBadge"
        private const val VenueKey = "strVenue"
        private const val CityKey = "strCity"
        private const val RoundKey = "intRound"
        private const val GroupKey = "strGroup"
    }
}
