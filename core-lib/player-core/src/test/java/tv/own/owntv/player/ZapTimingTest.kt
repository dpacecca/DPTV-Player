package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The CH+/− press → first frame measurement: read once, never charged to a stale press. */
class ZapTimingTest {

    @Test
    fun `a press is measured by the next first frame`() {
        val t = ZapTiming()
        t.markPress(1_000)
        assertEquals(2_400L, t.takeSincePress(3_400))
    }

    @Test
    fun `a press is read only once`() {
        val t = ZapTiming()
        t.markPress(1_000)
        t.takeSincePress(2_000)
        assertNull(t.takeSincePress(2_500))
    }

    @Test
    fun `no press means no measurement`() {
        assertNull(ZapTiming().takeSincePress(5_000))
    }

    @Test
    fun `the newest press wins`() {
        val t = ZapTiming()
        t.markPress(1_000)
        t.markPress(1_170) // key-repeat
        assertEquals(830L, t.takeSincePress(2_000))
    }

    @Test
    fun `a press older than the limit is dropped`() {
        val t = ZapTiming(maxAgeMs = 30_000)
        t.markPress(0)
        assertNull(t.takeSincePress(30_001))
        t.markPress(0)
        assertEquals(30_000L, t.takeSincePress(30_000))
    }

    @Test
    fun `a clock that went backwards is not a measurement`() {
        val t = ZapTiming()
        t.markPress(5_000)
        assertNull(t.takeSincePress(4_000))
    }

    @Test
    fun `the log suffix is empty without a press`() {
        assertEquals("", ZapTiming.pressSuffix(null))
        assertEquals(" (zap key press to first frame 812ms)", ZapTiming.pressSuffix(812))
    }
}
