package tv.own.owntv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import tv.own.owntv.R

/**
 * Caladea — Google's metric-compatible equivalent of Cambria (which is Microsoft-licensed and
 * cannot be bundled). Popup menus render in this serif per owner preference; the rest of the
 * app keeps the sans-serif [OwnTVTypography]. Caladea ships Regular/Bold only, so Medium and
 * SemiBold map to the nearest available weight.
 */
val PopupFontFamily = FontFamily(
    Font(R.font.caladea_regular, FontWeight.Normal),
    Font(R.font.caladea_regular, FontWeight.Medium),
    Font(R.font.caladea_bold, FontWeight.SemiBold),
    Font(R.font.caladea_bold, FontWeight.Bold),
    Font(R.font.caladea_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.caladea_bolditalic, FontWeight.Bold, FontStyle.Italic),
)

/** Wraps popup-menu content so every text style inside uses [PopupFontFamily]. */
@Composable
fun PopupFontTheme(content: @Composable () -> Unit) {
    val t = MaterialTheme.typography
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = Typography(
            displayLarge = t.displayLarge.copy(fontFamily = PopupFontFamily),
            displayMedium = t.displayMedium.copy(fontFamily = PopupFontFamily),
            displaySmall = t.displaySmall.copy(fontFamily = PopupFontFamily),
            headlineLarge = t.headlineLarge.copy(fontFamily = PopupFontFamily),
            headlineMedium = t.headlineMedium.copy(fontFamily = PopupFontFamily),
            headlineSmall = t.headlineSmall.copy(fontFamily = PopupFontFamily),
            titleLarge = t.titleLarge.copy(fontFamily = PopupFontFamily),
            titleMedium = t.titleMedium.copy(fontFamily = PopupFontFamily),
            titleSmall = t.titleSmall.copy(fontFamily = PopupFontFamily),
            bodyLarge = t.bodyLarge.copy(fontFamily = PopupFontFamily),
            bodyMedium = t.bodyMedium.copy(fontFamily = PopupFontFamily),
            bodySmall = t.bodySmall.copy(fontFamily = PopupFontFamily),
            labelLarge = t.labelLarge.copy(fontFamily = PopupFontFamily),
            labelMedium = t.labelMedium.copy(fontFamily = PopupFontFamily),
            labelSmall = t.labelSmall.copy(fontFamily = PopupFontFamily),
        ),
        content = content,
    )
}
