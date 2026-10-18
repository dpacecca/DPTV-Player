package tv.own.owntv.features.sport

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sportsDataStore by preferencesDataStore(name = SportsFileName)

private const val SportsFileName = "sports"

/**
 * Followed leagues/teams for the Sports hub, in the app's own DataStore file (core settings stay
 * untouched, so upstream merges never conflict here).
 */
class SportsPrefs(private val context: Context) {

    val followedLeagues: Flow<Set<String>> =
        context.sportsDataStore.data.map { it[LeaguesKey] ?: DefaultActiveLeagueIds }

    val followedTeams: Flow<Set<String>> =
        context.sportsDataStore.data.map { it[TeamsKey].orEmpty() }

    /** Followed individual athletes ([playerKey]): tennis players, MMA fighters — never F1 drivers. */
    val followedPlayers: Flow<Set<String>> =
        context.sportsDataStore.data.map { it[PlayersKey].orEmpty() }

    val hubVisible: Flow<Boolean> =
        context.sportsDataStore.data.map { it[VisibleKey] ?: true }

    /**
     * The languages the matched channels are filtered to, as [SportLanguage] names. An empty set
     * means "all languages" (no filter); an absent key is the first-run default (German).
     */
    val preferredLanguages: Flow<Set<String>> =
        context.sportsDataStore.data.map { it[LanguagesKey] ?: DefaultLanguageNames }

    /** Languages never shown among a fixture's channels (v2.2.0), as [SportLanguage] names. */
    val hiddenLanguages: Flow<Set<String>> =
        context.sportsDataStore.data.map { it[HiddenLanguagesKey].orEmpty() }

    /** True once the user has been asked for a language (or picked channels); the prompt never nags. */
    val languageAsked: Flow<Boolean> =
        context.sportsDataStore.data.map { it[LanguageAskedKey] ?: false }

    /**
     * The user's own league order (v5.2 port: hold OK in the league rail), as league ids. Leagues not
     * listed follow after it — see [SportsOrder.orderLeagues]. Empty = catalogue order.
     */
    val leagueOrder: Flow<List<String>> =
        context.sportsDataStore.data.map { SportsOrder.decode(it[LeagueOrderKey]) }

    /** The user's own sport order (hold OK in Settings › Sports), as [SportGroup] names. */
    val sportOrder: Flow<List<String>> =
        context.sportsDataStore.data.map { SportsOrder.decode(it[SportOrderKey]) }

    /** How often live scores refresh while Sport or its Home row is on screen, in seconds; 0 = off. */
    val liveRefreshSeconds: Flow<Int> =
        context.sportsDataStore.data.map { it[LiveRefreshKey] ?: SportsSchedule.DefaultLiveRefreshSeconds }

    /** How often the channel lists of open/live fixtures are rebuilt, in minutes; 0 = off. */
    val autoRebuildMinutes: Flow<Int> =
        context.sportsDataStore.data.map { it[AutoRebuildKey] ?: SportsSchedule.DefaultAutoRebuildMinutes }

    /** The live playlists Sport matching may use, as source ids; empty = all of them. */
    val channelPlaylists: Flow<Set<Long>> =
        context.sportsDataStore.data.map { prefs -> prefs[PlaylistsKey].orEmpty().mapNotNull { it.toLongOrNull() }.toSet() }

    /** Strict channel filter: only channels naming both sides (or the event). Off by default. */
    val strictChannels: Flow<Boolean> =
        context.sportsDataStore.data.map { it[StrictKey] ?: false }

    suspend fun setLeagueOrder(ids: List<String>) {
        context.sportsDataStore.edit { prefs -> prefs[LeagueOrderKey] = SportsOrder.encode(ids) }
    }

    suspend fun setSportOrder(names: List<String>) {
        context.sportsDataStore.edit { prefs -> prefs[SportOrderKey] = SportsOrder.encode(names) }
    }

    suspend fun setLiveRefreshSeconds(seconds: Int) {
        context.sportsDataStore.edit { prefs -> prefs[LiveRefreshKey] = seconds }
    }

    suspend fun setAutoRebuildMinutes(minutes: Int) {
        context.sportsDataStore.edit { prefs -> prefs[AutoRebuildKey] = minutes }
    }

