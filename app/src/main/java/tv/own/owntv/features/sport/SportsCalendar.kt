package tv.own.owntv.features.sport

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The Sport calendar's rules (v2.6.2, from OwnTV's SportsCalendar): the month grid, the games filed by
 * the device's own day, and when a league's month is worth fetching again. Pure, so each is tested.
 */
object SportsCalendar {

    /** The month as weeks of seven, Monday first; null pads the days outside it. */
    fun weeks(month: YearMonth, firstDay: DayOfWeek = DayOfWeek.MONDAY): List<List<LocalDate?>> {
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - firstDay.value + 7) % 7
        val cells = ArrayList<LocalDate?>()
        repeat(lead) { cells.add(null) }
        for (d in 1..month.lengthOfMonth()) cells.add(month.atDay(d))
        while (cells.size % 7 != 0) cells.add(null)
        return cells.chunked(7)
    }

    /** Each day's games, by kick-off, on the device's own day (a late ESPN-day game lands on the next). */
    fun byDay(fixtures: List<Fixture>, zone: ZoneId): Map<LocalDate, List<Fixture>> =
        fixtures.distinctBy { it.id }
            .groupBy { Instant.ofEpochMilli(it.dateMs).atZone(zone).toLocalDate() }
            .mapValues { (_, games) -> games.sortedBy { it.dateMs } }

    /**
     * Whether a league's saved month is due again: never fetched -> yes; a month already over -> never
     * again; this month or a later one -> when older than [RefreshMs].
     */
    fun due(month: YearMonth, fetchedAt: Long?, now: Long, today: LocalDate): Boolean = when {
        fetchedAt == null -> true
        month.isBefore(YearMonth.from(today)) -> false
        else -> now - fetchedAt > RefreshMs
    }

    /** The day focus lands on when a month opens: today in this month, else its first day. */
    fun entryDay(month: YearMonth, today: LocalDate): LocalDate =
        if (YearMonth.from(today) == month) today else month.atDay(1)

    /** A current or future month is fetched again after six hours. */
    const val RefreshMs = 6L * 60L * 60L * 1000L
}

/**
 * The calendar's data (v2.6.2): each followed league's month, fetched when the calendar shows it and
 * kept for the app's run ([SportsCalendar.due]). Four leagues at a time, like the boards.
 */
class SportsCalendarRepository(private val api: SportsApi) {

    private class Saved(val fetchedAt: Long, val fixtures: List<Fixture>)

    private val saved = java.util.concurrent.ConcurrentHashMap<String, Saved>()
    private val gate = Semaphore(Parallel)

    data class Month(val fixtures: List<Fixture>, val failed: Set<String>)

    /** [leagues]' games in [month]; a league that cannot be reached keeps what was saved, if anything. */
    suspend fun month(leagues: List<SportLeague>, month: YearMonth, force: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): Month = coroutineScope {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val failed = java.util.Collections.synchronizedSet(HashSet<String>())
        val all = leagues.map { league ->
            async {
                val key = league.id + KeySep + month
                val have = saved[key]
                if (!force && !SportsCalendar.due(month, have?.fetchedAt, now, today)) return@async have?.fixtures.orEmpty()
                try {
                    val board = gate.withPermit { api.month(league, month) }
                    val inMonth = board.fixtures.filter { YearMonth.from(Instant.ofEpochMilli(it.dateMs).atZone(zone)) == month }
                    saved[key] = Saved(now, inMonth)
                    inMonth
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed.add(league.id)
                    have?.fixtures.orEmpty()
                }
            }
        }.awaitAll().flatten()
        Month(all, failed.toSet())
    }

    /** Settings' "refresh now" and the hub's ⟳: every month is fetched again when next shown. */
    fun markStale() = saved.clear()

    private companion object {
        const val Parallel = 4
        const val KeySep = "@"
    }
}
