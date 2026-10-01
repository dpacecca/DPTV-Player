package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx
import androidx.compose.ui.focus.onFocusChanged
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.ui.components.DayStepperDialog
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.rememberDialogFocusRestore
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.core.sync.work.EpgSyncState
import tv.own.owntv.core.util.classifySyncFailure
import tv.own.owntv.ui.components.displayText

/**
 * Settings → EPG Sources: standalone XMLTV feeds that fill the guide, independent of playlists.
 * Add (auto-syncs) / Edit / Re-sync / Delete. [startOnAdd] opens the add form immediately (deep-link
 * from the Guide's "Add EPG" button).
 */
@Composable
fun EpgSourcesScreen(onBack: () -> Unit, modifier: Modifier = Modifier, startOnAdd: Boolean = false) {
    val vm: EpgSourcesViewModel = koinViewModel()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val autoRefreshMap by vm.autoRefresh.collectAsStateWithLifecycle()
    val useLogosIds by vm.useLogos.collectAsStateWithLifecycle()
    val deletingIds by vm.deletingIds.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    var editing by remember { mutableStateOf<EpgSource?>(null) }
    var adding by remember { mutableStateOf(startOnAdd) }
    var confirmDelete by remember { mutableStateOf<EpgSource?>(null) }
    val addFocus = remember { FocusRequester() }

    // Per-row focus restore (mirrors ManageSourcesScreen / MoviesScreen): track the row the user is
    // acting on so edit/re-sync/delete returns focus INSIDE the list — same row if it survived, else
    // the nearest neighbour, else the first row, else "Add EPG". Without this the old code always
    // refocused the "Add EPG" button, which is why focus escaped the menu.
    var contextId by remember { mutableStateOf<Long?>(null) }
    var contextIndex by remember { mutableStateOf(-1) }
    val contextFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }

    // Grab focus inside the list (not on "Add EPG") whenever the list view is showing.
    LaunchedEffect(adding, editing, confirmDelete) {
        if (adding || editing != null || confirmDelete != null) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        val targetId = contextId
        if (targetId != null && sources.any { it.id == targetId }) {
            runCatching { contextFocus.requestFocus() }
        } else if (sources.isNotEmpty()) {
            runCatching { firstRowFocus.requestFocus() }
        } else {
            runCatching { addFocus.requestFocus() }
        }
    }

    // When the deleted row vanishes from `sources`, move focus to the nearest surviving neighbour
    // (same index slot, else new last row) instead of letting it escape the menu.
    LaunchedEffect(sources) {
        val targetId = contextId ?: return@LaunchedEffect
        if (sources.any { it.id == targetId }) return@LaunchedEffect
        withFrameNanos { }
        if (sources.isEmpty()) {
            contextId = null; contextIndex = -1
            runCatching { addFocus.requestFocus() }
            return@LaunchedEffect
        }
        val neighbor = sources.getOrNull(contextIndex.coerceAtLeast(0)) ?: sources.last()
        contextId = neighbor.id
        contextIndex = sources.indexOfFirst { it.id == neighbor.id }
        withFrameNanos { }
        runCatching { contextFocus.requestFocus() }
    }

    // Add / edit form.
    if (adding || editing != null) {
        EpgSourceForm(
            initial = editing,
            initialAutoRefresh = editing?.let { autoRefreshMap[it.id] } ?: EpgRefresh.OFF,
            initialUseLogos = editing?.let { it.id in useLogosIds } ?: false,
            loadPlaylistOptions = { vm.playlistEpgOptions() },
            onSave = { name, url, ua, autoRefresh, useLogos ->
                val e = editing
                if (e == null) vm.add(name, url, ua, autoRefresh, useLogos)
                else { vm.update(e, name, url, ua); vm.setAutoRefresh(e, autoRefresh); vm.setUseLogos(e, useLogos) }
                adding = false; editing = null
            },
            onCancel = { adding = false; editing = null },
            // The form is not redrawn yet (P10B step 4): it keeps the old inset under the top bar.
            modifier = modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp, top = tv.own.owntv.features.shell.components.StageContentTop),
        )
        BackHandler { adding = false; editing = null }
        return
    }

    // P10B-11: the guides as rows; the focused one's Re-sync / Edit / Delete in the panel.
    val actionsFocus = remember { FocusRequester() }
    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_sources)),
        title = stringResource(R.string.settings_epg_sources_title),
        count = "",
        onBack = onBack,
        modifier = modifier,
        toolbar = {
            tv.own.owntv.ui.stage.StageTool(
                stringResource(R.string.content_epg_add), onClick = { adding = true },
                icon = OwnTVIcon.ADD, boxed = true, modifier = Modifier.focusRequester(addFocus),
            )
        },
    ) {
        if (sources.isEmpty()) StageSettingsNote(stringResource(R.string.settings_epg_sources_empty), null)
        sources.forEachIndexed { index, source ->
            val syncState by remember(source.id) { vm.observeSync(source.id) }
                .collectAsStateWithLifecycle(EpgSyncState.Idle)
            EpgRow(
                source = source,
                autoRefresh = autoRefreshMap[source.id] ?: EpgRefresh.OFF,
                counts = { vm.counts(source.id) },
                syncState = syncState,
                deleting = source.id in deletingIds,
                // Bind contextFocus to the acted-on row (restore target), firstRowFocus to row 0.
                rowFocus = when {
                    source.id == contextId -> contextFocus
                    index == 0 -> firstRowFocus
                    else -> null
                },
                actionsFocus = actionsFocus,
                keepPanel = source.id == contextId,
                onFocused = { contextId = source.id; contextIndex = index },
                onResync = { contextId = source.id; contextIndex = index; vm.resync(source) },
                onCancelSync = { contextId = source.id; contextIndex = index; vm.cancelSync(source) },
                onEdit = { contextId = source.id; contextIndex = index; editing = source },
                onDelete = { contextId = source.id; contextIndex = index; confirmDelete = source },
            )
        }
    }

    confirmDelete?.let { s ->
        ConfirmDialog(
            title = stringResource(R.string.settings_epg_sources_delete_title, s.name),
            message = stringResource(R.string.settings_epg_sources_delete_message),
            onConfirm = { vm.delete(s); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun epgRefreshLabel(refresh: EpgRefresh): String =
    if (refresh.mode == EpgAutoRefresh.MANUAL) {
        pluralStringResource(R.plurals.settings_sources_refresh_days, refresh.manualDays, refresh.manualDays)
    } else {
        epgAutoRefreshLabel(refresh.mode)
    }

@Composable
private fun epgAutoRefreshLabel(mode: EpgAutoRefresh): String = stringResource(
    when (mode) {
        EpgAutoRefresh.OFF -> R.string.settings_sources_refresh_off
        EpgAutoRefresh.STARTUP -> R.string.settings_sources_refresh_startup
        EpgAutoRefresh.HOURS_1 -> R.string.settings_epg_refresh_1h
        EpgAutoRefresh.HOURS_3 -> R.string.settings_epg_refresh_3h
        EpgAutoRefresh.HOURS_6 -> R.string.settings_epg_refresh_6h
        EpgAutoRefresh.HOURS_12 -> R.string.settings_epg_refresh_12h
        EpgAutoRefresh.HOURS_24 -> R.string.settings_epg_refresh_24h
        EpgAutoRefresh.HOURS_48 -> R.string.settings_epg_refresh_48h
        EpgAutoRefresh.MANUAL -> R.string.settings_sources_refresh_manual
    },
)

@Composable
private fun EpgRow(
    source: EpgSource,
    autoRefresh: EpgRefresh,
    counts: suspend () -> Triple<Int, Int, Int>,
    syncState: EpgSyncState,
    deleting: Boolean,
    rowFocus: FocusRequester?,
    actionsFocus: FocusRequester,
    keepPanel: Boolean,
    onFocused: () -> Unit,
    onResync: () -> Unit,
    onCancelSync: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val count by produceState<Triple<Int, Int, Int>?>(initialValue = null, source.id, source.lastSyncAt, source.lastError) {
        value = runCatching { counts() }.getOrNull()
    }
    val activeSync = syncState as? EpgSyncState.Syncing
    val syncPercent = activeSync?.let {
        if (it.baseProgrammes > 0 && it.programmes > 0) {
            ((it.programmes.toLong() * 100) / it.baseProgrammes).toInt().coerceAtMost(99)
        } else {
            null
        }
    }
    val subtitle = if (tv.own.owntv.core.repository.EpgRepository.stalkerSourceIdOf(source.url) != null) {
        stringResource(R.string.settings_epg_sources_portal_guide)
    } else {
        source.url
    }
    val catchupNote = count?.third?.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.settings_epg_sources_catchup, it, it)
    }
    val status = when {
        activeSync != null -> when {
            activeSync.programmes > 0 -> stringResource(
                R.string.settings_epg_sources_status_count,
                pluralStringResource(R.plurals.settings_epg_sources_status_count_channels, activeSync.channels, activeSync.channels),
                pluralStringResource(R.plurals.settings_epg_sources_status_count_programmes, activeSync.programmes, activeSync.programmes),
            )
            activeSync.channels > 0 -> pluralStringResource(
                R.plurals.settings_epg_sources_status_count_channels,
                activeSync.channels,
                activeSync.channels,
            )
            else -> stringResource(R.string.settings_epg_sources_connecting)
        }
        source.lastError != null -> stringResource(
            R.string.settings_epg_sources_error,
            classifySyncFailure(source.lastError, online = true).displayText(),
        )
        count != null && count!!.second > 0 -> {
            val counts = stringResource(
                R.string.settings_epg_sources_status_count,
                pluralStringResource(R.plurals.settings_epg_sources_status_count_channels, count!!.first, count!!.first),
                pluralStringResource(R.plurals.settings_epg_sources_status_count_programmes, count!!.second, count!!.second),
            )
            if (catchupNote != null) stringResource(R.string.settings_epg_sources_status_count_with_catchup, counts, catchupNote)
            else counts
        }
        source.lastSyncAt != null -> catchupNote?.let {
            stringResource(R.string.settings_epg_sources_status_synced_with_catchup, it)
        } ?: stringResource(R.string.settings_epg_sources_status_synced)
        else -> stringResource(R.string.settings_epg_sources_not_synced)
    }
    val failed = source.lastError != null && activeSync == null
    val badge = when {
        deleting -> stringResource(R.string.settings_epg_sources_deleting)
        activeSync != null -> syncPercent?.let { stringResource(R.string.settings_epg_sources_syncing_percent, it) }
            ?: stringResource(R.string.settings_epg_sources_syncing_label)
        autoRefresh.mode != EpgAutoRefresh.OFF -> stringResource(R.string.settings_sources_auto_refresh, epgRefreshLabel(autoRefresh))
        else -> null
    }
    val back = rowFocus ?: remember { FocusRequester() }
    // While the guide data is being deleted the actions go: the row is on its way out.
    val actions = if (deleting) emptyList() else listOf(
        StageAction(
            OwnTVIcon.REFRESH,
            stringResource(if (syncState.isActive) R.string.common_cancel else R.string.settings_sources_resync),
            if (syncState.isActive) onCancelSync else onResync,
        ),
        StageAction(OwnTVIcon.PENCIL, stringResource(R.string.common_edit), onEdit),
        StageAction(OwnTVIcon.TRASH, stringResource(R.string.common_delete), onDelete, danger = true),
    )
    StageSettingRow(
        icon = OwnTVIcon.EPG,
        title = source.name,
        // A failed guide shows its error in red on the right, so the line under the name is its address.
        desc = if (failed) subtitle else status,
        value = when {
            failed -> SettingValue.Custom {
                Text(status, style = tv.own.owntv.ui.theme.stageText(18, 700), color = tv.own.owntv.ui.theme.StageColors.Danger, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.mpx))
            }
            badge != null -> SettingValue.Action(badge)
            else -> null
        },
        onClick = { if (actions.isNotEmpty()) runCatching { actionsFocus.requestFocus() } },
        keepPanel = keepPanel,
        help = SettingHelp(
            title = source.name,
            // The address on one line, the counts under it (owner).
            text = subtitle,
            hints = listOf(
                "▶" to stringResource(R.string.settings_key_actions),
                stringResource(R.string.common_back) to stringResource(R.string.common_nav_settings),
            ),
            extra = {
                Text(status, style = tv.own.owntv.ui.theme.stageText(17, 500), color = if (failed) tv.own.owntv.ui.theme.StageColors.Danger else tv.own.owntv.ui.theme.StageColors.Muted)
                if (actions.isNotEmpty()) StageActionColumn(actions, back, actionsFocus)
            },
        ),
        modifier = Modifier
            .focusRequester(back)
            .focusProperties { if (actions.isNotEmpty()) right = actionsFocus }
            .onFocusChanged { if (it.isFocused) onFocused() },
    )
}

