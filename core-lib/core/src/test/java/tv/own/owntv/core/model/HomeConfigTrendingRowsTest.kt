package tv.own.owntv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Home's rows since v2.4.0: no Keep watching or Now trending, and the Trending movies / shows rows under Recent channels. */
class HomeConfigTrendingRowsTest {

    @Test
    fun `the Trending rows follow Recent channels, the retired rows are gone`() {
        val order = HomeConfig(hidden = emptySet()).visibleOrder
        assertFalse(HomeRow.TRENDING in order)
        assertFalse(HomeRow.HERO in order)
        val recent = order.indexOf(HomeRow.RECENT_CHANNELS)
        assertEquals(listOf(HomeRow.TRENDING_MOVIES, HomeRow.TRENDING_SHOWS), order.subList(recent + 1, recent + 3))
    }

    @Test
    fun `they keep Recent channels' place while it is hidden, and follow it when moved`() {
        // The default hides Recent channels.
        val defaults = HomeConfig().visibleOrder
        assertEquals(listOf(HomeRow.TRENDING_MOVIES, HomeRow.TRENDING_SHOWS, HomeRow.FAVORITE_CHANNELS), defaults.take(3))

        val moved = HomeConfig(
            order = listOf(HomeRow.FAVORITE_MOVIES, HomeRow.RECENT_CHANNELS, HomeRow.FAVORITE_CHANNELS),
            hidden = emptySet(),
        ).visibleOrder
        assertEquals(listOf(HomeRow.FAVORITE_MOVIES, HomeRow.RECENT_CHANNELS, HomeRow.TRENDING_MOVIES, HomeRow.TRENDING_SHOWS), moved.take(4))
    }

    @Test
    fun `settings list neither the retired nor the Trending rows`() {
        val rows = HomeConfig().settingsRows
        listOf(HomeRow.TRENDING, HomeRow.HERO, HomeRow.TRENDING_MOVIES, HomeRow.TRENDING_SHOWS).forEach { assertFalse(it.name, it in rows) }
    }

    @Test
    fun `a saved config from before v2_4_0 still reads`() {
        val old = """{"order":["TRENDING","HERO","RECENT_CHANNELS","FAVORITE_CHANNELS"],"hidden":[]}"""
        val order = HomeConfig.fromJson(old).visibleOrder
        assertEquals(listOf(HomeRow.RECENT_CHANNELS, HomeRow.TRENDING_MOVIES, HomeRow.TRENDING_SHOWS, HomeRow.FAVORITE_CHANNELS), order.take(4))
    }

    @Test
    fun `the featured titles are on by default, also for a config saved before v2_6_0`() {
        assertEquals(true, HomeConfig().featuredHero)
        assertEquals(true, HomeConfig.fromJson("""{"order":["RECENT_CHANNELS"],"hidden":[]}""").featuredHero)
        assertEquals(false, HomeConfig.fromJson(HomeConfig(featuredHero = false).toJson().toString()).featuredHero)
    }
}
