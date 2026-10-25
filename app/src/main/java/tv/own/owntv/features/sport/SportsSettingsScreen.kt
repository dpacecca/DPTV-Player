package tv.own.owntv.features.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.companion.CompanionSelectItem
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsHeading
import tv.own.owntv.features.settings.StageSettingsNote
import tv.own.owntv.features.settings.settingHelp
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.StageCompanionStatus
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupIcon
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** The pages of Settings › Sport: the main page and the ones its rows open. */
private enum class SportSettingsPage { MAIN, LEAGUES, TEAMS, LANGUAGES, PLAYLISTS, ABOUT }

/**
 * Settings › Sport (v2.6.0), drawn as every Settings page is, after OwnTV's Settings › Sports: Sports
 * and leagues (two panes, a league search, Hold OK to put sports and leagues in order), My teams
 * (search the chosen leagues' teams), Live sports on Home and Manage on phone; then DPTV's channel
 * options — the channel languages and the ones never shown, the playlists, strict filtering — and the
 * data options: live refresh, auto rebuild, Rebuild now. Every value is [SportsPrefs]'s; backups
 * carry it.
 */
@Composable
fun SportsSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SportsViewModel = koinViewModel()
    var page by rememberSaveable { mutableStateOf(SportSettingsPage.MAIN) }
    val title = stringResource(R.string.sport_title)
    val settings = stringResource(R.string.common_nav_settings)
    val main = { page = SportSettingsPage.MAIN }
    // Each page opens with focus on its first row, as every Settings page does.
    val rowsFocus = remember(page) { FocusRequester() }
    LaunchedEffect(page) {
        for (attempt in 0 until 10) {
            withFrameNanos { }
            if (runCatching { rowsFocus.requestFocus() }.getOrDefault(false)) break
        }
    }
    var showPhone by remember { mutableStateOf(false) }
    when (page) {
        SportSettingsPage.MAIN -> StageFullPage(
            parents = listOf(stringResource(R.string.settings_app_group)), title = title,
            count = pluralStringResource(R.plurals.settings_setting_count, MainRows, MainRows),
            onBack = onBack, modifier = modifier, rowsFocus = rowsFocus,
        ) { MainRows(vm, open = { page = it }, onPhone = { showPhone = true }) }
        SportSettingsPage.LEAGUES -> StageFullPage(
            parents = listOf(settings, title),
            title = stringResource(R.string.sport_settings_leagues_title),
            count = "",
            onBack = main, modifier = modifier, rowsFocus = rowsFocus, settingsRoot = false,
            list = { pos -> LeaguePicker(vm, rowsFocus, pos) },
        )
        else -> StageFullPage(
            parents = listOf(settings, title),
            title = stringResource(page.titleRes),
            count = "",
            onBack = main, modifier = modifier, rowsFocus = rowsFocus, settingsRoot = false,
        ) {
            when (page) {
                SportSettingsPage.TEAMS -> TeamRows(vm)
                SportSettingsPage.LANGUAGES -> LanguageRows(vm)
                SportSettingsPage.PLAYLISTS -> PlaylistRows(vm)
                SportSettingsPage.ABOUT -> StageSettingsNote(stringResource(R.string.sport_about_summary), stringResource(R.string.sport_about_body))
                SportSettingsPage.MAIN, SportSettingsPage.LEAGUES -> Unit
            }
        }
    }
    if (showPhone) PhonePicker(vm) { showPhone = false }
}

private val SportSettingsPage.titleRes: Int get() = when (this) {
    SportSettingsPage.MAIN -> R.string.sport_title
    SportSettingsPage.LEAGUES -> R.string.sport_settings_leagues_title
    SportSettingsPage.TEAMS -> R.string.sport_my_teams
    SportSettingsPage.LANGUAGES -> R.string.sport_settings_languages
    SportSettingsPage.PLAYLISTS -> R.string.sport_playlists_heading
    SportSettingsPage.ABOUT -> R.string.sport_about
}