@Composable
internal fun EpgSourceForm(
    initial: EpgSource?,
    initialAutoRefresh: EpgRefresh,
    initialUseLogos: Boolean,
    loadPlaylistOptions: suspend () -> List<EpgSourcesViewModel.PlaylistEpg>,
    onSave: (name: String, url: String, userAgent: String?, autoRefresh: EpgRefresh, useLogos: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var ua by remember { mutableStateOf(initial?.userAgent ?: "") }
    var autoRefresh by remember { mutableStateOf(initialAutoRefresh) }
    var useLogos by remember { mutableStateOf(initialUseLogos) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showAutoRefreshPicker by remember { mutableStateOf(false) }
    var showManualDays by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }
    BackHandler { onCancel() }

    // Both pickers here open from controls further down the form, so closing one used to drop focus
    // back on the Name field at the top and scroll the form with it.
    val scrollState = rememberScrollState()
    val dialogFocus = rememberDialogFocusRestore(showPlaylistPicker || showAutoRefreshPicker, scrollState)
    val autoRefreshRowFocus = remember { FocusRequester() }

    Column(
        modifier = modifier.fillMaxSize().roundedPanel()
            .verticalScroll(scrollState) // scroll so lower fields/buttons stay reachable on small screens / large zoom
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Text(stringResource(if (initial == null) R.string.settings_epg_sources_add else R.string.settings_epg_sources_edit), style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
        Spacer(Modifier.height(20.dp))
        OwnTVTextField(name, { name = it }, label = stringResource(R.string.settings_epg_sources_name), placeholder = stringResource(R.string.settings_epg_sources_name_hint), modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp).focusRequester(firstFocus))
        Spacer(Modifier.height(14.dp))
        val fillButtonFocus = remember { FocusRequester() }
        OwnTVTextField(url, { url = it }, label = stringResource(R.string.settings_epg_sources_url), placeholder = stringResource(R.string.settings_epg_sources_url_hint), modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp).focusProperties { down = fillButtonFocus })
        Spacer(Modifier.height(8.dp))
        OwnTVButton(stringResource(R.string.settings_epg_sources_fill_playlist), onClick = { dialogFocus.value = fillButtonFocus; showPlaylistPicker = true }, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.PLAYLIST, modifier = Modifier.focusRequester(fillButtonFocus))
        Spacer(Modifier.height(14.dp))
        OwnTVTextField(ua, { ua = it }, label = stringResource(R.string.settings_epg_sources_user_agent), placeholder = stringResource(R.string.settings_epg_sources_user_agent_hint), modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp))

        Spacer(Modifier.height(14.dp))
        // Auto-refresh dropdown — same Off/Startup/staleness-threshold semantics as playlist sources.
        EpgAutoRefreshRow(selected = autoRefresh, modifier = Modifier.focusRequester(autoRefreshRowFocus)) {
            dialogFocus.value = autoRefreshRowFocus
            showAutoRefreshPicker = true
        }

        Spacer(Modifier.height(10.dp))
        // Per-feed logo override: this guide's <icon src> replaces the playlist's channel logos.
        EpgUseLogosRow(enabled = useLogos) { useLogos = !useLogos }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OwnTVButton(stringResource(R.string.common_cancel), onClick = onCancel, style = OwnTVButtonStyle.SECONDARY)
            OwnTVButton(stringResource(if (initial == null) R.string.settings_epg_sources_add_sync else R.string.settings_epg_sources_save_sync), onClick = { onSave(name, url, ua, autoRefresh, useLogos) }, enabled = url.isNotBlank())
        }
    }

    if (showPlaylistPicker) {
        PlaylistEpgPicker(
            load = loadPlaylistOptions,
            onPick = { opt ->
                showPlaylistPicker = false
                if (tv.own.owntv.core.repository.EpgRepository.stalkerSourceIdOf(opt.url) != null) {
                    // Nothing to fill in: a portal guide is fetched through the playlist's own session,
                    // so showing the user an internal marker in a URL box would be worse than useless.
                    onSave(opt.name, opt.url, ua, autoRefresh, useLogos)
                } else {
                    if (name.isBlank()) name = opt.name
                    url = opt.url
                }
            },
            onDismiss = { showPlaylistPicker = false },
        )
    }
    if (showAutoRefreshPicker) {
        PickerDialog(
            title = stringResource(R.string.settings_epg_sources_auto_refresh_title),
            options = EpgAutoRefresh.entries.map { it.name to epgAutoRefreshLabel(it) },
            selected = autoRefresh.mode.name,
            onSelect = { value ->
                val mode = runCatching { EpgAutoRefresh.valueOf(value) }.getOrDefault(EpgAutoRefresh.OFF)
                showAutoRefreshPicker = false
                // "Every N days" needs the N, so it opens the same stepper the playlist picker uses.
                if (mode == EpgAutoRefresh.MANUAL) showManualDays = true else autoRefresh = EpgRefresh(mode)
            },
            onDismiss = { showAutoRefreshPicker = false },
        )
    }
    if (showManualDays) {
        DayStepperDialog(
            title = stringResource(R.string.settings_sources_refresh_days_title),
            hint = stringResource(R.string.settings_sources_refresh_days_hint),
            initialDays = autoRefresh.manualDays,
            minDays = PlaylistRefresh.MIN_MANUAL_DAYS,
            maxDays = PlaylistRefresh.MAX_MANUAL_DAYS,
            label = { days -> pluralStringResource(R.plurals.settings_sources_refresh_days, days, days) },
            onConfirm = { autoRefresh = EpgRefresh(EpgAutoRefresh.MANUAL, it); showManualDays = false },
            onDismiss = { showManualDays = false },
        )
    }
}

