package tv.own.owntv.features.sport

import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TheSportsDB events into the Sport models (v2.6.0), against live answers from 2026-10-25 (Rugby
 * League World Cup 2026: today's two round 2 games and the round 1 opener; trimmed to the fields read).
 */
class TheSportsDbParseTest {

    private val league = SportLeague(
        id = "sofascore.431", espnSport = "rugby-league", espnLeague = "431", labelRes = 0,
        region = LeagueRegion.INTERNATIONAL, kind = LeagueKind.TEAMS, source = LeagueSource.SOFASCORE,
        theSportsDbId = "5806",
    )

    private val events = TheSportsDbApi.eventsOf(
        JSONObject(requireNotNull(javaClass.getResource("/thesportsdb_rlwc.json")).readText()),
    )

    /** 2026-10-25 00:00 UTC: before today's games. */
    private val morning = 1_792_886_400_000L

    private fun parse(event: JSONObject, now: Long = morning) =
        TheSportsDbApi.fixtureFrom(league, event, ", ", ZoneOffset.UTC, now)

    @Test
    fun todaysGamesParseWithCleanNamesTimeVenueAndRound() {
        val fixtures = events.take(2).map { parse(it)!! }
        val nz = fixtures[0]
        assertEquals("sofascore.431/2420423", nz.id)
        assertEquals("sofascore.431", nz.leagueId)
        assertEquals(FixtureState.UPCOMING, nz.state)
        assertEquals("06:05", nz.statusLine)
        assertEquals(1_792_908_300_000L, nz.dateMs)
        assertEquals("New Zealand", nz.home!!.name)
        assertEquals("NZL", nz.home!!.abbreviation)
        assertEquals("154465", nz.home!!.id)
        assertEquals("home", nz.home!!.homeAway)
        assertEquals("Cook Islands", nz.away!!.name)
        assertEquals("COK", nz.away!!.abbreviation)
        assertTrue(nz.home!!.logoUrl!!.startsWith("https://r2.thesportsdb.com/images/media/team/badge/"))
        assertNull(nz.home!!.score)
        assertEquals("One NZ Stadium, Christchurch", nz.venue)
        assertEquals(2, nz.roundNumber)
        assertEquals("A", nz.group)
        assertTrue(nz.broadcasts.isEmpty())
        assertEquals("Australia", fixtures[1].home!!.name)
        assertEquals("Fiji", fixtures[1].away!!.name)
        assertEquals("09:05", fixtures[1].statusLine)
    }

    @Test
    fun aFinishedGameCarriesScoresAndWinner() {
        val f = parse(events[2])!!
        assertEquals(FixtureState.FINISHED, f.state)
        assertEquals("FT", f.statusLine)
        assertEquals("Australia", f.home!!.name)
        assertEquals("24", f.home!!.score)
        assertEquals("22", f.away!!.score)
        assertTrue(f.home!!.winner)
        assertFalse(f.away!!.winner)
        assertEquals(1, f.roundNumber)
    }

    @Test
    fun aGamePastKickOffWithoutAResultIsLiveThenOver() {
        val kickOff = 1_792_908_300_000L
        assertEquals(FixtureState.LIVE, parse(events[0], now = kickOff + 30 * 60_000L)!!.state)
        val over = parse(events[0], now = kickOff + TheSportsDbApi.LikelyLengthMs + 1)!!
        assertEquals(FixtureState.FINISHED, over.state)
        // No result yet: no score, and the kick-off time rather than an invented "FT".
        assertNull(over.home!!.score)
        assertEquals("06:05", over.statusLine)
    }

    @Test
    fun stateCoversTheStatusWords() {
        val k = 1_000_000L
        assertEquals(FixtureState.FINISHED, TheSportsDbApi.stateOf("Match Finished", false, true, k, 0L))
        assertEquals(FixtureState.FINISHED, TheSportsDbApi.stateOf("NS", true, false, k, 0L))
        assertEquals(FixtureState.FINISHED, TheSportsDbApi.stateOf("Postponed", false, false, k, 0L))
        assertEquals(FixtureState.LIVE, TheSportsDbApi.stateOf("2H", false, false, k, 0L))
        assertEquals(FixtureState.UPCOMING, TheSportsDbApi.stateOf("NS", false, false, k, 0L))
    }

    @Test
    fun kickOffFallsBackToDateAndTimeInUtc() {
        val event = JSONObject("""{"dateEvent":"2026-10-25","strTime":"09:05:00+00:00"}""")
        assertEquals(1_792_919_100_000L, TheSportsDbApi.kickOffMs(event))
        assertEquals(1_792_919_100_000L, TheSportsDbApi.kickOffMs(JSONObject("""{"strTimestamp":"2026-10-25T09:05:00+00:00"}""")))
        assertNull(TheSportsDbApi.kickOffMs(JSONObject("""{"strEvent":"x"}""")))
    }

    @Test
    fun teamNamesAndShortCodes() {
        assertEquals("Papua New Guinea", TheSportsDbApi.cleanTeamName("Papua New Guinea Rugby League"))
        assertEquals("Wigan Warriors", TheSportsDbApi.cleanTeamName("Wigan Warriors"))
        assertEquals("Fiji", TheSportsDbApi.cleanTeamName("Fiji 7s Rugby"))
        assertEquals("PNG", TheSportsDbApi.abbreviationOf("Papua New Guinea"))
        assertEquals("HKR", TheSportsDbApi.abbreviationOf("Hull Kingston Rovers"))
        assertEquals("HUL", TheSportsDbApi.abbreviationOf("Hull FC"))
        assertEquals("WAR", TheSportsDbApi.abbreviationOf("Warrington Wolves"))
        assertEquals("STH", TheSportsDbApi.abbreviationOf("St Helens"))
        assertEquals("QLD", TheSportsDbApi.abbreviationOf("Queensland Maroons"))
    }

    @Test
    fun aNullEventListIsEmpty() {
        assertTrue(TheSportsDbApi.eventsOf(JSONObject("""{"events":null}""")).isEmpty())
    }
}
