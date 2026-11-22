package tv.own.owntv.core.parser

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.content.AirDate
import tv.own.owntv.core.network.HttpClient

/**
 * `get_series_info` bodies that carry an episode's air date more than once. The reader used to skip
 * the second date key unread once the first had matched, and then failed the whole show with
 * "Expected a name but was STRING". Runs on the real reader (see the test-only android.util.JsonReader).
 */
class XtreamSeriesInfoParseTest {

    private val client = XtreamClient(HttpClient(OkHttpClient()))

    private fun parse(json: String) = client.readSeriesInfoEpisodes(json.byteInputStream())

    private fun day(text: String) = AirDate.parse(text)

    @Test
    fun `dispatcharr body with air_date and release_date inside info parses every episode`() {
        val body = javaClass.getResourceAsStream("/xtream/series_info_dispatcharr.json")!!
        val episodes = client.readSeriesInfoEpisodes(body)
        assertEquals(listOf("788", "789", "798", "799"), episodes.map { it.id })
        assertEquals(listOf(1, 1, 2, 2), episodes.map { it.seasonNumber })
        assertEquals(listOf(1, 2, 1, 2), episodes.map { it.episodeNumber })
        assertEquals(
            listOf(day("2018-08-17"), day("2018-08-17"), day("2021-01-15"), day("2021-01-15")),
            episodes.map { it.airDateMs },
        )
    }

    @Test
    fun `direct date then info keeps the direct date and reads past info`() {
        val episodes = parse(
            """{"episodes":{"1":[
                {"id":"1","episode_num":1,"release_date":"2020-01-01","info":{"air_date":"2021-02-02"},"title":"A"},
                {"id":"2","episode_num":2,"title":"B"}
            ]}}""",
        )
        assertEquals(listOf("1", "2"), episodes.map { it.id })
        assertEquals(day("2020-01-01"), episodes[0].airDateMs)
        assertEquals("A", episodes[0].title)
    }

    @Test
    fun `info then direct date keeps the info date`() {
        val episodes = parse(
            """{"episodes":{"1":[
                {"id":"1","info":{"release_date":"2021-02-02"},"air_date":"2020-01-01","episode_num":3}
            ]}}""",
        )
        assertEquals(1, episodes.size)
        assertEquals(day("2021-02-02"), episodes[0].airDateMs)
        assertEquals(3, episodes[0].episodeNumber)
    }

    @Test
    fun `two direct dates keep the first and read the second`() {
        val episodes = parse(
            """{"episodes":{"1":[
                {"id":"1","air_date":"2020-01-01","releasedate":"2022-03-03","episode_num":4,"title":"A"}
            ]}}""",
        )
        assertEquals(1, episodes.size)
        assertEquals(day("2020-01-01"), episodes[0].airDateMs)
        assertEquals(4, episodes[0].episodeNumber)
        assertEquals("A", episodes[0].title)
    }

    @Test
    fun `a first date that does not parse lets the second one in`() {
        val episodes = parse(
            """{"episodes":{"1":[{"id":"1","air_date":"0000-00-00","release_date":"2022-03-03"}]}}""",
        )
        assertEquals(day("2022-03-03"), episodes.single().airDateMs)
    }

    @Test
    fun `a fractional or oversized episode number reads as none instead of failing the show`() {
        val episodes = parse(
            """{"episodes":{"1":[
                {"id":"1","episode_num":1.5},
                {"id":"2","episode_num":99999999999},
                {"id":"3","episode_num":"7.0"},
                {"id":"4","episode_num":8.0}
            ]}}""",
        )
        assertEquals(listOf("1", "2", "3", "4"), episodes.map { it.id })
        assertEquals(listOf(0, 0, 7, 8), episodes.map { it.episodeNumber })
    }

    @Test
    fun `an episode's synopsis and running time come from its info, and both plot keys are read past`() {
        val episodes = parse(
            """{"episodes":{"1":[
                {"id":"1","episode_num":1,"plot":"Top level.","description":"Also top level.",
                 "info":{"plot":"  Michael freaks out.  ","overview":"Second","duration_secs":1320,"duration":"00:30:00"}},
                {"id":"2","episode_num":2,"info":{"duration":"01:02:03","description":"From description"}},
                {"id":"3","episode_num":3,"plot":"Only top level","info":{"plot":"","duration":"bad"}},
                {"id":"4","episode_num":4,"info":[]}
            ]}}""",
        )
        assertEquals(listOf("1", "2", "3", "4"), episodes.map { it.id })
        assertEquals(listOf("Michael freaks out.", "From description", "Only top level", null), episodes.map { it.plot })
        assertEquals(listOf(1320, 3723, null, null), episodes.map { it.durationSecs })
    }

    @Test
    fun `a show's info and seasons are read beside its episodes`() {
        val info = client.readSeriesInfo(
            """{"seasons":[
                  {"season_number":1,"name":"Season One","overview":" The start. ","cover_big":"http://p/s1.jpg"},
                  {"season_number":"2","name":"","cover":"not a link"},
                  "junk"
                ],
                "info":{"name":"Show","genre":"Drama, Crime / Drama","cast":"A One, B Two","director":"D",
                        "backdrop_path":["http://p/b1.jpg","http://p/b2.jpg"],"youtube_trailer":"https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                        "episode_run_time":"45","releaseDate":"2019-04-14","plot":"About it."},
                "episodes":{"1":[{"id":"1","episode_num":1}]}}""".byteInputStream(),
        )
        assertEquals(listOf("1"), info.episodes.map { it.id })
        val d = info.details!!
        assertEquals(listOf("Drama", "Crime"), d.genres)
        assertEquals(listOf("A One", "B Two"), d.cast)
        assertEquals("D", d.director)
        assertEquals("http://p/b1.jpg", d.backdropUrl)
        assertEquals("dQw4w9WgXcQ", d.trailerKey)
        assertEquals(45 * 60, d.runtimeSecs)
        assertEquals(2019, d.year)
        assertEquals("About it.", d.plot)
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals(XtSeasonInfo(1, "Season One", "The start.", "http://p/s1.jpg"), info.seasons[0])
        assertEquals(XtSeasonInfo(2, null, null, null), info.seasons[1])
    }

    @Test
    fun `a film's info reads its details, and an empty one reads as none`() {
        val d = client.readVodInfo(
            """{"info":{"genre":"Action","actors":"X","duration_secs":5400,"duration":"01:00:00",
                        "youtube_trailer":"dQw4w9WgXcQ","backdrop_path":"http://p/b.jpg","releasedate":"1999"},
                "movie_data":{"stream_id":1}}""".byteInputStream(),
        )!!
        assertEquals(listOf("Action"), d.genres)
        assertEquals(listOf("X"), d.cast)
        assertEquals(5400, d.runtimeSecs)
        assertEquals("dQw4w9WgXcQ", d.trailerKey)
        assertEquals("http://p/b.jpg", d.backdropUrl)
        assertEquals(1999, d.year)
        assertEquals(null, client.readVodInfo("""{"info":{"genre":"","youtube_trailer":""}}""".byteInputStream()))
        assertEquals(null, client.readVodInfo("""{"info":[]}""".byteInputStream()))
        assertEquals("abcdefghijk", client.youtubeId("https://youtu.be/abcdefghijk?t=1"))
        assertEquals(null, client.youtubeId("not a trailer"))
    }
}