/** Per-EPG-source toggle: use this feed's own channel logos instead of the playlist's. */
@Composable
private fun EpgUseLogosRow(enabled: Boolean, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        shape = RoundedCornerShape(14.dp),
        surface = GlassSurface.CARDS,
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_epg_sources_use_logos), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Text(
                    stringResource(R.string.settings_epg_sources_logos_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(stringResource(if (enabled) R.string.settings_epg_sources_on else R.string.settings_epg_sources_off), style = MaterialTheme.typography.titleMedium, color = if (enabled) colors.primary else colors.onSurfaceVariant)
        }
    }
}

/** A focusable settings row showing the current EPG auto-refresh selection; opens a picker on click. */
@Composable
private fun EpgAutoRefreshRow(selected: EpgRefresh, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().widthIn(max = 680.dp),
        shape = RoundedCornerShape(14.dp),
        surface = GlassSurface.CARDS,
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_epg_sources_auto_refresh_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Text(
                    stringResource(R.string.settings_epg_sources_auto_refresh_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                epgRefreshLabel(selected),
                style = MaterialTheme.typography.titleMedium,
                color = colors.primary,
            )
        }
    }
}

@Composable
private fun PlaylistEpgPicker(
    load: suspend () -> List<EpgSourcesViewModel.PlaylistEpg>,
    onPick: (EpgSourcesViewModel.PlaylistEpg) -> Unit,
    onDismiss: () -> Unit,
) {
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
    val colors = OwnTVTheme.colors
    val options by produceState<List<EpgSourcesViewModel.PlaylistEpg>?>(initialValue = null) { value = runCatching { load() }.getOrDefault(emptyList()) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(options) { if (!options.isNullOrEmpty()) runCatching { firstFocus.requestFocus() } }
    BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(), contentAlignment = Alignment.Center) {
        Column(Modifier.dialogPanel(width = 560.dp, corner = 20.dp, padding = 24.dp, scroll = false)) {
            Text(stringResource(R.string.settings_epg_sources_fill_playlist), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(Modifier.height(14.dp))
            val opts = options
            when {
                opts == null -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { OwnTVSpinner(sizeDp = 28) }
                opts.isEmpty() -> Text(stringResource(R.string.settings_epg_sources_none_playlist), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxWidth().height(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(opts) { opt ->
                        FocusableSurface(
                            onClick = { onPick(opt) },
                            modifier = if (opt == opts.first()) Modifier.fillMaxWidth().focusRequester(firstFocus) else Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            contentAlignment = Alignment.CenterStart,
                            surface = GlassSurface.DIALOGS,
                        ) { _ ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(opt.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                                // A portal guide has no address to show — say what it is instead.
                                val subtitle = if (tv.own.owntv.core.repository.EpgRepository.stalkerSourceIdOf(opt.url) != null) {
                                    stringResource(R.string.settings_epg_sources_portal_guide)
                                } else {
                                    opt.url
                                }
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OwnTVButton(stringResource(R.string.settings_close), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY)
            }
        }
    }
    }
}
