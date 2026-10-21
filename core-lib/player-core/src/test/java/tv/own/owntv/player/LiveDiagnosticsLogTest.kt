package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Playback log's Clear (v2.5.2): the ring empties. */
class LiveDiagnosticsLogTest {
    @Test
    fun clearEmptiesTheRing() {
        LiveDiagnosticsLog.enabled = false
        LiveDiagnosticsLog.event("first frame after 812ms")
        assertTrue(LiveDiagnosticsLog.snapshot().endsWith("first frame after 812ms"))
        LiveDiagnosticsLog.clear()
        assertEquals("", LiveDiagnosticsLog.snapshot())
    }
}
