package tv.own.owntv.features.sport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round/stage, week and note from ESPN's scoreboard (v5.2 port), against the shapes of live answers
 * from 2026-10-18 (trimmed to the fields read).
 */
class EspnStageTest {

    private fun stage(event: String, competition: String, nested: Boolean = false) =
        EspnApi.stageOf(JSONObject(event), JSONObject(competition), nested, ", ")

    @Test
    fun aCupGameTakesItsRoundFromTheAltGameNote() {
        val s = stage(
            """{"season":{"slug":"second-round"}}""",
            """{"altGameNote":"German Cup, Second Round","notes":[]}""",
        )
        assertEquals("Second Round", s.round)
        assertNull(s.week)
        assertNull(s.note)
    }

    @Test
    fun aLeagueMatchdayHasNoRound() {
        val s = stage(
            """{"season":{"slug":"2026-27-german-bundesliga"}}""",
            """{"altGameNote":"Bundesliga","notes":[]}""",
        )
        assertNull(s.round)
    }

    @Test
    fun theSeasonSlugIsTheFallback() {
        assertEquals("League Phase", stage("""{"season":{"slug":"league-phase"}}""", "{}").round)
        assertNull(stage("""{"season":{"slug":"regular-season"}}""", "{}").round)
    }

    @Test
    fun anNflGameCarriesItsWeekAndHeadline() {
        val s = stage(
            """{"week":{"number":6},"season":{"slug":"regular-season"}}""",
            """{"type":{"id":"1","abbreviation":"STD"},"notes":[{"type":"event","headline":"NFL London Games"}]}""",
        )
        assertNull(s.round)
        assertEquals(6, s.week)
        assertEquals("NFL London Games", s.note)
    }

    @Test
    fun aTennisMatchJoinsDrawAndRoundAndSkipsTheResultNote() {
        val s = stage(
            """{"season":{"year":2026,"type":2}}""",
            """{"round":{"id":"11","displayName":"Qualifying 1st Round"},"type":{"id":"1","text":"Men's Singles"},
               "notes":[{"text":"A bt B 6-3 6-4","type":"event"}]}""",
            nested = true,
        )
        assertEquals("Men's Singles, Qualifying 1st Round", s.round)
        assertNull(s.note)
    }
}
