package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private class MpvSurfaceView(context: Context, private val player: OwnTVPlayer) :
    SurfaceView(context), SurfaceHolder.Callback {

    private var pendingFps = 0f

    init {
        holder.addCallback(this)
    }

    /** Ask the display to switch to a refresh rate matching the video (TVs that support it drop the
     *  3:2-pulldown judder of 24fps content on a fixed 60Hz panel). Re-applied on each fps change and
     *  on surface (re)create. No-op below Android 11, or where the panel can't switch (harmless). */
    fun applyVideoFrameRate(fps: Float) {
        pendingFps = fps
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val surface = holder.surface ?: return
        if (!surface.isValid || fps <= 0f) return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
            } else {
                @Suppress("DEPRECATION")
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        player.attachSurface(holder.surface)
        if (pendingFps > 0f) applyVideoFrameRate(pendingFps) // re-assert after a surface recreate
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        player.setSurfaceSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player.detachSurface()
    }
}

/**
 * Hosts the mpv video output (a [SurfaceView]) in Compose.
 *
 * In direct render mode the decoder fills the surface edge-to-edge (no GL scaling), so zoom/aspect is
 * done by **sizing the view itself**: the surface is scaled/cropped/letterboxed by laying it out at the
 * target geometry inside a clipped black box (the same approach ExoPlayer/YouTube use). In GL mode mpv
 * scales internally (via [OwnTVPlayer.setZoomMode]'s properties) and the view just fills the slot.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MpvVideoSurface(player: OwnTVPlayer, modifier: Modifier = Modifier) {
    val direct by player.directRender.collectAsStateWithLifecycle()
    val aspect by player.videoAspect.collectAsStateWithLifecycle()
    val videoSize by player.videoSize.collectAsStateWithLifecycle()
    val zoom by player.zoomMode.collectAsStateWithLifecycle()
    val fps by player.videoFps.collectAsStateWithLifecycle()
    val density = LocalDensity.current

    BoxWithConstraints(modifier.background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        val a = aspect
        val viewModifier = if (!direct || a == null || a <= 0f) {
            // GL mode letterboxes internally; or no video dimensions yet — just fill the slot.
            Modifier.fillMaxSize()
        } else {
            val cw = maxWidth
            val ch = maxHeight
            val containerAspect = cw.value / ch.value
            when (zoom) {
                ZoomMode.STRETCH -> Modifier.fillMaxSize()
                ZoomMode.ORIGINAL -> {
                    val vs = videoSize
                    if (vs != null && vs.first > 0 && vs.second > 0) {
                        with(density) { Modifier.size(vs.first.toDp(), vs.second.toDp()) }
                    } else {
                        Modifier.aspectRatio(a)
                    }
                }
                else -> {
                    // Target box aspect for the view; the surface stretches to fill it.
                    val targetAspect = when (zoom) {
                        ZoomMode.FORCE_16_9 -> 16f / 9f
                        ZoomMode.FORCE_4_3 -> 4f / 3f
                        else -> a // FIT, FILL keep the video aspect
                    }
                    val cover = zoom == ZoomMode.FILL // cover the container (crop) vs contain (fit)
                    // contain: largest box of targetAspect fitting inside; cover: smallest covering it.
                    val widthDriven = if (cover) targetAspect < containerAspect else targetAspect >= containerAspect
                    if (widthDriven) {
                        Modifier.width(cw).height((cw.value / targetAspect).dp)
                    } else {
                        Modifier.height(ch).width((ch.value * targetAspect).dp)
                    }
                }
            }
        }
        // key(surfaceResetToken): when the player bumps the token, this whole AndroidView is disposed and
        // recreated — destroying the old Surface and making a FRESH one. The Realtek decoder needs a clean
        // Surface for a back-to-back 4K-class session (reusing the dirty one throws 0x80001000).
        val surfaceResetToken by player.surfaceResetToken.collectAsStateWithLifecycle()
        androidx.compose.runtime.key(surfaceResetToken) {
            AndroidView(
                modifier = viewModifier,
                factory = { ctx -> MpvSurfaceView(ctx, player) },
                // Match the display refresh rate to the video FPS once known (kills 24fps-on-60Hz judder).
                update = { it.applyVideoFrameRate(fps ?: 0f) },
            )
        }
        // Image-subtitle (PGS/VOBSUB/DVB) overlay for the ExoPlayer handoff. Mounted ONLY while ExoPlayer
        // owns playback — putting ANY view over the SurfaceView (even an empty one) knocks it off the
        // hardware-overlay / direct scan-out path, which stutters 4K to a ~2 fps slideshow under GPU
        // composition. During normal mpv playback this isn't composed, so the surface scans out directly.
        val exoActive by player.exoActiveState.collectAsStateWithLifecycle()
        val cues by player.exoCues.collectAsStateWithLifecycle()
        if (exoActive) {
            AndroidView(
                modifier = viewModifier,
                factory = { ctx -> androidx.media3.ui.SubtitleView(ctx) },
                update = { it.setCues(cues) },
            )
        }
        // Freeze-frame: the last mpv frame, shown over the surface during the mpv→ExoPlayer swap so the
        // decoder switch doesn't flash black. Same geometry as the surface; cleared on Exo's first frame.
        val freeze by player.freezeFrame.collectAsStateWithLifecycle()
        freeze?.let { bmp ->
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = viewModifier,
                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
            )
        }
    }
}
