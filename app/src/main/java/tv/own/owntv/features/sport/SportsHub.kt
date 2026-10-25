package tv.own.owntv.features.sport

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Sport hub's rows (v2.6.0, OwnTV's Sports Hub): Live now, Starting soon and the latest results,
 * from the user's fixtures and the chips chosen on top. Pure, so the filtering and order are tested.
 *
 * Chips: none chosen = everything. A chosen sport passes its games (only its chosen leagues when any
 * of them are chosen); "My teams" passes the followed teams' games; several chips add up.
 */
object SportsHub {

    data class Filter(
        val sports: Set<SportGroup> = emptySet(),
        val myTeams: Boolean = false,
        val leagues: Set<String> = emptySet(),
    ) {
        val isAll: Boolean get() = sports.isEmpty() && !myTeams
    }

    data class Rows(
        val live: List<Fixture>,
        val soon: List<Fixture>,
        val results: List<Fixture>,
        /** Today's games after the filter, live or not, for the subtitle. */
        val today: List<Fixture>,
    )

    /** Cards in Starting soon and in the results. */
    const val RowMax = 12

    /** Results go back this far. */
    private const val ResultsWindowMs = 2L * 24L * 60L * 60L * 1000L

    fun passes(fixture: Fixture, filter: Filter, mine: (Fixture) -> Boolean): Boolean {
        if (filter.isAll) return true
        val group = sportsLeagueById(fixture.leagueId)?.sportGroup
        val bySport = group != null && group in filter.sports && filter.leagues
            .filter { id -> sportsLeagueById(id)?.sportGroup == group }
            .let { chosen -> chosen.isEmpty() || fixture.leagueId in chosen }
        return bySport || (filter.myTeams && mine(fixture))
    }

    fun rows(
        fixtures: List<Fixture>,
        filter: Filter,
        now: Long,
        leagueRank: (String) -> Int,
        mine: (Fixture) -> Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Rows {
        val shown = fixtures.distinctBy { it.id }.filter { passes(it, filter, mine) }
        val today = LocalDate.now(zone)
        fun isToday(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == today
        val live = shown.filter { SportsSchedule.isLiveish(it, now) }
            .sortedWith(compareBy<Fixture>({ leagueRank(it.leagueId) }, { !mine(it) }, { it.dateMs }))
        val soon = shown.filter { it.state == FixtureState.UPCOMING && it.dateMs > now }
            .sortedWith(compareBy<Fixture>({ it.dateMs }, { leagueRank(it.leagueId) }))
            .take(RowMax)
        val results = shown.filter { it.state == FixtureState.FINISHED && it.dateMs > now - ResultsWindowMs }
            .sortedWith(compareByDescending<Fixture> { it.dateMs }.thenBy { leagueRank(it.leagueId) })
            .take(RowMax)
        return Rows(live, soon, results, shown.filter { SportsSchedule.isLiveish(it, now) || isToday(it.dateMs) })
    }

    /** The sports the chip row offers: those with a chosen league, in the user's sport order. */
    fun chipSports(active: Set<String>, sportOrder: List<SportGroup>): List<SportGroup> {
        val present = SportsLeagues.filter { it.id in active }.map { it.sportGroup }.toSet()
        return sportOrder.filter { it in present }
    }
}

/** Team search across the chosen leagues (v2.6.0, Settings › Sport › My teams). */
object SportsTeamSearch {

    data class Hit(val leagueId: String, val team: TeamRef) {
        val key: String get() = SportsPrefs.teamKey(leagueId, team.abbreviation)
    }

    /** Results shown at most. */
    const val MaxHits = 30

    fun searchable(query: String): Boolean = query.trim().length >= 2

    /**
     * Teams whose name or short code contains [query], ignoring case and accents ("Koln" finds "Köln");
     * names that start with it first. One row per team and league.
     */
    fun search(index: List<Hit>, query: String): List<Hit> {
        if (!searchable(query)) return emptyList()
        val q = fold(query.trim())
        return index.distinctBy { it.key }
            .filter { fold(it.team.name).contains(q) || fold(it.team.abbreviation) == q }
            .sortedWith(compareBy<Hit>({ !fold(it.team.name).startsWith(q) }, { it.team.name.lowercase() }))
            .take(MaxHits)
    }

    private fun fold(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Marks, "")
            .lowercase()

    private val Marks = Regex("\\p{M}+")
}
