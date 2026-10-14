package tv.own.owntv.core.theme

/**
 * The one place DPTV-Player's colour values are written down, shared by the TV app and the mobile app.
 *
 * Values are ARGB longs rather than `androidx.compose.ui.graphics.Color` because core carries the
 * Compose *runtime* only — no `compose-ui` — and because the two apps build different colour
 * schemes from them (`androidx.tv.material3` on the TV, `androidx.compose.material3` here). Each
 * app wraps a value once: `Color(OwnTVPalette.DarkBackground)`.
 *
 * Dark uses a near-black pitch green (#060b07) so the panel colours (Phase 6) pop against
 * the deep dark surface while keeping a subtle green undertone. NEUTRAL and the secondary/tertiary roles are
 * theme-only; the `primary` roles are DPTV-Player's fixed signature green ([DptvPalette]).
 *
 * The three `*RailPanel` / `*ContentPanel` / `*PreviewPanel` values are not part of the M3 ladder:
 * they are the shell's per-region colour identity, keeping the navigation, the content and the
 * detail pane distinct in both themes instead of collapsing into the greyer generic elevation
 * steps. Both apps draw those same three regions, so they belong here rather than in either one.
 */
object OwnTVPalette {

    /** Brand mark colour (the DPTV-Player play logo) — constant, and the fixed signature accent. */
    const val AccentCyan = 0xFF4FEE94L

    // ---------------- DARK (M3 dark over near-black #060b07) ----------------
    const val DarkBackground = 0xFF060B07L
    const val DarkSurface = 0xFF0B120DL
    const val DarkSurfaceContainerLowest = 0xFF08100BL
    const val DarkSurfaceContainerLow = 0xFF101A13L
    const val DarkSurfaceContainer = 0xFF16241BL
    const val DarkSurfaceContainerHigh = 0xFF1E3025L
    const val DarkSurfaceContainerHighest = 0xFF28402FL
    const val DarkRailPanel = 0xFF0C1710L
    const val DarkContentPanel = 0xFF08120CL
    const val DarkPreviewPanel = 0xFF122018L
    const val DarkOnSurface = 0xFFEAF7EFL
    const val DarkOnSurfaceVariant = 0xFFA6C3AFL
    const val DarkOutline = 0xFF3C5A48L
    const val DarkOutlineVariant = 0xFF1E3126L
    const val DarkSecondary = 0xFF9FC8B0L
    const val DarkOnSecondary = 0xFF0A1F14L
    const val DarkSecondaryContainer = 0xFF22402EL
    const val DarkOnSecondaryContainer = 0xFFBCE0C9L
    const val DarkTertiary = 0xFF7FC4D6L
    const val DarkOnTertiary = 0xFF04222AL
    const val DarkTertiaryContainer = 0xFF1B3D46L
    const val DarkOnTertiaryContainer = 0xFFB4E8F3L
    const val DarkError = 0xFFFF8A86L

    // ---------------- LIGHT (M3 light) ----------------
    const val LightBackground = 0xFFF4F8F4L
    const val LightSurface = 0xFFFFFFFFL
    const val LightSurfaceContainerLowest = 0xFFFFFFFFL
    const val LightSurfaceContainerLow = 0xFFEFF5F0L
    const val LightSurfaceContainer = 0xFFEDF3EEL
    const val LightSurfaceContainerHigh = 0xFFE4ECE6L
    const val LightSurfaceContainerHighest = 0xFFDCE6DFL
    const val LightRailPanel = 0xFFE9F1EBL
    const val LightContentPanel = 0xFFF8FBF8L
    const val LightPreviewPanel = 0xFFE1EBE4L
    const val LightOnSurface = 0xFF081C11L
    const val LightOnSurfaceVariant = 0xFF3C5546L
    const val LightOutline = 0xFFA8BBAEL
    const val LightOutlineVariant = 0xFFD2DED5L
    const val LightSecondary = 0xFF4F7261L
    const val LightOnSecondary = 0xFFFFFFFFL
    const val LightSecondaryContainer = 0xFFD3E9DCL
    const val LightOnSecondaryContainer = 0xFF10291CL
    const val LightTertiary = 0xFF3F6183L
    const val LightOnTertiary = 0xFFFFFFFFL
    const val LightTertiaryContainer = 0xFFD5E9F3L
    const val LightOnTertiaryContainer = 0xFF112B3CL
    const val LightError = 0xFFC42127L
}

/**
 * The four M3 primary roles for one accent on one theme, as ARGB longs.
 *
 * They come either from a preset's table ([roles]) or are generated from the user's custom hex
 * seed ([accentRolesFromSeed]).
 */
data class AccentRoleValues(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
)

/**
 * The tonal palette each [AccentColor] seeds the M3 colour scheme with, for both themes (M3 uses
 * lighter tones on dark surfaces, darker tones on light).
 *
 * DPTV-Player ships a single fixed signature green ([DptvPalette.roles]). The preset enum is kept
 * only so stored choices from before the rebrand keep resolving — every entry returns the
 * DPTV-Player roles. There is no user-facing accent choice anymore.
 *
 * The display label belongs to whichever app is drawing the settings screen, so it is not here.
 */
private class AccentPalette(val dark: AccentRoleValues, val light: AccentRoleValues)

private val DptvBrandPalette = AccentPalette(
    dark = AccentRoleValues(0xFF4FEE94L, 0xFF081C11L, 0xFF134D2CL, 0xFF9BF5BEL),
    light = AccentRoleValues(0xFF0E6B41L, 0xFFFFFFFFL, 0xFFC8EFD8L, 0xFF08301CL),
)

/** The preset's four primary-role values for the given theme (always DPTV-Player green). */
fun AccentColor.roles(isDark: Boolean): AccentRoleValues {
    return if (isDark) DptvBrandPalette.dark else DptvBrandPalette.light
}

/** Parses "#RRGGBB" / "RRGGBB" (also 8-digit AARRGGBB) into an ARGB long; null when invalid. */
fun parseAccentHex(hex: String): Long? {
    val s = hex.trim().removePrefix("#")
    return runCatching {
        when (s.length) {
            6 -> 0xFF000000L or s.toLong(16)
            8 -> s.toLong(16)
            else -> null
        }
    }.getOrNull()
}

/**
 * Generate tonal primary roles from an arbitrary seed colour (the custom hex accent).
 * The seed is used EXACTLY as `primary` so the user's hex renders true; only the supporting
 * contrast roles (onPrimary / containers) are derived by nudging the seed's lightness.
 */
fun accentRolesFromSeed(seed: Long, isDark: Boolean): AccentRoleValues {
    val argb = seed.toInt()
    // Choose a readable foreground for text/icons drawn on top of the exact seed colour.
    val onPrimary = if (androidx.core.graphics.ColorUtils.calculateLuminance(argb) > 0.5) {
        0xFF000000L
    } else {
        0xFFFFFFFFL
    }
    return if (isDark) {
        AccentRoleValues(seed, onPrimary, argb.withLightness(0.26f), argb.withLightness(0.90f))
    } else {
        AccentRoleValues(seed, onPrimary, argb.withLightness(0.88f), argb.withLightness(0.10f))
    }
}

/** Keep the seed's hue/saturation but pin the HSL lightness — a cheap stand-in for M3 tones. */
private fun Int.withLightness(l: Float): Long {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(this, hsl)
    hsl[2] = l
    return androidx.core.graphics.ColorUtils.HSLToColor(hsl).toLong() and 0xFFFFFFFFL
}
