package tv.own.owntv.features.epg

/**
 * The guide's docked categories (v2.5.1): always a column between the menu and the guide, slim until
 * focus goes into it. This is the whole of its navigation, kept apart from Compose so it can be tested:
 * every move says where focus goes and whether the column is wide. The screen moves focus first and
 * then changes the width, so focus never lands on a list that is going away.
 *
 *  - [GuideDockEvent.OPEN] (◀ on what is on now, a swipe right): into the categories, wide.
 *  - [GuideDockEvent.LEFT] in the categories: to the menu; the column goes slim beside it.
 *  - [GuideDockEvent.RIGHT], [GuideDockEvent.BACK], [GuideDockEvent.SWIPE_LEFT], [GuideDockEvent.PICKED]
 *    in the categories: slim again, focus back on the guide's row.
 *  - [GuideDockEvent.RAIL_RIGHT] (▶ on the menu): back into the categories when the menu was reached
 *    from them, else to the guide's row as before.
 *  - [GuideDockEvent.GRID_FOCUSED], [GuideDockEvent.FOCUS_LEFT]: focus got somewhere else on its own
 *    (a tap, a restore); the column only goes slim, nothing is moved.
 */
internal data class GuideDock(
    /** The column is wide, with the full list in it (focus is in it, or about to be). */
    val expanded: Boolean = false,
    /** The menu was reached with ◀ from the categories, so ▶ there goes back to them. */
    val railFromDock: Boolean = false,
)

internal enum class GuideDockEvent { OPEN, LEFT, RIGHT, BACK, SWIPE_LEFT, PICKED, RAIL_RIGHT, GRID_FOCUSED, FOCUS_LEFT }

/** Where a move sends focus; [NONE] leaves it where it is. */
internal enum class GuideDockFocus { GRID, CATEGORIES, RAIL, NONE }

internal data class GuideDockStep(val dock: GuideDock, val focus: GuideDockFocus)

internal fun GuideDock.on(event: GuideDockEvent): GuideDockStep = when (event) {
    GuideDockEvent.OPEN -> GuideDockStep(GuideDock(expanded = true), GuideDockFocus.CATEGORIES)
    GuideDockEvent.LEFT ->
        if (expanded) GuideDockStep(GuideDock(railFromDock = true), GuideDockFocus.RAIL) else GuideDockStep(this, GuideDockFocus.NONE)
    GuideDockEvent.RIGHT, GuideDockEvent.BACK, GuideDockEvent.SWIPE_LEFT, GuideDockEvent.PICKED ->
        if (expanded) GuideDockStep(GuideDock(), GuideDockFocus.GRID) else GuideDockStep(this, GuideDockFocus.NONE)
    GuideDockEvent.RAIL_RIGHT ->
        if (railFromDock) GuideDockStep(GuideDock(expanded = true), GuideDockFocus.CATEGORIES) else GuideDockStep(GuideDock(), GuideDockFocus.GRID)
    GuideDockEvent.GRID_FOCUSED -> GuideDockStep(GuideDock(), GuideDockFocus.NONE)
    GuideDockEvent.FOCUS_LEFT -> GuideDockStep(copy(expanded = false), GuideDockFocus.NONE)
}
