package tv.own.owntv.features.sport

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.recording.RecordingManager
import tv.own.owntv.core.settings.SettingsRepository

/**
 * "Record" on a game (v2.6.2): a standing wish to record it. A game's channel is only known once the
 * guide carries it, often days after the game is on the calendar, so the wish waits here until
 * [SportRecordingPlanner] finds the channel and turns it into an ordinary recording.
 */
data class RecordPlan(
    val fixtureId: String,
    val leagueId: String,
    val kickoffMs: Long,
    /** "Arsenal – Liverpool", the recording's title. */
    val title: String,
    val status: Status = Status.WAITING,
    /** The channel it was scheduled on, once [Status.SCHEDULED]. */
    val channelName: String? = null,
) {
    enum class Status { WAITING, SCHEDULED, NO_CHANNEL }

    fun encode(): String = listOf(fixtureId, leagueId, kickoffMs.toString(), title, status.name, channelName.orEmpty())
        .joinToString(Sep.toString())

    companion object {
        private const val Sep = '\u001F'

        fun decode(raw: String): RecordPlan? {
            val p = raw.split(Sep)
            if (p.size < 5) return null
            return RecordPlan(
                fixtureId = p[0],
                leagueId = p[1],
                kickoffMs = p[2].toLongOrNull() ?: return null,
                title = p[3],
                status = runCatching { Status.valueOf(p[4]) }.getOrNull() ?: return null,
                channelName = p.getOrNull(5)?.ifEmpty { null },
            )
        }
    }
}

/** When a waiting plan is looked at, and how long a game is recorded for. Pure, so it is tested. */
object RecordPlanRules {

    /** Guides rarely reach further than this; looking earlier only spends requests. */
    const val HorizonMs = 8L * 24L * 60L * 60L * 1000L

    /** A wish with no channel by this long before kick-off is reported as not found. */
    const val GiveUpBeforeMs = 10L * 60L * 1000L

    /** A plan stays (and shows its outcome) until a day after kick-off. */
    const val KeepAfterMs = 24L * 60L * 60L * 1000L

    /** Recording starts this long before kick-off when the guide has no programme for it. */
    const val LeadMs = 5L * 60L * 1000L

    /** A game this far under way is not started late any more: it is reported as not found. */
    const val TooLateMs = 30L * 60L * 1000L

    enum class Step { WAIT, TRY, DROP, MISSED }

    fun step(plan: RecordPlan, now: Long): Step = when {
        now > plan.kickoffMs + KeepAfterMs -> Step.DROP
        plan.status != RecordPlan.Status.WAITING -> Step.WAIT
        now > plan.kickoffMs + TooLateMs -> Step.MISSED
        plan.kickoffMs - now > HorizonMs -> Step.WAIT
        else -> Step.TRY
    }

    /** No channel found on this try: keep waiting, or say so once kick-off is close. */
    fun afterMiss(plan: RecordPlan, now: Long): RecordPlan =
        if (now >= plan.kickoffMs - GiveUpBeforeMs) plan.copy(status = RecordPlan.Status.NO_CHANNEL) else plan

    /** A game's usual length with breaks, for a recording the guide cannot bound. */
    fun durationMs(group: SportGroup?): Long = Minute * when (group) {
        SportGroup.SOCCER -> 135
        SportGroup.RUGBY_UNION, SportGroup.RUGBY_LEAGUE -> 120
        SportGroup.AUSTRALIAN_FOOTBALL -> 165
        SportGroup.AMERICAN_FOOTBALL, SportGroup.BASEBALL -> 210
        SportGroup.BASKETBALL, SportGroup.ICE_HOCKEY -> 165
        SportGroup.CRICKET -> 240
        SportGroup.MOTORSPORT -> 150
        SportGroup.GOLF -> 300
        else -> 180
    }

    private const val Minute = 60_000L
}

/**
 * Turns waiting [RecordPlan]s into recordings (v2.6.2). For each game within the guide's reach it
 * fetches the game afresh (kick-off times move), asks [SportsMatcher] for its channel with the user's
 * Sport channel settings, prefers a channel the guide itself names the game on, and schedules the
 * guide programme (paddings included) or, without one, kick-off to the sport's usual length. The
 * recording goes to the DPTV-Recorder server when there is one, as every recording does.
 */
