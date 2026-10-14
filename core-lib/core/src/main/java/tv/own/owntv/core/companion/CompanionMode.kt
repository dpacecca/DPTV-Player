package tv.own.owntv.core.companion

/**
 * What the companion HTTP server is currently serving:
 *  - [ADD_SOURCE] — the mobile add-source form (Xtream / M3U / Stalker), the original Remote flow;
 *  - [BACKUP_RESTORE] — an upload page the remote device uses to send an OwnTV backup JSON to the TV;
 *  - [BACKUP_DOWNLOAD] — a download page the remote device uses to fetch a backup the TV just exported;
 *  - [IMAGE_UPLOAD] — an upload page the remote device uses to send a background image to the TV;
 *  - [TMDB_KEY] — a one-field page the remote device uses to send a personal TMDB API key to the TV.
 *  - [LOCAL_SYNC] — the only mode with no web page behind it: the other OwnTV app on the same Wi-Fi
 *    talks to it directly. It accepts a backup upload AND serves this device's own export, because a
 *    merge sends and receives in one session, and it answers `/sync/hello` and `/sync/pair` so the
 *    two devices can identify each other and remember the pairing.
 *  - [SELECT_ITEMS] — a generic pick list: the host passes labelled items, the remote browser ticks
 *    the ones to keep and posts the chosen ids back. Used for the Sports hub's league/team picker;
 *    deliberately sport-agnostic so any screen can reuse it.
 *
 * "Remote device" is any browser on the same Wi-Fi — a phone, a tablet or a desktop with the URL
 * typed in — which is why nothing here is named after a phone.
 *
 * One server, one PIN gate; the mode only changes which page is served and which endpoint is accepted.
 */
enum class CompanionMode { ADD_SOURCE, BACKUP_RESTORE, BACKUP_DOWNLOAD, IMAGE_UPLOAD, TMDB_KEY, TMDB_CONFIG, OPEN_SUBTITLES_CONFIG, LOCAL_SYNC, SELECT_ITEMS }

/**
 * One row in a [CompanionMode.SELECT_ITEMS] pick list: a stable [id], a display [label], an optional
 * [group] heading, whether it starts ticked, and which row it belongs to.
 *
 * [parentId] turns the flat list into a two-level picker: a row **with** children is shown as a
 * tappable entry whose children open on their own level, and the `group` of those children becomes
 * the tab label inside it (e.g. the league row "Bundesliga" with its "Teams" and "Players" tabs).
 * `null` means a top-level row. The contract for a submit is unchanged: every ticked row still
 * arrives as `item=<id>` on `POST /select`.
 */
data class CompanionSelectItem(
    val id: String,
    val label: String,
    val group: String = "",
    val selected: Boolean = false,
    val parentId: String? = null,
)

/**
 * A service setup handed over from the remote browser.
 *
 * [username]/[password] are only ever populated in [CompanionMode.OPEN_SUBTITLES_CONFIG] — TMDB has
 * no account to sign in to, and the server drops both fields in any other mode.
 */
data class CompanionServiceConfig(
    val apiKey: String,
    val serverUrl: String,
    val username: String = "",
    val password: String = "",
)
