package tv.own.owntv.core.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.jellyfin.JellyfinItem
import tv.own.owntv.core.jellyfin.JellyfinKind
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeRow

/** (DPTV v2.9.1) Recommendations and the Home row order. */
class RecommendationRulesTest {

    private fun c(id: Int, title: String = "T$id", year: Int? = 2020) =
        TrendingCandidate(id, MetadataType.MOVIE, title, null, year, null, null, null, null, 0.0, 1)

    @Test
    fun titlesInMoreListsAndHigherUpComeFirstAndWatchedAreLeftOut() {
        val ranked = RecommendationRules.rank(
            listOf(listOf(c(1), c(2), c(3)), listOf(c(3), c(4)), listOf(c(5), c(3))),
            exclude = setOf(5),
        )
        assertEquals(listOf(3, 1, 2, 4), ranked.map { it.tmdbId })
    }

    @Test
    fun aJellyfinTitleIsFoundByTmdbIdOrByNameAndYear() {
        val byId = JellyfinItem("a", "Whatever", JellyfinKind.MOVIE, year = 2020, tmdbId = 7)
        val byName = JellyfinItem("b", "The Matrix", JellyfinKind.MOVIE, year = 1999)
        val idx = mapOf(7 to byId)
        val names = listOf(byName).groupBy { RecommendationRules.nameKey(it.name) }
        assertEquals("a", RecommendationRules.findInJellyfin(c(7), idx, names)?.id)
        assertEquals("b", RecommendationRules.findInJellyfin(c(9, "The Matrix", 2000), idx, names)?.id)
        assertNull(RecommendationRules.findInJellyfin(c(9, "The Matrix", 2003), idx, names))
    }

    @Test
    fun anOldOrderKeepsTrendingUnderRecentChannelsAndEveryRowCanMove() {
        val old = """{"order":["RECENT_CHANNELS","FAVORITE_CHANNELS","CONTINUE_MOVIES","TRENDING_MOVIES","TRENDING_SHOWS"],"hidden":[]}"""
        val config = HomeConfig.fromJson(old)
        val visible = config.visibleOrder
        assertEquals(HomeRow.TRENDING_MOVIES, visible[visible.indexOf(HomeRow.RECENT_CHANNELS) + 1])
        // Saved again, the order is kept as it is: Trending can now be moved like any row.
        val moved = config.copy(order = config.order.filter { it != HomeRow.TRENDING_SHOWS } + HomeRow.TRENDING_SHOWS)
        assertEquals(HomeRow.TRENDING_SHOWS, HomeConfig.fromJson(moved.toJson().toString()).order.last())
        assert(HomeRow.TRENDING_SHOWS in moved.settingsRows)
        assert(HomeRow.RECOMMENDED_MOVIES in HomeConfig().settingsRows)
    }
}
