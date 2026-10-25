package tv.own.owntv.features.sport

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hub's rows and chips (v2.6.0, OwnTV's Sports Hub) and the team search behind My teams. */
class SportsHubTest {

    private val now = System.currentTimeMillis()
    private val minute = 60_000L

    private fun team(code: String, name: String = code) = SportTeam(code, name, code, null, null, null, false, "home")

    private fun fixture(id: String, league: String, state: FixtureState, dateMs: Long, home: String = "H$id", away: String = "A$id") = Fixture(
        id = id, leagueId = league, dateMs = dateMs, state = state, statusLine = "",
        home = team(home), away = team(away), broadcasts = emptyList(), venue = null,
    )

    private val bundesliga = "soccer.ger.1"
    private val premier = "soccer.eng.1"
    private val rlwc = "sofascore.431"

    private val fixtures = listOf(
        fixture("live1", premier, FixtureState.LIVE, now - 30 * minute),
        fixture("live2", bundesliga, FixtureState.LIVE, now - 20 * minute, home = "BAY"),
        fixture("soon1", rlwc, FixtureState.UPCOMING, now + 60 * minute),
        fixture("soon2", bundesliga, FixtureState.UPCOMING, now + 30 * minute),
        fixture("done1", premier, FixtureState.FINISHED, now - 5 * 60 * minute),
        fixture("old", premier, FixtureState.FINISHED, now - 5 * 24 * 60 * minute),
    )
    private val rank = mapOf(bundesliga to 0, premier to 1, rlwc to 2)
    private val mine: (Fixture) -> Boolean = { it.home?.abbreviation == "BAY" }

    private fun rows(filter: SportsHub.Filter = SportsHub.Filter()) =
        SportsHub.rows(fixtures, filter, now, { rank[it] ?: Int.MAX_VALUE }, mine, ZoneOffset.UTC)

    @Test
    fun liveInTheUsersLeagueOrderSoonByKickOffResultsNewestFirst() {
        val r = rows()
        assertEquals(listOf("live2", "live1"), r.live.map { it.id })
        assertEquals(listOf("soon2", "soon1"), r.soon.map { it.id })
        // A result older than two days is not "latest".
        assertEquals(listOf("done1"), r.results.map { it.id })
    }

    @Test
    fun aSportChipKeepsItsGamesAndItsChosenLeagues() {
        val soccer = rows(SportsHub.Filter(sports = setOf(SportGroup.SOCCER)))
        assertEquals(setOf("live1", "live2", "soon2"), (soccer.live + soccer.soon).map { it.id }.toSet())
        val onlyBundesliga = rows(SportsHub.Filter(sports = setOf(SportGroup.SOCCER), leagues = setOf(bundesliga)))
        assertEquals(setOf("live2", "soon2"), (onlyBundesliga.live + onlyBundesliga.soon).map { it.id }.toSet())
        val rugby = rows(SportsHub.Filter(sports = setOf(SportGroup.RUGBY_LEAGUE)))
        assertEquals(listOf("soon1"), rugby.soon.map { it.id })
        assertTrue(rugby.live.isEmpty())
    }

    @Test
    fun myTeamsKeepsTheFollowedTeamsGamesAndAddsToASport() {
        val teams = rows(SportsHub.Filter(myTeams = true))
        assertEquals(listOf("live2"), (teams.live + teams.soon + teams.results).map { it.id })
        val both = rows(SportsHub.Filter(myTeams = true, sports = setOf(SportGroup.RUGBY_LEAGUE)))
        assertEquals(setOf("live2", "soon1"), (both.live + both.soon).map { it.id }.toSet())
    }

    @Test
    fun chipsOfferTheChosenSportsInTheUsersOrder() {
        val order = listOf(SportGroup.RUGBY_LEAGUE, SportGroup.SOCCER) + SportGroup.entries.filter { it != SportGroup.RUGBY_LEAGUE && it != SportGroup.SOCCER }
        assertEquals(listOf(SportGroup.RUGBY_LEAGUE, SportGroup.SOCCER), SportsHub.chipSports(setOf(bundesliga, rlwc), order))
        assertTrue(SportsHub.chipSports(emptySet(), order).isEmpty())
    }

    @Test
    fun theHomeRowPutsTheFavouriteTeamsFirst() {
        val row = SportsSchedule.homeRow(fixtures, now, mine = { it.home?.abbreviation == "BAY" }) { rank[it] ?: Int.MAX_VALUE }
        assertEquals("live2", row.first().id)
        val plain = SportsSchedule.homeRow(fixtures, now) { rank[it] ?: Int.MAX_VALUE }
        assertEquals("live1", plain.first().id)
    }

    @Test
    fun stepMovesBothWaysAndWraps() {
        assertEquals(30, SportsSchedule.step(SportsSchedule.LiveRefreshChoices, 0, 1))
        assertEquals(120, SportsSchedule.step(SportsSchedule.LiveRefreshChoices, 0, -1))
        assertEquals(0, SportsSchedule.step(SportsSchedule.AutoRebuildChoices, 60, 1))
    }

    @Test
    fun teamSearchMatchesNamesIgnoringCaseAndAccentsStartsFirst() {
        val index = listOf(
            SportsTeamSearch.Hit(bundesliga, TeamRef("1", "KOE", "1. FC Köln", null)),
            SportsTeamSearch.Hit(bundesliga, TeamRef("2", "BAY", "Bayern Munich", null)),
            SportsTeamSearch.Hit(rlwc, TeamRef("3", "NZL", "New Zealand", null)),
            SportsTeamSearch.Hit(rlwc, TeamRef("4", "COK", "Cook Islands", null)),
            SportsTeamSearch.Hit(rlwc, TeamRef("3", "NZL", "New Zealand", null)),
        )
        assertEquals(listOf("1. FC Köln"), SportsTeamSearch.search(index, "koln").map { it.team.name })
        assertEquals(listOf("New Zealand"), SportsTeamSearch.search(index, "zeal").map { it.team.name })
        assertEquals(listOf("Bayern Munich"), SportsTeamSearch.search(index, "bay").map { it.team.name })
        // "co" starts Cook Islands and is inside nothing else: one row per team and league.
        assertEquals(listOf("Cook Islands"), SportsTeamSearch.search(index, "co").map { it.team.name })
        assertEquals("sofascore.431|NZL", SportsTeamSearch.search(index, "new").single().key)
        assertFalse(SportsTeamSearch.searchable("k"))
        assertTrue(SportsTeamSearch.search(index, "k").isEmpty())
    }
}
