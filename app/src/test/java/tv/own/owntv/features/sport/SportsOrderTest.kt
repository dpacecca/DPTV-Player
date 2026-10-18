package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Test

/** The user's own sport and league order (v5.2 port: hold OK to move). */
class SportsOrderTest {

    private fun league(id: String, sport: String) = SportLeague(
        id = id, espnSport = sport, espnLeague = id, labelRes = 0,
        region = LeagueRegion.EUROPE, kind = LeagueKind.TEAMS,
    )

    private val bundesliga = league("soccer.ger.1", "soccer")
    private val premier = league("soccer.eng.1", "soccer")
    private val nfl = league("football.nfl", "football")
    private val nba = league("basketball.nba", "basketball")
    private val catalogue = listOf(bundesliga, premier, nfl, nba)

    @Test
    fun encodeAndDecodeRoundTripWithoutBlanksOrDuplicates() {
        val ids = listOf("soccer.ger.1", "football.nfl", "soccer.ger.1")
        assertEquals(listOf("soccer.ger.1", "football.nfl"), SportsOrder.decode(SportsOrder.encode(ids)))
        assertEquals(emptyList<String>(), SportsOrder.decode(null))
        assertEquals(listOf("a", "b"), SportsOrder.decode(" a,,b ,"))
    }

    @Test
    fun noStoredOrderKeepsTheCatalogueGroupedBySport() {
        val ordered = SportsOrder.orderLeagues(listOf(nba, bundesliga, nfl, premier), emptyList(), SportsOrder.orderSports(emptyList()))
        assertEquals(listOf(bundesliga, premier, nfl, nba), ordered)
    }

    @Test
    fun storedLeaguesComeFirstAndTheRestFollowTheSportOrder() {
        val sports = SportsOrder.orderSports(listOf(SportGroup.BASKETBALL.name, SportGroup.AMERICAN_FOOTBALL.name))
        val ordered = SportsOrder.orderLeagues(catalogue, listOf("soccer.eng.1"), sports)
        assertEquals(listOf(premier, nba, nfl, bundesliga), ordered)
    }

    @Test
    fun orderSportsListsEverySportOnceAndIgnoresUnknownNames() {
        val sports = SportsOrder.orderSports(listOf("TENNIS", "NOT_A_SPORT", "TENNIS", "SOCCER"))
        assertEquals(SportGroup.TENNIS, sports[0])
        assertEquals(SportGroup.SOCCER, sports[1])
        assertEquals(SportGroup.entries.size, sports.size)
        assertEquals(SportGroup.entries.toSet(), sports.toSet())
    }

    @Test
    fun moveSwapsWithTheNeighbourAndStopsAtTheEnds() {
        val list = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), SportsOrder.move(list, "b", up = true))
        assertEquals(listOf("a", "c", "b"), SportsOrder.move(list, "b", up = false))
        assertEquals(list, SportsOrder.move(list, "a", up = true))
        assertEquals(list, SportsOrder.move(list, "c", up = false))
        assertEquals(list, SportsOrder.move(list, "x", up = true))
    }

    @Test
    fun mergeKeepsLeaguesThatAreSwitchedOffAfterTheShownOnes() {
        val stored = listOf("x", "a", "y", "b")
        assertEquals(listOf("b", "a", "x", "y"), SportsOrder.merge(listOf("b", "a"), stored))
    }
}
