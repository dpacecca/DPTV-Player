package tv.own.owntv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass — translucent frosted surface treatment.
 *
 * Each surface that can go glassy is tagged with a [GlassSurface]. When the feature is enabled
 * a panel whose surface is in [GlassConfig.scope] renders with a translucent fill and directional
 * edge light. A wallpaper adds real cached backdrop frost; without one the same roles use a tonal
 * ceramic-glass fallback so contrast remains predictable.
 *
 * The fullscreen player is intentionally NOT a [GlassSurface] — it covers the shell when visible,
 * so leaving it opaque is correct.
 */
enum class GlassSurface {
    /** The large rounded content panels (Home/Movies/Series/Live/etc. root containers). */
    PANELS,

    /** The left navigation rail (sidebar). */
    SIDEBAR,

    /** The right detail column (preview pane / channel detail). */
    PREVIEW,

    /** Centered popup dialogs and sheets (accent picker, settings sheets, chooser dialogs). */
    DIALOGS,

    /** The top bar: active section + search pill + clock + playlist chip. */
    TOPBAR,

    /** Poster cards and list items. */
    CARDS,

    /** The docked (non-fullscreen) mini-player bar. */
    MINI_PLAYER,
}

/** User-facing material tuning. CUSTOM resolves to the separately persisted alpha/frost values. */
enum class GlassPreset(val alpha: Float?, val blurStrength: Float?) {
    CLEAR(alpha = 0.38f, blurStrength = 0.62f),
    BALANCED(alpha = 0.56f, blurStrength = 0.78f),
    TINTED(alpha = 0.74f, blurStrength = 0.88f),
    CUSTOM(alpha = null, blurStrength = null);

    fun resolveAlpha(custom: Float): Float = (alpha ?: custom).coerceIn(0f, 1f)
    fun resolveBlur(custom: Float): Float = (blurStrength ?: custom).coerceIn(0f, 1f)

    companion object {
        /** Migration-safe: recognize old defaults/preset values; preserve every other old value as Custom. */
        fun fromStored(name: String?, customAlpha: Float, customBlur: Float): GlassPreset {
            name?.let { stored -> entries.firstOrNull { it.name == stored }?.let { return it } }
            return entries.firstOrNull {
                it != CUSTOM && kotlin.math.abs((it.alpha ?: 0f) - customAlpha) < 0.001f &&
                    kotlin.math.abs((it.blurStrength ?: 0f) - customBlur) < 0.001f
            } ?: CUSTOM
        }
    }
}

/** Surface interaction passed to the material renderer; only FOCUSED receives the rich light lens. */
enum class GlassInteraction { IDLE, SELECTED, FOCUSED, PRESSED }

/** Every surface that can be glassed. Used to implement the "All" master tick. */
val ALL_GLASS_SURFACES: Set<GlassSurface> = GlassSurface.entries.toSet()

/**
 * Resolved glass state.
 *
 * @param scope which surfaces are glassy. Empty = feature off (panels stay solid).
 * @param alpha panel fill alpha when glassed, in 0..1. Default 0.56 (Balanced).
 *   Pure 0 means fully transparent (image shows through unobstructed), 1 = opaque (no glass effect).
 * @param blurStrength how much real backdrop blur ("frost") to apply, in 0..1. 0 = Tier-1
 *   translucency only (sharp background reads through); 1 = fully frosted. Default 0.78. Only has an
 *   effect on API 31+ ([supportsBackdropBlur]) and when a background image is present. The strength
 *   is the draw alpha of the single shared blurred-backdrop slice — O(1) to change, no re-blur.
 */
