package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The docked categories' moves (v2.5.1): where each key sends focus and whether the column is wide. */
class GuideDockNavTest {
    private val slim = GuideDock()
    private val wide = GuideDock(expanded = true)
    private val slimFromDock = GuideDock(railFromDock = true)

    @Test
    fun leftAtNowInTheGuideWidensAndFocusesTheCategories() {
        assertEquals(GuideDockStep(wide, GuideDockFocus.CATEGORIES), slim.on(GuideDockEvent.OPEN))
    }

    @Test
    fun leftInTheCategoriesGoesToTheMenuAndNarrows() {
        val step = wide.on(GuideDockEvent.LEFT)
        assertEquals(GuideDockFocus.RAIL, step.focus)
        assertFalse(step.dock.expanded)
        assertTrue(step.dock.railFromDock)
    }

    @Test
    fun rightOnTheMenuReachedFromTheCategoriesGoesBackIntoThem() {
        assertEquals(GuideDockStep(wide, GuideDockFocus.CATEGORIES), slimFromDock.on(GuideDockEvent.RAIL_RIGHT))
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
        assertEquals(GuideDockStep(slim, GuideDockFocus.NONE), slimFromDock.on(GuideDockEvent.GRID_FOCUSED))
    }

    @Test
    fun focusLeavingTheWideColumnOnlyNarrowsIt() {
        val step = GuideDock(expanded = true, railFromDock = true).on(GuideDockEvent.FOCUS_LEFT)
        assertEquals(GuideDockStep(slimFromDock, GuideDockFocus.NONE), step)
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
