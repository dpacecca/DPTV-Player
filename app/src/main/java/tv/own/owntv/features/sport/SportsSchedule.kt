package tv.own.owntv.features.sport

/**
 * Timing rules for the Sport section (v5.2 port of OwnTV's live refresh, auto rebuild and Home
 * "Sports" row), as pure functions of the boards and the clock so they can be unit-tested. The
 * view model owns the loops; this only decides *what* is due.
 */
object SportsSchedule {

    /** Live refresh choices in seconds (Off / 30 s / 60 s / 2 min). */
    val LiveRefreshChoices = listOf(0, 30, 60, 120)

    /** Auto rebuild choices in minutes (Off / 15 / 30 / 60 min). */
    val AutoRebuildChoices = listOf(0, 15, 30, 60)

    const val DefaultLiveRefreshSeconds = 60
    const val DefaultAutoRebuildMinutes = 30

    /** Home's row lists games kicking off within this window (plus everything live). */
    const val HomeSoonMs = 3L * 60L * 60L * 1000L

    /** At most this many cards on Home's row. */
    const val HomeMaxCards = 12

    /**
     * A kick-off that passed while ESPN still says "pre" counts as live for this long (ESPN's state
     * lags the real start by a few minutes); after that it is a game ESPN never updated.
     */
    const val StaleUpcomingMs = 3L * 60L * 60L * 1000L

    /** The choice after [current] in [choices], wrapping; an unknown value restarts at the first. */
    fun next(choices: List<Int>, current: Int): Int {
        val index = choices.indexOf(current)
        return choices[(index + 1) % choices.size]
    }

    /** The choice [step] places from [current] in [choices], wrapping (Settings › Sport's ◀ ▶, v2.6.0). */
    fun step(choices: List<Int>, current: Int, step: Int): Int {
        val index = choices.indexOf(current).coerceAtLeast(0)
        return choices[(index + step).mod(choices.size)]
    }

    /**
     * True for a game that wants live scores now: live, or past its kick-off (within
     * [StaleUpcomingMs]) while ESPN still lists it as upcoming.
     */
    fun isLiveish(fixture: Fixture, now: Long): Boolean = when (fixture.state) {
        FixtureState.LIVE -> true
        FixtureState.UPCOMING -> fixture.dateMs <= now && fixture.dateMs > now - StaleUpcomingMs
        FixtureState.FINISHED -> false
    }

    /**
     * The leagues whose boards the live refresh fetches again: only those with a live(-ish) game.
     * A board with nothing live costs no request — its 15-minute cache covers kick-off times.
     */
    fun dueLeagues(boards: Map<String, List<Fixture>>, now: Long): Set<String> =
        boards.filterValues { fixtures -> fixtures.any { isLiveish(it, now) } }.keys

    /**
     * Fixtures whose channel lists the auto rebuild refreshes: the ones still to watch (live or
     * upcoming). A finished game's channels no longer matter.
     */
    fun rebuildable(fixture: Fixture): Boolean = fixture.state != FixtureState.FINISHED

    /**
     * Home's Sports row: every live game and every game kicking off within [HomeSoonMs] from
     * [fixtures] (the Favourites aggregate — active leagues plus followed teams and athletes). Live
     * first, the user's favourite teams' games first within that ([mine], v2.6.0, as OwnTV's Sports
     * Hub does), then by kick-off, then in the user's league order ([leagueRank]); at most
     * [HomeMaxCards]. Empty means the row is not drawn.
     */
    fun homeRow(
        fixtures: List<Fixture>,
        now: Long,
        mine: (Fixture) -> Boolean = { false },
        leagueRank: (String) -> Int,
    ): List<Fixture> =
        fixtures
            .filter { isLiveish(it, now) || (it.state == FixtureState.UPCOMING && it.dateMs in now..(now + HomeSoonMs)) }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<Fixture> { !isLiveish(it, now) }
                    .thenBy { !mine(it) }
                    .thenBy { it.dateMs }
                    .thenBy { leagueRank(it.leagueId) },
            )
            .take(HomeMaxCards)
}
