package tv.own.owntv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.DptvPalette

/**
 * DPTV-Player's resolved Material 3 color roles for the current theme. Read as `OwnTVTheme.colors`.
 *
 * Exposes the full M3 surface-container tiers and primary/secondary/tertiary roles the MD3 UI needs.
 * A few legacy aliases (`panel`/`card`/`rail`/`textPrimary`/`textSecondary`/`accent`) map onto M3
 * roles so older components keep working.
 */
@Immutable
data class OwnTVColors(
    val isDark: Boolean,
    // Surfaces
    val background: Color,
    val surface: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    // Primary
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    // Secondary
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    // Tertiary
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    // Focus / status
    val focusBorder: Color,
    val focusGlow: Color,
    val favorite: Color,
    /**
     * The accent as it must look on the player's chrome, which is always dark scrim over video no
     * matter which theme the app is in. [primary] can't be used there: on the light theme it is the
     * deep tone M3 picks for a light surface (DPTV-Player deep green #0E6B41), which all but disappears
     * against a dark HUD. This is the same accent resolved for dark surfaces, so the seek bar and the
     * active buttons stay readable — and on the dark theme it is simply [primary].
     */
    val accentOnVideo: Color,
    /** Text/icon colour for content drawn ON [accentOnVideo] — e.g. the count inside a badge. */
    val onAccentOnVideo: Color,
) {
    // Legacy aliases used by existing components.
    val textPrimary: Color get() = onSurface
    val textSecondary: Color get() = onSurfaceVariant
    val panel: Color get() = surfaceContainerLow
    val card: Color get() = surfaceContainerHigh
    val rail: Color get() = surfaceContainer
    val accent: Color get() = primary
}

/**
 * Parses "#RRGGBB" / "RRGGBB" (also 8-digit AARRGGBB) into a [Color]; null when invalid.
 *
 * The parsing itself is core's, so the mobile app reads a user's custom accent identically.
 */
fun parseAccentHex(hex: String): Color? =
    tv.own.owntv.core.theme.parseAccentHex(hex)?.let { Color(it) }

/**
 * Build the resolved M3 tokens for a theme (dark/light).
 *
 * DPTV-Player ships one fixed brand colour: the signature green from [DptvPalette]. [accent],
 * [customAccent] and [focusHighlight] are accepted for call-site compatibility (stored values
 * from before the rebrand, clock helpers) but no longer change the theme — the settings UI
 * offers no colour choice anymore. The focus ring is the floodlight green in dark ([Beam]) and
 * the signature green itself in light.
 */
fun ownTvColors(
    isDark: Boolean,
    accent: AccentColor,
    customAccent: String = "",
    focusHighlight: String = "",
): OwnTVColors {
    val roles = if (isDark) DptvPalette.DarkPrimary else DptvPalette.LightPrimary
    val primary = Color(roles.primary)
    val onVideoRoles = DptvPalette.DarkPrimary
    val focus = if (isDark) Color(DptvPalette.Beam) else primary
    return if (isDark) {
        OwnTVColors(
            isDark = true,
            background = DarkBackground,
            surface = DarkSurface,
            surfaceContainerLowest = DarkSurfaceContainerLowest,
            surfaceContainerLow = DarkSurfaceContainerLow,
            surfaceContainer = DarkSurfaceContainer,
            surfaceContainerHigh = DarkSurfaceContainerHigh,
            surfaceContainerHighest = DarkSurfaceContainerHighest,
            onSurface = DarkOnSurface,
            onSurfaceVariant = DarkOnSurfaceVariant,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
            primary = primary,
            onPrimary = Color(roles.onPrimary),
            primaryContainer = Color(roles.primaryContainer),
            onPrimaryContainer = Color(roles.onPrimaryContainer),
            secondary = DarkSecondary,
            onSecondary = DarkOnSecondary,
            secondaryContainer = DarkSecondaryContainer,
            onSecondaryContainer = DarkOnSecondaryContainer,
            tertiary = DarkTertiary,
            onTertiary = DarkOnTertiary,
            tertiaryContainer = DarkTertiaryContainer,
            onTertiaryContainer = DarkOnTertiaryContainer,
            focusBorder = focus,
            focusGlow = focus.copy(alpha = 0.40f),
            favorite = DarkError,
            accentOnVideo = Color(onVideoRoles.primary),
            onAccentOnVideo = Color(onVideoRoles.onPrimary),
        )
    } else {
        OwnTVColors(
            isDark = false,
            background = LightBackground,
            surface = LightSurface,
            surfaceContainerLowest = LightSurfaceContainerLowest,
            surfaceContainerLow = LightSurfaceContainerLow,
            surfaceContainer = LightSurfaceContainer,
            surfaceContainerHigh = LightSurfaceContainerHigh,
            surfaceContainerHighest = LightSurfaceContainerHighest,
            onSurface = LightOnSurface,
            onSurfaceVariant = LightOnSurfaceVariant,
            outline = LightOutline,
            outlineVariant = LightOutlineVariant,
            primary = primary,
            onPrimary = Color(roles.onPrimary),
            primaryContainer = Color(roles.primaryContainer),
            onPrimaryContainer = Color(roles.onPrimaryContainer),
            secondary = LightSecondary,
            onSecondary = LightOnSecondary,
            secondaryContainer = LightSecondaryContainer,
            onSecondaryContainer = LightOnSecondaryContainer,
            tertiary = LightTertiary,
            onTertiary = LightOnTertiary,
            tertiaryContainer = LightTertiaryContainer,
            onTertiaryContainer = LightOnTertiaryContainer,
            focusBorder = focus,
            focusGlow = focus.copy(alpha = 0.28f),
            favorite = LightError,
            accentOnVideo = Color(onVideoRoles.primary),
            onAccentOnVideo = Color(onVideoRoles.onPrimary),
        )
    }
}

val LocalOwnTVColors = staticCompositionLocalOf { ownTvColors(isDark = true, accent = AccentColor.DPTV) }
