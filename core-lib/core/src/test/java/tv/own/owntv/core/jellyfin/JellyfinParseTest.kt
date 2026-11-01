package tv.own.owntv.core.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinParseTest {

    @Test
    fun signInAnswerBecomesAnAccountOnANormalisedServer() {
        val body = """{"AccessToken":"abc","User":{"Id":"u1","Name":"Damian"}}"""
        val a = JellyfinParse.account("192.168.1.10:8096/", "dev", body)
        assertNotNull(a)
        assertEquals("http://192.168.1.10:8096", a!!.server)
        assertEquals("u1", a.userId)
        assertEquals("Damian", a.userName)
        assertEquals("abc", a.token)
        assertEquals("dev", a.deviceId)
    }

    @Test
    fun signInAnswerWithoutATokenIsRefused() {
        assertNull(JellyfinParse.account("h", "d", """{"User":{"Id":"u1"}}"""))
        assertNull(JellyfinParse.account("h", "d", "not json"))
    }

    @Test
    fun serverAddressKeepsItsSchemeAndLosesTrailingSlashes() {
        assertEquals("https://jf.example.org", JellyfinParse.normalizeServer(" https://jf.example.org// "))
        assertEquals("http://host", JellyfinParse.normalizeServer("host"))
    }

    @Test
    fun anEpisodeCarriesItsProgressSeasonAndSeries() {
        val body = """{
            "Id":"e1","Name":"Pilot","Type":"Episode","RunTimeTicks":36000000000,
            "ParentIndexNumber":1,"IndexNumber":4,"SeriesId":"s1","SeriesName":"Show","SeasonId":"se1",
            "UserData":{"PlaybackPositionTicks":6000000000,"Played":false},
            "ImageTags":{},"SeriesPrimaryImageTag":"spt","CommunityRating":7.84,
            "DateCreated":"2026-10-01T20:15:00.0000000Z"
        }"""
        val e = JellyfinParse.item(body)!!
        assertEquals(JellyfinKind.EPISODE, e.kind)
        assertEquals(3_600_000L, e.runtimeMs)
        assertEquals(600_000L, e.positionMs)
        assertTrue(e.resumable)
        assertEquals(1, e.seasonNumber)
        assertEquals(4, e.episodeNumber)
        assertEquals("s1", e.seriesId)
        assertNull(e.primaryTag)
        assertEquals("spt", e.seriesPrimaryTag)
        assertEquals(7.84, e.rating!!, 0.0001)
        assertEquals(java.time.Instant.parse("2026-10-01T20:15:00Z").toEpochMilli(), e.dateCreatedMs)
    }

    @Test
    fun aSeasonTakesItsNumberFromIndexNumber() {
        val s = JellyfinParse.item("""{"Id":"x","Name":"Season 2","Type":"Season","IndexNumber":2,"UserData":{"UnplayedItemCount":3}}""")!!
        assertEquals(JellyfinKind.SEASON, s.kind)
        assertEquals(2, s.seasonNumber)
        assertNull(s.episodeNumber)
        assertEquals(3, s.unplayed)
    }

    @Test
    fun aWatchedFilmIsNotResumable() {
        val m = JellyfinParse.item("""{"Id":"m","Name":"Film","Type":"Movie","ProductionYear":2024,"Genres":["Drama",""],"UserData":{"Played":true,"PlaybackPositionTicks":10000}}""")!!
        assertEquals(JellyfinKind.MOVIE, m.kind)
        assertEquals(2024, m.year)
        assertEquals(listOf("Drama"), m.genres)
        assertTrue(m.played)
        assertFalse(m.resumable)
    }

    @Test
    fun pagesAndBareArraysSkipItemsWithoutAnId() {
        val page = JellyfinParse.page("""{"Items":[{"Id":"a","Type":"Movie"},{"Name":"no id"}],"TotalRecordCount":250}""")
        assertEquals(listOf("a"), page.items.map { it.id })
        assertEquals(250, page.total)
        assertEquals(listOf("b"), JellyfinParse.array("""[{"Id":"b","Type":"Series"}]""").map { it.id })
        assertEquals(0, JellyfinParse.page("garbage").total)
    }
}