/** One row with its panel text, as every Settings page builds them. */
@Composable
private fun SettingRow(
    icon: OwnTVIcon,
    title: String,
    desc: String?,
    value: SettingValue?,
    modifier: Modifier = Modifier,
    onStep: ((Int) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val choices = (value as? SettingValue.Segmented)?.options.orEmpty()
    StageSettingRow(
        icon = icon, title = title, desc = desc, value = value, onClick = onClick, onStep = onStep, modifier = modifier,
        help = settingHelp(null, title, desc, value, choices = choices, pinnable = false),
    )
}

/** Rows on the main page (the "N settings" beside its title): 4 + 3 + 4. */
private const val MainRows = 11

/** The main page: What to show · Watching · Data. */
@Composable
private fun MainRows(vm: SportsViewModel, open: (SportSettingsPage) -> Unit, onPhone: () -> Unit) {
    val active by vm.activeLeagues.collectAsStateWithLifecycle()
    val sportOrder by vm.sportOrder.collectAsStateWithLifecycle()
    val followed by vm.followedTeams.collectAsStateWithLifecycle()
    val names by vm.teamNames.collectAsStateWithLifecycle()
    val homeRow by vm.hubVisible.collectAsStateWithLifecycle()
    val preferred by vm.preferredLanguages.collectAsStateWithLifecycle()
    val hidden by vm.hiddenLanguages.collectAsStateWithLifecycle()
    val chosenLists by vm.channelPlaylists.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val strict by vm.strictChannels.collectAsStateWithLifecycle()
    val liveSeconds by vm.liveRefreshSeconds.collectAsStateWithLifecycle()
    val rebuildMinutes by vm.autoRebuildMinutes.collectAsStateWithLifecycle()
    var rebuilt by remember { mutableStateOf(false) }
    val sep = stringResource(R.string.sport_detail_separator)

    val teamsLine = stringResource(R.string.sport_my_teams_description)
    StageSettingsHeading(stringResource(R.string.sport_settings_what), 4, first = true)
    val sports = SportsHub.chipSports(active, sportOrder)
    SettingRow(
        OwnTVIcon.SPORT, stringResource(R.string.sport_settings_leagues_title),
        (sports.take(4).map { sportLabel(it) } + pluralStringResource(R.plurals.sport_league_count, active.size, active.size)).joinToString(sep),
        SettingValue.Opens(stringResource(R.string.common_edit)),
    ) { open(SportSettingsPage.LEAGUES) }
    SettingRow(
        OwnTVIcon.STAR, stringResource(R.string.sport_my_teams),
        followed.map { names[it] ?: it.substringAfter('|') }.sorted().joinToString(", ").ifBlank { teamsLine },
        SettingValue.Opens(followed.size.toString()),
    ) { open(SportSettingsPage.TEAMS) }
    SettingRow(OwnTVIcon.HOME, stringResource(R.string.sport_home_row_setting), stringResource(R.string.sport_home_row_setting_description), SettingValue.Switch(homeRow)) {
        vm.setHubVisible(!homeRow)
    }
    SettingRow(OwnTVIcon.PHONE, stringResource(R.string.sport_manage_on_phone), stringResource(R.string.sport_manage_on_phone_description), SettingValue.Opens(null), onClick = onPhone)

    StageSettingsHeading(stringResource(R.string.sport_settings_watching), 3)
    val langLabel = when {
        preferred.isEmpty() -> stringResource(R.string.sport_language_all)
        else -> SportLanguages.order.filter { it in preferred }.map { stringResource(it.labelRes) }.joinToString(", ")
    }
    val hiddenLine = if (hidden.isEmpty()) stringResource(R.string.sport_language_subtitle) else {
        stringResource(R.string.sport_languages_hidden_line, SportLanguages.order.filter { it in hidden }.map { stringResource(it.labelRes) }.joinToString(", "))
    }
    SettingRow(OwnTVIcon.LANGUAGE, stringResource(R.string.sport_settings_languages), hiddenLine, SettingValue.Opens(langLabel)) { open(SportSettingsPage.LANGUAGES) }
    val chosen = playlists.filter { it.id in chosenLists }
    SettingRow(
        OwnTVIcon.PLAYLIST, stringResource(R.string.sport_playlists_heading), null,
        SettingValue.Opens(if (chosen.isEmpty()) stringResource(R.string.sport_playlists_all) else chosen.joinToString(", ") { it.name }),
    ) { open(SportSettingsPage.PLAYLISTS) }
    SettingRow(OwnTVIcon.SORT, stringResource(R.string.sport_strict_filter), stringResource(R.string.sport_strict_description), SettingValue.Switch(strict)) {
        vm.toggleStrictChannels()
    }

    StageSettingsHeading(stringResource(R.string.sport_settings_data), 4)
    val liveLabel = when {
        liveSeconds <= 0 -> stringResource(R.string.sport_option_off)
        liveSeconds >= 120 && liveSeconds % 60 == 0 -> stringResource(R.string.sport_option_minutes, liveSeconds / 60)
        else -> stringResource(R.string.sport_option_seconds, liveSeconds)
    }
    SettingRow(
        OwnTVIcon.CLOCK, stringResource(R.string.sport_live_refresh), stringResource(R.string.sport_live_refresh_description),
        SettingValue.Stepper(liveLabel), onStep = { vm.stepLiveRefresh(it) },
    ) { vm.stepLiveRefresh(1) }
    val rebuildLabel = if (rebuildMinutes <= 0) stringResource(R.string.sport_option_off) else stringResource(R.string.sport_option_minutes, rebuildMinutes)
    SettingRow(
        OwnTVIcon.REFRESH, stringResource(R.string.sport_auto_rebuild), stringResource(R.string.sport_auto_rebuild_description),
        SettingValue.Stepper(rebuildLabel), onStep = { vm.stepAutoRebuild(it) },
    ) { vm.stepAutoRebuild(1) }
    SettingRow(
        OwnTVIcon.REFRESH, stringResource(R.string.sport_rebuild_now), stringResource(R.string.sport_rebuild_now_description),
        SettingValue.Action(stringResource(if (rebuilt) R.string.sport_rebuild_done else R.string.sport_rebuild_action)),
    ) {
        vm.refreshAll()
        vm.rebuildNow()
        rebuilt = true
    }
    SettingRow(OwnTVIcon.INFO, stringResource(R.string.sport_about), stringResource(R.string.sport_about_summary), SettingValue.Opens(null)) { open(SportSettingsPage.ABOUT) }
}

/** One reorderable list in Sports and leagues: the sports, or one sport's chosen leagues. */
private class MoveList(val title: String, val keys: List<String>, val names: List<String>, val start: Int, val commit: (List<String>) -> Unit)

/**
 * Sports and leagues, two panes: a league search and the sports on the left, each with how many of
 * its leagues are chosen; the focused sport's leagues (or the search's matches) on the right, each a
 * switch, the chosen ones first in the user's order. OK or ▶ on a sport moves into its leagues. Hold OK
 * on a sport or a chosen league opens the Move window ([MovePopup]). That order is the hub's and Home's.
 */
@Composable
private fun LeaguePicker(vm: SportsViewModel, rowsFocus: FocusRequester, modifier: Modifier) {
    val active by vm.activeLeagues.collectAsStateWithLifecycle()
    val sportOrder by vm.sportOrder.collectAsStateWithLifecycle()
    val rail by vm.leagues.collectAsStateWithLifecycle()
    val sports = sportOrder.filter { g -> SportsLeagues.any { it.sportGroup == g } }
    var selected by rememberSaveable { mutableStateOf(sports.first()) }
    var query by rememberSaveable { mutableStateOf("") }
    val leaguesFocus = remember { FocusRequester() }
    val sportFocus = remember { SportGroup.entries.associateWith { FocusRequester() } }
    // The pane is ordered once per sport (and after a Move), not on every switch: a league that jumped
    // to the top when switched on took the focus with it.
    var orderVersion by remember { mutableStateOf(0) }
    var moving by remember { mutableStateOf<MoveList?>(null) }
    val warning = stringResource(R.string.sport_load_time_warning)
    val holdOk = stringResource(R.string.content_key_hold_ok)
    val move = stringResource(R.string.content_move)
    val back = stringResource(R.string.common_back) to stringResource(R.string.common_nav_settings)
    val railOrder = rail.map { it.league.id }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.mpx)) {
        Column(
            Modifier.width(420.mpx).fillMaxHeight().focusRequester(rowsFocus).focusGroup()
                .verticalScroll(rememberScrollState()).padding(bottom = 40.mpx),
            verticalArrangement = Arrangement.spacedBy(6.mpx),
        ) {
            val searchTitle = stringResource(R.string.sport_settings_search_leagues)
            StageFieldRow(
                icon = OwnTVIcon.SEARCH, label = searchTitle, value = query, onValueChange = { query = it },
                help = settingHelp(null, searchTitle, null, null, pinnable = false), placeholder = searchTitle,
            )
            sports.forEach { s ->
                key(s) {
                    val all = SportsLeagues.filter { it.sportGroup == s }
                    val chosen = all.count { it.id in active }
                    val label = sportLabel(s)
                    val value = SettingValue.Opens(chosen.takeIf { it > 0 }?.toString())
                    val help = settingHelp(null, label, warning, value, pinnable = false).let { h ->
                        h.copy(hints = listOf(stringResource(R.string.common_ok) to stringResource(R.string.settings_key_open), holdOk to move, back))
                    }
                    val names = sports.map { sportLabel(it) }
                    StageSettingRow(
                        icon = OwnTVIcon.SPORT, title = label, desc = null, value = value,
                        onClick = {
                            query = ""
                            runCatching { leaguesFocus.requestFocus() }
                        },
                        onLongClick = {
                            moving = MoveList(label, sports.map { it.name }, names, sports.indexOf(s)) { keys ->
                                vm.saveSportOrder(keys.mapNotNull { k -> SportGroup.entries.firstOrNull { it.name == k } })
                            }
                        },
                        help = help,
                        modifier = Modifier.focusRequester(sportFocus.getValue(s)).onFocusChanged { if (it.isFocused) selected = s },
                    )
                }
            }
        }
        Column(
            // ◀ from a league returns to the sport it belongs to, not the nearest row.
            Modifier.weight(1f).fillMaxHeight().focusRequester(leaguesFocus)
                .onPreviewKeyEvent { e ->
                    val left = e.key == Key.DirectionLeft
                    if (left && e.type == KeyEventType.KeyDown) runCatching { sportFocus.getValue(selected).requestFocus() }
                    left
                }
                .focusGroup()
                .verticalScroll(rememberScrollState()).padding(bottom = 40.mpx),
            verticalArrangement = Arrangement.spacedBy(6.mpx),
        ) {
            val q = query.trim()
            val leagueNames = SportsLeagues.associate { it.id to stringResource(it.labelRes) }
            val sportNames = SportGroup.entries.associateWith { sportLabel(it) }
            // A search lists every match across the sports; otherwise the focused sport's leagues.
            val all = if (q.isNotEmpty()) {
                SportsLeagues.filter { leagueNames.getValue(it.id).contains(q, ignoreCase = true) || sportNames.getValue(it.sportGroup).contains(q, ignoreCase = true) }
            } else {
                SportsLeagues.filter { it.sportGroup == selected }
            }
            val chosenHere = railOrder.mapNotNull { id -> all.firstOrNull { it.id == id } }
            val ordered = remember(selected, q, orderVersion) { chosenHere + all.filter { it !in chosenHere } }
            if (ordered.isEmpty()) StageSettingsNote(stringResource(R.string.sport_settings_no_leagues), null)
            ordered.forEach { l ->
                key(l.id) {
                    val name = leagueNames.getValue(l.id)
                    val on = l.id in active
                    val value = SettingValue.Switch(on)
                    val desc = if (q.isNotEmpty()) sportNames.getValue(l.sportGroup) else null
                    val help = settingHelp(null, name, desc ?: warning, value, pinnable = false).let { h ->
                        if (on) h.copy(hints = h.hints.dropLast(1) + (holdOk to move) + back) else h
                    }
                    StageSettingRow(
                        icon = OwnTVIcon.SPORT, title = name, desc = desc, value = value,
                        onClick = { vm.toggleLeagueFollowed(l.id) },
                        // Only a chosen league has a place in the order, and only with another to swap with.
                        // Never null: switching between a plain and a long-click handler drops the focus.
                        onLongClick = {
                            val group = chosenHere.filter { it.sportGroup == l.sportGroup }
                            if (on && group.size > 1) {
                                moving = MoveList(name, group.map { it.id }, group.map { leagueNames.getValue(it.id) }, group.indexOf(l)) { keys ->
                                    vm.saveLeagueOrderWithin(keys)
                                }
                            }
                        },
                        help = help,
                    )
                }
            }
        }
    }
    moving?.let { list -> MovePopup(list, move, onDone = { moving = null; orderVersion++ }, onCancel = { moving = null }) }
}

