package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The docked categories' moves (v2.5.1, v2.5.2): where each key sends focus and whether the column is wide. */
class GuideDockNavTest {
    private val slim = GuideDock()
    private val wide = GuideDock(expanded = true)
    private val slimFromDock = GuideDock(railFromDock = true)
    private val wideFromDock = GuideDock(expanded = true, railFromDock = true)

    @Test
    fun leftAtNowInTheGuideWidensAndFocusesTheCategories() {
        assertEquals(GuideDockStep(wide, GuideDockFocus.CATEGORIES), slim.on(GuideDockEvent.OPEN))
    }

    // v2.5.2: the categories stay wide while the menu is open beside them.
    @Test
    fun leftInTheCategoriesGoesToTheMenuAndStaysWide() {
        val step = wide.on(GuideDockEvent.LEFT)
        assertEquals(GuideDockFocus.RAIL, step.focus)
        assertTrue(step.dock.expanded)
        assertTrue(step.dock.railFromDock)
    }

    @Test
    fun rightOnTheMenuReachedFromTheCategoriesGoesBackIntoThem() {
        assertEquals(GuideDockStep(wide, GuideDockFocus.CATEGORIES), wideFromDock.on(GuideDockEvent.RAIL_RIGHT))
        assertEquals(GuideDockStep(wide, GuideDockFocus.CATEGORIES), slimFromDock.on(GuideDockEvent.RAIL_RIGHT))
    }

    @Test
    fun goingBackAndForthBetweenTheMenuAndTheCategoriesNeverNarrows() {
        var dock = wide
        repeat(3) {
            dock = dock.on(GuideDockEvent.LEFT).dock
            assertTrue(dock.expanded)
            dock = dock.on(GuideDockEvent.FOCUS_LEFT).dock
            assertTrue(dock.expanded)
            dock = dock.on(GuideDockEvent.RAIL_RIGHT).dock
            assertTrue(dock.expanded)
        }
    }

    @Test
    fun rightOnTheMenuReachedAnotherWayGoesToTheGuide() {
        assertEquals(GuideDockStep(slim, GuideDockFocus.GRID), slim.on(GuideDockEvent.RAIL_RIGHT))
    }

    @Test
    fun rightBackSwipeAndPickInTheCategoriesNarrowAndReturnToTheGuide() {
        for (ev in listOf(GuideDockEvent.RIGHT, GuideDockEvent.BACK, GuideDockEvent.SWIPE_LEFT, GuideDockEvent.PICKED)) {
            assertEquals(ev.name, GuideDockStep(slim, GuideDockFocus.GRID), wide.on(ev))
        }
    }

    @Test
    fun movesOutOfTheCategoriesDoNothingWhileTheyAreSlim() {
        for (ev in listOf(GuideDockEvent.LEFT, GuideDockEvent.RIGHT, GuideDockEvent.BACK, GuideDockEvent.SWIPE_LEFT, GuideDockEvent.PICKED)) {
            assertEquals(ev.name, GuideDockStep(slim, GuideDockFocus.NONE), slim.on(ev))
        }
    }

    @Test
    fun focusReachingTheGuideNarrowsWithoutMovingFocus() {
        assertEquals(GuideDockStep(slim, GuideDockFocus.NONE), wide.on(GuideDockEvent.GRID_FOCUSED))
        assertEquals(GuideDockStep(slim, GuideDockFocus.NONE), wideFromDock.on(GuideDockEvent.GRID_FOCUSED))
        assertEquals(GuideDockStep(slim, GuideDockFocus.NONE), slimFromDock.on(GuideDockEvent.GRID_FOCUSED))
    }

    @Test
    fun focusLeavingTheWideColumnForTheMenuKeepsItWide() {
        assertEquals(GuideDockStep(wideFromDock, GuideDockFocus.NONE), wide.on(GuideDockEvent.FOCUS_LEFT))
        assertEquals(GuideDockStep(wideFromDock, GuideDockFocus.NONE), wideFromDock.on(GuideDockEvent.FOCUS_LEFT))
        assertEquals(GuideDockStep(slim, GuideDockFocus.NONE), slim.on(GuideDockEvent.FOCUS_LEFT))
    }

    @Test
    fun rightBackSwipeAndPickFromTheCategoriesReachedFromTheMenuNarrow() {
        for (ev in listOf(GuideDockEvent.RIGHT, GuideDockEvent.BACK, GuideDockEvent.SWIPE_LEFT, GuideDockEvent.PICKED)) {
            assertEquals(ev.name, GuideDockStep(slim, GuideDockFocus.GRID), wideFromDock.on(ev))
        }
    }

    @Test
    fun aRoundTripGuideCategoriesMenuCategoriesGuide() {
        var dock = slim
        val focus = mutableListOf<GuideDockFocus>()
        for (ev in listOf(GuideDockEvent.OPEN, GuideDockEvent.LEFT, GuideDockEvent.RAIL_RIGHT, GuideDockEvent.RIGHT)) {
            val step = dock.on(ev)
            focus += step.focus
            dock = step.dock
        }
        assertEquals(listOf(GuideDockFocus.CATEGORIES, GuideDockFocus.RAIL, GuideDockFocus.CATEGORIES, GuideDockFocus.GRID), focus)
        assertEquals(slim, dock)
    }
}
