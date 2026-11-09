package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.jellyfin.JellyfinItem
import tv.own.owntv.core.jellyfin.JellyfinKind

/** (DPTV v2.9.1) Continue watching with VOD and Jellyfin in one row. */
class HomeExtrasRulesTest {

    private fun vod(id: Long, at: Long, episodes: Boolean = false) =
        HomeExtrasRules.vodCard("m$id", "VOD $id", null, 50, 100, at, id, id + 1000, episodes)

    private fun jf(id: String, at: Long) = HomeCard(
        key = "jf:$id", title = id, posterUrl = null, lastWatchedMs = at,
        target = HomeCardTarget.JellyfinPlay(JellyfinItem(id, id, JellyfinKind.MOVIE), 0),
    )

    @Test
    fun bothKindsMixMostRecentFirst() {
        val cards = HomeExtrasRules.merge(listOf(vod(1, 300), vod(2, 100)), listOf(jf("x", 200)))
        assertEquals(listOf("vod:m1", "jf:x", "vod:m2"), cards.map { it.key })
        assertEquals(0.5f, cards.first().progress)
        assertEquals(HomeCardTarget.VodMovie(1, 50), cards.first().target)
    }

    @Test
    fun withoutJellyfinTheOwnOrderStaysAndEpisodesPlayTheEpisode() {
        val own = listOf(vod(2, 100, episodes = true), vod(1, 300, episodes = true))
        assertEquals(own, HomeExtrasRules.merge(own, emptyList()))
        assertEquals(HomeCardTarget.VodEpisode(1002, 50), own.first().target)
    }

    // (DPTV v2.9.11) Continue watching keeps the last week, and what was taken off stays off until watched again.
    @Test
    fun continueShowsTheLastWeekOnly() {
        val day = 24L * 60L * 60L * 1000L
        val now = 100L * day
        val cards = listOf(vod(1, now - day), vod(2, now - 8 * day), jf("x", now - 7 * day), jf("unknown", 0))
        assertEquals(listOf("vod:m1", "jf:x", "jf:unknown"), HomeExtrasRules.continueShown(cards, now, emptyMap()).map { it.key })
    }

    @Test
    fun aRemovedCardStaysOffUntilWatchedAgain() {
        val now = 1_000_000L
        val removed = mapOf("vod:m1" to now - 500)
        assertEquals(emptyList<String>(), HomeExtrasRules.continueShown(listOf(vod(1, now - 500)), now, removed).map { it.key })
        assertEquals(listOf("vod:m1"), HomeExtrasRules.continueShown(listOf(vod(1, now - 100)), now, removed).map { it.key })
    }
}