    suspend fun setChannelPlaylists(ids: Set<Long>) {
        context.sportsDataStore.edit { prefs -> prefs[PlaylistsKey] = ids.map { it.toString() }.toSet() }
    }

    suspend fun setStrictChannels(on: Boolean) {
        context.sportsDataStore.edit { prefs -> prefs[StrictKey] = on }
    }

    suspend fun setLeagueFollowed(leagueId: String, followed: Boolean) {
        context.sportsDataStore.edit { prefs ->
            val current = prefs[LeaguesKey] ?: DefaultActiveLeagueIds
            prefs[LeaguesKey] = if (followed) current + leagueId else current - leagueId
        }
    }

    /** Replace the whole active-league set at once (the phone/companion picker). */
    suspend fun setActiveLeagues(leagueIds: Set<String>) {
        context.sportsDataStore.edit { prefs -> prefs[LeaguesKey] = leagueIds }
    }

    suspend fun setTeamFollowed(teamKey: String, followed: Boolean) {
        context.sportsDataStore.edit { prefs ->
            val current = prefs[TeamsKey].orEmpty()
            prefs[TeamsKey] = if (followed) current + teamKey else current - teamKey
        }
    }

    suspend fun setPlayerFollowed(playerKey: String, followed: Boolean) {
        context.sportsDataStore.edit { prefs ->
            val current = prefs[PlayersKey].orEmpty()
            prefs[PlayersKey] = if (followed) current + playerKey else current - playerKey
        }
    }

    /** Replace the whole followed-team set at once (the phone picker reconciles in one write). */
    suspend fun setFollowedTeams(keys: Set<String>) {
        context.sportsDataStore.edit { prefs -> prefs[TeamsKey] = keys }
    }

    /** Replace the whole followed-athlete set at once (the phone picker). */
    suspend fun setFollowedPlayers(keys: Set<String>) {
        context.sportsDataStore.edit { prefs -> prefs[PlayersKey] = keys }
    }

    suspend fun setHubVisible(visible: Boolean) {
        context.sportsDataStore.edit { prefs -> prefs[VisibleKey] = visible }
    }

    /** Replace the preferred channel languages; picking any language also marks the prompt as seen. */
    suspend fun setPreferredLanguages(names: Set<String>) {
        context.sportsDataStore.edit { prefs ->
            prefs[LanguagesKey] = names
            prefs[LanguageAskedKey] = true
        }
    }

    suspend fun setHiddenLanguages(names: Set<String>) {
        context.sportsDataStore.edit { prefs -> prefs[HiddenLanguagesKey] = names }
    }

    /** Mark the language prompt as answered without changing the choice (dismissed first run). */
    suspend fun setLanguageAsked() {
        context.sportsDataStore.edit { prefs -> prefs[LanguageAskedKey] = true }
    }

    companion object {
        fun teamKey(leagueId: String, teamAbbrev: String): String = leagueId + TeamSeparator + teamAbbrev

        /** Key for a followed individual athlete (tennis player / MMA fighter): `leagueId|playerId`. */
        fun playerKey(leagueId: String, playerId: String): String = leagueId + TeamSeparator + playerId

        private val LeaguesKey = stringSetPreferencesKey("followed_leagues")
        private val TeamsKey = stringSetPreferencesKey("followed_teams")
        private val PlayersKey = stringSetPreferencesKey("followed_players")
        private val VisibleKey = booleanPreferencesKey("hub_visible")
        private val LanguagesKey = stringSetPreferencesKey("preferred_languages")
        private val LanguageAskedKey = booleanPreferencesKey("language_asked")
        private val HiddenLanguagesKey = stringSetPreferencesKey("hidden_languages")
        private val LeagueOrderKey = stringPreferencesKey("league_order")
        private val SportOrderKey = stringPreferencesKey("sport_order")
        private val LiveRefreshKey = intPreferencesKey("live_refresh_seconds")
        private val AutoRebuildKey = intPreferencesKey("auto_rebuild_minutes")
        private val PlaylistsKey = stringSetPreferencesKey("channel_playlists")
        private val StrictKey = booleanPreferencesKey("strict_channels")
        private val DefaultLanguageNames = setOf(SportLanguage.GERMAN.name)
        private const val TeamSeparator = "|"
    }
}
