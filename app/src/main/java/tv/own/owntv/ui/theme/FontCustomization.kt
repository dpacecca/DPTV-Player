package tv.own.owntv.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import tv.own.owntv.R

/** The five font families users can independently apply to the main UI and popup chrome. */
enum class AppFontFamily {
    LORA,
    SYSTEM_SANS,
    PLAYFAIR_DISPLAY,
    DANCING_SCRIPT,
    POPPINS;

    companion object {
        fun fromStored(value: String?, fallback: AppFontFamily): AppFontFamily =
            entries.firstOrNull { it.name == value } ?: fallback
    }
}

/** App-only text scaling. Android's system font scale remains the base and is multiplied by this. */
object UiFontScale {
    const val MIN = 60
    const val MAX = 140
    const val DEFAULT = 100
    const val STEP = 5

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)
    fun factor(percent: Int): Float = clamp(percent) / 100f
}

data class FontCustomization(
    val sizePercent: Int = UiFontScale.DEFAULT,
    val mainFamily: AppFontFamily = AppFontFamily.SYSTEM_SANS,
    val popupFamily: AppFontFamily = AppFontFamily.LORA,
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variableFont(resourceId: Int, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
    Font(
        resourceId,
        weight = weight,
        style = style,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

private val LoraFamily = FontFamily(
    variableFont(R.font.lora_variable, FontWeight.Normal),
    variableFont(R.font.lora_variable, FontWeight.Medium),
    variableFont(R.font.lora_variable, FontWeight.SemiBold),
    variableFont(R.font.lora_variable, FontWeight.Bold),
    variableFont(R.font.lora_italic_variable, FontWeight.Normal, FontStyle.Italic),
    variableFont(R.font.lora_italic_variable, FontWeight.Bold, FontStyle.Italic),
)

private val PlayfairDisplayFamily = FontFamily(
    variableFont(R.font.playfair_display_variable, FontWeight.Normal),
    variableFont(R.font.playfair_display_variable, FontWeight.Medium),
    variableFont(R.font.playfair_display_variable, FontWeight.SemiBold),
    variableFont(R.font.playfair_display_variable, FontWeight.Bold),
    variableFont(R.font.playfair_display_italic_variable, FontWeight.Normal, FontStyle.Italic),
    variableFont(R.font.playfair_display_italic_variable, FontWeight.Bold, FontStyle.Italic),
)

private val DancingScriptFamily = FontFamily(
    variableFont(R.font.dancing_script_variable, FontWeight.Normal),
    variableFont(R.font.dancing_script_variable, FontWeight.Medium),
    variableFont(R.font.dancing_script_variable, FontWeight.SemiBold),
    variableFont(R.font.dancing_script_variable, FontWeight.Bold),
)

private val PoppinsFamily = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

fun AppFontFamily.asComposeFamily(): FontFamily = when (this) {
    AppFontFamily.LORA -> LoraFamily
    AppFontFamily.SYSTEM_SANS -> FontFamily.SansSerif
    AppFontFamily.PLAYFAIR_DISPLAY -> PlayfairDisplayFamily
    AppFontFamily.DANCING_SCRIPT -> DancingScriptFamily
    AppFontFamily.POPPINS -> PoppinsFamily
}

val LocalMainFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }
val LocalPopupFontFamily = staticCompositionLocalOf<FontFamily> { LoraFamily }
val LocalUiFontScaleFactor = staticCompositionLocalOf { 1f }
