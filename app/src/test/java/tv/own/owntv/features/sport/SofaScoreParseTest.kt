package tv.own.owntv.features.sport

import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SofaScore events and standings into the Sport models (v2.5.0), against the shapes of live answers
 * from 2026-10-19 (Rugby League World Cup 2026, Super League 2026, SVNS 2026; trimmed to the fields
 * read).
 */
class SofaScoreParseTest {

    private val league = SportLeague(
        id = "sofascore.431", espnSport = "rugby-league", espnLeague = "431", labelRes = 0,
        region = LeagueRegion.INTERNATIONAL, kind = LeagueKind.TEAMS, source = LeagueSource.SOFASCORE,
    )

    private fun fixture(json: String) = SofaScoreApi.fixtureFrom(league, JSONObject(json), ", ", ZoneOffset.UTC)

    private val upcoming = """
        {"id":14253021,"startTimestamp":1792746300,
         "status":{"code":0,"description":"Not started","type":"notstarted"},
         "tournament":{"name":"World Cup, Group B and C","uniqueTournament":{"name":"World Cup","id":431}},
         "roundInfo":{"round":1},
         "venue":{"name":"CommBank Stadium","city":{"name":"Parramatta"}},
         "homeTeam":{"id":24244,"name":"Tonga","shortName":"Tonga","nameCode":"TGA","teamColors":{"primary":"#ff0000"}},
         "awayTeam":{"id":24237,"name":"Fiji","shortName":"Fiji","nameCode":"FIJ"},
         "homeScore":{},"awayScore":{}}
    """

    @Test
    fun anUpcomingGameMapsTeamsTimeVenueAndStage() {
        val f = fixture(upcoming)!!
        assertEquals("sofascore.431/14253021", f.id)
        assertEquals("sofascore.431", f.leagueId)
        assertEquals(1792746300L * 1000L, f.dateMs)
        assertEquals(FixtureState.UPCOMING, f.state)
        assertEquals("09:05", f.statusLine)
        assertEquals(FixtureFormat.VERSUS, f.format)
        assertEquals("Tonga", f.home!!.name)
        assertEquals("TGA", f.home!!.abbreviation)
        assertEquals("24244", f.home!!.id)
        assertEquals("home", f.home!!.homeAway)
        assertEquals("ff0000", f.home!!.colorHex)
        assertEquals("https://api.sofascore.app/api/v1/team/24244/image", f.home!!.logoUrl)
        assertEquals("FIJ", f.away!!.abbreviation)
        assertEquals("away", f.away!!.homeAway)
        assertNull(f.home!!.score)
        assertEquals("CommBank Stadium, Parramatta", f.venue)
        assertEquals("Group B and C", f.round)
        assertTrue(f.broadcasts.isEmpty())
    }

    @Test
    fun aFinishedGameCarriesScoresWinnerAndNamedRound() {
        val f = fixture(
            """
            {"id":13900001,"startTimestamp":1787416200,
             "status":{"code":100,"description":"Ended","type":"finished"},
             "tournament":{"name":"Super League, Playoffs","uniqueTournament":{"id":302}},
             "roundInfo":{"round":29,"name":"Final","slug":"final","cupRoundType":1},
             "venue":{"name":"Accor Stadium ","city":{"name":"Sydney"}},
             "homeTeam":{"id":4233,"name":"Wigan Warriors","shortName":"Wigan","nameCode":"WGW","teamColors":{"primary":"#374df5"}},
             "awayTeam":{"id":4240,"name":"Hull KR","shortName":"Hull KR","nameCode":"HKR"},
             "homeScore":{"current":24,"display":24,"period1":12,"period2":12},
             "awayScore":{"current":6,"display":6,"period1":0,"period2":6},
             "winnerCode":1}
            """,
        )!!
        assertEquals(FixtureState.FINISHED, f.state)
        assertEquals("Ended", f.statusLine)
        assertEquals("24", f.home!!.score)
        assertEquals("6", f.away!!.score)
        assertTrue(f.home!!.winner)
        assertFalse(f.away!!.winner)
        assertEquals("Final", f.round)
        assertEquals("Accor Stadium, Sydney", f.venue)
        assertNull(f.home!!.colorHex)
    }

