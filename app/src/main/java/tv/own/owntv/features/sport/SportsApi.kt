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
    private val worldRugby: WorldRugbyApi,
    context: android.content.Context,
) {

    private val appContext = context.applicationContext
    private val gate = SofaScoreGate()

    suspend fun scoreboard(league: SportLeague): EspnApi.Scoreboard {
        if (league.worldRugby.isNotEmpty()) worldRugbyBoard(league, null)?.let { return it }
        return sourceBoard(league)
    }

    /** The league's board from its own source, when World Rugby has not served it. */
    private suspend fun sourceBoard(league: SportLeague): EspnApi.Scoreboard = when (league.source) {
        LeagueSource.WORLD_RUGBY -> throw SourceUnavailableException(NoSource + league.id)
        LeagueSource.ESPN -> espnBoard(league)
        LeagueSource.SOFASCORE -> SourceChain.scoreboard(
            league, gate, ::sportLog,
            primary = { sofaScore.scoreboard(it) },
            fallback = { sportsDb.scoreboard(it) },
        )
    }

    /**
     * One month of a league's games (v2.6.2, the calendar). ESPN answers a month at once; cricket (no
     * month feed) and the SofaScore leagues give what their board holds, which the calendar files by day.
     */
    suspend fun month(league: SportLeague, month: java.time.YearMonth): EspnApi.Scoreboard {
        if (league.worldRugby.isNotEmpty()) worldRugbyBoard(league, month)?.let { return it }
        return when {
            league.source == LeagueSource.ESPN && league.espnSport != CricketSport -> espn.scoreboard(league, month = month)
            else -> sourceBoard(league)
        }
    }

    /**
     * (DPTV v2.9.20) A rugby league's board from World Rugby, the preferred source: its fixtures and
     * scores, with ESPN's games laid over them for crests, broadcasters and ids
     * ([WorldRugbyRules.merge]). Null when World Rugby has no season of it or cannot be reached, so the
     * league's own source serves it as before.
     */
    private suspend fun worldRugbyBoard(league: SportLeague, month: java.time.YearMonth?): EspnApi.Scoreboard? {
        val fetched: List<Fixture>? = try {
            if (month == null) worldRugby.fixtures(league) else worldRugby.month(league, month)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            sportLog(WorldRugbyFailed + league.id + Colon + e.message)
            null
        }
        val fixtures = fetched ?: return null
        if (fixtures.isEmpty() && month == null) return null
        val merged = WorldRugbyRules.merge(fixtures, espnSide(league, month), crests(league))
        if (month == null) boardSides[league.id] = merged
        return EspnApi.Scoreboard(appContext.getString(league.labelRes), merged)
    }

    /** ESPN's games to lay over World Rugby's: the current day, this month and next (or [month]). */
    private suspend fun espnSide(league: SportLeague, month: java.time.YearMonth?): List<Fixture> {
        if (league.source != LeagueSource.ESPN) return emptyList()
        val key = league.id + Colon + (month?.toString() ?: "")
        val now = System.currentTimeMillis()
        espnSides[key]?.takeIf { now - it.first < EspnSideMs }?.let { return it.second }
        val thisMonth = java.time.YearMonth.now()
        val boards = if (month != null) {
            listOf(runCatching { espn.scoreboard(league, month = month) }.getOrNull())
        } else {
            listOf(
                runCatching { espn.scoreboard(league) }.getOrNull(),
                runCatching { espn.scoreboard(league, month = thisMonth) }.getOrNull(),
                runCatching { espn.scoreboard(league, month = thisMonth.plusMonths(1)) }.getOrNull(),
            )
        }
        val fixtures = boards.filterNotNull().flatMap { it.fixtures }.distinctBy { it.id }
        // A failed fetch is tried again on the next refresh rather than kept for ten minutes.
        if (boards.any { it != null }) espnSides[key] = now to fixtures
        return fixtures
    }

    /**
     * ESPN's rugby teams to take crests from: the league's own and the national sides of the World
     * Cups and test windows, which cover the internationals only World Rugby lists (the Nations Cup).
     */
    private suspend fun crests(league: SportLeague): List<TeamRef> {
        val now = System.currentTimeMillis()
        val national = crestCache[NationalKey]?.takeIf { now - it.first < CrestCacheMs }?.second
            ?: CrestLeagues.mapNotNull { sportsLeagueById(it) }
                .flatMap { runCatching { espn.teams(it) }.getOrDefault(emptyList()) }
                .also { if (it.isNotEmpty()) crestCache[NationalKey] = now to it }
        val own = if (league.source != LeagueSource.ESPN) {
            emptyList()
        } else {
            crestCache[league.id]?.takeIf { now - it.first < CrestCacheMs }?.second
                ?: runCatching { espn.teams(league) }.getOrDefault(emptyList())
                    .also { if (it.isNotEmpty()) crestCache[league.id] = now to it }
        }
        return own + national
    }

    /** A World Rugby match's team sheets (DPTV v2.9.20); null for a game World Rugby does not list. */
    suspend fun lineups(fixture: Fixture): Lineups? {
        val id = fixture.worldRugbyMatchId ?: return null
        return worldRugby.lineups(id)
    }

    private val espnSides = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Fixture>>>()
    private val crestCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<TeamRef>>>()
    private val boardSides = java.util.concurrent.ConcurrentHashMap<String, List<Fixture>>()

    /**
     * (DPTV v2.9.19) An ESPN league's board. ESPN's "current" scoreboard is one match day: for a
     * competition between rounds (rugby's Nations Championship, a league over an international break)
     * it holds only games already played, and nothing to come showed. When it has nothing after today,
     * this month and next are read too (ESPN answers a month at once) and joined to it.
     */
    private suspend fun espnBoard(league: SportLeague): EspnApi.Scoreboard {
        val current = espn.scoreboard(league)
        if (league.espnSport == CricketSport) return current
        val now = System.currentTimeMillis()
        if (!BoardRules.needsMonths(current.fixtures, now)) return current
        val thisMonth = java.time.YearMonth.now()
        val months = listOf(thisMonth, thisMonth.plusMonths(1)).mapNotNull { m ->
            runCatching { espn.scoreboard(league, month = m) }.getOrNull()
        }
        return current.copy(fixtures = BoardRules.join(current.fixtures, months.flatMap { it.fixtures }))
    }

    /**
     * A rugby league's tables come from World Rugby first (DPTV v2.9.20), with the board's crests.
     * Tables come from SofaScore only (TheSportsDB's free key has none): none while it rests.
     */
    suspend fun standings(league: SportLeague): List<LeagueTable> {
        if (league.worldRugby.isNotEmpty()) {
            val tables = try {
                worldRugby.standings(league)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                sportLog(WorldRugbyFailed + league.id + Colon + e.message)
                emptyList()
            }
            if (tables.isNotEmpty()) return WorldRugbyRules.adoptRows(tables, boardSides[league.id].orEmpty(), crests(league))
        }
        return sourceStandings(league)
    }

    private suspend fun sourceStandings(league: SportLeague): List<LeagueTable> = when (league.source) {
        LeagueSource.WORLD_RUGBY -> emptyList()
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
        LeagueSource.SOFASCORE, LeagueSource.WORLD_RUGBY -> scoreboard(league).fixtures.flatMap { listOfNotNull(it.home, it.away) }
            .distinctBy { it.id }
            .map { TeamRef(it.id, it.abbreviation, it.name, it.logoUrl) }
            .sortedBy { it.name.lowercase() }
    }

    private companion object {
        const val CricketSport = "cricket"
        const val SofaScoreFailed = "sofascore standings failed "
        const val WorldRugbyFailed = "world rugby failed "
        const val NoSource = "no source for "
        const val Colon = ": "
        const val NationalKey = "national"
        const val EspnSideMs = 10L * 60L * 1000L
        const val CrestCacheMs = 24L * 60L * 60L * 1000L

        /** ESPN's competitions of national sides: World Cups, test windows, Six Nations, Championship. */
        val CrestLeagues = listOf("rugby.164205", "rugby.289234", "rugby.17567", "rugby.180659", "rugby.244293", "rugby.289237")

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

/** (DPTV v2.9.19) When a league's board needs more than ESPN's current day, and how the parts join. */
internal object BoardRules {
    /** Nothing scheduled after [nowMs] on the board: the next games are on later days ESPN didn't send. */
    fun needsMonths(fixtures: List<Fixture>, nowMs: Long): Boolean = fixtures.none { it.dateMs > nowMs }

    /** [current] then [more], one per fixture id (the current board's copy kept: it is the freshest), by start. */
    fun join(current: List<Fixture>, more: List<Fixture>): List<Fixture> =
        (current + more).distinctBy { it.id }.sortedBy { it.dateMs }
}
