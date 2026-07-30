package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.SyncCounts
import tv.own.owntv.core.sync.SyncPhase
import tv.own.owntv.core.sync.SyncProgressCounts
import tv.own.owntv.core.sync.SyncProgressDisplay
import tv.own.owntv.core.sync.SyncProgressPhase
import tv.own.owntv.core.sync.SyncWarning
import tv.own.owntv.core.sync.SyncWarningKind

/** Render import counts at the Compose boundary; SyncCounts itself remains semantic data. */
@Composable
fun SyncCounts.breakdownText(includeEpg: Boolean = false): String {
    val parts = buildList {
        if (channels > 0) add(pluralStringResource(R.plurals.sync_count_channels, channels, channels))
        if (movies > 0) add(pluralStringResource(R.plurals.sync_count_movies, movies, movies))
        if (series > 0) add(pluralStringResource(R.plurals.sync_count_series, series, series))
        if (includeEpg && epg > 0) add(pluralStringResource(R.plurals.sync_count_epg, epg, epg))
    }
    return parts.joinToString(stringResource(R.string.sync_counts_separator))
}

@Composable
fun SyncCounts.summaryText(includeEpg: Boolean = false): String {
    val breakdown = breakdownText(includeEpg)
    return if (breakdown.isBlank()) stringResource(R.string.sync_counts_success)
    else stringResource(R.string.sync_counts_synced, breakdown)
}

@Composable
fun SyncProgressCounts.displayText(): String {
    val parts = buildList {
        if (liveActive && live > 0) add(pluralStringResource(R.plurals.sync_count_channels, live, live))
        if (moviesActive && movies > 0) add(pluralStringResource(R.plurals.sync_count_movies, movies, movies))
        if (seriesActive && series > 0) add(pluralStringResource(R.plurals.sync_count_series, series, series))
    }
    return parts.joinToString(stringResource(R.string.sync_counts_separator))
}

@Composable
fun SyncProgressDisplay.primaryText(): String = when (phase) {
    SyncProgressPhase.PREPARING -> stringResource(R.string.sync_progress_preparing)
    SyncProgressPhase.CONNECTING -> stringResource(R.string.sync_progress_connecting)
    SyncProgressPhase.SYNCING -> counts?.displayText().orEmpty().ifBlank { stringResource(R.string.sync_progress_preparing) }
}

@Composable
fun SyncProgressDisplay.detailText(): String = when (phase) {
    SyncProgressPhase.SYNCING -> stringResource(R.string.sync_progress_syncing)
    SyncProgressPhase.PREPARING, SyncProgressPhase.CONNECTING -> stringResource(R.string.sync_progress_connecting)
}

@Composable
fun SyncWarning.labelText(): String = when (phase.trim().uppercase()) {
    SyncPhase.LIVE.name -> stringResource(R.string.sync_phase_live)
    SyncPhase.MOVIES.name -> stringResource(R.string.sync_phase_movies)
    SyncPhase.SERIES.name -> stringResource(R.string.sync_phase_series)
    else -> phase.replaceFirstChar { it.uppercase() }
}

@Composable
fun List<SyncWarning>.warningText(): String? {
    if (isEmpty()) return null
    val rendered = ArrayList<String>(size)
    for (warning in this) {
        rendered += when (val kind = warning.kind) {
            SyncWarningKind.PAGE_FAILURE -> pluralStringResource(
                R.plurals.sync_warning_page_failures,
                warning.count,
                warning.count,
            )
            SyncWarningKind.GENERIC -> if (warning.message.isBlank()) {
                warning.labelText()
            } else {
                stringResource(R.string.sync_warning_phase_error, warning.labelText(), warning.message)
            }
            is SyncWarningKind.CATALOG_SHRINK -> stringResource(
                R.string.sync_warning_catalog_shrink,
                kind.stored,
                kind.percentFewer,
            )
        }
    }
    return stringResource(
        R.string.sync_import_warnings,
        rendered.joinToString(stringResource(R.string.sync_counts_separator)),
    )
}

@Composable
fun SyncContentTypes.remainderText(): String? = if (!hasAny) null else stringResource(R.string.sync_remainder_note)
