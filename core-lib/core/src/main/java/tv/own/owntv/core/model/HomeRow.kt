package tv.own.owntv.core.model

import androidx.compose.runtime.Immutable

import org.json.JSONArray
import org.json.JSONObject

enum class HomeRow {
    TRENDING,
    HERO,
    RECENT_CHANNELS,
    FAVORITE_CHANNELS,
    FAVORITE_MOVIES,
    FAVORITE_SERIES,
    CONTINUE_MOVIES,
    CONTINUE_SERIES,
    /** TMDB's trending films / shows (on streaming in the user's country, or this week), matched to the playlists (v2.4.0). */
    TRENDING_MOVIES,
    TRENDING_SHOWS;

    val implemented: Boolean get() = true

    companion object {
        /**
         * Off Home since v2.4.0: Keep watching (the owner's call), and TMDB's Now trending, which the
         * two TMDB Trending movies / Trending shows rows replace. Their code and stored choices stay, so bringing one back is this set.
         */
        val RETIRED: Set<HomeRow> = setOf(TRENDING, HERO)

        /** Trending movies / Trending shows: not reordered on their own, they always follow Recent channels (v2.4.0). */
        val TRENDING_ROWS: List<HomeRow> = listOf(TRENDING_MOVIES, TRENDING_SHOWS)
    }
}

enum class HomeLiveRowMode {
    CARDS,
    ON_NOW;

    fun toggled(): HomeLiveRowMode = when (this) {
        CARDS -> ON_NOW
        ON_NOW -> CARDS
    }
}

/**
 * How the Trending row is drawn: the full card with the artwork, badges and reasons, or the plain
 * strip of posters. [HERO] is the default, so nothing changes for anyone who never opens the setting.
 */
enum class HomeTrendingStyle {
    HERO,
    POSTERS,
}

enum class HeroKind {
    LIVE, MOVIES, SERIES,
}

@Immutable
data class HomeConfig(
    val order: List<HomeRow> = HomeRow.entries.toList(),
    val hidden: Set<HomeRow> = setOf(HomeRow.RECENT_CHANNELS),
    val heroIncludeLive: Boolean = true,
    val heroIncludeMovies: Boolean = true,
    val heroIncludeSeries: Boolean = true,
    val recentLiveMode: HomeLiveRowMode = HomeLiveRowMode.CARDS,
    val favoriteLiveMode: HomeLiveRowMode = HomeLiveRowMode.ON_NOW,
    val trendingStyle: HomeTrendingStyle = HomeTrendingStyle.HERO,
) {
    val visibleOrder: List<HomeRow>
        get() = buildList {
            // Trending used to own the top position here; since v2.4.0 it and Keep watching are off
            // Home ([HomeRow.RETIRED]). The two Trending rows sit right under Recent channels — at its
            // place in the order even while that row itself is hidden.
            order.filter { it !in HomeRow.RETIRED && it !in HomeRow.TRENDING_ROWS && it.implemented }.forEach { row ->
                if (row !in hidden) add(row)
                if (row == HomeRow.RECENT_CHANNELS) addAll(HomeRow.TRENDING_ROWS.filter { it !in hidden })
            }
        }

    val settingsRows: List<HomeRow>
        get() = order.filter { it !in HomeRow.RETIRED && it !in HomeRow.TRENDING_ROWS && it.implemented }

    fun toJson(): JSONObject = JSONObject().apply {
        put("order", JSONArray(order.map { it.name }))
        put("hidden", JSONArray(hidden.map { it.name }))
        put("heroLive", heroIncludeLive)
        put("heroMovies", heroIncludeMovies)
        put("heroSeries", heroIncludeSeries)
        put("recentLiveMode", recentLiveMode.name)
        put("favoriteLiveMode", favoriteLiveMode.name)
        put("trendingStyle", trendingStyle.name)
    }

    companion object {
        fun fromJson(raw: String?): HomeConfig {
            if (raw.isNullOrBlank()) return HomeConfig()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return HomeConfig()
            val storedOrder = obj.optJSONArray("order").toHomeRows()
            val hidden = if (obj.has("hidden")) obj.optJSONArray("hidden").toHomeRows().toSet()
                else HomeConfig().hidden
            return HomeConfig(
                order = mergeOrder(storedOrder),
                hidden = hidden,
                heroIncludeLive = readBool(obj, "heroLive", "heroIncludeLive", default = true),
                heroIncludeMovies = readBool(obj, "heroMovies", "heroIncludeMovies", default = true),
                heroIncludeSeries = readBool(obj, "heroSeries", "heroIncludeSeries", default = true),
                recentLiveMode = readLiveMode(obj, "recentLiveMode", HomeLiveRowMode.CARDS),
                favoriteLiveMode = readLiveMode(obj, "favoriteLiveMode", HomeLiveRowMode.ON_NOW),
                // Absent in every config written before the setting existed, and in every backup
                // taken then — those all read as the hero, which is what they were showing.
                trendingStyle = runCatching { HomeTrendingStyle.valueOf(obj.optString("trendingStyle")) }
                    .getOrDefault(HomeTrendingStyle.HERO),
            )
        }

        private fun readBool(obj: JSONObject, primary: String, fallback: String, default: Boolean): Boolean =
            when {
                obj.has(primary) -> obj.optBoolean(primary, default)
                obj.has(fallback) -> obj.optBoolean(fallback, default)
                else -> default
            }

        private fun readLiveMode(obj: JSONObject, key: String, default: HomeLiveRowMode): HomeLiveRowMode =
            runCatching { HomeLiveRowMode.valueOf(obj.optString(key)) }.getOrDefault(default)
    }
}

fun mergeOrder(stored: List<HomeRow>): List<HomeRow> {
    val result = LinkedHashSet<HomeRow>()
    stored.forEach { result += it }
    HomeRow.entries.forEachIndexed { index, row ->
        if (row !in result) {
            val current = result.toMutableList()
            current.add(index.coerceAtMost(current.size), row)
            result.clear()
            result += current
        }
    }
    return result.toList()
}

private fun JSONArray?.toHomeRows(): List<HomeRow> {
    if (this == null) return emptyList()
    val out = ArrayList<HomeRow>(length())
    for (i in 0 until length()) {
        val row = runCatching { HomeRow.valueOf(optString(i)) }.getOrNull() ?: continue
        if (row !in out) out += row
    }
    return out
}
