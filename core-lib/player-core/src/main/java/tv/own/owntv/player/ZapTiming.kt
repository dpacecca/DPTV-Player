package tv.own.owntv.player

/**
 * Channel-change timing for the diagnostics log: how long from a CH+/− key press until the zapped
 * channel's picture, whichever engine plays it.
 *
 * The app marks the press ([markPress]); the engine that renders the next first frame reads it once
 * ([takeSincePress]) and appends it to its own first-frame line. One press is read at most once, and a
 * press older than [maxAgeMs] is dropped rather than charged to an unrelated tune (a press that moved
 * nothing, a channel that never opened). Measurement only: nothing here changes playback.
 *
 * Thread-safe: presses arrive on the main thread, ExoPlayer's first frame on its playback thread and
 * mpv's first position on mpv's event thread.
 */
class ZapTiming(private val maxAgeMs: Long = MAX_AGE_MS) {

    private var pressAtMs: Long? = null

    /** A zap key moved the channel at [nowMs] (elapsedRealtime). A newer press replaces an unread one. */
    @Synchronized
    fun markPress(nowMs: Long) {
        pressAtMs = nowMs
    }

    /** Milliseconds since the unread press, consuming it; null when there is none or it is too old. */
    @Synchronized
    fun takeSincePress(nowMs: Long): Long? {
        val at = pressAtMs ?: return null
        pressAtMs = null
        return (nowMs - at).takeIf { it in 0..maxAgeMs }
    }

    companion object {
        /** Longer than any healthy open (ExoPlayer gives up at 12 s plus a pre-roll; mpv's live open is
         *  bounded at 10 s), so a slow start is still measured but a stale press is not. */
        const val MAX_AGE_MS = 30_000L

        /** The one instance both engines and the app share. */
        val shared = ZapTiming()

        /** The suffix a first-frame line carries when a zap press is being measured, else "". */
        fun pressSuffix(sincePressMs: Long?): String =
            sincePressMs?.let { " (zap key press to first frame ${it}ms)" }.orEmpty()
    }
}
