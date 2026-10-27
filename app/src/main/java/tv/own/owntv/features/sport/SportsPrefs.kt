package tv.own.owntv.features.sport

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.backup.BackupExtra

private val Context.sportsDataStore by preferencesDataStore(name = SportsFileName)

private const val SportsFileName = "sports"

/**
 * Followed leagues/teams for the Sports hub, in the app's own DataStore file (core settings stay
 * untouched, so upstream merges never conflict here). Settings › Sport edits every value here, and
 * the whole file rides in Settings backups as an app block (v2.6.0, [BackupExtra]).
 */
class SportsPrefs(private val context: Context) : BackupExtra {

    /** Followed teams' names by [teamKey] (v2.6.0), for Settings › Sport › My teams. */
    val teamNames: Flow<Map<String, String>> =
        context.sportsDataStore.data.map { decodeNames(it[TeamNamesKey]) }

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

    /** (v2.6.2) The games to record once their channel is known ([SportRecordingPlanner]). */
    val recordPlans: Flow<List<RecordPlan>> =
        context.sportsDataStore.data.map { prefs -> prefs[RecordPlansKey].orEmpty().mapNotNull(RecordPlan::decode).sortedBy { it.kickoffMs } }

    /** Add (or reset to waiting) the plan for [plan]'s game. */
    suspend fun putRecordPlan(plan: RecordPlan) {
        context.sportsDataStore.edit { prefs ->
            val rest = prefs[RecordPlansKey].orEmpty().filter { RecordPlan.decode(it)?.fixtureId != plan.fixtureId }
            prefs[RecordPlansKey] = (rest + plan.encode()).toSet()
        }
    }

    suspend fun removeRecordPlan(fixtureId: String) {
        context.sportsDataStore.edit { prefs ->
            prefs[RecordPlansKey] = prefs[RecordPlansKey].orEmpty().filter { RecordPlan.decode(it)?.fixtureId != fixtureId }.toSet()
        }
    }

    /**
     * The planner's results: each plan it looked at ([before]) becomes its outcome in [after] (or goes),
     * unless the user changed or removed it meanwhile; plans added meanwhile stay.
     */
    suspend fun replaceRecordPlans(before: List<RecordPlan>, after: List<RecordPlan>) {
        context.sportsDataStore.edit { prefs ->
            prefs[RecordPlansKey] = mergeRecordPlans(prefs[RecordPlansKey].orEmpty(), before, after)
        }
    }

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

    suspend fun setTeamFollowed(teamKey: String, followed: Boolean, name: String? = null) {
        context.sportsDataStore.edit { prefs ->
            val current = prefs[TeamsKey].orEmpty()
            prefs[TeamsKey] = if (followed) current + teamKey else current - teamKey
            // The name travels with the follow (v2.6.0), so My teams can list it without a board.
            val names = decodeNames(prefs[TeamNamesKey])
            val next = if (followed && name != null) names + (teamKey to name) else if (followed) names else names - teamKey
            prefs[TeamNamesKey] = encodeNames(next)
        }
    }

    /** Store names for followed teams (v2.6.0: a board names a team stored under its code). */
    suspend fun putTeamNames(names: Map<String, String>) {
        context.sportsDataStore.edit { prefs -> prefs[TeamNamesKey] = encodeNames(decodeNames(prefs[TeamNamesKey]) + names) }
    }

    /** Run [migrate] once on this device's file (v2.6.0). */
    suspend fun migrateIfNeeded() {
        if ((context.sportsDataStore.data.first()[VersionKey] ?: 0) >= CurrentVersion) return
        context.sportsDataStore.edit { migrate(it) }
    }

    override val backupKey: String get() = BackupBlock

    override suspend fun exportBackup(): JSONObject = exportPrefs(context.sportsDataStore.data.first())

