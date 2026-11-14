package tv.own.owntv.features.sport

import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * World Rugby's feed into the Sport models (DPTV v2.9.20), against the shapes of live answers from
 * 2026-11-14 (World Rugby Nations Cup 2026, Top 14 2027; trimmed to the fields read), and the rules
 * that pick a season and lay ESPN over it.
 */
class WorldRugbyParseTest {

    private val league = SportLeague(
        id = "worldrugby.nations-cup", espnSport = "rugby", espnLeague = "", labelRes = 0,
        region = LeagueRegion.INTERNATIONAL, kind = LeagueKind.TEAMS, source = LeagueSource.WORLD_RUGBY,
        worldRugby = listOf("World Rugby Nations Cup", "Nations Cup"),
    )
    private val labels = WorldRugbyApi.Labels("FT", "HT", " · ") { "$it'" }
    private val now = 1_795_000_000_000L

    private fun fixture(json: String) = WorldRugbyApi.fixtureFrom(league, JSONObject(json), labels, ZoneOffset.UTC, now)

    private val finished = """
        {"matchId":"34d3d710-b853","description":"Match 21","eventPhase":null,
         "venue":{"name":"Stade Marcel-Verchère","city":"Bourg-en-Bresse","country":"France"},
         "time":{"millis":1794149100000,"gmtOffset":1.0,"label":"2026-11-08"},
         "teams":[{"id":"725","name":"Hong Kong China","abbreviation":"HKG"},{"id":"51","name":"USA","abbreviation":"USA"}],
         "scores":[26,28],"status":"C","clock":{"secs":4856,"label":"80:56"},"outcome":"B"}
    """

    private val upcoming = """
        {"matchId":"e6254670","description":"Match 25","eventPhase":"Quarter-Finals",
         "venue":{"name":"Mikheil Meskhi Stadium","city":"Tbilisi"},
         "time":{"millis":1795100000000},
         "teams":[{"id":"720","name":"Georgia","abbreviation":"GEO"},{"id":"40","name":"Tonga","abbreviation":"TGA"}],
         "scores":[0,0],"status":"U","clock":{"secs":0,"label":"00:00"},"outcome":null}
    """

    private val live = """
        {"matchId":"live1","eventPhase":"League","time":{"millis":1794998000000},
         "teams":[{"id":"1","name":"Stade Rochelais","abbreviation":"SR"},{"id":"2","name":"Aviron Bayonnais","abbreviation":"BAY"}],
         "scores":[17,10],"status":"L2","clock":{"secs":3260,"label":"54:20"}}
    """

    @Test
    fun aFinishedGameMapsScoresWinnerVenueAndMatchId() {
        val f = fixture(finished)!!
        assertEquals("worldrugby.nations-cup/wr/34d3d710-b853", f.id)
        assertEquals("34d3d710-b853", f.worldRugbyMatchId)
        assertEquals(FixtureState.FINISHED, f.state)
        assertEquals("FT", f.statusLine)
        assertEquals("Hong Kong China", f.home!!.name)
        assertEquals("HKG", f.home!!.abbreviation)
        assertEquals("26", f.home!!.score)
        assertEquals("28", f.away!!.score)
        assertFalse(f.home!!.winner)
        assertTrue(f.away!!.winner)
        assertEquals("Stade Marcel-Verchère · Bourg-en-Bresse", f.venue)
        // "Match 21" is World Rugby's numbering, not a round.
        assertNull(f.round)
    }

    @Test
    fun anUpcomingGameHasNoScoreAndShowsItsKickOffAndStage() {
        val f = fixture(upcoming)!!
        assertEquals(FixtureState.UPCOMING, f.state)
        assertEquals("14:53", f.statusLine)
        assertNull(f.home!!.score)
        assertEquals("Quarter-Finals", f.round)
    }

    @Test
    fun aGameInProgressShowsTheMinuteAndHalfTime() {
        val f = fixture(live)!!
        assertEquals(FixtureState.LIVE, f.state)
        assertEquals("54'", f.statusLine)
        assertEquals("17", f.home!!.score)
        assertNull(f.round)
        val half = fixture(live.replace("\"L2\"", "\"LHT\""))!!
        assertEquals("HT", half.statusLine)
    }

    @Test
    fun aMatchWithoutTeamsOrTimeIsSkipped() {
        assertNull(fixture("""{"matchId":"x","time":{"millis":0},"teams":[]}"""))
    }

