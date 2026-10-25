package tv.own.owntv.core.sync.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixed sync times (v2.5.3): next run, last slot, DST, zone changes and the catch-up rule. */
class SyncTimesTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val sydney = ZoneId.of("Australia/Sydney")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)
    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun theDefaultsFollowTheServerByHalfAnHour() {
        assertEquals("00:30,06:30,12:30,16:30,20:30", SyncTimes.serialize(SyncTimes.DEFAULT_LIVE))
        assertEquals("06:30,16:30", SyncTimes.serialize(SyncTimes.DEFAULT_EPG))
        assertEquals("03:00", SyncTimes.serialize(SyncTimes.DEFAULT_VOD))
    }

    @Test
    fun parseAndSerializeRoundTripSortedAndDeduplicated() {
        assertEquals(listOf(hm(6, 30), hm(20, 30)), SyncTimes.parse("20:30, 06:30,06:30"))
        assertEquals(emptyList<Int>(), SyncTimes.parse(""))
        assertNull(SyncTimes.parse(null))
        // Unreadable parts are dropped, not fatal.
        assertEquals(listOf(hm(3)), SyncTimes.parse("25:00,03:00,ab:cd,7"))
        assertEquals("03:00,23:30", SyncTimes.serialize(listOf(hm(23, 30), hm(3), -30 + 24 * 60 * 2)))
    }

    @Test
    fun nextRunPicksTheNextTimeInTheListAndWrapsToTomorrow() {
        val times = SyncTimes.DEFAULT_LIVE
        assertEquals(at(berlin, 2026, 6, 10, 6, 30), SyncTimes.nextRun(times, at(berlin, 2026, 6, 10, 1)))
        assertEquals(at(berlin, 2026, 6, 10, 12, 30), SyncTimes.nextRun(times, at(berlin, 2026, 6, 10, 6, 30)))
        assertEquals(at(berlin, 2026, 6, 11, 0, 30), SyncTimes.nextRun(times, at(berlin, 2026, 6, 10, 21)))
        assertEquals(at(berlin, 2026, 6, 11, 3), SyncTimes.nextRun(SyncTimes.DEFAULT_VOD, at(berlin, 2026, 6, 10, 3, 1)))
        assertNull(SyncTimes.nextRun(emptyList(), at(berlin, 2026, 6, 10, 3)))
    }

    @Test
    fun theDailyThreeAmDelayIsWorkedOutInLocalTime() {
        val now = at(berlin, 2026, 6, 10, 22, 15)
        assertEquals(Duration.ofMinutes(4 * 60 + 45).toMillis(), SyncTimes.delayToNext(SyncTimes.DEFAULT_VOD, now))
    }

    @Test
    fun springForwardRunsAMissingTimeOnceThatMorning() {
        // 2026-03-29 in Berlin: 02:00 jumps to 03:00, so 02:30 does not exist.
        val now = at(berlin, 2026, 3, 29, 1)
        val next = SyncTimes.nextRun(listOf(hm(2, 30)), now)!!
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), next.toLocalDateTime())
        // 03:00 the same night is only 1 h of wall time but 1 h of real time too (01:00 CET -> 03:00 CEST).
        assertEquals(Duration.ofHours(1).toMillis(), SyncTimes.delayToNext(SyncTimes.DEFAULT_VOD, now))
    }

    @Test
    fun autumnBackRunsARepeatedTimeOnce() {
        // 2026-10-25 in Berlin: 03:00 falls back to 02:00, so 02:30 happens twice.
        val first = at(berlin, 2026, 10, 25, 2, 30).withEarlierOffsetAtOverlap()
        val next = SyncTimes.nextRun(listOf(hm(2, 30)), at(berlin, 2026, 10, 25, 1))!!
        assertEquals(first.toInstant(), next.toInstant())
        // After the first 02:30, the next one is tomorrow's — not the repeated hour.
        val after = SyncTimes.nextRun(listOf(hm(2, 30)), first.plusMinutes(1))!!
        assertEquals(LocalDateTime.of(2026, 10, 26, 2, 30), after.toLocalDateTime())
        // 03:00 that night is 2 h of real time after 01:00 (the hour 02:00-03:00 repeats).
        assertEquals(Duration.ofHours(3).toMillis(), SyncTimes.delayToNext(SyncTimes.DEFAULT_VOD, at(berlin, 2026, 10, 25, 1)))
    }

    @Test
    fun aTimeZoneChangeMovesTheNextRunToTheNewLocalTime() {
        val instant = at(berlin, 2026, 6, 10, 12).toInstant()
        val inBerlin = SyncTimes.nextRun(SyncTimes.DEFAULT_VOD, instant.atZone(berlin))!!
        val inSydney = SyncTimes.nextRun(SyncTimes.DEFAULT_VOD, instant.atZone(sydney))!!
        assertEquals(3, inBerlin.hour)
        assertEquals(3, inSydney.hour)
        // Same moment, different local 03:00s: 20:00 Sydney -> 03:00 tomorrow is 7 h, not 15 h.
        assertEquals(Duration.ofHours(7).toMillis(), Duration.between(instant, inSydney.toInstant()).toMillis())
        assertEquals(Duration.ofHours(15).toMillis(), Duration.between(instant, inBerlin.toInstant()).toMillis())
    }

    @Test
    fun lastSlotIsTheLatestTimeAtOrBeforeNow() {
        val times = SyncTimes.DEFAULT_LIVE
        assertEquals(at(berlin, 2026, 6, 10, 6, 30), SyncTimes.lastSlot(times, at(berlin, 2026, 6, 10, 6, 30)))
        assertEquals(at(berlin, 2026, 6, 10, 0, 30), SyncTimes.lastSlot(times, at(berlin, 2026, 6, 10, 6, 29)))
        assertEquals(at(berlin, 2026, 6, 9, 20, 30), SyncTimes.lastSlot(times, at(berlin, 2026, 6, 10, 0, 10)))
        assertEquals(at(berlin, 2026, 6, 9, 3), SyncTimes.lastSlot(SyncTimes.DEFAULT_VOD, at(berlin, 2026, 6, 10, 2)))
    }

    @Test
    fun aMissedSlotIsCaughtUpOnceAtTheNextStart() {
        // Off at 03:00, switched on at 09:00: the 03:00 run is owed.
        val slot = at(berlin, 2026, 6, 10, 3).toInstant()
        val lastOk = at(berlin, 2026, 6, 9, 3, 20).toInstant()
        assertTrue(SyncTimes.isDue(slot, handledSlot = null, lastSuccess = lastOk))
        // Once handled, the same slot never runs again — however many starts follow.
        assertFalse(SyncTimes.isDue(slot, handledSlot = slot, lastSuccess = lastOk))
        // A manual sync after the slot already covers it.
        assertFalse(SyncTimes.isDue(slot, handledSlot = null, lastSuccess = slot.plusSeconds(60)))
        // Three days off still means a single catch-up: only the latest slot is ever asked about.
        val latest = SyncTimes.lastSlot(SyncTimes.DEFAULT_VOD, at(berlin, 2026, 6, 13, 9))!!.toInstant()
        assertEquals(at(berlin, 2026, 6, 13, 3).toInstant(), latest)
        assertTrue(SyncTimes.isDue(latest, handledSlot = slot, lastSuccess = lastOk))
        assertFalse(SyncTimes.isDue(latest, handledSlot = latest, lastSuccess = lastOk))
        // First run ever: nothing recorded, so the slot is owed.
        assertTrue(SyncTimes.isDue(slot, null, null))
    }

    @Test
    fun aRunHeldBackByPlaybackMayStartForThreeHours() {
        val slot = Instant.parse("2026-06-10T01:00:00Z")
        assertTrue(SyncTimes.withinHoldWindow(slot, slot))
        assertTrue(SyncTimes.withinHoldWindow(slot, slot.plus(Duration.ofHours(3))))
        assertFalse(SyncTimes.withinHoldWindow(slot, slot.plus(Duration.ofHours(3)).plusSeconds(1)))
    }
}
