package tv.own.owntv.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * The wide "now-playing" bar shown in the top bar (left of the weather chip) while [PlayerMode.AUDIO]
 * is active — Audio Mode plan §6. Video is stopped; only audio plays.
 *
 * **Two-stage focus (owner spec):**
 *  1. **Collapsed** — no D-pad focus: equaliser cover + title + a static play/pause glyph.
 *  2. **Stage 1 — pill focused:** focus lands on the WHOLE bar as one target (highlighted, expanded to
 *     show the full row). The inner buttons are NOT individually navigable yet.
 *  3. **Stage 2 — activated:** press OK on the focused pill → focus moves inside and is **trapped**
 *     there: D-pad left/right only step between the buttons (never escaping the bar), OK runs the
 *     focused button, and **Back** is the only way out — it returns to Stage 1.
 *
 * The trap is enforced manually with [onPreviewKeyEvent] because Compose's default 2D focus search
 * would let left/right leak out to the neighbouring top-bar chips.
 *
 * The equaliser animates only while playing and freezes flat when paused, on every state. prev/next are
 * context-wired by the shell (channel zap / episode queue / disabled for a standalone movie).
 */
@Composable
fun AudioNowPlayingBar(
    player: PlaybackEngine,
    isLive: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    focusable: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    val isPlaying by player.isPlaying.collectAsStateWithLifecycle()
    val meta by player.currentMeta.collectAsStateWithLifecycle()
    val volume by player.volume.collectAsStateWithLifecycle()
    val position by player.position.collectAsStateWithLifecycle()
    val duration by player.duration.collectAsStateWithLifecycle()

    var hasFocus by remember { mutableStateOf(false) } // any part of the bar holds focus (stage 1 or 2)
    var active by remember { mutableStateOf(false) }    // stage 2 — inner buttons live and trapped
    var pendingReturn by remember { mutableStateOf(false) } // Back pressed → hand focus back to the pill

    val pillFocus = remember { FocusRequester() }

    // Button slots, fixed order: 0=prev 1=play 2=next 3=volume 4=fullscreen 5=close. prev/next are only
    // focusable when there's somewhere to go (episode queue / channel zap); everything else is always on.
    val slotCount = 6
    val requesters = remember { List(slotCount) { FocusRequester() } }
    val enabled = booleanArrayOf(canPrev, true, canNext, true, true, true)
    val navSlots = (0 until slotCount).filter { enabled[it] }
    var focusedSlot by remember { mutableIntStateOf(1) } // play by default

    val expanded = (hasFocus || active) && focusable
    val hasSeek = !isLive && duration > 0L

    fun moveFocus(dir: Int) {
        val pos = navSlots.indexOf(focusedSlot)
        val next = pos + dir
        if (pos >= 0 && next in navSlots.indices) {
            val slot = navSlots[next]
            focusedSlot = slot
            runCatching { requesters[slot].requestFocus() }
        }
        // out of range → do nothing: focus stays inside (trapped).
    }

    // Enter/exit stage 2: drop focus onto the play button (trapped), or hand it back to the whole pill.
    LaunchedEffect(active) {
        if (active) {
            focusedSlot = if (1 in navSlots) 1 else navSlots.first()
            runCatching { requesters[focusedSlot].requestFocus() }
        } else if (pendingReturn) {
            pendingReturn = false
            runCatching { pillFocus.requestFocus() }
        }
    }
    // Losing focusability (bar dismissed / mode left) collapses back to a single target.
    LaunchedEffect(focusable) { if (!focusable) { active = false; pendingReturn = false } }
    // Back while activated → return to the whole-pill focus (stage 1). This is the ONLY exit from stage 2.
    if (active) BackHandler { pendingReturn = true; active = false }

    Box(
        modifier = modifier
            // Just track focus. We never reset `active` here: while activated the trap below keeps focus
            // inside, so the only "focus lost" events are the transient drops during the stage-1↔2
            // hand-off — resetting on those is exactly what used to collapse the bar on OK. Real exits go
            // through Back (→ stage 1) or losing [focusable] (→ collapsed), handled explicitly below.
            .onFocusChanged { hasFocus = it.hasFocus }
            // Trap the D-pad while activated: left/right only step between buttons, up/down are swallowed,
            // and OK falls through so the focused button's own click handler runs it.
            .onPreviewKeyEvent { ev ->
                if (!active || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { moveFocus(-1); true }
                    Key.DirectionRight -> { moveFocus(1); true }
                    Key.DirectionUp, Key.DirectionDown -> true // swallow: never escape vertically
                    else -> false
                }
            }
            .focusGroup(),
    ) {
        // --- Content (drawn in all states; buttons focusable only in stage 2) ---
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.surfaceContainer.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(colors.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Equalizer(playing = isPlaying, color = colors.primary, modifier = Modifier.size(16.dp))
            }

            Column(Modifier.widthIn(max = if (expanded) 220.dp else 130.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    meta.title ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (expanded) {
                    when {
                        isLive -> LiveRow(colors.favorite)
                        hasSeek -> SeekRow(position = position, duration = duration, accent = colors.primary, dim = colors.onSurfaceVariant)
                        meta.subtitle != null -> Text(
                            meta.subtitle ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (expanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AudioBtn(0, OwnTVIcon.SKIP_PREVIOUS, active && enabled[0], canPrev, false, requesters, { focusedSlot = it }, onPrev)
                    AudioBtn(1, if (isPlaying) OwnTVIcon.PAUSE else OwnTVIcon.PLAY, active, true, false, requesters, { focusedSlot = it }) { player.togglePlayPause() }
                    AudioBtn(2, OwnTVIcon.SKIP_NEXT, active && enabled[2], canNext, false, requesters, { focusedSlot = it }, onNext)
                    AudioBtn(3, if (volume <= 0) OwnTVIcon.VOLUME_MUTE else OwnTVIcon.VOLUME_HIGH, active, true, false, requesters, { focusedSlot = it }) { player.toggleMute() }
                    AudioBtn(4, OwnTVIcon.FULLSCREEN, active, true, false, requesters, { focusedSlot = it }, onExpand)
                    AudioBtn(5, OwnTVIcon.CLOSE, active, true, true, requesters, { focusedSlot = it }, onClose)
                }
            }
        }

        // --- Stage 1 focus catcher: the whole pill as one target. Focusable only until activated; once
        // activated it steps aside so the trapped buttons own focus. OK enters stage 2. ---
        FocusableSurface(
            onClick = { active = true },
            modifier = Modifier
                .matchParentSize()
                .focusRequester(pillFocus)
                .focusProperties { canFocus = focusable && !active },
            shape = RoundedCornerShape(999.dp),
            focusedScale = 1.03f,
            focusedContainerColor = colors.primary.copy(alpha = 0.20f),
            unfocusedContainerColor = Color.Transparent,
            selectedContainerColor = Color.Transparent,
        ) { _ -> }
    }
}

@Composable
private fun LiveRow(dotColor: Color) {
    val colors = OwnTVTheme.colors
    val transition = rememberInfiniteTransition(label = "liveDot")
    val a by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "liveDotAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor).alpha(a))
        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SeekRow(position: Long, duration: Long, accent: Color, dim: Color) {
    val frac = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 120.dp, height = 3.dp)) {
            drawRoundRect(
                color = dim.copy(alpha = 0.35f), topLeft = Offset(0f, 0f), size = Size(size.width, size.height),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
            )
            drawRoundRect(
                color = accent, topLeft = Offset(0f, 0f), size = Size(size.width * frac, size.height),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
            )
        }
        Text(fmtTime(duration - position, sign = "-"), style = MaterialTheme.typography.labelSmall, color = dim, maxLines = 1)
    }
}

/** Four bars that dance while [playing] and freeze flat when paused (Audio Mode plan §3/§6). */
@Composable
private fun Equalizer(playing: Boolean, color: Color, modifier: Modifier) {
    val bars = 4
    val transition = rememberInfiniteTransition(label = "eq")
    val heights = (0 until bars).map { i ->
        transition.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420 + i * 90, easing = LinearEasing), RepeatMode.Reverse),
            label = "eqBar$i",
        )
    }
    Canvas(modifier) {
        val gap = size.width * 0.12f
        val barW = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val h = if (playing) heights[i].value else 0.18f
            val bh = size.height * h
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), size.height - bh),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

@Composable
private fun AudioBtn(
    slot: Int,
    icon: OwnTVIcon,
    focusable: Boolean,
    enabled: Boolean,
    danger: Boolean,
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onClick: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .size(30.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .focusRequester(requesters[slot])
            .onFocusChanged { if (it.isFocused) onFocused(slot) }
            .focusProperties { canFocus = focusable && enabled },
        shape = CircleShape,
        focusedScale = 1.12f,
        focusedContainerColor = if (danger) colors.favorite else colors.primary,
        unfocusedContainerColor = colors.surfaceContainerHigh.copy(alpha = 0.7f),
        selectedContainerColor = colors.surfaceContainerHigh.copy(alpha = 0.7f),
        contentAlignment = Alignment.Center,
    ) { focused ->
        val tint = if (focused) (if (danger) Color.White else colors.onPrimary) else colors.onSurface
        OwnTVIcon(icon, tint = tint, filled = true, modifier = Modifier.size(16.dp))
    }
}

private fun fmtTime(ms: Long, sign: String = ""): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%s%d:%02d:%02d".format(sign, h, m, s) else "%s%d:%02d".format(sign, m, s)
}
