package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** (DPTV v2.9.19) A league's board between rounds: ESPN's current day plus the months ahead. */
class BoardRulesTest {
    private fun fx(id: String, dateMs: Long, state: FixtureState = FixtureState.UPCOMING) =
        Fixture(id = id, leagueId = "rugby.17567", dateMs = dateMs, state = state, statusLine = "")

    private val now = 1_000_000L

    @Test fun `only games already played means the months ahead are read`() {
        assertTrue(BoardRules.needsMonths(listOf(fx("a", now - 3_600_000, FixtureState.FINISHED)), now))
        assertTrue(BoardRules.needsMonths(emptyList(), now))
        assertFalse(BoardRules.needsMonths(listOf(fx("b", now + 60_000)), now))
    }

    @Test fun `the parts join once each, in order`() {
        val current = listOf(fx("fra-rsa", now - 3_600_000, FixtureState.FINISHED))
        val months = listOf(fx("eng-nzl", now + 7 * 86_400_000L), fx("fra-rsa", now - 3_600_000), fx("ita-arg", now + 86_400_000L))
        val joined = BoardRules.join(current, months)
        assertEquals(listOf("fra-rsa", "ita-arg", "eng-nzl"), joined.map { it.id })
        assertEquals(FixtureState.FINISHED, joined.first().state)
    }
}