@Stable
data class GlassConfig(
    val scope: Set<GlassSurface> = emptySet(),
    val alpha: Float = DEFAULT_GLASS_ALPHA,
    val blurStrength: Float = DEFAULT_BLUR_STRENGTH,
    val preset: GlassPreset = GlassPreset.BALANCED,
    /** Runtime-only environment flag supplied by MainActivity; it is not persisted in the bitmask. */
    val hasBackdrop: Boolean = false,
) {
    /** Glass is "on" only when at least one surface is scoped. */
    val enabled: Boolean get() = scope.isNotEmpty()

    /** True when [surface] should render as glass. */
    fun isGlassy(surface: GlassSurface): Boolean = enabled && surface in scope

    /** Bitmask encode/decode — used by SettingsRepository persistence. */
    fun toBitmask(): Int {
        var bits = 0
        for (s in scope) bits = bits or (1 shl s.ordinal)
        return bits
    }

    companion object {
        const val DEFAULT_GLASS_ALPHA: Float = 0.56f
        const val DEFAULT_BLUR_STRENGTH: Float = 0.78f

        fun fromBitmask(
            bits: Int,
            alpha: Float = DEFAULT_GLASS_ALPHA,
            blurStrength: Float = DEFAULT_BLUR_STRENGTH,
            preset: GlassPreset = GlassPreset.CUSTOM,
        ): GlassConfig {
            val scope = GlassSurface.entries.filter { (bits shr it.ordinal) and 1 == 1 }.toSet()
            return GlassConfig(
                scope = scope,
                alpha = preset.resolveAlpha(alpha),
                blurStrength = preset.resolveBlur(blurStrength),
                preset = preset,
            )
        }
    }
}

/** Ambient glass state. Default is "off" so the app looks unchanged until a background image is set. */
val LocalGlass = compositionLocalOf { GlassConfig() }

/**
 * Which [GlassSurface] an ambient action control (e.g. `OwnTVButton`) should frost with, or null to
 * stay flat. Used by the shared action pill so a button frosts with whatever surface its host renders
 * on — `DIALOGS` inside a popup, `CARDS` on a settings panel — without each call site passing a flag.
 *
 * Defaults to [GlassSurface.DIALOGS]: most action pills live in dialogs/popups, and a `Popup` does
 * NOT inherit the screen's CompositionLocals, so each popup would otherwise need its own provider.
 * The DIALOGS default means dialog buttons are correct with zero per-dialog wiring; screens whose
 * buttons sit on a panel (e.g. the settings list, Customize) override it to `CARDS`, and surfaces that
 * should never frost (the fullscreen player, over opaque video) provide null.
 */
val LocalActionSurface = compositionLocalOf<GlassSurface?> { GlassSurface.DIALOGS }

/**
 * Holder for the single shared blurred copy of the background image (Phase 4 backdrop blur).
 * Provided by `MainActivity` when a background image is set AND [supportsBackdropBlur]; null
 * otherwise (panels then fall back to Tier-1 translucency over the sharp image).
 *
 * [rootSizePx] is the full app viewport size in pixels (the area the bitmap stands in for, since the
 * blurred bitmap is a downscaled stand-in). Each glass surface maps its on-screen rect from root px
 * → bitmap coords using [rootSizePx], then draws the matching slice translated to its own position so
 * the frost lines up with the photo behind it.
 */
@Stable
data class BlurredBackdrop(
    val bitmap: ImageBitmap,
    val rootSizePx: Size,
)

/** Ambient blurred backdrop, or null when blur isn't active. See [BlurredBackdrop]. */
val LocalBlurredBackdrop = compositionLocalOf<BlurredBackdrop?> { null }

/**
 * Render this surface as glass: (Tier 2) a frosted slice of the blurred backdrop aligned to this
 * panel's on-screen position, then a translucent fill + a specular top-edge highlight, clipped to
 * [shape]. Falls back to Tier 1 (translucency + sheen, no frost) when there's no blurred backdrop
 * (no image / pre-API 31 / blurStrength 0). When the surface is not in scope the panel keeps its
 * normal solid [baseFill].
 *
 * Pass the [surface] so we honour the per-surface scope.
 *
 * @param baseFill the opaque colour this panel would normally use.
 * @param shape clip shape for the fill + highlight. Must match the container's own clip.
 */