/**
 * The Move window of Sports and leagues, in Stage form: ▲ ▼ choose a row, OK picks it up and puts it
 * down, so several can move in one go; only Save or Cancel closes it.
 */
@Composable
private fun MovePopup(list: MoveList, title: String, onDone: () -> Unit, onCancel: () -> Unit) {
    var order by remember { mutableStateOf(list.keys.indices.toList()) }
    var picked by remember { mutableStateOf<Int?>(list.start) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    val accent = stageAccent.accent
    StagePopup(
        onDismiss = onCancel, title = title, body = stringResource(R.string.sport_move_instructions), width = 760.mpx, scroll = false,
        buttons = {
            StageButton(stringResource(R.string.common_cancel), onClick = onCancel, height = 56.mpx, textSize = 19)
            StageButton(
                stringResource(R.string.common_save), height = 56.mpx, textSize = 19, tinted = true,
                onClick = { list.commit(order.map { list.keys[it] }); onDone() },
            )
        },
    ) {
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
            items(order.size, key = { order[it] }) { pos ->
                val item = order[pos]
                val carried = picked == item
                StagePopupOption(
                    title = list.names[item],
                    chosen = carried,
                    onClick = { picked = if (carried) null else item },
                    // A carried row moves with ▲ ▼; its key keeps the focus on it as it goes.
                    modifier = (if (item == list.start) Modifier.focusRequester(first) else Modifier).onPreviewKeyEvent { e ->
                        if (!carried || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val to = when (e.key) {
                            Key.DirectionUp -> pos - 1
                            Key.DirectionDown -> pos + 1
                            else -> return@onPreviewKeyEvent false
                        }
                        if (to in order.indices) order = order.toMutableList().apply { add(to, removeAt(pos)) }
                        true
                    },
                    leading = { _ -> StagePopupIcon(OwnTVIcon.MOVE, if (carried) accent else StageColors.Dim) },
                )
            }
        }
    }
}

/**
 * My teams: a search over the chosen leagues' teams (ESPN's teams endpoint; SofaScore/TheSportsDB
 * leagues from their boards), OK follows or stops following; then the followed teams.
 */
@Composable
private fun TeamRows(vm: SportsViewModel) {
    val followed by vm.followedTeams.collectAsStateWithLifecycle()
    val names by vm.teamNames.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    DisposableEffect(Unit) {
        vm.resetTeamIndex()
        onDispose { }
    }
    val searchTitle = stringResource(R.string.sport_search_teams)
    StageFieldRow(
        icon = OwnTVIcon.SEARCH, label = searchTitle, value = query, onValueChange = { query = it },
        help = settingHelp(null, searchTitle, stringResource(R.string.sport_search_teams_description), null, pinnable = false), placeholder = searchTitle,
    )
    val found by produceState(emptyList<SportsTeamSearch.Hit>(), query) { value = vm.searchTeams(query) }
    if (SportsTeamSearch.searchable(query) && found.isEmpty()) StageSettingsNote(stringResource(R.string.sport_search_teams_none), null)
    found.forEach { hit ->
        val on = hit.key in followed
        SettingRow(OwnTVIcon.STAR, hit.team.name, leagueLabel(hit.leagueId), SettingValue.Switch(on)) {
            vm.toggleTeamFollowed(hit.leagueId, hit.team.abbreviation, hit.team.name)
        }
    }
    if (followed.isNotEmpty()) {
        StageSettingsHeading(stringResource(R.string.sport_my_teams), followed.size)
        followed.sortedBy { (names[it] ?: it).lowercase() }.forEach { key ->
            val leagueId = key.substringBefore('|')
            SettingRow(OwnTVIcon.STAR, names[key] ?: key.substringAfter('|'), leagueLabel(leagueId), SettingValue.Switch(true)) { vm.unfollowTeam(key) }
        }
    } else if (!SportsTeamSearch.searchable(query)) {
        StageSettingsNote(stringResource(R.string.sport_my_teams_empty), stringResource(R.string.sport_my_teams_description))
    }
}

/** The channel languages: the ones shown first (or all), then the ones never shown (v2.2.0). */
@Composable
private fun LanguageRows(vm: SportsViewModel) {
    val preferred by vm.preferredLanguages.collectAsStateWithLifecycle()
    val hidden by vm.hiddenLanguages.collectAsStateWithLifecycle()
    SettingRow(OwnTVIcon.LANGUAGE, stringResource(R.string.sport_language_all), null, SettingValue.Switch(preferred.isEmpty())) { vm.setPreferredLanguages(emptySet()) }
    SportLanguages.order.filter { it !in hidden }.forEach { language ->
        SettingRow(OwnTVIcon.LANGUAGE, stringResource(language.labelRes), null, SettingValue.Switch(language in preferred)) {
            vm.setPreferredLanguages(toggleLanguage(preferred, language))
        }
    }
    StageSettingsHeading(stringResource(R.string.sport_language_hide), hidden.size)
    SportLanguages.order.forEach { language ->
        SettingRow(OwnTVIcon.EYE_OFF, stringResource(language.labelRes), null, SettingValue.Switch(language in hidden)) { vm.toggleLanguageHidden(language) }
    }
}

/** The playlist filter: All playlists, or the chosen ones (this device's live playlists). */
@Composable
private fun PlaylistRows(vm: SportsViewModel) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val chosen by vm.channelPlaylists.collectAsStateWithLifecycle()
    SettingRow(OwnTVIcon.PLAYLIST, stringResource(R.string.sport_playlists_all), null, SettingValue.Switch(chosen.isEmpty())) { vm.clearPlaylistFilter() }
    playlists.forEach { p ->
        SettingRow(OwnTVIcon.PLAYLIST, p.name, null, SettingValue.Switch(p.id in chosen)) { vm.togglePlaylist(p.id) }
    }
}

