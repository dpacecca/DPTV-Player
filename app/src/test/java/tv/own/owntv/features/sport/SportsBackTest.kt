package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** (v2.6.1) Sport's pages: which one is on screen, and where Back from each goes. */
class SportsBackTest {
    @Test
    fun aLeagueShowsOverAMatchAndAMatchOverTheHub() {
        assertEquals(SportsPage.HUB, sportsPage(fixtureOpen = false, leagueOpen = false))
        assertEquals(SportsPage.MATCH, sportsPage(fixtureOpen = true, leagueOpen = false))
        assertEquals(SportsPage.LEAGUE, sportsPage(fixtureOpen = false, leagueOpen = true))
        assertEquals(SportsPage.LEAGUE, sportsPage(fixtureOpen = true, leagueOpen = true))
    }

    @Test
    fun backFromAMatchOpenedOnTheHubReturnsToTheHub() {
        assertEquals(SportsPage.HUB, sportsBack(SportsPage.MATCH, matchFromLeague = false, leagueFromMatch = false))
    }

    @Test
    fun backFromAMatchOpenedOnALeagueReturnsToTheLeague() {
        assertEquals(SportsPage.LEAGUE, sportsBack(SportsPage.MATCH, matchFromLeague = true, leagueFromMatch = false))
    }

    @Test
    fun backFromALeagueReturnsToWhereItWasOpened() {
        assertEquals(SportsPage.HUB, sportsBack(SportsPage.LEAGUE, matchFromLeague = false, leagueFromMatch = false))
        assertEquals(SportsPage.MATCH, sportsBack(SportsPage.LEAGUE, matchFromLeague = false, leagueFromMatch = true))
    }

    @Test
    fun theHubKeysForFocusToComeBackTo() {
        assertEquals("league:nfl", hubLeagueKey("nfl"))
        assertNotEquals(HubSettingsKey, hubLeagueKey(HubSettingsKey))
    }

    @Test
    fun theCalendarShowsUnderAMatchOpenedFromIt() {
        assertEquals(SportsPage.CALENDAR, sportsPage(fixtureOpen = false, leagueOpen = false, calendarOpen = true))
        assertEquals(SportsPage.MATCH, sportsPage(fixtureOpen = true, leagueOpen = false, calendarOpen = true))
        assertEquals(SportsPage.CALENDAR, sportsBack(SportsPage.MATCH, matchFromLeague = false, leagueFromMatch = false, matchFromCalendar = true))
        assertEquals(SportsPage.HUB, sportsBack(SportsPage.CALENDAR, matchFromLeague = false, leagueFromMatch = false))
        assertNotEquals(HubCalendarKey, HubSettingsKey)
    }
}
