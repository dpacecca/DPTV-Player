package tv.own.owntv.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.own.owntv.R
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.hudAccent
import tv.own.owntv.ui.theme.hudInk

/** How long the information banner stays up when the player opens, on OK and on a channel change (v2). */
internal const val INFO_BANNER_MS = 3_000L

/**
 * The live information banner (v2): along the bottom of the picture, over a scrim — the channel's logo,
 * its number and name with the stream's facts ("1080p", "25 FPS", "STEREO") and Catch-up, the programme
 * on now (two lines), its time with a progress bar and what is left, a two-line synopsis and what is
 * next. A thin bar along the bottom drains over the time the banner stays up. Not focusable.
 */
@Composable
internal fun LiveInfoBanner(
    logoUrl: String?,
    number: String?,
    channelName: String,
    chips: List<String>,
    catchup: Boolean,
    now: XtEpgEntry?,
    next: XtEpgEntry?,
    /** Changes each time the banner is shown again, so the drain bar starts over. */
    showKey: Int,
    modifier: Modifier = Modifier,
) {
    val accent = hudAccent
    val formatTime = rememberSystemTimeFormatter()
    val drain = remember { Animatable(1f) }
    LaunchedEffect(showKey) {
        drain.snapTo(1f)
        drain.animateTo(0f, tween(INFO_BANNER_MS.toInt(), easing = LinearEasing))
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < 700.dp
        val logoSize = if (compact) 64.dp else 112.dp
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(if (compact) 0.6f else 0.5f)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.9f))),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = if (compact) 16.dp else 40.dp, end = if (compact) 16.dp else 40.dp, bottom = if (compact) 16.dp else 32.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 24.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.size(logoSize).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize().padding(8.dp))
                } else {
                    OwnTVIcon(OwnTVIcon.LIVE_TV, tint = hudInk.copy(alpha = 0.6f), modifier = Modifier.size(logoSize / 2))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!number.isNullOrBlank()) {
                        Text(number, color = accent, fontSize = if (compact) 15.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        channelName, color = hudInk, fontSize = if (compact) 14.sp else 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    chips.forEach { chip ->
                        Text(
                            chip, color = hudInk.copy(alpha = 0.85f), fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.border(1.dp, hudInk.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    if (catchup) {
                        OwnTVIcon(OwnTVIcon.CATCHUP, tint = accent, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.sport_guide_catchup), color = accent, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (now != null) {
                    Text(
                        now.title, color = hudInk, fontSize = if (compact) 20.sp else 32.sp, fontWeight = FontWeight.Bold, lineHeight = if (compact) 24.sp else 36.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val nowMs = System.currentTimeMillis()
                    val span = (now.stopMs - now.startMs).coerceAtLeast(1)
                    val frac = ((nowMs - now.startMs).toFloat() / span).coerceIn(0f, 1f)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.content_live_time_range_plain, formatTime(now.startMs), formatTime(now.stopMs)),
                            color = hudInk.copy(alpha = 0.8f), fontSize = if (compact) 13.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Box(Modifier.width(if (compact) 90.dp else 180.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(hudInk.copy(alpha = 0.25f))) {
                            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(hudInk))
                        }
                        Text(
                            stringResource(R.string.sport_guide_min_left, (((now.stopMs - nowMs) + 59_999) / 60_000).toInt().coerceAtLeast(0)),
                            color = hudInk.copy(alpha = 0.8f), fontSize = if (compact) 13.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    now.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it, color = hudInk.copy(alpha = 0.78f), fontSize = if (compact) 12.sp else 15.sp, lineHeight = if (compact) 16.sp else 21.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 900.dp),
                        )
                    }
                }
                if (next != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sport_guide_next), color = hudInk, fontSize = if (compact) 12.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.content_live_time_range_plain, formatTime(next.startMs), formatTime(next.stopMs)),
                            color = hudInk.copy(alpha = 0.75f), fontSize = if (compact) 12.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(next.title, color = hudInk.copy(alpha = 0.75f), fontSize = if (compact) 12.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // The time left before the banner goes.
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(hudInk.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(drain.value).fillMaxHeight().background(accent))
        }
    }
}
