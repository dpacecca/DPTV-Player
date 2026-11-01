package tv.own.owntv.core.jellyfin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.jellyfinStore: DataStore<Preferences> by preferencesDataStore(name = "dptv_jellyfin")

/**
 * The Jellyfin sign-in (DPTV v2.9.0). Holds the session token Jellyfin issued, never the password,
 * and stays on this TV: it is not in backups or the shared profile (each TV signs in itself, and
 * Jellyfin lists each as its own device).
 */
class JellyfinStore(private val context: Context) {

    val account: Flow<JellyfinAccount?> = context.jellyfinStore.data.map { p ->
        val server = p[Server] ?: return@map null
        val token = p[Token] ?: return@map null
        val userId = p[UserId] ?: return@map null
        JellyfinAccount(server, userId, p[UserName].orEmpty(), token, p[DeviceId].orEmpty())
    }

    suspend fun current(): JellyfinAccount? = account.first()

    /** This TV's device id towards Jellyfin, made on first use. */
    suspend fun deviceId(): String {
        context.jellyfinStore.data.first()[DeviceId]?.let { return it }
        val id = "dptv-" + UUID.randomUUID().toString()
        context.jellyfinStore.edit { it[DeviceId] = id }
        return id
    }

    suspend fun save(account: JellyfinAccount) {
        context.jellyfinStore.edit {
            it[Server] = account.server
            it[UserId] = account.userId
            it[UserName] = account.userName
            it[Token] = account.token
            it[DeviceId] = account.deviceId
        }
    }

    /** Signs out here; the device id stays, so signing in again is the same device to Jellyfin. */
    suspend fun clear() {
        context.jellyfinStore.edit {
            it.remove(Token)
            it.remove(UserId)
            it.remove(UserName)
        }
    }

    /** The last server typed, to fill the form again. */
    val lastServer: Flow<String?> = context.jellyfinStore.data.map { it[Server] }

    private companion object {
        val Server = stringPreferencesKey("server")
        val UserId = stringPreferencesKey("user_id")
        val UserName = stringPreferencesKey("user_name")
        val Token = stringPreferencesKey("token")
        val DeviceId = stringPreferencesKey("device_id")
    }
}
