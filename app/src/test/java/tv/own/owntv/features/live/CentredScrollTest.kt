package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** The centring lists (v2.6.0): the focused item's centre at the viewport's centre, clamped at the ends. */
class CentredScrollTest {
    private val eps = 0.001f

    @Test
    fun anItemInTheMiddleStaysPut() {
        assertEquals(0f, centredScrollDistance(offset = 450f, size = 100f, containerSize = 1000f), eps)
    }

    @Test
    fun anItemBelowTheMiddleScrollsUpToIt() {
        assertEquals(300f, centredScrollDistance(offset = 750f, size = 100f, containerSize = 1000f), eps)
    }

    @Test
    fun anItemAboveTheMiddleScrollsDownToIt() {
        assertEquals(-350f, centredScrollDistance(offset = 100f, size = 100f, containerSize = 1000f), eps)
    }

    @Test
    fun atTheTopTheListDoesNotRunPastItsFirstRow() {
        // The second row of a list scrolled to its start: it would centre 350 back, but there is nothing.
        assertEquals(0f, centredScrollDistance(offset = 100f, size = 100f, containerSize = 1000f, before = 0f, after = 2_000f), eps)
        // Part way: only as far back as there is.
        assertEquals(-120f, centredScrollDistance(offset = 100f, size = 100f, containerSize = 1000f, before = 120f, after = 2_000f), eps)
    }

    @Test
    fun atTheEndTheListDoesNotRunPastItsLastRow() {
        assertEquals(80f, centredScrollDistance(offset = 850f, size = 100f, containerSize = 1000f, before = 5_000f, after = 80f), eps)
        assertEquals(0f, centredScrollDistance(offset = 850f, size = 100f, containerSize = 1000f, before = 5_000f, after = 0f), eps)
    }

    @Test
    fun anItemTallerThanTheViewportGoesToItsStart() {
        assertEquals(40f, centredScrollDistance(offset = 40f, size = 1_200f, containerSize = 1000f), eps)
    }
}
