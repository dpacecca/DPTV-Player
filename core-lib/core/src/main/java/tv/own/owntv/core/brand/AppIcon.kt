package tv.own.owntv.core.brand

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import tv.own.owntv.core.R

/**
 * DPTV-Player's launcher mark: the green pitch tile with the play triangle. There is exactly one
 * launcher activity in the host app (`MainActivity`), one TV banner and one launch theme — the
 * icon colour is part of the brand and no longer user-configurable.
 *
 * Older installs may still have an `app_icon` value stored from the multi-colour era
 * (PETROL/SUNFLOWER/…/PIXEL); [fromStored] maps every such value to [DPTV] so the launcher
 * and the in-app logo converge on the DPTV-Player mark without a data migration.
 */
enum class AppIcon(
    val activitySuffix: String,
    @StringRes val label: Int,
    /** Flat in-app mark, cropped to the card: the sidebar, lockups, About. */
    @DrawableRes val mark: Int,
    /** The simpler drawing for 32 dp and below. */
    @DrawableRes val markSmall: Int,
    /** 320×180 Android TV banner, also the logo of the TV home-screen channel. */
    @DrawableRes val banner: Int,
    /** ARGB of the wordmark's "Sport" on a dark surface (the brand guide's pitch green). */
    val accent: Long,
    /** ARGB of the wordmark's "Sport" on a light surface (the brand guide's deep green). */
    val accentOnLight: Long,
) {
    DPTV("", R.string.app_icon_dptv, R.drawable.dptv_mark, R.drawable.dptv_mark_small, R.drawable.dptv_banner, 0xFF4FEE94, 0xFF0E6B41);

    /**
     * Whether the drawing at this size is the pitch tile, whose play triangle sits exactly where
     * the in-app lockup draws it. Always true: the DPTV-Player mark is one tile at every size.
     */
    fun isCard(small: Boolean): Boolean = true

    companion object {
        val DEFAULT = DPTV

        fun fromStored(value: String?): AppIcon = DPTV
    }
}
