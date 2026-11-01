package tv.own.owntv.core.backup

import org.json.JSONObject

/**
 * Settings an app keeps in its own store (DPTV v2.6.0: Sport's DataStore), carried in the Settings
 * section of a backup under `appExtras.<backupKey>`. Older readers ignore the block; a backup without
 * it leaves the store as it is.
 */
interface BackupExtra {
    /** The block's name inside `appExtras`; stable across releases. */
    val backupKey: String

    suspend fun exportBackup(): JSONObject

    suspend fun importBackup(block: JSONObject)

    /**
     * (DPTV v2.8.1) What the profile shared through DPTV-Server carries of this store: by default all of
     * it; a store leaves out what belongs to one TV (ids of its own playlists, its own jobs). Null = none.
     */
    suspend fun exportForSync(): JSONObject? = exportBackup()

    /** (DPTV v2.8.1) Another TV's [exportForSync], merged in; keys it does not carry keep this TV's value. */
    suspend fun importFromSync(block: JSONObject) = importBackup(block)
}
