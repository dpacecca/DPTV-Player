package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/** (DPTV v2.9.18) DPTV-Server's versions of a channel, as the player reads and plays them. */
class ServerVersionRulesTest {
    private val main = ChannelEntity(id = 42, sourceId = 1, name = "Sky Sports F1", streamUrl = "http://dptv:8000/live/alice/pw/100.ts", remoteId = "100")

    private val json = """[
        {"stream_id": 100, "name": "UK: Sky Sports F1", "height": 720, "fps": 25, "ok": true, "reason": "720p 25 fps", "checked_at": 1, "active": true, "main": true},
        {"stream_id": 205, "name": "Sky Sports F1 4K", "height": 2160, "fps": 50, "ok": true, "reason": "2160p 50 fps", "checked_at": 1, "active": false, "main": false},
        {"stream_id": 206, "name": "Sky Sports F1 FHD", "height": null, "fps": null, "ok": null, "reason": null, "checked_at": null, "active": false, "main": false},
        {"stream_id": 207, "name": "Sky Sports F1 HD", "height": 1080, "fps": 25, "ok": false, "reason": "black for 20 of 20 s", "checked_at": 1, "active": false, "main": false}
    ]"""

    @Test fun `reads the server's versions`() {
        val list = ServerVersionRules.parse(json)
        assertEquals(listOf("100", "205", "206", "207"), list.map { it.streamId })
        assertEquals(false, list[3].ok)
        assertNull(list[2].ok)
        assertTrue(list[0].main && list[0].active)
        assertEquals(emptyList<ServerVersion>(), ServerVersionRules.parse("not json"))
    }

    @Test fun `a version plays as the same channel on its own stream`() {
        val v = ServerVersionRules.parse(json)
        assertSame(main, ServerVersionRules.entityFor(main, v[0]))
        val other = ServerVersionRules.entityFor(main, v[1])!!
        assertEquals("http://dptv:8000/live/alice/pw/205.ts", other.streamUrl)
        assertEquals("205", other.remoteId)
        assertEquals("Sky Sports F1", other.name)
        assertTrue(ServerVersionRules.isSynthetic(other.id))
        assertEquals("http://h/live/u/p/9.m3u8?x=1", ServerVersionRules.urlFor("http://h/live/u/p/7.m3u8?x=1", "9"))
    }

    @Test fun `a stall moves to a working version not tried, never a failed one`() {
        val v = ServerVersionRules.parse(json)
        assertEquals("205", ServerVersionRules.nextAfterStall(v, setOf("100"))?.streamId)
        assertEquals("206", ServerVersionRules.nextAfterStall(v, setOf("100", "205"))?.streamId, "unchecked before failed")
        assertNull("the black one is never picked", ServerVersionRules.nextAfterStall(v, setOf("100", "205", "206")))
    }

    private fun assertEquals(expected: Any?, actual: Any?, message: String) = org.junit.Assert.assertEquals(message, expected, actual)
}
