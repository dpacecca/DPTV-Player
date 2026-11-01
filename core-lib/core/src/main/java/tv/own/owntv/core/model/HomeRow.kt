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
    TRENDING_SHOWS,
    /** (DPTV v2.9.1) TMDB's picks from what was watched, only what Jellyfin or the playlists have. */
    RECOMMENDED_MOVIES,
    RECOMMENDED_SHOWS,
    /** (DPTV v2.9.1) What was added last on the user's Jellyfin server. */
    JELLYFIN_MOVIES,
    JELLYFIN_SHOWS,
    /** (DPTV v2.9.1) The Trakt watchlist, only what Jellyfin or the playlists have. */
    TRAKT_WATCHLIST;

    val implemented: Boolean get() = true

    companion object {
        /**
         * Off Home since v2.4.0: Keep watching (the owner's call), and TMDB's Now trending, which the
         * two TMDB Trending movies / Trending shows rows replace. Their code and stored choices stay, so bringing one back is this set.
         */
        val RETIRED: Set<HomeRow> = setOf(TRENDING, HERO)

        /**
         * Rows that follow Recent channels instead of having a place of their own. Trending movies / Trending
         * shows were (v2.4.0); since DPTV v2.9.1 every row is placed on its own, so none is.
         */
        val TRENDING_ROWS: List<HomeRow> = emptyList()
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
    val order: List<HomeRow> = HomeConfig.trendingUnderRecent(HomeRow.entries.toList()),
    val hidden: Set<HomeRow> = setOf(HomeRow.RECENT_CHANNELS),
    val heroIncludeLive: Boolean = true,
    val heroIncludeMovies: Boolean = true,
    val heroIncludeSeries: Boolean = true,
    val recentLiveMode: HomeLiveRowMode = HomeLiveRowMode.CARDS,
    val favoriteLiveMode: HomeLiveRowMode = HomeLiveRowMode.ON_NOW,
    val trendingStyle: HomeTrendingStyle = HomeTrendingStyle.HERO,
    /** (v2.6.0) The featured titles at the top of Home: TMDB's trending films and shows found in the playlists. */
    val featuredHero: Boolean = true,
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
        put("featured", featuredHero)
        put("orderVersion", ORDER_VERSION)
    }

    companion object {
        fun fromJson(raw: String?): HomeConfig {
            if (raw.isNullOrBlank()) return HomeConfig()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return HomeConfig()
            val storedOrder = obj.optJSONArray("order").toHomeRows()
            val hidden = if (obj.has("hidden")) obj.optJSONArray("hidden").toHomeRows().toSet()
                else HomeConfig().hidden
            // (DPTV v2.9.1) Trending rows used to sit under Recent channels whatever their stored place;
            // a config from before then has them moved there once, so Home looks the same.
            val merged = mergeOrder(storedOrder)
            val placed = if (obj.optInt("orderVersion", 1) >= ORDER_VERSION) merged else trendingUnderRecent(merged)
            return HomeConfig(
                order = placed,
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
                // (v2.6.0) Absent before the featured titles existed: on, as for everyone new.
                featuredHero = obj.optBoolean("featured", true),
            )
        }

        /** The order's format: 2 since DPTV v2.9.1 (each row has its own place). */
        const val ORDER_VERSION = 2

        internal fun trendingUnderRecent(order: List<HomeRow>): List<HomeRow> {
            val trending = listOf(HomeRow.TRENDING_MOVIES, HomeRow.TRENDING_SHOWS)
            if (HomeRow.RECENT_CHANNELS !in order) return order
            // Rows a config from before v2.4.0 never listed go there too.
            val rest = order.filter { it !in trending }.toMutableList()
            rest.addAll(rest.indexOf(HomeRow.RECENT_CHANNELS) + 1, trending)
            return rest
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
