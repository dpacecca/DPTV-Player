package tv.own.owntv.features.epg

/**
 * The guide's menus on a TV (v2.6.0, as TiviMate): the guide alone at full width, the categories slid in
 * from the left beside the menu's icons, or the menu open with its names and the categories still beside it.
 * This is the whole of its navigation, kept apart from Compose so it can be tested: every move says which
 * state comes next and where focus goes. The screen moves focus first and then starts the slide, so focus
 * never lands on a panel that is going away (the lesson of v2.5.1).
 *
 *  - [GuideNavEvent.OPEN] (◀ on what is on now, a swipe right): GUIDE → CATEGORIES.
 *  - [GuideNavEvent.LEFT] in the categories: CATEGORIES → MENU.
 *  - [GuideNavEvent.RAIL_RIGHT] (▶ on the menu): MENU → CATEGORIES; from the menu over the guide alone, the
 *    guide's row.
 *  - [GuideNavEvent.RIGHT], [GuideNavEvent.BACK], [GuideNavEvent.SWIPE_LEFT] in the categories: CATEGORIES →
 *    GUIDE, focus back on the row it came from. [GuideNavEvent.PICKED] (OK): the same, on the first row.
 *  - Back on the menu is not here: the shell answers it with the exit prompt ([railBackAsksToExit]).
 *  - [GuideNavEvent.GRID_FOCUSED]: focus reached the guide on its own (a tap, a restore): GUIDE, nothing moved.
 *  - [GuideNavEvent.FOCUS_LEFT]: focus left the categories for somewhere else (the menu, by touch): MENU.
 */
internal enum class GuideNav { GUIDE, CATEGORIES, MENU }

internal enum class GuideNavEvent { OPEN, LEFT, RIGHT, BACK, SWIPE_LEFT, PICKED, RAIL_RIGHT, GRID_FOCUSED, FOCUS_LEFT }

/** Where a move sends focus; [GRID_FIRST] is the first row of a newly picked category, [NONE] leaves it. */
internal enum class GuideNavFocus { GRID, GRID_FIRST, CATEGORIES, RAIL, NONE }

internal data class GuideNavStep(val nav: GuideNav, val focus: GuideNavFocus)

/** The categories are on screen (with the menu beside them, or not). */
internal val GuideNav.categoriesOpen: Boolean get() = this != GuideNav.GUIDE

internal fun GuideNav.on(event: GuideNavEvent): GuideNavStep {
    val stay = GuideNavStep(this, GuideNavFocus.NONE)
    return when (event) {
        GuideNavEvent.OPEN ->
            if (this == GuideNav.GUIDE) GuideNavStep(GuideNav.CATEGORIES, GuideNavFocus.CATEGORIES) else stay
        GuideNavEvent.LEFT ->
            if (this == GuideNav.CATEGORIES) GuideNavStep(GuideNav.MENU, GuideNavFocus.RAIL) else stay
        GuideNavEvent.RIGHT, GuideNavEvent.BACK, GuideNavEvent.SWIPE_LEFT ->
            if (this == GuideNav.CATEGORIES) GuideNavStep(GuideNav.GUIDE, GuideNavFocus.GRID) else stay
        GuideNavEvent.PICKED ->
            if (this == GuideNav.CATEGORIES) GuideNavStep(GuideNav.GUIDE, GuideNavFocus.GRID_FIRST) else stay
        GuideNavEvent.RAIL_RIGHT ->
            if (this == GuideNav.MENU) GuideNavStep(GuideNav.CATEGORIES, GuideNavFocus.CATEGORIES)
            else GuideNavStep(GuideNav.GUIDE, GuideNavFocus.GRID)
        GuideNavEvent.GRID_FOCUSED -> GuideNavStep(GuideNav.GUIDE, GuideNavFocus.NONE)
        GuideNavEvent.FOCUS_LEFT -> if (this == GuideNav.CATEGORIES) GuideNavStep(GuideNav.MENU, GuideNavFocus.NONE) else stay
    }
}

/**
 * Highlighting a category switches the guide to it (v2.6.0, as TiviMate) once the highlight has rested for
 * [settleMs], so a held ▲/▼ through the list does not filter the guide on every row. Driven by a clock
 * (uptime millis) so it can be tested: [highlight] on every move, [due] after the wait, [flush] on OK, ▶ or
 * Back — which keep the highlighted category even before the wait is up.
 */
internal class CategorySettle<K : Any>(private val settleMs: Long) {
    private var pending: K? = null
    private var at = 0L

    /** Focus reached [key]; [current] is the category the guide shows. */
    fun highlight(key: K, current: K, nowMs: Long) {
        pending = key.takeIf { it != current }
        at = nowMs
    }

    /** The category to switch to now, once the highlight has rested long enough; null otherwise. */
    fun due(nowMs: Long): K? {
        val key = pending ?: return null
        if (nowMs - at < settleMs) return null
        pending = null
        return key
    }

    /** The category still waiting, at once (OK, ▶, Back); null when there is none. */
    fun flush(): K? = pending.also { pending = null }

    fun cancel() { pending = null }
}

/** (v2.6.0) How long a highlighted category rests before the guide switches to it. */
internal const val CATEGORY_SETTLE_MS = 220L
