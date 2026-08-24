package tv.own.owntv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import androidx.compose.ui.focus.onFocusChanged
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.format.localizedDecimal

/**
 * Shared HUD primitives: the two time formatters, the button vocabulary and the two
 * scrub bars. Split out of [PlayerHud] — behaviour unchanged; the declarations the other HUD files reach
 * for are `internal` rather than file-private for that reason alone.
 */

@Composable
internal fun formatTime(ms: Long): String = tv.own.owntv.ui.components.formatTimestamp(ms)

/** mm:ss for a seconds offset (e.g. 150 → "2:30"). */
@Composable
internal fun mmss(sec: Int): String = tv.own.owntv.ui.components.formatTimestamp(sec * 1000L)

// ---------------- Buttons ----------------

@Composable
internal fun CircleButton(icon: OwnTVIcon, size: Int, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Focus fills the button with the accent and rings it in a white hairline. The glyph takes
    // onAccentOnVideo rather than a fixed dark, so a deep custom accent still gets a readable icon —
    // that role is already derived from the seed's luminance, which is the check this needs.
    val accent = OwnTVTheme.colors.accentOnVideo
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.size(size.dp),
        shape = CircleShape,
        focusedScale = 1.1f,
        focusedContainerColor = accent,
        unfocusedContainerColor = if (primary) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.10f),
        selectedContainerColor = Color.White.copy(alpha = 0.10f),
        contentAlignment = Alignment.Center,
    ) { focused ->
        if (focused) {
            Box(Modifier.matchParentSize().border(1.dp, Color.White.copy(alpha = 0.9f), CircleShape))
        }
        OwnTVIcon(
            icon,
            tint = when {
                focused -> OwnTVTheme.colors.onAccentOnVideo
                primary -> Color(0xFF0E1513)
                else -> Color.White
            },
            filled = true,
            modifier = Modifier.size((size * 0.42f).dp),
        )
    }
}

/** The transport buttons live in one frosted capsule instead of floating loose over the video. */
@Composable
internal fun TransportCapsule(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(44.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(44.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun SpeedButton(label: String, active: Boolean, name: String? = null, onName: (String?) -> Unit = {}, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp).onFocusChanged { if (it.isFocused) onName(name) },
        shape = RoundedCornerShape(12.dp),
        focusedContainerColor = Color.White.copy(alpha = 0.16f),
        unfocusedContainerColor = Color.Transparent,
        selectedContainerColor = Color.Transparent,
        contentAlignment = Alignment.Center,
    ) { focused ->
        // The rate itself is the icon — the extra ">>" glyph read as a seek control next to the real
        // rewind/forward buttons, and "1.0x" already says everything the button does.
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) OwnTVTheme.colors.accentOnVideo else if (focused) Color.White else Color.White.copy(alpha = 0.78f), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun formatSpeed(speed: Double): String = if (speed == 1.0) {
    stringResource(R.string.player_speed_normal_short)
} else {
    stringResource(R.string.player_speed, localizedDecimal(speed))
}

/** The MPV/EXO engine toggle: a one-line pill showing the active engine, flipped on click. Accent-coloured
 *  while on the non-default engine (ExoPlayer for VOD; mpv "compatibility" pin for Live). Mirrors [SpeedButton]. */
@Composable
internal fun EngineToggle(label: String, active: Boolean, name: String? = null, onName: (String?) -> Unit = {}, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp).onFocusChanged { if (it.isFocused) onName(name) },
        shape = RoundedCornerShape(12.dp),
        focusedContainerColor = Color.White.copy(alpha = 0.16f),
        unfocusedContainerColor = Color.Transparent,
        selectedContainerColor = Color.Transparent,
        contentAlignment = Alignment.Center,
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OwnTVIcon(OwnTVIcon.SWAP, tint = if (active) OwnTVTheme.colors.accentOnVideo else if (focused) Color.White else Color.White.copy(alpha = 0.78f), filled = true, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) OwnTVTheme.colors.accentOnVideo else if (focused) Color.White else Color.White.copy(alpha = 0.78f), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun CtrlButton(
    icon: OwnTVIcon,
    badge: Int? = null,
    active: Boolean = false,
    // The favourite heart stays coral here instead of taking the accent, so one colour means
    // "favourite" everywhere in the app — browse rows, posters and the player alike.
    activeTint: Color = OwnTVTheme.colors.accentOnVideo,
    name: String? = null,
    onName: (String?) -> Unit = {},
    onClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.size(44.dp).onFocusChanged { if (it.isFocused) onName(name) },
        shape = RoundedCornerShape(12.dp),
        focusedContainerColor = Color.White.copy(alpha = 0.16f),
        unfocusedContainerColor = Color.Transparent,
        selectedContainerColor = Color.Transparent,
        contentAlignment = Alignment.Center,
    ) { focused ->
        Box(contentAlignment = Alignment.Center) {
            OwnTVIcon(icon, tint = if (active) activeTint else if (focused) Color.White else Color.White.copy(alpha = 0.78f), filled = true, modifier = Modifier.size(22.dp))
            if (badge != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(15.dp).clip(CircleShape).background(OwnTVTheme.colors.accentOnVideo),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.common_number_grouped, badge), style = MaterialTheme.typography.labelSmall, color = OwnTVTheme.colors.onAccentOnVideo, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------- Seekbar ----------------

@Composable
internal fun SeekBar(positionMs: Long, durationMs: Long, stepMs: Long, onSeek: (Long) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(
        modifier = Modifier.fillMaxWidth().height(24.dp)
            .onKeyEvent { e ->
                // Physical by design: left rewinds and right advances media time in every locale.
                if (e.type == KeyEventType.KeyDown) when (e.key) {
                    Key.DirectionLeft -> { onSeek(-stepMs); true }
                    Key.DirectionRight -> { onSeek(stepMs); true }
                    else -> false
                } else false
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(if (focused) 6.dp else 4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (focused) 0.4f else 0.22f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50)).background(OwnTVTheme.colors.accentOnVideo))
        }
        if (focused) {
            Box(Modifier.fillMaxWidth(frac), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(OwnTVTheme.colors.accentOnVideo))
            }
            // Time-remaining bubble above the thumb (elapsed is shown at the bar's left, total at the right,
            // so the bubble shows what's LEFT: "-12:34"). Uses a negative offset (not bottom padding) so it
            // floats clear above the 24dp-tall bar — padding can't lift it out of the height-constrained parent.
            Box(Modifier.fillMaxWidth(frac), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier.offset(y = (-32).dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.9f)).padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        stringResource(R.string.player_time_remaining, formatTime((durationMs - positionMs).coerceAtLeast(0))),
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                        color = Color.White,
                    )
                }
            }
        }
    }
    }
}

