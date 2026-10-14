package tv.own.owntv.features.sport

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cricket from ESPN's personalized scoreboard header — the only ESPN feed that lists cricket
 * matches: the site scoreboard the other sports use answers 404 for every cricket league. One
 * request returns every active series (a tour, a league season, a world cup) with its live and
 * upcoming matches, under `sports[0].leagues[].events[]`.
 *
 * Series ids change with every edition, so a catalogue entry picks its series by name: a series is
 * kept when its name contains the entry's label ("Indian Premier League" keeps "Indian Premier
 * League 2027"). The entry whose ESPN league is [AllSeries] keeps every series.
 *
 * Pure (no Android), so the parse is unit-tested; defensive like the rest of [EspnApi] — an event
 * whose shape is off is skipped, never fatal.
 */
object CricketHeader {
    /** `SportLeague.espnLeague` of the catalogue entry that shows every active series. */
    const val AllSeries = "all"

    /** Followed by the device's time zone id, URL-encoded. Region "in" lists the most series. */
    const val Url = "https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket&lang=en&region=in&tz="

    /** Whether a series named [seriesName] belongs to an entry filtering on [filter] (null = all). */
    fun keeps(seriesName: String, filter: String?): Boolean =
        filter == null || seriesName.contains(filter.trim(), ignoreCase = true)