@Composable
fun Modifier.glass(
    surface: GlassSurface,
    baseFill: Color,
    shape: Shape = RectangleShape,
    cornerRadius: Dp = 22.dp,
    // Per-call frost multiplier (0..1) applied on top of the global blurStrength — lets small chrome
    // (e.g. top-bar chips) read as lighter glass than the big panels without changing the global setting.
    frostScale: Float = 1f,
    interaction: GlassInteraction = GlassInteraction.IDLE,
    idleRimAlpha: Float? = null,
): Modifier {
    val config = LocalGlass.current
    // Fully transparent fill = nothing to render (e.g. an idle nav/list item whose highlight fill
    // is Color.Transparent). Skip both the frost and the background so glass() can be called
    // unconditionally on highlights that toggle between transparent (idle) and filled (focused).
    if (baseFill.alpha == 0f) return this
    // No glass for this surface → keep the solid fill, no highlight, no frost.
    if (!config.isGlassy(surface)) return this.background(baseFill, shape)

    val roleAdjustment = when (surface) {
        GlassSurface.DIALOGS -> 0.12f
        GlassSurface.SIDEBAR -> 0.06f
        GlassSurface.TOPBAR, GlassSurface.MINI_PLAYER -> 0.04f
        GlassSurface.PREVIEW -> -0.05f
        GlassSurface.CARDS -> -0.08f
        GlassSurface.PANELS -> 0f
    }
    val interactionAdjustment = when (interaction) {
        GlassInteraction.IDLE -> 0f
        GlassInteraction.SELECTED -> 0.04f
        GlassInteraction.FOCUSED -> -0.06f
        GlassInteraction.PRESSED -> 0.02f
    }
    val tintAlpha = (config.alpha + roleAdjustment + interactionAdjustment).coerceIn(0.22f, 0.9f)
    val resolvedIdleRim = idleRimAlpha ?: when (surface) {
        GlassSurface.DIALOGS -> 0.18f
        GlassSurface.SIDEBAR, GlassSurface.TOPBAR, GlassSurface.MINI_PLAYER -> 0.15f
        GlassSurface.PANELS, GlassSurface.PREVIEW -> 0.11f
        GlassSurface.CARDS -> 0.06f
    }

    // Phase 4 — real backdrop blur. The single shared blurred bitmap (provided by MainActivity) is
    // drawn here as a slice aligned to this panel's on-screen position, so the frost matches the photo
    // region behind it. O(1) per panel: one textured draw.
    val blurred = LocalBlurredBackdrop.current
    val frostAlpha = (config.blurStrength * frostScale).coerceIn(0f, 1f)
    // Dense lists can contain many idle cards. Their material already reads from frost + tint; reserving
    // the extra radial light and perimeter brushes for selected/focused states removes three GPU draws
    // per idle item without changing the interaction users actually track with the D-pad.
    val lightweightIdle = surface == GlassSurface.CARDS && interaction == GlassInteraction.IDLE

    // No wallpaper is a deliberate tonal/ceramic material, not fake transparency over a flat colour.
    if (!config.hasBackdrop) {
        return this.drawWithCache {
            val body = if (lightweightIdle) null else createLuminousBody(interaction = interaction, tonal = true)
            val rim = if (lightweightIdle) null else createLuminousRim(cornerRadius = cornerRadius, interaction = interaction, idleAlpha = resolvedIdleRim)
            onDrawWithContent {
                drawRect(baseFill.copy(alpha = 0.94f))
                body?.let(::drawLuminousBody)
                drawContent()
                rim?.let(::drawLuminousRim)
            }
        }
    }

    // No cached blur (pre-API 31 / blur off) → translucent material over the real wallpaper. Idle
    // cards deliberately use this lightweight path too: list scrolling no longer resamples the full
    // backdrop texture for every visible tile; focus promotes just the active tile to real frost.
    if (blurred == null || frostAlpha <= 0f || lightweightIdle) {
        return this.drawWithCache {
            val body = if (lightweightIdle) null else createLuminousBody(interaction = interaction, tonal = false)
            val rim = if (lightweightIdle) null else createLuminousRim(cornerRadius = cornerRadius, interaction = interaction, idleAlpha = resolvedIdleRim)
            onDrawWithContent {
                drawRect(baseFill.copy(alpha = tintAlpha))
                body?.let(::drawLuminousBody)
                drawContent()
                rim?.let(::drawLuminousRim)
            }
        }
    }

    // Capture this panel's rect only for the Tier-2 path that consumes it. Avoiding this remembered
    // state entirely for solid/no-wallpaper/Tier-1 surfaces substantially reduces list bookkeeping.
    val position = remember { GlassPositionState() }

    // Tier 2 — frost. Compose can't blur a node's own backdrop, so we approximate glassmorphism by
    // drawing the blurred slice OPAQUELY: it fully MASKS the sharp background photo that would
    // otherwise bleed through the translucent panel (a translucent blurred copy over a sharp original
    // is visually identical → no perceptible blur). frostAlpha then blends frost vs. the panel tint.
    val rootW = blurred.rootSizePx.width
    val rootH = blurred.rootSizePx.height
    val bmp = blurred.bitmap
    return this
        .then(GlassPositionElement(position))
        .drawWithCache {
            // Gradients depend only on this node's size and material inputs. Cache them across frames;
            // the moving backdrop bounds stay in the draw lambda so scrolling does not rebuild them.
            val body = createLuminousBody(interaction = interaction, tonal = false)
            val rim = createLuminousRim(cornerRadius = cornerRadius, interaction = interaction, idleAlpha = resolvedIdleRim)
            val resolvedTint = (tintAlpha + (1f - frostAlpha) * 0.16f).coerceIn(0f, 0.94f)
            onDrawWithContent {
                val bounds = position.bounds
                if (rootW > 0f && rootH > 0f && bounds.width > 0f && bounds.height > 0f && bmp.width > 0 && bmp.height > 0) {
                // Draw the WHOLE blurred bitmap scaled to the root viewport, translated so the slice that
                // lands behind THIS panel is the one visible. The node is already clipped to `shape`
                // (by the upstream .clip() in RoundedPanel/DialogPanel), so only the panel's region shows.
                // We deliberately do NOT use drawImage's src/dst-slice overload here: that overload takes
                // IntOffset/IntSize, whose integer truncation of the panel's sub-pixel position misaligns
                // RGB subpixels under GPU bilinear filtering → saturated red/green/blue blocks. Using the
                // DrawScope's float translate/scale + whole-image drawImage keeps sampling continuous.
                val sx = size.width / bounds.width       // node px ↔ root px scale (usually ~1)
                val sy = size.height / bounds.height
                // Order + pivot matter: translate must be OUTSIDE the scale (in root px, not scaled px)
                // and the scale must pivot at the origin (DrawScope.scale defaults to the center). Both
                // are invisible while the bitmap is at full root resolution (scale ≈ 1) but misalign the
                // frost whenever the bitmap is smaller than the root — e.g. a 4K root with the 1920-capped
                // bitmap (scale = 2).
                translate(-bounds.left * sx, -bounds.top * sy) {
                    scale(rootW / bmp.width * sx, rootH / bmp.height * sy, pivot = Offset.Zero) {
                        drawImage(bmp, topLeft = Offset.Zero)
                    }
                }
                // The cached bitmap supplies the frost; the role/preset controls the coloured tint above it.
                // A small extra tint at low frost keeps sharp detail from fighting text.
                    drawRect(baseFill.copy(alpha = resolvedTint))
                } else {
                    // Bounds not resolved yet (first frame) → keep the panel readable with the solid fill.
                    drawRect(baseFill)
                }
                drawLuminousBody(body)
                drawContent()
                drawLuminousRim(rim)
            }
        }
}

