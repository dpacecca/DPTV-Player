package tv.own.owntv.core.sync.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import tv.own.owntv.core.sync.SyncContentTypes

/**
 * The three things the app refreshes on its own (v2.5.3). Each has its own list of device-local
 * times, its own WorkManager alarm and its own "last done" record.
 */
enum class ScheduledKind {
    /** Live channels only — a playlist pass without Movies and Series. */
    LIVE,

    /** The guide (EPG sources). */
    EPG,

    /** Movies and TV Shows only. */
    VOD;

    /** What a playlist pass of this kind asks for; null for the guide, which is not a playlist sync. */
    val contentTypes: SyncContentTypes?
        get() = when (this) {
            LIVE -> SyncContentTypes(live = true, movies = false, series = false)
            VOD -> SyncContentTypes(live = false, movies = true, series = true)
            EPG -> null
        }
}

/**
 * Pure time arithmetic for the fixed sync times (v2.5.3). Times are minutes after local midnight;
 * every answer is worked out from the wall clock in the given zone, so a DST shift or a new time
 * zone simply moves the next run to the right instant the next time it is asked.
 */
object SyncTimes {
    const val MINUTES_PER_DAY = 24 * 60

    /** Editor step for the ± stepper. */
    const val STEP_MINUTES = 30

    /** A run held back by playback may still start this long after its slot; later, it waits for the next one. */
    val HOLD_WINDOW: Duration = Duration.ofHours(3)

    /** The owner's server refreshes playlists at 00, 06, 12, 16 and 20 h; the app follows 30 minutes later. */
    val DEFAULT_LIVE = listOf(30, 6 * 60 + 30, 12 * 60 + 30, 16 * 60 + 30, 20 * 60 + 30)

    /** The guide is refreshed upstream at 06 and 16 h. */
    val DEFAULT_EPG = listOf(6 * 60 + 30, 16 * 60 + 30)

    /** Movies and TV Shows once a day, after the server's midnight refresh. */
    val DEFAULT_VOD = listOf(3 * 60)

    fun defaults(kind: ScheduledKind): List<Int> = when (kind) {
        ScheduledKind.LIVE -> DEFAULT_LIVE
        ScheduledKind.EPG -> DEFAULT_EPG
        ScheduledKind.VOD -> DEFAULT_VOD
    }

    /** "HH:mm" for minutes after midnight. */
    fun format(minutes: Int): String {
        val m = normalize(minutes)
        return "%02d:%02d".format(java.util.Locale.ROOT, m / 60, m % 60)
    }

    fun normalize(minutes: Int): Int = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    /** Stored form: "00:30,06:30" — sorted, de-duplicated. */
    fun serialize(times: List<Int>): String = clean(times).joinToString(",") { format(it) }

    /** Reads [serialize]'s form; null for absent or unreadable, so the caller falls back to the defaults. */
    fun parse(raw: String?): List<Int>? {
        if (raw == null) return null
        if (raw.isBlank()) return emptyList()
        val out = raw.split(',').mapNotNull { part ->
            val bits = part.trim().split(':')
            if (bits.size != 2) return@mapNotNull null
            val h = bits[0].toIntOrNull() ?: return@mapNotNull null
            val m = bits[1].toIntOrNull() ?: return@mapNotNull null
            if (h !in 0..23 || m !in 0..59) null else h * 60 + m
        }
        return clean(out)
    }

    fun clean(times: List<Int>): List<Int> = times.map(::normalize).distinct().sorted()

    /** The first slot strictly after [now], or null when there are no times. */
    fun nextRun(times: List<Int>, now: ZonedDateTime): ZonedDateTime? {
        if (times.isEmpty()) return null
        val zone = now.zone
        val today = now.toLocalDate()
        // Two days ahead covers a list with one time that has already passed today, plus a DST gap.
        for (dayOffset in 0L..2L) {
            val day = today.plusDays(dayOffset)
            clean(times).forEach { minutes ->
                val at = slotAt(day, minutes, zone)
                if (at.isAfter(now)) return at
            }
        }
        return null
    }

    /** The latest slot at or before [now], or null when there are no times. */
    fun lastSlot(times: List<Int>, now: ZonedDateTime): ZonedDateTime? {
        if (times.isEmpty()) return null
        val zone = now.zone
        val today = now.toLocalDate()
        for (dayOffset in 0L..2L) {
            val day = today.minusDays(dayOffset)
            clean(times).asReversed().forEach { minutes ->
                val at = slotAt(day, minutes, zone)
                if (!at.isAfter(now)) return at
            }
        }
        return null
    }

    /** Milliseconds from [now] to the next slot; null with no times. Never below one minute. */
    fun delayToNext(times: List<Int>, now: ZonedDateTime): Long? =
        nextRun(times, now)?.let { Duration.between(now, it).toMillis().coerceAtLeast(60_000L) }

    /**
     * A local time on a day. In a spring-forward gap (02:30 that does not exist) java.time moves it
     * forward by the gap's length, so the run still happens that day; in an autumn overlap it takes
     * the earlier of the two instants, so it runs once.
     */
    fun slotAt(day: LocalDate, minutes: Int, zone: ZoneId): ZonedDateTime {
        val m = normalize(minutes)
        return ZonedDateTime.of(day, LocalTime.of(m / 60, m % 60), zone)
    }

    /**
     * Whether the run for [slot] is still owed: neither this slot nor a later one was handled, and
     * nothing of this kind has succeeded since the slot. One answer per slot, so a device that was
     * off for three days catches up once, not three times.
     */
    fun isDue(slot: Instant, handledSlot: Instant?, lastSuccess: Instant?): Boolean {
        if (handledSlot != null && !handledSlot.isBefore(slot)) return false
        if (lastSuccess != null && !lastSuccess.isBefore(slot)) return false
        return true
    }

    /** True while a run held back for playback may still start for [slot]. */
    fun withinHoldWindow(slot: Instant, now: Instant): Boolean =
        !now.isAfter(slot.plus(HOLD_WINDOW))
}
