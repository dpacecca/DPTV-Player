package tv.own.owntv.core.sync.profile

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.backup.BackupContainer
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.recording.RecorderSignInRules
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.schedule.SyncStateStore

/**
 * The profile shared through DPTV-Server (DPTV v2.8.0): every TV signed in with one XC login keeps
 * one watch history, progress, favourites, last channel and Customize between them.
 *
 * A sync reads the server's profile, merges it into this TV when another TV wrote since (the same
 * merge a restore and Local sync use, deletions included), then writes this TV's profile back when it
 * holds something the server has not seen. The write names the revision it merged from; when another
 * TV wrote in between, the server refuses it and the round runs again ([ProfileSyncRules.MaxRounds]).
 * Only for a playlist the change check found to be DPTV-Server; elsewhere it does nothing.
 *
 * It runs when the app opens and goes to the background, and after every change check (15 minutes).
 * Never logs a URL or a credential.
 */
class ServerProfileSync(
    private val context: Context,
    private val http: HttpClient,
    private val xtream: XtreamClient,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val settings: SettingsRepository,
    private val checks: SyncStateStore,
    private val backups: BackupManager,
) {
    enum class Outcome { OFF, NO_SERVER, UP_TO_DATE, RECEIVED, SENT, TOO_LARGE, FAILED }

    private val mutex = Mutex()
    private val _last = MutableStateFlow<Outcome?>(null)

    /** How the last sync ended, for Settings. */
    val last: StateFlow<Outcome?> = _last.asStateFlow()

    suspend fun sync(why: String): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                signInToRecorder()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "recorder sign-in failed: ${e.javaClass.simpleName}")
            }
            val outcome = try {
                syncLocked()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "profile sync failed why=$why: ${e.javaClass.simpleName}")
                Outcome.FAILED
            }
            Log.i(TAG, "profile sync why=$why outcome=$outcome")
            _last.value = outcome
            outcome
        }
    }

    /** The playlist the profile travels through: one of this profile's Xtream playlists on DPTV-Server. */
    suspend fun serverSource(profileId: Long): SourceEntity? {
        val linked = sourceDao.allLinks().filter { it.profileId == profileId }.map { it.sourceId }.toSet()
        val previous = ProfileSyncState.decode(settings.profileSyncState.first())?.sourceId
        val candidates = sourceDao.getAllOnce().filter {
            it.id in linked && it.type == SourceType.XTREAM && checks.check(it.id)?.isDptv == true
        }
        return candidates.firstOrNull { it.id == previous } ?: candidates.minByOrNull { it.id }
    }

    /**
     * (DPTV v2.9.14) With no recorder set on this TV, sign in to the one DPTV-Server advertises with
     * the playlist's login, so recording to the server needs no key typed in. A recorder set in
     * Settings by hand is never touched. A key the recorder stops taking is dropped and replaced.
     * Runs whether or not profile sync is on; never logs the key, the login or a URL.
     */
    private suspend fun signInToRecorder() {
        val origin = settings.recorderOrigin()
        if (origin.setByHand) return
        val pid = settings.activeProfileId.first().takeIf { it >= 0 } ?: return
        val source = serverSource(pid) ?: return
        val username = source.username.orEmpty()
        val password = source.password.orEmpty()
        if (username.isEmpty() || password.isEmpty()) return
        val advertised = RecorderSignInRules.advertisedRecorder(http.getText(xtream.actionUrl(source, ""), source.userAgent))
        val signedInAs = advertised?.let { RecorderSignInRules.signedInAs(it, username, source.id) }
        when (RecorderSignInRules.step(origin, advertised, signedInAs)) {
            RecorderSignInRules.Step.KEEP -> return
            RecorderSignInRules.Step.CHECK -> {
                val now = System.currentTimeMillis()
                if (now - lastRecorderCheck < RECORDER_CHECK_EVERY_MS) return
                lastRecorderCheck = now
                val code = try {
                    http.getText("$advertised/api/sync/status", headers = mapOf("Authorization" to "Bearer ${origin.key}"))
                    200
                } catch (e: HttpClient.HttpStatusException) {
                    e.code
                }
                if (code != 401) return
                Log.i(TAG, "recorder no longer takes this device's key: signing in again")
                settings.clearRecorderSignIn()
            }
            RecorderSignInRules.Step.SIGN_IN -> {
                // A refused or failed sign-in waits an hour, not the next 15-minute check.
                if (System.currentTimeMillis() - lastSignInFailure < SIGN_IN_RETRY_MS) return
            }
        }
        val identity = settings.deviceIdentity()
        val (code, answer) = http.postJson(
            "$advertised/api/auth/dptv",
            RecorderSignInRules.requestBody(username, password, RecorderSignInRules.deviceName(identity.name, identity.id)),
        )
        val key = if (code == 200) RecorderSignInRules.issuedKey(answer) else null
        Log.i(TAG, "recorder sign-in code=$code ${if (key != null) "signed in" else "not signed in"}")
        if (key != null && signedInAs != null) {
            settings.setRecorderSignedIn(advertised!!, key, signedInAs)
            lastSignInFailure = 0L
        } else {
            lastSignInFailure = System.currentTimeMillis()
        }
    }

    @Volatile private var lastRecorderCheck = 0L
    @Volatile private var lastSignInFailure = 0L

    private suspend fun syncLocked(): Outcome {
        if (!settings.profileServerSync.first()) return Outcome.OFF
        val pid = settings.activeProfileId.first().takeIf { it >= 0 } ?: return Outcome.NO_SERVER
        val profile = profileDao.getById(pid) ?: return Outcome.NO_SERVER
        val source = serverSource(pid) ?: return Outcome.NO_SERVER
        var state = ProfileSyncState.decode(settings.profileSyncState.first())
            ?.takeIf { it.sourceId == source.id } ?: ProfileSyncState(source.id, 0, null, 0)
        var received = false
        repeat(ProfileSyncRules.MaxRounds) {
            val remote = ProfileSyncRules.parseRemote(http.getText(xtream.actionUrl(source, ACTION_READ), source.userAgent))
                ?: return Outcome.FAILED
            if (remote.data != null && remote.revision != state.revision) {
                ProfileSyncRules.forThisTv(remote.data, profile.name)?.let { theirs ->
                    // This TV's own unsent Customize stays and goes up; otherwise the other TVs' comes in.
                    val mine = export(pid) ?: return Outcome.FAILED
                    merge(theirs, ProfileSyncRules.sectionsToMerge(ProfileSyncRules.customizeFingerprint(mine), state.customizeFingerprint))
                    // (v2.8.1) Sport's settings the same way: the other TV's, unless this TV changed its own.
                    if (ProfileSyncRules.mergeExtras(ProfileSyncRules.extrasFingerprint(mine), state.extrasFingerprint)) {
                        theirs.optJSONObject(ProfileSyncRules.ExtrasKey)?.let { backups.importExtrasFromSync(it) }
                    }
                }
                received = true
            }
            val local = export(pid) ?: return Outcome.FAILED
            val fingerprint = ProfileSyncRules.fingerprint(local)
            val customize = ProfileSyncRules.customizeFingerprint(local)
            val extras = ProfileSyncRules.extrasFingerprint(local)
            val now = System.currentTimeMillis()
            if (fingerprint == state.fingerprint && remote.data != null) {
                // Nothing here the server lacks: note the revision merged and stop.
                state = state.copy(revision = remote.revision, lastSyncAt = now, customizeFingerprint = customize, extrasFingerprint = extras)
                settings.setProfileSyncState(state.encode())
                return if (received) Outcome.RECEIVED else Outcome.UP_TO_DATE
            }
            val (code, answer) = http.postJson(
                xtream.actionUrl(source, ACTION_WRITE),
                ProfileSyncRules.writeBody(remote.revision, local, settings.deviceIdentity().name),
                source.userAgent,
            )
            when (code) {
                200 -> {
                    state = ProfileSyncState(source.id, ProfileSyncRules.newRevision(answer) ?: (remote.revision + 1), fingerprint, now, customize, extras)
                    settings.setProfileSyncState(state.encode())
                    return Outcome.SENT
                }
                // Another TV wrote first: merge what it wrote, then try again.
                409 -> Unit
                413 -> return Outcome.TOO_LARGE
                else -> return Outcome.FAILED
            }
        }
        return Outcome.FAILED
    }

    private suspend fun export(profileId: Long): JSONObject? {
        val folder = workDir()
        val path = backups.export(folder, ProfileSyncRules.Sections, null, setOf(profileId), recordAsBackup = false).getOrNull()
            ?: return null
        val file = File(path)
        return try {
            JSONObject(BackupContainer.open(file).json).apply {
                // (v2.8.1) The app's own settings (Sport) ride along; a backup carries them only with Settings.
                backups.exportExtrasForSync().takeIf { it.length() > 0 }?.let { put(ProfileSyncRules.ExtrasKey, it) }
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun merge(root: JSONObject, sections: Set<BackupManager.Section>) {
        val file = File(workDir(), MERGE_FILE)
        file.writeText(root.toString())
        try {
            backups.import(file, sections, null, BackupManager.ImportMode.MERGE).getOrThrow()
        } finally {
            file.delete()
        }
    }

    private fun workDir(): File = File(context.cacheDir, WORK_DIR).apply { mkdirs() }

    companion object {
        private const val TAG = "ProfileSync"
        const val ACTION_READ = "dptv_profile"
        const val ACTION_WRITE = "dptv_profile_put"
        private const val WORK_DIR = "profile-sync"
        private const val MERGE_FILE = "profile.json"
        private const val WORK_NAME = "profile-sync"
        private const val RECORDER_CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val SIGN_IN_RETRY_MS = 60 * 60 * 1000L

        /** Queue a sync (the app opening or going to the background): after any already queued, when online. */
        fun enqueue(context: Context, why: String) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<ProfileSyncWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInputData(workDataOf(KEY_WHY to why))
                        .build(),
                )
            }
        }

        internal const val KEY_WHY = "why"
    }
}

/** Runs [ServerProfileSync.sync] for WorkManager. */
class ProfileSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val sync: ServerProfileSync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        sync.sync(inputData.getString(ServerProfileSync.KEY_WHY) ?: "work")
        return Result.success()
    }
}