/**
 * Position holder deliberately outside Compose snapshot state. A scrolling frost surface only needs
 * a redraw when its root-space position changes; recomposition and cache rebuilding would be wasted.
 */
private class GlassPositionState(var bounds: Rect = Rect.Zero)

private data class GlassPositionElement(
    val position: GlassPositionState,
) : ModifierNodeElement<GlassPositionNode>() {
    override fun create(): GlassPositionNode = GlassPositionNode(position)

    override fun update(node: GlassPositionNode) {
        node.position = position
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassPosition"
    }
}

private class GlassPositionNode(
    var position: GlassPositionState,
) : Modifier.Node(), GlobalPositionAwareModifierNode, DrawModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val next = coordinates.boundsInRoot()
        if (next != position.bounds) {
            position.bounds = next
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }
}

private data class LuminousBody(
    val radial: Brush,
    val focusSweep: Brush?,
    val shade: Brush,
)

private data class LuminousRim(
    val brush: Brush,
    val topLeft: Offset,
    val size: Size,
    val cornerRadius: CornerRadius,
    val stroke: Stroke,
)

/** Build size-dependent light resources once per draw-cache lifetime, not once per rendered frame. */
private fun CacheDrawScope.createLuminousBody(interaction: GlassInteraction, tonal: Boolean): LuminousBody {
    val compact = (1f - ((size.minDimension / 150f.dp.toPx()) - 1f).coerceIn(0f, 1f))
    val focused = interaction == GlassInteraction.FOCUSED || interaction == GlassInteraction.PRESSED
    val peak = when {
        focused -> 0.30f + compact * 0.16f
        interaction == GlassInteraction.SELECTED -> 0.10f
        tonal -> 0.075f
        else -> 0.055f + compact * 0.035f
    }
    return LuminousBody(
        radial = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = peak), Color.White.copy(alpha = 0f)),
            center = Offset(size.width * 0.14f, -size.height * 0.08f),
            radius = size.maxDimension * 0.72f,
        ),
        focusSweep = if (focused) {
            Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.42f to Color.Transparent,
                    0.56f to Color.White.copy(alpha = 0.07f),
                    0.68f to Color.Transparent,
                    1f to Color.Transparent,
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height * 0.25f),
            )
        } else {
            null
        },
        // A restrained lower/right shade implies material thickness without blackening the whole pane.
        shade = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (tonal) 0.10f else 0.07f)),
            startY = size.height * 0.58f,
            endY = size.height,
        ),
    )
}

