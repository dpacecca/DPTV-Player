package tv.own.owntv.core.theme

/**
 * Material You-style accent presets. OwnTV can't rely on true wallpaper-based dynamic color (a phone
 * feature that isn't dependable on Android TV), so instead the user picks an accent and the M3 color
 * scheme is seeded from it.
 *
 * DPTV-Player ships a single fixed signature green: [DPTV]. The older entries (TEAL/BLUE/VIOLET/
 * GREEN/AMBER) are kept only so stored choices from before the rebrand still parse — every entry
 * resolves to the same DPTV-Player roles (see `AccentColor.roles`). New installs default to [DPTV].
 *
 * This is only the user's *choice* — the name is what gets persisted. The tonal `primary` /
 * `primaryContainer` roles each preset seeds, and its display label, are rendering concerns and live
 * with the theme in the app module.
 */
enum class AccentColor { TEAL, BLUE, VIOLET, GREEN, AMBER, DPTV }
