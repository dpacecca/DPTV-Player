package tv.own.owntv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.ui.theme.OwnTVTheme

/** Lightweight indeterminate spinner drawn with Canvas (no Material dependency). */
@Composable
fun OwnTVSpinner(
    modifier: Modifier = Modifier,
    sizeDp: Int = 44,
    color: Color = OwnTVTheme.colors.accent,
) {
    // (OwnTV) Turns in [SPINNER_STEPS] steps rather than every frame: on a TV each frame redraws the whole
    // window, so a per-frame spinner kept the render thread busy for as long as it showed (a quarter of the
    // app's CPU during an import). The step is read only while drawing: one redraw per step, no recomposition.
    val step = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SPINNER_TURN_MS / SPINNER_STEPS)
            step.intValue = (step.intValue + 1) % SPINNER_STEPS
        }
    }
    Canvas(modifier = modifier.size(sizeDp.dp)) {
        val d = size.minDimension
        val stroke = d * 0.11f
        drawArc(
            color = color,
            startAngle = step.intValue * (360f / SPINNER_STEPS),
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(d - stroke, d - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** One full turn, as before the steps. */
private const val SPINNER_TURN_MS = 900L

/** Positions per turn. */
private const val SPINNER_STEPS = 16

/** Centered loading state: spinner + message (e.g. "Importing channels…"). */
@Composable
fun LoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OwnTVSpinner()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = OwnTVTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Centered error state: title + message + an optional Retry action. Used for failed loads/syncs. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val colors = OwnTVTheme.colors
    val resolvedTitle = title ?: stringResource(R.string.common_something_went_wrong)
    val resolvedRetryLabel = retryLabel ?: stringResource(R.string.common_retry)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = resolvedTitle, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            OwnTVButton(label = resolvedRetryLabel, onClick = onRetry, icon = OwnTVIcon.HISTORY)
        }
    }
}

/** Centered empty state: icon + title + message + optional call-to-action button. */
@Composable
fun EmptyState(
    icon: OwnTVIcon,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = OwnTVTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OwnTVIcon(icon = icon, tint = colors.textSecondary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            OwnTVButton(label = actionLabel, onClick = onAction, icon = OwnTVIcon.MENU)
        }
    }
}
