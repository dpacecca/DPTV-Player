package tv.own.owntv.core.timeshift

import java.io.InputStream

/**
 * A channel's saved copy as the player reads it: kept on this device ([TimeshiftSession]) or by the
 * DPTV-Recorder server ([tv.own.owntv.core.recording.ServerTimeshift], DPTV-Player v2.1.5). Either way
 * the player opens it through [TimeshiftServer] on loopback, so rewinding, pausing and the live edge
 * work the same.
 *
 * A piece's index is opaque to callers: they get one from [pieceAt] and hand it back to [open].
 */
interface TimeshiftBuffer {
    /** Unguessable, and part of every URL, so a stale URL can never reach a newer buffer. */
    val token: String
    /** Which channel this is the buffer of (the controller's key). */
    val channelKey: String
    val container: TimeshiftSession.Container
    val isClosed: Boolean
    /** Where the newest reader's stream starts on the wall clock (see [TimeshiftSession.readingFromWallMs]). */
    val readingFromWallMs: Long?
    /** A stream from piece [fromIndex] onwards, or from the live edge (null), blocking at the edge. */
    fun open(fromIndex: Long?): InputStream
    /** The piece holding wall-clock instant [wallMs]; the oldest when that has already been dropped. */
    fun pieceAt(wallMs: Long): Long?
    fun oldestWallMs(): Long?
    /** The newest instant a player can show, never ahead of this device's clock. */
    fun playableEdgeWallMs(): Long?
    /** Wall-clock spans that hold nothing (the connection was down), oldest first. */
    fun gaps(): List<LongRange>
}
