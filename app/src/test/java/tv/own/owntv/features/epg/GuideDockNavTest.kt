package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's menus (v2.6.0, as TiviMate): where each key sends focus and which state comes next. */
class GuideDockNavTest {
    private fun step(nav: GuideNav, focus: GuideNavFocus) = GuideNavStep(nav, focus)

    @Test
    fun leftAtNowInTheGuideSlidesTheCategoriesIn() {
        assertEquals(step(GuideNav.CATEGORIES, GuideNavFocus.CATEGORIES), GuideNav.GUIDE.on(GuideNavEvent.OPEN))
    }

    @Test
    fun leftInTheCategoriesOpensTheMenuWithTheCategoriesStillOut() {
        val s = GuideNav.CATEGORIES.on(GuideNavEvent.LEFT)
        assertEquals(step(GuideNav.MENU, GuideNavFocus.RAIL), s)
        assertTrue(s.nav.categoriesOpen)
    }

    @Test
    fun rightOnTheMenuFoldsItBackToTheCategories() {
        assertEquals(step(GuideNav.CATEGORIES, GuideNavFocus.CATEGORIES), GuideNav.MENU.on(GuideNavEvent.RAIL_RIGHT))
    }

    @Test
    fun rightOnTheMenuOverTheGuideAloneGoesToTheRow() {
        assertEquals(step(GuideNav.GUIDE, GuideNavFocus.GRID), GuideNav.GUIDE.on(GuideNavEvent.RAIL_RIGHT))
    }

    @Test
    fun rightBackAndSwipeInTheCategoriesReturnToTheRowTheyCameFrom() {
        for (ev in listOf(GuideNavEvent.RIGHT, GuideNavEvent.BACK, GuideNavEvent.SWIPE_LEFT)) {
            assertEquals(ev.name, step(GuideNav.GUIDE, GuideNavFocus.GRID), GuideNav.CATEGORIES.on(ev))
        }
    }

    @Test
    fun okInTheCategoriesGoesToTheFirstRow() {
        assertEquals(step(GuideNav.GUIDE, GuideNavFocus.GRID_FIRST), GuideNav.CATEGORIES.on(GuideNavEvent.PICKED))
    }

    @Test
    fun backOnTheMenuIsLeftToTheShellsExitPrompt() {
        assertEquals(step(GuideNav.MENU, GuideNavFocus.NONE), GuideNav.MENU.on(GuideNavEvent.BACK))
    }

    @Test
    fun movesThatDoNotApplyLeaveTheStateAndFocusAlone() {
        for (ev in listOf(GuideNavEvent.LEFT, GuideNavEvent.RIGHT, GuideNavEvent.BACK, GuideNavEvent.SWIPE_LEFT, GuideNavEvent.PICKED, GuideNavEvent.FOCUS_LEFT)) {
            assertEquals(ev.name, step(GuideNav.GUIDE, GuideNavFocus.NONE), GuideNav.GUIDE.on(ev))
        }
        for (ev in listOf(GuideNavEvent.LEFT, GuideNavEvent.RIGHT, GuideNavEvent.PICKED, GuideNavEvent.FOCUS_LEFT)) {
            assertEquals(ev.name, step(GuideNav.MENU, GuideNavFocus.NONE), GuideNav.MENU.on(ev))
        }
    }

    // --- v2.6.1: ▶ from the menu, the self-heal, and ◀ at now after odd focus routes ---------------------

    @Test
    fun leftAtNowOpensTheCategoriesWhateverStateWasLeftBehind() {
        for (nav in GuideNav.entries) {
            assertEquals(nav.name, step(GuideNav.CATEGORIES, GuideNavFocus.CATEGORIES), nav.on(GuideNavEvent.OPEN))
        }
    }

    @Test
    fun rightFromTheMenuLandsInTheCategoriesWheneverTheyAreOut() {
        assertEquals(step(GuideNav.CATEGORIES, GuideNavFocus.CATEGORIES), GuideNav.MENU.on(GuideNavEvent.RAIL_RIGHT))
        assertEquals(step(GuideNav.CATEGORIES, GuideNavFocus.CATEGORIES), GuideNav.CATEGORIES.on(GuideNavEvent.RAIL_RIGHT))
    }

    @Test
    fun rightOnTheRailGoesToTheCategoriesWhileTheyAreOutOnATv() {
        assertEquals(GuideNavFocus.CATEGORIES, GuideNav.MENU.railEntry(tv = true))
        assertEquals(GuideNavFocus.CATEGORIES, GuideNav.CATEGORIES.railEntry(tv = true))
        assertEquals(GuideNavFocus.GRID, GuideNav.GUIDE.railEntry(tv = true))
        // A phone's sheet is never beside the menu.
        assertEquals(GuideNavFocus.GRID, GuideNav.MENU.railEntry(tv = false))
        // And the route itself: the rail's ▶ in MENU lands in the categories.
        assertEquals(step(GuideNav.CATEGORIES, GuideNavFocus.CATEGORIES), GuideNav.MENU.on(GuideNavEvent.RAIL_RIGHT))
    }

    @Test
    fun focusArrivingInTheGridWhileInTheMenuOrCategoriesIsTheGuide() {
        assertEquals(step(GuideNav.GUIDE, GuideNavFocus.NONE), GuideNav.MENU.on(GuideNavEvent.GRID_FOCUSED))
        assertEquals(step(GuideNav.GUIDE, GuideNavFocus.NONE), GuideNav.CATEGORIES.on(GuideNavEvent.GRID_FOCUSED))
        assertFalse(GuideNav.MENU.on(GuideNavEvent.GRID_FOCUSED).nav.categoriesOpen)
    }

