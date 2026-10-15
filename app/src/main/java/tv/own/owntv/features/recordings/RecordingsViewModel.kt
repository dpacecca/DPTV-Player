package tv.own.owntv.features.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.player.ExternalPlayerLauncher
import tv.own.owntv.core.recording.RecordingManager
import tv.own.owntv.core.recording.RemoteRecorderException
import tv.own.owntv.core.recording.RemoteRecordings
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.player.OwnTVPlayer

/**
 * What has been recorded, what is being recorded, and what never was.
 *
 * Everything here is core's — the same table the phone reads — so this only observes it and offers
 * the four things a recording can be told. Recordings are **not** filtered by the customisation
 * hidden-items list the way downloads are: a recording is a file the user asked for by name, not a
 * catalogue row, and hiding a channel should not make last night's programme disappear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModel(
    private val settings: SettingsRepository,
    private val recordings: RecordingManager,
    val player: OwnTVPlayer,
    private val externalPlayerLauncher: ExternalPlayerLauncher,
    context: android.content.Context,
) : ViewModel() {

    // --- Resume (v2.1): where each recording was left, so opening it again can pick up there. ---------
    // Kept on this device only, by recording id. Saved every few seconds while it plays — switching the
    // television off ends the process without any "exit" — and once more when the player is left.
    private val progress = context.applicationContext.getSharedPreferences(PROGRESS_PREFS, android.content.Context.MODE_PRIVATE)
    private val _progressRevision = MutableStateFlow(0)
    /** Bumped whenever a saved position changes, so the list can redraw its progress bars. */
    val progressRevision: StateFlow<Int> = _progressRevision.asStateFlow()
    private var playingId: Long? = null
    private var playingUrl: String? = null
    private var trackJob: kotlinx.coroutines.Job? = null

    /** Where [recording] was left, when that is worth resuming: past the first half minute and not in its last minute. */
    fun resumePositionMs(recording: RecordingEntity): Long? {
        val pos = progress.getLong("pos_${recording.id}", 0L)
        val dur = progress.getLong("dur_${recording.id}", 0L)
        return pos.takeIf { it >= MIN_RESUME_MS && (dur <= 0L || dur - it > END_MARGIN_MS) }
    }

    /** How far through [recording] the saved position is (0–1), or null with nothing saved. */
    fun progressFraction(recording: RecordingEntity): Float? {
        val pos = resumePositionMs(recording) ?: return null
        val dur = progress.getLong("dur_${recording.id}", 0L).takeIf { it > 0 }
            ?: (recording.programmeStopMs - recording.programmeStartMs).takeIf { it > 0 } ?: return null
        return (pos.toFloat() / dur).coerceIn(0f, 1f)
    }

    /** Save where the recording now playing is, if the player is still on it. */
    fun saveProgressNow() {
        val id = playingId ?: return
        val url = playingUrl ?: return
        if (player.currentMediaUrl != url) return
        val pos = player.position.value
        val dur = player.duration.value
        if (pos <= 0L) return
        // Watched to the end: nothing to come back to.
        val finished = dur > 0 && dur - pos <= END_MARGIN_MS
        progress.edit().apply {
            if (finished) { remove("pos_$id"); remove("dur_$id") } else { putLong("pos_$id", pos); putLong("dur_$id", dur) }
        }.apply()
        _progressRevision.value++
    }

    private fun forgetProgress(id: Long) {
        progress.edit().remove("pos_$id").remove("dur_$id").apply()
        _progressRevision.value++
    }

    private fun track(id: Long, url: String) {
        playingId = id
        playingUrl = url
        trackJob?.cancel()
        trackJob = viewModelScope.launch {
            delay(TRACK_FIRST_MS)
            while (isActive && player.currentMediaUrl == url) {
                saveProgressNow()
                delay(TRACK_EVERY_MS)
            }
        }
    }


    val rows: StateFlow<List<RecordingEntity>> = settings.activeProfileId
        .flatMapLatest { pid -> if (pid < 0) flowOf(emptyList()) else recordings.observe(pid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * False when Android will not let the app set exact alarms, so the screen can say that a
     * recording may begin a few minutes late. Read once: revoking the permission stops the app, so
     * the answer cannot change under a running process.
     */
    val timersAreExact: Boolean = recordings.timersAreExact()

    init {
        // A recording server (Settings › Recording) changes recordings' status on its own clock and
        // can be given recordings from its own web page; keep this screen in step while it is open.
        viewModelScope.launch {
            while (isActive) {
                val pid = settings.activeProfileId.first()
                if (pid >= 0) recordings.syncWithServer(pid)
                delay(SERVER_SYNC_INTERVAL_MS)
            }
        }
    }

    // --- Recording server (DPTV-Recorder) settings ---------------------------------------------

    val recorderServerUrl: StateFlow<String> = settings.recorderServerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val recorderApiKey: StateFlow<String> = settings.recorderApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setRecorderServerUrl(url: String) {
        _serverTest.value = null
        viewModelScope.launch { settings.setRecorderServerUrl(url) }
    }

    fun setRecorderApiKey(key: String) {
        _serverTest.value = null
        viewModelScope.launch { settings.setRecorderApiKey(key) }
    }

    /** The last "Test connection" outcome, for the settings row; null = not tested since the last change. */
    sealed interface ServerTest {
        data object Testing : ServerTest
        data class Ok(val recordings: Int) : ServerTest
        data class Failed(val kind: RemoteRecorderException.Kind, val detail: String?) : ServerTest
    }

    private val _serverTest = MutableStateFlow<ServerTest?>(null)
    val serverTest: StateFlow<ServerTest?> = _serverTest.asStateFlow()

    fun testServer() {
        _serverTest.value = ServerTest.Testing
        viewModelScope.launch {
            _serverTest.value = try {
                ServerTest.Ok(recordings.testServer())
            } catch (e: RemoteRecorderException) {
                ServerTest.Failed(e.kind, e.detail)
            }
        }
    }

    private val _lastPlayedId = MutableStateFlow<Long?>(null)

    /** The recording last asked to play, so returning from the player lands back on its row. */
    val lastPlayedId: StateFlow<Long?> = _lastPlayedId.asStateFlow()

    /**
     * Play a finished recording from the file on disk.
     *
     * `isLive = false` even though what it holds is live television: the flag describes the *source*,
     * and a file on disk seeks, pauses and ends. Treating it as live would take the scrub bar away
     * from the one recording the user most wants to skip through.
     *
     * A recording that is still running is deliberately not playable here. A `.ts` can be played
     * while it is being written, and offering that from this screen would mean two readers on one
     * growing file and a picture that stops at whatever byte it started from.
     */
    fun play(recording: RecordingEntity, resume: Boolean = false) {
        if (recording.status != RecordingStatus.COMPLETED) return
        if (recording.filePath == null) return
        _lastPlayedId.value = recording.id
        viewModelScope.launch {
            // A recording the server made streams from the server: a fresh short-lived link each time.
            val path = if (RemoteRecordings.isRemote(recording)) {
                recordings.serverPlaybackUrl(recording) ?: return@launch
            } else {
                recording.filePath ?: return@launch
            }
            if (settings.externalPlayerFor(MediaType.LIVE).first()) {
                externalPlayerLauncher.launch(path, recording.title)
                return@launch
            }
            val startMs = if (resume) resumePositionMs(recording) ?: 0L else 0L
            if (!resume) forgetProgress(recording.id)
            player.play(path, title = recording.title, isLive = false, startPositionMs = startMs)
            track(recording.id, path)
        }
    }

    /** True when playback should be handed to another app, so the shell does not mount its player. */
    val externalPlayerOn: StateFlow<Boolean> = settings.externalPlayerFor(MediaType.LIVE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Ends a running recording and keeps what it captured — a short recording, not a failure. */
    fun stop(recording: RecordingEntity) = recordings.stop(recording)

    /** Drops a scheduled recording. Nothing on disk is touched, because nothing is there yet. */
    fun cancel(recording: RecordingEntity) = recordings.cancel(recording)

    /** Removes the row **and** the file. The only thing here that deletes anything (D2). */
    fun delete(recording: RecordingEntity) {
        forgetProgress(recording.id)
        recordings.delete(recording)
    }

    /**
     * Try a missed or failed recording again — only possible while its window is still open, which
     * for a live programme is rarely. Re-scheduling is the honest action: the engine decides again
     * whether there is a connection for it.
     */
    fun retry(recording: RecordingEntity) {
        viewModelScope.launch { recordings.schedule(recording) }
    }

    private companion object {
        const val SERVER_SYNC_INTERVAL_MS = 30_000L
        private const val PROGRESS_PREFS = "dvr_progress"
        private const val MIN_RESUME_MS = 30_000L
        private const val END_MARGIN_MS = 60_000L
        private const val TRACK_FIRST_MS = 3_000L
        private const val TRACK_EVERY_MS = 5_000L
    }
}
