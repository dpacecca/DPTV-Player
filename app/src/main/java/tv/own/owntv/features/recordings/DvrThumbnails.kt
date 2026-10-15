package tv.own.owntv.features.recordings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.recording.RemoteRecordings
import java.io.File

/**
 * A still from each finished recording for the DVR list, taken once and kept in the cache.
 *
 * The frame comes from a little way in (a tenth of the length, at most five minutes) rather than the
 * first second, which is usually the end of whatever was on before. Only recordings on this device
 * get one: a server recording would mean streaming it just to draw a thumbnail, so those keep the
 * channel logo. Any file the system cannot read the same way.
 */
internal object DvrThumbnails {
    private const val DIR = "dvr_thumbs"
    private const val WIDTH = 480
    // One at a time: decoding a frame is heavy, and a list scrolled quickly would start many at once.
    private val lock = Mutex()

    private fun fileFor(context: Context, id: Long) = File(File(context.cacheDir, DIR), "$id.jpg")

    /** The still for [recording], made now if it is not cached yet; null when none can be made. */
    suspend fun load(context: Context, recording: RecordingEntity): Bitmap? = withContext(Dispatchers.IO) {
        val cached = fileFor(context, recording.id)
        if (cached.exists()) return@withContext runCatching { BitmapFactory.decodeFile(cached.path) }.getOrNull()
        val path = recording.filePath ?: return@withContext null
        if (RemoteRecordings.isRemote(recording)) return@withContext null
        lock.withLock {
            if (cached.exists()) return@withLock runCatching { BitmapFactory.decodeFile(cached.path) }.getOrNull()
            val frame = grab(context, path) ?: return@withLock null
            runCatching {
                cached.parentFile?.mkdirs()
                cached.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            }
            frame
        }
    }

    /** Forget [id]'s still, with the recording it was taken from. */
    fun delete(context: Context, id: Long) {
        runCatching { fileFor(context, id).delete() }
    }

    private fun grab(context: Context, path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (path.startsWith("content:") || path.startsWith("file:")) {
                retriever.setDataSource(context, Uri.parse(path))
            } else {
                if (!File(path).exists()) return null
                retriever.setDataSource(path)
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atMs = if (durationMs > 0) (durationMs / 10).coerceAtMost(5 * 60_000L) else 60_000L
            val frame = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            if (frame.width <= WIDTH) frame
            else Bitmap.createScaledBitmap(frame, WIDTH, (frame.height * WIDTH.toFloat() / frame.width).toInt().coerceAtLeast(1), true)
                .also { if (it !== frame) frame.recycle() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