    @Test
    fun theOwnersRouteRightFromTheMenuIntoTheGridThenLeftAtNowReopens() {
        // GUIDE ◀ → CATEGORIES ◀ → MENU ▶ (turned aside into the grid) → healed → ◀ at now → CATEGORIES.
        var nav = GuideNav.GUIDE.on(GuideNavEvent.OPEN).nav
        nav = nav.on(GuideNavEvent.LEFT).nav
        assertEquals(GuideNav.MENU, nav)
        nav = nav.on(GuideNavEvent.RAIL_RIGHT).nav
        assertEquals(GuideNav.CATEGORIES, nav)
        nav = nav.on(GuideNavEvent.GRID_FOCUSED).nav
        assertEquals(GuideNav.GUIDE, nav)
        assertEquals(step(GuideNav.CATEGORIES, GuideNavFocus.CATEGORIES), nav.on(GuideNavEvent.OPEN))
    }

    @Test
    fun focusInTheGuideHealsAtOnceUnlessTheCategoriesWereJustAskedFor() {
        assertEquals(0L, guideHealDelayMs(GuideNav.MENU, guideHasFocus = true, sinceCategoriesAskedMs = 10_000))
        assertEquals(0L, guideHealDelayMs(GuideNav.CATEGORIES, guideHasFocus = true, sinceCategoriesAskedMs = 10_000))
        assertEquals(CATEGORY_ASK_GRACE_MS - 100, guideHealDelayMs(GuideNav.CATEGORIES, guideHasFocus = true, sinceCategoriesAskedMs = 100))
    }

    @Test
    fun nothingToHealOutsideTheGuideOrInGuideState() {
        assertNull(guideHealDelayMs(GuideNav.GUIDE, guideHasFocus = true, sinceCategoriesAskedMs = 0))
        assertNull(guideHealDelayMs(GuideNav.MENU, guideHasFocus = false, sinceCategoriesAskedMs = 10_000))
        assertNull(guideHealDelayMs(GuideNav.CATEGORIES, guideHasFocus = false, sinceCategoriesAskedMs = 0))
    }

    @Test
    fun focusReachingTheGuideOnItsOwnPutsTheCategoriesAway() {
        for (nav in GuideNav.entries) {
            assertEquals(nav.name, step(GuideNav.GUIDE, GuideNavFocus.NONE), nav.on(GuideNavEvent.GRID_FOCUSED))
        }
    }

    @Test
    fun focusLeavingTheCategoriesForTheMenuIsTheMenuState() {
        assertEquals(step(GuideNav.MENU, GuideNavFocus.NONE), GuideNav.CATEGORIES.on(GuideNavEvent.FOCUS_LEFT))
    }

    @Test
    fun onlyTheGuideAloneHidesTheCategories() {
        assertFalse(GuideNav.GUIDE.categoriesOpen)
        assertTrue(GuideNav.CATEGORIES.categoriesOpen)
        assertTrue(GuideNav.MENU.categoriesOpen)
    }

    @Test
    fun leftLeftRightRightIsARoundTrip() {
        var nav = GuideNav.GUIDE
        val focus = mutableListOf<GuideNavFocus>()
        val states = mutableListOf<GuideNav>()
        for (ev in listOf(GuideNavEvent.OPEN, GuideNavEvent.LEFT, GuideNavEvent.RAIL_RIGHT, GuideNavEvent.RIGHT)) {
            val s = nav.on(ev)
            focus += s.focus
            states += s.nav
            nav = s.nav
        }
        assertEquals(listOf(GuideNav.CATEGORIES, GuideNav.MENU, GuideNav.CATEGORIES, GuideNav.GUIDE), states)
        assertEquals(listOf(GuideNavFocus.CATEGORIES, GuideNavFocus.RAIL, GuideNavFocus.CATEGORIES, GuideNavFocus.GRID), focus)
    }

    @Test
    fun backStepsBackOneLevel() {
        assertEquals(GuideNav.GUIDE, GuideNav.CATEGORIES.on(GuideNavEvent.BACK).nav)
    }
}

/** Highlighting a category switches the guide after a short rest (v2.6.0). */
class CategorySettleTest {
    @Test
    fun aHighlightSwitchesOnlyOnceItHasRested() {
        val s = CategorySettle<String>(220)
        s.highlight("news", current = "all", nowMs = 1_000)
        assertNull(s.due(1_100))
        assertEquals("news", s.due(1_220))
        assertNull("switched once", s.due(1_500))
    }

    @Test
    fun aHeldKeySwitchesOnlyToWhereItStops() {
        val s = CategorySettle<String>(220)
        var t = 0L
        for (k in listOf("a", "b", "c", "d")) {
            s.highlight(k, current = "all", nowMs = t)
            assertNull(s.due(t + 100))
            t += 100
        }
        assertEquals("d", s.due(t - 100 + 220))
    }

    @Test
    fun theCategoryOnScreenIsNotSwitchedToAgain() {
        val s = CategorySettle<String>(220)
        s.highlight("news", current = "all", nowMs = 0)
        s.highlight("all", current = "all", nowMs = 50)
        assertNull(s.due(1_000))
    }

    @Test
    fun okRightOrBackTakeTheHighlightAtOnce() {
        val s = CategorySettle<String>(220)
        s.highlight("sport", current = "all", nowMs = 0)
        assertEquals("sport", s.flush())
        assertNull(s.flush())
        assertNull(s.due(10_000))
    }

    @Test
    fun cancelDropsTheWaitingSwitch() {
        val s = CategorySettle<String>(220)
        s.highlight("sport", current = "all", nowMs = 0)
        s.cancel()
        assertNull(s.due(10_000))
    }
}
