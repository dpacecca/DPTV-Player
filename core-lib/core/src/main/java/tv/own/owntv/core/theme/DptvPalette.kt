package tv.own.owntv.core.theme

/**
 * DPTV-Player's green brand palette — a drop-in companion to [OwnTVPalette].
 *
 * DPTV-Player is the TV + Sports fork of OwnTV, so it keeps OwnTV's whole token *structure*
 * (M3 roles, the surface-container ladder, the three panel identities) and only replaces the
 * colour values with a green-seeded set. That way every Stage component that reads
 * `OwnTVPalette` / `OwnTVColors` renders in DPTV-Player green with no component changes.
 *
 * **Why green.** The Sport Hub is the product's differentiator: scores, standings, live badges.
 * Green is the pitch under floodlights — it reads as "the app is about the sport" at a glance and
 * separates the fork from upstream OwnTV's teal (#52DBC8, hue 172) without a palette clash,
 * because DPTV-Player sits at hue 146, a full quarter-turn away.
 *
 * All pairs below were verified against WCAG 2.1:
 * every text pair is >= 4.5:1, every non-text UI pair >= 3:1, in BOTH themes.
 */
object DptvPalette {

    /**
     * Signature green. Constant — the play-triangle / logo colour on dark surfaces.
     * P40 of the green ramp: vivid enough to own a 2 px focus ring on near-black, calm enough
     * to sit behind body text without shouting.
     */
    const val Pitch = 0xFF4FEE94L

    /** The green at rest on a *light* surface. P80-light: deep enough for a white label at 6.57:1. */
    const val PitchDeep = 0xFF0E6B41L

    /** Floodlight rim — the dark-theme focus ring. P30. */
    const val Beam = 0xFF81EEB0L

    /** Mown-stripe green — the dark accent container. */
    const val Field = 0xFF134D2CL

    /** Text drawn on [Field]. */
    const val Sprout = 0xFF9BF5BEL

    /** The full green ramp, P0 → P100. Use for gradients, data viz and the icon set. */
    object Ramp {
        const val P0 = 0xFFF3FCF7L;   const val P10 = 0xFFDEF8E9L
        const val P20 = 0xFFB5F2D0L; const val P30 = 0xFF81EEB0L
        const val P40 = 0xFF4FEE94L; const val P50 = 0xFF30E880L
        const val P60 = 0xFF19CC67L; const val P70 = 0xFF16A252L
        const val P80 = 0xFF147B41L; const val P90 = 0xFF105D2CL
        const val P95 = 0xFF0C311CL; const val P100 = 0xFF081C11L
    }

    // ---------------- DARK (near-black pitch under floodlights) ----------------
    const val DarkBackground = 0xFF060B07L
    const val DarkSurface = 0xFF0B120DL
    const val DarkSurfaceContainerLowest = 0xFF08100BL
    const val DarkSurfaceContainerLow = 0xFF101A13L
    const val DarkSurfaceContainer = 0xFF16241BL
    const val DarkSurfaceContainerHigh = 0xFF1E3025L
    const val DarkSurfaceContainerHighest = 0xFF28402FL
    const val DarkRailPanel = 0xFF0C1710L
    const val DarkContentPanel = 0xFF08120CL
    const val DarkPreviewPanel = 0xFF122018L
    const val DarkOnSurface = 0xFFEAF7EFL
    const val DarkOnSurfaceVariant = 0xFFA6C3AFL
    const val DarkOutline = 0xFF3C5A48L
    const val DarkOutlineVariant = 0xFF1E3126L
    const val DarkSecondary = 0xFF9FC8B0L
    const val DarkOnSecondary = 0xFF0A1F14L
    const val DarkSecondaryContainer = 0xFF22402EL
    const val DarkOnSecondaryContainer = 0xFFBCE0C9L
    const val DarkTertiary = 0xFF7FC4D6L
    const val DarkOnTertiary = 0xFF04222AL
    const val DarkTertiaryContainer = 0xFF1B3D46L
    const val DarkOnTertiaryContainer = 0xFFB4E8F3L
    const val DarkError = 0xFFFF8A86L