/** Localized light replaces the old full-width rectangular sheen. */
private fun DrawScope.drawLuminousBody(body: LuminousBody) {
    drawRect(brush = body.radial)
    body.focusSweep?.let { drawRect(brush = it) }
    drawRect(brush = body.shade)
}

/** Directional perimeter: brightest near the light source, never a uniform white focus box. */
private fun CacheDrawScope.createLuminousRim(cornerRadius: Dp, interaction: GlassInteraction, idleAlpha: Float): LuminousRim {
    val focused = interaction == GlassInteraction.FOCUSED || interaction == GlassInteraction.PRESSED
    val selected = interaction == GlassInteraction.SELECTED
    val peak = when {
        focused -> 0.78f
        selected -> 0.28f
        else -> idleAlpha
    }
    val tail = when {
        focused -> 0.22f
        selected -> 0.10f
        else -> idleAlpha * 0.45f
    }
    val stroke = if (focused) 1.5.dp.toPx() else 1.dp.toPx()
    val inset = stroke / 2f
    return LuminousRim(
        brush = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = peak), Color.White.copy(alpha = tail), Color.White.copy(alpha = tail * 0.55f)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        topLeft = Offset(inset, inset),
        size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((cornerRadius.toPx() - inset).coerceAtLeast(0f)),
        stroke = Stroke(width = stroke),
    )
}

private fun DrawScope.drawLuminousRim(rim: LuminousRim) {
    drawRoundRect(
        brush = rim.brush,
        topLeft = rim.topLeft,
        size = rim.size,
        cornerRadius = rim.cornerRadius,
        style = rim.stroke,
    )
}

/**
 * True when backdrop blur is available on this device. Tier 2 (Phase 4) uses a cached blurred
 * copy of the background image; older devices fall back to Tier 1 translucency only.
 */
@Composable
@ReadOnlyComposable
fun supportsBackdropBlur(): Boolean =
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
