package tv.own.owntv.features.more

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Playback log page's lines (v2.5.2): the Channel changes filter, the order, and the redaction. */
class PlaybackLogLinesTest {
    private val zap = "10-21 20:01:02.003 zap: tune wait 0ms — isolated press"
    private val route = "10-21 20:01:02.010 engine: tune 'BBC One' -> exoplayer [AUTO]"
    private val frame = "10-21 20:01:02.900 first frame after 850ms (zap key press to first frame 897ms)"
    private val mpv = "10-21 20:01:03.000 mpv live first position after 700ms (file loaded 300ms, video 650ms)"
    private val timeshift = "10-21 20:01:04.000 engine: timeshift: 'BBC One' plays from its saved copy"
    private val noise = "10-21 20:01:05.000 stallWatchdog armed (8000ms)"
    private val noise2 = "10-21 20:01:06.000 setMuted engine=1 muted=false"

    @Test
    fun theChannelChangeFilterKeepsOnlyZapTimingLines() {
        for (line in listOf(zap, route, frame, mpv, timeshift)) assertTrue(line, PlaybackLogLines.isChannelChange(line))
        for (line in listOf(noise, noise2)) assertFalse(line, PlaybackLogLines.isChannelChange(line))
    }

    @Test
    fun prepareFiltersAndPutsTheNewestFirst() {
        val raw = listOf(zap, noise, route, "", frame, noise2)
        assertEquals(listOf(frame, route, zap), PlaybackLogLines.prepare(raw, channelChangesOnly = true))
        assertEquals(listOf(noise2, frame, route, noise, zap), PlaybackLogLines.prepare(raw, channelChangesOnly = false))
    }

    @Test
    fun prepareKeepsOnlyTheNewestLines() {
        val raw = (1..500).map { "line $it" }
        val shown = PlaybackLogLines.prepare(raw, channelChangesOnly = false)
        assertEquals(PlaybackLogLines.MAX_LINES, shown.size)
        assertEquals("line 500", shown.first())
        assertEquals("line 201", shown.last())
    }

    @Test
    fun redactDropsStreamUrlsAndCredentials() {
        val xtream = "play() engine=1 url=http://panel.example:8080/live/alice/s3cret/123.ts muted=false"
        val shown = PlaybackLogLines.redact(xtream)
        assertFalse(shown, shown.contains("alice"))
        assertFalse(shown, shown.contains("s3cret"))
        assertFalse(shown, shown.contains("panel.example"))
        assertEquals("play() engine=1 url=http://••• muted=false", shown)

        val query = PlaybackLogLines.redact("reconnect re-resolved expiring URL (https://cdn.example/x.m3u8?token=abc&username=bob)")
        assertEquals("reconnect re-resolved expiring URL (https://•••)", query)

        val userinfo = PlaybackLogLines.redact("proxy rtmp://joe:pw@host/app")
        assertEquals("proxy rtmp://•••", userinfo)

        val bare = PlaybackLogLines.redact("retry with password=hunter2&user=joe")
        assertFalse(bare, bare.contains("hunter2"))
        assertFalse(bare, bare.contains("joe"))
    }

    @Test
    fun redactLeavesOrdinaryLinesAlone() {
        assertEquals(frame, PlaybackLogLines.redact(frame))
        assertEquals(route, PlaybackLogLines.redact(route))
    }

    @Test
    fun splitSeparatesTheTimeStamp() {
        assertEquals("10-21 20:01:02.900" to "first frame after 850ms (zap key press to first frame 897ms)", PlaybackLogLines.split(frame))
        assertEquals(null to "no stamp here", PlaybackLogLines.split("no stamp here"))
    }

    @Test
    fun theSyncCheckFilterKeepsOnlyChangeCheckLines() {
        val check = "10-25 16:31:00.000 sync-check: playlist #3 DPTV-Server 1.0.4 changed=live → sync live"
        val raw = listOf(zap, check, noise)
        assertTrue(PlaybackLogLines.isSyncCheck(check))
        assertFalse(PlaybackLogLines.isSyncCheck(zap))
        assertEquals(listOf(check), PlaybackLogLines.prepare(raw, channelChangesOnly = false, syncChecksOnly = true))
        assertEquals(listOf(check, zap), PlaybackLogLines.prepare(raw, channelChangesOnly = true, syncChecksOnly = true))
    }
}