private const val LIVE_SCRUB_STEP_SEC = 60     // per Left/Right press (hold to scrub fast); buttons stay 30 s

/** Scrubbable live timeline for a catch-up channel: spans the last [LIVE_WINDOW_SEC] up to the live edge.
 *  Left = back in time, Right = toward live; the thumb is the watched point and the gap to the red LIVE dot
 *  on the right is how far behind live you are. Holding a key scrubs freely; the archive loads when you
 *  settle (the VM debounces). Going past the window keeps working via the ⏪ button — the bar just pins left. */
@Composable
internal fun LiveTimelineBar(offsetSec: Int, onScrub: (Int) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val frac = offsetFrac(offsetSec) // 1 = live edge, 0 = far edge
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(
        modifier = Modifier.fillMaxWidth().height(24.dp)
            .onKeyEvent { e ->
                // Physical by design: left moves away from live; right moves toward the live edge.
                if (e.type == KeyEventType.KeyDown) when (e.key) {
                    Key.DirectionLeft -> { onScrub(LIVE_SCRUB_STEP_SEC); true }    // back in time
                    Key.DirectionRight -> { onScrub(-LIVE_SCRUB_STEP_SEC); true }  // toward live
                    else -> false
                } else false
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(if (focused) 6.dp else 4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (focused) 0.4f else 0.22f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50)).background(OwnTVTheme.colors.accentOnVideo))
        }
        // Live-edge marker (red dot) at the far right.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF4D4D)))
        }
        if (focused) {
            Box(Modifier.fillMaxWidth(frac), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(OwnTVTheme.colors.accentOnVideo))
            }
            Box(Modifier.fillMaxWidth(frac), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.padding(bottom = 30.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.9f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(
                        if (offsetSec <= 1) stringResource(R.string.player_live) else stringResource(R.string.player_live_offset, mmss(offsetSec)),
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                        color = Color.White,
                    )
                }
            }
        }
    }
    }
}
