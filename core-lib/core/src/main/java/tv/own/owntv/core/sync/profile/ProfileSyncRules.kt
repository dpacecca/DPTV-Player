package tv.own.owntv.core.sync.profile

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.backup.BackupManager

/**
 * The rules of the DPTV-Server profile sync (DPTV v2.8.0), as pure functions so each is tested.
 *
 * The profile travels as this app's own backup JSON, for one profile and only the sections that
 * are the person rather than the TV: watch history (the last channel is its newest live entry),
 * progress, favourites, Customize and hand-made orders. Playlists, logins and the TV's settings
 * stay on each TV.
 */
object ProfileSyncRules {

    val Sections: Set<BackupManager.Section> = setOf(
        BackupManager.Section.CUSTOMIZE,
        BackupManager.Section.FAVORITES,
        BackupManager.Section.HISTORY,
        BackupManager.Section.RESUME,
        BackupManager.Section.MANUAL_REORDER,
    )

    /**
     * The Customize part alone. Unlike history, progress and favourites, which merge record by record,
     * a restore writes Customize back whole, the file's winning. So when this TV changed its own
     * Customize since it last synced, another TV's copy must not be merged over it: this TV's goes up
     * instead (see [sectionsToMerge]). Null when the file carries none.
     */
    fun customizeFingerprint(root: JSONObject): String? {
        val parts = JSONObject()
        for (key in CustomizeKeys) root.opt(key)?.let { parts.put(key, it) }
        if (parts.length() == 0) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** What to merge from another TV: everything, but Customize only when this TV has none of its own unsent. */
    fun sectionsToMerge(localCustomize: String?, lastSyncedCustomize: String?): Set<BackupManager.Section> =
        if (lastSyncedCustomize != null && localCustomize != lastSyncedCustomize) Sections - BackupManager.Section.CUSTOMIZE
        else Sections

    /** Tries at the compare-and-set: another TV wrote in between, so merge its profile and write again. */
    const val MaxRounds = 3

    /**
     * What decides whether this TV has something new to send: the backup with this TV's own ids for
     * the profile taken out (they never change for a TV, but they are not content either).
     */
    fun fingerprint(root: JSONObject): String {
        val copy = JSONObject(root.toString())
        copy.remove(ActiveProfileKey)
        val digest = MessageDigest.getInstance("SHA-256").digest(copy.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Another TV's profile, made to land on this TV's own profile. A restore matches profiles by name,
     * and each TV's one profile may be called anything ("Living room", "Bedroom"): the file's profile —
     * the one it marks active, else its first — is renamed to this TV's, and any other is dropped.
     * Null when the file holds no profile at all.
     */
    fun forThisTv(remote: JSONObject, localProfileName: String): JSONObject? {
        val profiles = remote.optJSONArray(ProfilesKey) ?: return null
        if (profiles.length() == 0) return null
        val wanted = remote.optLong(ActiveProfileKey, Long.MIN_VALUE)
        val chosen = (0 until profiles.length()).map { profiles.getJSONObject(it) }
            .firstOrNull { it.optLong("id", Long.MIN_VALUE) == wanted } ?: profiles.getJSONObject(0)
        val out = JSONObject(remote.toString())
        out.put(ProfilesKey, JSONArray().put(JSONObject(chosen.toString()).put("name", localProfileName)))
        out.put(ActiveProfileKey, chosen.optLong("id"))
        return out
    }

    /** The server's answer to a read: its revision and the stored profile (null before the first write). */
    data class Remote(val revision: Long, val data: JSONObject?)

    fun parseRemote(body: String): Remote? = runCatching {
        val o = JSONObject(body)
        if (!o.has("revision")) return null
        Remote(o.getLong("revision"), o.optJSONObject("data"))
    }.getOrNull()

    /** The revision a refused write (409) names as current, when it says. */
    fun staleRevision(body: String): Long? = runCatching {
        JSONObject(body).optJSONObject("detail")?.takeIf { it.has("revision") }?.getLong("revision")
    }.getOrNull()

    fun writeBody(baseRevision: Long, data: JSONObject, device: String): String =
        JSONObject().put("base_revision", baseRevision).put("device", device).put("data", data).toString()

    fun newRevision(body: String): Long? = runCatching { JSONObject(body).getLong("revision") }.getOrNull()

    /** The backup's Customize blocks (BackupManager, Section.CUSTOMIZE). */
    private val CustomizeKeys = listOf("customizations", "homeConfigs", "hideNewCategories")
    private const val ProfilesKey = "profiles"
    private const val ActiveProfileKey = "activeProfileId"
}

/** Where this TV's profile sync stands: which playlist it syncs through, the revision, what it sent. */
data class ProfileSyncState(
    val sourceId: Long,
    val revision: Long,
    val fingerprint: String?,
    val lastSyncAt: Long,
    /** [ProfileSyncRules.customizeFingerprint] of what this TV had when it last synced. */
    val customizeFingerprint: String? = null,
) {
    fun encode(): String = JSONObject()
        .put("src", sourceId).put("rev", revision).put("fp", fingerprint ?: "").put("at", lastSyncAt)
        .put("cfp", customizeFingerprint ?: "")
        .toString()

    companion object {
        fun decode(raw: String?): ProfileSyncState? = raw?.let {
            runCatching {
                val o = JSONObject(it)
                ProfileSyncState(
                    o.getLong("src"), o.getLong("rev"), o.optString("fp").ifEmpty { null }, o.optLong("at"),
                    o.optString("cfp").ifEmpty { null },
                )
            }.getOrNull()
        }
    }
}