    @Test
    fun theTeamSheetsSplitStartersBenchCaptainAndCoach() {
        val root = JSONObject(
            """
            {"teams":[
              {"teamList":{"captainIds":["p10"],"list":[
                {"player":{"id":"p16","name":{"display":"Harry Baron"},"caps":4},"number":"16","position":"Rep","positionLabel":"Replacement"},
                {"player":{"id":"p10","name":{"display":"Joe Barker"},"caps":7},"number":"10","position":"FH","positionLabel":"Fly Half"},
                {"player":{"id":"c1","name":{"display":"Logan Asplin"}},"number":null,"position":null,"positionLabel":"Head Coach"},
                {"player":{"id":"p1","name":{"display":"Sunia Fameitau"},"caps":12},"number":"1","position":"PR","positionLabel":"Prop"}
              ]}},
              {"teamList":{"list":[]}}
            ]}
            """,
        )
        val sheets = WorldRugbyApi.lineupsFrom(root)
        assertTrue(sheets.announced)
        assertEquals(listOf(1, 10), sheets.home.starters.map { it.number })
        assertEquals("Prop", sheets.home.starters[0].position)
        assertTrue(sheets.home.starters[1].captain)
        assertFalse(sheets.home.starters[0].captain)
        assertEquals(12, sheets.home.starters[0].caps)
        assertEquals(listOf("Harry Baron"), sheets.home.replacements.map { it.name })
        assertNull(sheets.home.replacements[0].position)
        assertEquals("Logan Asplin", sheets.home.coach)
        assertTrue(sheets.away.starters.isEmpty())
        assertFalse(WorldRugbyApi.lineupsFrom(JSONObject("{}")).announced)
    }

    @Test
    fun tablesCarryEveryColumnAndNamePoolsOnlyWhenThereAreSeveral() {
        val entry = """{"positionLabel":"1","team":{"id":"51","name":"USA","abbreviation":"USA"},
            "played":4,"won":4,"lost":0,"drawn":0,"pointsFor":118,"pointsAgainst":92,"bonusPoints":3,"points":19}"""
        val two = JSONObject("""{"tables":[{"label":null,"shortLabel":"A","entries":[$entry]},{"label":null,"shortLabel":"B","entries":[$entry]}]}""")
        val tables = WorldRugbyApi.tablesFrom(league, two, listOf("P", "W", "D", "L", "+/-", "BP", "Pts")) { "Pool $it" }
        assertEquals(2, tables.size)
        assertEquals("Pool A", tables[0].groupName)
        assertEquals(listOf("4", "4", "0", "0", "+26", "3", "19"), tables[0].rows[0].stats)
        val one = JSONObject("""{"tables":[{"label":null,"shortLabel":"A","entries":[$entry]}]}""")
        assertNull(WorldRugbyApi.tablesFrom(league, one, emptyList()) { "Pool $it" }[0].groupName)
    }

    @Test
    fun aSeasonBelongsToTheLeagueByNameWithoutTheYearAndBySport() {
        fun event(label: String, sport: String = "mru") = WorldRugbyApi.Event("id", label, sport, 0L, 0L)
        assertTrue(WorldRugbyRules.isCompetition(event("World Rugby Nations Cup 2026"), league))
        assertFalse(WorldRugbyRules.isCompetition(event("Pacific Nations Cup 2026"), league))
        assertFalse(WorldRugbyRules.isCompetition(event("World Rugby Nations Cup 2026", "wru"), league))
        val europe = league.copy(worldRugby = listOf("Rugby Europe Trophy Men's"))
        assertTrue(WorldRugbyRules.isCompetition(event("Rugby Europe Trophy Men's 2026-27"), europe))
        val six = league.copy(worldRugby = listOf("Six Nations"))
        assertTrue(WorldRugbyRules.isCompetition(event("2027 Six Nations"), six))
        assertFalse(WorldRugbyRules.isCompetition(event("2027 U20 Six Nations"), six))
    }

    @Test
    fun theSeasonOnNowWinsElseOneStartingSoonElseTheLastPlayed() {
        val day = 24L * 60L * 60L * 1000L
        val last = WorldRugbyApi.Event("last", "x", "mru", now - 200 * day, now - 100 * day)
        val soon = WorldRugbyApi.Event("soon", "x", "mru", now + 10 * day, now + 60 * day)
        val far = WorldRugbyApi.Event("far", "x", "mru", now + 90 * day, now + 120 * day)
        val on = WorldRugbyApi.Event("on", "x", "mru", now - 10 * day, now + 10 * day)
        assertEquals("on", WorldRugbyRules.pickEvent(listOf(last, soon, on), now)?.id)
        assertEquals("soon", WorldRugbyRules.pickEvent(listOf(last, soon), now)?.id)
        assertEquals("last", WorldRugbyRules.pickEvent(listOf(last, far), now)?.id)
        assertEquals("far", WorldRugbyRules.pickEvent(listOf(far), now)?.id)
        assertNull(WorldRugbyRules.pickEvent(emptyList(), now))
    }

    private fun team(id: String, name: String, abbrev: String, logo: String? = null, homeAway: String = "home", score: String? = null) =
        SportTeam(id, name, abbrev, logo, null, score, false, homeAway)

