package tv.own.owntv.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Maps a channel's category name to a coarse "genre" with a colour, used as a small dot/badge
 * in the Live preview metadata row and the Guide channel rows so a user can spot content type at
 * a glance.
 *
 * Green = Sport · Red = News · Violet = Movies/Film · Amber = Kids/Animation ·
 * Blue = Music · Teal = Documentary · Grey = Other.
 *
 * The inference is keyword-based on the (lower-cased) category name and intentionally lenient —
 * it's a visual hint, not authoritative metadata. Anything unmatched falls back to [OTHER].
 */
enum class ChannelGenre(val label: String, val dot: Color) {
    SPORT("Sport", Color(0xFF4CAF50)),
    NEWS("News", Color(0xFFEF5350)),
    MOVIES("Movies", Color(0xFFAB47BC)),
    KIDS("Kids", Color(0xFFFFB300)),
    MUSIC("Music", Color(0xFF42A5F5)),
    DOCUMENTARY("Documentary", Color(0xFF26A69A)),
    OTHER("Other", Color(0xFF9E9E9E));

    companion object {
        /**
         * Match by keyword. Order matters — earlier genres win on overlap (e.g. "Sport News" → Sport).
         * Returns [OTHER] when the name is blank or no keyword matches.
         */
        fun fromCategory(categoryName: String?): ChannelGenre {
            val n = categoryName?.lowercase()?.trim().orEmpty()
            if (n.isEmpty()) return OTHER
            val keywords: (ChannelGenre) -> List<String> = {
                when (it) {
                    SPORT -> listOf("sport", "football", "soccer", "cricket", "espn", "golf", "tennis", "basket", "baseball", "racing", "fight", "ufc", "wwe", "boxing", "hockey")
                    NEWS -> listOf("news")
                    MOVIES -> listOf("movie", "film", "cinema", "flix", "premiere")
                    KIDS -> listOf("kid", "child", "cartoon", "anim", "disney", "junior", "baby", "teen", "nick", "boomerang")
                    MUSIC -> listOf("music", "mtv", "vh1", "concert")
                    DOCUMENTARY -> listOf("documentary", "docu", "discovery", "nature", "history", "science", "national geo", "nat geo")
                    OTHER -> emptyList()
                }
            }
            // Preserve declaration order so precedence is deterministic.
            val ordered = listOf(SPORT, NEWS, MOVIES, KIDS, MUSIC, DOCUMENTARY)
            for (g in ordered) {
                if (keywords(g).any { n.contains(it) }) return g
            }
            return OTHER
        }

        /**
         * The dot colour for a category, or null when the category doesn't match a known genre.
         * For Guide-style usage where no dot is preferable to a grey "other" dot.
         */
        fun dotFor(categoryName: String?): Color? = fromCategory(categoryName).let {
            if (it == OTHER) null else it.dot
        }
    }
}