class SportRecordingPlanner(
    private val prefs: SportsPrefs,
    private val api: SportsApi,
    private val matcher: SportsMatcher,
    private val recordings: RecordingManager,
    private val channelDao: ChannelDao,
    private val epgDao: EpgDao,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
) {
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun run() = lock.withLock { runLocked() }

    private suspend fun runLocked() {
        val now = System.currentTimeMillis()
        val plans = prefs.recordPlans.first()
        if (plans.isEmpty()) return
        val out = ArrayList<RecordPlan>()
        for (plan in plans) {
            when (RecordPlanRules.step(plan, now)) {
                RecordPlanRules.Step.DROP -> Unit
                RecordPlanRules.Step.WAIT -> out.add(plan)
                RecordPlanRules.Step.MISSED -> out.add(plan.copy(status = RecordPlan.Status.NO_CHANNEL))
                RecordPlanRules.Step.TRY -> out.add(runCatching { attempt(plan, now) }.getOrDefault(plan))
            }
        }
        prefs.replaceRecordPlans(plans, out)
    }

    private suspend fun attempt(plan: RecordPlan, now: Long): RecordPlan {
        val league = sportsLeagueById(plan.leagueId) ?: return plan.copy(status = RecordPlan.Status.NO_CHANNEL)
        val day = Instant.ofEpochMilli(plan.kickoffMs).atZone(ZoneOffset.UTC).toLocalDate()
        val board = if (league.source == LeagueSource.ESPN) {
            runCatching { api.scoreboard(league) }.getOrNull()?.fixtures.orEmpty() +
                runCatching { api.month(league, java.time.YearMonth.from(day)) }.getOrNull()?.fixtures.orEmpty()
        } else {
            runCatching { api.scoreboard(league) }.getOrNull()?.fixtures.orEmpty()
        }
        val fixture = board.firstOrNull { it.id == plan.fixtureId } ?: return RecordPlanRules.afterMiss(plan, now)
        val fresh = plan.copy(kickoffMs = fixture.dateMs)
        val languages = prefs.preferredLanguages.first().mapNotNull { runCatching { SportLanguage.valueOf(it) }.getOrNull() }.toSet()
        val hidden = prefs.hiddenLanguages.first().mapNotNull { runCatching { SportLanguage.valueOf(it) }.getOrNull() }.toSet()
        val matches = matcher.match(
            fixture, languages, hidden = hidden,
            strict = prefs.strictChannels.first(), playlists = prefs.channelPlaylists.first(),
        )
        // The guide naming the game on a channel is the evidence worth recording on; a channel found by
        // its name or ESPN's broadcaster alone is taken only in the last hours, when nothing better came.
        val pick = matches.firstOrNull { it.epgTitle != null }
            ?: matches.firstOrNull().takeIf { fixture.dateMs - now < LastHoursMs }
            ?: return RecordPlanRules.afterMiss(fresh, now)
        val channel = channelDao.getById(pick.channelId) ?: return RecordPlanRules.afterMiss(fresh, now)
        val profile = settings.activeProfileId.first().takeIf { it >= 0 }?.let { profileDao.resolveExistingProfileId(it) }
            ?: return fresh
        val programme = channel.epgChannelId?.let { key ->
            runCatching { epgDao.programmesForChannel(key, fixture.dateMs - ProgrammeSlackMs, fixture.dateMs + ProgrammeSlackMs) }
                .getOrDefault(emptyList())
                .filter { it.startMs <= fixture.dateMs + ProgrammeSlackMs && it.stopMs > fixture.dateMs }
                .let { found -> found.firstOrNull { it.title == pick.epgTitle } ?: found.firstOrNull { it.startMs <= fixture.dateMs } }
        }
        val start = programme?.startMs ?: (fixture.dateMs - RecordPlanRules.LeadMs)
        val stop = programme?.stopMs ?: (fixture.dateMs + RecordPlanRules.durationMs(league.sportGroup))
        val window = recordings.windowFor(start, stop)
        recordings.schedule(
            RecordingEntity(
                profileId = profile,
                sourceId = channel.sourceId,
                channelId = channel.id,
                channelName = channel.name,
                channelIconUrl = channel.logoUrl,
                epgChannelId = channel.epgChannelId,
                streamUrl = channel.streamUrl,
                httpHeaders = channel.httpHeaders,
                title = fresh.title,
                description = programme?.title,
                programmeStartMs = start,
                programmeStopMs = stop,
                startMs = window.first,
                stopMs = window.last,
            ),
        )
        return fresh.copy(status = RecordPlan.Status.SCHEDULED, channelName = channel.name)
    }

    private companion object {
        const val LastHoursMs = 3L * 60L * 60L * 1000L
        const val ProgrammeSlackMs = 2L * 60L * 60L * 1000L
    }
}

/** Looks at the waiting plans every hour, and at once when one is added (v2.6.2). */
class SportRecordingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {

    private val planner: SportRecordingPlanner by inject()

    override suspend fun doWork(): Result {
        runCatching { planner.run() }
        return Result.success()
    }

    companion object {
        private const val Periodic = "sport-record-plans"
        private const val Now = "sport-record-plans-now"

        fun ensure(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Periodic,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SportRecordingWorker>(1, TimeUnit.HOURS).build(),
            )
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                Now,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SportRecordingWorker>().build(),
            )
        }
    }
}