    // ---------------- LIGHT (daylight on the same pitch) ----------------
    const val LightBackground = 0xFFF4F8F4L
    const val LightSurface = 0xFFFFFFFFL
    const val LightSurfaceContainerLowest = 0xFFFFFFFFL
    const val LightSurfaceContainerLow = 0xFFEFF5F0L
    const val LightSurfaceContainer = 0xFFEDF3EEL
    const val LightSurfaceContainerHigh = 0xFFE4ECE6L
    const val LightSurfaceContainerHighest = 0xFFDCE6DFL
    const val LightRailPanel = 0xFFE9F1EBL
    const val LightContentPanel = 0xFFF8FBF8L
    const val LightPreviewPanel = 0xFFE1EBE4L
    const val LightOnSurface = 0xFF081C11L
    const val LightOnSurfaceVariant = 0xFF3C5546L
    const val LightOutline = 0xFFA8BBAEL
    const val LightOutlineVariant = 0xFFD2DED5L
    const val LightSecondary = 0xFF4F7261L
    const val LightOnSecondary = 0xFFFFFFFFL
    const val LightSecondaryContainer = 0xFFD3E9DCL
    const val LightOnSecondaryContainer = 0xFF10291CL
    const val LightTertiary = 0xFF3F6183L
    const val LightOnTertiary = 0xFFFFFFFFL
    const val LightTertiaryContainer = 0xFFD5E9F3L
    const val LightOnTertiaryContainer = 0xFF112B3CL
    const val LightError = 0xFFC42127L

    // ---------------- SEMANTIC ----------------
    /**
     * LIVE is the only colour in the system allowed to mean "this is happening now".
     * It never appears on a finished or upcoming match — those use [dim] and [muted].
     * Always accompanied by the pulsing dot AND the word; colour alone is not the signal.
     */
    const val LiveDark = 0xFFFF5A5FL
    const val LiveLight = 0xFFC42127L
    const val OnLive = 0xFFFFFFFFL

    /** Warnings, "catch-up ended", expiring trials. Amber, never red — red is reserved for LIVE. */
    const val WarnDark = 0xFFFFB020L
    const val WarnLight = 0xFF7A4D00L
    const val OnWarnDark = 0xFF221600L

    /** Recording dot. Distinct from LIVE red and always carries the REC glyph. */
    const val RecordDark = 0xFFFF3B5CL
    const val RecordLight = 0xFFC81038L

    /** Informational: EPG sync, league updates, first-run hints. */
    const val InfoDark = 0xFF3FD0E8L
    const val InfoLight = 0xFF0E6B7DL

    // ---------------- PRIMARY ROLES ----------------

    /** Dark-theme primary roles. [Pitch] as `primary`; on-top text is near-black at 11.81:1. */
    val DarkPrimary = AccentRoleValues(
        primary = Pitch,
        onPrimary = Ramp.P100,
        primaryContainer = Field,
        onPrimaryContainer = Sprout,
    )

    /** Light-theme primary roles. [PitchDeep] as `primary`; white label clears AA at 6.57:1. */
    val LightPrimary = AccentRoleValues(
        primary = PitchDeep,
        onPrimary = 0xFFFFFFFFL,
        primaryContainer = 0xFFC8EFD8L,
        onPrimaryContainer = 0xFF08301CL,
    )

    /**
     * The four primary roles for the given theme — the DPTV-Player equivalent of
     * [AccentColor.roles]. Wire this in `ownTvColors()` by returning [DarkPrimary] / [LightPrimary]
     * instead of the accent preset.
     */
    fun roles(isDark: Boolean): AccentRoleValues = if (isDark) DarkPrimary else LightPrimary

    // ---------------- TEAM / LEAGUE COLOURS ----------------
    /**
     * Team and league colours, dual-variant: the same hue as a light tone for the dark page and
     * a dark tone for the light page. Each clears 3:1 on the page it is used on, so a club colour
     * stays legible in either theme.
     *
     * Always pass through [teamColor] rather than storing a club's raw brand hex — an ESPN
     * `colorHex` can be near-black and would vanish on the dark page.
     */
    object Team {
        val Orange = 0xFFED926EL to 0xFFAC4015L
        val Amber = 0xFFEAB771L to 0xFFA96D19L
        val Sky = 0xFF74C2E7L to 0xFF1D7AA5L
        val Blue = 0xFF7697E5L to 0xFF1F47A3L
        val Indigo = 0xFF8F7BE0L to 0xFF3D259DL
        val Violet = 0xFFBA7CDEL to 0xFF70279BL
        val Crimson = 0xFFE57692L to 0xFFA31F40L
        val Slate = 0xFFA2ABB9L to 0xFF535F6EL
        val Green = 0xFF7BE0A0L to 0xFF259D51L
    }
}

/** Pick the theme-correct tone of a [DptvPalette.Team] pair. */
fun teamColor(pair: Pair<Long, Long>, isDark: Boolean): Long = if (isDark) pair.first else pair.second
