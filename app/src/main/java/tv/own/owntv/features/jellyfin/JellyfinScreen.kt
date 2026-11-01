package tv.own.owntv.features.jellyfin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.jellyfin.JellyfinAccount
import tv.own.owntv.core.jellyfin.JellyfinClient
import tv.own.owntv.core.jellyfin.JellyfinItem
import tv.own.owntv.core.jellyfin.JellyfinKind
import tv.own.owntv.features.epg.GuideMenuHost
import tv.own.owntv.features.recordings.dvrClock
import tv.own.owntv.features.shell.components.VodPosterArt
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.stage.stageFocusLook
import tv.own.owntv.ui.stage.stageStillBack
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The Jellyfin section (DPTV v2.9.0): Movies and Shows from the user's own Jellyfin server — continue
 * watching, next up, the latest added and the whole library A–Z — and a title's page with its seasons
 * and episodes. Plays in DPTV's own player; where you stopped goes back to Jellyfin.
 */
@Composable
fun JellyfinScreen(
    onFullscreen: () -> Unit,
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    contentStart: Dp = 150.mpx,
    modifier: Modifier = Modifier,
) {
    val vm: JellyfinViewModel = koinViewModel()
    val client: JellyfinClient = koinInject()
    val account by vm.account.collectAsStateWithLifecycle()
    val shelves by vm.shelves.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(JellyfinViewModel.Tab.MOVIES.name) }
    val tab = JellyfinViewModel.Tab.valueOf(tabName)

    // Opening the section asks the server afresh: what was watched on another device shows here.
    LaunchedEffect(account?.userId) { if (account != null) vm.refresh() }

    val a = account
    if (a == null) {
        Box(modifier.fillMaxSize().padding(start = contentStart, end = 64.mpx), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.sport_jf_signed_out), style = stageText(22, 600), color = StageColors.Muted,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 900.mpx),
            )
        }
        return
    }

    val cardFocus = remember { HashMap<String, FocusRequester>() }
    fun focusOf(key: String) = cardFocus.getOrPut(key) { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    var resumeFor by remember { mutableStateOf<JellyfinItem?>(null) }
    var menuFor by remember { mutableStateOf<JellyfinItem?>(null) }

    val episodeSeparator = stringResource(R.string.content_epg_bits_separator)
    val episodeFormat = stringResource(R.string.content_season_episode)
    val start: (JellyfinItem, Long) -> Unit = { item, at ->
        val title = if (item.kind == JellyfinKind.EPISODE) episodeTitle(item, episodeFormat, episodeSeparator) else item.name
        if (vm.play(item, at, title)) onFullscreen()
    }
    // A film or episode left part-way asks Resume / Start over; a show opens its page.
    val choose: (JellyfinItem) -> Unit = { item ->
        when {
            item.kind == JellyfinKind.SERIES || item.kind == JellyfinKind.SEASON -> vm.open(item)
            item.positionMs > 0 -> resumeFor = item
            else -> start(item, 0)
        }
    }
    val openPage: (JellyfinItem) -> Unit = { item ->
        if (item.kind == JellyfinKind.EPISODE || item.kind == JellyfinKind.SERIES || item.kind == JellyfinKind.SEASON) vm.open(item) else menuFor = item
    }

    val entry: () -> Boolean = {
        val key = lastFocused
        (key != null && runCatching { focusOf(key).requestFocus() }.getOrDefault(false)) ||
            runCatching { tabFocus.requestFocus() }.getOrDefault(false)
    }
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke(entry)
        onDispose { onEntryHook?.invoke(null) }
    }
    LaunchedEffect(restoreFocus) {
        if (!restoreFocus) return@LaunchedEffect
        withFrameNanos { }
        entry()
        onRestored()
    }

    val d = detail
    if (d != null) {
        BackHandler { vm.close() }
        JellyfinDetailPage(
            detail = d, account = a, client = client,
            onPlay = choose,
            onResume = { resumeFor = it },
            onPickSeason = vm::pickSeason,
            onSetPlayed = vm::setPlayed,
            onFocused = { lastFocused = it },
            focusOf = ::focusOf,
            contentStart = contentStart,
            modifier = modifier,
        )
    } else {
        Column(modifier.fillMaxSize().padding(start = contentStart, top = 44.mpx)) {
            Row(Modifier.padding(end = 640.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.mpx)) {
                Text(stringResource(R.string.common_nav_jellyfin), style = stageText(46, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StageTool(
                    text = stringResource(R.string.sport_jf_movies), icon = OwnTVIcon.MOVIES,
                    boxed = tab == JellyfinViewModel.Tab.MOVIES,
                    onClick = { tabName = JellyfinViewModel.Tab.MOVIES.name },
                    modifier = Modifier.focusRequester(tabFocus),
                )
                StageTool(
                    text = stringResource(R.string.sport_jf_shows), icon = OwnTVIcon.SERIES,
                    boxed = tab == JellyfinViewModel.Tab.SHOWS,
                    onClick = { tabName = JellyfinViewModel.Tab.SHOWS.name },
                )
                StageTool(text = null, icon = OwnTVIcon.REFRESH, onClick = { vm.refresh() })
            }
            val rows = shelfRows(tab, shelves)
            when {
                rows.isEmpty() && (shelves.loading || shelves.failed) || rows.all { it.items.isEmpty() } -> Box(
                    Modifier.fillMaxSize().padding(end = 64.mpx, bottom = 120.mpx), contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(
                            when {
                                shelves.failed -> R.string.sport_jf_failed
                                shelves.loading -> R.string.sport_jf_loading
                                else -> R.string.sport_jf_empty
                            },
                        ),
                        style = stageText(22, 600), color = StageColors.Muted, textAlign = TextAlign.Center,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 900.mpx),
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(top = 20.mpx),
                    contentPadding = PaddingValues(bottom = 60.mpx, end = 64.mpx),
                    verticalArrangement = Arrangement.spacedBy(22.mpx),
                ) {
                    rows.filter { it.items.isNotEmpty() }.forEach { row ->
                        item(key = "h-" + row.key) {
                            Text(stringResource(row.title), style = stageText(26, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (row.grid) {
                            val chunks = row.items.chunked(GRID_COLUMNS)
                            itemsIndexed(chunks, key = { i, _ -> row.key + "-" + i }) { i, chunk ->
                                // Near the end of what is loaded: fetch the next page.
                                if (i >= chunks.size - 2) LaunchedEffect(chunks.size) { vm.loadMore(tab) }
                                Row(horizontalArrangement = Arrangement.spacedBy(22.mpx)) {
                                    chunk.forEach { item ->
                                        val key = row.key + ":" + item.id
                                        PosterCard(item, client.posterUrl(a, item), focusOf(key), { lastFocused = key }, { choose(item) }, { openPage(item) })
                                    }
                                }
                            }
                        } else {
                            item(key = "r-" + row.key) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(22.mpx), contentPadding = PaddingValues(end = 40.mpx)) {
                                    items(row.items, key = { it.id }) { item ->
                                        val key = row.key + ":" + item.id
                                        if (item.kind == JellyfinKind.EPISODE) {
                                            EpisodeCard(item, client.backdropUrl(a, item, 360) ?: client.posterUrl(a, item), focusOf(key), { lastFocused = key }, { choose(item) }, { openPage(item) })
                                        } else {
                                            PosterCard(item, client.posterUrl(a, item), focusOf(key), { lastFocused = key }, { choose(item) }, { openPage(item) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    resumeFor?.let { item ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        GuideMenuHost(x = 1150f, top = 120.mpx, width = 560.mpx, onDismiss = { resumeFor = null }) {
            StageMenuHeader(title = titleOf(item), subtitle = item.seriesName)
            StageMenuItem(
                text = stringResource(R.string.sport_dvr_resume, dvrClock(item.positionMs)), icon = OwnTVIcon.PLAY,
                onClick = { resumeFor = null; start(item, item.positionMs) },
                modifier = Modifier.focusRequester(focus),
            )
            StageMenuItem(
                text = stringResource(R.string.sport_dvr_start_over), icon = OwnTVIcon.REWIND,
                onClick = { resumeFor = null; start(item, 0) },
            )
        }
    }

    menuFor?.let { item ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        GuideMenuHost(x = 1150f, top = 120.mpx, width = 560.mpx, onDismiss = { menuFor = null }) {
            StageMenuHeader(title = titleOf(item), subtitle = metaLine(item))
            StageMenuItem(
                text = stringResource(R.string.content_action_play), icon = OwnTVIcon.PLAY,
                onClick = { menuFor = null; choose(item) },
                modifier = Modifier.focusRequester(focus),
            )
            StageMenuItem(
                text = stringResource(if (item.played) R.string.sport_jf_mark_unwatched else R.string.sport_jf_mark_watched),
                icon = OwnTVIcon.CHECK,
                onClick = { menuFor = null; vm.setPlayed(item, !item.played) },
            )
        }
    }
}

private class ShelfRow(val key: String, val title: Int, val items: List<JellyfinItem>, val grid: Boolean = false)

private fun shelfRows(tab: JellyfinViewModel.Tab, s: JellyfinViewModel.Shelves): List<ShelfRow> = when (tab) {
    JellyfinViewModel.Tab.MOVIES -> listOf(
        ShelfRow("cm", R.string.sport_jf_continue, s.continueMovies),
        ShelfRow("lm", R.string.sport_jf_latest_movies, s.latestMovies),
        ShelfRow("am", R.string.sport_jf_all_movies, s.allMovies, grid = true),
    )
    JellyfinViewModel.Tab.SHOWS -> listOf(
        ShelfRow("ce", R.string.sport_jf_continue, s.continueEpisodes),
        ShelfRow("nu", R.string.sport_jf_next_up, s.nextUp),
        ShelfRow("ls", R.string.sport_jf_latest_shows, s.latestShows),
        ShelfRow("as", R.string.sport_jf_all_shows, s.allShows, grid = true),
    )
}

/** A film or episode's own title; an episode as "Series · S1 · E4 · Name". */
@Composable
private fun titleOf(item: JellyfinItem): String =
    if (item.kind == JellyfinKind.EPISODE) {
        episodeTitle(item, stringResource(R.string.content_season_episode), stringResource(R.string.content_epg_bits_separator))
    } else item.name

/** [format] is content_season_episode ("S%1$d · E%2$d"), [separator] the " · " between the parts. */
internal fun episodeTitle(item: JellyfinItem, format: String, separator: String): String = listOfNotNull(
    item.seriesName,
    if (item.seasonNumber != null && item.episodeNumber != null) String.format(java.util.Locale.getDefault(), format, item.seasonNumber, item.episodeNumber) else null,
    item.name.ifBlank { null },
).joinToString(separator)

/** Year · running time · rating, whatever Jellyfin knows. */
@Composable
private fun metaLine(item: JellyfinItem): String = listOfNotNull(
    item.year?.toString(),
    item.runtimeMs?.takeIf { it > 0 }?.let { stringResource(R.string.player_duration_minutes, (it / 60_000L).toInt().coerceAtLeast(1)) },
    item.rating?.takeIf { it > 0 }?.let { r ->
        java.text.NumberFormat.getNumberInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
            .apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }.format(r)
    },
).joinToString(stringResource(R.string.content_epg_bits_separator))

private const val GRID_COLUMNS = 7

/** OK plays; holding OK (or ☰) opens the title's page or menu. Acting on release is what lets a hold differ. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.jellyfinCard(focus: FocusRequester, onFocused: () -> Unit, onPlay: () -> Unit, onHold: () -> Unit, focused: (Boolean) -> Unit): Modifier {
    var okDown by remember { mutableStateOf(false) }
    return this
        .focusRequester(focus)
        .onFocusChanged { focused(it.isFocused); if (it.isFocused) onFocused() else okDown = false }
        .onPreviewKeyEvent { e ->
            val down = e.type == KeyEventType.KeyDown
            when (e.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    val repeat = e.nativeKeyEvent.repeatCount
                    if (down && repeat == 0) okDown = true
                    else if (down && okDown) { okDown = false; onHold() }
                    else if (!down && okDown) { okDown = false; onPlay() }
                    true
                }
                Key.Menu -> { if (down) onHold(); true }
                else -> false
            }
        }
        .combinedClickable(interactionSource = null, indication = null, onClick = onPlay, onLongClick = onHold)
        .focusable()
}

@Composable
private fun PosterCard(item: JellyfinItem, url: String?, focus: FocusRequester, onFocused: () -> Unit, onPlay: () -> Unit, onHold: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(200.mpx).jellyfinCard(focus, onFocused, onPlay, onHold) { focused = it },
        verticalArrangement = Arrangement.spacedBy(8.mpx),
    ) {
        Box(
            Modifier.size(200.mpx, 300.mpx).clip(RoundedCornerShape(14.mpx))
                .then(if (focused) Modifier.stageFocusLook(StageFocus.FX, 14.mpx) else Modifier),
        ) {
            VodPosterArt(url, if (item.kind == JellyfinKind.MOVIE) OwnTVIcon.MOVIES else OwnTVIcon.SERIES)
            Marks(item)
        }
        Text(
            item.name, style = stageText(19, 700), color = if (focused) stageAccent.accent else StageColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EpisodeCard(item: JellyfinItem, url: String?, focus: FocusRequester, onFocused: () -> Unit, onPlay: () -> Unit, onHold: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(360.mpx).jellyfinCard(focus, onFocused, onPlay, onHold) { focused = it },
        verticalArrangement = Arrangement.spacedBy(6.mpx),
    ) {
        Box(
            Modifier.size(360.mpx, 203.mpx).clip(RoundedCornerShape(14.mpx)).background(stageStillBack)
                .then(if (focused) Modifier.stageFocusLook(StageFocus.FX, 14.mpx) else Modifier),
        ) {
            VodPosterArt(url, OwnTVIcon.SERIES)
            Marks(item)
        }
        Text(
            item.seriesName ?: item.name, style = stageText(19, 700), color = if (focused) stageAccent.accent else StageColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(episodeLine(item), style = stageText(16, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "S1 · E4 · Name". */
@Composable
private fun episodeLine(item: JellyfinItem): String = listOfNotNull(
    if (item.seasonNumber != null && item.episodeNumber != null) stringResource(R.string.content_season_episode, item.seasonNumber!!, item.episodeNumber!!) else null,
    item.name.ifBlank { null },
).joinToString(stringResource(R.string.content_epg_bits_separator))

/** How far it was watched along the bottom, and a ✓ once watched. */
@Composable
private fun BoxScope.Marks(item: JellyfinItem) {
    val runtime = item.runtimeMs ?: 0L
    if (item.positionMs > 0 && runtime > 0) {
        val watched = (item.positionMs.toFloat() / runtime).coerceIn(0.02f, 1f)
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.mpx).background(StageColors.Dim.copy(alpha = 0.6f))) {
            Box(Modifier.fillMaxWidth(watched).fillMaxHeight().background(stageAccent.accent))
        }
    }
    if (item.played) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(10.mpx).size(34.mpx).clip(RoundedCornerShape(17.mpx)).background(stageAccent.accent),
            contentAlignment = Alignment.Center,
        ) { tv.own.owntv.ui.components.OwnTVIcon(OwnTVIcon.CHECK, StageColors.Text, Modifier.size(22.mpx)) }
    }
}

/** A film's or a show's page: the art, what it is, Play/Resume, watched, and a show's seasons and episodes. */
@Composable
private fun JellyfinDetailPage(
    detail: JellyfinViewModel.Detail,
    account: JellyfinAccount,
    client: JellyfinClient,
    onPlay: (JellyfinItem) -> Unit,
    onResume: (JellyfinItem) -> Unit,
    onPickSeason: (String) -> Unit,
    onSetPlayed: (JellyfinItem, Boolean) -> Unit,
    onFocused: (String) -> Unit,
    focusOf: (String) -> FocusRequester,
    contentStart: Dp,
    modifier: Modifier,
) {
    val item = detail.item
    val series = item.kind == JellyfinKind.SERIES
    val playFocus = remember(item.id) { FocusRequester() }
    LaunchedEffect(item.id) { withFrameNanos { }; runCatching { playFocus.requestFocus() } }
    // A show's Play: the first episode not yet watched in the season shown, part-watched first.
    val next = if (series) detail.episodes.firstOrNull { it.positionMs > 0 && !it.played } ?: detail.episodes.firstOrNull { !it.played } else item

    LazyColumn(
        modifier.fillMaxSize().padding(start = contentStart, top = 44.mpx),
        contentPadding = PaddingValues(bottom = 60.mpx, end = 64.mpx),
        verticalArrangement = Arrangement.spacedBy(18.mpx),
    ) {
        item(key = "head") {
            Row(horizontalArrangement = Arrangement.spacedBy(36.mpx)) {
                Box(Modifier.size(260.mpx, 390.mpx).clip(RoundedCornerShape(16.mpx))) {
                    VodPosterArt(client.posterUrl(account, item), if (series) OwnTVIcon.SERIES else OwnTVIcon.MOVIES)
                }
                Column(Modifier.weight(1f).padding(end = 520.mpx), verticalArrangement = Arrangement.spacedBy(10.mpx)) {
                    Text(
                        item.name, style = stageText(44, 800).copy(lineHeight = 52.mpxSp), color = StageColors.Text,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(metaLine(item), style = stageText(20, 600), color = stageAccent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.genres.isNotEmpty()) {
                        Text(item.genres.joinToString(stringResource(R.string.content_epg_bits_separator)), style = stageText(18, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    item.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = stageText(21, 400).copy(lineHeight = 30.mpxSp), color = StageColors.Body, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.padding(top = 10.mpx), horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
                        val target = next
                        StageButton(
                            text = when {
                                target == null -> stringResource(R.string.content_action_play)
                                target.positionMs > 0 -> stringResource(R.string.sport_dvr_resume, dvrClock(target.positionMs))
                                series && target.seasonNumber != null && target.episodeNumber != null ->
                                    stringResource(R.string.sport_jf_play_episode, stringResource(R.string.content_season_episode, target.seasonNumber!!, target.episodeNumber!!))
                                else -> stringResource(R.string.content_action_play)
                            },
                            icon = OwnTVIcon.PLAY, iconFilled = true, tinted = true,
                            onClick = { target?.let { if (it.positionMs > 0) onResume(it) else onPlay(it) } },
                            modifier = Modifier.focusRequester(playFocus),
                        )
                        StageButton(
                            text = stringResource(if (item.played) R.string.sport_jf_mark_unwatched else R.string.sport_jf_mark_watched),
                            icon = OwnTVIcon.CHECK,
                            onClick = { onSetPlayed(item, !item.played) },
                        )
                        // (v2.9.1) The Trakt watchlist, once signed in to Trakt and Jellyfin knows the title's TMDB id.
                        val trakt: tv.own.owntv.features.trakt.TraktViewModel = koinViewModel()
                        val traktOn by trakt.signedIn.collectAsStateWithLifecycle()
                        val list by trakt.watchlist.collectAsStateWithLifecycle()
                        if (traktOn && item.tmdbId != null) {
                            val listed = remember(list, item.id) { trakt.onWatchlist(item) }
                            StageButton(
                                text = stringResource(if (listed) R.string.sport_trakt_watchlist_remove else R.string.sport_trakt_watchlist_add),
                                icon = OwnTVIcon.LIST,
                                onClick = { trakt.toggleWatchlist(item) },
                            )
                        }
                    }
                }
            }
        }
        if (series) {
            item(key = "seasons") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
                    items(detail.seasons, key = { it.id }) { s ->
                        StageTool(
                            text = s.name, boxed = s.id == detail.seasonId,
                            trailingIcon = if (s.played) OwnTVIcon.CHECK else null,
                            onClick = { onPickSeason(s.id) },
                        )
                    }
                }
            }
            if (detail.loading && detail.episodes.isEmpty()) {
                item(key = "loading") {
                    Text(stringResource(R.string.sport_jf_loading), style = stageText(20, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            items(detail.episodes, key = { "e-" + it.id }) { e ->
                val key = "ep:" + e.id
                EpisodeRow(
                    e, client.posterUrl(account, e, 300), focusOf(key), { onFocused(key) },
                    onPlay = { if (e.positionMs > 0) onResume(e) else onPlay(e) },
                    onHold = { onSetPlayed(e, !e.played) },
                )
            }
        }
    }
}

/** One episode: the still, "S1 · E4 · Name", its length and synopsis. Holding OK marks it (un)watched. */
@Composable
private fun EpisodeRow(item: JellyfinItem, url: String?, focus: FocusRequester, onFocused: () -> Unit, onPlay: () -> Unit, onHold: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 150.mpx)
            .jellyfinCard(focus, onFocused, onPlay, onHold) { focused = it }
            .then(if (focused) Modifier.stageFocusLook(StageFocus.FX, 22.mpx) else Modifier)
            .padding(horizontal = 18.mpx, vertical = 12.mpx),
        horizontalArrangement = Arrangement.spacedBy(28.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(240.mpx, 135.mpx).clip(RoundedCornerShape(12.mpx)).background(stageStillBack)) {
            VodPosterArt(url, OwnTVIcon.SERIES)
            Marks(item)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
            Text(
                episodeLine(item), style = stageText(26, 800), color = if (focused) StageColors.FxText else StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(metaLine(item), style = stageText(18, 600), color = if (focused) stageAccent.accent else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = stageText(19, 400).copy(lineHeight = 27.mpxSp), color = StageColors.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