/**
 * "Manage on phone": serves the pick list (leagues, the chosen leagues' teams and athletes) to a phone
 * on the same Wi-Fi and shows the companion QR + PIN. Closes itself once the selection was applied
 * (the server stops a moment later) or on Back.
 */
@Composable
private fun PhonePicker(vm: SportsViewModel, onDismiss: () -> Unit) {
    val active by vm.activeLeagues.collectAsStateWithLifecycle()
    val state by vm.remoteState.collectAsStateWithLifecycle()
    val title = stringResource(R.string.sport_phone_title)
    val teamGroup = stringResource(R.string.sport_phone_teams)
    val playerGroup = stringResource(R.string.sport_phone_players)
    // The phone page orders its groups by first appearance, so the items go in sport order.
    val items = SportsLeagues.sortedBy { it.sportGroup.ordinal }.map { league ->
        CompanionSelectItem(id = league.id, label = stringResource(league.labelRes), group = stringResource(league.sportGroup.labelRes), selected = league.id in active)
    }
    val labels = SportsLeagues.associate { it.id to stringResource(it.labelRes) }
    LaunchedEffect(Unit) { vm.startPhonePicker(CompanionLink.DEFAULT_PORT, title, items, labels, teamGroup, playerGroup) }
    val close = {
        vm.stopPhonePicker()
        onDismiss()
    }
    androidx.activity.compose.BackHandler { close() }
    var sawListening by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            is CompanionServerState.Listening -> sawListening = true
            CompanionServerState.Idle -> if (sawListening) onDismiss()
            else -> Unit
        }
    }
    OwnTVPopup(onDismissRequest = close, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier.padding(start = 560.mpx, top = 150.mpx, bottom = 24.mpx).width(720.mpx).trapAllFocusExit().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(20.mpx),
            ) {
                Text(stringResource(R.string.sport_manage_on_phone), style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StageCompanionStatus(state)
            }
        }
    }
}
