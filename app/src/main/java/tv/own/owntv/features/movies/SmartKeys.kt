package tv.own.owntv.features.movies

import tv.own.owntv.core.live.LiveKey

/**
 * (DPTV v2.9.31) DPTV-Server's movie categories in the Movies menu. They ride [LiveKey.Custom] with ids
 * no custom category can have ("dptv:smart:…"), so the menu, the selection and "remember last category"
 * carry them as they are; Movies draws them itself instead of asking the database for their titles.
 */
internal const val SmartKeyPrefix = "dptv:smart:"

/** Movies' landing: every movie category as a carousel. */
internal val DiscoverKey: LiveKey = LiveKey.Custom(SmartKeyPrefix + "discover")

internal fun smartKey(categoryId: String): LiveKey = LiveKey.Custom(SmartKeyPrefix + categoryId)

/** The movie category (or "discover") behind [this] key; null for any other key. */
internal fun LiveKey.smartId(): String? = (this as? LiveKey.Custom)?.id?.takeIf { it.startsWith(SmartKeyPrefix) }?.removePrefix(SmartKeyPrefix)

internal fun LiveKey.isSmart(): Boolean = smartId() != null

/** "Movies by Genre" and "Movies by Decade": one carousel per genre or decade. */
internal const val GenreGroup = "group:genre"
internal const val DecadeGroup = "group:decade"
