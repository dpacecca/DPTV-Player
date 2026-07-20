package tv.own.owntv.features.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.core.sync.EpgActivityTracker
import tv.own.owntv.core.sync.SyncActivityTracker
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import tv.own.owntv.core.sync.SyncResult
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * Unobtrusive "sync running" pill (shell overlay, bottom middle). Reflects BOTH catalog syncs (a
 * backgrounded first import, the movies/series remainder worker, auto refresh — all funnel through
 * SyncManager → [SyncActivityTracker]) AND EPG/guide syncs (manual resync from Settings, auto startup
 * refresh — [EpgSyncWorker] → [EpgActivityTracker]). Semi-transparent, never focusable, renders nothing
 * when no sync is active. The shell hides it during fullscreen playback.
 */
@Composable
fun SyncStatusPill(modifier: Modifier = Modifier) {
    val catalogTracker: SyncActivityTracker = koinInject()
    val epgTracker: EpgActivityTracker = koinInject()
    val activeCatalog by catalogTracker.active.collectAsStateWithLifecycle()
    val activeEpg by epgTracker.active.collectAsStateWithLifecycle()
    val lastCompleted by catalogTracker.lastCompleted.collectAsStateWithLifecycle()

    val colors = OwnTVTheme.colors

    val catalogSync = activeCatalog.values.firstOrNull()
    val epgSync = activeEpg.values.firstOrNull()

    val completedQueue = remember { mutableStateListOf<SyncActivityTracker.CompletedSync>() }
    var currentCompleted by remember { mutableStateOf<SyncActivityTracker.CompletedSync?>(null) }

    LaunchedEffect(lastCompleted) {
        val completed = lastCompleted ?: return@LaunchedEffect
        if (completedQueue.none { it.timestamp == completed.timestamp && it.sourceId == completed.sourceId }) {
            completedQueue.add(completed)
        }
    }

    LaunchedEffect(catalogSync, epgSync, currentCompleted, completedQueue.size) {
        if (catalogSync == null && epgSync == null && currentCompleted == null && completedQueue.isNotEmpty()) {
            currentCompleted = completedQueue.removeAt(0)
        }
    }

    LaunchedEffect(currentCompleted) {
        if (currentCompleted != null) {
            delay(5000)
            currentCompleted = null
        }
    }

    if (catalogSync == null && epgSync == null && currentCompleted == null) return

    Row(
        modifier = modifier
            .padding(bottom = 14.dp)
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceContainerHigh.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            catalogSync != null -> {
                val count = catalogSync.stage?.totalProcessed ?: 0
                val others = activeCatalog.size - 1
                val label = buildString {
                    append("Syncing ")
                    append(catalogSync.sourceName)
                    if (count > 0) append(" · ").append(String.format(java.util.Locale.getDefault(), "%,d", count)).append(" items")
                    if (others > 0) append(" (+$others more)")
                    if (epgSync != null) append(" · EPG too")
                }
                OwnTVSpinner(sizeDp = 14)
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            epgSync != null -> {
                val others = activeEpg.size - 1
                val label = buildString {
                    append("Updating guide · ")
                    append(epgSync.sourceName)
                    if (epgSync.programmes > 0) {
                        append(" · ").append(String.format(java.util.Locale.getDefault(), "%,d", epgSync.programmes)).append(" programmes")
                    }
                    if (others > 0) append(" (+$others more)")
                }
                OwnTVSpinner(sizeDp = 14)
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            currentCompleted != null -> {
                val completed = currentCompleted!!
                val label = androidx.compose.ui.text.buildAnnotatedString {
                    val boldStyle = androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    when (val res = completed.result) {
                        is SyncResult.Success -> {
                            pushStyle(boldStyle)
                            append("Sync complete")
                            pop()
                            append(" · ")
                            append(completed.sourceName)
                            append(" · ")
                            append(res.categoryChangeSummary())
                        }
                        is SyncResult.Failed -> {
                            pushStyle(boldStyle)
                            append("Sync failed")
                            pop()
                            append(" · ")
                            append(completed.sourceName)
                            append(": ")
                            append(res.message)
                        }
                        SyncResult.Cancelled -> {
                            pushStyle(boldStyle)
                            append("Sync cancelled")
                            pop()
                            append(" · ")
                            append(completed.sourceName)
                        }
                    }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
