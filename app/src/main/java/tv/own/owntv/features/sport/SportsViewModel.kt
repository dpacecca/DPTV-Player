package tv.own.owntv.features.sport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.companion.CompanionController
import tv.own.owntv.core.companion.CompanionSelectItem
import tv.own.owntv.core.companion.CompanionServerState

/**
 * Sports hub state: ESPN boards per league (cached 15 minutes), the active-league set, follow sets,
 * the open match and league pages (v2.6.0, OwnTV's Sports Hub), channel matches and standings.
 *
 * Only **active** leagues (the user's selection, default = [DefaultActiveLeagueIds]) are loaded, so
 * the full [SportsLeagues] catalogue can be large without costing a request per league. Playback
 * itself stays in `LiveViewModel` — the screen calls it with a matched channel id, so there is
 * exactly one zap path.
 */
class SportsViewModel(
    private val prefs: SportsPrefs,
    private val api: SportsApi,
    private val matcher: SportsMatcher,
    private val companion: CompanionController,
    channelSources: SportChannelSources,
) : ViewModel() {

    /** Who is showing live Sport data right now; the live refresh runs only while someone is. */
    enum class Watcher { HUB, HOME }

    sealed interface BoardState {
        data object Loading : BoardState
        data class Content(val board: EspnApi.Scoreboard) : BoardState
        data object Error : BoardState
    }

    sealed interface MatchesState {
        data object Idle : MatchesState
        data object Loading : MatchesState
        data class Content(val matches: List<ChannelMatch>) : MatchesState
    }

    data class LeagueUi(
        val league: SportLeague,
        val liveCount: Int,
        val loading: Boolean,
    )

    /** Leagues the user activated (shown in the rail and loaded); default = the brand's starter set. */
    val activeLeagues: StateFlow<Set<String>> = prefs.followedLeagues
        .map { ids -> ids.filterTo(LinkedHashSet()) { sportsLeagueById(it) != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultActiveLeagueIds)

    val followedTeams: StateFlow<Set<String>> = prefs.followedTeams
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Followed teams' names by key (v2.6.0), for Settings › Sport › My teams. */
    val teamNames: StateFlow<Map<String, String>> = prefs.teamNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Followed individual athletes (tennis/MMA duel entries) — never racing/golf field entries. */
    val followedPlayers: StateFlow<Set<String>> = prefs.followedPlayers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val hubVisible: StateFlow<Boolean> = prefs.hubVisible
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Languages the matched channels are filtered to; empty = all. Default: German. */
    val preferredLanguages: StateFlow<Set<SportLanguage>> = prefs.preferredLanguages
        .map { names -> names.mapNotNull { runCatching { SportLanguage.valueOf(it) }.getOrNull() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportLanguages.default)

    /** Languages never shown among a fixture's channels (v2.2.0). */
    val hiddenLanguages: StateFlow<Set<SportLanguage>> = prefs.hiddenLanguages
        .map { names -> names.mapNotNull { runCatching { SportLanguage.valueOf(it) }.getOrNull() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** True once the language prompt was answered (or dismissed); it is shown at most once. */
    val languageAsked: StateFlow<Boolean> = prefs.languageAsked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The user's sport order (Settings › Sports, hold OK): every sport, stored ones first. */
    val sportOrder: StateFlow<List<SportGroup>> = prefs.sportOrder
        .map { SportsOrder.orderSports(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SportGroup.entries.toList())

    /** The user's league order (hold OK in the rail), as stored ids — see [SportsOrder.orderLeagues]. */
    private val leagueOrder: StateFlow<List<String>> = prefs.leagueOrder
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Live refresh interval in seconds (0 = off) — Sport settings › Channels. */
    val liveRefreshSeconds: StateFlow<Int> = prefs.liveRefreshSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, SportsSchedule.DefaultLiveRefreshSeconds)

    /** Auto rebuild interval in minutes (0 = off) — Sport settings › Channels. */
    val autoRebuildMinutes: StateFlow<Int> = prefs.autoRebuildMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, SportsSchedule.DefaultAutoRebuildMinutes)

    /** Playlists Sport matching may use (empty = all) — Sport settings › Channels. */
    val channelPlaylists: StateFlow<Set<Long>> = prefs.channelPlaylists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Strict channel filter (off by default) — Sport settings › Channels. */
    val strictChannels: StateFlow<Boolean> = prefs.strictChannels
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The live playlists the playlist filter offers. */
    val playlists: StateFlow<List<SportPlaylist>> = channelSources.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val watchers = MutableStateFlow<Set<Watcher>>(emptySet())

    /** Leagues that hold a followed team or athlete — loaded too, so Favourites can show them. */
    private val followedItemLeagues: StateFlow<Set<String>> = combine(followedTeams, followedPlayers) { teams, players ->
        (teams + players).mapNotNull { it.substringBefore(TeamKeySep).takeIf { id -> sportsLeagueById(id) != null } }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Active leagues plus the ones a followed team/athlete is in — the boards we fetch. */
    private val loadingLeagues: StateFlow<Set<String>> =
        combine(activeLeagues, followedItemLeagues) { active, followed -> active + followed }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultActiveLeagueIds)

    private val boards = MutableStateFlow<Map<String, BoardState>>(emptyMap())
    private val boardAt = HashMap<String, Long>()
    private val standingsCache = HashMap<String, List<LeagueTable>>()
    private val matchesCache = HashMap<String, List<ChannelMatch>>()

    /** The open match page (v2.6.0); null = the hub. */
    private val _selectedFixture = MutableStateFlow<Fixture?>(null)
    val selectedFixture: StateFlow<Fixture?> = _selectedFixture.asStateFlow()

    /** The open league page (v2.6.0); null = none. */
    private val _openLeagueId = MutableStateFlow<String?>(null)
    val openLeagueId: StateFlow<String?> = _openLeagueId.asStateFlow()

    /** The open league's board: Loading, its fixtures, or Error. */
    val openLeagueBoard: StateFlow<BoardState?> =
        combine(boards, _openLeagueId) { current, id -> id?.let { current[it] } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _matches = MutableStateFlow<MatchesState>(MatchesState.Idle)
    val matches: StateFlow<MatchesState> = _matches.asStateFlow()

    private val _standings = MutableStateFlow<List<LeagueTable>>(emptyList())
    val standings: StateFlow<List<LeagueTable>> = _standings.asStateFlow()

    private val _standingsLoading = MutableStateFlow(false)
    val standingsLoading: StateFlow<Boolean> = _standingsLoading.asStateFlow()

    /**
     * The active leagues with their live counts — the rail. In the user's order ([SportsOrder]):
     * leagues they sorted by hand first, the rest grouped by their sport order (sortedBy is stable,
     * so the catalogue order holds within a sport). A fresh install keeps the picker's order.
     */
    val leagues: StateFlow<List<LeagueUi>> =
        combine(boards, activeLeagues, leagueOrder, sportOrder) { current, active, order, sports ->
            SportsOrder.orderLeagues(SportsLeagues.filter { it.id in active }, order, sports).map { league ->
                val board = (current[league.id] as? BoardState.Content)?.board
                LeagueUi(
                    league = league,
                    liveCount = board?.fixtures?.count { it.state == FixtureState.LIVE } ?: 0,
                    loading = current[league.id] is BoardState.Loading,
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SportsLeagues.filter { it.defaultActive }.sortedBy { it.sportGroup.ordinal }.map { LeagueUi(it, 0, false) },
        )

    /**
     * Everything the user picked: every fixture of an active league plus games of followed teams or
     * athletes (even when that item's league is not active), live games first. The hub's source.
     */
    val favourites: StateFlow<List<Fixture>> =
        combine(boards, activeLeagues, followedTeams, followedPlayers) { current, active, teams, players ->
            current.values.filterIsInstance<BoardState.Content>()
                .flatMap { it.board.fixtures }
                .filter {
                    it.leagueId in active ||
                        involvesFollowedTeam(it, teams) ||
                        involvesFollowedPlayer(it, players)
                }
                .sortedWith(compareBy<Fixture> { it.state != FixtureState.LIVE }.thenBy { it.dateMs })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Each shown league's place in the user's order (v2.6.0): the hub sorts its rows by it. */
    val leagueRank: StateFlow<Map<String, Int>> = leagues
        .map { rail -> rail.withIndex().associate { it.value.league.id to it.index } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Home's Sports row (v5.2 port): live games and games kicking off soon from [favourites], live
     * first and the followed teams' games first among them (v2.6.0) — see [SportsSchedule.homeRow].
     * The minute ticker moves the "soon" window while Home is on screen; it only runs while the row
     * collects this.
     */
    val homeRow: StateFlow<List<Fixture>> =
        combine(favourites, leagueRank, followedTeams, followedPlayers, minuteTicker()) { favs, rank, teams, players, now ->
            SportsSchedule.homeRow(
                favs, now,
                mine = { involvesFollowedTeam(it, teams) || involvesFollowedPlayer(it, players) },
                leagueRank = { rank[it] ?: Int.MAX_VALUE },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True while any wanted board is still loading (the hub's first load). */
    val hubLoading: StateFlow<Boolean> =
        combine(loadingLeagues, boards) { want, b -> want.any { b[it] == null || b[it] is BoardState.Loading } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Wanted leagues whose board failed or no source could serve (the hub's "can't be reached"). */
    val failingLeagues: StateFlow<Set<String>> =
        combine(loadingLeagues, boards) { want, b ->
            want.filter { id -> b[id] is BoardState.Error || (b[id] as? BoardState.Content)?.board?.unavailable == true }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        // Settings moved into Settings › Sport (v2.6.0): bring an older file up to date once.
        viewModelScope.launch { runCatching { prefs.migrateIfNeeded() } }
        // Load exactly the active leagues (+ leagues of followed teams, for Favourites); re-run when
        // the user changes the selection, so adding/removing a league fetches/drops it live.
        viewModelScope.launch { loadingLeagues.collect { applyLeagues(it) } }
        startLiveRefresh()
        startAutoRebuild(channelSources)
    }

    /**
     * The live refresh (Sport settings › Channels › Live refresh): while the hub or Home is on screen,
     * every interval re-fetch the boards that have a live game ([SportsSchedule.dueLeagues]) and the
     * ones older than the 15-minute cache (so a hub or Home left open for hours still sees new
     * kick-offs) — quietly, so lists never blank out. Off, or nobody watching, costs nothing.
     */
    private fun startLiveRefresh() {
        viewModelScope.launch {
            combine(liveRefreshSeconds, watchers) { seconds, who -> if (who.isEmpty()) 0 else seconds }
                .distinctUntilChanged()
                .collectLatest { seconds ->
                    if (seconds <= 0) return@collectLatest
                    while (true) {
                        delay(seconds * 1_000L)
                        val loaded = boards.value.mapNotNull { (id, state) ->
                            (state as? BoardState.Content)?.let { id to it.board.fixtures }
                        }.toMap()
                        val now = System.currentTimeMillis()
                        val stale = loaded.keys.filter { now - (boardAt[it] ?: 0L) >= BoardTtlMs }
                        val due = SportsSchedule.dueLeagues(loaded, now) + stale
                        if (due.isEmpty()) continue
                        supervisorScope {
                            due.mapNotNull { sportsLeagueById(it) }
                                .map { async { loadBoard(it, force = true, quiet = true) } }
                                .awaitAll()
                        }
                    }
                }
        }
    }

    /**
     * The auto rebuild (Sport settings › Channels › Auto rebuild): every interval, and whenever a
     * playlist or a guide finished a sync, the cached channel lists of live/upcoming games are
     * dropped and the open game's list is matched again (quietly — the old list stays until the new
     * one is ready). Off disables both triggers; the manual "Rebuild now" still works.
     */
    private fun startAutoRebuild(channelSources: SportChannelSources) {
        viewModelScope.launch {
            autoRebuildMinutes.collectLatest { minutes ->
                if (minutes <= 0) return@collectLatest
                while (true) {
                    delay(minutes * 60_000L)
                    rebuildChannels()
                }
            }
        }
        viewModelScope.launch {
            // The first value is the state at start, not a change.
            channelSources.changes.drop(1).collect {
                if (autoRebuildMinutes.value > 0) rebuildChannels()
            }
        }
    }

    /** Mark [who] as showing (or no longer showing) live Sport data — drives the live refresh. */
    fun setWatching(who: Watcher, on: Boolean) {
        watchers.value = if (on) watchers.value + who else watchers.value - who
    }

    /** Rebuild the channel lists now (Sport settings › Channels › Rebuild now, and the schedule). */
    fun rebuildNow() {
        viewModelScope.launch { rebuildChannels() }
    }

    private suspend fun rebuildChannels() {
        val drop = matchesCache.keys.filter { id -> boardFixture(id)?.let(SportsSchedule::rebuildable) ?: true }
        drop.forEach { matchesCache.remove(it) }
        val fixture = _selectedFixture.value ?: return
        if (!SportsSchedule.rebuildable(fixture)) return
        val found = runMatch(fixture)
        matchesCache[fixture.id] = found
        if (_selectedFixture.value?.id == fixture.id) _matches.value = MatchesState.Content(found)
    }

    /** The loaded fixture with [id], from any board. */
    private fun boardFixture(id: String): Fixture? =
        boards.value.values.asSequence().filterIsInstance<BoardState.Content>()
            .flatMap { it.board.fixtures.asSequence() }.firstOrNull { it.id == id }

    /** One matcher call with every channel setting applied (languages, strict, playlists). */
    private suspend fun runMatch(fixture: Fixture): List<ChannelMatch> =
        runCatching {
            matcher.match(
                fixture,
                preferredLanguages.value,
                hidden = hiddenLanguages.value,
                strict = strictChannels.value,
                playlists = channelPlaylists.value,
            )
        }.getOrDefault(emptyList())

    /**
     * Open [fixture]'s match page (Home's Sports row, v2.6.0): Sport shows it at once, and Back
     * returns to the hub.
     */
    fun openFixture(fixture: Fixture) {
        _openLeagueId.value = null
        selectFixture(fixture)
    }

    /** Store the rail's order after a move (hold OK): the shown leagues first, see [SportsOrder.merge]. */
    fun saveLeagueOrder(visibleIds: List<String>) {
        viewModelScope.launch { prefs.setLeagueOrder(SportsOrder.merge(visibleIds, leagueOrder.value)) }
    }

    /** Store the sport order after a move in Settings › Sports. */
    fun saveSportOrder(order: List<SportGroup>) {
        viewModelScope.launch { prefs.setSportOrder(order.map { it.name }) }
    }

    /**
     * Store a new order for one sport's chosen leagues (Settings › Sport's Move window, v2.6.0): they
     * take the places those leagues hold in the current order, the others stay where they are.
     */
    fun saveLeagueOrderWithin(ids: List<String>) {
        val current = leagues.value.map { it.league.id }
        val moved = ids.toSet()
        val next = ids.iterator()
        saveLeagueOrder(current.map { id -> if (id in moved && next.hasNext()) next.next() else id })
    }

    /** ◀ ▶ on Live refresh / Auto rebuild (Settings › Sport): the previous or next choice. */
    fun stepLiveRefresh(step: Int) {
        viewModelScope.launch {
            prefs.setLiveRefreshSeconds(SportsSchedule.step(SportsSchedule.LiveRefreshChoices, liveRefreshSeconds.value, step))
        }
    }

    fun stepAutoRebuild(step: Int) {
        viewModelScope.launch {
            prefs.setAutoRebuildMinutes(SportsSchedule.step(SportsSchedule.AutoRebuildChoices, autoRebuildMinutes.value, step))
        }
    }

    /** All playlists again (the filter's "All playlists" row). */
    fun clearPlaylistFilter() {
        viewModelScope.launch {
            prefs.setChannelPlaylists(emptySet())
            rematch()
        }
    }

    /**
     * One playlist in or out of the filter. From "all" (empty) the first pick narrows to that one;
     * dropping the last one means all again — the language filter's rule.
     */
    fun togglePlaylist(id: Long) {
        viewModelScope.launch {
            val current = channelPlaylists.value
            val next = when {
                current.isEmpty() -> setOf(id)
                id in current -> current - id
                else -> current + id
            }
            prefs.setChannelPlaylists(next)
            rematch(playlists = next)
        }
    }

    fun toggleStrictChannels() {
        viewModelScope.launch {
            val on = !strictChannels.value
            prefs.setStrictChannels(on)
            rematch(strict = on)
        }
    }

    /** True when [fixture] involves a followed team or athlete (the hub's star and "My teams"). */
    fun isMine(fixture: Fixture): Boolean =
        involvesFollowedTeam(fixture, followedTeams.value) || involvesFollowedPlayer(fixture, followedPlayers.value)

    /** True when [fixture] involves one of the user's followed teams. */
    private fun involvesFollowedTeam(fixture: Fixture, teams: Set<String>): Boolean {
        val home = fixture.home?.abbreviation
        val away = fixture.away?.abbreviation
        return (home != null && teams.contains(SportsPrefs.teamKey(fixture.leagueId, home))) ||
            (away != null && teams.contains(SportsPrefs.teamKey(fixture.leagueId, away)))
    }

    /** True when [fixture] features one of the user's followed athletes (a duel entry). */
    private fun involvesFollowedPlayer(fixture: Fixture, players: Set<String>): Boolean =
        players.isNotEmpty() && fixture.entries.any { entry ->
            entry.id != null && players.contains(SportsPrefs.playerKey(fixture.leagueId, entry.id))
        }

    private suspend fun applyLeagues(want: Set<String>) {
        val open = _openLeagueId.value
        if (boards.value.keys.any { it !in want && it != open }) {
            boards.value = boards.value.filterKeys { it in want || it == open }
        }
        standingsCache.keys.retainAll(want)
        supervisorScope {
            SportsLeagues.filter { it.id in want }.map { league ->
                async { loadBoard(league, force = false) }
            }.awaitAll()
        }
    }

    /** Fetch every wanted board again now (the hub's ⟳ chip). */
    fun refreshAll() {
        val want = loadingLeagues.value
        viewModelScope.launch {
            supervisorScope {
                SportsLeagues.filter { it.id in want }.map { async { loadBoard(it, force = true) } }.awaitAll()
            }
        }
    }

    /**
     * Open a league's page (v2.6.0): its board (fetched when it is not one the hub loads) and its
     * table. Null closes it.
     */
    fun openLeague(id: String?) {
        _openLeagueId.value = id
        val league = id?.let { sportsLeagueById(it) } ?: return
        viewModelScope.launch {
            loadBoard(league, force = false, extra = true)
            loadStandings(league)
        }
    }

    /** Open (or with null close) a match page; its channels are matched and its table loaded. */
    fun selectFixture(fixture: Fixture?) {
        _selectedFixture.value = fixture
        if (fixture == null) {
            _matches.value = MatchesState.Idle
            return
        }
        sportsLeagueById(fixture.leagueId)?.let { league -> viewModelScope.launch { loadStandings(league) } }
        matchesCache[fixture.id]?.let {
            _matches.value = MatchesState.Content(it)
            return
        }
        _matches.value = MatchesState.Loading
        viewModelScope.launch {
            val found = runMatch(fixture)
            matchesCache[fixture.id] = found
            if (_selectedFixture.value?.id == fixture.id) _matches.value = MatchesState.Content(found)
        }
    }

    fun toggleLeagueFollowed(leagueId: String) {
        viewModelScope.launch {
            prefs.setLeagueFollowed(leagueId, activeLeagues.value.contains(leagueId).not())
        }
    }

    fun toggleTeamFollowed(leagueId: String, abbrev: String, name: String? = null) {
        viewModelScope.launch {
            val key = SportsPrefs.teamKey(leagueId, abbrev)
            prefs.setTeamFollowed(key, followedTeams.value.contains(key).not(), name)
        }
    }

    /** Stop following a team by its stored key (Settings › Sport › My teams). */
    fun unfollowTeam(key: String) {
        viewModelScope.launch { prefs.setTeamFollowed(key, false) }
    }

    /**
     * The teams of the chosen leagues (v2.6.0, Settings › Sport › My teams): ESPN's teams endpoint
     * per league, the SofaScore/TheSportsDB ones from their boards. Built once per [resetTeamIndex].
     */
    private var teamIndex: List<SportsTeamSearch.Hit>? = null
    private val teamIndexLock = kotlinx.coroutines.sync.Mutex()

    fun resetTeamIndex() {
        teamIndex = null
    }

    suspend fun searchTeams(query: String): List<SportsTeamSearch.Hit> {
        if (!SportsTeamSearch.searchable(query)) return emptyList()
        val index = teamIndexLock.withLock {
            teamIndex ?: supervisorScope {
                SportsLeagues.filter { it.id in activeLeagues.value && it.kind == LeagueKind.TEAMS }.map { league ->
                    async { runCatching { api.teams(league) }.getOrDefault(emptyList()).map { SportsTeamSearch.Hit(league.id, it) } }
                }.awaitAll().flatten()
            }.also { teamIndex = it }
        }
        return SportsTeamSearch.search(index, query)
    }

    fun togglePlayerFollowed(leagueId: String, playerId: String) {
        viewModelScope.launch {
            val key = SportsPrefs.playerKey(leagueId, playerId)
            prefs.setPlayerFollowed(key, followedPlayers.value.contains(key).not())
        }
    }

    /** Resolve matched ids to entities for the zap context, then hand off to the player caller. */
    fun playMatch(match: ChannelMatch, matches: List<ChannelMatch>, onPlay: (Long, List<tv.own.owntv.core.database.entity.ChannelEntity>) -> Unit) {
        viewModelScope.launch {
            val entities = runCatching { matcher.channels(matches.map { it.channelId }) }.getOrDefault(emptyList())
            onPlay(match.channelId, entities)
        }
    }

    /** Entities for the channel matches, in order — for the in-pane preview. */
    suspend fun entitiesFor(matches: List<ChannelMatch>): List<tv.own.owntv.core.database.entity.ChannelEntity> =
        runCatching { matcher.channels(matches.map { it.channelId }) }.getOrDefault(emptyList())

    fun setHubVisible(visible: Boolean) {
        viewModelScope.launch { prefs.setHubVisible(visible) }
    }

    /** Choose the languages matched channels are filtered to; empty = all. Marks the prompt answered. */
    fun setPreferredLanguages(languages: Set<SportLanguage>) {
        viewModelScope.launch {
            prefs.setPreferredLanguages(languages.map { it.name }.toSet())
            rematch(languages = languages)
        }
    }

    /** Hide a language from every fixture's channels for good, or show it again (v2.2.0). */
    fun toggleLanguageHidden(language: SportLanguage) {
        viewModelScope.launch {
            val hidden = hiddenLanguages.value.let { if (language in it) it - language else it + language }
            prefs.setHiddenLanguages(hidden.map { it.name }.toSet())
            rematch(hidden = hidden)
        }
    }

    /**
     * Matches are filtered inside the matcher (languages, strict filter, playlists), so the cache is
     * only valid for the settings it was built with — drop it and re-resolve the selected fixture.
     * The new value is passed in because the pref flow has not emitted it yet.
     */
    private suspend fun rematch(
        languages: Set<SportLanguage> = preferredLanguages.value,
        hidden: Set<SportLanguage> = hiddenLanguages.value,
        strict: Boolean = strictChannels.value,
        playlists: Set<Long> = channelPlaylists.value,
    ) {
        matchesCache.clear()
        val fixture = _selectedFixture.value ?: return
        _matches.value = MatchesState.Loading
        val found = runCatching {
            matcher.match(fixture, languages, hidden = hidden, strict = strict, playlists = playlists)
        }.getOrDefault(emptyList())
        matchesCache[fixture.id] = found
        if (_selectedFixture.value?.id == fixture.id) _matches.value = MatchesState.Content(found)
    }

    /** Remember that the language prompt was shown, so it does not reappear on the next fixture. */
    fun markLanguageAsked() {
        viewModelScope.launch { prefs.setLanguageAsked() }
    }

    // ---- Phone management (companion pick list) -------------------------------------------------

    /** The companion server's state while the "Manage on phone" panel is open (Idle otherwise). */
    val remoteState: StateFlow<CompanionServerState> = companion.state

    /**
     * Serve the pick list (leagues + every active league's teams and athletes) to a phone on the same
     * Wi-Fi. Teams come from ESPN's `/teams`; athletes from the already-loaded duel fixtures. Chosen
     * ids reconcile the active leagues, followed teams and followed athletes in one submit.
     *
     * Teams and athletes are children of their league ([CompanionSelectItem.parentId]), so the phone
     * page opens one level per league instead of one endless list; [teamGroup] and [playerGroup]
     * label the two tabs inside it.
     */
    fun startPhonePicker(
        port: Int,
        title: String,
        leagueItems: List<CompanionSelectItem>,
        leagueLabels: Map<String, String>,
        teamGroup: String,
        playerGroup: String,
    ) {
        viewModelScope.launch {
            val shownLeagues = activeLeagues.value
            val extra = ArrayList<CompanionSelectItem>()
            shownLeagues.mapNotNull { sportsLeagueById(it) }
                .sortedBy { leagueLabels[it.id] ?: it.id }
                .forEach { league ->
                    runCatching { api.teams(league) }.getOrDefault(emptyList()).forEach { team ->
                        val key = SportsPrefs.teamKey(league.id, team.abbreviation)
                        extra.add(
                            CompanionSelectItem(
                                TeamItemPrefix + key, team.name, teamGroup,
                                key in followedTeams.value, parentId = league.id,
                            ),
                        )
                    }
                    val seen = HashSet<String>()
                    boards.value[league.id].let { it as? BoardState.Content }?.board?.fixtures.orEmpty()
                        .flatMap { it.entries }
                        .filter { it.id != null && !it.id.startsWith("-") }
                        .sortedBy { it.name.lowercase() }
                        .forEach { entry ->
                            val id = entry.id ?: return@forEach
                            val key = SportsPrefs.playerKey(league.id, id)
                            if (seen.add(key)) {
                                extra.add(
                                    CompanionSelectItem(
                                        PlayerItemPrefix + key, entry.name, playerGroup,
                                        key in followedPlayers.value, parentId = league.id,
                                    ),
                                )
                            }
                        }
                }
            companion.startForSelectItems(port, title, leagueItems + extra) { ids ->
                viewModelScope.launch { applyPhoneSelection(ids, shownLeagues) }
            }
        }
    }

    /**
     * Apply a phone submit: the league ids become the active set; the team/athlete ids replace the
     * followed ones **for the leagues whose lists were shown** (follows in other leagues are kept).
     */
    private suspend fun applyPhoneSelection(ids: Set<String>, shownLeagues: Set<String>) {
        val leagueIds = ids.filterNot { it.startsWith(TeamItemPrefix) || it.startsWith(PlayerItemPrefix) }.toSet()
        val teamKeys = ids.filter { it.startsWith(TeamItemPrefix) }.map { it.removePrefix(TeamItemPrefix) }.toSet()
        val playerKeys = ids.filter { it.startsWith(PlayerItemPrefix) }.map { it.removePrefix(PlayerItemPrefix) }.toSet()
        val shownPrefixes = shownLeagues.map { "$it$TeamKeySep" }
        fun shownInLeagues(key: String) = shownPrefixes.any { key.startsWith(it) }
        prefs.setActiveLeagues(leagueIds)
        prefs.setFollowedTeams((followedTeams.value.filterNot { shownInLeagues(it) } + teamKeys).toSet())
        prefs.setFollowedPlayers((followedPlayers.value.filterNot { shownInLeagues(it) } + playerKeys).toSet())
        delay(1_500)
        companion.stop()
    }

    fun stopPhonePicker() = companion.stop()

    /**
     * Fetch one league's board. [quiet] (the live refresh) keeps the current board on screen while
     * fetching and on failure, so the lists never blank out or flash "Loading…" every 30 seconds.
     */
    private suspend fun loadBoard(league: SportLeague, force: Boolean, quiet: Boolean = false, extra: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            val at = boardAt[league.id] ?: 0L
            if (now - at < BoardTtlMs && boards.value[league.id] is BoardState.Content) return
            if (boards.value[league.id] is BoardState.Loading) return
        }
        val hadContent = boards.value[league.id] is BoardState.Content
        if (!(quiet && hadContent)) boards.value = boards.value + (league.id to BoardState.Loading)
        val result = runCatching { api.scoreboard(league) }
        // The source chain already logged its line (v2.6.0).
        result.onFailure { if (league.source == LeagueSource.ESPN) android.util.Log.w("SportsHub", "board failed " + league.id, it) }
        result.onSuccess { android.util.Log.d("SportsHub", "board ok " + league.id + " fixtures=" + it.fixtures.size) }
        boardAt[league.id] = now
        if (result.isFailure && quiet && hadContent) return
        // A SofaScore league no source could serve says so rather than an error, and is fetched
        // again by the next refresh once its board is stale (v2.5.0; "unavailable" v2.6.0).
        val board = result.getOrNull()
            ?: if (league.source == LeagueSource.SOFASCORE) EspnApi.Scoreboard(league.id, emptyList(), unavailable = true) else null
        // A league dropped from the selection while fetching must not come back (the open league
        // page's own board, v2.6.0, stays).
        if (league.id !in loadingLeagues.value && !(extra || league.id == _openLeagueId.value)) return
        // A followed team stored under its code alone gets its name from the board (v2.6.0).
        board?.let { fillTeamNames(league, it) }
        boards.value = boards.value + (league.id to (board?.let { BoardState.Content(it) } ?: BoardState.Error))
        // The open game's score and status follow the fresh board (same id, new values); its
        // channel list stays — the cache is keyed by id.
        val selected = _selectedFixture.value
        if (board != null && selected != null && selected.leagueId == league.id) {
            board.fixtures.firstOrNull { it.id == selected.id }?.takeIf { it != selected }?.let { _selectedFixture.value = it }
        }
    }

    private suspend fun fillTeamNames(league: SportLeague, board: EspnApi.Scoreboard) {
        val followed = runCatching { prefs.followedTeams.first() }.getOrDefault(emptySet())
        if (followed.none { it.startsWith(league.id + TeamKeySep) }) return
        val names = runCatching { prefs.teamNames.first() }.getOrDefault(emptyMap())
        val found = board.fixtures.flatMap { listOfNotNull(it.home, it.away) }.mapNotNull { team ->
            val key = SportsPrefs.teamKey(league.id, team.abbreviation)
            if (key in followed && names[key] != team.name) key to team.name else null
        }.toMap()
        if (found.isNotEmpty()) runCatching { prefs.putTeamNames(found) }
    }

    private suspend fun loadStandings(league: SportLeague) {
        standingsCache[league.id]?.let {
            _standings.value = it
            return
        }
        _standingsLoading.value = true
        val tables = runCatching { api.standings(league) }.getOrDefault(emptyList())
        standingsCache[league.id] = tables
        _standings.value = tables
        _standingsLoading.value = false
    }

    companion object {
        /** Pick-list id prefixes, so one phone submit can carry leagues, teams and athletes. */
        const val TeamItemPrefix = "team:"
        const val PlayerItemPrefix = "player:"

        /** Separator inside [SportsPrefs.teamKey] ("<leagueId>|<abbrev>"). */
        private const val TeamKeySep = '|'

        private const val BoardTtlMs = 15L * 60L * 1000L

        /** The current time, then once a minute, while collected (Home's "soon" window). */
        private fun minuteTicker() = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000L)
            }
        }
    }
}
