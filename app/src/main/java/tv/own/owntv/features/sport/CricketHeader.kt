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
 * An entry picks its matches one of three ways ([Selection]):
 *  - by series name — series ids change with every edition, so a series is kept when its name
 *    contains the entry's label ("Indian Premier League" keeps "Indian Premier League 2027");
 *  - by format — the international Tests, ODIs or T20Is of every tour, from each match's
 *    `class.internationalClassId`;
 *  - everything ([AllSeries]).
 *
 * Women's cricket is left out everywhere (owner's choice): women's series and matches never show,
 * so "Big Bash League" does not also bring in the Women's Big Bash League.
 *
 * Pure (no Android), so the parse is unit-tested; defensive like the rest of [EspnApi] — an event
 * whose shape is off is skipped, never fatal.
 */
object CricketHeader {
    /** `SportLeague.espnLeague` of the catalogue entry that shows every active series. */
    const val AllSeries = "all"

    /** `SportLeague.espnLeague` of the format entries: every international Test / ODI / T20I. */
    const val Tests = "tests"
    const val Odis = "odis"
    const val T20is = "t20is"

    /**
     * Region "in" lists the most series. The time zone is fixed on purpose: ESPN answers 502 to the
     * first request for a zone it has not cached, and the app formats every time itself anyway.
     */
    const val Url = "https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket&lang=en&region=in&tz=Asia%2FCalcutta"

    /** Which matches a cricket entry shows. */
    sealed interface Selection {
        /** Every active series. */
        data object All : Selection
        /** The series whose name contains [name] (the entry's label). */
        data class Series(val name: String) : Selection
        /** Matches whose `class.internationalClassId` is one of [classIds], in any series. */
        data class Format(val classIds: Set<Int>) : Selection
    }

    /** The selection of the entry whose ESPN league is [espnLeague] and whose label is [label]. */
    fun selectionFor(espnLeague: String, label: String): Selection = when (espnLeague) {
        AllSeries -> Selection.All
        // ESPN cricinfo's international classes: 1/2/3 Test/ODI/T20I (8/9/10, the women's, are
        // left out with the rest of women's cricket).
        Tests -> Selection.Format(setOf(1))
        Odis -> Selection.Format(setOf(2))
        T20is -> Selection.Format(setOf(3))
        else -> Selection.Series(label)
    }

    /** Whether a series named [seriesName] can hold matches for [selection]. */
    fun keeps(seriesName: String, selection: Selection): Boolean = when (selection) {
        is Selection.Series -> seriesName.contains(selection.name.trim(), ignoreCase = true)
        else -> true
    }

    /** The fixtures [league] shows from a header response, as chosen by [selection]. */
    fun fixtures(
        root: JSONObject,
        league: SportLeague,
        selection: Selection,
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
                if (!keeps(seriesName, selection) || isWomens(seriesName)) continue
                val events = series.optJSONArray(EventsKey) ?: continue
                for (e in 0 until events.length()) {
                    val event = events.optJSONObject(e) ?: continue
                    val matchClass = event.optJSONObject(ClassKey)
                    if (matchClass != null && isWomens(matchClass)) continue
                    if (selection is Selection.Format) {
                        val classId = matchClass?.optString(IntlClassKey)?.toIntOrNull()
                        if (classId == null || classId !in selection.classIds) continue
                    }
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
        // Some women's matches say so only in the team names ("Melbourne Stars Women").
        if (teams.any { WomenWord.containsMatchIn(it.name) }) return null
        val home = teams.firstOrNull { it.homeAway == HomeValue } ?: teams[0]
        val away = teams.firstOrNull { it !== home && it.homeAway == AwayValue } ?: teams.first { it !== home }

        // The state lives in fullStatus.type.state; the bare "status" word is the fallback.
        val fullStatus = event.optJSONObject(FullStatusKey)
        val type = fullStatus?.optJSONObject(TypeKey)
        val stateWord = type?.optStringOrNull(StateKey)
            ?: (event.opt(StatusKey) as? String)
            ?: event.optJSONObject(StatusKey)?.optJSONObject(TypeKey)?.optStringOrNull(StateKey)
        // ESPN marks a match "in" from the start of its scheduled day, before a ball is bowled
        // ("Starts at 14:30 local time", no score on either side): that is still to come.
        val nothingScored = home.score == null && away.score == null
        val state = when {
            stateWord == InState && nothingScored -> FixtureState.UPCOMING
            stateWord == InState -> FixtureState.LIVE
            stateWord == PostState -> FixtureState.FINISHED
            else -> FixtureState.UPCOMING
        }
        // Where the match stands ("J & K require 248 runs", "Bengal won by 9 wkts") is
        // fullStatus.summary, HTML-escaped; the event's own summary is only "Live" / "Result".
        val detail = fullStatus?.optStringOrNull(SummaryKey)?.let(::unescape)
            ?: type?.optStringOrNull(ShortDetailKey)
            ?: event.optStringOrNull(SummaryKey)
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

    /** A women's series, by its name ("Bangladesh Women tour of Australia", "Women's Big Bash League"). */
    private fun isWomens(seriesName: String): Boolean = WomenWord.containsMatchIn(seriesName)

    /** A women's match, by its class: the women's international ids, or "Women's …" in the class name. */
    private fun isWomens(matchClass: JSONObject): Boolean =
        matchClass.optString(IntlClassKey).toIntOrNull()?.let { it in WomensIntlClasses } == true ||
            WomenWord.containsMatchIn(matchClass.optString(ClassCardKey)) ||
            WomenWord.containsMatchIn(matchClass.optString(NameKey))

    /** "Women", "Women's", "Womens" as a word; never "Bowmen" or the like. */
    private val WomenWord = Regex("\\bwomen", RegexOption.IGNORE_CASE)
    private val WomensIntlClasses = setOf(8, 9, 10)

    /** The few entities ESPN leaves in its summaries ("won by an inns &amp; 135 runs"). */
    internal fun unescape(text: String): String =
        if ('&' !in text) text else text
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

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
    private const val ClassKey = "class"
    private const val IntlClassKey = "internationalClassId"
    private const val ClassCardKey = "generalClassCard"
    private const val InState = "in"
    private const val PostState = "post"
}