    override suspend fun importBackup(block: JSONObject) {
        context.sportsDataStore.edit { prefs ->
            importPrefs(block, prefs)
            migrate(prefs)
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
        /** The migrations' version (v2.6.0 = 260): the settings moved into Settings › Sport. */
        internal const val CurrentVersion = 260

        /**
         * Bring an older file up to [CurrentVersion]. Every v2.5 key (followed leagues and teams, the
         * orders, the languages and hidden languages, the channel options) keeps its name and meaning,
         * so Settings › Sport reads them as they are; only the followed teams gain a name, their short
         * code until a board or the team search supplies the real one.
         */
        internal fun migrate(prefs: MutablePreferences) {
            if ((prefs[VersionKey] ?: 0) >= CurrentVersion) return
            val names = decodeNames(prefs[TeamNamesKey])
            val missing = prefs[TeamsKey].orEmpty().filter { it !in names }
                .associateWith { it.substringAfter(TeamSeparator) }
            if (missing.isNotEmpty()) prefs[TeamNamesKey] = encodeNames(names + missing)
            prefs[VersionKey] = CurrentVersion
        }

        /** Every key this file holds, each with how it is written to a backup. */
        internal val BackedUpStringSets get() = listOf(
            LeaguesKey, TeamsKey, PlayersKey, LanguagesKey, HiddenLanguagesKey, PlaylistsKey, TeamNamesKey,
            RecordPlansKey,
        )
        internal val BackedUpBools get() = listOf(VisibleKey, LanguageAskedKey, StrictKey)
        internal val BackedUpStrings get() = listOf(LeagueOrderKey, SportOrderKey)
        internal val BackedUpInts get() = listOf(LiveRefreshKey, AutoRebuildKey, VersionKey)

        /** The keys a backup carries ([exportPrefs]); the coverage test holds every declared key to it. */
        internal val BackedUpKeys: List<Preferences.Key<*>>
            get() = BackedUpStringSets + BackedUpBools + BackedUpStrings + BackedUpInts

        /** The file as one JSON object: only the keys that are set, so a restore leaves defaults alone. */
        internal fun exportPrefs(prefs: Preferences): JSONObject = JSONObject().apply {
            BackedUpStringSets.forEach { k -> prefs[k]?.let { put(k.name, JSONArray(it.sorted())) } }
            BackedUpBools.forEach { k -> prefs[k]?.let { put(k.name, it) } }
            BackedUpStrings.forEach { k -> prefs[k]?.let { put(k.name, it) } }
            BackedUpInts.forEach { k -> prefs[k]?.let { put(k.name, it) } }
        }

        /** Write a backup's block back; keys it does not carry keep this device's value. */
        internal fun importPrefs(block: JSONObject, into: MutablePreferences) {
            BackedUpStringSets.forEach { k ->
                block.optJSONArray(k.name)?.let { a -> into[k] = (0 until a.length()).map { a.optString(it) }.toSet() }
            }
            BackedUpBools.forEach { k -> if (block.has(k.name)) into[k] = block.optBoolean(k.name) }
            BackedUpStrings.forEach { k -> if (block.has(k.name)) into[k] = block.optString(k.name) }
            BackedUpInts.forEach { k -> if (block.has(k.name)) into[k] = block.optInt(k.name) }
        }

        /** See [replaceRecordPlans]. */
        internal fun mergeRecordPlans(current: Set<String>, before: List<RecordPlan>, after: List<RecordPlan>): Set<String> {
            val seen = before.associate { it.fixtureId to it.encode() }
            val outcome = after.associateBy { it.fixtureId }
            return current.mapNotNull { raw ->
                val id = RecordPlan.decode(raw)?.fixtureId ?: return@mapNotNull null
                if (seen[id] == raw) outcome[id]?.encode() else raw
            }.toSet()
        }

        /** "key<US>name" entries; a team key never contains the unit separator. */
        internal fun decodeNames(raw: Set<String>?): Map<String, String> =
            raw.orEmpty().mapNotNull { e -> e.indexOf(NameSeparator).takeIf { it > 0 }?.let { e.substring(0, it) to e.substring(it + 1) } }.toMap()

        internal fun encodeNames(names: Map<String, String>): Set<String> =
            names.map { (k, v) -> k + NameSeparator + v }.toSet()

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
        private val TeamNamesKey = stringSetPreferencesKey("followed_team_names")
        private val VersionKey = intPreferencesKey("prefs_version")
        private val RecordPlansKey = stringSetPreferencesKey("record_plans")
        private const val BackupBlock = "sport"
        private const val NameSeparator = '\u001F'
        private val DefaultLanguageNames = setOf(SportLanguage.GERMAN.name)
        private const val TeamSeparator = "|"
    }
}
