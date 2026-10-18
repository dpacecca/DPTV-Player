package tv.own.owntv.features.sport

/**
 * The user's own order of sports and leagues (v5.2 port of OwnTV's "Hold OK to put sports and
 * leagues in your own order"). Pure list logic, kept out of the screen so it can be unit-tested.
 *
 * Two stored lists ([SportsPrefs.sportOrder], [SportsPrefs.leagueOrder]) drive every place leagues
 * are listed: the rail, the Sports and Leagues settings tabs and Home's Sports row. Both may be
 * partial or stale (a league removed from the catalogue, a sport added in an update) — whatever
 * they do not name keeps its catalogue position after the named ones, so an update never hides
 * anything and a fresh install looks exactly as before (sport declaration order, catalogue order).
 */
object SportsOrder {

    /** Ids in one DataStore string. League ids and enum names never contain a comma. */
    private const val Separator = ','

    fun decode(raw: String?): List<String> =
        raw?.split(Separator)?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    fun encode(ids: List<String>): String = ids.distinct().joinToString(Separator.toString())

    /** Every sport, the stored ones first in the stored order, the rest in declaration order. */
    fun orderSports(stored: List<String>): List<SportGroup> {
        val named = stored.mapNotNull { name -> SportGroup.entries.firstOrNull { it.name == name } }.distinct()
        return named + SportGroup.entries.filter { it !in named }
    }

    /**
     * [leagues] in the user's order: the ones [leagueOrder] names first, in that order; the rest
     * after them, grouped by [sportOrder] (stable, so the catalogue order holds inside a sport). A
     * league activated after the user sorted the rail therefore lands at its end, where they will
     * look for it, instead of somewhere in the middle.
     */
    fun orderLeagues(
        leagues: List<SportLeague>,
        leagueOrder: List<String>,
        sportOrder: List<SportGroup>,
    ): List<SportLeague> {
        val rank = HashMap<String, Int>()
        leagueOrder.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
        val sportRank = HashMap<SportGroup, Int>()
        sportOrder.forEachIndexed { index, group -> sportRank.putIfAbsent(group, index) }
        val (listed, rest) = leagues.partition { it.id in rank }
        return listed.sortedBy { rank.getValue(it.id) } +
            rest.sortedBy { sportRank[it.sportGroup] ?: (SportGroup.entries.size + it.sportGroup.ordinal) }
    }

    /** [item] swapped with its neighbour above ([up]) or below; a no-op at either end or if absent. */
    fun <T> move(list: List<T>, item: T, up: Boolean): List<T> {
        val index = list.indexOf(item)
        if (index < 0) return list
        val target = if (up) index - 1 else index + 1
        if (target !in list.indices) return list
        return list.toMutableList().apply {
            this[index] = this[target]
            this[target] = item
        }
    }

    /**
     * The order to store after the user sorted [visible] (the rail's active leagues): those first,
     * then every id stored before that is not on screen now, in its old relative order — so the
     * stored list never forgets a league that is only switched off for a while.
     */
    fun merge(visible: List<String>, stored: List<String>): List<String> =
        (visible + stored.filter { it !in visible }).distinct()
}
