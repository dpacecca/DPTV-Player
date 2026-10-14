package tv.own.owntv.features.sport

import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sport.CricketHeader.Selection

/**
 * The cricket header parse, against a trimmed copy of a real ESPN answer (2026-10-14, region "in"):
 * which matches each kind of entry keeps, and how a header event becomes a fixture.
 */
class CricketHeaderTest {

    private val header: JSONObject = JSONObject(
        requireNotNull(javaClass.getResource("/espn_cricket_header.json")).readText(),
    )

    private fun league(id: String, slug: String) = SportLeague(
        id = id, espnSport = "cricket", espnLeague = slug, labelRes = 0,
        region = LeagueRegion.INTERNATIONAL, kind = LeagueKind.TEAMS,
    )

    private fun shown(slug: String, label: String = ""): List<String> =
        CricketHeader.fixtures(header, league("cricket.$slug", slug), CricketHeader.selectionFor(slug, label), ZoneOffset.UTC)
            .map { it.home?.abbreviation + " v " + it.away?.abbreviation }

    @Test
    fun `entries pick their matches by series name, by format, or all`() {
        assertEquals(listOf("BENG v DELHI", "J & K v MP", "GOA v BRODA"), shown("ranji", "Ranji Trophy"))
        // The T20Is of every tour: a qualifier and a bilateral series alike.
        assertEquals(listOf("MNG v MYAN", "IND v WI"), shown(CricketHeader.T20is))
        assertEquals(emptyList<String>(), shown(CricketHeader.Tests))
        // Every men's match, oldest first (ESPN's order for the same start).
        assertEquals(
            listOf("BENG v DELHI", "J & K v MP", "GOA v BRODA", "KRL v PakTV", "MNG v MYAN", "IND v WI"),
            shown(CricketHeader.AllSeries),
        )
    }

    @Test
    fun `women's cricket is left out everywhere`() {
        // By series name ("Bangladesh Women tour of Australia"), by the women's ODI class, and by
        // team names when nothing else says so ("Melbourne Renegades Women" in a mixed series).
        assertEquals(emptyList<String>(), shown(CricketHeader.Odis))
        assertEquals(emptyList<String>(), shown("spring", "T20 Spring Challenge"))
        assertFalse(shown(CricketHeader.AllSeries).any { "-W" in it })
        assertTrue(CricketHeader.keeps("Indian Premier League 2027", Selection.Series("indian premier league")))
        assertFalse(CricketHeader.keeps("ICC Men's T20 World Cup 2026", Selection.Series("Cricket World Cup")))
    }

    @Test
    fun `a header event becomes a fixture that says where the match stands`() {
        val all = CricketHeader.fixtures(header, league("cricket.all", CricketHeader.AllSeries), Selection.All, ZoneOffset.UTC)
            .associateBy { it.home?.abbreviation }

        val live = all.getValue("J & K")
        assertEquals(FixtureState.LIVE, live.state)
        assertEquals("J & K require 248 runs", live.statusLine)
        assertEquals("Jammu & Kashmir", live.home?.name)
        assertEquals("120 & 78/7 (33.4 ov, target 326)", live.home?.score)
        assertEquals("Ranji Trophy", live.tournament)
        assertTrue(live.home?.logoUrl?.startsWith("https://") == true)
        assertNull(live.home?.colorHex) // ESPN sends "" for a team without a colour

        // ESPN's own summary there is "won by an inns &amp; 135 runs".
        assertEquals(FixtureState.FINISHED, all.getValue("GOA").state)
        assertEquals("Baroda won by an inns & 135 runs", all.getValue("GOA").statusLine)

        val upcoming = all.getValue("IND")
        assertEquals(FixtureState.UPCOMING, upcoming.state)
        assertEquals("13:30", upcoming.statusLine)
    }

    @Test
    fun `a match marked in play before a ball is bowled is still to come`() {
        val event = JSONObject(
            """
            {"id":"9","date":"2026-10-14T05:00:00Z","status":"in","summary":"Live",
             "fullStatus":{"type":{"state":"in","shortDetail":"Live"},"summary":"Starts at 10:00 local time"},
             "competitors":[{"homeAway":"home","displayName":"Ghazi","score":""},
                            {"homeAway":"away","displayName":"SNGPL","score":""}]}
            """.trimIndent(),
        )
        val root = JSONObject().put(
            "sports",
            org.json.JSONArray().put(
                JSONObject().put(
                    "leagues",
                    org.json.JSONArray().put(JSONObject().put("name", "President's Trophy").put("events", org.json.JSONArray().put(event))),
                ),
            ),
        )
        val fixture = CricketHeader.fixtures(root, league("cricket.all", CricketHeader.AllSeries), Selection.All, ZoneOffset.UTC).single()
        assertEquals(FixtureState.UPCOMING, fixture.state)
        assertEquals("05:00", fixture.statusLine)
    }

    @Test
    fun `entities are unescaped`() {
        assertEquals("A & B <c> \"d\" 'e'", CricketHeader.unescape("A &amp; B &lt;c&gt; &quot;d&quot; &#39;e&#39;"))
        assertEquals("plain", CricketHeader.unescape("plain"))
    }
}
