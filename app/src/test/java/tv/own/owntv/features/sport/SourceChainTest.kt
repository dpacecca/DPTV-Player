package tv.own.owntv.features.sport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The SofaScore → TheSportsDB decision (v2.6.0). */
class SourceChainTest {

    private fun league(fallbackId: String?) = SportLeague(
        id = "sofascore.431", espnSport = "rugby-league", espnLeague = "431", labelRes = 0,
        region = LeagueRegion.INTERNATIONAL, kind = LeagueKind.TEAMS, source = LeagueSource.SOFASCORE,
        theSportsDbId = fallbackId,
    )

    private val sofa = EspnApi.Scoreboard("sofa", emptyList())
    private val tsdb = EspnApi.Scoreboard("tsdb", emptyList())
    private var now = 0L
    private val gate = SofaScoreGate(pauseMs = 1_000L, clock = { now })
    private val log = ArrayList<String>()
    private var primaryCalls = 0

    private suspend fun run(league: SportLeague, primaryFails: Boolean, fallbackFails: Boolean = false) =
        SourceChain.scoreboard(
            league, gate, { log.add(it) },
            primary = {
                primaryCalls++
                if (primaryFails) throw java.io.IOException("SofaScore HTTP 403") else sofa
            },
            fallback = { if (fallbackFails) throw java.io.IOException("TheSportsDB HTTP 429") else tsdb },
        )

    @Test
    fun sofaScoreServesWhileItAnswers() = runBlocking {
        assertEquals("sofa", run(league("5806"), primaryFails = false).leagueName)
        assertTrue(gate.open())
        assertTrue(log.isEmpty())
    }

    @Test
    fun a403FallsBackAndRestsSofaScore() = runBlocking {
        assertEquals("tsdb", run(league("5806"), primaryFails = true).leagueName)
        assertFalse(gate.open())
        assertEquals(1, log.size)
        assertTrue(log[0].startsWith("sofascore failed sofascore.431: SofaScore HTTP 403"))
        // While it rests, SofaScore is not asked at all.
        assertEquals("tsdb", run(league("5806"), primaryFails = true).leagueName)
        assertEquals(1, primaryCalls)
        // After the pause it is tried again.
        now = 1_000L
        assertEquals("sofa", run(league("5806"), primaryFails = false).leagueName)
        assertEquals(2, primaryCalls)
    }

    @Test
    fun aLeagueWithoutFallbackIsUnavailable() = runBlocking {
        try {
            run(league(null), primaryFails = true)
            fail("expected SourceUnavailableException")
        } catch (_: SourceUnavailableException) {
        }
        assertTrue(log[0].contains("no fallback"))
    }

    @Test
    fun aFailedFallbackIsUnavailableAndLogged() = runBlocking {
        try {
            run(league("5806"), primaryFails = true, fallbackFails = true)
            fail("expected SourceUnavailableException")
        } catch (_: SourceUnavailableException) {
        }
        assertEquals(2, log.size)
        assertTrue(log[1].startsWith("thesportsdb failed sofascore.431: TheSportsDB HTTP 429"))
    }
}