    /** The fixtures [league] shows from a header response, kept by [filter] (see [keeps]). */
    fun fixtures(
        root: JSONObject,
        league: SportLeague,
        filter: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Fixture> {
        val out = ArrayList<Fixture>()
        val seen = HashSet<String>()
        val sports = root.optJSONArray(SportsKey) ?: return out
        for (s in 0 until sports.length()) {
            val seriesList = sports.optJSONObject(s)?.optJSONArray(LeaguesKey) ?: continue
            for (l in 0 until seriesList.length()) {
                val series = seriesList.optJSONObject(l) ?: continue
                val seriesName = series.optStringOrNull(NameKey)
                    ?: series.optStringOrNull(ShortNameKey)
                    ?: continue
                if (!keeps(seriesName, filter)) continue
                val events = series.optJSONArray(EventsKey) ?: continue
                for (e in 0 until events.length()) {
                    val event = events.optJSONObject(e) ?: continue
                    val fixture = runCatching { fixtureFrom(league, seriesName, event, zone) }.getOrNull() ?: continue
                    if (seen.add(fixture.id)) out.add(fixture)
                }
            }
        }
        return out.sortedBy { it.dateMs }
    }

    private fun fixtureFrom(league: SportLeague, seriesName: String, event: JSONObject, zone: ZoneId): Fixture? {
        val eventId = event.optStringOrNull(IdKey) ?: return null
        val dateMs = event.optStringOrNull(DateKey)
            ?.let { raw -> runCatching { Instant.parse(EspnApi.normalizeEspnDate(raw)).toEpochMilli() }.getOrNull() }
            ?: return null
        val teams = teamsOf(event.optJSONArray(CompetitorsKey))
        if (teams.size < 2) return null
        val home = teams.firstOrNull { it.homeAway == HomeValue } ?: teams[0]
        val away = teams.firstOrNull { it !== home && it.homeAway == AwayValue } ?: teams.first { it !== home }

        // The state lives in fullStatus.type.state; older answers carry it as a bare "status" word.
        val type = event.optJSONObject(FullStatusKey)?.optJSONObject(TypeKey)
        val stateWord = type?.optStringOrNull(StateKey)
            ?: (event.opt(StatusKey) as? String)
            ?: event.optJSONObject(StatusKey)?.optJSONObject(TypeKey)?.optStringOrNull(StateKey)
        val state = when (stateWord) {
            InState -> FixtureState.LIVE
            PostState -> FixtureState.FINISHED
            else -> FixtureState.UPCOMING
        }
        // A match in play or over says where it stands ("India need 45 runs from 30 balls").
        val detail = event.optStringOrNull(SummaryKey) ?: type?.optStringOrNull(ShortDetailKey)
        val statusLine = if (state == FixtureState.UPCOMING || detail == null) {
            DateTimeFormatter.ofPattern(TimePattern).withZone(zone).format(Instant.ofEpochMilli(dateMs))
        } else {
            detail
        }
        return Fixture(
            id = league.id + Sep + eventId,
            leagueId = league.id,
            dateMs = dateMs,
            state = state,
            statusLine = statusLine,
            format = FixtureFormat.VERSUS,
            home = home,
            away = away,
            broadcasts = broadcastsOf(event.optJSONArray(BroadcastsKey)),
            // The series is what a guide most often names ("IPL 2027"), when it names no teams.
            tournament = seriesName,
            venue = event.optStringOrNull(LocationKey),
        )
    }

    /** Header competitors are flat (name, logo, score on the competitor); a nested `team` also works. */
    private fun teamsOf(competitors: JSONArray?): List<SportTeam> {
        if (competitors == null) return emptyList()
        val out = ArrayList<SportTeam>(competitors.length())
        for (i in 0 until competitors.length()) {
            val c = competitors.optJSONObject(i) ?: continue
            val team = c.optJSONObject(TeamKey) ?: c
            val name = team.optStringOrNull(DisplayNameKey)
                ?: team.optStringOrNull(ShortDisplayNameKey)
                ?: team.optStringOrNull(NameKey)
                ?: continue
            out.add(
                SportTeam(
                    id = team.optStringOrNull(IdKey) ?: c.optString(IdKey),
                    name = name,
                    abbreviation = team.optStringOrNull(AbbrevKey) ?: name,
                    logoUrl = team.optStringOrNull(LogoKey)
                        ?: team.optJSONArray(LogosKey)?.optJSONObject(0)?.optStringOrNull(HrefKey),
                    colorHex = team.optStringOrNull(ColorKey),
                    score = c.optStringOrNull(ScoreKey),
                    winner = c.optBoolean(WinnerKey),
                    homeAway = c.optString(HomeAwayKey),
                ),
            )
        }
        return out
    }

    /** Broadcast names, as plain strings or objects with a name / shortName. */
    private fun broadcastsOf(list: JSONArray?): List<String> {
        if (list == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until list.length()) {
            val item = list.opt(i)
            val name = when (item) {
                is String -> item.takeIf { it.isNotBlank() }
                is JSONObject -> item.optStringOrNull(NameKey) ?: item.optStringOrNull(ShortNameKey)
                else -> null
            }
            name?.let(out::add)
        }
        return out.distinct()
    }

    /** `optString`, but null for a missing value or JSON null (org.json returns "null" for the latter). */
    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private const val Sep = "/"
    private const val TimePattern = "HH:mm"
    private const val SportsKey = "sports"
    private const val LeaguesKey = "leagues"
    private const val EventsKey = "events"
    private const val CompetitorsKey = "competitors"
    private const val BroadcastsKey = "broadcasts"
    private const val TeamKey = "team"
    private const val IdKey = "id"
    private const val DateKey = "date"
    private const val NameKey = "name"
    private const val ShortNameKey = "shortName"
    private const val DisplayNameKey = "displayName"
    private const val ShortDisplayNameKey = "shortDisplayName"
    private const val AbbrevKey = "abbreviation"
    private const val LogoKey = "logo"
    private const val LogosKey = "logos"
    private const val HrefKey = "href"
    private const val ColorKey = "color"
    private const val ScoreKey = "score"
    private const val WinnerKey = "winner"
    private const val HomeAwayKey = "homeAway"
    private const val HomeValue = "home"
    private const val AwayValue = "away"
    private const val FullStatusKey = "fullStatus"
    private const val StatusKey = "status"
    private const val TypeKey = "type"
    private const val StateKey = "state"
    private const val ShortDetailKey = "shortDetail"
    private const val SummaryKey = "summary"
    private const val LocationKey = "location"
    private const val InState = "in"
    private const val PostState = "post"
}