    @Test
    fun aLiveGameShowsSofaScoresPeriod() {
        val f = fixture(
            upcoming
                .replace(""""code":0,"description":"Not started","type":"notstarted"""", """"code":6,"description":"1st half","type":"inprogress"""")
                .replace(""""homeScore":{}""", """"homeScore":{"current":8,"display":8}""")
                .replace(""""awayScore":{}""", """"awayScore":{"current":0}"""),
        )!!
        assertEquals(FixtureState.LIVE, f.state)
        assertEquals("1st half", f.statusLine)
        assertEquals("8", f.home!!.score)
        assertEquals("0", f.away!!.score)
    }

    @Test
    fun statusTypesMapToStates() {
        assertEquals(FixtureState.LIVE, SofaScoreApi.stateOf("inprogress"))
        assertEquals(FixtureState.LIVE, SofaScoreApi.stateOf("interrupted"))
        assertEquals(FixtureState.FINISHED, SofaScoreApi.stateOf("finished"))
        assertEquals(FixtureState.FINISHED, SofaScoreApi.stateOf("postponed"))
        assertEquals(FixtureState.FINISHED, SofaScoreApi.stateOf("canceled"))
        assertEquals(FixtureState.UPCOMING, SofaScoreApi.stateOf("notstarted"))
        assertEquals(FixtureState.UPCOMING, SofaScoreApi.stateOf(""))
    }

    @Test
    fun aPlainRoundNumberGivesNoRound() {
        val f = fixture(upcoming.replace(""""name":"World Cup, Group B and C"""", """"name":"Super League""""))!!
        assertNull(f.round)
    }

    @Test
    fun anEventWithoutTimeOrTeamsIsSkipped() {
        assertNull(fixture(upcoming.replace(""""startTimestamp":1792746300""", """"startTimestamp":0""")))
        assertNull(fixture("""{"id":1,"startTimestamp":1792746300,"homeTeam":{"id":1,"name":"A"}}"""))
    }

    @Test
    fun theLiveListIsMatchedByUniqueTournament() {
        assertEquals("431", SofaScoreApi.tournamentIdOf(JSONObject(upcoming)))
        assertNull(SofaScoreApi.tournamentIdOf(JSONObject("""{"tournament":{"name":"x"}}""")))
    }

    @Test
    fun standingsBecomeOneTablePerGroup() {
        val tables = SofaScoreApi.tablesFrom(
            league,
            JSONObject(
                """
                {"standings":[
                  {"name":"Group A","type":"total","rows":[
                    {"team":{"id":24237,"name":"Fiji","shortName":"Fiji","nameCode":"FIJ"},"position":1,
                     "matches":1,"wins":1,"losses":0,"draws":0,"scoresFor":40,"scoresAgainst":12,"points":2,
                     "scoreDiffFormatted":"+28"},
                    {"team":{"id":24244,"name":"Tonga"},"position":2,"matches":1,"scoresFor":12,
                     "scoresAgainst":40,"points":0}]},
                  {"name":"Group B and C","type":"total","rows":[]}]}
                """,
            ),
            listOf("P", "+/-", "Pts"),
        )
        assertEquals(1, tables.size)
        val table = tables.single()
        assertEquals("Group A", table.groupName)
        assertEquals(listOf("P", "+/-", "Pts"), table.columns)
        assertEquals("1", table.rows[0].rank)
        assertEquals("FIJ", table.rows[0].teamAbbrev)
        assertEquals(listOf("1", "+28", "2"), table.rows[0].stats)
        assertEquals("https://api.sofascore.app/api/v1/team/24237/image", table.rows[0].logoUrl)
        assertEquals("", table.rows[1].teamAbbrev)
        assertEquals(listOf("1", "-28", "0"), table.rows[1].stats)
    }

    @Test
    fun sofaScoreLeaguesSitInTheRugbyGroups() {
        val ids = SportsLeagues.filter { it.source == LeagueSource.SOFASCORE }.associate { it.espnLeague to it.sportGroup }
        assertEquals(SportGroup.RUGBY_UNION, ids["10055"])
        assertEquals(SportGroup.RUGBY_UNION, ids["11623"])
        assertEquals(SportGroup.RUGBY_LEAGUE, ids["431"])
        assertEquals(SportGroup.RUGBY_LEAGUE, ids["791"])
        assertEquals(SportGroup.RUGBY_LEAGUE, ids["302"])
        assertEquals(SportGroup.RUGBY_LEAGUE, ids["1590"])
        assertFalse("421" in ids)
    }
}
