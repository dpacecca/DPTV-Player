package tv.own.owntv.core.trakt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** (DPTV v2.9.1) Trakt's JSON and the sync's rules. */
class TraktParseTest {

    @Test
    fun deviceCodeAndTokens() {
        val code = TraktParse.deviceCode("""{"device_code":"d","user_code":"ABCD1234","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}""")!!
        assertEquals("ABCD1234", code.userCode)
        assertEquals(5, code.intervalSec)
        assertNull(TraktParse.deviceCode("""{"user_code":"x"}"""))
        val t = TraktParse.tokens("""{"access_token":"a","refresh_token":"r","expires_in":7776000,"created_at":1000}""", 0)!!
        assertEquals("a", t.accessToken)
        assertEquals((1000L + 7776000L) * 1000L, t.expiresAtMs)
    }

    @Test
    fun watchedFilmsAndEpisodes() {
        val movies = """[{"movie":{"title":"A","ids":{"trakt":1,"tmdb":603,"imdb":"tt0133093"}}}]"""
        val shows = """[{"show":{"ids":{"tmdb":1399,"tvdb":121361}},"seasons":[{"number":1,"episodes":[{"number":1},{"number":2}]}]}]"""
        val w = TraktParse.watched(movies, shows)
        assertTrue(603 in w.movieTmdbIds)
        assertTrue("tt0133093" in w.movieImdbIds)
        assertEquals(setOf(1 to 1, 1 to 2), w.showEpisodes[1399])
        assertEquals(setOf(1 to 1, 1 to 2), TraktSyncRules.showEpisodes(w, null, 121361))
        assertTrue(TraktSyncRules.movieWatched(w, null, "tt0133093"))
        assertFalse(TraktSyncRules.movieWatched(w, 1, null))
        assertEquals(setOf("m:603", "s:1399"), TraktSyncRules.watchedTmdbKeys(w))
    }

    @Test
    fun watchlistRoundTrips() {
        val body = """[{"listed_at":"2026-10-01T10:00:00.000Z","type":"movie","movie":{"title":"Dune","year":2021,"ids":{"tmdb":438631}}},
            {"listed_at":"2026-10-02T10:00:00.000Z","type":"show","show":{"title":"Severance","year":2022,"ids":{"tmdb":95396}}}]"""
        val list = TraktParse.watchlist(body)
        assertEquals(2, list.size)
        assertTrue(list[1].isShow)
        assertEquals(438631, list[0].ids.tmdb)
        assertEquals(list, TraktParse.watchlist(TraktParse.encodeList(list)))
    }

    @Test
    fun historyBodyGroupsEpisodesByShowAndSeason() {
        val show = TraktIds(tmdb = 1399)
        val body = JSONObject(
            TraktParse.historyBody(
                listOf(TraktIds(tmdb = 603)),
                listOf(TraktEpisodeRef(show, 1, 2), TraktEpisodeRef(show, 1, 1), TraktEpisodeRef(show, 2, 1)),
            ),
        )
        assertEquals(603, body.getJSONArray("movies").getJSONObject(0).getJSONObject("ids").getInt("tmdb"))
        val seasons = body.getJSONArray("shows").getJSONObject(0).getJSONArray("seasons")
        assertEquals(2, seasons.length())
        assertEquals(1, seasons.getJSONObject(0).getJSONArray("episodes").getJSONObject(0).getInt("number"))
        assertNotNull(seasons.getJSONObject(1))
    }

    @Test
    fun aVodTitleCountsAsWatchedFromNinetyPercent() {
        assertTrue(TraktSyncRules.isWatched(1, 1))
        assertTrue(TraktSyncRules.isWatched(90, 100))
        assertFalse(TraktSyncRules.isWatched(89, 100))
        assertFalse(TraktSyncRules.isWatched(5, 0))
    }
}
