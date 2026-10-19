package tv.own.owntv.features.sport

/**
 * The one door Sport's data comes through (v2.5.0): each call goes to the league's own source
 * ([SportLeague.source]) — ESPN for nearly everything, SofaScore for the few competitions ESPN lacks.
 * Both answer in the same models, so [SportsViewModel] and everything after it stay source-blind.
 */
class SportsApi(private val espn: EspnApi, private val sofaScore: SofaScoreApi) {

    suspend fun scoreboard(league: SportLeague): EspnApi.Scoreboard = when (league.source) {
        LeagueSource.ESPN -> espn.scoreboard(league)
        LeagueSource.SOFASCORE -> sofaScore.scoreboard(league)
    }

    suspend fun standings(league: SportLeague): List<LeagueTable> = when (league.source) {
        LeagueSource.ESPN -> espn.standings(league)
        LeagueSource.SOFASCORE -> sofaScore.standings(league)
    }

    suspend fun teams(league: SportLeague): List<TeamRef> = when (league.source) {
        LeagueSource.ESPN -> espn.teams(league)
        LeagueSource.SOFASCORE -> sofaScore.teams(league)
    }
}
