package tv.own.owntv.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.R
import tv.own.owntv.core.sync.schedule.ChangeSignal
import tv.own.owntv.core.sync.schedule.ScheduledKind
import tv.own.owntv.core.sync.schedule.SourceCheckState
import tv.own.owntv.core.sync.schedule.SyncTimes
import tv.own.owntv.ui.theme.mpx

/** One sync time in the device's own clock style ("06:30", or "6:30 AM"). */
@Composable
fun syncTimeLabel(minutes: Int): String {
    val context = LocalContext.current
    val m = SyncTimes.normalize(minutes)
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, m / 60)
        set(java.util.Calendar.MINUTE, m % 60)
    }
    val skeleton = if (android.text.format.DateFormat.is24HourFormat(context)) "HHmm" else "hmm"
    return tv.own.owntv.ui.format.formatBestDate(context, skeleton, cal.timeInMillis)
}

@Composable
private fun timesText(times: List<Int>): String =
    if (times.isEmpty()) stringResource(R.string.common_off)
    else times.map { syncTimeLabel(it) }.joinToString(stringResource(R.string.settings_sources_details_separator))

@Composable
fun syncKindLabel(kind: ScheduledKind): String = stringResource(
    when (kind) {
        ScheduledKind.LIVE -> R.string.sport_sync_times_live
        ScheduledKind.EPG -> R.string.sport_sync_times_epg
        ScheduledKind.VOD -> R.string.sport_sync_times_vod
    },
)

/** "Live 00:30 · 06:30 · … · Guide 06:30 · 16:30 · Films & shows 03:00" (v2.5.3). */
@Composable
fun syncTimesSummary(times: Map<ScheduledKind, List<Int>>): String = stringResource(
    R.string.sport_sync_times_summary,
    timesText(times[ScheduledKind.LIVE].orEmpty()),
    timesText(times[ScheduledKind.EPG].orEmpty()),
    timesText(times[ScheduledKind.VOD].orEmpty()),
)

/** When the check last saw a change: the time today, or the date and time before that. */
@Composable
private fun checkWhen(ms: Long?): String {
    if (ms == null) return stringResource(R.string.sport_check_never)
    val context = LocalContext.current
    val today = android.text.format.DateUtils.isToday(ms)
    return if (today) {
        tv.own.owntv.ui.format.formatBestDate(context, if (android.text.format.DateFormat.is24HourFormat(context)) "HHmm" else "hmm", ms)
    } else {
        tv.own.owntv.ui.format.formatBestDateTime(context, "dMMM", ms)
    }
}

/**
 * A source's change-check line (v2.5.3): which signal its server gives and when a change was last
 * seen, e.g. "Change check: ETag ✓ · last change 16:02". Never a URL or a credential.
 */
@Composable
fun changeCheckLine(state: SourceCheckState?, checking: Boolean): String {
    if (checking) return stringResource(R.string.sport_check_running)
    if (state?.signal == null) return stringResource(R.string.sport_check_line_pending)
    val at = checkWhen(state.lastChangeAt)
    return when (state.signal) {
        ChangeSignal.DPTV -> stringResource(R.string.sport_check_line_dptv, at)
        ChangeSignal.ETAG -> stringResource(R.string.sport_check_line_etag, at)
        ChangeSignal.LAST_MODIFIED -> stringResource(R.string.sport_check_line_last_modified, at)
        ChangeSignal.CONTENT_HASH -> stringResource(R.string.sport_check_line_hash, at)
        ChangeSignal.NONE -> stringResource(R.string.sport_check_line_none)
        ChangeSignal.FAILED -> state.failureCode?.takeIf { it > 0 }
            ?.let { stringResource(R.string.sport_check_line_failed, it) }
            ?: stringResource(R.string.sport_check_line_failed_offline)
        null -> stringResource(R.string.sport_check_line_pending)
    }
}

/**
 * Settings › Sync times (v2.5.3): the three kinds, each opening its list of times; a time opens a
 * ◀ ▶ stepper in 30-minute steps, with Remove; the list also offers Add a time and the defaults.
 */
@Composable
fun SyncTimesDialog(vm: SettingsViewModel, onClose: () -> Unit) {
    val all by vm.syncTimes.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf<ScheduledKind?>(null) }
    // The index being edited; -1 adds a new time.
    var editing by remember { mutableStateOf<Int?>(null) }
    val current = kind
    when {
        current == null -> {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
            tv.own.owntv.ui.stage.StagePopup(
                onDismiss = onClose,
                title = stringResource(R.string.sport_sync_times),
                body = stringResource(R.string.sport_sync_times_hint),
                width = 880.mpx,
            ) {
                ScheduledKind.entries.forEachIndexed { i, k ->
                    tv.own.owntv.ui.stage.StagePopupOption(
                        title = syncKindLabel(k),
                        subtitle = timesText(all[k].orEmpty()),
                        onClick = { kind = k },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
        editing == null -> {
            val times = all[current].orEmpty()
            val first = remember(current) { FocusRequester() }
            LaunchedEffect(current) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
            tv.own.owntv.ui.stage.StagePopup(
                onDismiss = { kind = null },
                title = syncKindLabel(current),
                body = stringResource(R.string.sport_sync_times_list_hint),
                width = 760.mpx,
            ) {
                times.forEachIndexed { i, t ->
                    tv.own.owntv.ui.stage.StagePopupOption(
                        title = syncTimeLabel(t),
                        onClick = { editing = i },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
                tv.own.owntv.ui.stage.StagePopupOption(
                    title = stringResource(R.string.sport_sync_times_add),
                    onClick = { editing = -1 },
                    modifier = if (times.isEmpty()) Modifier.focusRequester(first) else Modifier,
                )
                tv.own.owntv.ui.stage.StagePopupOption(
                    title = stringResource(R.string.sport_sync_times_reset),
                    subtitle = timesText(SyncTimes.defaults(current)),
                    onClick = { vm.setSyncTimes(current, SyncTimes.defaults(current)) },
                )
            }
        }
        else -> {
            val times = all[current].orEmpty()
            val index = editing ?: -1
            val start = times.getOrNull(index) ?: ((times.lastOrNull() ?: (SyncTimes.defaults(current).firstOrNull() ?: 0)) + SyncTimes.STEP_MINUTES)
            var value by remember(current, index) { mutableIntStateOf(SyncTimes.normalize(start)) }
            val save = {
                val next = times.toMutableList()
                if (index in next.indices) next[index] = value else next.add(value)
                vm.setSyncTimes(current, SyncTimes.clean(next))
                editing = null
            }
            tv.own.owntv.ui.stage.StagePopup(
                onDismiss = { editing = null },
                title = syncKindLabel(current),
                width = 760.mpx,
                buttons = if (index in times.indices) {
                    {
                        tv.own.owntv.ui.stage.StageButton(
                            stringResource(R.string.sport_sync_times_remove),
                            onClick = {
                                vm.setSyncTimes(current, times.filterIndexed { i, _ -> i != index })
                                editing = null
                            },
                            height = 52.mpx, textSize = 18,
                        )
                    }
                } else {
                    null
                },
            ) {
                PanelStepper(
                    value = syncTimeLabel(value),
                    onStep = { d -> value = SyncTimes.normalize(value + d * SyncTimes.STEP_MINUTES) },
                    onReset = null,
                    onDone = save,
                    confirm = true,
                )
                Spacer(Modifier.fillMaxWidth().height(4.mpx))
            }
        }
    }
}
