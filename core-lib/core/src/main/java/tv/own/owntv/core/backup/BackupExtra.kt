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
}
