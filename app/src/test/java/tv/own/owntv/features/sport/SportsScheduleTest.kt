package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live refresh, auto rebuild and Home's Sports row rules (v5.2 port). */
class SportsScheduleTest {

    private val now = 1_760_000_000_000L
    private val minute = 60_000L

    private fun fixture(id: String, state: FixtureState, dateMs: Long, league: String = "soccer.ger.1") = Fixture(
        id = id, leagueId = league, dateMs = dateMs, state = state, statusLine = "",
        broadcasts = emptyList(), venue = null,
    )

    @Test
    fun nextCyclesThroughTheChoicesAndRestartsOnUnknownValues() {
        assertEquals(30, SportsSchedule.next(SportsSchedule.LiveRefreshChoices, 0))
        assertEquals(0, SportsSchedule.next(SportsSchedule.LiveRefreshChoices, 120))
        assertEquals(0, SportsSchedule.next(SportsSchedule.LiveRefreshChoices, 45))
        assertEquals(60, SportsSchedule.next(SportsSchedule.AutoRebuildChoices, 30))
    }

    @Test
    fun aPastKickOffStillUpcomingCountsAsLiveForAWhile() {
        assertTrue(SportsSchedule.isLiveish(fixture("1", FixtureState.LIVE, now - 200 * minute), now))
        assertTrue(SportsSchedule.isLiveish(fixture("2", FixtureState.UPCOMING, now - 5 * minute), now))
        assertFalse(SportsSchedule.isLiveish(fixture("3", FixtureState.UPCOMING, now + 5 * minute), now))
        assertFalse(SportsSchedule.isLiveish(fixture("4", FixtureState.UPCOMING, now - 4 * 60 * minute), now))
        assertFalse(SportsSchedule.isLiveish(fixture("5", FixtureState.FINISHED, now - 5 * minute), now))
    }

    @Test
    fun onlyBoardsWithALiveGameAreRefreshed() {
        val boards = mapOf(
            "live" to listOf(fixture("1", FixtureState.LIVE, now), fixture("2", FixtureState.FINISHED, now)),
            "quiet" to listOf(fixture("3", FixtureState.UPCOMING, now + 60 * minute)),
        )
        assertEquals(setOf("live"), SportsSchedule.dueLeagues(boards, now))
    }

    @Test
    fun finishedGamesAreNotRebuilt() {
        assertTrue(SportsSchedule.rebuildable(fixture("1", FixtureState.UPCOMING, now)))
        assertTrue(SportsSchedule.rebuildable(fixture("2", FixtureState.LIVE, now)))
        assertFalse(SportsSchedule.rebuildable(fixture("3", FixtureState.FINISHED, now)))
    }

    @Test
    fun homeRowListsLiveFirstThenSoonestAndLeavesTheRestOut() {
        val rank = mapOf("soccer.eng.1" to 0, "soccer.ger.1" to 1)
        val fixtures = listOf(
            fixture("later", FixtureState.UPCOMING, now + 4 * 60 * minute),
            fixture("soon-ger", FixtureState.UPCOMING, now + 30 * minute, league = "soccer.ger.1"),
            fixture("soon-eng", FixtureState.UPCOMING, now + 30 * minute, league = "soccer.eng.1"),
            fixture("done", FixtureState.FINISHED, now - 10 * minute),
            fixture("live", FixtureState.LIVE, now - 50 * minute),
            fixture("live", FixtureState.LIVE, now - 50 * minute),
        )
        val row = SportsSchedule.homeRow(fixtures, now) { rank[it] ?: Int.MAX_VALUE }
        assertEquals(listOf("live", "soon-eng", "soon-ger"), row.map { it.id })
    }

    @Test
    fun homeRowIsCapped() {
        val fixtures = (1..20).map { fixture("g$it", FixtureState.LIVE, now - it * minute) }
        assertEquals(SportsSchedule.HomeMaxCards, SportsSchedule.homeRow(fixtures, now) { 0 }.size)
    }
}
