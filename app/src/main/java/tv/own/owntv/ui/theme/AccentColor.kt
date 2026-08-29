package tv.own.owntv.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import tv.own.owntv.R
import tv.own.owntv.core.theme.AccentColor

/**
 * The tonal palette and display label each [AccentColor] seeds the M3 color scheme with. Each preset
 * carries its `primary` / `primaryContainer` roles for both dark and light themes (M3 uses lighter
 * tones on dark surfaces, darker tones on light).
 *
 * Neutrals (background, surface containers, text, outline) are theme-only and live in [OwnTVColors].
 */
private class AccentPalette(
    @param:StringRes val labelRes: Int,
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryContainerDark: Color,
    val onPrimaryContainerDark: Color,
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val primaryContainerLight: Color,
    val onPrimaryContainerLight: Color,
)

private val TealPalette = AccentPalette(
    R.string.settings_accent_teal,
    primaryDark = Color(0xFF52DBC8), onPrimaryDark = Color(0xFF003730),
    primaryContainerDark = Color(0xFF004F46), onPrimaryContainerDark = Color(0xFF6FF8E4),
    primaryLight = Color(0xFF006B5E), onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFF6FF8E4), onPrimaryContainerLight = Color(0xFF00201B),
)

private val BluePalette = AccentPalette(
    R.string.settings_accent_blue,
    primaryDark = Color(0xFF6FB0FF), onPrimaryDark = Color(0xFF00315C),
    primaryContainerDark = Color(0xFF134A7C), onPrimaryContainerDark = Color(0xFFD3E4FF),
    primaryLight = Color(0xFF1565C0), onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFD6E3FF), onPrimaryContainerLight = Color(0xFF001C3A),
)

private val VioletPalette = AccentPalette(
    R.string.settings_accent_violet,
    primaryDark = Color(0xFFCBBEFF), onPrimaryDark = Color(0xFF312170),
    primaryContainerDark = Color(0xFF483A88), onPrimaryContainerDark = Color(0xFFE7DEFF),
    primaryLight = Color(0xFF5B45C9), onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFE5DEFF), onPrimaryContainerLight = Color(0xFF190066),
)

private val GreenPalette = AccentPalette(
    R.string.settings_accent_green,
    primaryDark = Color(0xFF6FDB94), onPrimaryDark = Color(0xFF00391C),
    primaryContainerDark = Color(0xFF1F5135), onPrimaryContainerDark = Color(0xFF8BF8AF),
    primaryLight = Color(0xFF1B6B3F), onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFA6F2C0), onPrimaryContainerLight = Color(0xFF00210F),
)

private val AmberPalette = AccentPalette(
    R.string.settings_accent_amber,
    primaryDark = Color(0xFFFFB95C), onPrimaryDark = Color(0xFF452B00),
    primaryContainerDark = Color(0xFF624000), onPrimaryContainerDark = Color(0xFFFFDDB3),
    primaryLight = Color(0xFF8A5100), onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFFFDDB3), onPrimaryContainerLight = Color(0xFF2C1600),
)

private val AccentColor.palette: AccentPalette
    get() = when (this) {
        AccentColor.TEAL -> TealPalette
        AccentColor.BLUE -> BluePalette
        AccentColor.VIOLET -> VioletPalette
        AccentColor.GREEN -> GreenPalette
        AccentColor.AMBER -> AmberPalette
    }

val AccentColor.labelRes: Int
    @StringRes get() = palette.labelRes

fun AccentColor.primary(isDark: Boolean) = with(palette) { if (isDark) primaryDark else primaryLight }
fun AccentColor.onPrimary(isDark: Boolean) = with(palette) { if (isDark) onPrimaryDark else onPrimaryLight }
fun AccentColor.primaryContainer(isDark: Boolean) = with(palette) { if (isDark) primaryContainerDark else primaryContainerLight }
fun AccentColor.onPrimaryContainer(isDark: Boolean) = with(palette) { if (isDark) onPrimaryContainerDark else onPrimaryContainerLight }
