package tv.own.owntv.features.sport

import tv.own.owntv.player.LiveDiagnosticsLog

/**
 * The one door Sport's data comes through (v2.5.0): each call goes to the league's own source
 * ([SportLeague.source]) — ESPN for nearly everything, SofaScore for the few competitions ESPN lacks.
 * All answer in the same models, so [SportsViewModel] and everything after it stay source-blind.
 *
 * A SofaScore league runs a source chain (v2.6.0): SofaScore while it answers; after any failure
 * (since October 2026 it refuses every unofficial client with HTTP 403) it rests for
 * [SofaScoreGate.PauseMs] and the league comes from TheSportsDB when it has one
 * ([SportLeague.theSportsDbId]). A league neither can serve fails with [SourceUnavailableException],
 * which the board shows as "unavailable right now" rather than blank.
 */
class SportsApi(
    private val espn: EspnApi,
    private val sofaScore: SofaScoreApi,
    private val sportsDb: TheSportsDbApi,
) {

    private val gate = SofaScoreGate()

    suspend fun scoreboard(league: SportLeague): EspnApi.Scoreboard = when (league.source) {
        LeagueSource.ESPN -> espn.scoreboard(league)
        LeagueSource.SOFASCORE -> SourceChain.scoreboard(
            league, gate, ::sportLog,
            primary = { sofaScore.scoreboard(it) },
            fallback = { sportsDb.scoreboard(it) },
        )
    }

    /** Tables come from SofaScore only (TheSportsDB's free key has none): none while it rests. */
    suspend fun standings(league: SportLeague): List<LeagueTable> = when (league.source) {
        LeagueSource.ESPN -> espn.standings(league)
        LeagueSource.SOFASCORE -> if (!gate.open()) {
            emptyList()
        } else {
            try {
                sofaScore.standings(league)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                gate.close()
                sportLog(SofaScoreFailed + league.id + Colon + e.message)
                emptyList()
            }
        }
    }

    suspend fun teams(league: SportLeague): List<TeamRef> = when (league.source) {
        LeagueSource.ESPN -> espn.teams(league)
        LeagueSource.SOFASCORE -> scoreboard(league).fixtures.flatMap { listOfNotNull(it.home, it.away) }
            .distinctBy { it.id }
            .map { TeamRef(it.id, it.abbreviation, it.name, it.logoUrl) }
            .sortedBy { it.name.lowercase() }
    }

    private companion object {
        const val SofaScoreFailed = "sofascore standings failed "
        const val Colon = ": "

        /** A source failure, to logcat and to the Playback log page ("sport:" lines). */
        fun sportLog(message: String) {
            android.util.Log.w(LogTag, message)
            LiveDiagnosticsLog.event(LogPrefix + message)
        }

        const val LogTag = "SportsHub"
        const val LogPrefix = "sport: "
    }
}

/** No source could serve a league's board right now (v2.6.0). */
class SourceUnavailableException(message: String) : java.io.IOException(message)

/**
 * Whether SofaScore is worth asking (v2.6.0): open until a call fails, then shut for [pauseMs] so
 * every refresh does not spend a request on a 403 first. One gate for every SofaScore league, since a
 * block is never per competition.
 */
class SofaScoreGate(
    private val pauseMs: Long = PauseMs,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var closedUntil = 0L

    fun open(): Boolean = clock() >= closedUntil

    fun close() {
        closedUntil = clock() + pauseMs
    }

    companion object {
        const val PauseMs = 6L * 60L * 60L * 1000L
    }
}

/** The SofaScore → TheSportsDB decision (v2.6.0), with the fetches passed in so tests can fake them. */
internal object SourceChain {

    suspend fun scoreboard(
        league: SportLeague,
        gate: SofaScoreGate,
        log: (String) -> Unit,
        primary: suspend (SportLeague) -> EspnApi.Scoreboard,
        fallback: suspend (SportLeague) -> EspnApi.Scoreboard,
    ): EspnApi.Scoreboard {
        if (gate.open()) {
            try {
                return primary(league)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                gate.close()
                log(
                    PrimaryFailed + league.id + Colon + e.message +
                        if (league.theSportsDbId != null) UsingFallback else NoFallback,
                )
            }
        }
        if (league.theSportsDbId == null) throw SourceUnavailableException(NoSource + league.id)
        try {
            return fallback(league)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log(FallbackFailed + league.id + Colon + e.message)
            throw SourceUnavailableException(NoSource + league.id)
        }
    }

    private const val PrimaryFailed = "sofascore failed "
    private const val FallbackFailed = "thesportsdb failed "
    private const val UsingFallback = "; TheSportsDB for 6 h"
    private const val NoFallback = "; no fallback, unavailable for 6 h"
    private const val NoSource = "no source for "
    private const val Colon = ": "
}
