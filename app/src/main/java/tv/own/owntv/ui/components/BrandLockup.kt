package tv.own.owntv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * The icon colour the launcher shows right now. Sportio ships a single mark, so this always
 * resolves to the Sportio icon — kept so logo and home-screen icon still change together if the
 * launcher entry ever changes again.
 */
@Composable
fun rememberAppliedIcon(): AppIcon {
    val context = LocalContext.current
    return remember(context) { AppIconSwitcher.applied(context) }
}

/**
 * The Sportio pitch tile (no shadow); at 32 dp and below the simpler small drawing. The mark is
 * part of the brand and no longer follows any accent — [followAccent] is accepted for call-site
 * compatibility only.
 */
@Composable
fun BrandMark(icon: AppIcon, size: Dp, modifier: Modifier = Modifier, followAccent: Boolean = true) {
    val small = size <= 32.dp
    Image(
        painterResource(if (small) R.drawable.sportio_mark_small else R.drawable.sportio_mark),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/**
 * The DPTV-Player wordmark: "DPTV" in the brand green, "-Player" neutral, set as text so the name
 * stays a string resource. Same box as the old vector wordmark (585 × 160) so layouts are unchanged.
 * [tv]/[own] override the two tones; unspecified means the theme's primary / onSurface.
 */
@Composable
fun Wordmark(width: Dp, modifier: Modifier = Modifier, tv: Color = Color.Unspecified, own: Color = Color.Unspecified) {
    val colors = OwnTVTheme.colors
    val lead = stringResource(R.string.brand_wordmark_lead)
    val tail = stringResource(R.string.brand_wordmark_tail)
    val leadColor = if (tv == Color.Unspecified) colors.primary else tv
    val tailColor = if (own == Color.Unspecified) colors.onSurface else own
    val fontSize = with(LocalDensity.current) { (width * 0.145f).toSp() }
    Box(modifier.width(width).height(width * 160f / 585f), contentAlignment = Alignment.CenterStart) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = leadColor)) { append(lead) }
                withStyle(SpanStyle(color = tailColor)) { append(tail) }
            },
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

val WordmarkCream = Color(0xFFFFF6EE)

/**
 * The lockup: the mark, then the wordmark at 2.75 × the mark's width. Side by side the gap is
 * 0.23 × the mark; [stacked] (the preview pane) puts the mark above with a 0.2 × gap.
 */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    markSize: Int = 36,
    stacked: Boolean = false,
) {
    val icon = rememberAppliedIcon()
    val parts = @Composable {
        BrandMark(icon, markSize.dp)
        Wordmark((markSize * 2.75f).dp)
    }
    if (stacked) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((markSize * 0.2f).dp),
        ) { parts() }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((markSize * 0.23f).dp),
        ) { parts() }
    }
}
