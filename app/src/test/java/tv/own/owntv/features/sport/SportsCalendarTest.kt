package tv.own.owntv.features.sport

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** (v2.6.2) The calendar's grid, its days and when a month is fetched again. */
class SportsCalendarTest {

    private fun game(id: String, iso: String) = Fixture(
        id = id, leagueId = "soccer.eng.1", dateMs = java.time.Instant.parse(iso).toEpochMilli(),
        state = FixtureState.UPCOMING, statusLine = "", broadcasts = emptyList(), venue = null,
    )

    @Test
    fun october2026StartsOnAThursdayAndFillsFiveWeeks() {
        val weeks = SportsCalendar.weeks(YearMonth.of(2026, 10))
        assertEquals(5, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        assertNull(weeks[0][2])
        assertEquals(LocalDate.of(2026, 10, 1), weeks[0][3])
        assertEquals(LocalDate.of(2026, 10, 31), weeks[4][5])
        assertNull(weeks[4][6])
    }

    @Test
    fun aMonthStartingOnMondayHasNoLeadingGap() {
        assertEquals(LocalDate.of(2026, 6, 1), SportsCalendar.weeks(YearMonth.of(2026, 6))[0][0])
    }

    @Test
    fun gamesAreFiledOnTheDevicesOwnDayInKickOffOrder() {
        val sydney = ZoneId.of("Australia/Sydney")
        val late = game("late", "2026-10-31T19:00:00Z") // 06:00 on 1 November in Sydney
        val early = game("early", "2026-10-31T03:00:00Z")
        val again = game("early", "2026-10-31T03:00:00Z")
        val days = SportsCalendar.byDay(listOf(late, early, again), sydney)
        assertEquals(listOf("early"), days[LocalDate.of(2026, 10, 31)]?.map { it.id })
        assertEquals(listOf("late"), days[LocalDate.of(2026, 11, 1)]?.map { it.id })
        assertEquals(listOf("early", "late"), SportsCalendar.byDay(listOf(late, early), ZoneOffset.UTC)[LocalDate.of(2026, 10, 31)]?.map { it.id })
    }

    @Test
    fun aMonthIsFetchedOnceThenOnlyWhileItIsNotOver() {
        val today = LocalDate.of(2026, 10, 27)
        val now = 1_000_000_000L
        assertTrue(SportsCalendar.due(YearMonth.of(2026, 9), null, now, today))
        assertFalse(SportsCalendar.due(YearMonth.of(2026, 9), now - 30L * 86_400_000L, now, today))
        assertFalse(SportsCalendar.due(YearMonth.of(2026, 10), now - 60_000L, now, today))
        assertTrue(SportsCalendar.due(YearMonth.of(2026, 11), now - SportsCalendar.RefreshMs - 1, now, today))
    }

    @Test
    fun aMonthOpensOnTodayOrItsFirstDay() {
        val today = LocalDate.of(2026, 10, 27)
        assertEquals(today, SportsCalendar.entryDay(YearMonth.of(2026, 10), today))
        assertEquals(LocalDate.of(2026, 11, 1), SportsCalendar.entryDay(YearMonth.of(2026, 11), today))
    }
}