    private fun game(id: String, at: Long, home: SportTeam, away: SportTeam, broadcasts: List<String> = emptyList()) =
        Fixture(id = id, leagueId = "rugby.270559", dateMs = at, state = FixtureState.UPCOMING, statusLine = "", home = home, away = away, broadcasts = broadcasts, venue = null)

    @Test
    fun espnsGameLendsItsIdBroadcastersAndTeamsKeepingWorldRugbysScore() {
        val wr = game(
            "rugby.270559/wr/m1", now,
            team("wr1", "Stade Rochelais", "SR", score = "17"), team("wr2", "Aviron Bayonnais", "BAY", homeAway = "away", score = "10"),
        ).copy(worldRugbyMatchId = "m1")
        // ESPN lists the same game half an hour later, the other way round, under its own names.
        val espn = game(
            "rugby.270559/401", now + 30 * 60 * 1000L,
            team("9", "Bayonne", "BAY", "https://espn/bay.png"), team("8", "La Rochelle", "LR", "https://espn/lr.png", "away"),
            broadcasts = listOf("beIN Sports"),
        )
        val merged = WorldRugbyRules.merge(listOf(wr), listOf(espn), emptyList()).single()
        assertEquals("rugby.270559/401", merged.id)
        assertEquals("m1", merged.worldRugbyMatchId)
        assertEquals(listOf("beIN Sports"), merged.broadcasts)
        assertEquals("La Rochelle", merged.home!!.name)
        assertEquals("LR", merged.home!!.abbreviation)
        assertEquals("https://espn/lr.png", merged.home!!.logoUrl)
        assertEquals("17", merged.home!!.score)
        assertEquals("Bayonne", merged.away!!.name)
        assertEquals("10", merged.away!!.score)
    }

    @Test
    fun aGameEspnNeverShowedTakesCrestsByTeamThenByName() {
        val day = 24L * 60L * 60L * 1000L
        val first = game("a/wr/1", now, team("wr1", "Stade Rochelais", "SR"), team("wr2", "Aviron Bayonnais", "BAY", homeAway = "away"))
        val espn = game("a/401", now, team("9", "Bayonne", "BAY", "https://espn/bay.png"), team("8", "La Rochelle", "LR", "https://espn/lr.png", "away"))
        // A later game ESPN's board does not reach: La Rochelle by World Rugby's team id, the USA by name.
        val later = game("a/wr/2", now + 40 * day, team("wr1", "Stade Rochelais", "SR"), team("wr51", "USA", "USA", homeAway = "away"))
        val crests = listOf(TeamRef("77", "USA", "United States of America", "https://espn/usa.png"))
        val merged = WorldRugbyRules.merge(listOf(first, later), listOf(espn), crests)
        assertEquals("a/wr/2", merged[1].id)
        assertEquals("https://espn/lr.png", merged[1].home!!.logoUrl)
        assertEquals("LR", merged[1].home!!.abbreviation)
        assertEquals("https://espn/usa.png", merged[1].away!!.logoUrl)
        assertEquals("away", merged[1].away!!.homeAway)
        assertTrue(merged[1].broadcasts.isEmpty())
    }

    @Test
    fun namesMatchWithoutFillersButNotByOneSharedWord() {
        assertTrue(WorldRugbyRules.sameName("England", "England Women"))
        assertTrue(WorldRugbyRules.sameName("USA", "United States of America"))
        assertTrue(WorldRugbyRules.sameName("Northampton Saints", "Northampton"))
        assertFalse(WorldRugbyRules.sameName("South Africa", "Samoa"))
        assertFalse(WorldRugbyRules.sameName("New Zealand", "New South Wales Waratahs"))
        // An exact name wins over a containing one when crests are looked up.
        val crests = listOf(
            TeamRef("1", "NSW", "New South Wales Waratahs", "https://espn/nsw.png"),
            TeamRef("2", "WAL", "Wales", "https://espn/wal.png"),
        )
        val wales = game("x/wr/1", now, team("wr1", "Wales", "WAL"), team("wr2", "Fiji", "FIJ", homeAway = "away"))
        assertEquals("https://espn/wal.png", WorldRugbyRules.merge(listOf(wales), emptyList(), crests).single().home!!.logoUrl)
    }

    @Test
    fun eventListsKeepIdLabelSportAndDates() {
        val list = JSONArray(
            """[{"id":"795ee1f0","label":"World Rugby Nations Cup 2026","sport":"mru",
                "start":{"millis":1780272000000},"end":{"millis":1795996800000}},{"label":"no id"}]""",
        )
        val events = WorldRugbyApi.eventsFrom(list)
        assertEquals(1, events.size)
        assertEquals(WorldRugbyApi.Event("795ee1f0", "World Rugby Nations Cup 2026", "mru", 1780272000000L, 1795996800000L), events[0])
    }
}
