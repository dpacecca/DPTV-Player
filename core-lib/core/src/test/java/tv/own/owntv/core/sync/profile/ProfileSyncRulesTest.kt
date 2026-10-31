package tv.own.owntv.core.sync.profile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** (DPTV v2.8.0) The DPTV-Server profile sync's rules. */
class ProfileSyncRulesTest {

    private fun backup(profileId: Long, name: String, history: List<String>) = JSONObject()
        .put("version", 26)
        .put("profiles", JSONArray().put(JSONObject().put("id", profileId).put("name", name)))
        .put("activeProfileId", profileId)
        .put("userData", JSONArray().apply { history.forEach { put(JSONObject().put("kind", "his").put("key", it)) } })

    @Test
    fun anotherTvsProfileLandsOnThisTvsOwnProfile() {
        val remote = backup(7, "Living room", listOf("a"))
        val mine = ProfileSyncRules.forThisTv(remote, "Bedroom")!!
        assertEquals("Bedroom", mine.getJSONArray("profiles").getJSONObject(0).getString("name"))
        assertEquals(1, mine.getJSONArray("profiles").length())
        assertEquals(7L, mine.getLong("activeProfileId"))
        // The original is left as it was.
        assertEquals("Living room", remote.getJSONArray("profiles").getJSONObject(0).getString("name"))
    }

    @Test
    fun theActiveProfileIsTheOneTaken() {
        val remote = backup(1, "Kids", emptyList())
        remote.getJSONArray("profiles").put(JSONObject().put("id", 2).put("name", "Main"))
        remote.put("activeProfileId", 2)
        val mine = ProfileSyncRules.forThisTv(remote, "Den")!!
        assertEquals(2L, mine.getJSONArray("profiles").getJSONObject(0).getLong("id"))
        assertEquals("Den", mine.getJSONArray("profiles").getJSONObject(0).getString("name"))
    }

    @Test
    fun aFileWithoutProfilesIsNotMerged() {
        assertNull(ProfileSyncRules.forThisTv(JSONObject().put("version", 26), "Den"))
    }

    @Test
    fun theFingerprintFollowsTheContentNotTheProfileId() {
        val a = backup(3, "TV", listOf("x"))
        val sameOnAnotherDay = backup(3, "TV", listOf("x")).put("activeProfileId", 9)
        val more = backup(3, "TV", listOf("x", "y"))
        assertEquals(ProfileSyncRules.fingerprint(a), ProfileSyncRules.fingerprint(sameOnAnotherDay))
        assertNotEquals(ProfileSyncRules.fingerprint(a), ProfileSyncRules.fingerprint(more))
    }

    @Test
    fun theServersAnswersAreRead() {
        val remote = ProfileSyncRules.parseRemote("""{"revision":4,"updated_at":1,"device":"Den","data":{"version":26}}""")!!
        assertEquals(4L, remote.revision)
        assertEquals(26, remote.data!!.getInt("version"))
        assertNull(ProfileSyncRules.parseRemote("""{"revision":0,"data":null}""")!!.data)
        assertNull(ProfileSyncRules.parseRemote("[]"))
        assertNull(ProfileSyncRules.parseRemote("not json"))
        assertEquals(5L, ProfileSyncRules.staleRevision("""{"detail":{"error":"stale","revision":5}}"""))
        assertEquals(9L, ProfileSyncRules.newRevision("""{"revision":9,"updated_at":2}"""))
        val body = JSONObject(ProfileSyncRules.writeBody(4, JSONObject().put("v", 1), "Den"))
        assertEquals(4L, body.getLong("base_revision"))
        assertEquals("Den", body.getString("device"))
    }

    @Test
    fun theStateSurvivesItsEncoding() {
        val state = ProfileSyncState(12, 4, "abc", 1_000L)
        assertEquals(state, ProfileSyncState.decode(state.encode()))
        assertEquals(state.copy(fingerprint = null), ProfileSyncState.decode(state.copy(fingerprint = null).encode()))
        assertNull(ProfileSyncState.decode("{}"))
        assertNull(ProfileSyncState.decode(null))
    }

    @Test
    fun thisTvsUnsentCustomizeIsNotOverwritten() {
        val withCustomize = backup(3, "TV", listOf("x")).put("customizations", JSONObject().put("cust_3_LIVE", "{}"))
        val synced = ProfileSyncRules.customizeFingerprint(withCustomize)
        val changed = ProfileSyncRules.customizeFingerprint(
            backup(3, "TV", listOf("x")).put("customizations", JSONObject().put("cust_3_LIVE", "{\"hiddenCats\":[\"a\"]}")),
        )
        val all = ProfileSyncRules.Sections
        // Unchanged here since the last sync: the other TVs' Customize comes in.
        assertEquals(all, ProfileSyncRules.sectionsToMerge(synced, synced))
        // Changed here: everything but Customize comes in, and this TV's goes up.
        assertEquals(all - tv.own.owntv.core.backup.BackupManager.Section.CUSTOMIZE, ProfileSyncRules.sectionsToMerge(changed, synced))
        // Never synced: take what the server has.
        assertEquals(all, ProfileSyncRules.sectionsToMerge(changed, null))
        assertNull(ProfileSyncRules.customizeFingerprint(backup(3, "TV", emptyList())))
        // History alone does not move the Customize fingerprint.
        assertEquals(synced, ProfileSyncRules.customizeFingerprint(JSONObject(withCustomize.toString()).put("userData", JSONArray())))
    }
}
