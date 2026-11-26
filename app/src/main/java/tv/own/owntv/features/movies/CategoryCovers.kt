package tv.own.owntv.features.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.own.owntv.core.repository.SmartCategoriesRepository
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageText

/** (DPTV v2.10.2) A category cover's two gradient colours and its mark. */
private data class CoverStyle(val top: Color, val bottom: Color, val icon: OwnTVIcon)

private fun rgb(hex: Int) = Color(0xFF000000L or hex.toLong())

/** Each TMDB genre in a colour of its own; anything else falls back to the category's kind. */
private val GenreColours: Map<String, Pair<Int, Int>> = mapOf(
    "action" to (0xE2483D to 0x5A1010),
    "adventure" to (0xE89B2C to 0x5C2E06),
    "animation" to (0x3BC98A to 0x0F4A3A),
    "comedy" to (0xF2C94C to 0x7A4B05),
    "crime" to (0x5D6B82 to 0x1A2130),
    "documentary" to (0x2BB3A3 to 0x0B3D3A),
    "drama" to (0x9B5DE5 to 0x2E1450),
    "family" to (0xF08A5D to 0x6B2A14),
    "fantasy" to (0x8E7CF0 to 0x26195C),
    "history" to (0xB58552 to 0x3F2612),
    "horror" to (0xA4161A to 0x170405),
    "music" to (0xE0559B to 0x4D0F33),
    "mystery" to (0x4F5BD5 to 0x141A4A),
    "romance" to (0xF25F8E to 0x5C0F2A),
    "science fiction" to (0x22B8E6 to 0x0A2F4D),
    "thriller" to (0x3A7BD5 to 0x0D1A36),
    "tv movie" to (0x8A96A3 to 0x2A3038),
    "war" to (0x7D8F4E to 0x262E12),
    "western" to (0xC96F3B to 0x4A220D),
)

private fun styleOf(category: SmartCategoriesRepository.Category): CoverStyle = when (category.kind) {
    "trending" -> CoverStyle(rgb(0xFF6A3D), rgb(0x7A0F45), OwnTVIcon.TREND)
    "new" -> CoverStyle(rgb(0x3DDC84), rgb(0x0B4A4A), OwnTVIcon.SPARKLE)
    "genre" -> GenreColours[category.name.lowercase()]
        ?.let { (a, b) -> CoverStyle(rgb(a), rgb(b), OwnTVIcon.TAGS) }
        ?: CoverStyle(rgb(0x5B8DEF), rgb(0x16204A), OwnTVIcon.TAGS)
    "decade" -> {
        // Older decades in warmer, more faded tones; recent ones brighter and cooler.
        val year = category.name.take(4).toIntOrNull() ?: 2000
        val t = ((year - 1920).coerceIn(0, 100)) / 100f
        CoverStyle(lerp(rgb(0xC9A36B), rgb(0x4FA3F7), t), lerp(rgb(0x3B2410), rgb(0x0E1E44), t), OwnTVIcon.CALENDAR)
    }
    else -> CoverStyle(rgb(0x5B8DEF), rgb(0x16204A), OwnTVIcon.MOVIES)
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)

/**
 * (DPTV v2.10.2) A category's cover, the first tile of its carousel: its own gradient, its top film's
 * poster faded in behind, its mark, its name in large type (a decade as "1990s" even larger) and its
 * film count.
 */
@Composable
internal fun BoxScope.CategoryCover(category: SmartCategoriesRepository.Category, posterUrl: String?) {
    val style = styleOf(category)
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(style.top, style.bottom), start = Offset.Zero, end = Offset(0f, Float.POSITIVE_INFINITY)),
        ),
    )
    if (!posterUrl.isNullOrBlank()) {
        AsyncImage(
            model = posterUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.32f),
        )
    }
    // The colour returns over the poster towards the bottom, so the name always reads.
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to style.top.copy(alpha = 0.35f), 0.45f to Color.Transparent, 1f to style.bottom.copy(alpha = 0.95f)),
        ),
    )
    Column(Modifier.fillMaxSize().padding(14.mpx)) {
        Box(
            Modifier.size(40.mpx).background(Color.White.copy(alpha = 0.18f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) { OwnTVIcon(style.icon, Color.White, Modifier.size(24.mpx)) }
        Spacer(Modifier.weight(1f))
        val decade = category.kind == "decade"
        Text(
            category.name,
            // A long single word ("Documentary") steps down so it fits the tile's width.
            style = if (decade) stageText(38, 900).copy(lineHeight = 40.mpxSp)
            else if (category.name.split(' ').maxOf { it.length } > 9) stageText(20, 900).copy(lineHeight = 22.mpxSp)
            else stageText(24, 900).copy(lineHeight = 26.mpxSp),
            color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.mpx))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                category.films.size.toString(), style = stageText(16, 700), color = Color.White.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            OwnTVIcon(OwnTVIcon.CHEVRON, Color.White, Modifier.size(22.mpx))
        }
    }
}
