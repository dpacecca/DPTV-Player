package tv.own.owntv.features.sport

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings › Sport (v2.6.0) over a v2.5 Sport file: every old choice carries over unchanged, the
 * followed teams gain names, and every key of the file rides in Settings backups.
 */
class SportsPrefsMigrationTest {

    private val leagues = stringSetPreferencesKey("followed_leagues")
    private val teams = stringSetPreferencesKey("followed_teams")
    private val hidden = stringSetPreferencesKey("hidden_languages")
    private val languages = stringSetPreferencesKey("preferred_languages")
    private val leagueOrder = stringPreferencesKey("league_order")
    private val sportOrder = stringPreferencesKey("sport_order")
    private val live = intPreferencesKey("live_refresh_seconds")
    private val rebuild = intPreferencesKey("auto_rebuild_minutes")
    private val playlists = stringSetPreferencesKey("channel_playlists")
    private val strict = booleanPreferencesKey("strict_channels")
    private val names = stringSetPreferencesKey("followed_team_names")
    private val version = intPreferencesKey("prefs_version")

    /** A v2.5.2 file as the owner's TV has it. */
    private fun v25() = mutablePreferencesOf(
        leagues to setOf("soccer.ger.1", "sofascore.431"),
        teams to setOf("soccer.ger.1|BAY", "sofascore.431|NZL"),
        hidden to setOf("ARABIC", "TURKISH"),
        languages to setOf("GERMAN", "ENGLISH"),
        leagueOrder to "sofascore.431,soccer.ger.1",
        sportOrder to "RUGBY_LEAGUE,SOCCER",
        live to 30,
        rebuild to 15,
        playlists to setOf("7"),
        strict to true,
    )

    @Test
    fun theV25ChoicesCarryOverAndTeamsGainNames() {
        val prefs = v25()
        SportsPrefs.migrate(prefs)
        assertEquals(setOf("soccer.ger.1", "sofascore.431"), prefs[leagues])
        assertEquals(setOf("soccer.ger.1|BAY", "sofascore.431|NZL"), prefs[teams])
        assertEquals(setOf("ARABIC", "TURKISH"), prefs[hidden])
        assertEquals(setOf("GERMAN", "ENGLISH"), prefs[languages])
        assertEquals("sofascore.431,soccer.ger.1", prefs[leagueOrder])
        assertEquals("RUGBY_LEAGUE,SOCCER", prefs[sportOrder])
        assertEquals(30, prefs[live])
        assertEquals(15, prefs[rebuild])
        assertEquals(setOf("7"), prefs[playlists])
        assertEquals(true, prefs[strict])
        assertEquals(mapOf("soccer.ger.1|BAY" to "BAY", "sofascore.431|NZL" to "NZL"), SportsPrefs.decodeNames(prefs[names]))
        assertEquals(SportsPrefs.CurrentVersion, prefs[version])
    }

    @Test
    fun migrationRunsOnceAndKeepsRealNames() {
        val prefs = v25()
        prefs[names] = SportsPrefs.encodeNames(mapOf("soccer.ger.1|BAY" to "Bayern Munich"))
        SportsPrefs.migrate(prefs)
        assertEquals("Bayern Munich", SportsPrefs.decodeNames(prefs[names])["soccer.ger.1|BAY"])
        // A second run changes nothing, even with a new team followed meanwhile.
        prefs[teams] = prefs[teams].orEmpty() + "soccer.eng.1|ARS"
        SportsPrefs.migrate(prefs)
        assertTrue("soccer.eng.1|ARS" !in SportsPrefs.decodeNames(prefs[names]))
    }

    @Test
    fun aBackupCarriesEveryValueAndRestoresIt() {
        val prefs = v25()
        SportsPrefs.migrate(prefs)
        val block = SportsPrefs.exportPrefs(prefs)
        val restored = mutablePreferencesOf()
        SportsPrefs.importPrefs(block, restored)
        assertEquals(prefs.asMap(), restored.asMap())
    }

    @Test
    fun aRestoreLeavesKeysTheBackupDoesNotCarry() {
        val here = mutablePreferencesOf(strict to true, live to 60)
        SportsPrefs.importPrefs(SportsPrefs.exportPrefs(mutablePreferencesOf(live to 120)), here)
        assertEquals(true, here[strict])
        assertEquals(120, here[live])
    }

    /** Every key SportsPrefs declares is in the backup — a new one cannot be forgotten (cf. SettingsBackupCoverageTest). */
    @Test
    fun everyDeclaredKeyIsBackedUp() {
        val declared = (SportsPrefs::class.java.declaredFields + SportsPrefs.Companion::class.java.declaredFields)
            .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { f ->
                f.isAccessible = true
                val owner = if (java.lang.reflect.Modifier.isStatic(f.modifiers)) null else SportsPrefs.Companion
                (f.get(owner) as Preferences.Key<*>).name
            }.toSet()
        assertTrue("reflection found too few keys: $declared", declared.size >= 15)
        assertEquals(declared, SportsPrefs.BackedUpKeys.map { it.name }.toSet())
    }
}
