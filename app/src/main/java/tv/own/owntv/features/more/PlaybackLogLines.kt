package tv.own.owntv.features.more

import tv.own.owntv.core.network.HttpClient

/**
 * The Playback log page's lines (v2.5.2): the newest first, made safe to show, and the Channel changes
 * filter. Kept apart from Compose so it can be tested.
 */
internal object PlaybackLogLines {
    /** How many of the newest lines the page shows. */
    const val MAX_LINES = 300

    /**
     * The phrases of a channel change's timing, as the callers of `LiveDiagnosticsLog.event` write them:
     * the key press and its wait (LiveViewModel), the tune's route and timeshift's hand-overs
     * (LiveTuneController), and the first frame (ExoPlayer) or first position (mpv) with the time since the press.
     */
    private val CHANNEL_CHANGE_PHRASES = listOf(
        "zap:",
        "zap key press",
        "first frame after",
        "first position after",
        "engine: tune '",
        "engine: timeshift:",
    )

    /** `MM-dd HH:mm:ss.SSS message`, as `LiveDiagnosticsLog.event` stamps each line. */
    private val STAMPED = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (.*)$""")

    /** Any `scheme://…` in a line: shown as its scheme only. */
    private val URL = Regex("""(?i)\b([a-z][a-z0-9+.-]*)://[^\s'")\]]+""")

    fun isChannelChange(line: String): Boolean = CHANNEL_CHANGE_PHRASES.any { line.contains(it, ignoreCase = true) }

    /** The change check's and the sync times' lines (v2.5.3), as `SyncCheckLog` writes them. */
    const val SYNC_CHECK_PREFIX = "sync-check:"

    fun isSyncCheck(line: String): Boolean = line.contains(SYNC_CHECK_PREFIX, ignoreCase = true)

    /** A second guard: callers redact before logging; this drops whole URLs, then masks any credential
     *  left outside one ([HttpClient.redactUrl]). */
    fun redact(line: String): String = HttpClient.redactUrl(URL.replace(line) { "${it.groupValues[1]}://•••" })

    /** The newest [max] lines (after the filter), newest first, redacted. */
    fun prepare(raw: List<String>, channelChangesOnly: Boolean, max: Int = MAX_LINES, syncChecksOnly: Boolean = false): List<String> =
        raw.filter {
            it.isNotBlank() && when {
                // Both filters on: either kind of line.
                channelChangesOnly && syncChecksOnly -> isChannelChange(it) || isSyncCheck(it)
                channelChangesOnly -> isChannelChange(it)
                syncChecksOnly -> isSyncCheck(it)
                else -> true
            }
        }
            .takeLast(max)
            .asReversed()
            .map(::redact)

    /** A line's time stamp and its message; null stamp when the line has none. */
    fun split(line: String): Pair<String?, String> =
        STAMPED.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2] } ?: (null to line)
}
