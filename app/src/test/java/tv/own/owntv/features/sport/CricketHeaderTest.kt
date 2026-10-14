package tv.own.owntv.features.sport

import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cricket header parse: which series an entry keeps, and how a header event becomes a fixture. */
class CricketHeaderTest {

    private fun league(id: String, slug: String) = SportLeague(
        id = id, espnSport = "cricket", espnLeague = slug, labelRes = 0,
        region = LeagueRegion.INTERNATIONAL, kind = LeagueKind.TEAMS,
    )

    // Shaped like the personalized header (sports[0].leagues[].events[]): flat competitors, the
    // state in fullStatus.type, the series name on the league.
    private val header = JSONObject(
        """
        {"sports":[{"slug":"cricket","leagues":[
          {"id":"23694","name":"Indian Premier League 2027","events":[
            {"id":"1490237","date":"2027-04-10T14:00Z","summary":"CSK need 45 runs from 30 balls","location":"Chennai",
             "fullStatus":{"type":{"state":"in","shortDetail":"Live"}},
             "competitors":[
               {"id":"1","homeAway":"home","displayName":"Chennai Super Kings","abbreviation":"CSK","score":"160/4","logo":"https://a/csk.png"},
               {"id":"2","homeAway":"away","displayName":"Mumbai Indians","abbreviation":"MI","score":"204/6","winner":false}
             ],
             "broadcasts":[{"name":"Star Sports 1"},"JioCinema"]},
            {"id":"1490238","date":"2027-04-11T10:00:00Z","status":"pre",
             "competitors":[
               {"displayName":"Delhi Capitals","abbreviation":"DC"},
               {"displayName":"Punjab Kings","abbreviation":"PBKS"}
             ]},
            {"id":"1490239","date":"2027-04-12T10:00Z","competitors":[{"displayName":"Only one side"}]}
          ]},
          {"id":"24001","name":"India tour of England 2027","events":[
            {"id":"1500001","date":"2027-04-09T10:00Z","summary":"England won by 5 wickets",
             "fullStatus":{"type":{"state":"post"}},
             "competitors":[
               {"team":{"id":"1","displayName":"England","abbreviation":"ENG"},"score":"301/5","winner":true},
               {"team":{"id":"6","displayName":"India","abbreviation":"IND"},"score":"299/8"}
             ]}
          ]}
        ]}]}
        """.trimIndent(),
    )

    @Test
    fun `an entry keeps the series its label names, all keeps every series`() {
        assertTrue(CricketHeader.keeps("Indian Premier League 2027", "Indian Premier League"))
        assertTrue(CricketHeader.keeps("ICC Men's Cricket World Cup 2027", "cricket world cup"))
        assertFalse(CricketHeader.keeps("ICC Men's T20 World Cup 2026", "Cricket World Cup"))
        assertTrue(CricketHeader.keeps("Anything at all", null))

        val ipl = CricketHeader.fixtures(header, league("cricket.ipl", "ipl"), "Indian Premier League", ZoneOffset.UTC)
        assertEquals(listOf("cricket.ipl/1490237", "cricket.ipl/1490238"), ipl.map { it.id })
        val all = CricketHeader.fixtures(header, league("cricket.all", CricketHeader.AllSeries), null, ZoneOffset.UTC)
        // Every series, oldest first; the one-sided event is skipped rather than failing the board.
        assertEquals(listOf("cricket.all/1500001", "cricket.all/1490237", "cricket.all/1490238"), all.map { it.id })
    }

    @Test
    fun `a header event becomes a two-sided fixture`() {
        val all = CricketHeader.fixtures(header, league("cricket.all", CricketHeader.AllSeries), null, ZoneOffset.UTC)
            .associateBy { it.id.substringAfter('/') }

        val live = all.getValue("1490237")
        assertEquals(FixtureState.LIVE, live.state)
        assertEquals("CSK need 45 runs from 30 balls", live.statusLine)
        assertEquals("Chennai Super Kings", live.home?.name)
        assertEquals("160/4", live.home?.score)
        assertEquals("https://a/csk.png", live.home?.logoUrl)
        assertEquals("Mumbai Indians", live.away?.name)
        assertEquals(listOf("Star Sports 1", "JioCinema"), live.broadcasts)
        assertEquals("Indian Premier League 2027", live.tournament)
        assertEquals("Chennai", live.venue)

        // A bare "status" word, seconds in the date, no homeAway: first side home, upcoming shows the time.
        val upcoming = all.getValue("1490238")
        assertEquals(FixtureState.UPCOMING, upcoming.state)
        assertEquals("10:00", upcoming.statusLine)
        assertEquals("Delhi Capitals", upcoming.home?.name)
        assertEquals("Punjab Kings", upcoming.away?.name)

        // Competitors nested under "team" parse too; a finished match carries the result.
        val result = all.getValue("1500001")
        assertEquals(FixtureState.FINISHED, result.state)
        assertEquals("England won by 5 wickets", result.statusLine)
        assertTrue(result.home?.winner == true)
        assertEquals("IND", result.away?.abbreviation)
    }
}
