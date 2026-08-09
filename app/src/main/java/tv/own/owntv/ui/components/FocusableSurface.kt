package tv.own.owntv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.GlassInteraction
import tv.own.owntv.ui.theme.LocalGlass
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.glass

@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(Dimens.CardCorner),
    focusedContainerColor: Color = OwnTVTheme.colors.card,
    unfocusedContainerColor: Color = Color.Transparent,
    selectedContainerColor: Color = OwnTVTheme.colors.card,
    focusedScale: Float = 1.012f,
    glowElevation: Int = 6,
    // When non-null AND that surface is glassy (glass mode on + surface in scope), the focused/
    // selected highlight fill renders as a frosted glass slice (Modifier.glass) with a bright white
    // rim instead of the flat tonal fill + accent border. Idle fills are transparent, which glass()
    // skips, so the toggle is safe. Null = the original flat-fill behaviour (unchanged for callers).
    surface: GlassSurface? = null,
    // Per-call frost multiplier for lighter glass on small chrome (see Modifier.glass). Ignored when [surface] is null.
    glassFrostScale: Float = 1f,
    glassCornerRadius: Dp = Dimens.CardCorner,
    // When >0 AND this surface is glassy, an always-on faint white rim lenses the whole edge even when
    // unfocused — the glass edge highlight. Focus still swaps to the brighter rim.
    // Default 0 = no idle rim (unchanged for the 90+ existing callers); opt-in for discrete controls
    // like buttons where a permanent glass edge suits them.
    glassIdleRimAlpha: Float = 0f,
    // When false, this surface never draws the built-in focus/selected outline, so the caller can
    // manage its own border (e.g. the nav ladder, which outlines only the focused-unselected cursor).
    showFocusBorder: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val colors = OwnTVTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val visualState = when {
        pressed -> GlassInteraction.PRESSED
        focused -> GlassInteraction.FOCUSED
        selected -> GlassInteraction.SELECTED
        else -> GlassInteraction.IDLE
    }
    // Glass already communicates focus with its light lens/rim. On several Android TV GPUs, combining
    // a full-width row's animated scale layer with an elevated shadow leaves a dark horizontal trail
    // while the parent scrolls. Row-sized controls use no transform; larger poster cards (1.03+) retain
    // their deliberate depth motion.
    val glassy = surface != null && LocalGlass.current.isGlassy(surface)
    val transformFreeGlassRow = glassy && focusedScale <= 1.012f

    // Fast D-pad navigation can move focus through several cards before the previous frame reaches
    // the GPU. Keep the immediate tint/lens/rim feedback, but promote only the surface that remains
    // focused long enough to the full aligned-backdrop frost path. This avoids a 4K texture sample at
    // every transient focus stop without making navigation feel delayed.
    var focusFrostSettled by remember { mutableStateOf(false) }
    if (focused && glassy && surface == GlassSurface.CARDS) {
        // Conditional effect means only the one focused card owns a timer; dense idle lists launch
        // no settle coroutines. Leaving this composition group also cancels the timer immediately.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(FOCUS_FROST_SETTLE_MS)
            focusFrostSettled = true
        }
    } else {
        SideEffect {
            if (focusFrostSettled) focusFrostSettled = false
        }
    }
    val effectiveFrostScale = if (
        focused && glassy && surface == GlassSurface.CARDS && !focusFrostSettled
    ) 0f else glassFrostScale

    val scale by animateFloatAsState(
        when {
            transformFreeGlassRow -> 1f
            pressed -> 0.992f
            focused -> focusedScale
            else -> 1f
        },
        animationSpec = tv.own.owntv.ui.theme.ownTvTween(if (pressed) 80 else 170),
        label = "focusScale",
    )
    val container by animateColorAsState(
        when {
            focused -> focusedContainerColor
            selected -> selectedContainerColor
            else -> unfocusedContainerColor
        },
        // A row that loses focus is commonly moved by bringIntoView in the same frame. Fading its
        // wide focus fill toward transparent therefore produces a dark plate that visibly follows
        // behind the new focus position. Row-sized glass controls snap the fill/rim state; poster
        // cards and solid surfaces keep the softer transition.
        animationSpec = tv.own.owntv.ui.theme.ownTvTween(if (transformFreeGlassRow) 0 else 160),
        label = "focusContainer",
    )
    val showBorder = showFocusBorder && (focused || selected)
    // Glassy only when a surface is given and it's in the active glass scope. Highlighted glass
    // rows swap the accent focus border for a bright white glass rim (matches the sidebar).
    val borderColor = if (focused) colors.focusBorder else colors.focusBorder.copy(alpha = 0.42f)

    Box(
        modifier = modifier
            .scale(scale)
            .then(
                if (focused && !glassy) Modifier.shadow(
                    elevation = glowElevation.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = colors.focusGlow,
                    spotColor = colors.focusGlow,
                ) else Modifier,
            )
            .clip(shape)
            .then(
                // Frosted glass fill when this surface is glassy (glass() skips transparent idle
                // fills); plain tonal fill otherwise. When surface is null, behaviour is unchanged.
                if (surface != null) Modifier.glass(
                    surface = surface,
                    baseFill = container,
                    shape = shape,
                    cornerRadius = glassCornerRadius,
                    frostScale = effectiveFrostScale,
                    interaction = visualState,
                    idleRimAlpha = glassIdleRimAlpha,
                )
                else Modifier.background(container)
            )
            .then(
                when {
                    showBorder && !glassy -> Modifier.border(Dimens.FocusBorderWidth, borderColor, shape)
                    else -> Modifier
                }
            )
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                } else {
                    Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                },
            ),
        contentAlignment = contentAlignment,
    ) {
        content(focused)
    }
}

private const val FOCUS_FROST_SETTLE_MS = 96L
